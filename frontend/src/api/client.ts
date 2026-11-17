import type * as Workbench from '../../../packages/protocol/workbench'
import type { ImChannel, ImConnection, ImCredentialSelection, ImDocument, NotificationSettings } from '../domain/im'
import type {
  AutomationAttempt, AutomationDraft, AutomationJob, AutomationResult, AutomationTargetView, SchedulePreview,
} from '../domain/automations'
import type { SearchProbeResult } from '../domain/searchSettings'
import { fetchEventSource } from '@microsoft/fetch-event-source'
import type {
  ProtocolDocument,
  RuntimeDiagnostics,
  RuntimeStatusline,
  ChangesView,
  FileDiff,
  FilePage,
  FilePreview,
  FileSearchPage,
  ConfigDocument,
  McpServerStatus,
  EffectiveSkillDto,
  MemoryDocument,
  RunView,
  RunInputView,
  RunInputMode,
  GoalView,
  RuntimeWorkspace,
  WorkspaceGit,
} from './contracts'
import { isRecord } from '../utils/unknown'
import { isMacPlatform } from '../utils/platform'

export interface RuntimeIdentity { scopeId: string; instanceId: string }
export interface RuntimeTarget { readonly identity: Readonly<RuntimeIdentity> | null; readonly base: string; readonly remote: boolean }
export type PreviewSession = Workbench.PreviewSession
export type PreviewRequest = Workbench.PreviewRequest
let apiIdentity: RuntimeIdentity | null = null
let runtimeApiBase = ''
let remoteRuntime = false
let runtimeRevision = 0
const identityListeners = new Set<() => void>()
const responseRevisions = new WeakMap<Response, number>()
function identityChanged() { runtimeRevision++; for (const listener of identityListeners) listener() }
export function getRuntimeRevision() { return runtimeRevision }
export function onRuntimeIdentityChanged(listener: () => void) { identityListeners.add(listener); return () => identityListeners.delete(listener) }
export function setRemoteRuntime(remote: boolean) { if (remoteRuntime !== remote) { remoteRuntime = remote; identityChanged() } }
function assertRuntimeRevision(revision: number) {
  if (revision !== runtimeRevision) throw new DOMException('Workspace changed during the request', 'AbortError')
}

function captureRuntimeTarget(): RuntimeTarget {
  return Object.freeze({ identity: apiIdentity ? Object.freeze({ ...apiIdentity }) : null,
    base: runtimeApiBase, remote: remoteRuntime })
}

async function nativeAuthorization(target: RuntimeTarget, rejectedToken?: string): Promise<string | null> {
  if (!isTauriHost() || !target.identity) return null
  const session = await tauriInvoke<{ instanceId: string; scopeId: string; token: string } | null>(
    'runtime_session_token', { scopeId: target.identity.scopeId, instanceId: target.identity.instanceId,
      ...(rejectedToken ? { rejectedToken } : {}) })
  if (!session && !target.remote && !rejectedToken) return null
  if (!session || session.instanceId !== target.identity.instanceId || session.scopeId !== target.identity.scopeId) {
    throw new Error('Runtime instance changed; reconnect to the same space before retrying')
  }
  return session.token
}

/** A 401 is rejected before dispatch; retry one replayable request on the same instance. */
async function fetchWithNativeSession(url: RequestInfo | URL, init: RequestInit, target: RuntimeTarget,
  token: string | null, beforeRetry: () => void = () => {}) {
  const response = await fetch(url, init)
  if (response.status !== 401 || !token || !isTauriHost()) return response
  assertResponseTarget(response, target, true)
  const renewed = await nativeAuthorization(target, token)
  beforeRetry()
  if (init.signal?.aborted) throw new DOMException('Aborted', 'AbortError')
  if (!renewed || renewed === token || typeof ReadableStream !== 'undefined' && init.body instanceof ReadableStream) return response
  await response.body?.cancel()
  const headers = new Headers(init.headers)
  headers.set('Authorization', `Bearer ${renewed}`)
  return fetch(url, { ...init, headers })
}

type TauriInternals = { invoke?: (command: string, args?: Record<string, unknown> | Uint8Array,
  options?: {headers:Record<string,string>}) => Promise<unknown> }

export function isTauriHost(): boolean {
  return typeof (globalThis as typeof globalThis & { __TAURI_INTERNALS__?: TauriInternals })
    .__TAURI_INTERNALS__?.invoke === 'function'
}

async function tauriInvoke<T>(command: string, args: Record<string, unknown> = {}): Promise<T> {
  const invoke = (globalThis as typeof globalThis & { __TAURI_INTERNALS__?: TauriInternals })
    .__TAURI_INTERNALS__?.invoke
  if (!invoke) throw new Error('Tauri workspace host is unavailable')
  return await invoke(command, args) as T
}

/** Configure the Runtime origin while the trusted Tauri SPA remains on tauri.localhost. */
export function setRuntimeApiBase(base: string) {
  const next = String(base || '').replace(/\/$/, '')
  if (runtimeApiBase !== next) { runtimeApiBase = next; identityChanged() }
}

/** Preserve automatic OS tracking instead of pinning the WebView to a previously resolved colour. */
export async function syncNativeWindowTheme(mode: 'light' | 'dark' | 'system'): Promise<void> {
  if (!isTauriHost()) return
  await tauriInvoke('window_set_theme', { mode })
}

/** Move newly-entered provider keys into macOS Keychain before persisting config. */
export async function protectNativeProviderSecrets(
    values: Record<string, unknown>,
    onStored?: (reference: string) => void,
): Promise<Record<string, unknown>> {
  if (!isTauriHost() || !isMacPlatform() || remoteRuntime) return values
  const next = JSON.parse(JSON.stringify(values || {})) as Record<string, unknown>
  const entries = [
    ...Object.entries(isRecord(next.providers) ? next.providers : {}),
    ...Object.entries(isRecord(next['image-services']) ? next['image-services'] : {}).map(([id, raw]) => [`image:${id}`, raw] as const),
  ]
  const revision = runtimeRevision
  for (const [providerId, raw] of entries) {
    assertRuntimeRevision(revision)
    if (!isRecord(raw)) continue
    const field = Object.prototype.hasOwnProperty.call(raw, 'api-key') ? 'api-key' : 'apiKey'
    const secret = String(raw[field] || '')
    if (!secret || secret === '••••••••' || secret.startsWith('$')) continue
    const stored = await tauriInvoke<{ reference: string }>('provider_secret_store', { providerId, secret })
    assertRuntimeRevision(revision)
    raw[field] = stored.reference
    onStored?.(stored.reference)
  }
  return next
}

export async function revealNativeProviderSecret(providerId: string): Promise<string | null> {
  if (!isTauriHost() || !isMacPlatform() || remoteRuntime) return null
  try {
    const result = await tauriInvoke<{ apiKey?: string }>('provider_secret_reveal', { providerId })
    return result.apiKey || ''
  } catch {
    // Legacy desktop installs may still have a chmod-600 YAML secret and no Keychain entry.
    // Let the backend reveal path handle that migration case.
    return null
  }
}

export async function deleteNativeProviderSecrets(providerIds: string[]): Promise<void> {
  if (!isTauriHost() || !isMacPlatform() || remoteRuntime) return
  await Promise.all(providerIds.map(providerId =>
    tauriInvoke('provider_secret_delete', { providerId })))
}

function runtimeUrl(path: string, target: RuntimeTarget) {
  return target.base && path.startsWith('/') ? `${target.base}${path}` : path
}

/** Stamp every workspace-scoped request so a late tab/request cannot land in a replacement JVM. */
export function setApiIdentity(identity: RuntimeIdentity | null) {
  const changed = apiIdentity?.scopeId !== identity?.scopeId || apiIdentity?.instanceId !== identity?.instanceId
  apiIdentity = identity ? { ...identity } : null
  if (changed) identityChanged()
}

export type RuntimeConnectionState = {
  connected: boolean
  message?: string
}

const runtimeConnectionListeners = new Set<(state: RuntimeConnectionState) => void>()
let lastRuntimeConnectionState: RuntimeConnectionState = { connected: true }

/** Subscribe to transport health without coupling the API layer to a Pinia store. */
export function onRuntimeConnection(listener: (state: RuntimeConnectionState) => void) {
  runtimeConnectionListeners.add(listener)
  listener(lastRuntimeConnectionState)
  return () => runtimeConnectionListeners.delete(listener)
}

function publishRuntimeConnection(state: RuntimeConnectionState) {
  if (lastRuntimeConnectionState.connected === state.connected
      && lastRuntimeConnectionState.message === state.message) return
  lastRuntimeConnectionState = state
  for (const listener of runtimeConnectionListeners) listener(state)
}

let workbenchClientId: string | undefined
function clientIdentity(): string {
  if (workbenchClientId) return workbenchClientId
  const fresh = `workbench-${globalThis.crypto?.randomUUID?.() || `${Date.now()}-${Math.random().toString(36).slice(2)}`}`
  try {
    const saved = localStorage.getItem('harnex:client-identity')
    workbenchClientId = saved && /^workbench-[A-Za-z0-9-]+$/.test(saved) ? saved : fresh
    localStorage.setItem('harnex:client-identity', workbenchClientId)
  } catch { workbenchClientId = fresh }
  return workbenchClientId
}

