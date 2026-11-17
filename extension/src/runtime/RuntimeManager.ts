import * as vscode from 'vscode'
import { spawn, spawnSync, ChildProcess } from 'node:child_process'
import { createHash, randomBytes, randomUUID } from 'node:crypto'
import * as net from 'node:net'
import * as fs from 'node:fs'
import * as os from 'node:os'
import * as path from 'node:path'
import { pathToFileURL } from 'node:url'
import { cfg, buildProxyArgs } from '../util/config'
import { log, getLog } from '../util/log'
import { EditorBridgeConnection } from '../bridge/EditorBridgeServer'
import { workspaceIdentity } from './workspaceIdentity'
import { AttachedRuntime, AttachmentChangedError, attachProjectRuntime, assertAttachmentCurrent, attachmentRequest, discoverProjectRuntimes, reconnectAttachedWorkspace } from './attachedRuntime'
import { AttachBinding, AttachBindingStore } from './attachBinding'

export type RuntimeState = 'stopped' | 'starting' | 'running' | 'error'

/**
 * A managed connection owns a window-scoped Runtime Domain; an attached connection only borrows
 * a verified project endpoint. Process and manifest mutations are restricted to the managed branch.
 * The extension never requires a globally installed `harnex` npm CLI.
 */
export class RuntimeManager implements vscode.Disposable {
  private child: ChildProcess | undefined
  private state: RuntimeState = 'stopped'
  private baseUrl_ = ''
  private dataDir_ = ''
  private spawned = false
  private userStopped = false
  private restartedOnce = false
  private ensurePromise: Promise<string> | undefined
  private stopPromise: Promise<void> | undefined
  private restartTimer: NodeJS.Timeout | undefined
  private stabilityTimer: NodeJS.Timeout | undefined
  private attached: AttachedRuntime | undefined
  private attachmentHeartbeat: NodeJS.Timeout | undefined
  private connectionGeneration = 0
  private actionGeneration = 0
  private connectionAbort = new AbortController()
  private clientId_ = `vscode-${randomUUID()}`
  private readonly bindingStore: Promise<AttachBindingStore | undefined>
  private initialization: Promise<void> | undefined
  private binding: AttachBinding | undefined
  private connectionMode: 'managed' | 'existing' | undefined
  private lastError_ = ''
  private readonly workspaceSubscription: vscode.Disposable
  private runtimeControlToken: string | undefined
  private runtimeLog: fs.WriteStream | undefined
  private readonly ownerInstanceId = randomUUID()
  readonly runtimeDomainId: string
  readonly runtimeDomainDir: string
  readonly manifestFile: string
  readonly bootTokenFile: string
  private readonly ownerFile: string
  private readonly domainStateFile: string
  private readonly userDataDir: string
  private domainClaimed = false
  private workspaceRoot_ = ''
  private workspaceId_ = ''
  private instanceId_ = ''
  private resolvedRuntime: { java: string; jar: string; bin?: string } | undefined
  private readonly stateEmitter = new vscode.EventEmitter<RuntimeState>()
  readonly onDidChangeState = this.stateEmitter.event

  constructor(private readonly ctx: vscode.ExtensionContext, private readonly editorBridge: EditorBridgeConnection) {
    this.bindingStore = AttachBindingStore.create(ctx)
    this.workspaceSubscription = vscode.workspace.onDidChangeWorkspaceFolders(() => {
      void this.followWorkspace().catch(error => {
        if (error instanceof Error && ['RuntimeConnectionCancelled', 'RuntimeSwitchDeclined', 'AttachmentChangedError'].includes(error.name)) return
        log(`workspace connection failed: ${error instanceof Error ? error.message : error}`)
        if (this.state === 'error') this.revealConnectionFailure()
      })
    })
    const session = vscode.env.sessionId.replace(/[^A-Za-z0-9._-]/g, '-').slice(0, 96)
    // sessionId alone is not documented as window-unique across every VS Code host shape;
    // the Extension Host PID makes concurrent windows unambiguously separate Domains.
    this.runtimeDomainId = `vscode-${session || 'session'}-${process.pid}`
    this.userDataDir = path.resolve(process.env.HARNEX_USER_DATA_DIR?.trim() || path.join(os.homedir(), '.harnex'))
    this.runtimeDomainDir = path.join(this.userDataDir, 'runtime-domains', this.runtimeDomainId)
    this.manifestFile = path.join(this.runtimeDomainDir, 'domain.json')
    this.bootTokenFile = path.join(this.runtimeDomainDir, 'boot-token')
    this.ownerFile = path.join(this.runtimeDomainDir, 'owner.json')
    this.domainStateFile = path.join(this.runtimeDomainDir, 'state.json')
  }

  get currentState(): RuntimeState { return this.state }
  get clientId(): string { return this.clientId_ }
  get lastError(): string { return this.lastError_ }
  get connectionRevision(): number { return this.connectionGeneration }
  get connectionSignal(): AbortSignal { return this.connectionAbort.signal }
  get editorConnectionScope(): string | undefined {
    if (this.state !== 'running') return undefined
    try {
      const folder = vscode.workspace.workspaceFolders?.[0]
      if (!folder || folder.uri.scheme !== 'file' || workspaceIdentity(folder.uri.fsPath).root !== this.workspaceRoot_) return undefined
      if (this.attached) assertAttachmentCurrent(this.attached)
      return `${this.workspaceId_}:${this.instanceId_}`
    } catch { return undefined }
  }
  get baseUrl(): string { return this.baseUrl_ }
  get currentIdentity(): { workspaceId: string; instanceId: string } | undefined {
    return this.workspaceId_ && this.instanceId_
      ? { workspaceId: this.workspaceId_, instanceId: this.instanceId_ } : undefined
  }
  /** Workspace data dir of the runtime we spawned. User credentials never live here. */
  get spawnedDataDir(): string { return this.spawned ? this.dataDir_ : '' }
  get isSpawned(): boolean { return this.spawned }
  get isAttached(): boolean { return Boolean(this.attached) }
  get connectionManifestFile(): string { return this.attached?.file || this.manifestFile }
  get connectionBootTokenFile(): string { return this.attached?.manifest.bootTokenFile || this.bootTokenFile }
  get editorToolset(): string { return this.attached?.toolset || 'vscode' }
  get connectionLabel(): string { return this.attached ? `已连接 ${this.attached.manifest.ownerKind} Runtime` : this.connectionMode === 'managed' ? '独立 Runtime' : '未连接 Runtime' }
  /** Native CLI directory from the same verified companion bundle, if present. */
  get cliBinDir(): string | undefined {
    if (!this.spawned) return undefined
    const runtime = this.resolveRuntime()
    if (!runtime?.bin) return undefined
    const executable = path.join(runtime.bin, process.platform === 'win32' ? 'harnex.exe' : 'harnex')
    return fs.existsSync(executable) ? runtime.bin : undefined
  }

