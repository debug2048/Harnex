import { storeToRefs } from 'pinia'
import { RuntimeClient, getRuntimeRevision, onRuntimeIdentityChanged, ApiHttpError, type RunEventEnvelope,
  type RunReplayResetError, type RunReplayCursor, type UploadInfo } from '../api/client'
import type { RunView } from '../api/contracts'
import {
  newMessageUid as newUid,
  withMessageUid as withUid,
  type Msg,
  type ToolEvent,
} from '../domain/chat'
import { useActivityStore } from '../stores/activity'
import { useConversationStore } from '../stores/conversation'
import { useInteractionStore } from '../stores/interaction'
import { useRunStore } from '../stores/run'
import { useSettings } from '../stores/settings'
import { useThreadStore } from '../stores/threads'
import { useWorkspaceStore } from '../stores/workspace'
import { errorMessage, isRecord } from '../utils/unknown'
import { t } from '../i18n'
import { archiveMessage, archivePage, prependArchivePage, replaceArchiveTail } from '../domain/archiveMessages'
import { processMessage, restoreRunProcess } from '../domain/runTranscript'
import type { Presentation, ExecutionSpec, RunCreateRequest, PendingSubmissionCreateRequest } from '../../../packages/protocol/workbench'
import { executionDraft } from '../domain/executionDraft'
import { normalizeQuestionOptions } from '../domain/questions'
import { RunSession } from './runSession'
import { rememberHistoryWindow, restoreHistoryWindow, peekHistoryWindow, discardHistoryWindow, discardHistoryAnchor, historyViewKey } from '../domain/historyViewCache'

let activeController: ReturnType<typeof createRunController> | null = null
type AdmissionReceipt = { fingerprint: string; request: RunCreateRequest }
type QueueReceipt = { fingerprint: string; request: PendingSubmissionCreateRequest }
type SteerReceipt = { fingerprint: string; request: Parameters<RuntimeClient['api']['enqueueRunInput']>[1] }
const pendingReceipts = new Map<string, {
  admission: AdmissionReceipt | null; queueAdmission: QueueReceipt | null; steer: SteerReceipt | null
}>()


export function shouldRefreshTodos(event: Pick<ToolEvent, 'name' | 'error'>): boolean {
  return String(event.name || '').toLowerCase() === 'todo_write' && !event.error
}

/**
 * Stateless orchestration boundary for send/reattach/cancel ordering. Durable UI state belongs
 * to ConversationStore, RunStore, InteractionStore and ActivityStore; this controller only owns
 * AbortController instances and short-lived buffers that cannot be meaningfully persisted.
 */