async function authFetch(url: string, init?: RequestInit, pinned?: RuntimeTarget): Promise<Response> {
  // The user-level GUI has no interactive account lifecycle. credentials stays enabled
  // solely for hosts that silently exchanged a boot token while explicit API auth is on.
  // CSRF (Double-Submit Cookie): for any state-changing method we read the
  // browser-readable XSRF-TOKEN cookie and echo it as X-XSRF-TOKEN. The server's
  // CsrfFilter rejects the request when the two don't match.
  const method = (init?.method || 'GET').toUpperCase()
  const revision = runtimeRevision
  const target = pinned || captureRuntimeTarget()
  const reportsForegroundConnection = !pinned || target.base === runtimeApiBase && target.remote === remoteRuntime
    && target.identity?.scopeId === apiIdentity?.scopeId && target.identity?.instanceId === apiIdentity?.instanceId
  const identity = target.identity
  const headers = new Headers(init?.headers || {})
  headers.set('X-Harnex-Client-Id', clientIdentity())
  const hostToken = await nativeAuthorization(target)
  if (!pinned) assertRuntimeRevision(revision)
  if (hostToken) headers.set('Authorization', `Bearer ${hostToken}`)
  if (method !== 'GET' && method !== 'HEAD' && method !== 'OPTIONS') {
    const csrf = readCsrfCookie()
    if (csrf) headers.set('X-XSRF-TOKEN', csrf)
  }
  // Per-request traceId for end-to-end correlation. Server echoes back X-Harnex-Trace-Id;
  // we surface it in error messages so users can quote it when reporting bugs.
  if (!headers.has('X-Harnex-Trace-Id')) {
    headers.set('X-Harnex-Trace-Id', mintTraceId())
  }
  if (identity) {
    headers.set('X-Harnex-Scope-Id', identity.scopeId)
    headers.set('X-Harnex-Instance-Id', identity.instanceId)
  }
  try {
    const response = await fetchWithNativeSession(runtimeUrl(url, target), {
      ...init,
      headers,
      credentials: target.base ? 'omit' : 'same-origin',
    }, target, hostToken, () => { if (!pinned) assertRuntimeRevision(revision) })
    if (!pinned) { assertRuntimeRevision(revision); responseRevisions.set(response, revision) }
    assertResponseTarget(response, target)
    if (revision !== runtimeRevision || !reportsForegroundConnection) return response
    if (response.status === 502 || response.status === 503 || response.status === 504) {
      publishRuntimeConnection({ connected: false, message: `HTTP ${response.status}` })
    } else {
      // A reachable Runtime may legitimately reject a request with 4xx. That is not a
      // connection failure and must dismiss a stale disconnected banner.
      publishRuntimeConnection({ connected: true })
    }
    return response
  } catch (cause: unknown) {
    if (reportsForegroundConnection && revision === runtimeRevision && !(cause instanceof DOMException && cause.name === 'AbortError')) {
      publishRuntimeConnection({
        connected: false,
        message: cause instanceof Error ? cause.message : String(cause),
      })
    }
    throw cause
  }
}

function assertResponseTarget(response: Response, target: RuntimeTarget, includeError = false) {
  if (!response.ok && !includeError || !target.identity) return
  if (response.headers.get('X-Harnex-Scope-Id') !== target.identity.scopeId
      || response.headers.get('X-Harnex-Instance-Id') !== target.identity.instanceId) {
    throw new ApiHttpError(409, 'runtime_instance_changed', 'Response belongs to a different Runtime instance')
  }
}

/** Its origin, scope and instance stay fixed for the lifetime of a submission or Run session. */
export class RuntimeClient {
  readonly target: RuntimeTarget
  readonly api: ReturnType<typeof createApi>
  constructor(target: RuntimeTarget = captureRuntimeTarget()) {
    this.target = Object.freeze({ ...target, identity: target.identity ? Object.freeze({ ...target.identity }) : null })
    this.api = createApi((url, init) => authFetch(url, init, this.target))
  }
  stream(runId: string, afterSeq: number, handler: StreamHandler, signal?: AbortSignal, streamEpoch?: number) {
    return streamRunEvents(runId, afterSeq, handler, signal, streamEpoch, this.target)
  }
}

/** Bound read-only recovery requests, including an implementation that stalls after response headers. */
export async function withReadTimeout<T>(read: (signal: AbortSignal) => Promise<T>, signal?: AbortSignal): Promise<T> {
  if (signal?.aborted) throw new DOMException('Aborted', 'AbortError')
  const controller = new AbortController()
  let timer: ReturnType<typeof setTimeout> | undefined
  let cancel = () => {}
  const timeout = new Promise<never>((_resolve, reject) => {
    cancel = () => { reject(new DOMException('Aborted', 'AbortError')); controller.abort() }
    signal?.addEventListener('abort', cancel, { once: true })
    timer = setTimeout(() => {
      reject(new ApiHttpError(504, 'run_read_timeout', 'Run data request timed out'))
      controller.abort()
    }, 30_000)
  })
  try { return await Promise.race([read(controller.signal), timeout]) }
  finally { if (timer) clearTimeout(timer); signal?.removeEventListener('abort', cancel) }
}

function mintTraceId(): string {
  try {
    const u = globalThis.crypto?.randomUUID?.()
    if (u) return String(u).replace(/-/g, '')
  } catch { /* fallthrough */ }
  return 't' + Date.now().toString(36) + Math.random().toString(36).slice(2, 10)
}

/** Read the non-HttpOnly XSRF-TOKEN cookie (set by the server CsrfFilter). */
function readCsrfCookie(): string | null {
  try {
    const m = document.cookie.match(/(?:^|;\s*)XSRF-TOKEN=([^;]+)/)
    return m ? decodeURIComponent(m[1]) : null
  } catch { return null }
}

/**
 * Parse a JSON response, throwing a useful Error on non-2xx so callers don't silently
 * try to consume an HTML error page as JSON. Used by every `api.*` helper below.
 */
export class ApiHttpError extends Error {
  constructor(public readonly status: number, public readonly code: string, detail: string,
    public readonly details: Record<string, unknown> = {}) {
    super(`HTTP ${status}${detail ? `: ${detail}` : ''}`)
    this.name = 'ApiHttpError'
  }
}

async function asJson<T = any>(res: Response): Promise<T> {
  if (!res.ok) {
    let detail = ''
    let code = ''
    let details: Record<string, unknown> = {}
    try {
      const j = await res.clone().json()
      // Unified error envelope: {error:{code,message}}; older shapes kept as fallback.
      code = j?.error?.code || j?.code || j?.reason || (typeof j?.error === 'string' ? j.error : '')
      detail = j?.error?.message || j?.message || (typeof j?.error === 'string' ? j.error : '')
        || (Array.isArray(j?.warnings) ? j.warnings.slice(0, 3).join('; ').slice(0, 2000) : '') || j?.reason || ''
      if (isRecord(j?.error)) details = j.error
    } catch {
      try { detail = (await res.clone().text()).slice(0, 200) } catch { /* ignore */ }
    }
    throw new ApiHttpError(res.status, code, detail, details)
  }
  // Some endpoints return 204 / empty body — guard against JSON.parse('') throwing.
  const text = await res.text()
  const revision = responseRevisions.get(res)
  if (revision !== undefined) assertRuntimeRevision(revision)
  return (text ? JSON.parse(text) : undefined) as T
}

export type ChatReq = Workbench.RunCreateRequest

export interface ProviderProbeResult {
  success: boolean
  outcome?: 'success' | 'inconclusive' | 'failure'
  mode?: 'stream' | 'one-shot'
  attempts?: number
  httpProtocol?: string | null
  code: string
  message: string
  provider: string
  profile: string
  model: string
  latencyMs: number
  billable: boolean
}

export interface ProviderModelCatalogResult {
  provider: string
  models: string[]
  source: 'upstream' | string
}

/** Envelope of every run event; seq and streamEpoch together identify the replay cursor. */
export interface RunEventEnvelope {
  type: string
  data: Record<string, unknown>
  runId?: string
  conversationId?: string
  seq?: number
  streamEpoch?: number
  ts?: number
}

export interface RunReplayCursor {
  lastSeq: number
  streamEpoch?: number
  done?: boolean
}

/** The live stream cannot fill a history gap: rebuild the UI from REST projections before resuming. */
export class RunReplayResetError extends Error {
  constructor(readonly runId: string,
    readonly reason: 'replay_reset' | 'stream_epoch_changed' | 'buffer_miss' | 'event_replay_gone',
    readonly streamEpoch?: number) {
    super(`Run event replay requires projection recovery: ${reason}`)
    this.name = 'RunReplayResetError'
  }
}

export interface StreamHandler {
  onEvent: (type: string, data: Record<string, unknown>, envelope?: RunEventEnvelope) => void
  onReplayReset?: (reset: RunReplayResetError) => Promise<RunReplayCursor>
  onError?: (e: unknown) => void
  onDone?: () => void
}