  /** Background consumers may use a connection, but may never choose or start one. */
  ensure(): Promise<string> {
    if (this.state === 'starting' && this.ensurePromise) return this.ensurePromise
    if (this.state !== 'running') return Promise.reject(new Error(this.lastError_ || '请先启动独立 Runtime 或主动连接已有 Runtime。'))
    try {
      const folder = vscode.workspace.workspaceFolders?.[0]
      if (!folder || folder.uri.scheme !== 'file' || workspaceIdentity(folder.uri.fsPath).root !== this.workspaceRoot_) throw new Error('VS Code 工作区已变化，请等待项目切换完成后重连。')
      if (this.attached) assertAttachmentCurrent(this.attached)
    } catch (error) {
      if (error instanceof AttachmentChangedError) {
        this.invalidateAttachment(error)
        return Promise.reject(error)
      }
      this.lastError_ = error instanceof Error ? error.message : 'Runtime 连接已变化，请重新连接。'
      this.setState('error')
      return Promise.reject(new Error(this.lastError_))
    }
    return Promise.resolve(this.baseUrl_)
  }

  initialize(): Promise<void> {
    return this.initialization ??= this.restoreBinding()
  }

  private async restoreBinding(): Promise<void> {
    const store = await this.bindingStore
    if (this.actionGeneration) return
    this.binding = store?.read()
    if (this.binding) {
      this.clientId_ = this.binding.clientId
      if (this.binding.invalid) {
        this.binding = undefined
        this.connectionMode = 'existing'
        this.lastError_ = '此前选择的 Runtime 连接已失效，请主动重新连接，或选择独立 Runtime。'
        this.setState('error')
        return
      }
      await this.connect('existing', this.binding)
      return
    }
  }

  /** Auto-start the installed bundle only in response to opening/using Harnex, not every VS Code window. */
  async startDefaultRuntime(): Promise<void> {
    await this.initialize().catch(() => {})
    if (this.state !== 'stopped' || this.userStopped || this.connectionMode) return
    const settings = cfg()
    if (settings.runtimeMode !== 'existing' && !settings.manifestPath && this.resolveRuntime()
        && vscode.workspace.workspaceFolders?.[0]?.uri.scheme === 'file') await this.connect('managed')
  }

  async startManaged(): Promise<string> {
    if (this.state === 'starting') return this.ensure()
    const action = this.actionGeneration + 1
    await this.disconnect()
    this.requireAction(action)
    return this.connect('managed')
  }

  async connectExisting(): Promise<string> {
    if (this.state === 'starting') return this.ensure()
    const action = this.actionGeneration + 1
    await this.disconnect()
    this.requireAction(action)
    return this.connect('existing')
  }

  async disconnect(): Promise<void> {
    const action = ++this.actionGeneration
    this.lastError_ = ''
    this.binding = undefined
    const store = await this.bindingStore
    this.requireAction(action)
    store?.clear()
    await this.settleConnection()
    this.requireAction(action)
    this.connectionMode = undefined
    this.lastError_ = ''
    this.setState('stopped')
  }

  async restart(): Promise<string> {
    const action = ++this.actionGeneration
    this.lastError_ = ''
    const mode = this.connectionMode
    const binding = this.binding
    await this.settleConnection()
    this.requireAction(action)
    if (this.domainClaimed) throw new Error('原有 Runtime 尚未退出，暂时不能切换连接。请查看日志后重试。')
    this.restartedOnce = false
    // Development settings may have changed since the previous start. Production companion
    // verification is deterministic, so resolving it again is cheap and avoids stale paths.
    this.resolvedRuntime = undefined
    return this.connect(mode || (cfg().runtimeMode === 'existing' || cfg().manifestPath || !this.resolveRuntime() ? 'existing' : 'managed'), binding)
  }

  private async followWorkspace(): Promise<void> {
    const folder = vscode.workspace.workspaceFolders?.[0]
    let root: string | undefined
    try { if (folder?.uri.scheme === 'file') root = workspaceIdentity(folder.uri.fsPath).root } catch { /* unavailable folder */ }
    if (!this.connectionMode || this.connectionMode === 'existing' && !this.binding
        || root && root === this.workspaceRoot_ && this.state === 'running') return
    const action = ++this.actionGeneration
    const mode = this.connectionMode, binding = this.binding
    this.lastError_ = ''
    await this.settleConnection()
    this.requireAction(action)
    if (!root) { this.lastError_ = '请打开项目目录后重连。'; this.setState('error'); return }
    await this.connect(mode, binding)
  }

  private requireAction(action: number): void {
    if (action !== this.actionGeneration) {
      const error = new Error('Runtime 连接操作已被后续操作替代。')
      error.name = 'RuntimeConnectionCancelled'
      throw error
    }
  }

  private async settleConnection(): Promise<void> {
    const pending = this.ensurePromise
    await this.stop()
    await pending?.catch(() => {})
  }

  private setState(s: RuntimeState): void {
    if (s === 'starting' || s === 'running') this.lastError_ = ''
    if (s === 'error') this.connectionAbort.abort()
    if (s === 'running' && this.connectionAbort.signal.aborted) {
      this.connectionAbort = new AbortController()
      this.connectionGeneration++
    }
    this.state = s
    const lifecycle = { stopped: 'CLOSED', starting: 'OPENING', running: 'READY', error: 'FAILED' }[s]
    if (this.domainClaimed) this.persistDomainState(lifecycle)
    this.stateEmitter.fire(s)
  }

