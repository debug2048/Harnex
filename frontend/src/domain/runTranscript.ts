import type { Presentation, RunView } from '../../../packages/protocol/workbench'
import { isBriefToolCommentary, type Msg, type ToolEvent } from './chat'
import { mergeRunProcess } from './archiveMessages'

export function processMessage(runId: string, seq: number, content: string, reasoning: string): Msg {
  const brief = isBriefToolCommentary(content)
  return { _uid: `process:${runId}:${seq}`, runId, _seq: seq, _process: true, role: 'assistant',
    content: brief ? content : '', reasoning: brief ? reasoning : [reasoning, content].filter(Boolean).join('\n\n'),
    _reasoningBlock: !brief }
}

export function toolMessage(runId: string, event: ToolEvent): Msg {
  return { _uid: `tool-${runId}:${event.seq}:${event.id}`, runId, _seq: event.seq, _process: true,
    role: 'tool', content: event.content || '', _toolEvent: event }
}

/** Recover the display from a captured Run; the immutable conversation archive owns only input/output. */
export function restoreRunProcess(messages: Msg[], run: RunView, live?: Presentation['live']): Msg[] {
  const gateway = run.origin?.kind === 'GATEWAY'
  const [gatewayChannel = 'webhook', source = ''] = gateway ? (run.origin.reference || 'webhook').split('|') : []
  const channel = gateway ? gatewayChannel : run.channel?.trim()
  if (channel) {
    messages = messages.map(message => message.role === 'user'
      && !message.channel
      && (message.runId ? message.runId === run.runId : Boolean(run.turnId && message.turnId === run.turnId))
      ? { ...message, channel, source } : message)
  }
  const recovered: Msg[] = []
  // Durable semantic tool facts remain available even after the live replay window expires.
  const tools: ToolEvent[] = []
  // The bounded activity window may contain only a call's end. Prefer the original
  // occurrence sequence from live/cached tools so it cannot become a second call.
  const knownTools = [
    ...(live?.tools || []).filter(tool => !tool.subagentConversationId).map(tool => ({ ...tool, live: true })),
    ...messages.filter(message => message.runId === run.runId && message._toolEvent)
      .map(message => ({ ...message._toolEvent!, live: false })),
  ].sort((left, right) => (right.seq || 0) - (left.seq || 0))
  const endedAt = new Map<string, number>()
  for (const activity of run.activities || []) {
    if (activity.subagentThreadId || !['tool_use_queued', 'tool_use_start', 'tool_use_end', 'tool_use_status'].includes(activity.eventType)) continue
    const id = activity.id || activity.callId
    if (!id) continue
    let tool = activity.eventType === 'tool_use_queued' ? undefined : tools.find(item => item.id === id
      && (!item.done || item.resultPending) && (activity.eventType !== 'tool_use_start' || item.status === 'queued'))
    if (!tool) {
      const known = knownTools.find(item => item.id === id && item.seq && item.seq <= activity.seq
        && item.seq > (endedAt.get(id) || 0)
        && (item.seq === activity.seq || activity.eventType !== 'tool_use_queued'
          && (item.live || ['tool_use_end', 'tool_use_status'].includes(activity.eventType))))
      tool = { id, name: activity.name || activity.toolName || '', seq: known?.seq || activity.seq, done: false }
      tools.push(tool)
    }
    tool.name = activity.name || activity.toolName || tool.name
    tool.summary = activity.activitySummary || tool.summary
    tool.purpose = activity.purpose || tool.purpose
    tool.detailSource = { runId: run.runId, seq: tool.seq! }
    if (['tool_use_queued', 'tool_use_start'].includes(activity.eventType) && activity.activitySummary) {
      // Short, complete argument JSON can populate the title immediately. Clipped summaries cannot.
      try { if (JSON.parse(activity.activitySummary)) tool.arguments = activity.activitySummary } catch { /* lazy detail */ }
    }
    tool.done = ['tool_use_end', 'tool_use_status'].includes(activity.eventType)
    tool.error = activity.activityOutcome === 'failed'
    tool.resultPending = activity.eventType !== 'tool_use_end'
    tool.status = tool.done ? tool.error ? 'failed' : 'completed' : activity.eventType === 'tool_use_queued' ? 'queued' : 'running'
    if (activity.eventType === 'tool_use_end') endedAt.set(id, activity.seq)
  }
  for (const tool of live?.tools || []) {
    if (!tool.id || tool.subagentConversationId) continue
    const previous = tools.find(item => item.id === tool.id && item.seq === tool.seq)
      || tools.find(item => item.id === tool.id && !item.done)
    const event: ToolEvent = { id: tool.id, name: tool.name || '', seq: tool.seq,
      arguments: tool.arguments, content: tool.content, summary: tool.summary, purpose: tool.purpose || previous?.purpose, durationMs: tool.durationMs,
      detailSource: { runId: run.runId, seq: tool.seq! },
      resultPending: tool.resultPending,
      done: ['COMPLETED', 'FAILED'].includes(tool.state || ''), error: tool.state === 'FAILED',
      status: tool.state === 'QUEUED' ? 'queued' : tool.state === 'RUNNING' ? 'running' : tool.state === 'FAILED' ? 'failed' : 'completed' }
    if (previous) Object.assign(previous, event)
    else tools.push(event)
  }
  for (const tool of tools) {
    if (!tool.done && run.terminal) { tool.done = true; tool.error = true; tool.status = 'failed' }
    if (run.terminal) tool.resultPending = false
    const item = toolMessage(run.runId, tool)
    item._afterMessageId = live?.tools.find(entry => entry.id === tool.id && entry.seq === tool.seq)?.afterMessageId
    const existing = messages.find(message => message._uid === item._uid)
    // A live client has full outputs and diff metadata; compact snapshots must not replace them with summaries.
    if (existing?._toolEvent) {
      item._afterMessageId = existing._afterMessageId
      item._beforeMessageId = existing._beforeMessageId
      const complete = existing._toolEvent.done && !existing._toolEvent.resultPending
        && !existing._toolEvent.detailSource && existing._toolEvent.content !== undefined
      item._toolEvent = { ...existing._toolEvent, ...tool,
        detailSource: complete ? undefined : tool.detailSource,
        arguments: existing._toolEvent.arguments || tool.arguments,
        purpose: tool.purpose || existing._toolEvent.purpose,
        content: complete ? existing._toolEvent.content : tool.content,
        durationMs: tool.durationMs ?? existing._toolEvent.durationMs }
      item.content = item._toolEvent.content || ''
    }
    recovered.push(item)
  }
  for (const item of live?.messages || []) {
    const message = processMessage(run.runId, item.seq || 0, item.text || '', item.thinking || '')
    message._afterMessageId = item.afterMessageId
    const existing = messages.find(current => current._uid === message._uid)
    recovered.push(existing || message)
  }
  return mergeRunProcess(messages, recovered)
}
