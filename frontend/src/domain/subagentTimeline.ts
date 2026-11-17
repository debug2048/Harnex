import type { SemanticActivity } from './activity'
import type { ToolEvent } from './chat'
import { t } from '../i18n'

export type SubagentTimelineEntry =
  | { kind: 'tool'; id: string; ts?: number; tool: ToolEvent }
  | { kind: 'activity'; id: string; ts?: number; activity: SemanticActivity }

/** Queue/start/result are transitions of one tool invocation, not three unfinished steps. */
export function subagentTimeline(activity?: SemanticActivity | null): SubagentTimelineEntry[] {
  if (!activity) return []
  const result: SubagentTimelineEntry[] = []
  const calls: Extract<SubagentTimelineEntry, { kind: 'tool' }>[] = []
  const pending = new Map<string, Extract<SubagentTimelineEntry, { kind: 'tool' }>[]>()
  const compressions = new Map<string, Extract<SubagentTimelineEntry, { kind: 'activity' }>>()
  const steps = [...(activity.details || [])].sort((left, right) => left.seq - right.seq)
  const terminal = !['running', 'waiting'].includes(activity.outcome)
  for (const [index, step] of steps.entries()) {
    const type = step.childEventType || ''
    if (!['tool_use_queued', 'tool_use_start', 'tool_use_end', 'tool_use_status'].includes(type)) {
      const compressionKey = step.groupId || step.callId || ''
      if (type === 'context_compression_finished' && compressionKey && compressions.has(compressionKey)) {
        const entry = compressions.get(compressionKey)!
        entry.activity = { ...step, seq: entry.activity.seq, ts: entry.ts }
        continue
      }
      const settledModel = type === 'message_start' && (terminal || index < steps.length - 1)
      const entry: Extract<SubagentTimelineEntry, { kind: 'activity' }> = { kind: 'activity', id: step.id, ts: step.ts,
        activity: settledModel ? { ...step, outcome: 'completed' } : step }
      result.push(entry)
      if (type === 'context_compression_started' && compressionKey) compressions.set(compressionKey, entry)
      continue
    }
    const id = step.callId || step.groupId || step.id
    const queue = pending.get(id) || []
    let entry = type === 'tool_use_queued' ? undefined : queue.find(item => item.tool.id === id
      && (!item.tool.done || item.tool.resultPending)
      && (type !== 'tool_use_start' || item.tool.status === 'queued'))
    if (!entry) {
      entry = { kind: 'tool', id: step.id, ts: step.ts, tool: {
        id, name: step.toolName || 'tool', seq: step.seq, done: false,
        detailSource: activity.runId ? { runId: activity.runId, seq: step.seq } : undefined,
      } }
      calls.push(entry); result.push(entry)
      queue.push(entry); pending.set(id, queue)
    }
    const tool = entry.tool
    tool.name = step.toolName || tool.name
    tool.summary = step.summary
    tool.purpose = step.purpose || tool.purpose
    if (step.arguments !== undefined) tool.arguments = step.arguments
    else if (!tool.arguments && ['tool_use_queued', 'tool_use_start'].includes(type)) {
      try { JSON.parse(step.summary); tool.arguments = step.summary } catch { /* Full arguments load on expansion. */ }
    }
    if (step.content !== undefined) tool.content = step.content
    if (step.durationMs !== undefined) tool.durationMs = step.durationMs
    tool.done = type === 'tool_use_end' || type === 'tool_use_status'
    tool.error = step.outcome === 'failed'
    tool.resultPending = type !== 'tool_use_end'
    tool.status = tool.done ? tool.error ? 'failed' : 'completed' : type === 'tool_use_queued' ? 'queued' : 'running'
    if (type === 'tool_use_end') {
      queue.splice(queue.indexOf(entry), 1)
      if (!queue.length) pending.delete(id)
    }
  }
  if (terminal) for (const { tool } of calls) {
    if (!tool.done) {
      tool.done = true; tool.error = true; tool.status = 'failed'
      tool.summary = t('chat.subagentStepInterrupted')
    }
    tool.resultPending = false
  }
  if (terminal) for (const entry of result) {
    if (entry.kind === 'activity' && ['waiting', 'running'].includes(entry.activity.outcome)) {
      entry.activity = { ...entry.activity, outcome: 'failed' }
    }
  }
  return result
}

/** Hide only the generated trailing trace; the individual tool results are available in the timeline. */
export function subagentResultText(output: string): string {
  const marker = '\n\n[sub-agent tool trace]\n'
  const index = output.lastIndexOf(marker)
  if (index < 0) return output
  const rows = output.slice(index + marker.length).trim().split(/\r?\n/)
  return rows.length && rows.every(row => /^- .+ \((ok|error)\)(:.*)?$/.test(row))
    ? output.slice(0, index).trimEnd() : output
}
