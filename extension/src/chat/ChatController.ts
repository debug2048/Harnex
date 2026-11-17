import type { RunCreateRequest, PendingSubmission, Presentation, MessageRecord, RunView } from '../../../packages/protocol/workbench'
import { randomUUID } from 'node:crypto'
import * as vscode from 'vscode'
import {
  HarnexClient, HttpError, PendingInteraction, RunInputView,
} from '../client/HarnexClient'
import { InteractionRouter } from '../interactions/InteractionRouter'
import { SseTap, StreamEnvelope } from '../interactions/SseTap'
import { RuntimeManager } from '../runtime/RuntimeManager'
import { StatusBar } from '../ui/StatusBar'
import { PermissionMode } from '../util/config'
import { log } from '../util/log'
import { EditorContextProvider } from '../bridge/EditorContextProvider'
import { chatModels, type ChatModel } from './modelCatalog'
import { transcriptEnvelopes } from './transcript'

const SESSION_KEY = 'harnex.chat.session.v3'
const MODEL_KEY = 'harnex.chat.model.v1'
const QUIESCENT = new Set(['PAUSED', 'READY_FOR_REVIEW', 'BLOCKED', 'BUDGET_LIMITED', 'INTERRUPTED'])
const TERMINAL = new Set(['COMPLETED', 'INCOMPLETE', 'FAILED', 'CANCELLED', 'EXPIRED'])

interface StreamCursor {
  runId: string
  lastSeq: number
  streamEpoch?: number
}

interface SavedSession {
  conversationId?: string
  runId?: string
  lastSeq: number
  streamEpoch?: number
  mode: PermissionMode
}

/**
 * Extension-host owned chat state. The SSE connection is only an incremental accelerator:
 * seq/streamEpoch form its replay cursor, while Run/Thread/Input/Interaction REST projections
 * are the authority after buffer_miss, replay_reset, replay expiry or Extension Host reload.
 * The webview remains a tokenless renderer and receives a replay of this host state.
 */
export class ChatController implements vscode.Disposable {
  private webview: vscode.Webview | undefined
  private envs: StreamEnvelope[] = []
  private conversationId: string | undefined
  private runId: string | undefined
  private cursor: StreamCursor | undefined
  private streaming = false
  private cancelRequested = false
  private pendingInputs: RunInputView[] = []
  private pendingSubmissions: PendingSubmission[] = []
  private historyRevision = 0
  private activeLeafId: string | undefined
  private ownsConversation = false
  private admission: { fingerprint: string; request: RunCreateRequest } | undefined
  private queueAdmission: { fingerprint: string; key: string } | undefined
  private mode: PermissionMode
  private models: ChatModel[] = []
  private selectedModel: { provider: string; model: string } | undefined
  private modelsLoading = false
  private modelsError: string | undefined
  private clientError: string | undefined
  private modelLoad: Promise<void> | undefined
  private streamTask: Promise<void> | undefined
  private persistTimer: NodeJS.Timeout | undefined
  private pendingSession: { key: string; value: SavedSession | undefined } | undefined
  private restoreTask: Promise<void> = Promise.resolve()
  private sessionKey: string | undefined
  private readonly runtimeStateSubscription: vscode.Disposable
  private disposed = false
  private generation = 0
  private navigating = false
  private earlierLoading = false
  private earlierCursor: string | undefined
  private archivedMessages: MessageRecord[] = []
  private snapshot: Presentation | undefined
  private readonly historicalRuns = new Map<string, RunView>()
  private historyPicker: vscode.QuickPick<HistoryItem> | undefined

  constructor(
    private readonly ctx: vscode.ExtensionContext,
    private readonly runtime: RuntimeManager,
    private readonly client: HarnexClient,
    private readonly editorContext: EditorContextProvider,
    private readonly router: InteractionRouter,
    private readonly statusBar: StatusBar,
  ) {
    this.mode = statusBar.currentMode
    const selected = ctx.workspaceState.get<{ provider: string; model: string }>(MODEL_KEY)
    if (selected && typeof selected.provider === 'string' && typeof selected.model === 'string') {
      this.selectedModel = { provider: selected.provider, model: selected.model }
    }
    this.runtimeStateSubscription = runtime.onDidChangeState(() => this.connectionChanged())
  }

  private connectionChanged(): void {
    this.clientError = undefined
    this.flushSession()
    this.generation++
    this.historyPicker?.hide()
    this.historyPicker?.dispose()
    this.historyPicker = undefined
    this.streaming = false
    this.navigating = false
    this.modelLoad = undefined
    this.modelsLoading = false
    if (this.persistTimer) clearTimeout(this.persistTimer)
    this.persistTimer = undefined
    if (this.runtime.currentState === 'running') {
      this.sessionKey = `${SESSION_KEY}:${this.runtime.currentIdentity!.workspaceId}`
      this.envs = []; this.conversationId = undefined; this.runId = undefined; this.cursor = undefined
      this.archivedMessages = []; this.earlierCursor = undefined; this.snapshot = undefined; this.historicalRuns.clear()
      this.admission = undefined; this.queueAdmission = undefined; this.pendingInputs = []; this.pendingSubmissions = []
      this.ownsConversation = false; this.cancelRequested = false; this.historyRevision = 0; this.activeLeafId = undefined
      this.models = []; this.modelsError = undefined
      this.restoreTask = this.restoreSession()
      void this.refreshModels()
      this.postReplay()
    } else {
      this.restoreTask = Promise.resolve()
      this.postState()
    }
  }

