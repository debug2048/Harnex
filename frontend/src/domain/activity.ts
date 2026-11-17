import { subagentDisplayOutcome } from './subagentStatus'

export interface SemanticActivity {
  id: string
  runId?: string
  seq: number
  ts?: number
  eventType: string
  kind: string
  title: string
  summary: string
  output?: string
  childEventType?: string
  callId?: string
  toolName?: string
  purpose?: string
  arguments?: string
  content?: string
  durationMs?: number
  targets: string[]
  outcome: 'waiting' | 'running' | 'completed' | 'failed' | string
  attempts?: number
  groupId?: string
  subagentThreadId?: string
  parentThreadId?: string
  delegateCallId?: string
  /** UI-only provenance. Historical activities remain available to inspectors but are not
   *  replayed as transient cards when a completed conversation is reopened. */
  historical?: boolean
  details?: SemanticActivity[]
}

function text(data: Record<string, unknown>, ...keys: string[]) {
  for (const key of keys) {
    const value = data[key]
    if (value !== undefined && value !== null && String(value).trim()) return String(value)
  }
  return ''
}

function compressionStartSummary(data: Record<string, unknown>) {
  return t('activity.compressionRunningSummary', { tokens: text(data, 'beforeTokens') || '—' })
}

function compressionFinishTitle(data: Record<string, unknown>) {
  const outcome = text(data, 'outcome', 'activityOutcome') || 'completed'
  if (outcome === 'cancelled') return t('activity.compressionCancelled')
  if (outcome === 'failed') return t('activity.compressionFailed')
  return t('activity.compressionCompleted')
}

function compressionFinishSummary(data: Record<string, unknown>) {
  const outcome = text(data, 'outcome', 'activityOutcome') || 'completed'
  if (outcome === 'cancelled') return t('activity.compressionCancelledSummary')
  if (outcome === 'failed') return t('activity.compressionFailedSummary')
  const source = text(data, 'summarySource') === 'model'
    ? t('activity.compressionModelSource')
    : t('activity.compressionDeterministicSource')
  return t('activity.compressionCompletedSummary', {
    source,
    before: text(data, 'beforeTokens') || '—',
    after: text(data, 'afterTokens') || '—',
    reduction: text(data, 'reductionPercent') || '0',
  })
}

function providerSwitchSummary(data: Record<string, unknown>) {
  const from = text(data, 'from') || '—'
  const to = text(data, 'to', 'actualProvider') || '—'
  const attempts = Number(data.selectedAttempts ?? 0)
  const reason = text(data, 'reason')
  if (attempts > 0) {
    return t('activity.providerSwitchedSummary', {
      from,
      to,
      attempts: String(attempts),
      reason: reason || '—',
    })
  }
  return t('activity.providerCircuitSwitchedSummary', { from, to })
}