export interface RunListView {
  runs: RunView[]
}

/** Error thrown when run creation is rejected with a structured code (thread_busy, duplicate_request, …). */
export class RunRejectedError extends Error {
  code: string
  constructor(code: string, message: string) { super(message); this.name = 'RunRejectedError'; this.code = code }
}

/**
 * Create a run and consume its SSE inline: `POST /api/v1/runs` with `stream:true`.
 *
 * The create POST is never replayed. Once the run identity is known, interrupted streams resume through
 * {@link resumeRunEvents} using `afterSeq` and `streamEpoch`. Structured rejections
 * (thread_busy / duplicate_request / …) surface as {@link RunRejectedError} with the server's error code.
 */
export async function streamChat(req: ChatReq, h: StreamHandler, signal?: AbortSignal) {
  const target = captureRuntimeTarget()
  const body = JSON.stringify({ ...req, stream: true })
  try {
    let first: SseAttempt
    try {
      first = await consumeSseAttempt('/api/v1/runs', { method: 'POST', body }, h, signal, undefined, target)
    } catch (cause) {
      if (cause instanceof RunReplayResetError) {
        await resumeRunEvents(cause.runId, 0, h, signal, cause.streamEpoch, cause, target)
        return
      }
      if (cause instanceof InterruptedSseError && cause.runId) {
        await resumeRunEvents(cause.runId, cause.lastSeq, h, signal, cause.streamEpoch, undefined, target)
        return
      }
      throw cause
    }
    if (first.done) { h.onDone?.(); return }
    if (!first.runId) throw new FatalSseError('Create stream closed before run identity was received')
    await resumeRunEvents(first.runId, first.lastSeq, h, signal, first.streamEpoch, undefined, target)
  } catch (cause) {
    if (!signal?.aborted) h.onError?.(cause)
    throw cause
  }
}

/** Subscribe to an existing run's events with afterSeq replay — refresh / route return / reattach path. */
export async function streamRunEvents(runId: string, afterSeq: number, h: StreamHandler, signal?: AbortSignal,
  streamEpoch?: number, target: RuntimeTarget = captureRuntimeTarget()) {
  try {
    await resumeRunEvents(runId, afterSeq, h, signal, streamEpoch, undefined, target)
  } catch (cause) {
    if (!signal?.aborted) h.onError?.(cause)
    throw cause
  }
}

interface SseAttempt extends RunReplayCursor { runId?: string; done: boolean }
const RETRYABLE_STREAM_STATUS = new Set([408, 429, 500, 502, 503, 504])
function retryableRecovery(cause: unknown) {
  return cause instanceof TypeError && /fetch|network|load failed/i.test(cause.message)
    || cause instanceof ApiHttpError && RETRYABLE_STREAM_STATUS.has(cause.status)
}

async function resumeRunEvents(runId: string, afterSeq: number, h: StreamHandler, signal?: AbortSignal,
  streamEpoch?: number, reset?: RunReplayResetError, target: RuntimeTarget = captureRuntimeTarget()) {
  let cursor = Math.max(0, afterSeq)
  let retryMs = 250
  while (!signal?.aborted) {
    if (reset) {
      if (!h.onReplayReset) throw reset
      let restored: RunReplayCursor
      try { restored = await h.onReplayReset(reset) }
      catch (cause) {
        if (signal?.aborted) return
        if (!retryableRecovery(cause)) throw cause
        await abortableDelay(retryMs, signal)
        retryMs = Math.min(3_000, retryMs * 2)
        continue
      }
      if (signal?.aborted) return
      cursor = Math.max(0, restored.lastSeq)
      streamEpoch = restored.streamEpoch ?? reset.streamEpoch ?? streamEpoch
      reset = undefined
      if (restored.done) { h.onDone?.(); return }
    }
    const params = new URLSearchParams({ afterSeq: String(cursor) })
    if (streamEpoch !== undefined) params.set('streamEpoch', String(streamEpoch))
    const url = `/api/v1/runs/${encodeURIComponent(runId)}/events?${params}`
    try {
      const attempt = await consumeSseAttempt(url, { method: 'GET' }, h, signal,
        { runId, lastSeq: cursor, streamEpoch }, target)
      if (attempt.lastSeq > cursor) retryMs = 250
      cursor = Math.max(cursor, attempt.lastSeq)
      streamEpoch = attempt.streamEpoch ?? streamEpoch
      if (attempt.done) { h.onDone?.(); return }
    } catch (cause) {
      if (signal?.aborted) throw cause
      if (cause instanceof RunReplayResetError) {
        reset = cause
        retryMs = 250
        continue
      }
      if (!(cause instanceof InterruptedSseError)) throw cause
      cursor = Math.max(cursor, cause.lastSeq)
      streamEpoch = cause.streamEpoch ?? streamEpoch
    }
    await abortableDelay(retryMs, signal)
    retryMs = Math.min(3_000, retryMs * 2)
  }
}

async function consumeSseAttempt(url: string, init: { method: string; body?: string }, h: StreamHandler,
  signal?: AbortSignal, cursor?: RunReplayCursor & { runId: string }, target: RuntimeTarget = captureRuntimeTarget()) {
  const revision = runtimeRevision
  const identity = target.identity
  // traceId: REST helpers in authFetch() always send X-Harnex-Trace-Id; SSE has its own
  // hand-rolled headers block here, so we must mint the same header explicitly.
  const clientTraceId = mintTraceId()
  let serverTraceId: string | null = null
  let receivedDone = false
  let runId = cursor?.runId
  let lastSeq = cursor?.lastSeq ?? 0
  let streamEpoch = cursor?.streamEpoch
  const hostToken = await nativeAuthorization(target)
  if (signal?.aborted) return { ...cursor, done: false, lastSeq: cursor?.lastSeq || 0 }
  const connection = new AbortController()
  const abortConnection = () => connection.abort()
  signal?.addEventListener('abort', abortConnection, { once: true })
  let lastActivity = Date.now(), stalled = false
  let idleTimer: ReturnType<typeof setTimeout> | undefined
  function checkIdle() {
    const remaining = 60_000 - (Date.now() - lastActivity)
    if (remaining > 0) idleTimer = setTimeout(checkIdle, remaining)
    else { stalled = true; connection.abort() }
  }
  idleTimer = setTimeout(checkIdle, 60_000)
  try {
    await fetchEventSource(runtimeUrl(url, target), {
      fetch: (url, options) => fetchWithNativeSession(url, options || {}, target, hostToken),
      method: init.method,
      headers: (() => {
        const hdr: Record<string, string> = {}
        hdr['X-Harnex-Client-Id'] = clientIdentity()
        if (hostToken) hdr.Authorization = `Bearer ${hostToken}`
        if (init.body) hdr['Content-Type'] = 'application/json'
        const csrf = readCsrfCookie()
        if (csrf) hdr['X-XSRF-TOKEN'] = csrf
        hdr['X-Harnex-Trace-Id'] = clientTraceId
        if (identity) {
          hdr['X-Harnex-Scope-Id'] = identity.scopeId
          hdr['X-Harnex-Instance-Id'] = identity.instanceId
        }
        return hdr
      })(),
      body: init.body,
      credentials: target.base ? 'omit' : 'same-origin',
      signal: connection.signal,
      openWhenHidden: true,
      async onopen(res) {
        assertResponseTarget(res, target)
        const echoed = res.headers.get('X-Harnex-Trace-Id')
        if (echoed) serverTraceId = echoed
        if (res.ok && res.headers.get('content-type')?.includes('text/event-stream')) {
          lastActivity = Date.now()
          publishRuntimeConnection({ connected: true })
          return
        }
        if (res.status === 401) {
          throw new FatalSseError(`API authentication required [trace ${serverTraceId ?? clientTraceId}]`)
        }
        // Resubscriptions are idempotent GETs. A proxy restart/rate limit must not end the Run in the UI.
        if (init.method === 'GET' && RETRYABLE_STREAM_STATUS.has(res.status)) {
          throw new InterruptedSseError(`HTTP ${res.status} [trace ${serverTraceId ?? clientTraceId}]`, runId, lastSeq, streamEpoch)
        }
        // Structured rejection (unified error envelope) → typed error the store can branch on.
        try {
          const j = await res.clone().json()
          const code = j?.error?.code
          if (runId && ((res.status === 409 && code === 'replay_reset')
              || (res.status === 410 && code === 'event_replay_gone'))) {
            throw new RunReplayResetError(runId, code,
              typeof j.streamEpoch === 'number' ? j.streamEpoch : streamEpoch)
          }
          if (code) {
            throw new RunRejectedError(String(code),
              `${j?.error?.message || code} [trace ${serverTraceId ?? clientTraceId}]`)
          }
        } catch (e) {
          if (e instanceof RunRejectedError || e instanceof RunReplayResetError) throw e
          /* body wasn't the envelope — fall through to the generic error */
        }
        throw new FatalSseError(`HTTP ${res.status} [trace ${serverTraceId ?? clientTraceId}]`)
      },
      onmessage(ev) {
        if (signal?.aborted || connection.signal.aborted) return
        lastActivity = Date.now() // Comment-only heartbeats count as connection activity too.
        if (!ev.event) return
        let parsed: unknown = ev.data
        try { parsed = JSON.parse(ev.data) } catch { /* plain text */ }
        // v1 wire format: the SSE data IS the envelope, including its replay generation.
        const rawEnvelope = isRecord(parsed) ? parsed : { data: parsed }
        const rawData = 'data' in rawEnvelope ? rawEnvelope.data : rawEnvelope
        const data = isRecord(rawData)
          ? rawData
          : { value: rawData, ...(typeof rawData === 'string' ? { text: rawData } : {}) }
        const envelope: RunEventEnvelope = {
          type: typeof rawEnvelope.type === 'string' ? rawEnvelope.type : ev.event,
          data,
          runId: typeof rawEnvelope.runId === 'string' ? rawEnvelope.runId : undefined,
          conversationId: typeof rawEnvelope.conversationId === 'string' ? rawEnvelope.conversationId : undefined,
          seq: typeof rawEnvelope.seq === 'number' ? rawEnvelope.seq : undefined,
          streamEpoch: typeof rawEnvelope.streamEpoch === 'number' ? rawEnvelope.streamEpoch : undefined,
          ts: typeof rawEnvelope.ts === 'number' ? rawEnvelope.ts : undefined,
        }
        if (envelope.runId) runId = envelope.runId
        if (envelope.streamEpoch !== undefined) {
          if (runId && streamEpoch !== undefined && streamEpoch !== envelope.streamEpoch) {
            throw new RunReplayResetError(runId, 'stream_epoch_changed', envelope.streamEpoch)
          }
          streamEpoch = envelope.streamEpoch
        }
        if (runId && envelope.type === 'notice' && data.kind === 'buffer_miss') {
          throw new RunReplayResetError(runId, 'buffer_miss', streamEpoch)
        }
        if (envelope.type === 'done') receivedDone = true
        if (typeof envelope.seq === 'number') {
          if (envelope.seq <= lastSeq) return
          lastSeq = envelope.seq
        }
        h.onEvent(ev.event, data, envelope)
      },
      onerror(err) {
        // Always bail — reconnect is handled by streamRunEvents(afterSeq), never by re-POSTing.
        const tid = serverTraceId ?? clientTraceId
        const msg = err instanceof Error ? err.message : String(err)
        if (err instanceof RunRejectedError || err instanceof RunReplayResetError || err instanceof FatalSseError || err instanceof ApiHttpError) throw err
        if (/\[trace [A-Za-z0-9_-]+\]/.test(msg)) throw err
        throw new Error(`${msg} [trace ${tid}]`)
      },
      onclose() {
        // A transport close is never a run terminal signal. The caller resumes from lastSeq unless `done` arrived.
      }
    })
    if (stalled && !signal?.aborted && !receivedDone) {
      throw new InterruptedSseError('Event stream heartbeat timed out', runId, lastSeq, streamEpoch)
    }
    return { runId, lastSeq, streamEpoch, done: receivedDone } satisfies SseAttempt
  } catch (e: unknown) {
    if (e instanceof RunReplayResetError) throw e
    if (e instanceof RunRejectedError) throw e
    if (e instanceof FatalSseError || e instanceof ApiHttpError) throw e
    if (revision === runtimeRevision && !(e instanceof DOMException && e.name === 'AbortError')) {
      publishRuntimeConnection({
        connected: false,
        message: e instanceof Error ? e.message : String(e),
      })
    }
    const msg = e instanceof Error ? e.message : String(e)
    const tagged = /\[trace [A-Za-z0-9_-]+\]/.test(msg)
      ? msg : `${msg} [trace ${serverTraceId ?? clientTraceId}]`
    if (e instanceof DOMException && e.name === 'AbortError') throw e
    throw new InterruptedSseError(tagged, runId, lastSeq, streamEpoch)
  } finally {
    if (idleTimer) clearTimeout(idleTimer)
    signal?.removeEventListener('abort', abortConnection)
  }
}