  attach(webview: vscode.Webview): vscode.Disposable {
    this.webview = webview
    return webview.onDidReceiveMessage((m: WebviewMsg) => { void this.dispatch(m) })
  }

  private async dispatch(m: WebviewMsg): Promise<void> {
    const connection = this.runtime.connectionRevision
    let acknowledged = false
    const acknowledge = (accepted: boolean) => {
      if (!m.requestId || acknowledged) return
      acknowledged = true
      void this.webview?.postMessage({ t: 'submission', requestId: m.requestId, accepted })
    }
    try {
      // A restored Run is authoritative. Do not let a fast webview message create a new
      // thread or mutate the previous Run while Extension Host recovery is still loading it.
      if (m.t !== 'ready' && m.t !== 'runtime') await this.restoreTask
      switch (m.t) {
        case 'runtime':
          await vscode.commands.executeCommand({ start: 'harnex.startRuntime', attach: 'harnex.attachRuntime',
            disconnect: 'harnex.disconnectRuntime', reconnect: 'harnex.restartBackend' }[m.action])
          break
        case 'ready': this.postReplay(); await this.runtime.startDefaultRuntime(); void this.refreshModels(); break
        case 'refreshModels': await this.refreshModels(); break
        case 'model': await this.setModel(m.ref); break
        case 'history': await this.showHistory(); break
        case 'refreshConversation': if (this.conversationId) await this.openConversation(this.conversationId); break
        case 'earlier': await this.loadEarlierMessages(); break
        case 'toolDetails': {
          if (!m.runId || !Number.isSafeInteger(m.seq) || m.seq < 1) break
          const generation = this.generation
          try {
            const detail = await this.client.toolDetails(m.runId, m.seq)
            if (generation === this.generation) void this.webview?.postMessage({ t: 'toolDetailsResult', runId: m.runId, seq: m.seq, detail })
          } catch (error) {
            if (generation === this.generation) void this.webview?.postMessage({ t: 'toolDetailsResult', runId: m.runId, seq: m.seq,
              error: error instanceof Error ? error.message : String(error) })
          }
          break
        }
        case 'send': await this.send(m.text, () => acknowledge(true), m.attachmentIds); break
        case 'steer': await this.enqueueInput(m.text, 'STEER', m.attachmentIds); acknowledge(true); break
        case 'followUp': await this.enqueueInput(m.text, 'QUEUE', m.attachmentIds); acknowledge(true); break
        case 'upload': {
          const generation = this.generation
          try {
            const asset = await this.client.importArtifact(m.name, m.mime, m.data, m.uploadId)
            if (generation === this.generation) void this.webview?.postMessage({ t: 'uploadResult', uploadId: m.uploadId, asset })
          } catch (error) {
            if (generation === this.generation) void this.webview?.postMessage({ t: 'uploadResult', uploadId: m.uploadId, error: String(error) })
          }
          break
        }
        case 'preview': {
          const url = await this.client.previewUrl(m.artifactId ? { artifactId: m.artifactId } : { html: m.html })
          await vscode.env.openExternal(await vscode.env.asExternalUri(vscode.Uri.parse(url)))
          break
        }
        case 'withdrawInput': await this.withdrawInput(m.inputId); break
        case 'cancel': await this.cancel(); break
        case 'new': await this.newThread(); break
        case 'mode': await this.setMode(m.mode); break
        case 'approval':
          await this.client.resolveApproval(m.conversationId, m.callId, m.decision, m.whitelist === true)
          this.pushLocal('approval_resolved', { callId: m.callId, decision: m.decision }, m.conversationId)
          break
        case 'question':
          await this.client.resolveQuestion(m.conversationId, m.callId, m.answer)
          this.pushLocal('question_answered', { callId: m.callId }, m.conversationId)
          break
        case 'plan':
          await this.client.resolvePlanReview(m.conversationId, m.callId, m.decision, m.feedback)
          this.pushLocal('plan_review_resolved', { callId: m.callId, decision: m.decision }, m.conversationId)
          break
      }
    } catch (err) {
      if (err instanceof Error && ['RuntimeConnectionCancelled', 'RuntimeSwitchDeclined', 'AttachmentChangedError'].includes(err.name)) return
      if (m.t === 'approval' || m.t === 'question' || m.t === 'plan') {
        this.pushLocal(err instanceof HttpError && err.status === 410 ? 'interaction_expired' : 'interaction_failed',
          { callId: m.callId }, m.conversationId)
      }
      if (err instanceof HttpError && err.status === 410) return
      const message = err instanceof Error ? err.message : String(err)
      log(`webview dispatch ${m.t} failed: ${message}`)
      // Connection failures already have a replaceable Runtime status. A previous attempt
      // must not put its error back after a newer connection has succeeded.
      if (m.t !== 'runtime' && m.t !== 'ready' && connection === this.runtime.connectionRevision) this.reportClientError(message)
    } finally {
      acknowledge(false)
    }
  }