  private connect(mode: 'managed' | 'existing', binding?: AttachBinding): Promise<string> {
    if (this.ensurePromise) return this.ensurePromise
    const action = this.actionGeneration
    const pending = Promise.resolve().then(() => { this.requireAction(action); return this.start(mode, binding) })
      .finally(() => { if (this.ensurePromise === pending) this.ensurePromise = undefined })
    this.ensurePromise = pending
    return pending
  }

  private async start(mode: 'managed' | 'existing', binding?: AttachBinding): Promise<string> {
    if (this.domainClaimed) {
      this.lastError_ = '原 Runtime 尚未退出，请稍后重试。'
      this.setState('error')
      throw new Error(this.lastError_)
    }
    this.connectionMode = mode
    this.lastError_ = ''
    this.connectionAbort.abort()
    this.connectionAbort = new AbortController()
    this.userStopped = false
    this.setState('starting')
    const generation = ++this.connectionGeneration
    const settings = cfg()
    try {
      if (mode === 'existing') {
        if (binding) {
          const store = await this.bindingStore
          const folder = vscode.workspace.workspaceFolders?.[0]
          if (!folder || folder.uri.scheme !== 'file') throw new Error('请先打开本地项目目录。')
          if (this.userStopped || generation !== this.connectionGeneration) throw new Error('Runtime 连接已取消。')
          const ready = await reconnectAttachedWorkspace(binding, workspaceIdentity(folder.uri.fsPath).root, this.connectionSignal,
            async (current, target) => {
              const choice = await vscode.window.showWarningMessage(
                `将已连接的 ${current.ownerKind} Runtime 切换到 VS Code 当前项目吗？`,
                { modal: true, detail: `当前 Runtime：${current.canonicalRoot}\nVS Code 项目：${target}\n\n这也会切换该宿主的当前项目。若仍有任务未结束，切换会被拒绝。` },
                '切换 Runtime')
              return choice === '切换 Runtime'
            })
          if (this.userStopped || generation !== this.connectionGeneration) throw new Error('Runtime 连接已取消。')
          this.binding = { ...binding, instanceId: ready.instanceId, root: ready.canonicalRoot }
          // A host switch can succeed even if the subsequent editor registration fails.
          // Remember the confirmed instance so a retry does not mistake it for another client's switch.
          try { store?.write(this.binding) } catch (error) { log(`cannot remember project switch: ${String(error)}`) }
        }
        return await this.attachRuntime(binding?.file || settings.manifestPath, generation)
      }
      return await this.spawnRuntime(generation)
    } catch (error) {
      if (!this.userStopped && generation === this.connectionGeneration) {
        if (error instanceof AttachmentChangedError) { this.invalidateAttachment(error); throw error }
        this.lastError_ = error instanceof Error ? error.message : String(error)
        this.setState(error instanceof Error && error.name === 'RuntimeConnectionCancelled' ? 'stopped' : 'error')
      }
      throw error
    }
  }

