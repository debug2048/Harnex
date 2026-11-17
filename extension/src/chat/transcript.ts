import type { MessageRecord, Presentation, RunView, ToolActivity } from '../../../packages/protocol/workbench'
import type { StreamEnvelope } from '../interactions/SseTap'

/** Recover occurrences by their first sequence, since a provider can reuse a call id. */
function toolsForRun(run: RunView, live?: Presentation['live']): ToolActivity[] {
  const tools: ToolActivity[] = []
  const activities = [...(run.activities || []), ...(run.subagents || []).flatMap(child => child.details || [])]
    .sort((a, b) => a.seq - b.seq)
  const seen = new Set<number>()
  for (const activity of activities) {
    if (seen.has(activity.seq)) continue
    seen.add(activity.seq)
    const type = activity.childEventType || activity.eventType
    if (!['tool_use_queued', 'tool_use_start', 'tool_use_end', 'tool_use_status'].includes(type)) continue
    const id = activity.id || activity.callId
    if (!id) continue
    let tool = type === 'tool_use_queued' ? undefined : tools.find(item => item.id === id
      && item.subagentConversationId === activity.subagentThreadId && item.resultPending
      && (type !== 'tool_use_start' || item.state === 'QUEUED'))
    if (!tool) {
      tool = { id, seq: activity.seq, subagentConversationId: activity.subagentThreadId }
      tools.push(tool)
    }
    tool.name = activity.name || activity.toolName || tool.name || 'tool'
    tool.summary = activity.activitySummary || tool.summary
    tool.purpose = activity.purpose || tool.purpose
    tool.state = type === 'tool_use_queued' ? 'QUEUED' : type === 'tool_use_start' ? 'RUNNING'
      : type === 'tool_use_end' ? activity.activityOutcome === 'failed' ? 'FAILED' : 'COMPLETED' : tool.state || 'RUNNING'
    tool.resultPending = type !== 'tool_use_end'
    if (['tool_use_queued', 'tool_use_start'].includes(type) && activity.activitySummary) {
      try { JSON.parse(activity.activitySummary); tool.arguments = activity.activitySummary } catch { /* full details are fetched on expand */ }
    }
  }
  for (const tool of live?.tools || []) {
    const index = tools.findIndex(item => item.seq === tool.seq && item.id === tool.id
      && item.subagentConversationId === tool.subagentConversationId)
    if (index < 0) tools.push({ ...tool })
    else tools[index] = { ...tools[index], ...tool }
  }
  return tools.map(tool => run.terminal && ['QUEUED', 'RUNNING'].includes(tool.state || '')
    ? { ...tool, state: 'INTERRUPTED' } : tool)
}

export function archiveEnvelopes(message: MessageRecord): StreamEnvelope[] {
  const text = message.contentParts.map(part => part.type === 'TEXT' ? part.text || ''
    : part.type === 'ARTIFACT_REF' ? `[附件 ${part.artifactId}]`
      : part.type === 'IMAGE_OPERATION_REF' ? `[图片操作 ${part.imageOperationId}]` : '').join('')
  const identity = { runId: message.runId || undefined }
  if (message.role === 'USER') return [{ ...identity, type: 'user_message', data: { text, messageId: message.messageId } }]
  return text ? [{ ...identity, type: 'text_delta', data: { text, messageId: message.messageId } },
    { ...identity, type: 'message_end', data: {} }] : []
}

/** Keep tools and progress next to the archived turn that produced them, including after reload. */
export function transcriptEnvelopes(messages: MessageRecord[], runs: RunView[], snapshot?: Presentation): StreamEnvelope[] {
  const groups = new Map<string, { seq: number; events: StreamEnvelope[] }[]>()
  for (const run of runs) {
    const live = snapshot?.run.runId === run.runId ? snapshot.live : undefined
    const first = messages.find(message => message.runId === run.runId)
    if (!first) continue
    const add = (afterMessageId: string | undefined, seq: number, events: StreamEnvelope[]) => {
      const explicit = messages.find(message => message.messageId === afterMessageId && message.runId === run.runId)
      const anchor = explicit || first
      const key = `${explicit || anchor.role === 'USER' ? 'after' : 'before'}:${anchor.messageId}`
      const group = groups.get(key) || []
      group.push({ seq, events: events.map(event => ({ ...event, runId: run.runId, conversationId: run.conversationId })) })
      groups.set(key, group)
    }
    for (const tool of toolsForRun(run, live)) {
      add(tool.afterMessageId, tool.seq || 0, [{ type: 'tool_snapshot', seq: tool.seq, data: { ...tool } }])
    }
    for (const progress of live?.messages || []) {
      const events: StreamEnvelope[] = []
      if (progress.thinking) events.push({ type: 'thinking_delta', data: { text: progress.thinking } }, { type: 'thinking_end', data: {} })
      if (progress.text) events.push({ type: 'text_delta', data: { text: progress.text } }, { type: 'message_end', data: {} })
      add(progress.afterMessageId, progress.seq || 0, events)
    }
  }
  const restored: StreamEnvelope[] = []
  for (const message of messages) {
    const group = (side: string) => (groups.get(`${side}:${message.messageId}`) || []).sort((a, b) => a.seq - b.seq).flatMap(item => item.events)
    restored.push(...group('before'))
    const liveAnswer = snapshot?.history.answer?.messageId === message.messageId && snapshot.run.executing
      && ['PENDING', 'STREAMING'].includes(message.status)
    if (!liveAnswer) restored.push(...archiveEnvelopes(message))
    restored.push(...group('after'))
  }
  if (snapshot?.live.thinking) restored.push({ type: 'thinking_delta', data: { text: snapshot.live.thinking } })
  if (snapshot?.live.text && snapshot.run.executing) restored.push({ type: 'text_delta', data: { text: snapshot.live.text } })
  return restored
}
