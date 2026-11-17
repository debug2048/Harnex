import type { ToolEvent } from './chat'

export type ToolActivityFamily =
  | 'read'
  | 'edit'
  | 'shell'
  | 'web'
  | 'delegate'
  | 'memory'
  | 'todo'
  | 'generic'

export interface ToolActivityGroup {
  family: ToolActivityFamily
  events: ToolEvent[]
}

/** Failed activity needs immediate attention; running/successful groups stay compact by default. */
export function shouldInitiallyExpandToolActivity(events: ToolEvent[]) {
  return events.some(event => event.error)
}

/** A single command already has a complete command card; an extra group card only adds another click. */
export function singleDirectToolActivity(events: ToolEvent[]) {
  if (events.length !== 1) return null
  return toolActivityFamily(events[0]) === 'shell' ? events[0] : null
}

export function toolActivityFamily(event: ToolEvent): ToolActivityFamily {
  const name = String(event.name || '').toLowerCase()
  if (name === 'delegate' || name.includes('subagent')) return 'delegate'
  if (name.includes('web_') || name.includes('fetch_url') || name.includes('search_web')) return 'web'
  if (name.includes('shell') || name.includes('bash') || name.includes('command')) return 'shell'
  if (name.includes('todo')) return 'todo'
  if (name.includes('memory') || name.includes('remember')) return 'memory'
  if (name.includes('write') || name.includes('edit') || name.includes('patch')
      || name.includes('replace')) return 'edit'
  if (name.includes('read') || name.includes('grep') || name.includes('glob')
      || name.includes('list') || name.includes('find') || name.includes('search')) return 'read'
  return 'generic'
}

export function canMergeToolActivity(group: ToolActivityGroup, event: ToolEvent) {
  const family = toolActivityFamily(event)
  return group.family === family
    && group.events.every(item => (item.purpose || '') === (event.purpose || ''))
    && (family === 'read' || family === 'edit' || family === 'web')
    && group.events.every(item => item.done && !item.error)
    && event.done
    && !event.error
}

export function groupToolActivities(events: ToolEvent[]): ToolActivityGroup[] {
  const groups: ToolActivityGroup[] = []
  for (const event of events) {
    const previous = groups[groups.length - 1]
    if (previous && canMergeToolActivity(previous, event)) {
      previous.events.push(event)
    } else {
      groups.push({ family: toolActivityFamily(event), events: [event] })
    }
  }
  return groups
}

export function toolActivityTargets(events: ToolEvent[]) {
  const targets = new Set<string>()
  for (const event of events) {
    const path = event.metadata?.diff?.path
    if (path) targets.add(path)
    if (!event.arguments) continue
    try {
      const value = JSON.parse(event.arguments) as Record<string, unknown>
      for (const key of ['path', 'file', 'target', 'url', 'glob']) {
        if (typeof value[key] === 'string' && value[key]) targets.add(String(value[key]))
      }
    } catch {
      // Raw arguments remain available in the technical detail; they are not a safe path source.
    }
  }
  return [...targets]
}