  private async attachRuntime(manifestPath: string, generation: number): Promise<string> {
    const store = await this.bindingStore
    const folder = vscode.workspace.workspaceFolders?.[0]
    if (!folder || folder.uri.scheme !== 'file') throw new Error('请先打开要连接的项目目录。')
    const { root } = workspaceIdentity(folder.uri.fsPath)
    let file = manifestPath
    if (!file) {
      const candidates = discoverProjectRuntimes(this.userDataDir, root)
      if (!candidates.length) throw new Error('没有找到当前项目已启动的 Runtime。请先用 Harnex 桌面端或 Web 打开此项目，或安装 Runtime VSIX 并选择自动/独立运行模式。')
      if (candidates.length === 1) file = candidates[0].file
      else {
        const cancellation = new vscode.CancellationTokenSource()
        const signal = this.connectionSignal
        const cancel = () => cancellation.cancel()
        signal.addEventListener('abort', cancel, { once: true })
        if (signal.aborted) cancellation.cancel()
        try {
          const selected = await vscode.window.showQuickPick(candidates.map(candidate => ({
            label: candidate.manifest.ownerKind, description: candidate.manifest.endpoint,
            detail: candidate.manifest.canonicalRoot, file: candidate.file,
          })), { title: 'Harnex · 连接已有 Runtime', placeHolder: '当前项目有多个 Runtime，选择要连接的服务' }, cancellation.token)
          if (!selected) {
            const cancelled = new Error('已取消连接 Runtime。'); cancelled.name = 'RuntimeConnectionCancelled'
            throw cancelled
          }
          file = selected.file
        } finally { signal.removeEventListener('abort', cancel); cancellation.dispose() }
      }
    }
    const attached = await attachProjectRuntime(file, root)
    if (this.userStopped || generation !== this.connectionGeneration) throw new Error('Runtime 连接已取消。')
    const registration = await attachmentRequest(attached, this.clientId, `/api/v1/ide/clients/${this.clientId}`, {
      method: 'PUT', headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ endpoint: this.editorBridge.url, token: this.editorBridge.token }),
    })
    if (!registration.ok) throw new Error(`已有 Runtime 无法注册编辑器连接（HTTP ${registration.status}）。请使用包含 IDE 连接支持的 Runtime。`)
    const binding = await registration.json() as { toolset?: string }
    if (!binding.toolset || !/^ide-[A-Za-z0-9-]+$/.test(binding.toolset)) throw new Error('Runtime 返回了无效的编辑器工具集。')
    attached.toolset = binding.toolset
    if (this.userStopped || generation !== this.connectionGeneration) {
      await attachmentRequest(attached, this.clientId, `/api/v1/ide/clients/${this.clientId}`, { method: 'DELETE' }).catch(() => {})
      throw new Error('Runtime 连接已取消。')
    }
    this.attached = attached
    this.spawned = false
    this.baseUrl_ = attached.endpoint
    this.workspaceRoot_ = root
    this.workspaceId_ = attached.manifest.scopeId
    this.instanceId_ = attached.manifest.instanceId
    this.binding = { file: attached.file, ownerInstanceId: attached.manifest.ownerInstanceId,
      instanceId: attached.manifest.instanceId, root, clientId: this.clientId }
    try { store?.write(this.binding) }
    catch (error) {
      log(`cannot remember attached Runtime: ${error instanceof Error ? error.message : error}`)
      void vscode.window.showWarningMessage('当前连接已建立，但无法保存窗口连接记录。切换文件夹后需主动重新连接。')
    }
    if (!store) void vscode.window.showWarningMessage('此 VS Code 环境不提供稳定的窗口标识。当前连接可用，切换文件夹导致扩展重启后需要主动重新连接。')
    this.setState('running')
    let renewAt = Date.now() + 30000
    this.attachmentHeartbeat = setInterval(() => {
      if (this.attached !== attached) return
      try { assertAttachmentCurrent(attached) }
      catch (error) {
        this.invalidateAttachment(error instanceof AttachmentChangedError ? error : new AttachmentChangedError())
        return
      }
      if (Date.now() >= renewAt) { renewAt = Date.now() + 30000; void this.renewAttachment(attached) }
    }, 1000)
    log(`attached to ${attached.manifest.ownerKind} Runtime at ${attached.endpoint}; process ownership remains with its host`)
    return this.baseUrl_
  }

  private async renewAttachment(attached: AttachedRuntime): Promise<void> {
    if (this.attached !== attached || this.userStopped) return
    try {
      let response = await attachmentRequest(attached, this.clientId, `/api/v1/ide/clients/${this.clientId}`, { method: 'POST' })
      if (response.status === 401) {
        const refreshed = await attachProjectRuntime(attached.file, this.workspaceRoot_)
        if (this.attached !== attached) return
        if (refreshed.manifest.instanceId !== attached.manifest.instanceId) throw new Error('已有 Runtime 已重新启动，请重新连接。')
        attached.token = refreshed.token
        response = await attachmentRequest(attached, this.clientId, `/api/v1/ide/clients/${this.clientId}`, { method: 'POST' })
      }
      if (response.status === 410 && this.attached === attached) {
        response = await attachmentRequest(attached, this.clientId, `/api/v1/ide/clients/${this.clientId}`, {
          method: 'PUT', headers: { 'content-type': 'application/json' },
          body: JSON.stringify({ endpoint: this.editorBridge.url, token: this.editorBridge.token }),
        })
      }
      if (!response.ok) throw new Error(`连接续期失败：HTTP ${response.status}`)
      if (this.attached === attached && this.state === 'error') { this.lastError_ = ''; this.setState('running') }
    } catch (error) {
      if (this.attached === attached) {
        if (error instanceof AttachmentChangedError) { this.invalidateAttachment(error); return }
        this.lastError_ = error instanceof Error ? error.message : String(error)
        this.setState('error')
        log(`attached Runtime connection lost: ${error instanceof Error ? error.message : String(error)}`)
      }
    }
  }

  private invalidateAttachment(error: AttachmentChangedError): void {
    if (this.attachmentHeartbeat) clearInterval(this.attachmentHeartbeat)
    this.attachmentHeartbeat = undefined
    const previousBinding = this.binding
    this.binding = undefined
    this.attached = undefined
    this.baseUrl_ = ''; this.workspaceId_ = ''; this.instanceId_ = ''
    const generation = ++this.connectionGeneration
    // Remember the user's connection mode, but never reuse an invalid target after reload.
    void this.bindingStore.then(store => {
      if (generation === this.connectionGeneration && !this.binding && previousBinding) store?.write({ ...previousBinding, invalid: true })
    }).catch(failure => log(`cannot remember disconnected Runtime: ${String(failure)}`))
    this.lastError_ = error.message
    this.setState('error')
    this.revealConnectionFailure()
  }

  private revealConnectionFailure(): void {
    // Reveal the replaceable connection notice without taking focus from the editor.
    void vscode.commands.executeCommand('harnex.openChat', true).then(undefined, failure => log(String(failure)))
  }

  private async spawnRuntime(generation: number): Promise<string> {
    const action = this.actionGeneration
    const c = cfg()
    const executable = this.resolveRuntime()
    if (!executable) {
      this.setState('error')
      throw new Error('No verified Harnex Runtime Bundle found. Install harnex.harnex-runtime for this platform. '
        + 'Development builds may set backend.jarPath/javaPath or build backend/target locally.')
    }
    const { jar, java } = executable
    const primaryFolder = vscode.workspace.workspaceFolders?.[0]
    if (!primaryFolder || primaryFolder.uri.scheme !== 'file') {
      this.setState('error')
      throw new Error('Harnex requires a local file-system workspace folder')
    }
    const { root: cwd, id: scopeId } = workspaceIdentity(primaryFolder.uri.fsPath)
    const port = await freePort()
    if (this.userStopped || generation !== this.connectionGeneration) throw new Error('Runtime 启动已取消。')
    const catalogFile = path.join(this.userDataDir, 'catalog', 'workspaces.json')
    const catalog = fs.existsSync(catalogFile) ? JSON.parse(fs.readFileSync(catalogFile, 'utf8')) : {workspaces:[]}
    if (!Array.isArray(catalog.workspaces)) throw new Error('Workspace catalog is invalid; cannot resolve its data directory')
    const recordedDataDir = catalog.workspaces.find((item: {id?: string; remoteProfile?: string; dataDir?: string}) =>
      item.id === scopeId && !item.remoteProfile)?.dataDir
    const sharedDataDir = path.resolve(cwd, c.dataDir || recordedDataDir || '.harnex/data')
    const storageId = createHash('sha256').update(this.ctx.storageUri?.fsPath || cwd).digest('hex').slice(0, 16)
    this.dataDir_ = path.join(sharedDataDir, 'runtime-stores', `vscode-${storageId}`)
    fs.mkdirSync(this.dataDir_, { recursive: true })
    const args = [
      ...c.jvmArgs,
      '-jar', jar,
      `--server.port=${port}`,
      '--server.address=127.0.0.1',
      `--harnex.data-dir=${this.dataDir_}`,
      `--harnex.shared-data-dir=${sharedDataDir}`,
      `--harnex.user-data-dir=${this.userDataDir}`,
      `--harnex.runtime-domain-id=${this.runtimeDomainId}`,
      `--harnex.runtime-domain-dir=${this.runtimeDomainDir}`,
      '--harnex.runtime-owner-kind=vscode',
      `--harnex.runtime-mcp-bridge.name=${this.editorBridge.name}`,
      '--harnex.runtime-mcp-bridge.transport=sse',
      `--harnex.runtime-mcp-bridge.url=${this.editorBridge.url}`,
      '--harnex.runtime-mcp-bridge.headers[x-harnex-bridge-token]=${HARNEX_IDE_BRIDGE_TOKEN}',
      '--harnex.runtime-mcp-bridge.expose-to-presets[0]=vscode',
      ...buildProxyArgs(),
    ]
    // Config resolution mirrors `harnex serve` (bin/harnex.mjs spawnJvm): jar-internal
    // defaults always load and user-global overrides at ~/.harnex/ are APPENDED here.
    // Project-local agent-config.yml is intentionally not imported: Agent/Settings policy
    // is user-level, while workspace state uses dedicated Memory/Skill/Automation APIs.
    // Use exact-file URLs because Spring
    // scans directory locations for application.* only and would ignore agent-config.yml.
    const env: NodeJS.ProcessEnv = { ...process.env }
    env.HARNEX_WORKSPACE = primaryFolder.uri.fsPath
    env.HARNEX_SCOPE_ID = scopeId
    env.HARNEX_INSTANCE_ID = `${process.pid}-${Date.now()}-${Math.random().toString(36).slice(2)}`
    this.workspaceRoot_ = cwd
    this.workspaceId_ = env.HARNEX_SCOPE_ID
    this.instanceId_ = env.HARNEX_INSTANCE_ID
    env.HARNEX_USER_DATA_DIR = this.userDataDir
    env.HARNEX_RUNTIME_DOMAIN_ID = this.runtimeDomainId
    env.HARNEX_RUNTIME_DOMAIN_DIR = this.runtimeDomainDir
    env.HARNEX_RUNTIME_OWNER_KIND = 'vscode'
    env.HARNEX_IDE_BRIDGE_TOKEN = this.editorBridge.token
    this.runtimeControlToken = randomBytes(32).toString('base64url')
    env.HARNEX_INTERNAL_SUPERVISOR_TOKEN = this.runtimeControlToken
    for (const name of ['agent-config.yml', 'application.yml']) {
      const f = path.join(this.userDataDir, name)
      if (!fs.existsSync(f)) continue
      this.requireResolvableKeychainReferences(f, env)
      const url = pathToFileURL(f).href.replaceAll(',', '%2C').replaceAll(';', '%3B')
      const cur = env.SPRING_CONFIG_ADDITIONAL_LOCATION
      const protectedFiles = env.HARNEX_CONFIG_FILES ? env.HARNEX_CONFIG_FILES.split('\n') : []
      if (!protectedFiles.includes(f)) env.HARNEX_CONFIG_FILES = [...protectedFiles, f].join('\n')
      if (cur && cur.split(',').includes(url)) continue
      env.SPRING_CONFIG_ADDITIONAL_LOCATION = cur ? `${cur},${url}` : url
      log(`loading user config from ${f}`)
    }
    this.claimDomain()
    try {
      log(`spawning runtime: ${java} ${args.join(' ')} (cwd=${cwd})`)

      const child = spawn(java, args, {
        cwd, env, windowsHide: true,
        // A dedicated POSIX process group lets deactivation reap the JVM plus MCP/tool
        // descendants. Windows uses taskkill /T instead.
        detached: process.platform !== 'win32',
      })
      let spawnFailure: Error | undefined
      this.child = child
      this.spawned = true
      this.baseUrl_ = `http://127.0.0.1:${port}`
      this.persistDomainState('OPENING')

      const out = getLog()
      const logDir = path.join(this.runtimeDomainDir, 'logs')
      fs.mkdirSync(logDir, { recursive: true })
      this.runtimeLog = fs.createWriteStream(path.join(logDir, 'runtime.log'), { flags: 'a' })
      child.stdout?.on('data', (d: Buffer) => { out.append(d.toString()); this.runtimeLog?.write(d) })
      child.stderr?.on('data', (d: Buffer) => { out.append(d.toString()); this.runtimeLog?.write(d) })
      child.on('error', err => {
        spawnFailure = err
        this.lastError_ = `Runtime 启动失败：${err.message}`
        log(`runtime spawn error: ${err.message}`)
        this.setState('error')
        this.releaseDomain()
      })
      child.on('exit', (code, signal) => {
        const wasRunning = this.state === 'running'
        log(`runtime exited code=${code} signal=${signal}`)
        this.child = undefined
        this.ensurePromise = undefined
        this.unpublishManifest()
        this.runtimeLog?.end()
        this.runtimeLog = undefined
        if (this.userStopped) {
          this.setState('stopped')
          this.releaseDomain()
          return
        }
        this.lastError_ = wasRunning && this.restartedOnce ? 'Runtime 连续退出，已停止自动重启，请查看日志。'
          : `Runtime 已退出（code=${code ?? signal ?? 'unknown'}）。`
        this.setState('error')
        this.releaseDomain()
        if (wasRunning && !this.restartedOnce) {
          this.restartedOnce = true
          log('runtime crashed — restarting once in 5s')
          this.restartTimer = setTimeout(() => {
            this.restartTimer = undefined
            if (!this.userStopped) void this.connect('managed').catch(() => {})
          }, 5000)
        } else if (wasRunning) {
          this.revealConnectionFailure()
        }
      })

      const startTimeoutMs = runtimeStartTimeoutMs()
      const ok = await waitHealthy(this.baseUrl_, startTimeoutMs,
        () => this.userStopped || generation !== this.connectionGeneration || child.exitCode !== null || spawnFailure !== undefined, this.workspaceId_, this.instanceId_)
      if (!ok) {
        throw new Error(spawnFailure
          ? `Harnex runtime could not start: ${spawnFailure.message}`
          : `Harnex runtime did not become healthy on ${this.baseUrl_} within ${Math.round(startTimeoutMs / 1000)}s `
            + '(see Harnex output)')
      }
      if (this.userStopped || generation !== this.connectionGeneration) throw new Error('Runtime 启动已取消。')
      log(`runtime healthy at ${this.baseUrl_}`)
      this.publishManifest(child.pid!, cwd, env.HARNEX_SCOPE_ID!, env.HARNEX_INSTANCE_ID!, port)
      if (this.restartedOnce) {
        // A restart must remain healthy for a while before earning another automatic retry;
        // otherwise a process that repeatedly reaches /protocol then crashes loops forever.
        if (this.stabilityTimer) clearTimeout(this.stabilityTimer)
        this.stabilityTimer = setTimeout(() => {
          this.stabilityTimer = undefined
          this.restartedOnce = false
        }, 60_000)
      }
      this.setState('running')
      return this.baseUrl_
    } catch (error) {
      if (generation === this.connectionGeneration) {
        await this.stop()
        if (action === this.actionGeneration) {
          this.lastError_ = error instanceof Error ? error.message : String(error)
          this.setState('error')
        }
      }
      throw error
    }
  }

  private requireResolvableKeychainReferences(file: string, env: NodeJS.ProcessEnv): void {
    if (process.platform !== 'darwin') return
    const references = new Set<string>()
    for (const line of fs.readFileSync(file, 'utf8').split(/\r?\n/)) {
      if (line.trimStart().startsWith('#')) continue
      for (const match of line.matchAll(/\$\{(HARNEX_KEYCHAIN_PROVIDER_[A-Z0-9_]+)(?::[^}]*)?\}/g)) {
        references.add(match[1])
      }
    }
    const missing = [...references].filter(reference => !env[reference])
    if (missing.length) {
      throw new Error(`${file} contains desktop-only macOS Keychain Provider references (${missing.join(', ')}). `
        + 'Provide explicit credentials for the VS Code-owned Runtime, or use Harnex.app with an attached CLI client.')
    }
  }

  private resolveRuntime(): { java: string; jar: string; bin?: string } | undefined {
    if (this.resolvedRuntime) return this.resolvedRuntime
    const c = cfg()
    // Explicit paths are a development-only escape hatch. Production never silently combines
    // an unverified jar with an arbitrary system Java installation.
    if (c.jarPath && fs.existsSync(c.jarPath)) {
      this.resolvedRuntime = { java: c.javaPath || 'java', jar: c.jarPath }
      return this.resolvedRuntime
    }
    const companion = vscode.extensions.getExtension('harnex.harnex-runtime')
    for (const directory of [
      companion ? path.join(companion.extensionUri.fsPath, 'runtime') : '',
      path.join(this.ctx.extensionUri.fsPath, 'runtime'), // optional all-in-one offline derivative
    ]) {
      if (!directory || !fs.existsSync(directory)) continue
      const expectedVersion = companion && directory.startsWith(companion.extensionUri.fsPath)
        ? String((companion.packageJSON as { version?: unknown }).version || '') : undefined
      const resolved = verifyRuntimeBundle(directory, expectedVersion)
      if (resolved) {
        this.resolvedRuntime = { ...resolved, bin: path.join(directory, 'bin') }
        return this.resolvedRuntime
      }
    }
    // Repository development convenience only.
    for (const f of this.ctx.extensionMode === vscode.ExtensionMode.Development ? vscode.workspace.workspaceFolders ?? [] : []) {
      const hit = firstJar(path.join(f.uri.fsPath, 'backend', 'target'))
      if (hit) {
        this.resolvedRuntime = { java: c.javaPath || 'java', jar: hit }
        return this.resolvedRuntime
      }
    }
    return undefined
  }

  private claimDomain(): void {
    fs.mkdirSync(this.runtimeDomainDir, { recursive: true, mode: 0o700 })
    try { fs.chmodSync(this.runtimeDomainDir, 0o700) } catch {}
    if (process.platform === 'win32' && process.env.USERNAME) {
      spawnSync('icacls.exe', [this.runtimeDomainDir, '/inheritance:r', '/grant:r',
        `${process.env.USERNAME}:(OI)(CI)F`], { stdio: 'ignore', windowsHide: true, timeout: 5000 })
    }
    const owner = {
      schemaVersion: 1,
      runtimeDomainId: this.runtimeDomainId,
      ownerKind: 'vscode',
      ownerPid: process.pid,
      ownerInstanceId: this.ownerInstanceId,
      startedAt: new Date().toISOString(),
    }
    try {
      fs.writeFileSync(this.ownerFile, `${JSON.stringify(owner, null, 2)}\n`, { flag: 'wx', mode: 0o600 })
    } catch (error) {
      if ((error as NodeJS.ErrnoException).code !== 'EEXIST') throw error
      let current: { ownerPid?: number; startedAt?: string } = {}
      try { current = JSON.parse(fs.readFileSync(this.ownerFile, 'utf8')) as typeof current } catch {}
      const parsedStartedAt = Date.parse(current.startedAt || '')
      let ageMs = Number.isFinite(parsedStartedAt) ? Date.now() - parsedStartedAt : 0
      if (!Number.isFinite(parsedStartedAt)) {
        try { ageMs = Date.now() - fs.statSync(this.ownerFile).mtimeMs } catch {}
      }
      if ((!current.ownerPid || !pidAlive(current.ownerPid)) && ageMs >= 2000) {
        for (const file of [this.ownerFile, this.manifestFile, this.bootTokenFile]) {
          try { fs.unlinkSync(file) } catch {}
        }
        fs.writeFileSync(this.ownerFile, `${JSON.stringify(owner, null, 2)}\n`, { flag: 'wx', mode: 0o600 })
        this.domainClaimed = true
        this.unpublishManifest()
        this.persistDomainState('OPENING')
        return
      }
      throw new Error(`VS Code Runtime Domain ${this.runtimeDomainId} is already owned by pid `
        + `${current.ownerPid ?? 'unknown'}; retry after that window exits`)
    }
    this.domainClaimed = true
    this.unpublishManifest()
    this.persistDomainState('OPENING')
  }

  private releaseDomain(): void {
    try {
      const owner = JSON.parse(fs.readFileSync(this.ownerFile, 'utf8')) as {
        ownerPid?: number; ownerInstanceId?: string
      }
      if (owner.ownerPid === process.pid && owner.ownerInstanceId === this.ownerInstanceId) {
        fs.unlinkSync(this.ownerFile)
      }
    } catch {}
    this.domainClaimed = false
  }

  private persistDomainState(lifecycle: string): void {
    const state = {
      schemaVersion: 1,
      runtimeDomainId: this.runtimeDomainId,
      ownerKind: 'vscode',
      activeWorkspaceId: lifecycle === 'CLOSED' ? null : this.workspaceId_ || null,
      lastWorkspaceId: this.workspaceId_ || null,
      runtimePid: this.child?.pid ?? null,
      endpoint: this.baseUrl_ || null,
      instanceId: lifecycle === 'CLOSED' ? null : this.instanceId_ || null,
      canonicalRoot: this.workspaceRoot_ || null,
      lifecycle,
      updatedAt: new Date().toISOString(),
    }
    const tmp = `${this.domainStateFile}.${process.pid}.${randomUUID()}.tmp`
    try {
      fs.writeFileSync(tmp, `${JSON.stringify(state, null, 2)}\n`, { flag: 'wx', mode: 0o600 })
      try { fs.renameSync(tmp, this.domainStateFile) } catch {
        try { fs.unlinkSync(this.domainStateFile) } catch {}
        fs.renameSync(tmp, this.domainStateFile)
      }
    } catch (error) {
      try { fs.unlinkSync(tmp) } catch {}
      log(`cannot persist Runtime Domain state: ${error instanceof Error ? error.message : error}`)
    }
  }

  private publishManifest(runtimePid: number, root: string, runtimeWorkspaceId: string,
    instanceId: string, port: number): void {
    const configHash = createHash('sha256')
    for (const name of ['agent-config.yml', 'application.yml']) {
      const file = path.join(this.userDataDir, name)
      if (fs.existsSync(file)) configHash.update(name).update(fs.readFileSync(file))
    }
    const manifest = {
      schemaVersion: 3,
      runtimeProfile: 'PROJECT',
      runtimeDomainId: this.runtimeDomainId,
      ownerKind: 'vscode',
      ownerPid: process.pid,
      ownerInstanceId: this.ownerInstanceId,
      runtimePid,
      canonicalRoot: fs.realpathSync.native(root),
      scopeId: runtimeWorkspaceId,
      instanceId,
      endpoint: `http://127.0.0.1:${port}`,
      lifecycle: 'READY',
      protocolVersion: '1.0',
      bootTokenFile: this.bootTokenFile,
      configRevision: configHash.digest('hex'),
      publishedAt: new Date().toISOString(),
    }
    const tmp = `${this.manifestFile}.${process.pid}.${randomUUID()}.tmp`
    fs.writeFileSync(tmp, `${JSON.stringify(manifest, null, 2)}\n`, { flag: 'wx', mode: 0o600 })
    try {
      fs.renameSync(tmp, this.manifestFile)
    } catch {
      try { fs.unlinkSync(this.manifestFile) } catch {}
      fs.renameSync(tmp, this.manifestFile)
    }
    try { fs.chmodSync(this.manifestFile, 0o600) } catch {}
  }

  private unpublishManifest(): void {
    try { fs.unlinkSync(this.manifestFile) } catch {}
  }

  stop(): Promise<void> {
    if (!this.stopPromise) {
      this.stopPromise = this.stopInternal().finally(() => { this.stopPromise = undefined })
    }
    return this.stopPromise
  }

  private async stopInternal(): Promise<void> {
    this.userStopped = true
    this.connectionAbort.abort()
    this.connectionGeneration++
    if (this.attachmentHeartbeat) clearInterval(this.attachmentHeartbeat)
    this.attachmentHeartbeat = undefined
    if (this.restartTimer) clearTimeout(this.restartTimer)
    this.restartTimer = undefined
    if (this.stabilityTimer) clearTimeout(this.stabilityTimer)
    this.stabilityTimer = undefined
    this.ensurePromise = undefined
    this.setState('stopped')
    if (this.attached) {
      const attached = this.attached
      this.attached = undefined
      await attachmentRequest(attached, this.clientId, `/api/v1/ide/clients/${this.clientId}`, { method: 'DELETE', signal: AbortSignal.timeout(2500) }).catch(() => {})
      this.baseUrl_ = ''; this.workspaceId_ = ''; this.instanceId_ = ''
      this.setState('stopped')
      return
    }
    const child = this.child
    this.unpublishManifest()
    if (this.domainClaimed) this.persistDomainState('DRAINING')
    if (!child || child.exitCode !== null || !child.pid) {
      this.child = undefined
      this.spawned = false
      this.runtimeControlToken = undefined
      this.setState('stopped')
      this.releaseDomain()
      return
    }
    const exited = new Promise<void>(res => child.once('exit', () => res()))
    // Match every other client: DRAIN active runs before closing the sole workspace JVM.
    if (this.baseUrl_) {
      try {
        await fetch(`${this.baseUrl_}/api/v1/admin/drain?timeoutMs=30000`, {
          method: 'POST',
          headers: this.runtimeControlToken
            ? { 'X-Harnex-Supervisor-Token': this.runtimeControlToken }
            : {},
          signal: AbortSignal.timeout(32_000),
        })
      } catch { /* process-tree close below is the final backstop */ }
    }
    if (process.platform === 'win32' && child.pid) {
      // SIGTERM is a hard kill on Windows anyway; taskkill /T also reaps the process tree.
      spawn('taskkill', ['/pid', String(child.pid), '/T', '/F'], { windowsHide: true })
    } else {
      signalProcessGroup(child, 'SIGTERM')
      setTimeout(() => { if (child.exitCode === null) signalProcessGroup(child, 'SIGKILL') }, 3000)
    }
    await Promise.race([exited, new Promise<void>(res => setTimeout(res, 5000))])
    if (child.exitCode !== null) {
      this.runtimeControlToken = undefined
      this.spawned = false
      this.setState('stopped')
      this.releaseDomain()
    } else {
      this.lastError_ = '原 Runtime 尚未退出，请查看日志后重试。'
      this.setState('error')
      log(`runtime pid ${child.pid ?? 'unknown'} did not exit; Domain ownership retained`)
    }
  }

  dispose(): void {
    this.workspaceSubscription.dispose()
    void this.stop()
    this.stateEmitter.dispose()
  }
}