  /** Acknowledge only the durable admission; the event subscription has its own lifetime. */
  async send(text: string, onAccepted?: () => void, attachmentIds: string[] = []): Promise<void> {
    await this.runtime.startDefaultRuntime()
    await this.runtime.ensure()
    const connection = this.runtime.connectionRevision
    await this.restoreTask
    if (connection !== this.runtime.connectionRevision || this.runtime.currentState !== 'running') throw new Error('Runtime 连接已变化，草稿已保留。')
    if (this.navigating) throw new Error('会话正在加载，草稿已保留。')
    attachmentIds = validAttachments(attachmentIds)
    if (!text?.trim() && !attachmentIds.length) return
    if (this.streaming) { await this.enqueueInput(text, 'STEER', attachmentIds); onAccepted?.(); return }
    if (this.ownsConversation && this.snapshot?.run.controlState === 'EXTERNAL' && this.runId) {
      await this.recoverProjections('external_refresh')
    }
    if (connection !== this.runtime.connectionRevision || this.runtime.currentState !== 'running') throw new Error('Runtime 连接已变化，草稿已保留。')
    if (this.ownsConversation) throw new Error(this.snapshot?.run.controlState === 'EXTERNAL'
      ? '此会话正在另一端使用，请先在那里结束任务。草稿已保留。'
      : '当前任务仍占用会话，请继续或结束任务。草稿已保留。')
    const generation = this.generation
    const intent = {
      conversationId: this.conversationId, input: text, attachmentIds, stream: false, channel: 'vscode',
      expectedHistoryRevision: this.conversationId ? this.historyRevision : undefined,
      parentMessageId: this.activeLeafId,
      execution: { kind: 'AGENT' as const, ideContext: this.editorContext.snapshot(),
        text: this.selectedModel ? { ...this.selectedModel } : undefined,
        toolsets: [this.runtime.editorToolset], permissionMode: this.mode, interactionTarget: 'local-api',
        readOnly: !vscode.workspace.isTrusted },
    }
    const fingerprint = JSON.stringify(intent)
    if (this.admission?.fingerprint !== fingerprint) this.admission = {
      fingerprint, request: { ...intent, clientRequestId: `vscode-${randomUUID()}` },
    }
    this.streaming = true
    this.cancelRequested = false
    this.postState()
    try {
      const run = await this.client.createRun(this.admission.request)
      this.admission = undefined
      onAccepted?.()
      if (generation !== this.generation) return
      this.conversationId = run.conversationId
      this.selectRun(run.runId)
      this.ownsConversation = run.ownsConversation
      this.schedulePersist()
      this.streamTask = (async () => {
        if (!await this.recoverProjections('admission', undefined, generation)) await this.resumeEvents(run.runId, generation)
        if (generation === this.generation) {
          await this.recoverProjections('run_finished', undefined, generation)
          await this.followContinuations(run.runId, generation)
        }
      })().catch(err => { if (generation === this.generation) this.pushLocal('client_error', { message: String(err) }) }).finally(() => {
        if (generation === this.generation) { this.streaming = false; this.postState(); this.schedulePersist() }
      })
    } catch (err) {
      if (err instanceof HttpError && err.status >= 400 && err.status < 500) this.admission = undefined
      if (generation === this.generation) { this.streaming = false; this.postState() }
      throw err
    }
  }

  private async resumeEvents(runId: string, generation = this.generation): Promise<void> {
    let delayMs = 250
    while (this.runId === runId && generation === this.generation) {
      const cursor = this.ensureCursor(runId)
      const params = new URLSearchParams({ afterSeq: String(cursor.lastSeq) })
      if (cursor.streamEpoch !== undefined) params.set('streamEpoch', String(cursor.streamEpoch))
      try {
        const res = await this.client.request(
          `/api/v1/runs/${encodeURIComponent(runId)}/events?${params.toString()}`)
        if (res.status === 409) {
          const body = await safeJson(res)
          if (body?.error?.code === 'replay_reset') {
            if (await this.recoverProjections('replay_reset', undefined, generation)) return
            delayMs = 250
            continue
          }
        }
        if (res.status === 410 || res.status === 404) {
          await this.recoverProjections(
            res.status === 410 ? 'event_replay_gone' : 'run_not_found', undefined, generation)
          return
        }
        if (!res.ok) throw await responseError(res, 'resume run events')
        const result = await this.consumeSse(res, generation)
        delayMs = 250
        if (result.stop) return
      } catch (err) {
        if (err instanceof ProjectionReset) {
          if (await this.recoverProjections(err.reason, undefined, generation)) return
          delayMs = 250
          continue
        }
        if (this.runId !== runId || generation !== this.generation) return
        log(`run event stream interrupted at seq=${cursor.lastSeq}: ${err instanceof Error ? err.message : err}`)
      }
      await wait(delayMs)
      delayMs = Math.min(3000, delayMs * 2)
    }
  }

