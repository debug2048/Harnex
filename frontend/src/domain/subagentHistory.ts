import type { SemanticActivity } from './activity'
import type { ToolEvent } from './chat'

export function subagentForTool(activities: SemanticActivity[], tool: ToolEvent) {
  const thread = (tool.content || tool.summary || '').match(/\bthread:\s*([^) \r\n]+)/i)?.[1]
  const children = activities.filter(item => item.kind === 'subagent')
  if (thread) return children.find(item => item.subagentThreadId === thread)
  const matches = children.filter(item => item.delegateCallId === tool.id).sort((left, right) => left.seq - right.seq)
  return tool.seq ? matches.find(item => item.seq >= tool.seq!) : matches[matches.length - 1]
}

/** Group historical sibling delegations using their durable start sequence/time. Live cards remain independent. */
export function groupHistoricalSubagentWaves(activities: SemanticActivity[]): SemanticActivity[][] {
  const children = activities
    .filter(activity => activity.kind === 'subagent' && activity.historical)
    .sort((left, right) => left.seq - right.seq)
  const groups: SemanticActivity[][] = []
  for (const child of children) {
    const current = groups[groups.length - 1]
    const first = current?.[0]
    const previous = current?.[current.length - 1]
    const closeInSequence = previous ? child.seq - previous.seq <= 10 : false
    const closeInTime = first?.ts && child.ts ? child.ts - first.ts <= 5_000 : closeInSequence
    if (!current || !closeInSequence || !closeInTime) groups.push([child])
    else current.push(child)
  }
  return groups
}