function abortableDelay(ms: number, signal?: AbortSignal) {
  if (signal?.aborted) return Promise.reject(new DOMException('Aborted', 'AbortError'))
  return new Promise<void>((resolve, reject) => {
    const timer = globalThis.setTimeout(done, ms)
    function done() { signal?.removeEventListener('abort', aborted); resolve() }
    function aborted() {
      globalThis.clearTimeout(timer)
      signal?.removeEventListener('abort', aborted)
      reject(new DOMException('Aborted', 'AbortError'))
    }
    signal?.addEventListener('abort', aborted, { once: true })
  })
}

class InterruptedSseError extends Error {
  constructor(message: string, readonly runId: string | undefined, readonly lastSeq: number,
    readonly streamEpoch?: number) {
    super(message); this.name = 'InterruptedSseError'
  }
}

/** Sentinel marking errors that MUST NOT be retried by the streamChat reconnect logic. */
class FatalSseError extends Error {
  constructor(msg: string) { super(msg); this.name = 'FatalSseError' }
}

function createApi(authFetch: (url: string, init?: RequestInit) => Promise<Response>) {
return {
  createRun: (body: ChatReq): Promise<RunView> => authFetch('/api/v1/runs', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ ...body, stream: false }) }).then(asJson),
  retryMessage: (id: string, messageId: string, body: Workbench.Retry): Promise<RunView> => authFetch(`/api/v1/conversations/${encodeURIComponent(id)}/messages/${encodeURIComponent(messageId)}/retry`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) }).then(asJson),
  reviseMessage: (id: string, messageId: string, body: Workbench.Revision): Promise<Workbench.CommitReceipt> => authFetch(`/api/v1/conversations/${encodeURIComponent(id)}/messages/${encodeURIComponent(messageId)}/revisions`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) }).then(asJson),
  deleteConversation: (id: string, body: Workbench.Delete): Promise<Workbench.CommitReceipt> => authFetch(`/api/v1/conversations/${encodeURIComponent(id)}`, { method: 'DELETE', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) }).then(asJson),
  selectBranch: (id: string, body: Workbench.Branch): Promise<Workbench.CommitReceipt> => authFetch(`/api/v1/conversations/${encodeURIComponent(id)}/branches/selection`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) }).then(asJson),
  conversations: (cursor?: string, limit = 40): Promise<Workbench.SummaryPage> =>
    authFetch(`/api/v1/conversations?limit=${limit}${cursor ? `&cursor=${encodeURIComponent(cursor)}` : ''}`).then(asJson),
  conversation: (id: string, signal?: AbortSignal): Promise<Workbench.ConversationMeta> => authFetch(`/api/v1/conversations/${encodeURIComponent(id)}`, { signal }).then(asJson),
  message: (id: string, messageId: string): Promise<Workbench.MessageRecord> => authFetch(`/api/v1/conversations/${encodeURIComponent(id)}/messages/${encodeURIComponent(messageId)}`).then(asJson),
  turn: (id: string, turnId: string): Promise<Workbench.TurnRecord> => authFetch(`/api/v1/conversations/${encodeURIComponent(id)}/turns/${encodeURIComponent(turnId)}`).then(asJson),
  executionRecord: (id: string, recordId: string): Promise<Workbench.ExecutionSearchRecord> => authFetch(`/api/v1/conversations/${encodeURIComponent(id)}/execution-records/${encodeURIComponent(recordId)}`).then(asJson),
  forkConversation: (id: string, body: Workbench.Fork): Promise<Workbench.CommitReceipt> => authFetch(`/api/v1/conversations/${encodeURIComponent(id)}/forks`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) }).then(asJson),
  messages: (id: string, cursor?: string, leafId?: string, limit = 40, signal?: AbortSignal): Promise<Workbench.MessagePage> => {
    const params = new URLSearchParams({ limit: String(limit) }); if (cursor) params.set('cursor', cursor); if (leafId) params.set('leafId', leafId)
    return authFetch(`/api/v1/conversations/${encodeURIComponent(id)}/messages?${params}`, { signal }).then(asJson)
  },
  presentation: (runId: string, signal?: AbortSignal): Promise<Workbench.Presentation> => authFetch(`/api/v1/runs/${encodeURIComponent(runId)}/presentation`, { signal }).then(asJson),
  runToolDetails: (runId: string, seq: number, signal?: AbortSignal): Promise<Workbench.RunToolDetail> =>
    authFetch(`/api/v1/runs/${encodeURIComponent(runId)}/tools/${seq}`, { signal }).then(asJson),
  subagentResult: (runId: string, threadId: string): Promise<Workbench.RunSubagentResult> =>
    authFetch(`/api/v1/runs/${encodeURIComponent(runId)}/subagents/${encodeURIComponent(threadId)}/result`).then(asJson),
  usage: (runId: string): Promise<Workbench.UsageReport> => authFetch(`/api/v1/runs/${encodeURIComponent(runId)}/usage`).then(asJson),
  modelCalls: (runId: string): Promise<Workbench.ModelCallRecord[]> => authFetch(`/api/v1/runs/${encodeURIComponent(runId)}/model-calls`).then(asJson),
  callDiagnosticsStatus: (runId: string): Promise<Workbench.DiagnosticStatus> => authFetch(`/api/v1/runs/${encodeURIComponent(runId)}/model-calls/status`).then(asJson),
  contextPlans: (runId: string): Promise<Workbench.ContextPlan[]> => authFetch(`/api/v1/runs/${encodeURIComponent(runId)}/context-plans`).then(asJson),
  imageServices: (): Promise<Workbench.Catalog> => authFetch('/api/v1/image-services').then(asJson),
  imageOperation: (id: string): Promise<Workbench.ImageOperationRecord> => authFetch(`/api/v1/image-operations/${encodeURIComponent(id)}`).then(asJson),
  createPreview: (source: PreviewRequest): Promise<PreviewSession> => authFetch('/api/v1/previews', {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(source),
  }).then(asJson),
  closePreview: (id: string) => authFetch(`/api/v1/previews/${encodeURIComponent(id)}`, { method: 'DELETE' }).then(asJson),
  artifact: (id: string): Promise<Workbench.ArtifactRecord> => authFetch(`/api/v1/artifacts/${encodeURIComponent(id)}`).then(asJson),
  importArtifact: (file: Blob, filename: string, clientRequestId: string): Promise<Workbench.ArtifactRecord> => {
    const data = new FormData(); data.append('file', file, filename); data.append('clientRequestId', clientRequestId)
    return authFetch('/api/v1/artifacts/imports', { method: 'POST', body: data }).then(asJson)
  },
  artifactContent: async (id: string): Promise<Blob> => {
    // Revalidate even legacy cached responses: image bytes persist, but their Runtime identity changes on restart.
    const response = await authFetch(`/api/v1/artifacts/${encodeURIComponent(id)}/content`, { cache: 'no-cache' })
    if (!response.ok) await asJson(response)
    const bytes = await response.blob()
    const revision = responseRevisions.get(response)
    if (revision !== undefined) assertRuntimeRevision(revision)
    return bytes
  },
  submissions: (id: string, signal?: AbortSignal): Promise<Workbench.PendingSubmissionPage> => authFetch(`/api/v1/conversations/${encodeURIComponent(id)}/submissions`, { signal }).then(asJson),
  enqueueSubmission: (id: string, body: Workbench.PendingSubmissionCreateRequest): Promise<Workbench.PendingSubmission> => authFetch(`/api/v1/conversations/${encodeURIComponent(id)}/submissions`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) }).then(asJson),
  cancelSubmission: (id: string, submissionId: string): Promise<Workbench.PendingSubmission> => authFetch(`/api/v1/conversations/${encodeURIComponent(id)}/submissions/${encodeURIComponent(submissionId)}/cancel`, { method: 'POST' }).then(asJson),
  protocol: (): Promise<ProtocolDocument> => authFetch('/api/v1/protocol').then(asJson),
  diagnostics: (): Promise<RuntimeDiagnostics> => authFetch('/api/v1/diagnostics').then(asJson),
  openDataDirectory: () => authFetch('/api/v1/storage/open', { method: 'POST' }).then(asJson),
  tools:    () => authFetch('/api/v1/tools').then(asJson),
  toolsets: () => authFetch('/api/v1/toolsets').then(asJson),
  mcpServers: (): Promise<{ servers: McpServerStatus[]; totalTools: number }> => authFetch('/api/mcp/servers').then(asJson),
  models:   () => authFetch('/api/v1/models').then(asJson),
  /** ---------- Runs (the single execution resource) ---------- */
  /** Discovery: find e.g. the RUNNING run of a thread to reattach after refresh/route return. */
  runs: (threadId?: string, status?: string, signal?: AbortSignal): Promise<RunListView> => {
    const q = new URLSearchParams()
    if (threadId) q.set('conversationId', threadId)
    if (status) q.set('status', status)
    const qs = q.toString()
    return authFetch(`/api/v1/runs${qs ? `?${qs}` : ''}`, { signal }).then(asJson)
  },
  run: (runId: string, signal?: AbortSignal): Promise<RunView> =>
    authFetch(`/api/v1/runs/${encodeURIComponent(runId)}`, { signal }).then(asJson),
  cancelRun: (runId: string) =>
    authFetch(`/api/v1/runs/${encodeURIComponent(runId)}/cancel`, { method: 'POST' }).then(asJson),
  goals: (threadId?: string, status?: string): Promise<{ goals: GoalView[] }> => {
    const q = new URLSearchParams()
    if (threadId) q.set('conversationId', threadId)
    if (status) q.set('status', status)
    const qs = q.toString()
    return authFetch(`/api/v1/goals${qs ? `?${qs}` : ''}`).then(asJson)
  },
  goal: (goalId: string): Promise<GoalView> =>
    authFetch(`/api/v1/goals/${encodeURIComponent(goalId)}`).then(asJson),
  pauseGoal: (runId: string, revision: number, clientRequestId: string): Promise<RunView> =>
    authFetch(`/api/v1/runs/${encodeURIComponent(runId)}/pause`, {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ revision, clientRequestId }),
    }).then(asJson),
  resumeGoal: (runId: string, revision: number, clientRequestId: string): Promise<RunView> =>
    authFetch(`/api/v1/runs/${encodeURIComponent(runId)}/resume`, {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ revision, clientRequestId, interactionTarget: 'local-api' }),
    }).then(asJson),
  cancelGoal: (runId: string, revision: number, clientRequestId: string): Promise<RunView> =>
    authFetch(`/api/v1/runs/${encodeURIComponent(runId)}/cancel`, {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ revision, clientRequestId }),
    }).then(asJson),
  reviewGoal: (runId: string, revision: number, decision: 'confirm' | 'feedback', feedback: string | undefined,
    clientRequestId: string): Promise<RunView> =>
    authFetch(`/api/v1/runs/${encodeURIComponent(runId)}/review`, {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ revision, decision, feedback, clientRequestId }),
    }).then(asJson),
  runInputs: (runId: string, status?: string): Promise<{ inputs: RunInputView[] }> => {
    const q = status ? `?status=${encodeURIComponent(status)}` : ''
    return authFetch(`/api/v1/runs/${encodeURIComponent(runId)}/inputs${q}`).then(asJson)
  },
  enqueueRunInput: (runId: string, body: {
    mode: RunInputMode
    text: string
    attachmentIds?: string[]
    channel?: string
    clientRequestId: string
  }): Promise<{ input: RunInputView }> =>
    authFetch(`/api/v1/runs/${encodeURIComponent(runId)}/inputs`, {
      method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body),
    }).then(asJson),
  withdrawRunInput: (runId: string, inputId: string, clientRequestId: string):
    Promise<{ input: RunInputView }> =>
    authFetch(`/api/v1/runs/${encodeURIComponent(runId)}/inputs/${encodeURIComponent(inputId)}`
      + `?clientRequestId=${encodeURIComponent(clientRequestId)}`, { method: 'DELETE' }).then(asJson),
  /** Thread-scoped stop — reaches gateway/cron-driven activity that has no managed run object. */
  cancelConversation: async (conversationId: string) => {
    const page = await api.runs(conversationId)
    for (const run of page.runs.filter(item => item.ownsConversation)) await api.cancelRun(run.runId)
  },

  /** ---------- Active workspace read model ---------- */
  files: (path = '', cursor?: string, limit = 100): Promise<FilePage> => {
    const q = new URLSearchParams({ path, limit: String(limit) })
    if (cursor) q.set('cursor', cursor)
    return authFetch(`/api/v1/files?${q}`, {cache:'no-store'}).then(asJson)
  },
  fileContent: (path: string): Promise<FilePreview> =>
    authFetch(`/api/v1/files/content?path=${encodeURIComponent(path)}`).then(asJson),
  downloadFile: async (path: string, signal?: AbortSignal): Promise<Blob> => {
    const response = await authFetch(`/api/v1/files/download?path=${encodeURIComponent(path)}`, {signal})
    if (!response.ok) await asJson(response)
    const bytes = await response.blob()
    const revision = responseRevisions.get(response)
    if (revision !== undefined) assertRuntimeRevision(revision)
    return bytes
  },
  searchFiles: (q: string, content = false, cursor?: string, limit = 50): Promise<FileSearchPage> => {
    const params = new URLSearchParams({ q, content: String(content), limit: String(limit) })
    if (cursor) params.set('cursor', cursor)
    return authFetch(`/api/v1/files/search?${params}`, {cache:'no-store'}).then(asJson)
  },
  openContainingFolder: (path: string) => isTauriHost()
    ? tauriInvoke('workspace_reveal_path', { path })
    : authFetch('/api/v1/files/reveal', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ path }),
    }).then(asJson),
  changes: (runId: string): Promise<ChangesView> =>
    authFetch(`/api/v1/changes?runId=${encodeURIComponent(runId)}`).then(asJson),
  fileDiff: (runId: string, path: string): Promise<FileDiff> =>
    authFetch(`/api/v1/changes/diff?runId=${encodeURIComponent(runId)}&path=${encodeURIComponent(path)}`).then(asJson),

  todos:    (id: string) => authFetch(`/api/v1/agent/conversations/${id}/todos`).then(asJson),
  search: (q: string, cursor?: string): Promise<Workbench.SearchPage> => authFetch(`/api/v1/conversations/search?q=${encodeURIComponent(q)}${cursor ? `&cursor=${encodeURIComponent(cursor)}` : ''}`).then(asJson),
  /** Fetch the full body of a tool result whose preview was truncated and
   *  persisted as `ref:<sha>` by BudgetEnforcer. Returns the raw text body. The matching
   *  /meta endpoint is hit first by the UI to decide whether the persisted blob still
   *  exists (older threads may have been GC'd) before exposing the expand affordance. */
  toolResult:     (tid: string, ref: string) =>
    authFetch(`/api/tools/result/${encodeURIComponent(tid)}/${encodeURIComponent(ref)}`).then(r => {
      if (!r.ok) throw new Error(`tool result ${ref} not found (${r.status})`)
      return r.text()
    }),
  toolResultMeta: (tid: string, ref: string) =>
    authFetch(`/api/tools/result/${encodeURIComponent(tid)}/${encodeURIComponent(ref)}/meta`)
      .then(r => r.ok ? r.json() : { exists: false }),

  /** ---------- Interactions (unified pending + resolve) ---------- */
  interactions: (tid?: string) =>
    authFetch(tid ? `/api/v1/agent/conversations/${encodeURIComponent(tid)}/interactions?status=pending`
      : '/api/v1/interactions?status=pending').then(asJson),
  resolveInteraction: (tid: string, callId: string, body:
    { kind: 'approval'; decision: 'allow' | 'deny'; whitelist?: boolean }
    | { kind: 'plan_review'; decision: 'approve' | 'reject'; feedback?: string }
    | { kind: 'question'; answer: string }) =>
    authFetch(`/api/v1/agent/conversations/${encodeURIComponent(tid)}/interactions/${encodeURIComponent(callId)}`, {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body)
    }).then(asJson),

  /** Slash-command catalog for composer autocomplete (server-side expansion). */
  commands: () => authFetch('/api/v1/commands').then(asJson),
  moa:      (prompt: string) =>
    authFetch('/api/moa', { method: 'POST', headers: {'Content-Type':'application/json'}, body: JSON.stringify({prompt})}).then(asJson),
  imSettings: (): Promise<ImDocument> => authFetch('/api/im').then(asJson),
  imNotificationSettings: (): Promise<NotificationSettings> => authFetch('/api/im/notifications').then(asJson),
  imSetNotificationRecipient: (revision: string, recipientId: string | null): Promise<NotificationSettings> =>
    authFetch('/api/im/notifications', { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ revision, recipientId }) }).then(asJson),
  imRevealSecret: (channel: ImChannel, revision: string, clientId: string): Promise<{ clientSecret: string }> =>
    authFetch(`/api/im/${channel}/secret?${new URLSearchParams({ revision, clientId })}`).then(asJson),
  imRevealSavedSecret: (id: string, revision: string): Promise<{ clientSecret: string }> =>
    authFetch(`/api/im/credentials/${encodeURIComponent(id)}/secret?${new URLSearchParams({ revision })}`).then(asJson),
  imCardTemplate: (): Promise<Record<string, unknown>> => authFetch('/api/im/dingtalk/template').then(asJson),
  imSave: (channel: ImChannel, revision: string, connection: ImConnection,
    credential?: ImCredentialSelection): Promise<ImDocument> =>
    authFetch(`/api/im/${channel}`, { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ revision, connection, ...credential }) }).then(asJson),
  imReconnect: (channel: ImChannel): Promise<ImDocument> =>
    authFetch(`/api/im/${channel}/reconnect`, { method: 'POST' }).then(asJson),
  imPair: (id: string, revision: string): Promise<ImDocument> =>
    authFetch(`/api/im/pairing/${encodeURIComponent(id)}`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ revision }) }).then(asJson),
  cronList: (): Promise<AutomationJob[]> => authFetch('/api/cron').then(asJson),
  cronAdd: (job: AutomationDraft): Promise<{ id: string }> =>
    authFetch('/api/cron', { method: 'POST', headers:{'Content-Type':'application/json'}, body: JSON.stringify(job)}).then(asJson),
  cronDel: (id: string): Promise<{ cancelled: boolean }> => authFetch(`/api/cron/${encodeURIComponent(id)}`, { method: 'DELETE' }).then(asJson),
  cronEnabled: (id: string, enabled: boolean) => authFetch(`/api/cron/${encodeURIComponent(id)}/enabled`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ enabled }),
  }).then(asJson),
  cronRun: (id: string) => authFetch(`/api/cron/${encodeURIComponent(id)}/run`, { method: 'POST' }).then(asJson),
  cronCancel: (id: string) => authFetch(`/api/cron/${encodeURIComponent(id)}/cancel`, { method: 'POST' }).then(asJson),
  cronTargets: (): Promise<AutomationTargetView[]> => authFetch('/api/cron/targets').then(asJson),
  cronNotificationSettings: (targetId: string): Promise<NotificationSettings> =>
    authFetch('/api/cron/targets/' + encodeURIComponent(targetId) + '/notifications').then(asJson),
  cronToolsets: (): Promise<Record<string, string[]>> => authFetch('/api/cron/toolsets').then(asJson),
  cronHistory: (id: string): Promise<AutomationAttempt[]> => authFetch(`/api/cron/${encodeURIComponent(id)}/history`).then(asJson),
  cronResult: (id: string): Promise<AutomationResult> => authFetch(`/api/cron/results/${encodeURIComponent(id)}`).then(asJson),
  cronPreview: (cron: string, timeZone: string): Promise<SchedulePreview> => authFetch('/api/cron/preview', {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ cron, timeZone }),
  }).then(asJson),
  skills:   (): Promise<EffectiveSkillDto[]> => authFetch('/api/v1/skills').then(asJson),
  setSkillEnabled: (name: string, source: EffectiveSkillDto['source'], enabled: boolean) =>
    authFetch(`/api/v1/skills/${encodeURIComponent(name)}`, {
      method: 'PUT', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ source, enabled }),
    }).then(asJson),
  rescanSkills: () => authFetch('/api/v1/skills/rescan', { method: 'POST' }).then(asJson),
  hooks:    () => authFetch('/api/v1/hooks').then(asJson),
  configSchema: () => authFetch('/api/v1/config/schema').then(asJson),
  config: (): Promise<ConfigDocument> =>
    authFetch('/api/v1/config?scope=user').then(asJson),
  revealProviderApiKey: (providerId: string): Promise<{ apiKey: string }> =>
    authFetch(`/api/v1/config/providers/${encodeURIComponent(providerId)}/api-key`).then(asJson),
  revealImageServiceApiKey: (serviceId: string): Promise<{ apiKey: string }> =>
    authFetch(`/api/v1/config/image-services/${encodeURIComponent(serviceId)}/api-key`).then(asJson),
  saveConfig: (revision: string, values: Record<string, unknown>, credentialChanges: string[] = []) =>
    authFetch('/api/v1/config', {
      method: 'PUT', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ scope: 'user', revision, values, credentialChanges }),
    }).then(asJson),
  validateConfig: (values: Record<string, unknown>) =>
    authFetch('/api/v1/config/validate', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ values }),
    }).then(asJson),
  testConfigEndpoint: (kind: 'provider' | 'proxy', url: string) =>
    authFetch('/api/v1/config/test', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ kind, url }),
    }).then(asJson),
  probeProvider: (providerId: string, model: string | undefined, values: Record<string, unknown>): Promise<ProviderProbeResult> =>
    authFetch('/api/v1/config/providers/probe', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ providerId, model, values }),
    }).then(asJson),
  probeWebSearch: (values: Record<string, unknown>): Promise<SearchProbeResult> =>
    authFetch('/api/v1/config/web-search/probe', {
      method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ values }),
    }).then(asJson),
  providerModels: (providerId: string, values: Record<string, unknown>): Promise<ProviderModelCatalogResult> =>
    authFetch('/api/v1/config/providers/models', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ providerId, values }),
    }).then(asJson),
  configReload: () => authFetch('/api/v1/config/reload', { method: 'POST' }).then(asJson),
  memory: (): Promise<MemoryDocument> => authFetch('/api/v1/memory').then(asJson),
  saveMemory: (scope: 'USER' | 'WORKSPACE', kind: 'profile' | 'memory' | 'lessons', content: string) =>
    authFetch('/api/v1/memory', {
      method: 'PUT', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ scope, kind, content }),
    }).then(asJson),

  /** ---------- Snapshots / undo / branch ---------- */
  listSnapshots: (tid: string): Promise<Workbench.WorkspaceSnapshot[]> =>
    authFetch(`/api/v1/agent/conversations/${tid}/snapshots`).then(asJson),
  takeSnapshot: (tid: string, label?: string) =>
    authFetch(`/api/v1/agent/conversations/${tid}/snapshots${label ? `?label=${encodeURIComponent(label)}` : ''}`,
      { method: 'POST' }).then(asJson),
  restoreSnapshot: (tid: string, seq: number, request: Workbench.WorkspaceSnapshotRestoreRequest): Promise<Workbench.WorkspaceSnapshotRestoreResult> =>
    authFetch(`/api/v1/agent/conversations/${tid}/snapshots/${seq}/restore`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(request)
    }).then(asJson),
  restorePlan: (tid: string, seq: number, mode = 'conversation_and_files', paths: string[] | null = null): Promise<Workbench.WorkspaceSnapshotRestorePlan> =>
    authFetch(`/api/v1/agent/conversations/${tid}/snapshots/${seq}/restore-plan`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ mode, paths })
    }).then(asJson),
  branchSnapshot: (tid: string, seq: number, title: string | undefined, commandId: string): Promise<Workbench.WorkspaceSnapshotBranchResult> =>
    authFetch(`/api/v1/agent/conversations/${tid}/snapshots/${seq}/branch`,
      { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ commandId, title }) }).then(asJson),

  /** ---------- Permission mode / workspace / statusline ---------- */
  setMode: (tid: string, mode: 'ask' | 'accept-edits' | 'yolo') =>
    authFetch(`/api/v1/agent/conversations/${tid}/mode?mode=${mode}`, { method: 'PUT' }).then(asJson),
  statusline: (): Promise<RuntimeStatusline> => authFetch('/api/v1/statusline').then(asJson),
  scope: (): Promise<Workbench.ScopeView> => authFetch('/api/v1/scope').then(asJson),
  workspace: (): Promise<RuntimeWorkspace> => authFetch('/api/v1/workspace').then(asJson),
  workspaceGit: (signal?: AbortSignal): Promise<WorkspaceGit> => authFetch('/api/v1/workspace/git', { signal }).then(asJson),
  reloadWorkspace: () => authFetch('/api/v1/workspace/reload', { method: 'POST' }).then(asJson),

  /** ---------- Auth ---------- */
  /** Boot-token trusted handshake: exchange <runtimeDomainDir>/boot-token content for a session. */
  bootToken: (token: string) =>
    authFetch('/api/v1/auth/boot-token', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ token }),
    }).then(asJson),
}

}
export const api = createApi(authFetch)