  private async consumeSse(res: Response, generation: number): Promise<{ stop: boolean }> {
    if (!res.body) throw new Error('Run event response has no SSE body')
    let stop = false
    const tap = new SseTap(env => {
      if (stop) return
      if (generation !== this.generation) return
      if (env.conversationId) this.conversationId = env.conversationId
      if (env.runId && env.runId !== this.runId) this.selectRun(env.runId)
      const runId = env.runId || this.runId
      if (runId) {
        const cursor = this.ensureCursor(runId)
        if (typeof env.streamEpoch === 'number') {
          if (cursor.streamEpoch !== undefined && cursor.streamEpoch !== env.streamEpoch) {
            throw new ProjectionReset('stream_epoch_changed', env.streamEpoch)
          }
          cursor.streamEpoch = env.streamEpoch
        }
        if (env.type === 'notice' && env.data?.kind === 'buffer_miss') {
          throw new ProjectionReset('buffer_miss', cursor.streamEpoch)
        }
        if (typeof env.seq === 'number') {
          if (env.seq <= cursor.lastSeq) return
          cursor.lastSeq = env.seq
        }
      }
      if (['interaction_updated', 'message_committed', 'agent_step_committed'].includes(env.type)) throw new ProjectionReset(env.type)
      if (['approval_required', 'plan_review', 'user_question'].includes(env.type)) return
      this.push(env)
      if (env.type === 'submission_updated') void this.refreshInputs()
      if (env.type.startsWith('run_input_')) setTimeout(() => { void this.refreshInputs() }, 80)
      if (env.type === 'done' || env.type === 'cancelled'
          || env.type === 'goal_state_changed' && QUIESCENT.has(String(env.data.status))) stop = true
      this.schedulePersist()
    })
    const reader = res.body.getReader()
    const decoder = new TextDecoder()
    try {
      for (;;) {
        const { done, value } = await reader.read()
        if (done || generation !== this.generation) break
        if (value?.length) tap.feed(decoder.decode(value, { stream: true }))
        if (stop) break
      }
    } finally {
      try { await reader.cancel() } catch {}
    }
    return { stop }
  }

  /** Replace public history and live state at the cursor captured by the Runtime. */
  private async recoverProjections(reason: string, _epoch?: number, generation = this.generation): Promise<boolean> {
    const runId = this.runId
    if (!runId || generation !== this.generation) return true
    const snapshot = await this.client.presentation(runId)
    const { run, history } = snapshot
    const ancestor = history.answer?.parentMessageId || undefined
    const page = history.answer && !ancestor ? undefined : await this.client.messages(run.conversationId, ancestor)
    if (generation !== this.generation || this.runId !== runId) return true
    this.conversationId = run.conversationId
    this.ownsConversation = run.ownsConversation
    this.historyRevision = history.conversation?.historyRevision || 0
    this.activeLeafId = history.conversation?.activeLeafId
    this.pendingInputs = snapshot.inputs
    this.cursor = { runId, lastSeq: snapshot.cursor.projectionSeq, streamEpoch: snapshot.cursor.streamEpoch }
    const messages = [...(page?.items || [])].reverse()
    if (history.answer && !messages.some(item => item.messageId === history.answer?.messageId)) messages.push(history.answer)
    const overlap = messages.length ? this.archivedMessages.findIndex(item => item.messageId === messages[0].messageId) : -1
    if (overlap > 0) messages.unshift(...this.archivedMessages.slice(0, overlap))
    else this.earlierCursor = page?.nextCursor || undefined
    this.archivedMessages = messages
    this.snapshot = snapshot
    this.historicalRuns.set(runId, run)
    this.rebuildTranscript(messages, snapshot, reason)
    this.streaming = run.controlState !== 'EXTERNAL' && !TERMINAL.has(run.status) && !QUIESCENT.has(run.status)
    this.postState()
    this.schedulePersist()
    await this.refreshInputs()
    return !this.streaming || !snapshot.cursor.replayAvailable
  }

  private rebuildTranscript(messages: MessageRecord[], snapshot: Presentation, reason: string): void {
    this.historicalRuns.set(snapshot.run.runId, snapshot.run)
    const restored = transcriptEnvelopes(messages, [...this.historicalRuns.values()], snapshot)
    for (const interaction of snapshot.interactions) restored.push(interactionEnvelope(interaction))
    restored.push({ type: 'notice', data: { kind: 'projection_restored', message: `Run state restored (${reason})` }, runId: this.runId, conversationId: this.conversationId })
    this.envs = restored
    this.postReplay()
  }

  async showHistory(): Promise<void> {
    await this.runtime.startDefaultRuntime()
    await this.runtime.ensure()
    if (this.historyPicker) { this.historyPicker.show(); return }
    const picker = vscode.window.createQuickPick<HistoryItem>()
    this.historyPicker = picker
    picker.title = 'Harnex · 当前项目的历史会话'
    picker.placeholder = '搜索标题或内容摘要，选择后继续会话'
    picker.matchOnDescription = true; picker.matchOnDetail = true
    let cursor: string | undefined, items: HistoryItem[] = [], closed = false
    const load = async (more = false) => {
      if (picker.busy) return
      picker.busy = true
      try {
        const page = await this.client.conversations(more ? cursor : undefined)
        if (closed) return
        const incoming = page.items.map(item => ({ conversationId: item.conversationId,
          label: item.title || '未命名会话', description: `${item.conversationId === this.conversationId ? '当前 · ' : ''}${new Date(item.updatedAt).toLocaleString()}`,
          detail: item.preview || '' }))
        const seen = new Set(items.map(item => item.conversationId))
        items = more ? [...items, ...incoming.filter(item => !seen.has(item.conversationId))] : incoming
        cursor = page.nextCursor
        picker.items = [...items, ...(cursor ? [{ label: '$(chevron-down) 加载更多历史会话', action: 'more' as const, alwaysShow: true }] : [])]
        if (!items.length) picker.placeholder = '当前项目还没有历史会话'
      } catch (error) {
        if (!closed) {
          picker.items = [...items, { label: '$(refresh) 加载失败，点击重试', action: more ? 'more' : 'retry',
            detail: error instanceof Error ? error.message : String(error), alwaysShow: true }]
        }
      } finally { if (!closed) picker.busy = false }
    }
    try {
      const selected = await new Promise<string | undefined>(resolve => {
        picker.onDidAccept(() => {
          const item = picker.selectedItems[0]
          if (!item || picker.busy) return
          if (item.action) { void load(item.action === 'more'); return }
          resolve(item.conversationId); picker.hide()
        })
        picker.onDidHide(() => { closed = true; resolve(undefined) })
        picker.show(); void load()
      })
      if (selected) await this.openConversation(selected)
    } finally {
      closed = true; picker.dispose()
      if (this.historyPicker === picker) this.historyPicker = undefined
    }
  }

