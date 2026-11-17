import type { RunCreateRequest, RunView, GoalView as WireGoal, RunInputView as WireRunInput, PendingSubmission, Presentation, MessagePage, ConversationMeta, SummaryPage, RunToolDetail, PendingInteraction as WirePendingInteraction } from '../../../packages/protocol/workbench'
import * as fs from 'node:fs'
import { RuntimeManager } from '../runtime/RuntimeManager'
import { PermissionMode } from '../util/config'
import { log } from '../util/log'

/**
 * REST client over the Harnex Local API v1 (docs/03 §3.14). Auth: local trusted boot —
 * read the owning Runtime Domain's boot-token, exchange it at POST /api/v1/auth/boot-token for a session token,
 * attach it as Authorization: Bearer on every call (docs/03 §3.14). The token lives only
 * in the extension host; the webview sends actions through ChatController and never sees it.
 */
export class HarnexClient {
  private token: string | undefined
  private handshakePromise: Promise<void> | undefined
  private identity: { workspaceId: string; instanceId: string } | undefined
  private protocolCache: { name: string; version: string; runtimeVersion: string; capabilities: string[] } | undefined
  private connectionRevision = -1

  constructor(private readonly runtime: RuntimeManager) {}

  async baseUrl(): Promise<string> {
    return this.runtime.ensure()
  }

  authHeader(): Record<string, string> {
    return this.token ? { Authorization: `Bearer ${this.token}` } : {}
  }

  /** Exchange this managed Domain's boot token. Idempotent & coalesced. */
  private handshake(): Promise<void> {
    if (!this.handshakePromise) {
      const pending = this.doHandshake().catch(err => {
        if (this.handshakePromise === pending) this.handshakePromise = undefined
        throw err
      })
      this.handshakePromise = pending
    }
    return this.handshakePromise
  }

  private async doHandshake(): Promise<void> {
    const base = await this.runtime.ensure()
    const revision = this.runtime.connectionRevision
    const candidate = this.readBootToken()
    if (!candidate) {
      // Auth may be disabled (harnex.auth.enabled=false) — proceed tokenless; 401s will surface.
      log('no boot-token file found — proceeding without a session token')
      return
    }
    const res = await fetch(`${base}/api/v1/auth/boot-token`, {
      method: 'POST',
      signal: this.runtime.connectionSignal,
      headers: { 'content-type': 'application/json', 'X-Harnex-Client-Id': this.runtime.clientId,
        ...(this.runtime.currentIdentity ? { 'X-Harnex-Scope-Id': this.runtime.currentIdentity.workspaceId,
          'X-Harnex-Instance-Id': this.runtime.currentIdentity.instanceId } : {}) },
      body: JSON.stringify({ token: candidate }),
    })
    this.requireConnection(revision)
    this.captureIdentity(res)
    if (!res.ok) {
      log(`boot-token handshake failed: HTTP ${res.status}`)
      return
    }
    const body = await res.json() as { token?: string }
    this.requireConnection(revision)
    if (!body.token) throw new Error('boot-token handshake returned no session token')
    this.token = body.token
    log('boot-token handshake ok')
  }

  private readBootToken(): string | undefined {
    try {
      const value = fs.readFileSync(this.runtime.connectionBootTokenFile, 'utf8').trim()
      return value || undefined
    } catch { return undefined }
  }