function signalProcessGroup(child: ChildProcess, signal: NodeJS.Signals): void {
  if (!child.pid) return
  try {
    process.kill(-child.pid, signal)
  } catch {
    try { child.kill(signal) } catch { /* already exited */ }
  }
}

function firstJar(dir: string): string | undefined {
  try {
    const hit = fs.readdirSync(dir).find(f => /^harnex-.*\.jar$/.test(f) && !f.includes('sources'))
    return hit ? path.join(dir, hit) : undefined
  } catch {
    return undefined
  }
}

interface RuntimeBundleManifest {
  schemaVersion: number
  runtimeVersion: string
  protocolVersion: string
  target: string
  entrypoint: string
  javaExecutable: string
  bundleDigest: string
  files: Record<string, string>
}

/** Fail closed on a modified, wrong-platform or structurally incompatible companion bundle. */
function verifyRuntimeBundle(directory: string, expectedVersion?: string): { java: string; jar: string } | undefined {
  try {
    const manifest = JSON.parse(
      fs.readFileSync(path.join(directory, 'runtime-bundle.json'), 'utf8')) as RuntimeBundleManifest
    if (manifest.schemaVersion !== 1 || manifest.protocolVersion !== '1.0') {
      throw new Error('unsupported bundle schema/protocol')
    }
    if (!manifest.runtimeVersion || (expectedVersion && manifest.runtimeVersion !== expectedVersion)) {
      throw new Error(`runtime version ${manifest.runtimeVersion || '<missing>'} != companion ${expectedVersion}`)
    }
    if (manifest.target !== platformTarget()) throw new Error(`target ${manifest.target} != ${platformTarget()}`)
    const entries = Object.entries(manifest.files || {}).sort(([a], [b]) => a < b ? -1 : a > b ? 1 : 0)
    if (entries.length === 0) throw new Error('empty file manifest')
    const declared = entries.map(([name]) => name)
    const actualFiles = runtimeBundleFileNames(directory)
    if (JSON.stringify(actualFiles) !== JSON.stringify(declared)) {
      throw new Error('bundle contains undeclared or missing files')
    }
    for (const [relative, expected] of entries) {
      if (!relative || path.isAbsolute(relative) || relative.split('/').includes('..')) {
        throw new Error(`unsafe bundle path: ${relative}`)
      }
      const file = path.join(directory, ...relative.split('/'))
      const actual = createHash('sha256').update(fs.readFileSync(file)).digest('hex')
      if (actual !== expected) throw new Error(`hash mismatch: ${relative}`)
    }
    const digest = createHash('sha256').update(JSON.stringify(Object.fromEntries(entries))).digest('hex')
    if (digest !== manifest.bundleDigest) throw new Error('bundle digest mismatch')
    if (!safeBundlePath(manifest.javaExecutable) || !safeBundlePath(manifest.entrypoint)) {
      throw new Error('unsafe bundle entrypoint path')
    }
    const java = path.join(directory, ...manifest.javaExecutable.split('/'))
    const jar = path.join(directory, ...manifest.entrypoint.split('/'))
    if (!fs.existsSync(java) || !fs.existsSync(jar)) throw new Error('bundle entrypoint missing')
    return { java, jar }
  } catch (error) {
    throw new Error(`Invalid Harnex Runtime Bundle at ${directory}: ${error instanceof Error ? error.message : error}`)
  }
}