  private async openConversation(id: string): Promise<void> {
    await this.restoreTask
    await this.loadConversation(id)
  }

  /** Archived messages remain readable after the Runtime that executed their Run is gone. */
  private async loadConversation(id: string): Promise<void> {
    const connection = this.runtime.connectionRevision
    if (this.streaming || this.navigating) throw new Error('请先停止当前任务，再切换会话。')
    this.clientError = undefined
    this.navigating = true; this.postState()
    try {
      const meta = await this.client.conversation(id)
      const [runs, snapshot] = await Promise.all([
        this.client.runs(id),
        meta.latestRunId ? this.historyPresentation(meta.latestRunId) : Promise.resolve(undefined),
      ])
      const answer = snapshot?.history.answer
      const page = answer && !answer.parentMessageId ? undefined : await this.client.messages(id, answer?.parentMessageId || undefined)
      if (connection !== this.runtime.connectionRevision || this.runtime.currentState !== 'running') return
      this.flushSession()
      this.generation++
      this.conversationId = id; this.runId = meta.latestRunId
      this.admission = undefined; this.queueAdmission = undefined; this.cancelRequested = false
      this.pendingInputs = []; this.pendingSubmissions = []; this.cursor = undefined
      this.ownsConversation = false; this.streaming = false
      this.historyRevision = snapshot?.history.conversation?.historyRevision ?? page?.historyRevision ?? meta.historyRevision
      this.activeLeafId = snapshot?.history.conversation?.activeLeafId || page?.activeLeafId || meta.activeLeafId
      this.archivedMessages = [...(page?.items || [])].reverse(); this.earlierCursor = page?.nextCursor || undefined
      if (answer && !this.archivedMessages.some(message => message.messageId === answer.messageId)) this.archivedMessages.push(answer)
      this.historicalRuns.clear(); runs.runs.forEach(run => this.historicalRuns.set(run.runId, run))
      this.snapshot = snapshot
      this.ownsConversation = Boolean(runs.runs.find(run => run.runId === meta.latestRunId)?.ownsConversation)
      if (snapshot) {
        this.ownsConversation = snapshot.run.ownsConversation
        this.cursor = { runId: snapshot.run.runId, lastSeq: snapshot.cursor.projectionSeq, streamEpoch: snapshot.cursor.streamEpoch }
        this.streaming = snapshot.run.controlState !== 'EXTERNAL' && snapshot.cursor.replayAvailable && snapshot.run.executing
        this.pendingInputs = snapshot.inputs
        this.rebuildTranscript(this.archivedMessages, snapshot, 'history_selected')
      } else {
        this.envs = transcriptEnvelopes(this.archivedMessages, [...this.historicalRuns.values()])
        this.postReplay()
      }
      this.schedulePersist()
      const generation = this.generation, runId = this.runId
      if (runId && this.streaming) this.streamTask = (async () => {
        await this.resumeEvents(runId, generation)
        if (generation !== this.generation) return
        await this.recoverProjections('history_run_finished', undefined, generation)
        await this.followContinuations(runId, generation)
      })().catch(error => { if (generation === this.generation) this.pushLocal('client_error', { message: String(error) }) })
    } finally { if (connection === this.runtime.connectionRevision) { this.navigating = false; this.postState() } }
  }

  private async historyPresentation(runId: string): Promise<Presentation | undefined> {
    try { return await this.client.presentation(runId) }
    catch (error) {
      if (error instanceof HttpError && (error.status === 404 || error.status === 410)) {
        log(`Run ${runId} has no retained presentation; opening archived conversation messages`)
        return undefined
      }
      throw error
    }
  }

  private async loadEarlierMessages(): Promise<void> {
    if (!this.conversationId || !this.earlierCursor || this.earlierLoading || this.navigating) return
    const generation = this.generation, cursor = this.earlierCursor
    this.earlierLoading = true; this.postState()
    try {
      const page = await this.client.messages(this.conversationId, undefined, cursor)
      if (generation !== this.generation || cursor !== this.earlierCursor) return
      const seen = new Set(this.archivedMessages.map(item => item.messageId))
      const older = page.items.filter(item => !seen.has(item.messageId)).reverse()
      const visibleRuns = new Set(this.archivedMessages.map(message => message.runId))
      this.archivedMessages.unshift(...older)
      this.earlierCursor = page.nextCursor || undefined
      this.envs.unshift(...transcriptEnvelopes(older, [...this.historicalRuns.values()].filter(run => !visibleRuns.has(run.runId))))
      this.postReplay()
    } finally { this.earlierLoading = false; this.postState() }
  }