  /**
   * Raw authenticated fetch. Session tokens are in-memory server-side (24h/2h sliding) —
   * on 401 redo the handshake once and retry, so a runtime restart heals transparently.
   */
  async request(pathname: string, init?: RequestInit & { headers?: Record<string, string> }): Promise<Response> {
    const base = await this.runtime.ensure()
    const revision = this.runtime.connectionRevision
    const managed = this.runtime.currentIdentity
    if (this.connectionRevision !== revision) {
      this.connectionRevision = revision
      this.handshakePromise = undefined
      this.token = undefined
      this.identity = undefined
      this.protocolCache = undefined
    }
    await this.handshake().catch(() => {})
    this.requireConnection(revision)
    const pinned = this.identity ? { ...this.identity } : managed && { ...managed }
    const validateTarget = (response: Response) => {
      this.requireConnection(revision)
      const current = this.runtime.currentIdentity
      const workspaceId = response.headers.get('X-Harnex-Scope-Id')
      const instanceId = response.headers.get('X-Harnex-Instance-Id')
      if (!pinned || workspaceId !== pinned.workspaceId || instanceId !== pinned.instanceId
          || current && (current.workspaceId !== pinned.workspaceId || current.instanceId !== pinned.instanceId)) {
        throw new Error('Runtime target changed during the request; reconnect before retrying')
      }
    }
    const doFetch = () => { this.requireConnection(revision); return fetch(`${base}${pathname}`, {
      ...init,
      signal: init?.signal || this.runtime.connectionSignal,
      headers: {
        ...init?.headers,
        ...this.authHeader(),
        'X-Harnex-Client-Id': this.runtime.clientId,
        ...(pinned ? {
          'X-Harnex-Scope-Id': pinned.workspaceId,
          'X-Harnex-Instance-Id': pinned.instanceId,
        } : {}),
      },
    }) }
    let res = await doFetch()
    validateTarget(res)
    if (res.status === 401) {
      this.handshakePromise = undefined
      this.token = undefined
      await this.handshake().catch(() => {})
      if (!pinned || this.identity?.workspaceId !== pinned.workspaceId || this.identity.instanceId !== pinned.instanceId) {
        throw new Error('Runtime target changed during authentication')
      }
      res = await doFetch()
      validateTarget(res)
    }
    return res
  }

  private requireConnection(revision: number): void {
    if (this.runtime.currentState !== 'running' || this.runtime.connectionRevision !== revision) {
      throw new Error('Runtime 连接已变化，请等待连接就绪后重试。')
    }
  }

  private captureIdentity(response: Response): boolean {
    const workspaceId = response.headers.get('X-Harnex-Scope-Id')
    const instanceId = response.headers.get('X-Harnex-Instance-Id')
    if (!workspaceId || !instanceId) {
      throw new Error('Runtime response omitted the workspace/instance identity guard')
    }
    const expected = this.runtime.currentIdentity
    if (expected && (expected.workspaceId !== workspaceId || expected.instanceId !== instanceId)) {
      throw new Error('Runtime response identity does not match the managed Runtime instance')
    }
    const changed = this.identity !== undefined
      && (this.identity.workspaceId !== workspaceId || this.identity.instanceId !== instanceId)
    this.identity = { workspaceId, instanceId }
    if (changed) this.protocolCache = undefined
    return changed
  }

  private async json<T>(pathname: string, init?: RequestInit & { headers?: Record<string, string> }): Promise<T> {
    const res = await this.request(pathname, init)
    const revision = this.runtime.connectionRevision
    if (!res.ok) {
      let detail = ''
      try {
        const b = await res.json() as { error?: { code?: string; message?: string } }
        detail = b?.error ? ` ${b.error.code}: ${b.error.message}` : ''
      } catch { /* non-JSON error body */ }
      throw new HttpError(res.status, `HTTP ${res.status} ${pathname}${detail}`)
    }
    if (res.status === 204) return undefined as T
    const body = await res.json() as T
    this.requireConnection(revision)
    const current = this.runtime.currentIdentity
    if (current && (res.headers.get('X-Harnex-Scope-Id') !== current.workspaceId
        || res.headers.get('X-Harnex-Instance-Id') !== current.instanceId)) throw new Error('Discarded response from a previous Runtime')
    return body
  }