function createRunController(client: RuntimeClient, runtimeRevision: number) {
  const isCurrent = () => runtimeRevision === getRuntimeRevision()
  const scopeKey = JSON.stringify([client.target.identity?.scopeId || client.target.base || 'direct', client.target.identity?.instanceId || ''])
  const workspace = useWorkspaceStore()
  // An unacknowledged write keeps its idempotency key across a Runtime restart, while hosts stay isolated.
  const receiptScope = workspace.scopeId === client.target.identity?.scopeId && workspace.storageScopeId
    ? workspace.storageScopeId : client.target.identity?.scopeId || client.target.base || 'direct'
  const api = new Proxy(client.api, { get(target, key) {
    return async (...args: unknown[]) => {
      if (!isCurrent()) throw new DOMException('Scope changed', 'AbortError')
      const result = await (target[key as keyof typeof target] as (...values: unknown[]) => unknown)(...args)
      if (!isCurrent()) throw new DOMException('Scope changed', 'AbortError')
      return result
    }
  } })
  const sessions = new Map<string, RunSession>()
  function session(runId: string) {
    let result = sessions.get(runId)
    if (!result) { result = new RunSession(client, runId); sessions.set(runId, result) }
    return result
  }
  const runStore = useRunStore()
  const activityStore = useActivityStore()
  const conversationStore = useConversationStore()
  const interactionStore = useInteractionStore()
  const {
    activeRunId,
    executionFacts,
    streaming,
    currentAssistant,
    currentThinking,
    toolEvents,
    todoRevision,
    error,
    goalProgress,
    latestSnapshotSeq,
    runInputs,
  } = storeToRefs(runStore)
  const { messages, threadId, permissionMode } = storeToRefs(conversationStore)
  const { pendingApprovals, pendingQuestions, pendingPlanReview } = storeToRefs(interactionStore)
  let abortCtl: AbortController | null = null
  // B8: explicit "user is cancelling" flag so onDone()/onError() don't race with cancel().
  // Invalidates delayed terminal transcript fetches when a new turn/thread starts.
  let terminalSyncSeq = 0
  let toolOccurrenceSeq = 0
  const openToolOccurrences: Record<string, string[]> = {}

  function appendProcess(message: Msg) {
    const runId = activeRunId.value || undefined
    const anchor = [...messages.value].reverse().find(item => item.messageId && item.runId === runId
      && !['PENDING', 'STREAMING'].includes(item.status || ''))
    messages.value.push({ ...message, runId, _process: true, _afterMessageId: anchor?.messageId })
  }

  async function refreshRunInputs(runId = activeRunId.value): Promise<void> {
    if (!runId) { runInputs.value = []; return }
    if (runStore.currentRun?.executionSpec.kind !== 'AGENT') { runInputs.value = []; return }
    try {
      const result = await api.runInputs(runId)
      if (activeRunId.value === runId) {
        runStore.setRunInputs(result.inputs || [])
      }
    } catch { /* optional projection refresh; enqueue/stream remains authoritative */ }
  }

  function controlRequestId(prefix: string) {
    return globalThis.crypto?.randomUUID?.()
      ?? `${prefix}-${Date.now()}-${Math.random().toString(36).slice(2, 10)}`
  }

  function captureControlTarget(runId = activeRunId.value) {
    const tid = threadId.value, seq = __loadSeq
    return () => isCurrent() && seq === __loadSeq && tid === threadId.value && runId === activeRunId.value
  }

  const receiptKey = () => JSON.stringify([receiptScope, conversationStore.executionKind, threadId.value])
  function receiptsFor(key: string) {
    const value = pendingReceipts.get(key) || { admission: null, queueAdmission: null, steer: null }
    pendingReceipts.set(key, value)
    return value
  }
  function releaseReceipts(key: string, value: ReturnType<typeof receiptsFor>) {
    if (!value.admission && !value.queueAdmission && !value.steer && pendingReceipts.get(key) === value) pendingReceipts.delete(key)
  }

  async function runGoalControl(runId: string, revision: number, prefix: string,
    invoke: (revision: number, requestId: string) => Promise<RunView>): Promise<RunView> {
    try {
      return await invoke(revision, controlRequestId(prefix))
    } catch (cause: unknown) {
      if (!isCurrent()) throw cause;
      if (!(cause instanceof ApiHttpError) || cause.status !== 409 || cause.code !== 'revision_conflict') throw cause
      // A projection event may advance revision between rendering the button and the click. Reload and retry once;
      // use a fresh idempotency key because the retried request intentionally has a different revision hash.
      const latest = await api.run(runId)
      if (['COMPLETED', 'INCOMPLETE', 'FAILED', 'CANCELLED', 'EXPIRED'].includes(latest.status)) return latest
      return await invoke(Number(latest.revision || revision), controlRequestId(`${prefix}-retry`))
    }
  }

  async function refreshGoalProjection(tid = threadId.value, knownRuns?: RunView[]) {
    if (!tid) { goalProgress.value = null; return }
    const current = captureControlTarget()
    try {
      const listed = (await api.goals(tid)).goals || []
      if (!current() || threadId.value !== tid) return
      const open = listed.find(goal => !['COMPLETED', 'INCOMPLETE', 'FAILED', 'CANCELLED', 'EXPIRED'].includes(goal.status))
      const goal = open || listed[0]
      if (!goal) { goalProgress.value = null; return }
      const runId = goal.activeRunId || goal.latestRunId
      let run = runId ? knownRuns?.find(item => item.runId === runId) : undefined
      if (!run && runId) run = await api.run(runId).catch(() => undefined)
      if (!current() || threadId.value !== tid) return
      if (run) runStore.hydrate(run)
      runStore.hydrateGoal(goal, run)
    } catch { /* Goal projection is additive; ordinary chat remains usable. */ }
  }

  async function enqueueRunInput(text: string, attachments: UploadInfo[] = [],
    mode: 'STEER' | 'QUEUE' = 'STEER'): Promise<boolean> {
    const runId = activeRunId.value
    const tid = threadId.value
    const value = text.trim()
    if (!value && attachments.length === 0) return false
    if (!streaming.value || !runId) {
      error.value = t(streaming.value ? 'chat.inputRunStarting' : 'chat.inputRunEnded')
      return false
    }
    const key = receiptKey(), receipts = receiptsFor(key)
    const queued = mode === 'QUEUE' || runStore.currentRun?.executionSpec.kind !== 'AGENT'
    try {
      if (queued) {
        if (!tid) return false
        const intent = {
          input: value, attachmentIds: attachments.map(item => item.artifactId),
          execution: executionDraft(conversationStore, useSettings()), channel: 'webui',
          notificationMode: conversationStore.executionKind === 'AGENT' ? conversationStore.notificationMode : 'OFF' as const }
        const fingerprint = JSON.stringify({ conversationId: tid, ...intent })
        if (!receipts.queueAdmission || receipts.queueAdmission.fingerprint !== fingerprint) {
          const source = runStore.currentRun?.runId === runId && runStore.currentRun.turnId
            ? runStore.currentRun : await api.run(runId)
          if (threadId.value !== tid || activeRunId.value !== runId) return false
          const predecessorTurnId = source.turnId
          if (!predecessorTurnId) { error.value = t('chat.inputRunStarting'); return false }
          receipts.queueAdmission = { fingerprint, request: { ...intent, predecessorTurnId,
            clientRequestId: controlRequestId('submission') } }
        }
        const accepted = await api.enqueueSubmission(tid, receipts.queueAdmission.request)
        receipts.queueAdmission = null
        if (threadId.value === tid) {
          conversationStore.submissions = [...conversationStore.submissions.filter(item => item.submissionId !== accepted.submissionId), accepted]
          void refreshSubmissions(tid).catch(() => {})
        }
        return true
      }
      const intent = { mode: 'STEER' as const, text: value,
        attachmentIds: attachments.map(item => item.artifactId), channel: 'webui' }
      const fingerprint = JSON.stringify({ runId, ...intent })
      if (!receipts.steer || receipts.steer.fingerprint !== fingerprint) {
        receipts.steer = { fingerprint, request: { ...intent, clientRequestId: controlRequestId('input') } }
      }
      const result = await api.enqueueRunInput(runId, receipts.steer.request)
      receipts.steer = null
      if (threadId.value === tid && activeRunId.value === runId) {
        error.value = null
        runStore.upsertRunInput(result.input)
      }
      return true
    } catch (cause: unknown) {
      if (!isCurrent()) throw cause;
      if (cause instanceof ApiHttpError && cause.status < 500) {
        if (queued) receipts.queueAdmission = null
        else receipts.steer = null
      }
      if (threadId.value === tid && activeRunId.value === runId) {
        error.value = cause instanceof ApiHttpError && cause.code === 'run_terminal'
          ? t('chat.inputRunEnded') : errorMessage(cause)
        await refreshRunInputs(runId)
      }
      return false
    } finally { releaseReceipts(key, receipts) }
  }

  async function withdrawRunInput(inputId: string): Promise<void> {
    const runId = activeRunId.value
    if (!runId) return
    try {
      const result = await api.withdrawRunInput(runId, inputId,
        globalThis.crypto?.randomUUID?.() ?? ('withdraw-' + Date.now() + '-' + Math.random().toString(36).slice(2)))
      if (activeRunId.value === runId) runStore.upsertRunInput(result.input)
    } catch (cause: unknown) {
      if (!isCurrent()) throw cause;
      if (activeRunId.value === runId) {
        error.value = errorMessage(cause)
        await refreshRunInputs(runId)
      }
    }
  }

  function clearOpenToolOccurrences() {
    for (const k of Object.keys(openToolOccurrences)) delete openToolOccurrences[k]
  }

  function finishOpenToolEvents(content = 'Tool result was not received before the run ended.', isError = true) {
    for (const occurrenceId of Object.keys(toolEvents.value)) {
      const ev = toolEvents.value[occurrenceId]
      if (!ev || ev.done) continue
      const completed: ToolEvent = { ...ev, content: ev.content || content, error: isError, done: true }
      const uid = `tool-${occurrenceId}`
      const idx = messages.value.findIndex(m => m._uid === uid)
      if (idx >= 0) {
        messages.value.splice(idx, 1, {
          ...messages.value[idx],
          content: completed.content || '',
          _toolEvent: completed
        })
      }
    }
    toolEvents.value = {}
    clearOpenToolOccurrences()
    pendingApprovals.value = []
    pendingQuestions.value = []
    pendingPlanReview.value = null
  }

  // Streaming text backpressure: each `text_delta` writes into a non-reactive buffer; an rAF
  // (or 30 ms fallback) loop flushes the buffer into `currentAssistant.value` at most once per
  // animation frame. The previous direct `+=` reassigned a reactive ref on every chunk, which
  // caused every dependent component (markdown renderer, scroller, code-highlighter) to re-run
  // hundreds of times per second on long generations and visibly stuttered the UI.
  let _textBuffer = ''
  let _flushScheduled = false
  let _flushRevision = 0
  let _flushFrame: number | null = null
  let _flushTimer: ReturnType<typeof setTimeout> | null = null
  function cancelTextFlush() {
    _flushRevision++
    if (_flushFrame !== null && typeof cancelAnimationFrame === 'function') cancelAnimationFrame(_flushFrame)
    if (_flushTimer !== null) clearTimeout(_flushTimer)
    _flushFrame = null; _flushTimer = null; _flushScheduled = false
  }
  function discardBufferedText() { cancelTextFlush(); _textBuffer = '' }
  function _scheduleFlush() {
    if (_flushScheduled) return
    _flushScheduled = true
    const revision = _flushRevision
    const run = () => {
      if (revision !== _flushRevision) return
      _flushScheduled = false
      _flushFrame = null; _flushTimer = null
      if (!isCurrent()) { _textBuffer = ''; return }
      if (_textBuffer) {
        currentAssistant.value += _textBuffer
        _textBuffer = ''
      }
    }
    // Prefer rAF — yields to the renderer naturally. Fall back to a 30 ms timer in non-DOM
    // contexts (SSR / tests) where requestAnimationFrame is unavailable.
    if (typeof requestAnimationFrame === 'function') {
      _flushFrame = requestAnimationFrame(run)
    } else {
      _flushTimer = setTimeout(run, 30)
    }
  }
  function _flushNow() {
    cancelTextFlush()
    if (!isCurrent()) { _textBuffer = ''; return }
    if (_textBuffer) {
      currentAssistant.value += _textBuffer
      _textBuffer = ''
    }
    _flushScheduled = false
  }

  /** Apply the suffix after the last captured presentation cursor. */
  function applyStreamEvent(type: string, data: Record<string, unknown>,
    envelope?: RunEventEnvelope) {
    // The Run store owns envelope de-duplication and execution facts. A replayed seq must not
    // mutate transcript/tool state or count usage a second time.
    if (captureBuffer) { captureBuffer.push({ type, data, envelope }); return }
    if (!runStore.apply(type, data || {}, envelope || {})) return
    activityStore.apply(type, data || {}, envelope || {})
    const eventSeq = Number(envelope?.seq || 0) || undefined
    switch (type) {
      case 'turn_accepted':
      case 'message_committed':
        refreshActivePresentation()
      case 'message_checkpoint': {
        if (isRecord(data.receipt)) {
          conversationStore.historyRevision = Number(data.receipt.historyRevision || 0)
          const ids = Array.isArray(data.receipt.messageIds) ? data.receipt.messageIds : []
          if (ids.length) conversationStore.parentMessageId = String(ids[ids.length - 1])
        }
        break
      }
      case 'run_started':
        // First envelope of a run (pre-loop) — identity only, no visible output.
        break
      case 'message_start':
      case 'message_end':
        // Provider/model facts are projected by runStore; no transcript node is needed.
        break
      case 'text_delta':
        // Buffer + rAF flush — see _scheduleFlush() rationale above.
        _textBuffer += String(data.text ?? '')
        _scheduleFlush()
        break
      case 'thinking_start':
        // Marks the start of a new reasoning block; no UI action needed here.
        break
      case 'thinking_end':
        {
          // Commit the current reasoning block as a standalone timeline node.
          // Prefer the live buffer, but fall back to the backend's final reasoning
          // text when the provider only exposes reasoning on message_end.
          const text = String(currentThinking.value.trim() || data.text || '').trim()
          if (text) {
            appendProcess(processMessage(activeRunId.value || '', eventSeq || 0, '', text))
          }
          currentThinking.value = ''
        }
        break
      case 'thinking_delta':
        // Reasoning/thinking content from models like DeepSeek-R1, Kimi, Qwen.
        // Accumulated separately from final content so we can display it in a
        // collapsible section without action buttons.
        currentThinking.value += String(data.text ?? '')
        break
      case 'tool_use_queued':
      case 'tool_use_start':
        // Flush buffered text before tools run. Provider-native reasoning is always collapsed. Ordinary content is
        // kept visible when it looks like a brief user-facing status, while longer work notes are collapsed. This is
        // display-only: backend history retains the exact payload needed for the next model call.
        _flushNow()
        {
          const reasoning = currentThinking.value
          const content = currentAssistant.value
          if (content.trim() || reasoning.trim()) appendProcess(processMessage(activeRunId.value || '', eventSeq || 0, content, reasoning))
          currentThinking.value = ''
          currentAssistant.value = ''
        }
        {
          const callId = String(data.id ?? data.callId ?? `tool_${++toolOccurrenceSeq}`)
          const queuedOccurrence = type === 'tool_use_start'
            ? (openToolOccurrences[callId] || []).find(id => toolEvents.value[id]?.status === 'queued')
            : undefined
          if (queuedOccurrence) {
            const previous = toolEvents.value[queuedOccurrence]
            const started: ToolEvent = { ...previous, status: 'running', done: false,
              purpose: typeof data.purpose === 'string' && data.purpose ? data.purpose : previous.purpose }
            toolEvents.value[queuedOccurrence] = started
            const queuedUid = `tool-${queuedOccurrence}`
            const queuedIndex = messages.value.findIndex(m => m._uid === queuedUid)
            if (queuedIndex >= 0) {
              messages.value.splice(queuedIndex, 1, { ...messages.value[queuedIndex], _toolEvent: started })
            }
            break
          }
          const occurrenceId = `${activeRunId.value}:${eventSeq || ++toolOccurrenceSeq}:${callId}`
          const te: ToolEvent = {
            id: callId,
            name: String(data.name ?? ''),
            purpose: typeof data.purpose === 'string' ? data.purpose : undefined,
            arguments: typeof data.arguments === 'string' ? data.arguments : undefined,
            done: false,
            status: type === 'tool_use_queued' ? 'queued' : 'running',
            seq: eventSeq,
          }
          toolEvents.value[occurrenceId] = te
          ;(openToolOccurrences[callId] ||= []).push(occurrenceId)
          // Push a tool-event message into the stream. Use a predictable _uid derived
          // from the local occurrence id so duplicate provider callIds do not collide.
          const uid = `tool-${occurrenceId}`
          appendProcess({ role: 'tool', content: '', _uid: uid, _toolEvent: te, _seq: eventSeq })
        }
        break
      case 'tool_use_end':
        {
          const callId = String(data.id ?? data.callId ?? '')
          const queue = openToolOccurrences[callId] || []
          let occurrenceId = queue.shift()
          if (queue.length === 0) delete openToolOccurrences[callId]
          if (!occurrenceId) {
            const pendingIdx = messages.value.findIndex(m => m._toolEvent?.id === callId && !m._toolEvent.done)
            if (pendingIdx >= 0) occurrenceId = String(messages.value[pendingIdx]._uid || '').replace(/^tool-/, '')
          }
          const completed: ToolEvent = {
            ...(occurrenceId ? toolEvents.value[occurrenceId] : undefined),
            id: callId || String(data.id ?? ''),
            name: String(data.name ?? ''),
            content: typeof data.content === 'string' ? data.content : undefined,
            error: data.error === true,
            durationMs: typeof data.durationMs === 'number' ? data.durationMs : undefined,
            done: true,
            resultPending: false,
            status: data.error === true ? 'failed' : 'completed',
            // Carry through optional structured metadata (file diffs, ...). Absent for
            // tools that don't emit it; safe for the renderer to no-op on undefined.
            metadata: isRecord(data.metadata) ? data.metadata as ToolEvent['metadata'] : undefined,
          }
          if (occurrenceId) toolEvents.value[occurrenceId] = completed
          // Find the inline message by its local occurrence _uid.
          const uid = occurrenceId ? `tool-${occurrenceId}` : ''
          const idx = uid ? messages.value.findIndex(m => m._uid === uid) : -1
          if (idx >= 0) {
            // Replace the entry to trigger Vue reactivity
            messages.value.splice(idx, 1, {
              ...messages.value[idx],
              content: typeof data.content === 'string' ? data.content : '',
              _toolEvent: completed,
            })
          }
          pendingApprovals.value = pendingApprovals.value.filter(p =>
            p.subagentThreadId || (p.sourceCallId || p.callId) !== callId)
          if (shouldRefreshTodos(completed)) {
            todoRevision.value++
          }
          // Remove from in-flight map
          if (occurrenceId) delete toolEvents.value[occurrenceId]
        }
        break
      case 'tool_use_status':
        {
          const callId = String(data.id ?? data.callId ?? '')
          const occurrenceId = (openToolOccurrences[callId] || [])[0]
          if (!occurrenceId) break
          const previous = toolEvents.value[occurrenceId]
          if (!previous) break
          const completed: ToolEvent = {
            ...previous,
            name: String(data.name ?? previous.name ?? ''),
            error: data.error === true,
            durationMs: typeof data.durationMs === 'number' ? data.durationMs : previous.durationMs,
            done: true,
            resultPending: true,
            status: data.error === true ? 'failed' : 'completed',
          }
          toolEvents.value[occurrenceId] = completed
          const uid = `tool-${occurrenceId}`
          const idx = messages.value.findIndex(m => m._uid === uid)
          if (idx >= 0) messages.value.splice(idx, 1, { ...messages.value[idx], _toolEvent: completed })
        }
        break
      case 'approval_required':
        break
      case 'subagent_event':
        if (String(data.childEventType ?? '') === 'tool_use_end') {
          const sourceCallId = String(data.id ?? data.callId ?? '')
          const childId = String(data.subagentThreadId ?? '')
          pendingApprovals.value = pendingApprovals.value.filter(p =>
            !(p.sourceCallId === sourceCallId && p.subagentThreadId === childId))
        }
        break
      case 'user_question':
        break
      case 'command_result':
        // Server-side slash result (/skill list, /goal status, plan-mode notice, errors).
        // Render as an info bubble; no agent loop ran for RESPOND cases.
        if (data.message != null && String(data.message).trim()) {
          messages.value.push(withUid({ role: 'system', content: String(data.message), _seq: eventSeq }))
        }
        break
      case 'plan_review':
        break
      case 'interaction_updated':
        refreshActivePresentation()
        break
      case 'image_operation_updated':
        if (typeof data.operationId === 'string' && !runStore.imageOperationIds.includes(data.operationId)) runStore.imageOperationIds.push(data.operationId)
        runStore.resourceRevision++
        break
      case 'model_call_updated':
      case 'artifact_committed':
        runStore.resourceRevision++
        break
      case 'submission_updated':
        void refreshSubmissions().catch(() => {})
        break
      case 'agent_step_committed':
        refreshActivePresentation()
        break
      case 'goal_continue':
        // Autonomous goal advanced to the next turn — update the live progress chip.
        goalProgress.value = {
          turnsUsed: Number(data.turnsUsed ?? 0),
          maxTurns: Number(data.maxTurns ?? 0),
          tokensUsed: Number(data.tokensUsed ?? 0),
          tokenBudget: Number(data.tokenBudget ?? 0),
          status: String(data.status ?? 'active')
        }
        break
      case 'goal_step_started':
      case 'goal_step_finished':
      case 'goal_criteria_evaluated':
      case 'goal_retry_scheduled':
        void refreshGoalProjection().catch(() => {})
        break
      case 'goal_state_changed':
      case 'goal_review_invalidated':
        {
          const status = String(data.status ?? goalProgress.value?.status ?? 'RUNNING')
          if (goalProgress.value) {
            goalProgress.value = {
              ...goalProgress.value,
              status,
              runRevision: Number(data.revision ?? goalProgress.value.runRevision ?? 0) || undefined,
              reason: data.reason ? String(data.reason) : goalProgress.value.reason,
            }
          }
          void refreshGoalProjection().catch(() => {})
          if (['PAUSED', 'READY_FOR_REVIEW', 'BLOCKED', 'BUDGET_LIMITED', 'INTERRUPTED'].includes(status)) {
            _flushNow()
            finishOpenToolEvents(String(data.reason || 'Goal is waiting for input or control.'))
            streaming.value = false
            queueMicrotask(() => { if (abortCtl) { abortCtl.abort(); abortCtl = null } })
          }
        }
        break
      case 'error':
        error.value = String(data.message ?? data.reason ?? 'error')
        break
      case 'cancelled':
        finishOpenToolEvents('Tool call was cancelled before a result was received.')
        break
      case 'title_updated':
        {
          const id = String(data.threadId || envelope?.conversationId || threadId.value || '')
          const title = String(data.title || '').trim()
          const fullTitle = String(data.fullTitle || title).trim()
          if (id && title) useThreadStore().updateTitle(id, title, fullTitle)
        }
        break
      case 'done':
        if (data.status === 'INCOMPLETE') {
          const result = isRecord(data.result) ? data.result : {}
          useRunStore().incompleteDetail = String(result.detail || data.message || error.value || result.reason || 'INCOMPLETE')
          error.value = null
        }
        if (typeof data.conversationId === 'string') threadId.value = data.conversationId
        // Terminal goal frame carries the final status (complete / blocked / budget_limited).
        if (isRecord(data.goal)) {
          goalProgress.value = {
            turnsUsed: Number(data.goal.turnsUsed ?? goalProgress.value?.turnsUsed ?? 0),
            maxTurns: Number(data.goal.maxTurns ?? goalProgress.value?.maxTurns ?? 0),
            tokensUsed: Number(data.goal.tokensUsed ?? goalProgress.value?.tokensUsed ?? 0),
            tokenBudget: Number(data.goal.tokenBudget ?? goalProgress.value?.tokenBudget ?? 0),
            status: String(data.goal.status ?? 'complete'),
            done: true,
            reason: data.goal.reason ? String(data.goal.reason) : undefined
          }
        }
        break
      case 'snapshot':
        // Backend captured a pre-tool snapshot. UI just records the latest seq for the
        // optional 'undo last' shortcut; full list is fetched lazily via api.listSnapshots().
        latestSnapshotSeq.value = typeof data.seq === 'number' ? data.seq : null
        break
      case 'usage':
        // Current-run aggregation is handled idempotently by runStore.
        break
      case 'notice':
        // A replay gap means the in-memory transcript is not authoritative. The run may still be
        // active, so terminal reconciliation below always fetches the compression-immune
        // transcript that AgentLoop persisted before emitting `done`.
        break
      case 'context_compression_started':
      case 'context_compression_finished':
        // The semantic activity store owns the single lifecycle card. Compression itself remains
        // synchronous for this Run; these frames only make that blocking phase visible.
        break
      case 'run_input_applying':
      case 'run_input_applied':
      case 'run_input_rejected':
      case 'run_input_queued':
      case 'run_input_cancelled':
      case 'run_input_held':
        {
          const runId = envelope?.runId || activeRunId.value
          if (type === 'run_input_applied') refreshActivePresentation()
          void refreshRunInputs(runId)
          // The durable mailbox transition follows the sequenced event by one atomic replace.
          setTimeout(() => { void refreshRunInputs(runId) }, 80)
        }
        break
      case 'interactions_superseded':
        pendingApprovals.value = []
        pendingQuestions.value = []
        pendingPlanReview.value = null
        break
      case 'provider_switched':
        // Failover: surface as a transient notice so the user understands why the model name
        // in the cockpit might change mid-stream. We append a one-line system message so it
        // is also persisted into the transcript for later debugging.
        // Rendered once through the semantic Activity projection; runStore owns actual facts.
        break
      default:
        // Surface unknown SSE event types in dev so backend additions don't silently drop on the UI.
        // eslint-disable-next-line no-console
        console.warn('[runController] unknown SSE event type:', type, data)
    }
  }

  // Version control for loadThread to prevent stale async responses from overwriting newer state
  let __loadSeq = 0
  let historyAbort: AbortController | null = null

  let captureBuffer: Array<{ type: string; data: Record<string, unknown>; envelope?: RunEventEnvelope }> | null = null
  let capturePromise: Promise<RunReplayCursor> | null = null
  let interactionRevision = -1

  function hydrateInteractions(presentation: Presentation) {
    if (presentation.interactionRevision < interactionRevision) return
    interactionRevision = presentation.interactionRevision
    interactionStore.reset()
    for (const item of presentation.interactions) {
      if (item.conversationId !== presentation.run.conversationId) continue
      const payload: Record<string, unknown> = item.payload || {}
      const callId = item.callId || ''
      if (item.kind === 'approval') pendingApprovals.value.push({ callId,
        toolName: String(payload.toolName || ''), arguments: String(payload.arguments || ''),
        outsideWrite: payload.outsideWrite === true, sourceCallId: String(payload.sourceCallId || ''),
        subagentThreadId: String(payload.subagentThreadId || ''),
      })
      if (item.kind === 'question') pendingQuestions.value.push({ callId,
        question: String(payload.question || ''), options: normalizeQuestionOptions(callId, payload.options, payload.optionDetails),
        allowFreeText: payload.allowFreeText !== false,
      })
      if (item.kind === 'plan_review') pendingPlanReview.value = { callId, plan: String(payload.plan || '') }
    }
  }

  /** Capture one ordered projection, then replay only frames beyond that projection's cursor. */
  function capturePresentation(runId: string, signal: AbortSignal): Promise<RunReplayCursor> {
    if (capturePromise) return capturePromise
    const tid = threadId.value, loadSeq = __loadSeq
    const buffer: NonNullable<typeof captureBuffer> = []
    captureBuffer = buffer
    const current = () => isCurrent() && !signal.aborted && loadSeq === __loadSeq && tid === threadId.value && activeRunId.value === runId
    capturePromise = (async () => {
      const snapshot = await session(runId).presentation(signal)
      if (!current()) throw new DOMException('Conversation changed during recovery', 'AbortError')
      const answer = snapshot.history.answer
      // The captured answer may still be mutable. Read only its immutable ancestor chain.
      const page = answer?.parentMessageId ? await api.messages(snapshot.run.conversationId, undefined, answer.parentMessageId)
        : answer ? null : await api.messages(snapshot.run.conversationId)
      if (!current()) throw new DOMException('Conversation changed during recovery', 'AbortError')
      terminalSyncSeq++
      discardBufferedText()
      runStore.hydrate(snapshot.run)
      runStore.setReplayCursor(runId, snapshot.cursor.projectionSeq)
      runStore.setRunInputs(snapshot.inputs)
      const tail = page ? archivePage(page) : []
      if (answer) {
        const item = archiveMessage(answer)
        if (snapshot.run.executing && ['PENDING', 'STREAMING'].includes(answer.status)) item.content = ''
        tail.push(item)
      }
      const previousHead = messages.value[0]?.messageId
      messages.value = restoreRunProcess(replaceArchiveTail(messages.value, tail), snapshot.run, snapshot.live)
      conversationStore.loadedThreadId = snapshot.run.conversationId
      conversationStore.historyRevision = snapshot.history.conversation?.historyRevision ?? page?.historyRevision ?? 0
      conversationStore.parentMessageId = snapshot.history.conversation?.activeLeafId || page?.activeLeafId || null
      if (!previousHead || messages.value[0]?.messageId !== previousHead) conversationStore.historyCursor = page?.nextCursor || null
      currentAssistant.value = snapshot.live.text
      currentThinking.value = snapshot.live.thinking
      toolEvents.value = {}
      clearOpenToolOccurrences()
      for (const message of messages.value) {
        const event = message._toolEvent
        if (message.runId !== runId || !event || event.done && !event.resultPending || !message._uid) continue
        const occurrenceId = message._uid.replace(/^tool-/, '')
        toolEvents.value[occurrenceId] = event
        ;(openToolOccurrences[event.id] ||= []).push(occurrenceId)
      }
      hydrateInteractions(snapshot)
      activityStore.reset()
      activityStore.hydrate(runId, snapshot.run.activities, snapshot.run.subagents, !snapshot.run.executing)
      runStore.imageOperationIds = snapshot.live.imageOperations.map(item => item.id).filter((id): id is string => !!id)
      error.value = snapshot.run.error || null
      return { lastSeq: snapshot.cursor.projectionSeq, streamEpoch: snapshot.cursor.streamEpoch,
        done: !['CREATED', 'QUEUED', 'RUNNING', 'PAUSE_REQUESTED', 'WAITING_RETRY'].includes(snapshot.run.status) || !snapshot.cursor.replayAvailable }
    })().finally(() => {
      if (captureBuffer !== buffer) return
      captureBuffer = null
      capturePromise = null
      if (current()) for (const frame of buffer) applyStreamEvent(frame.type, frame.data, frame.envelope)
    })
    return capturePromise
  }

  async function recoverRunReplay(reset: RunReplayResetError, signal: AbortSignal): Promise<RunReplayCursor> {
    return capturePresentation(reset.runId, signal)
  }

  function refreshActivePresentation() {
    if (activeRunId.value && abortCtl && !abortCtl.signal.aborted) {
      void capturePresentation(activeRunId.value, abortCtl.signal).catch(cause => {
        if (isCurrent() && !(cause instanceof DOMException && cause.name === 'AbortError')) error.value = errorMessage(cause)
      })
    }
  }

  async function refreshSubmissions(id = threadId.value, signal?: AbortSignal) {
    if (!id) return
    const page = await api.submissions(id, signal)
    if (!signal?.aborted && threadId.value === id) {
      const merged = new Map(conversationStore.submissions.map(item => [item.submissionId, item]))
      for (const item of page.items || []) {
        const previous = merged.get(item.submissionId)
        if (!previous || (item.revision || 0) >= (previous.revision || 0)) merged.set(item.submissionId, item)
      }
      conversationStore.submissions = [...merged.values()].sort((a, b) => (a.ordinal || 0) - (b.ordinal || 0))
    }
  }

  async function withdrawSubmission(id: string) {
    if (!threadId.value) return
    try { await api.cancelSubmission(threadId.value, id); await refreshSubmissions() }
    catch (cause) {
      if (!isCurrent()) throw cause; error.value = errorMessage(cause) }
  }

  async function reconcileTerminalThread(id: string) {
    const revision = ++terminalSyncSeq
    try {
      const page = await api.messages(id)
      if (revision !== terminalSyncSeq || threadId.value !== id || streaming.value) return
      const previousHead = messages.value[0]?.messageId
      messages.value = replaceArchiveTail(messages.value, archivePage(page))
      conversationStore.historyRevision = page.historyRevision
      conversationStore.parentMessageId = page.activeLeafId || null
      if (!previousHead || messages.value[0]?.messageId !== previousHead) conversationStore.historyCursor = page.nextCursor || null
      currentAssistant.value = ''
      currentThinking.value = ''
      await refreshSubmissions(id)
    } catch (cause) {
      if (!isCurrent()) return; if (threadId.value === id) error.value = errorMessage(cause) }
  }

  function restoreExecution(spec: ExecutionSpec) {
    const settings = useSettings()
    if (spec.kind === 'AGENT') {
      permissionMode.value = spec.permissionMode === 'yolo' || spec.permissionMode === 'accept-edits' ? spec.permissionMode : 'ask'
    }
    if (spec.text) {
      settings.provider = spec.text.provider || ''
      settings.model = spec.text.model || ''
      settings.reasoningEffort = spec.text.reasoningEffort || 'auto'
    }
  }

  let historyLoad: { id: string; promise: Promise<void> } | null = null
  function loadThread(id: string, options: { resetViewport?: boolean; force?: boolean } = {}): Promise<void> {
    if (!options.resetViewport && historyLoad?.id === id) return historyLoad.promise
    if (!options.resetViewport && !options.force && threadId.value === id
      && (streaming.value && activeRunId.value || conversationStore.loadedThreadId === id && !error.value)) return Promise.resolve()
    const promise = loadThreadInner(id, options).catch(cause => {
      if (isCurrent() && historyLoad?.promise === promise) error.value = errorMessage(cause)
      throw cause
    }).finally(() => {
      if (historyLoad?.promise === promise) { historyLoad = null; conversationStore.loadingHistory = false }
    })
    historyLoad = { id, promise }
    return promise
  }

  async function restoreHistoricalProcesses(runs: RunView[], loadSeq: number, signal?: AbortSignal) {
    const current = () => isCurrent() && loadSeq === __loadSeq && !signal?.aborted
    if (!current()) return
    const visibleRunIds = new Set(messages.value.map(message => message.runId).filter(Boolean))
    const visibleTurns = new Set(messages.value.map(message => message.turnId).filter(Boolean))
    const visible = runs.filter(run => (visibleRunIds.has(run.runId) || run.turnId && visibleTurns.has(run.turnId))
      // Reattachment owns the current local Run, including any live updates received while history loads.
      && (!run.ownsConversation || run.controlState === 'EXTERNAL'))
    // Older turns and newly paged history need the same durable progress as the latest turn.
    // Fetch in small batches; the archive may contain many Runs, most of them outside this page.
    for (let offset = 0; offset < visible.length; offset += 4) {
      if (!current()) return
      const snapshots = await Promise.all(visible.slice(offset, offset + 4).map(async run => ({
        run, snapshot: !run.ownsConversation
          ? await session(run.runId).presentation(signal).catch(() => null) : null,
      })))
      if (!current()) return
      for (const { run, snapshot } of snapshots) {
        messages.value = snapshot?.run.runId === run.runId
          ? restoreRunProcess(messages.value, snapshot.run, snapshot.live)
          : restoreRunProcess(messages.value, run)
      }
    }
  }

  async function loadThreadInner(id: string, options: { resetViewport?: boolean }) {
    if (threadId.value !== id) conversationStore.rememberDraft()
    if (threadId.value && conversationStore.loadedThreadId === threadId.value) {
      rememberHistoryWindow(historyViewKey(scopeKey, threadId.value), messages.value, conversationStore.historyCursor)
    }
    const viewKey = historyViewKey(scopeKey, id)
    if (options.resetViewport) discardHistoryAnchor(viewKey)
    const seq = ++__loadSeq
    historyAbort?.abort()
    const request = new AbortController()
    historyAbort = request
    const current = () => isCurrent() && seq === __loadSeq && threadId.value === id && !request.signal.aborted
    terminalSyncSeq++
    abortCtl?.abort(); abortCtl = null
    captureBuffer = null; capturePromise = null; interactionRevision = -1
    runStore.reset(); activityStore.reset(); interactionStore.reset()
    discardBufferedText()
    clearOpenToolOccurrences()
    threadId.value = id
    conversationStore.loadedThreadId = null
    conversationStore.loadingHistory = true
    conversationStore.historyRevision = 0
    conversationStore.parentMessageId = null
    messages.value = peekHistoryWindow(viewKey)
    conversationStore.submissions = []
    conversationStore.historyCursor = null
    useThreadStore().select(id)
    try {
      // Paint the authoritative body as soon as it arrives. Run discovery still gates actions, not reading.
      const [view, listed, meta] = await Promise.all([
        api.messages(id, undefined, undefined, 40, request.signal).then(page => {
          if (!current()) return null
          const view = restoreHistoryWindow(viewKey, page)
          messages.value = view.messages
          conversationStore.historyRevision = page.historyRevision
          conversationStore.parentMessageId = page.activeLeafId || null
          return { cursor: view.cursor }
        }),
        api.runs(id, undefined, request.signal), api.conversation(id, request.signal),
      ])
      if (!current() || !view) return
      const latest = listed.runs.find(run => run.ownsConversation) || listed.runs.find(run => run.runId === meta.latestRunId)
      if (latest) {
        if (!conversationStore.draftText && !conversationStore.draftAttachments.length) restoreExecution(latest.executionSpec)
        runStore.hydrate(latest)
        activityStore.hydrate(latest.runId, latest.activities, latest.subagents)
        if (latest.goalId && latest.controlState !== 'EXTERNAL') await refreshGoalProjection(id, listed.runs)
        if (!current()) return
        if (latest.ownsConversation && latest.controlState !== 'EXTERNAL') void reattach(latest.runId)
      }
      conversationStore.historyCursor = view.cursor
      conversationStore.loadedThreadId = id
      // Keep the existing completion/pagination contract while the visible transcript fills incrementally.
      await restoreHistoricalProcesses(listed.runs, seq, request.signal)
      if (!current()) return
      await refreshSubmissions(id, request.signal)
    } catch (cause) {
      if (!current()) return
      // Stop sibling requests too: a late successful body must not repaint a failed/deleted conversation.
      if (conversationStore.loadedThreadId !== id) request.abort()
      if (conversationStore.loadedThreadId !== id && cause instanceof ApiHttpError && [404, 410].includes(cause.status)) {
        discardHistoryWindow(viewKey); messages.value = []; conversationStore.loadedThreadId = null
      }
      throw cause
    }
  }

  async function loadMoreMessages() {
    const id = threadId.value, cursor = conversationStore.historyCursor, seq = __loadSeq
    const signal = historyAbort?.signal
    if (!id || !cursor || conversationStore.loadingHistory) return
    try {
      const page = await api.messages(id, cursor, undefined, 40, signal)
      if (signal?.aborted || seq !== __loadSeq || cursor !== conversationStore.historyCursor) return
      messages.value = prependArchivePage(messages.value, page)
      conversationStore.historyCursor = page.nextCursor || null
      const listed = await api.runs(id, undefined, signal)
      if (signal?.aborted || seq !== __loadSeq) return
      await restoreHistoricalProcesses(listed.runs, seq, signal)
    } catch (cause) {
      if (!isCurrent() || signal?.aborted || seq !== __loadSeq) return
      error.value = errorMessage(cause)
    }
  }

  let discoveringExternal = false
  async function discoverExternalRun() {
    const id = threadId.value, seq = __loadSeq
    if (!id || !isCurrent() || discoveringExternal || admitting || streaming.value || conversationStore.loadingHistory
        || conversationStore.loadedThreadId !== id) return
    discoveringExternal = true
    try {
      const listed = await api.runs(id)
      if (!isCurrent() || id !== threadId.value || seq !== __loadSeq || admitting || streaming.value) return
      const displayed = runStore.currentRun
      if (displayed?.controlState === 'EXTERNAL') {
        const updated = listed.runs.find(run => run.runId === displayed.runId)
        if (!updated || updated.status !== displayed.status || updated.ownsConversation !== displayed.ownsConversation
            || updated.controlState !== displayed.controlState) await loadThread(id, { force: true })
        return
      }
      const latest = listed.runs.find(run => run.ownsConversation) || listed.runs[0]
      if (latest?.origin.kind !== 'GATEWAY' || latest.runId === runStore.currentRun?.runId) return
      await reattach(latest.runId)
    } catch { /* Keep the last visible history; discovery retries without submitting any task. */ }
    finally { discoveringExternal = false }
  }

  async function reattach(runId: string) {
    if (streaming.value && activeRunId.value === runId) return
    abortCtl?.abort()
    abortCtl = new AbortController()
    const signal = abortCtl.signal
    captureBuffer = null; capturePromise = null; interactionRevision = -1
    runStore.begin(undefined, undefined, runId)
    streaming.value = true
    try {
      const cursor = await capturePresentation(runId, signal)
      if (signal.aborted) return
      if (runStore.currentRun?.goalId) void refreshGoalProjection().catch(() => {})
      if (cursor.done) { streaming.value = false; return }
      void session(runId).subscribe(cursor.lastSeq, {
        onReplayReset: reset => recoverRunReplay(reset, signal),
        onEvent(type, data, envelope) { if (isCurrent() && !signal.aborted) applyStreamEvent(type, data, envelope) },
        async onDone() {
          if (signal.aborted) return
          if (capturePromise) await capturePromise.catch(() => {})
          if (signal.aborted) return
          _flushNow()
          streaming.value = false
          finishOpenToolEvents()
          const id = threadId.value
          if (id) void reconcileTerminalThread(id).then(() => attachContinuationIfPresent(id, runId))
          void useThreadStore().refresh()
        },
        onError(cause) { if (!signal.aborted) { streaming.value = false; error.value = errorMessage(cause) } },
      }, signal, cursor.streamEpoch).catch(() => {})
    } catch (cause) {
      if (!isCurrent() || signal.aborted) return
      if (!signal.aborted) { streaming.value = false; error.value = errorMessage(cause) }
    }
  }

  function reset(preserveDraft = false) {
    discardBufferedText()
    if (threadId.value && conversationStore.loadedThreadId === threadId.value) {
      rememberHistoryWindow(historyViewKey(scopeKey, threadId.value), messages.value, conversationStore.historyCursor)
    }
    // Invalidate any thread request that may resolve after a workspace generation change.
    __loadSeq++
    historyAbort?.abort(); historyAbort = null
    historyLoad = null
    captureBuffer = null; capturePromise = null; interactionRevision = -1
    if (!preserveDraft) pendingReceipts.delete(receiptKey())
    conversationStore.reset(preserveDraft)
    terminalSyncSeq++
    if (abortCtl) { abortCtl.abort(); abortCtl = null }
    messages.value = []
    toolEvents.value = {}
    clearOpenToolOccurrences()
    threadId.value = null
    useThreadStore().select(null)
    pendingApprovals.value = []
    pendingQuestions.value = []
    pendingPlanReview.value = null
    goalProgress.value = null
    latestSnapshotSeq.value = null
    if (!preserveDraft) permissionMode.value = 'ask'
    currentAssistant.value = ''
    currentThinking.value = ''
    streaming.value = false
    runStore.reset()
    activityStore.reset()
  }

  let admitting = false
  let cancelAfterAdmission = false

  async function send(text: string, attachments: UploadInfo[] = []): Promise<boolean> {
    if (admitting) return false
    if (threadId.value && runStore.currentRun?.controlState === 'EXTERNAL') {
      admitting = true
      try { await loadThreadInner(threadId.value, { resetViewport: false }) }
      catch (cause) { error.value = errorMessage(cause); return false }
      finally { admitting = false }
    }
    if (streaming.value) return enqueueRunInput(text, attachments)
    if (!text.trim() && !attachments.length) return false
    if (runStore.currentRun?.ownsConversation && !runStore.currentRun.terminal) {
      error.value = t(runStore.currentRun.controlState === 'EXTERNAL' ? 'error.conversationBusyElsewhere' : 'error.agentBusy'); return false
    }
    const seq = __loadSeq
    const intent = { conversationId: threadId.value || undefined,
      input: text, attachmentIds: attachments.map(item => item.artifactId),
      execution: executionDraft(conversationStore, useSettings()), channel: 'webui',
      notificationMode: conversationStore.executionKind === 'AGENT' ? conversationStore.notificationMode : 'OFF' as const }
    const request = { ...intent,
      expectedHistoryRevision: conversationStore.historyRevision,
      parentMessageId: conversationStore.parentMessageId || undefined }
    // A history refresh can discover the accepted turn after an acknowledgment was lost.
    // Retrying the same intent must retain its original revision and idempotency key.
    const fingerprint = JSON.stringify(intent)
    const key = receiptKey(), receipts = receiptsFor(key)
    if (!receipts.admission || receipts.admission.fingerprint !== fingerprint) receipts.admission = { fingerprint,
      request: { ...request, clientRequestId: controlRequestId('run') } }
    admitting = true; cancelAfterAdmission = false
    error.value = null
    try {
      const view = await api.createRun(receipts.admission.request)
      receipts.admission = null
      if (cancelAfterAdmission) {
        try { if (view.goalId) await api.cancelGoal(view.runId, view.revision, controlRequestId('cancel'))
          else await api.cancelRun(view.runId) }
        catch (cause) {
      if (!isCurrent()) throw cause; if (seq === __loadSeq) error.value = errorMessage(cause) }
      }
      if (seq !== __loadSeq) return true
      // Admission confirms this message exists. Show it before the presentation/history fetch finishes.
      if (!messages.value.some(message => message.role === 'user' && message.runId === view.runId)) {
        messages.value.push({ _uid: `accepted-user:${view.runId}`, role: 'user', content: text,
          runId: view.runId, turnId: view.turnId, status: 'COMPLETED', attachments: [...attachments],
          contentParts: [{ type: 'TEXT', text }, ...attachments.map(item => ({ type: 'ARTIFACT_REF' as const, artifactId: item.artifactId }))] })
      }
      runStore.hydrate(view)
      threadId.value = view.conversationId
      useThreadStore().select(view.conversationId)
      useThreadStore().updateTitle(view.conversationId, text.trim().split('\n')[0]?.slice(0, 60) || t('sidebar.untitled'))
      // Clear the acknowledged draft before asynchronous history/stream work completes.
      void reattach(view.runId)
      void useThreadStore().refresh()
      return true
    } catch (cause) {
      if (!isCurrent()) return false
      if (seq === __loadSeq) error.value = errorMessage(cause)
      // A transport failure may follow durable acceptance: retain the exact idempotency key.
      if (cause instanceof ApiHttpError && cause.status < 500) receipts.admission = null
      return false
    } finally { admitting = false; releaseReceipts(key, receipts) }
  }

  async function attachContinuationIfPresent(tid: string, sourceRunId: string | null) {
    if (!sourceRunId) return
    try {
      const source = await api.run(sourceRunId)
      for (let attempt = 0; attempt < 20; attempt++) {
        if (attempt > 0) await new Promise(resolve => setTimeout(resolve, 400))
        if (streaming.value || threadId.value !== tid) return
        const queue = await api.submissions(tid)
        const sourceSubmission = queue.items.find(item => item.runId === sourceRunId)
        const next = queue.items.filter(item => item.predecessorSubmissionId
          ? item.predecessorSubmissionId === sourceSubmission?.submissionId : item.predecessorTurnId === source.turnId)
          .sort((a, b) => a.ordinal - b.ordinal)[0]
        if (!next) return
        if (next.runId) { await reattach(next.runId); return }
        if (!['QUEUED', 'STARTING'].includes(next.state)) { await refreshSubmissions(tid); return }
      }
    } catch { /* The durable queue remains visible on the next successful refresh. */ }
  }

  async function cancel(_toolEventMsg?: string, _toolEventError?: boolean) {
    if (admitting) { cancelAfterAdmission = true; return }
    const current = captureControlTarget()
    const external = runStore.currentRun
    if (external?.controlState === 'EXTERNAL') {
      if (!external.allowedActions.includes('cancel')) return
      try {
        await api.cancelRun(external.runId)
        if (current()) {
          await loadThreadInner(external.conversationId, { resetViewport: false })
        }
      } catch (cause) { if (current()) error.value = errorMessage(cause) }
      return
    }
    const rid = activeRunId.value
    if (!rid) return
    try {
      const goal = goalProgress.value
      if (goal?.runId === rid && goal.runRevision) {
        const view = await runGoalControl(rid, goal.runRevision, 'goal-cancel',
          (revision, requestId) => api.cancelGoal(rid, revision, requestId))
        if (current()) runStore.hydrate(view)
      } else {
        await api.cancelRun(rid)
        if (current() && runStore.currentRun?.runId === rid) runStore.currentRun = { ...runStore.currentRun, cancelRequested: true }
      }
      // Keep receiving the real terminal state and archived partial result while cancellation settles.
      if (current() && !streaming.value) await reattach(rid)
    } catch (cause) {
      if (current()) error.value = errorMessage(cause)
    }
  }

  async function pauseGoal() {
    const goal = goalProgress.value
    if (!goal?.runId || !goal.runRevision) return
    const current = captureControlTarget(goal.runId)
    try {
      const run = await runGoalControl(goal.runId, goal.runRevision, 'goal-pause',
        (revision, requestId) => api.pauseGoal(goal.runId!, revision, requestId))
      if (!current()) return
      runStore.hydrate(run)
      await refreshGoalProjection()
    } catch (cause: unknown) {
      if (!current()) return
      error.value = errorMessage(cause); await refreshGoalProjection() }
  }

  async function resumeGoal(feedback?: string) {
    const goal = goalProgress.value
    if (!goal?.runId || !goal.runRevision) return
    const current = captureControlTarget(goal.runId)
    try {
      const run = feedback?.trim()
        ? await api.reviewGoal(goal.runId, goal.runRevision, 'feedback', feedback.trim(),
            controlRequestId('goal-feedback'))
        : await api.resumeGoal(goal.runId, goal.runRevision, controlRequestId('goal-resume'))
      if (!current()) return
      runStore.hydrate(run)
      await refreshGoalProjection()
      if (current()) await reattach(goal.runId)
    } catch (cause: unknown) {
      if (!current()) return
      error.value = errorMessage(cause); await refreshGoalProjection() }
  }

  async function confirmGoal() {
    const goal = goalProgress.value
    if (!goal?.runId || !goal.runRevision) return
    const current = captureControlTarget(goal.runId)
    try {
      const run = await api.reviewGoal(goal.runId, goal.runRevision, 'confirm', undefined,
        controlRequestId('goal-confirm'))
      if (!current()) return
      runStore.hydrate(run)
      await refreshGoalProjection()
      if (current() && threadId.value) await loadThread(threadId.value, { force: true })
    } catch (cause: unknown) {
      if (!current()) return
      error.value = errorMessage(cause); await refreshGoalProjection() }
  }

  async function resolveApproval(callId: string, choice: 'allow' | 'deny' | 'whitelist', _toolName: string) {
    const tid = threadId.value
    const current = captureControlTarget()
    if (!tid) {
      // Remove from UI even if we can't route — avoids a stuck pending item.
      pendingApprovals.value = pendingApprovals.value.filter(p => p.callId !== callId)
      return
    }
    try {
      await api.resolveInteraction(tid, callId, {
        kind: 'approval',
        decision: choice === 'deny' ? 'deny' : 'allow',
        whitelist: choice === 'whitelist'
      })
      if (!current()) return
      // Only remove on success — dropping the card on failure would leave the agent loop
      // blocked on approval-timeout while the UI claimed the user had already responded.
      pendingApprovals.value = pendingApprovals.value.filter(p => p.callId !== callId)
    } catch (cause: unknown) {
      if (!current()) return
      if (errorMessage(cause).includes('HTTP 410')) {
        pendingApprovals.value = pendingApprovals.value.filter(p => p.callId !== callId)
        return
      }
      error.value = t('error.approvalFailed', { message: errorMessage(cause) })
      // Re-throw so the caller (button click handler) can render a retry hint if it wants.
      throw cause
    }
  }

  /** Resolve a submit_plan review (approve/reject + optional feedback). Mirrors {@link resolveApproval}. */
  async function reviewPlan(callId: string, decision: 'approve' | 'reject', feedback?: string) {
    const tid = threadId.value
    const currentTarget = captureControlTarget()
    const current = () => currentTarget() && pendingPlanReview.value?.callId === callId
    if (!tid) { if (current()) pendingPlanReview.value = null; return }
    try {
      await api.resolveInteraction(tid, callId, { kind: 'plan_review', decision, feedback })
      if (!current()) return
      pendingPlanReview.value = null
    } catch (cause: unknown) {
      if (!current()) return
      if (errorMessage(cause).includes('HTTP 410')) {
        pendingPlanReview.value = null
        return
      }
      error.value = t('error.planReviewFailed', { message: errorMessage(cause) })
      throw cause
    }
  }

  /** Answer a pending ask_user question (value = chosen option(s) or free text). Mirrors {@link resolveApproval}. */
  async function answerQuestion(callId: string, value: string) {
    const tid = threadId.value
    const current = captureControlTarget()
    if (!tid) {
      pendingQuestions.value = pendingQuestions.value.filter(q => q.callId !== callId)
      return
    }
    try {
      await api.resolveInteraction(tid, callId, { kind: 'question', answer: value })
      if (!current()) return
      pendingQuestions.value = pendingQuestions.value.filter(q => q.callId !== callId)
    } catch (cause: unknown) {
      if (!current()) return
      if (errorMessage(cause).includes('HTTP 410')) {
        pendingQuestions.value = pendingQuestions.value.filter(q => q.callId !== callId)
        return
      }
      error.value = t('error.answerFailed', { message: errorMessage(cause) })
      throw cause
    }
  }

  /** Create an immutable revision or another answer attempt at a stable archive message. */
  async function retryMessage(uid: string, newText?: string, rerunAgent = false) {
    const target = messages.value.find(message => message._uid === uid)
    const id = threadId.value
    if (!target?.messageId || !id || streaming.value || runStore.currentRun?.ownsConversation) return
    if (target.status === 'PROGRESS') return
    const text = executionDraft(conversationStore, useSettings()).text
    try {
      // Keep the original action and permissions; use the current model and user-wide toolset default.
      let source = target.runId === runStore.currentRun?.runId ? runStore.currentRun?.executionSpec : undefined
      if (!source && target.runId) {
        try { source = (await api.run(target.runId)).executionSpec }
        catch (cause) { if (!(cause instanceof ApiHttpError) || cause.status !== 404) throw cause }
      }
      if (!source && target.turnId) source = (await api.turn(id, target.turnId)).executionSnapshot
      if (threadId.value !== id) return
      if (!source) throw new Error(t('error.retrySourceUnavailable'))
      const execution: ExecutionSpec = source.kind === 'AGENT'
        ? { ...source, text, toolsets: [] } : { ...source, text }
      let messageId = target.messageId
      let revision = conversationStore.historyRevision
      if (newText !== undefined) {
        const receipt = await api.reviseMessage(id, messageId, { commandId: controlRequestId('revise'),
          expectedHistoryRevision: revision, contentParts: [{ type: 'TEXT', text: newText },
            ...(target.contentParts || []).filter(part => part.type === 'ARTIFACT_REF')] })
        messageId = receipt.messageIds?.[0] || messageId
        revision = receipt.historyRevision || revision
      }
      if (threadId.value !== id) return
      const view = await api.retryMessage(id, messageId, { clientRequestId: controlRequestId('retry'),
        expectedHistoryRevision: revision, execution, rerunAgent })
      if (threadId.value !== id) return
      if (JSON.stringify(executionDraft(conversationStore, useSettings()).text) === JSON.stringify(text)) {
        restoreExecution(view.executionSpec)
      }
      await reattach(view.runId)
    } catch (cause) {
      if (!isCurrent()) throw cause; if (threadId.value === id) error.value = errorMessage(cause) }
  }

  async function resendFromUserMessage(uid: string, newText?: string, rerunAgent = false) {
    return retryMessage(uid, newText, rerunAgent)
  }

  async function regenerateAssistant(uid: string, rerunAgent = false) {
    return retryMessage(uid, undefined, rerunAgent)
  }

  return {
    send,
    loadThread,
    loadMoreMessages,
    refreshSubmissions,
    withdrawSubmission,
    reset,
    resolveApproval,
    answerQuestion,
    reviewPlan,
    cancel,
    reattach,
    discoverExternalRun,
    resendFromUserMessage,
    regenerateAssistant,
    enqueueRunInput,
    withdrawRunInput,
    refreshRunInputs,
    refreshGoalProjection,
    pauseGoal,
    resumeGoal,
    confirmGoal,
  }
}

let activeRevision = -1
onRuntimeIdentityChanged(() => {
  // Invalidate synchronously, before the new scope restores its draft or history.
  // A lazy reset on the next controller call could erase that already-restored state.
  activeController?.reset(true)
  activeController = null
  activeRevision = -1
})
function currentController() {
  const revision = getRuntimeRevision()
  if (!activeController || activeRevision !== revision) {
    activeRevision = revision
    activeController = createRunController(new RuntimeClient(), revision)
  }
  return activeController
}
const controllerFacade = new Proxy({} as ReturnType<typeof createRunController>, {
  get(_target, key) {
    return (...args: unknown[]) => (currentController()[key as keyof ReturnType<typeof createRunController>] as (...values: unknown[]) => unknown)(...args)
  },
})
export function useRunController() { return controllerFacade }