// ─── Stable single-workspace Supervisor ───────────────────────────────────
export interface SupervisorWorkspace {
  id: string
  name: string
  root: string
  state: 'CLOSED' | 'DRAINING' | 'CLOSING' | 'OPENING' | 'READY' | 'FAILED'
  instanceId: string | null
  lastOpened: string | null
  createdAt: string
  isActive: boolean
  remoteProfile?: string
  remoteHost?: string
  remoteKeepRunning?: boolean
  remoteRetainedAt?: string | null
  runtimeScopeId?: string
  remoteService?: RemoteServiceRef
  remoteRuntime?: { ownership: 'managed' | 'attached'; runtimeVersion: string; runtimeSha256: string;
    targetVersion: string; targetSha256: string; updateAvailable: boolean; canUpgrade: boolean }
}

export async function openPreviewBrowser(url: string) {
  if (isTauriHost()) await tauriInvoke('preview_open_browser', { url })
  else window.open(url, '_blank', 'noopener,noreferrer')
}

/** Automations use a fixed user-level connection, independently of the visible project. */
export async function automationRuntimeClient(): Promise<RuntimeClient> {
  if (await supervisor.available()) {
    const personal = await supervisor.personal();
    if (personal.state !== 'READY' || !personal.instanceId) throw new Error('Automation service is unavailable');
    return new RuntimeClient({ base: isTauriHost() ? personal.endpoint || '' : '', remote: false,
      identity: { scopeId: personal.scopeId, instanceId: personal.instanceId } });
  }
  return new RuntimeClient();
}
export interface RemoteServiceRef { userDataDir: string; domainId: string; manifest: 'service.json' | 'domain.json'; scopeId: string; root: string; port?: number | null }
export interface RemoteServiceInfo { catalogId: string; workspaceId: string; root: string; name: string; instanceId: string; runtimeVersion: string; domainId: string; port: number; service: RemoteServiceRef }