  async importArtifact(name: string, mime: string, data: string, requestId: string) {
    if (data.length > 24 * 1024 * 1024) throw new Error('附件超过 16 MiB，请使用 Web 界面上传更大的文件')
    const bytes = Buffer.from(data, 'base64')
    if (!bytes.length || bytes.length > 16 * 1024 * 1024) throw new Error('附件大小不受支持')
    const form = new FormData(); form.append('file', new Blob([bytes], { type: mime }), name); form.append('clientRequestId', requestId)
    const response = await this.request('/api/v1/artifacts/imports', { method: 'POST', body: form })
    if (!response.ok) throw new Error(`Upload failed: HTTP ${response.status}`)
    return response.json() as Promise<{ artifactId: string; name: string; mime: string }>
  }
  models(): Promise<unknown> { return this.json('/api/v1/models') }
  async previewUrl(source: { artifactId?: string; html?: string }) {
    const base = await this.runtime.ensure()
    const result = await this.json<{ url: string }>('/api/v1/previews', { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify(source) })
    if (!result.url.startsWith('/api/v1/preview-content/')) throw new Error('Invalid preview URL')
    return new URL(result.url, base).href
  }

  protocol(): Promise<{ name: string; version: string; runtimeVersion: string; capabilities: string[] }> {
    if (this.protocolCache) return Promise.resolve(this.protocolCache)
    return this.json<{ name: string; version: string; runtimeVersion: string; capabilities: string[] }>(
      '/api/v1/protocol').then(value => {
        if (value.version !== '1.0') throw new Error(`Unsupported Harnex Local API protocol: ${value.version}`)
        this.protocolCache = value
        return value
      })
  }

  async requireRunInputs(): Promise<void> {
    const protocol = await this.protocol()
    if (!protocol.capabilities.includes('runs.inputs.steer')
      || !protocol.capabilities.includes('conversation.submissions.v1')) {
      throw new Error('This Harnex Runtime does not expose Run Input capabilities')
    }
  }

  runInputs(runId: string): Promise<{ inputs: RunInputView[] }> {
    return this.json(`/api/v1/runs/${encodeURIComponent(runId)}/inputs`)
  }