function runtimeBundleFileNames(directory: string): string[] {
  const files: string[] = []
  const visit = (current: string): void => {
    for (const entry of fs.readdirSync(current, { withFileTypes: true })) {
      const file = path.join(current, entry.name)
      if (entry.isDirectory()) visit(file)
      else if (entry.isFile()) {
        const relative = path.relative(directory, file).split(path.sep).join('/')
        if (relative !== 'runtime-bundle.json' && relative !== 'SHA256SUMS') files.push(relative)
      }
    }
  }
  visit(directory)
  return files.sort((a, b) => a < b ? -1 : a > b ? 1 : 0)
}

function safeBundlePath(value: unknown): value is string {
  return typeof value === 'string' && value.length > 0 && !value.includes('\\') && !value.startsWith('/')
    && !/^[A-Za-z]:/.test(value) && !value.includes('\0')
    && value.split('/').every(part => part !== '' && part !== '.' && part !== '..')
}

function platformTarget(): string {
  const platforms: Partial<Record<NodeJS.Platform, string>> = { win32: 'win32', linux: 'linux', darwin: 'darwin' }
  const arches: Partial<Record<NodeJS.Architecture, string>> = { x64: 'x64', arm64: 'arm64', arm: 'armhf' }
  const platform = platforms[process.platform]
  const arch = arches[process.arch]
  if (!platform || !arch) throw new Error(`Unsupported VS Code Runtime platform: ${process.platform}-${process.arch}`)
  return `${platform}-${arch}`
}