export interface PersonalScope {
  scopeId: string
  runtimeProfile: 'PERSONAL'
  name: string
  instanceId: string | null
  state: SupervisorWorkspace['state']
  failureMessage?: string | null
  endpoint?: string | null
  owned?: boolean
}

export interface RemoteConnectionProfile {
  id: string
  name: string
  host: string
  port?: number | null
  identityFile?: string | null
  java?: string
}

export interface RemoteAttempt {
  id: string
  host?: string
  state: 'running' | 'succeeded' | 'failed' | 'cancelled'
  phase: string
  challenge: { id: string; kind: 'hostKey' | 'secret'; prompt: string } | null
  result: Record<string, unknown> | null
  error: string | null
  errorCode?: string | null
}

async function remoteInteraction<T>(action: string, args: Record<string, unknown> = {}): Promise<T> {
  if (isTauriHost()) return tauriInvoke('remote_interaction', {action, args})
  return (await supervisorFetch('/supervisor/api/connections/interaction', {method:'POST',
    headers:{'Content-Type':'application/json'}, body:JSON.stringify({action,args})})).json()
}

export const remoteConnections = {
  native: isTauriHost(),
  discover: () => remoteInteraction<{aliases: string[]; configPath: string; machine: string}>('discover'),
  parse: (input: string) => remoteInteraction<Pick<RemoteConnectionProfile, 'host' | 'port' | 'identityFile'>>('parse', {input}),
  start: (profile: RemoteConnectionProfile, operation: 'connect' | 'directories' | 'prepare' | 'services' | 'service', root?: string, service?: RemoteServiceRef) =>
    remoteInteraction<{id: string}>('start', {profile, operation, root, ...(service ? {service} : {})}),
  status: (id: string) => remoteInteraction<RemoteAttempt>('status', {id}),
  pending: () => remoteInteraction<RemoteAttempt | null>('pending'),
  cancel: (id: string) => remoteInteraction<RemoteAttempt>('cancel', {id}),
  reply: (id: string, challengeId: string, answer: string) => remoteInteraction<RemoteAttempt>('reply', {id, challengeId, answer}),
  pickIdentity: (): Promise<string | null> => tauriInvoke('remote_identity_pick'),
  list: async (): Promise<RemoteConnectionProfile[]> => isTauriHost()
    ? tauriInvoke('remote_connections') : (await supervisorFetch('/supervisor/api/connections')).json(),
  save: async (profile: RemoteConnectionProfile): Promise<void> => {
    if (isTauriHost()) { await tauriInvoke('remote_connection_save', { profile }); return }
    await supervisorFetch('/supervisor/api/connections', { method: 'PUT', headers: {'Content-Type':'application/json'}, body: JSON.stringify(profile) })
  },
  remove: async (id: string): Promise<void> => {
    if (isTauriHost()) { await tauriInvoke('remote_connection_remove', { id }); return }
    await supervisorFetch(`/supervisor/api/connections/${encodeURIComponent(id)}`, { method:'DELETE' })
  },
  inspect: async (id: string, operation: 'probe' | 'directories', root?: string): Promise<Record<string, unknown>> => isTauriHost()
    ? tauriInvoke('remote_inspect', { id, operation, root:root || null })
    : (await supervisorFetch('/supervisor/api/connections/inspect', { method:'POST', headers:{'Content-Type':'application/json'}, body:JSON.stringify({id,operation,root}) })).json(),
  registerWorkspace: async (profileId: string, root: string, preparationId?: string): Promise<SupervisorWorkspace> => isTauriHost()
    ? tauriInvoke('remote_workspace_register', { profileId, root, preparationId:preparationId || null })
    : (await supervisorFetch('/supervisor/api/remote-workspaces', { method:'POST', headers:{'Content-Type':'application/json'}, body:JSON.stringify({profileId,root,preparationId}) })).json(),
}