export function projectActivity(
  type: string,
  data: Record<string, unknown>,
  envelope: { runId?: string; seq?: number; ts?: number } = {},
): SemanticActivity | null {
  let kind = text(data, 'activityKind')
  let title = text(data, 'activityTitle')
  let summary = text(data, 'activitySummary')
  let outcome = text(data, 'activityOutcome')

  if (!kind) {
    switch (type) {
      case 'provider_switched':
        kind = 'provider'; title = t('activity.providerSwitched')
        summary = providerSwitchSummary(data); outcome = 'completed'
        break
      case 'context_compression_started':
        kind = 'context_compression'; title = t('activity.compressionRunning')
        summary = compressionStartSummary(data); outcome = 'running'
        break
      case 'context_compression_finished':
        kind = 'context_compression'; title = compressionFinishTitle(data)
        summary = compressionFinishSummary(data); outcome = text(data, 'outcome') || 'completed'
        break
      case 'goal_continue':
        kind = 'goal'; title = t('activity.goalContinue')
        summary = t('activity.goalTurn', { used: text(data, 'turnsUsed'), max: text(data, 'maxTurns') }); outcome = 'running'
        break
      case 'approval_required':
        kind = 'approval'; title = t('activity.awaitApproval'); summary = text(data, 'toolName'); outcome = 'waiting'
        break
      case 'user_question':
        kind = 'question'; title = t('activity.awaitAnswer'); summary = text(data, 'question'); outcome = 'waiting'
        break
      case 'plan_review':
        kind = 'plan_review'; title = t('activity.awaitPlan'); summary = text(data, 'plan'); outcome = 'waiting'
        break
      case 'error':
        kind = 'error'; title = t('activity.failed'); summary = text(data, 'message', 'reason'); outcome = 'failed'
        break
      case 'tool_use_queued':
      case 'tool_use_start':
      case 'tool_use_end':
        kind = 'tool'; title = text(data, 'name', 'toolName') || t('activity.toolGeneric')
        summary = text(data, 'content', 'arguments')
        outcome = type === 'tool_use_queued' ? 'waiting' : type === 'tool_use_start' ? 'running' : 'completed'
        break
      default:
        return null
    }
  }
  if (type === 'provider_switched') {
    title = t('activity.providerSwitched')
    summary = providerSwitchSummary(data)
  }
  else if (type === 'context_compression_started') {
    title = t('activity.compressionRunning')
    summary = compressionStartSummary(data)
    outcome = 'running'
  }
  else if (type === 'context_compression_finished') {
    title = compressionFinishTitle(data)
    summary = compressionFinishSummary(data)
    outcome = text(data, 'outcome', 'activityOutcome') || 'completed'
  }
  else if (type === 'goal_continue') {
    title = t('activity.goalContinue')
    summary = t('activity.goalTurn', { used: text(data, 'turnsUsed'), max: text(data, 'maxTurns') })
  }
  else if (type === 'approval_required') title = t('activity.awaitApproval')
  else if (type === 'user_question') title = t('activity.awaitAnswer')
  else if (type === 'plan_review') title = t('activity.awaitPlan')
  else if (type === 'error') title = t('activity.failed')
  else if (type === 'subagent_started' || type === 'subagent_finished') {
    const goal = text(data, 'goal')
    title = goal ? `${t('activity.subagent')} · ${goal}` : t('activity.subagent')
    if (type === 'subagent_finished') outcome = subagentDisplayOutcome(text(data, 'terminalStatus'), outcome)
  } else if (type === 'subagent_event') {
    const childType = text(data, 'childEventType')
    const tool = text(data, 'name', 'toolName')
    if (childType === 'message_start') title = t('activity.modelCall')
    else if (childType === 'provider_switched') {
      title = t('activity.providerSwitched')
      summary = providerSwitchSummary(data)
    }
    else if (childType === 'context_compression_started') {
      title = t('activity.compressionRunning')
      summary = compressionStartSummary(data)
      outcome = 'running'
    }
    else if (childType === 'context_compression_finished') {
      title = compressionFinishTitle(data)
      summary = compressionFinishSummary(data)
      outcome = text(data, 'outcome', 'activityOutcome') || 'completed'
    }
    else if (childType === 'tool_use_queued') title = t('activity.toolQueued', { tool: tool || 'tool' })
    else if (childType === 'tool_use_start') title = t('activity.toolStarted', { tool: tool || 'tool' })
    else if (childType === 'tool_use_end') title = t('activity.toolFinished', { tool: tool || 'tool' })
    else if (childType === 'error') title = t('activity.subagentFailed')
    else title = t('activity.executionInfo')
  }
  const rawTargets = data.activityTargets
  const targets = Array.isArray(rawTargets) ? rawTargets.map(String) : []
  const seq = Number(envelope.seq || data.seq || 0)
  const subagentThreadId = text(data, 'subagentThreadId')
  const providerRoute = type === 'provider_switched'
    ? `${text(data, 'from')}->${text(data, 'to', 'actualProvider')}`
    : ''
  return {
    // The envelope sequence identifies an event. Snapshot payload ids can describe the
    // child's latest tool, and must not turn the same event into a second activity.
    id: `${envelope.runId || 'run'}:${seq}:${type}${seq ? '' : `:${subagentThreadId || text(data, 'id', 'callId')}`}`,
    runId: envelope.runId,
    seq,
    ts: Number(envelope.ts || data.ts || 0) || undefined,
    eventType: type,
    kind,
    title: title || t('activity.generic'),
    summary,
    output: typeof data.output === 'string' ? data.output : undefined,
    childEventType: text(data, 'childEventType') || undefined,
    callId: text(data, 'id', 'callId') || undefined,
    toolName: text(data, 'name', 'toolName') || undefined,
    purpose: text(data, 'purpose') || undefined,
    // Large tool bodies are durable and load on expansion. Keep the live activity projection small.
    arguments: typeof data.arguments === 'string' && data.arguments.length <= 4096 ? data.arguments : undefined,
    content: typeof data.content === 'string' && data.content.length <= 4096 ? data.content : undefined,
    durationMs: typeof data.durationMs === 'number' ? data.durationMs : undefined,
    targets,
    outcome: outcome || 'completed',
    attempts: type === 'provider_switched' ? Number(data.selectedAttempts ?? 0) : undefined,
    groupId: providerRoute || text(data, 'id') || undefined,
    subagentThreadId: subagentThreadId || undefined,
    parentThreadId: text(data, 'parentThreadId') || undefined,
    delegateCallId: text(data, 'delegateCallId') || undefined,
    details: kind === 'subagent' ? [] : undefined,
  }
}
import { t } from '../i18n'