function freePort(): Promise<number> {
  return new Promise((resolve, reject) => {
    const srv = net.createServer()
    srv.listen(0, '127.0.0.1', () => {
      const addr = srv.address()
      const port = typeof addr === 'object' && addr ? addr.port : 0
      srv.close(() => port ? resolve(port) : reject(new Error('failed to allocate a free port')))
    })
    srv.on('error', reject)
  })
}

async function probe(baseUrl: string, workspaceId: string, instanceId: string): Promise<boolean> {
  try {
    const response = await fetch(`${baseUrl}/api/v1/protocol`, {
      signal: AbortSignal.timeout(2000),
    })
    return (response.ok || response.status === 401)
      && response.headers.get('x-harnex-scope-id') === workspaceId
      && response.headers.get('x-harnex-instance-id') === instanceId
  } catch { return false }
}

async function waitHealthy(baseUrl: string, timeoutMs: number, dead: () => boolean,
  workspaceId: string, instanceId: string): Promise<boolean> {
  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline) {
    if (dead()) return false
    if (await probe(baseUrl, workspaceId, instanceId)) return true
    await new Promise(res => setTimeout(res, 500))
  }
  return false
}

function pidAlive(pid: number): boolean {
  try { process.kill(pid, 0); return true } catch { return false }
}

/** Product startup remains bounded at 90s; the real-host E2E may opt into a larger cold-runner budget. */
function runtimeStartTimeoutMs(): number {
  const requested = Number(process.env.HARNEX_E2E_RUNTIME_START_TIMEOUT_MS)
  return process.env.HARNEX_E2E === '1' && Number.isFinite(requested)
    ? Math.max(90_000, Math.min(300_000, Math.trunc(requested)))
    : 90_000
}