  private async restoreSession(): Promise<void> {
    const connection = this.runtime.connectionRevision
    const sessionKey = this.sessionKey
    if (!sessionKey) return
    const saved = this.ctx.workspaceState.get<SavedSession>(sessionKey)
    if (!saved?.conversationId) return
    if (saved.mode === 'ask' || saved.mode === 'accept-edits' || saved.mode === 'yolo') {
      this.mode = saved.mode
      this.statusBar.setMode(saved.mode)
    }
    try {
      await this.loadConversation(saved.conversationId)
    } catch (err) {
      if (connection !== this.runtime.connectionRevision) return
      log(`cannot restore VS Code chat session: ${err instanceof Error ? err.message : err}`)
      if (err instanceof HttpError && (err.status === 404 || err.status === 410)) {
        this.conversationId = undefined; this.runId = undefined; this.cursor = undefined
        this.streaming = false; this.ownsConversation = false
        await this.ctx.workspaceState.update(sessionKey, undefined)
        this.postReplay()
      } else this.reportClientError(err)
    }
  }

  private selectRun(runId: string): void {
    if (this.runId !== runId) this.pendingInputs = []
    this.runId = runId
    this.cursor = { runId, lastSeq: 0 }
    void this.refreshInputs()
    if (this.cancelRequested) void this.cancel().catch(err => this.pushLocal('client_error', { message: String(err) }))
  }

  private ensureCursor(runId: string): StreamCursor {
    if (!this.cursor || this.cursor.runId !== runId) this.cursor = { runId, lastSeq: 0 }
    return this.cursor
  }

  private async enqueueInput(text: string, mode: 'STEER' | 'QUEUE', attachmentIds: string[] = []): Promise<void> {
    const runId = this.runId
    const generation = this.generation
    attachmentIds = validAttachments(attachmentIds)
    if (!text.trim() && !attachmentIds.length) throw new Error('消息内容为空')
    if (!this.streaming) throw new Error('当前任务已结束，草稿已保留，可再次发送开始新任务。')
    if (!runId) throw new Error('任务正在启动，请稍后重试。草稿已保留。')
    await this.client.requireRunInputs()
    if (this.runId !== runId || this.generation !== generation || !this.streaming) throw new Error('任务状态已改变，草稿已保留。')
    if (mode === 'QUEUE') {
      const run = await this.client.run(runId)
      const fingerprint = JSON.stringify([runId, text, attachmentIds, run.executionSpec])
      if (this.queueAdmission?.fingerprint !== fingerprint) this.queueAdmission = { fingerprint, key: `vscode-queue-${randomUUID()}` }
      const submission = await this.client.enqueueSubmission(run, text.trim(), this.queueAdmission.key, attachmentIds)
      this.queueAdmission = undefined
      if (this.runId === runId && this.generation === generation) {
        this.pendingSubmissions.push(submission)
        this.postState()
      }
      return
    }
    const result = await this.client.enqueueRunInput(runId, mode, text.trim(), attachmentIds)
    if (this.runId !== runId || this.generation !== generation) return
    this.upsertInput(result.input)
    this.pushLocal('run_input_queued', { inputId: result.input.inputId, mode, status: result.input.status })
  }

  private async withdrawInput(inputId: string): Promise<void> {
    if (!this.runId) return
    const queued = this.pendingSubmissions.find(item => item.submissionId === inputId)
    if (queued && this.conversationId) {
      const updated = await this.client.cancelSubmission(this.conversationId, inputId)
      this.pendingSubmissions = this.pendingSubmissions.map(item => item.submissionId === inputId ? updated : item)
      this.postState()
      return
    }
    const result = await this.client.withdrawRunInput(this.runId, inputId)
    this.upsertInput(result.input)
  }

  private async refreshInputs(): Promise<void> {
    const runId = this.runId
    if (!runId) { this.pendingInputs = []; this.postState(); return }
    try {
      const [result, submissions] = await Promise.all([this.client.runInputs(runId), this.conversationId
        ? this.client.submissions(this.conversationId) : Promise.resolve({ items: [] as PendingSubmission[] })])
      if (this.runId === runId) {
        const current = new Map(this.pendingInputs.map(input => [input.inputId, input]))
        for (const input of result.inputs || []) {
          if ((current.get(input.inputId)?.revision || 0) <= input.revision) current.set(input.inputId, input)
        }
        this.pendingInputs = [...current.values()].sort((a, b) => a.sequence - b.sequence)
        const queue = new Map(this.pendingSubmissions.map(item => [item.submissionId, item]))
        for (const item of submissions.items) if ((queue.get(item.submissionId)?.revision || 0) <= item.revision) queue.set(item.submissionId, item)
        this.pendingSubmissions = [...queue.values()].sort((a, b) => a.ordinal - b.ordinal)
        this.postState()
      }
    } catch { /* Runtime can be between instances during restart. */ }
  }

  private upsertInput(input: RunInputView): void {
    const index = this.pendingInputs.findIndex(item => item.inputId === input.inputId)
    if (index >= 0) {
      if (this.pendingInputs[index].revision > input.revision) return
      this.pendingInputs.splice(index, 1, input)
    }
    else this.pendingInputs.push(input)
    this.pendingInputs.sort((a, b) => a.sequence - b.sequence)
    this.postState()
  }