export interface SupervisorDirectoryListing {
  path: string
  parentPath: string | null
  directories: Array<{ name: string; path: string; hidden: boolean }>
  locations: Array<{ kind: 'home' | 'root'; name: string; path: string }>
}

export class SupervisorApiError extends Error {
  constructor(
    public readonly status: number,
    public readonly code: string,
    message: string,
    public readonly details: Record<string, unknown> = {},
  ) {
    super(message)
    this.name = 'SupervisorApiError'
  }
}

let supervisorControlToken: string | null = null

async function fetchSupervisor(url: string, init: RequestInit, timeoutMs: number) {
  const controller = new AbortController()
  const timer = setTimeout(() => controller.abort(), timeoutMs)
  try { return await fetch(url, {...init, signal:init.signal || controller.signal}) }
  finally { clearTimeout(timer) }
}

async function ensureSupervisorControlToken(): Promise<string> {
  if (supervisorControlToken) return supervisorControlToken
  const response = await fetchSupervisor('/supervisor/api/status', { credentials: 'omit' }, 15_000)
  if (!response.ok) throw new Error(`supervisor ${response.status}: ${response.statusText}`)
  const status = await response.json()
  if (typeof status?.controlToken !== 'string' || !status.controlToken) {
    throw new Error('supervisor control token is unavailable')
  }
  supervisorControlToken = status.controlToken
  return status.controlToken
}