  enqueueRunInput(runId: string, mode: 'STEER', text: string, attachmentIds: string[] = []): Promise<{ input: RunInputView }> {
    return this.json(`/api/v1/runs/${encodeURIComponent(runId)}/inputs`, {
      method: 'POST', headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ mode, text, attachmentIds, channel: 'vscode', clientRequestId: randomId('vscode-input') }),
    })
  }

  withdrawRunInput(runId: string, inputId: string): Promise<{ input: RunInputView }> {
    return this.json(`/api/v1/runs/${encodeURIComponent(runId)}/inputs/${encodeURIComponent(inputId)}`
      + `?clientRequestId=${encodeURIComponent(randomId('vscode-withdraw'))}`, { method: 'DELETE' })
  }

  goals(conversationId?: string): Promise<{ goals: GoalView[] }> {
    const query = conversationId ? `?conversationId=${encodeURIComponent(conversationId)}` : ''
    return this.json(`/api/v1/goals${query}`)
  }

  goal(goalId: string): Promise<GoalView> {
    return this.json(`/api/v1/goals/${encodeURIComponent(goalId)}`)
  }

  run(runId: string): Promise<RunProjection> {
    return this.json(`/api/v1/runs/${encodeURIComponent(runId)}`)
  }

  runs(conversationId: string): Promise<{ runs: RunProjection[] }> {
    return this.json(`/api/v1/runs?conversationId=${encodeURIComponent(conversationId)}`)
  }

  createRun(body: RunCreateRequest): Promise<RunView> {
    return this.json('/api/v1/runs', { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify(body) })
  }

  conversation(id: string): Promise<ConversationMeta> { return this.json(`/api/v1/conversations/${encodeURIComponent(id)}`) }
  conversations(cursor?: string): Promise<SummaryPage> {
    const query = new URLSearchParams({ limit: '40' })
    if (cursor) query.set('cursor', cursor)
    return this.json(`/api/v1/conversations?${query}`)
  }
  messages(id: string, leafId?: string, cursor?: string): Promise<MessagePage> {
    const query = new URLSearchParams({ limit: '100' })
    if (leafId) query.set('leafId', leafId)
    if (cursor) query.set('cursor', cursor)
    return this.json(`/api/v1/conversations/${encodeURIComponent(id)}/messages?${query}`)
  }
  presentation(id: string): Promise<Presentation> { return this.json(`/api/v1/runs/${encodeURIComponent(id)}/presentation`) }
  toolDetails(runId: string, seq: number): Promise<RunToolDetail> {
    return this.json(`/api/v1/runs/${encodeURIComponent(runId)}/tools/${seq}`)
  }
  submissions(id: string): Promise<{ items: PendingSubmission[] }> { return this.json(`/api/v1/conversations/${encodeURIComponent(id)}/submissions`) }
  enqueueSubmission(run: RunView, text: string, clientRequestId: string, attachmentIds: string[] = []): Promise<PendingSubmission> {
    return this.json(`/api/v1/conversations/${encodeURIComponent(run.conversationId)}/submissions`, {
      method: 'POST', headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ predecessorTurnId: run.turnId, input: text, attachmentIds, execution: run.executionSpec, channel: 'vscode', clientRequestId }),
    })
  }
  cancelSubmission(id: string, submissionId: string): Promise<PendingSubmission> {
    return this.json(`/api/v1/conversations/${encodeURIComponent(id)}/submissions/${encodeURIComponent(submissionId)}/cancel`, { method: 'POST' })
  }

  pauseGoal(runId: string, revision: number): Promise<RunProjection> {
    return this.goalControl(runId, 'pause', revision)
  }

  resumeGoal(runId: string, revision: number): Promise<RunProjection> {
    return this.goalControl(runId, 'resume', revision)
  }

  cancelGoal(runId: string, revision: number): Promise<RunProjection> {
    return this.goalControl(runId, 'cancel', revision)
  }

  private goalControl(runId: string, operation: 'pause' | 'resume' | 'cancel',
    revision: number): Promise<RunProjection> {
    return this.json(`/api/v1/runs/${encodeURIComponent(runId)}/${operation}`, {
      method: 'POST', headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ revision, clientRequestId: randomId(`vscode-goal-${operation}`),
        ...(operation === 'resume' ? { interactionTarget: 'local-api' } : {}) }),
    })
  }

  diagnostics(): Promise<unknown> {
    return this.json('/api/v1/diagnostics')
  }

  pendingInteractions(): Promise<PendingInteraction[]> {
    return this.json<{ interactions: PendingInteraction[] }>('/api/v1/interactions?status=pending')
      .then(value => value.interactions || [])
  }

  resolveApproval(conversationId: string, callId: string, decision: 'allow' | 'deny', whitelist = false): Promise<void> {
    return this.json(`/api/v1/agent/conversations/${encodeURIComponent(conversationId)}/interactions/${encodeURIComponent(callId)}`, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ kind: 'approval', decision, whitelist }),
    })
  }

  resolveQuestion(conversationId: string, callId: string, answer: string): Promise<void> {
    return this.json(`/api/v1/agent/conversations/${encodeURIComponent(conversationId)}/interactions/${encodeURIComponent(callId)}`, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ kind: 'question', answer }),
    })
  }

  resolvePlanReview(conversationId: string, callId: string, decision: 'approve' | 'reject', feedback?: string): Promise<void> {
    return this.json(`/api/v1/agent/conversations/${encodeURIComponent(conversationId)}/interactions/${encodeURIComponent(callId)}`, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ kind: 'plan_review', decision, feedback }),
    })
  }

  setMode(conversationId: string, mode: PermissionMode): Promise<unknown> {
    // mode is a request param, not a JSON body (AgentConversationController @RequestParam).
    return this.json(`/api/v1/agent/conversations/${encodeURIComponent(conversationId)}/mode?mode=${encodeURIComponent(mode)}`, {
      method: 'PUT',
    })
  }
}

export class HttpError extends Error {
  constructor(readonly status: number, message: string) {
    super(message)
  }
}

export type PendingInteraction = WirePendingInteraction

export type RunInputView = WireRunInput
export type GoalView = WireGoal
export type RunProjection = RunView

function randomId(prefix: string): string {
  return `${prefix}-${Date.now()}-${Math.random().toString(36).slice(2, 10)}`
}