  private async followContinuations(sourceRunId: string | undefined, generation: number): Promise<void> {
    if (!this.conversationId || !sourceRunId) return
    const source = await this.client.run(sourceRunId)
    for (let attempt = 0; attempt < 20; attempt++) {
      if (generation !== this.generation) return
      if (attempt > 0) await wait(400)
      const queue = await this.client.submissions(this.conversationId)
      const sourceSubmission = queue.items.find(item => item.runId === sourceRunId)
      const next = queue.items.filter(item => item.predecessorSubmissionId
        ? item.predecessorSubmissionId === sourceSubmission?.submissionId : item.predecessorTurnId === source.turnId)
        .sort((a, b) => a.ordinal - b.ordinal)[0]
      if (!next) return
      if (next.runId) {
        this.selectRun(next.runId)
        if (!await this.recoverProjections('queued_run', undefined, generation)) await this.resumeEvents(next.runId, generation)
        await this.recoverProjections('queued_run_finished', undefined, generation)
        return this.followContinuations(next.runId, generation)
      }
      if (!['QUEUED', 'STARTING'].includes(next.state)) return
    }
    await this.refreshInputs()
  }

  async cancel(): Promise<void> {
    await this.restoreTask
    // During admission runId still identifies the previous turn. selectRun will
    // deliver this stop intent once the new request has a durable Run identity.
    if (this.streaming && this.admission) { this.cancelRequested = true; return }
    if (!this.runId) { this.cancelRequested = this.streaming; return }
    const runId = this.runId
    this.cancelRequested = false
    const run = await this.client.run(runId)
    if (run.goalId) { await this.client.cancelGoal(run.runId, run.revision); return }
    const response = await this.client.request(`/api/v1/runs/${encodeURIComponent(runId)}/cancel`, { method: 'POST' })
    if (!response.ok) throw await responseError(response, 'stop task')
    await this.refreshInputs()
  }

  async newThread(): Promise<void> {
    await this.restoreTask
    if (this.navigating) throw new Error('请等待会话加载完成。')
    this.flushSession()
    this.clientError = undefined
    const previousRunId = this.runId
    this.generation += 1
    this.cancelRequested = false
    if (this.streaming && previousRunId) {
      await this.client.request(`/api/v1/runs/${encodeURIComponent(previousRunId)}/cancel`, { method: 'POST' })
        .catch(() => {})
    }
    this.envs = []
    this.archivedMessages = []; this.earlierCursor = undefined; this.snapshot = undefined; this.historicalRuns.clear()
    this.admission = undefined
    this.queueAdmission = undefined
    this.pendingSubmissions = []
    this.ownsConversation = false
    this.historyRevision = 0
    this.activeLeafId = undefined
    this.conversationId = undefined
    this.runId = undefined
    this.cursor = undefined
    this.pendingInputs = []
    this.streaming = false
    if (this.sessionKey) await this.ctx.workspaceState.update(this.sessionKey, undefined)
    this.postReplay()
  }

  async setMode(mode: PermissionMode): Promise<void> {
    await this.restoreTask
    if (mode !== 'ask' && mode !== 'accept-edits' && mode !== 'yolo') return
    this.mode = mode
    this.statusBar.setMode(mode)
    this.postState()
    this.schedulePersist()
    if (this.conversationId) await this.client.setMode(this.conversationId, mode).catch(() => {})
  }

  private refreshModels(): Promise<void> {
    if (this.runtime.currentState !== 'running') return Promise.resolve()
    if (this.modelLoad) return this.modelLoad
    const connection = this.runtime.connectionRevision
    const current = () => !this.disposed && connection === this.runtime.connectionRevision && this.runtime.currentState === 'running'
    this.modelsLoading = true
    this.modelsError = undefined
    this.postState()
    this.modelLoad = Promise.resolve().then(async () => {
      try {
        const models = chatModels(await this.client.models())
        if (current()) this.models = models
      } catch (error) {
        if (current()) this.modelsError = error instanceof Error ? error.message : String(error)
      } finally {
        if (!current()) return
        this.modelsLoading = false
        this.modelLoad = undefined
        if (!this.disposed) this.postState()
      }
    })
    return this.modelLoad
  }

  private async setModel(ref: string): Promise<void> {
    try {
      if (this.streaming || this.ownsConversation) throw new Error('请在当前任务结束后切换模型。')
      if (typeof ref !== 'string') throw new Error('模型选择无效')
      const selected = this.models.find(model => model.ref === ref)
      if (ref && !selected) throw new Error('该模型已不可用，请刷新模型列表后重新选择。')
      this.selectedModel = selected ? { provider: selected.provider, model: selected.model } : undefined
      await this.ctx.workspaceState.update(MODEL_KEY, this.selectedModel)
    } finally {
      this.postState()
    }
  }

  get currentMode(): PermissionMode { return this.mode }
  isStreaming(): boolean { return this.streaming }

  dispose(): void {
    this.flushSession()
    this.disposed = true
    this.webview = undefined
    this.runId = undefined // terminates any reconnect loop without changing durable state
    if (this.persistTimer) clearTimeout(this.persistTimer)
    this.persistTimer = undefined
    this.runtimeStateSubscription.dispose()
    this.historyPicker?.dispose()
  }

  private push(env: StreamEnvelope): void {
    if (this.disposed) return
    this.envs.push(env)
    if (this.envs.length > 5000) this.envs.splice(0, this.envs.length - 5000)
    void this.webview?.postMessage({ t: 'env', env })
    try { this.router.handle(env) } catch (err) {
      log(`router.handle(${env.type}) failed: ${err instanceof Error ? err.message : err}`)
    }
  }

  private pushLocal(type: string, data: Record<string, unknown>, conversationId?: string): void {
    if (type === 'client_error') { this.reportClientError(data.message); return }
    this.push({ type, data, conversationId: conversationId ?? this.conversationId, ts: Date.now() })
  }

