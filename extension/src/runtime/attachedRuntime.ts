import * as fs from 'node:fs'
import * as path from 'node:path'
import type { AttachBinding } from './attachBinding'

export interface RuntimeManifest {
  runtimeProfile: 'PROJECT' | 'PERSONAL'
  runtimeDomainId: string
  ownerKind: string
  ownerInstanceId: string
  scopeId: string
  instanceId: string
  canonicalRoot: string
  endpoint: string
  bootTokenFile: string
  protocolVersion: string
  remote?: unknown
}
export interface AttachedRuntime {
  file: string
  manifest: RuntimeManifest
  endpoint: string
  token: string
  identityHeaders: Record<string, string>
  toolset?: string
}

export class AttachmentChangedError extends Error {
  constructor(message = '已连接的 Runtime 已关闭、重启或切换项目。VS Code 保留当前目录，请重新选择连接。') {
    super(message)
    this.name = 'AttachmentChangedError'
  }
}

// The CLI's trusted-manifest implementation is bundled into the extension by esbuild.
// Keeping this boundary shared avoids a second implementation of ownership/authentication checks.
const domains = require('../../../bin/lib/runtime-domain.mjs') as {
  readTrustedManifestSync(file: string): RuntimeManifest
  attachTrustedRuntime(file: string, options: { cwd: string }): Promise<Omit<AttachedRuntime, 'file'>>
}
const hosts = require('../../../bin/lib/workspace-control.mjs') as {
  switchHostWorkspace(file: string, owner: string, expectedInstance: string, root: string, signal?: AbortSignal): Promise<RuntimeManifest>
}

export async function reconnectAttachedWorkspace(binding: AttachBinding, root: string, signal: AbortSignal,
  confirmSwitch: (current: RuntimeManifest, targetRoot: string) => Promise<boolean>): Promise<RuntimeManifest> {
  let current: RuntimeManifest
  try { current = domains.readTrustedManifestSync(binding.file) }
  catch { throw new AttachmentChangedError() }
  if (current.ownerInstanceId !== binding.ownerInstanceId || current.instanceId !== binding.instanceId) {
    throw new AttachmentChangedError()
  }
  if (!sameRoot(current.canonicalRoot, root)) {
    if (!await confirmSwitch(current, root)) {
      const declined = new Error('尚未切换 Runtime：VS Code 与宿主的项目不同。点击“重试连接”可重新确认切换。')
      declined.name = 'RuntimeSwitchDeclined'
      throw declined
    }
    if (signal.aborted) throw new Error('Runtime 连接已取消。')
    const switched = await hosts.switchHostWorkspace(binding.file, binding.ownerInstanceId, binding.instanceId, root, signal)
    requireProject(switched, root)
    const ready = domains.readTrustedManifestSync(binding.file)
    if (ready.ownerInstanceId !== binding.ownerInstanceId || ready.instanceId !== switched.instanceId) {
      throw new Error('宿主在项目切换期间发生变化，请主动重新连接。')
    }
    return ready
  }
  return current
}

function sameRoot(left: string, right: string): boolean {
  const a = fs.realpathSync.native(left), b = fs.realpathSync.native(right)
  return process.platform === 'win32' ? a.toLowerCase() === b.toLowerCase() : a === b
}
function requireProject(manifest: RuntimeManifest, root: string): void {
  if (manifest.protocolVersion !== '1.0') throw new Error('已有 Runtime 的协议版本不受支持。')
  if (manifest.runtimeProfile !== 'PROJECT' || manifest.remote || !sameRoot(manifest.canonicalRoot, root)) {
    throw new Error('已有 Runtime 必须运行在当前 Extension Host 上，并且对应当前项目目录。')
  }
}

export function discoverProjectRuntimes(userDataDir: string, root: string): { file: string; manifest: RuntimeManifest }[] {
  const directory = path.join(userDataDir, 'runtime-domains')
  if (!fs.existsSync(directory)) return []
  const result: { file: string; manifest: RuntimeManifest }[] = []
  for (const entry of fs.readdirSync(directory, { withFileTypes: true })) {
    if (!entry.isDirectory()) continue
    const file = path.join(directory, entry.name, 'domain.json')
    try {
      if (fs.statSync(file).size > 65536) continue
      const candidate = JSON.parse(fs.readFileSync(file, 'utf8')) as RuntimeManifest
      requireProject(candidate, root)
      const manifest = domains.readTrustedManifestSync(file)
      result.push({ file, manifest })
    } catch { /* stopped, unrelated or unverified domains are not attach candidates */ }
  }
  return result
}

export async function attachProjectRuntime(file: string, root: string): Promise<AttachedRuntime> {
  const absolute = path.resolve(root, file)
  requireProject(domains.readTrustedManifestSync(absolute), root)
  const session = await domains.attachTrustedRuntime(absolute, { cwd: root })
  requireProject(session.manifest, root)
  return { ...session, file: absolute }
}

/** Never follow a recycled endpoint into another workspace or silently adopt a replacement instance. */
export function assertAttachmentCurrent(attached: AttachedRuntime): void {
  let current: RuntimeManifest, owner: { ownerInstanceId?: string }
  try {
    current = JSON.parse(fs.readFileSync(attached.file, 'utf8')) as RuntimeManifest
    owner = JSON.parse(fs.readFileSync(path.join(path.dirname(attached.file), 'owner.json'), 'utf8')) as typeof owner
  } catch { throw new AttachmentChangedError() }
  if (current.canonicalRoot !== attached.manifest.canonicalRoot) {
    throw new AttachmentChangedError(`宿主已切换到 ${current.canonicalRoot}。VS Code 仍保留当前项目，连接已停止。`)
  }
  for (const field of ['runtimeDomainId', 'ownerInstanceId', 'scopeId', 'instanceId', 'endpoint', 'canonicalRoot', 'bootTokenFile'] as const) {
    if (current[field] !== attached.manifest[field]) throw new AttachmentChangedError()
  }
  if (owner.ownerInstanceId !== attached.manifest.ownerInstanceId) throw new AttachmentChangedError()
}

export async function attachmentRequest(attached: AttachedRuntime, clientId: string, pathname: string, init: RequestInit = {}): Promise<Response> {
  assertAttachmentCurrent(attached)
  const headers = new Headers(init.headers)
  for (const [name, value] of Object.entries(attached.identityHeaders)) headers.set(name, value)
  headers.set('Authorization', `Bearer ${attached.token}`)
  headers.set('X-Harnex-Client-Id', clientId)
  const response = await fetch(`${attached.endpoint}${pathname}`, { ...init, redirect: 'error',
    signal: init.signal || AbortSignal.timeout(10000), headers })
  if (response.headers.get('X-Harnex-Scope-Id') !== attached.manifest.scopeId
      || response.headers.get('X-Harnex-Instance-Id') !== attached.manifest.instanceId) throw new Error('已有 Runtime 的响应身份不匹配。')
  assertAttachmentCurrent(attached)
  return response
}