async function supervisorFetch(url: string, init?: RequestInit) {
  const method = String(init?.method || 'GET').toUpperCase()
  const headers = new Headers(init?.headers)
  if (!['GET', 'HEAD', 'OPTIONS'].includes(method)) {
    headers.set('X-Harnex-Control-Token', await ensureSupervisorControlToken())
  }
  const timeout = /\/supervisor\/api\/(switch|close|personal)$/.test(url) ? 360_000 : 20_000
  const res = await fetchSupervisor(url, { ...init, method, headers, credentials: 'omit' }, timeout)
  if (!res.ok) {
    let code = 'supervisor_error'
    let detail = ''
    let details: Record<string, unknown> = {}
    try {
      const json = await res.clone().json()
      code = typeof json?.error === 'object' ? json.error?.code || code : code
      detail = typeof json?.error === 'string' ? json.error : json?.error?.message || json?.message || ''
      details = typeof json?.error?.details === 'object' && json.error.details ? json.error.details : {}
    } catch {
      try { detail = await res.text() } catch { /* swallow */ }
    }
    if (code === 'invalid_control_token') supervisorControlToken = null
    throw new SupervisorApiError(res.status, code, detail || res.statusText, details)
  }
  return res
}

export const supervisor = {
  native: isTauriHost(),
  available: async (): Promise<boolean> => {
    if (isTauriHost()) return true
    try {
      const res = await supervisorFetch('/supervisor/api/status')
      const ct = res.headers.get('content-type') || ''
      if (ct.includes('application/json')) {
        const status = await res.clone().json()
        if (typeof status?.controlToken === 'string') supervisorControlToken = status.controlToken
      }
      return ct.includes('application/json')
    } catch { return false }
  },
  list: async (): Promise<{
    workspaces: SupervisorWorkspace[]
    fixedWorkspaceId?: string | null
    activeWorkspaceId: string | null
    lastWorkspaceId: string | null
    lifecycle: SupervisorWorkspace['state']
    failureMessage?: string | null
    instanceId: string | null
    personal?: PersonalScope
  }> => isTauriHost()
    ? tauriInvoke('workspace_list')
    : (await supervisorFetch('/supervisor/api/workspaces')).json(),
  personal: async (restartIfCurrent = false): Promise<PersonalScope> => isTauriHost() ? tauriInvoke('personal_open', { restartIfCurrent })
    : (await supervisorFetch('/supervisor/api/personal', { method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ restartIfCurrent }) })).json(),
  activateProjectEndpoint: async () => {
    if (isTauriHost()) setRuntimeApiBase(await tauriInvoke<string>('runtime_base_url'))
  },
  register: async (root: string, name?: string, dataDir?: string): Promise<SupervisorWorkspace> =>
    isTauriHost()
      ? tauriInvoke('workspace_register', { root, name, dataDir })
      : (await supervisorFetch('/supervisor/api/workspaces', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ root, name, dataDir }),
    })).json(),
  pickDirectory: async (): Promise<string | null> => {
    if (isTauriHost()) return tauriInvoke<string | null>('workspace_pick_directory')
    const result = await (await supervisorFetch('/supervisor/api/pick-directory', {
      method: 'POST',
    })).json()
    return typeof result?.path === 'string' && result.path.trim() ? result.path : null
  },
  browseDirectory: async (path?: string): Promise<SupervisorDirectoryListing> => {
    if (isTauriHost()) throw new Error('the in-page directory browser is only available in WebUI')
    return (await supervisorFetch('/supervisor/api/directories', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ path: path || null }),
    })).json()
  },
  remove: async (id: string): Promise<void> => {
    if (isTauriHost()) {
      await tauriInvoke('workspace_remove', { id })
      return
    }
    await supervisorFetch(`/supervisor/api/workspaces/${encodeURIComponent(id)}`, { method: 'DELETE' })
  },
  switchTo: async (id: string, options: {
    expectedInstanceId?: string | null
    stopActiveRun?: boolean
    restartIfCurrent?: boolean
    upgradeRemote?: boolean
  } = {}): Promise<SupervisorWorkspace> => {
    if (isTauriHost()) {
      await tauriInvoke('workspace_switch', {
        id,
        restartIfCurrent: Boolean(options.restartIfCurrent),
        upgradeRemote: Boolean(options.upgradeRemote),
      })
      // Keep the UI alive through the native host's 300s cold-start budget.
      const deadline = Date.now() + 305_000
      while (Date.now() < deadline) {
        const state = await tauriInvoke<Awaited<ReturnType<typeof supervisor.list>>>('workspace_list')
        const active = state.workspaces.find(workspace => workspace.id === id && workspace.isActive)
        if (active?.state === 'READY') return active
        if (active?.state === 'FAILED' || state.lifecycle === 'FAILED') {
          throw new Error(state.failureMessage || `workspace ${id} failed to open`)
        }
        await new Promise(resolve => setTimeout(resolve, 250))
      }
      throw new Error(`workspace ${id} did not become ready within 305 seconds`)
    }
    return (await supervisorFetch('/supervisor/api/switch', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ id, ...options }),
    })).json()
  },
  background: async (id: string, expectedInstanceId: string, keepRunning: boolean): Promise<SupervisorWorkspace> => isTauriHost()
    ? tauriInvoke('workspace_background', { id, expectedInstanceId, keepRunning })
    : (await supervisorFetch(`/supervisor/api/workspaces/${encodeURIComponent(id)}`, {
      method: 'PATCH', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ expectedInstanceId, remoteKeepRunning: keepRunning }),
    })).json(),
  close: async (options: { expectedInstanceId?: string | null; stopActiveRun?: boolean; stopRemote?: boolean } = {}): Promise<void> => {
    if (isTauriHost()) {
      await tauriInvoke('workspace_close', { stopRemote: options.stopRemote === true, expectedInstanceId: options.expectedInstanceId || null })
      setApiIdentity(null)
      return
    }
    await supervisorFetch('/supervisor/api/close', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(options),
    })
    setApiIdentity(null)
  },
  active: async (): Promise<SupervisorWorkspace | null> => {
    if (isTauriHost()) {
      const state = await tauriInvoke<Awaited<ReturnType<typeof supervisor.list>>>('workspace_list')
      const workspace = state.workspaces.find(item => item.isActive) || null
      if (workspace?.instanceId) setApiIdentity({ scopeId: workspace.runtimeScopeId || workspace.id, instanceId: workspace.instanceId })
      return workspace
    }
    const workspace = await (await supervisorFetch('/supervisor/api/active')).json()
    if (!workspace?.id) return null
    if (workspace.instanceId) {
      setApiIdentity({ scopeId: workspace.runtimeScopeId || workspace.id, instanceId: workspace.instanceId })
    }
    return workspace as SupervisorWorkspace
  },
}

export const nativeFileSave = {
  pick: (suggestedName: string): Promise<string | null> => tauriInvoke('file_save_pick', {suggestedName}),
  cancel: (id: string): Promise<void> => tauriInvoke('file_save_cancel', {id}),
  write: async (id: string, bytes: Uint8Array): Promise<void> => {
    const invoke = (globalThis as typeof globalThis & {__TAURI_INTERNALS__?:TauriInternals}).__TAURI_INTERNALS__?.invoke
    if (!invoke) throw new Error('Native file saving is unavailable')
    await invoke('file_save_write', bytes, {headers:{'X-Harnex-Save-Id':id}})
  },
}

/** Resolve only an already-open project. The caller controls opening via the normal workspace chooser. */
export async function projectRuntimeClient(projectId: string): Promise<RuntimeClient> {
  if (await supervisor.available()) {
    const state = await supervisor.list()
    const project = state.workspaces.find(item => item.id === projectId)
    if (!project || state.activeWorkspaceId !== projectId || state.lifecycle !== 'READY' || !state.instanceId) {
      throw new ApiHttpError(409, 'export_target_unavailable', 'Open the selected project before saving')
    }
    const base = isTauriHost() ? await tauriInvoke<string>('runtime_base_url') : ''
    return new RuntimeClient({ base, remote: Boolean(project.remoteProfile), identity: { scopeId: project.runtimeScopeId || project.id, instanceId: state.instanceId } })
  }
  const client = new RuntimeClient()
  const scope = await client.api.scope()
  if (scope.runtimeProfile !== 'PROJECT' || scope.scopeId !== projectId || !scope.instanceId) {
    throw new ApiHttpError(409, 'export_target_unavailable', 'The selected project is unavailable')
  }
  return new RuntimeClient({ ...client.target, identity: { scopeId: scope.scopeId, instanceId: scope.instanceId } })
}

/** Composer projection of a scope-owned artifact. The wire record stays in the shared generated contract. */
export interface UploadInfo {
  artifactId: string
  name: string
  size: number
  mime: string
  extracted: boolean
  width?: number
  height?: number
}