  /** Client failures are current UI state, not durable conversation messages. */
  reportClientError(error: unknown): void {
    if (this.runtime.currentState === 'running') this.clientError = error instanceof Error ? error.message : String(error ?? '未知错误')
    this.postState()
  }

  private schedulePersist(): void {
    if (this.runtime.currentState !== 'running' || !this.sessionKey) return
    if (this.persistTimer) clearTimeout(this.persistTimer)
    this.pendingSession = { key: this.sessionKey, value: this.conversationId ? {
        conversationId: this.conversationId,
        runId: this.runId,
        lastSeq: this.cursor?.lastSeq || 0,
        streamEpoch: this.cursor?.streamEpoch,
        mode: this.mode,
      } : undefined }
    this.persistTimer = setTimeout(() => this.flushSession(), 80)
  }

  private flushSession(): void {
    if (this.persistTimer) clearTimeout(this.persistTimer)
    this.persistTimer = undefined
    const pending = this.pendingSession
    this.pendingSession = undefined
    if (pending) void this.ctx.workspaceState.update(pending.key, pending.value)
  }

  private state() {
    return {
      streaming: this.streaming,
      navigating: this.navigating,
      hasEarlier: Boolean(this.earlierCursor),
      earlierLoading: this.earlierLoading,
      externalBusy: this.ownsConversation && this.snapshot?.run.controlState === 'EXTERNAL',
      mode: this.mode,
      models: this.models,
      selectedModel: this.selectedModel ? `${this.selectedModel.provider}:${this.selectedModel.model}` : '',
      modelsLoading: this.modelsLoading,
      modelsError: this.modelsError,
      clientError: this.clientError,
      modelLocked: this.streaming || this.ownsConversation,
      conversationId: this.conversationId ?? null,
      runId: this.runId ?? null,
      runtime: this.runtime.currentState,
      runtimeLabel: this.runtime.connectionLabel,
      runtimeError: this.runtime.lastError,
      attached: this.runtime.isAttached,
      assetScope: JSON.stringify(this.runtime.currentIdentity || null),
      pendingInputs: [...this.pendingInputs, ...this.pendingSubmissions.map(item => ({
        inputId: item.submissionId, mode: 'QUEUE', text: item.request.input, status: item.state, terminalReason: item.reason,
      }))],
    }
  }

  private postState(): void { void this.webview?.postMessage({ t: 'state', state: this.state() }) }
  private postReplay(): void {
    void this.webview?.postMessage({ t: 'replay', envs: this.envs, state: this.state() })
  }
}

class ProjectionReset extends Error {
  constructor(readonly reason: string, readonly streamEpoch?: number) { super(reason) }
}

function interactionEnvelope(item: PendingInteraction): StreamEnvelope {
  const type = item.kind === 'approval' ? 'approval_required'
    : item.kind === 'plan_review' ? 'plan_review' : 'user_question'
  return {
    type,
    data: { callId: item.callId, conversationId: item.conversationId, ...item.payload },
    conversationId: item.conversationId,
    ts: item.createdAt,
  }
}

async function responseError(response: Response, operation: string): Promise<Error> {
  const body = await safeJson(response)
  const detail = body?.error?.message || body?.error?.code || `HTTP ${response.status}`
  return new Error(`${operation} failed: ${detail}`)
}

async function safeJson(response: Response): Promise<any> {
  try { return await response.json() } catch { return undefined }
}

function number(value: unknown): number | undefined {
  const parsed = Number(value)
  return Number.isFinite(parsed) ? parsed : undefined
}

function wait(ms: number): Promise<void> { return new Promise(resolve => setTimeout(resolve, ms)) }

function validAttachments(value: string[] = []) {
  if (!Array.isArray(value) || value.length > 8 || value.some(id => typeof id !== 'string' || !/^[A-Za-z0-9_-]+$/.test(id))) throw new Error('Invalid attachment references')
  return [...new Set(value)]
}

type WebviewMsg = (
  | { t: 'runtime'; action: 'start' | 'attach' | 'disconnect' | 'reconnect' }
  | { t: 'ready' }
  | { t: 'refreshModels' }
  | { t: 'model'; ref: string }
  | { t: 'history' }
  | { t: 'refreshConversation' }
  | { t: 'earlier' }
  | { t: 'toolDetails'; runId: string; seq: number }
  | { t: 'send'; text: string; attachmentIds?: string[] }
  | { t: 'steer'; text: string; attachmentIds?: string[] }
  | { t: 'followUp'; text: string; attachmentIds?: string[] }
  | { t: 'upload'; name: string; mime: string; data: string; uploadId: string }
  | { t: 'preview'; artifactId?: string; html?: string }
  | { t: 'withdrawInput'; inputId: string }
  | { t: 'cancel' }
  | { t: 'new' }
  | { t: 'mode'; mode: PermissionMode }
  | { t: 'approval'; conversationId: string; callId: string; decision: 'allow' | 'deny'; whitelist?: boolean }
  | { t: 'question'; conversationId: string; callId: string; answer: string }
  | { t: 'plan'; conversationId: string; callId: string; decision: 'approve' | 'reject'; feedback?: string }
) & { requestId?: string }

interface HistoryItem extends vscode.QuickPickItem {
  conversationId?: string
  action?: 'more' | 'retry'
}
