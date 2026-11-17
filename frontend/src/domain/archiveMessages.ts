import type { MessageRecord, MessagePage } from '../../../packages/protocol/workbench'
import type { Msg } from './chat'

export function archiveMessage(record: MessageRecord): Msg {
  return {
    _uid: record.messageId, messageId: record.messageId, runId: record.runId || undefined,
    turnId: record.turnId, status: record.status, parentMessageId: record.parentMessageId || undefined,
    role: record.role === 'USER' ? 'user' : 'assistant',
    content: record.contentParts.filter(part => part.type === 'TEXT').map(part => part.text || '').join('\n'),
    contentParts: record.contentParts,
    attachments: record.contentParts.filter(part => part.type === 'ARTIFACT_REF' && part.artifactId)
      .map(part => ({ artifactId: part.artifactId!, name: part.artifactId! })),
  }
}

/** Wire pages are newest first. The transcript renders the fixed ancestor chain oldest first. */
export function archivePage(page: MessagePage): Msg[] { return [...page.items].reverse().map(archiveMessage) }
export function prependArchivePage(messages: Msg[], page: MessagePage): Msg[] {
  const ids = new Set(messages.map(message => message.messageId))
  return [...archivePage(page).filter(item => !ids.has(item.messageId)), ...messages]
}

export function replaceArchiveTail(messages: Msg[], tail: Msg[]): Msg[] {
  // Archive records contain input/output; Run metadata supplies the source. Preserve that
  // attribution when a newer archive page refreshes older turns in the same visible history.
  const previous = new Map(messages.filter(item => item.messageId).map(item => [item.messageId, item]))
  tail = tail.map(item => {
    const old = previous.get(item.messageId)
    return old?.channel && !item.channel && old.runId === item.runId && old.turnId === item.turnId
      ? { ...item, channel: old.channel, source: old.source } : item
  })
  const overlap = tail[0]?.messageId
  const index = overlap ? messages.findIndex(message => message.messageId === overlap) : -1
  const archive = index >= 0 ? [...messages.slice(0, index).filter(item => !item._process), ...tail] : tail
  return mergeRunProcess(archive, messages.filter(item => item._process))
}

/** Public answers and Agent progress are separate projections; refreshing one must not erase the other. */
export function mergeRunProcess(messages: Msg[], process: Msg[]): Msg[] {
  const archive = messages.filter(item => !item._process)
  const anchors = new Map(archive.filter(item => item.messageId).map(item => [item.messageId!, item]))
  const firstByRun = new Map<string, Msg>()
  for (const item of archive) if (item.runId && !firstByRun.has(item.runId)) firstByRun.set(item.runId, item)
  const retainedRuns = new Set(archive.filter(item => item.role === 'assistant').map(item => item.runId))
  const last = archive[archive.length - 1]
  if (last?.role === 'user') retainedRuns.add(last.runId)
  const groups = new Map<string, Map<string, Msg>>()
  for (const item of [...messages.filter(item => item._process), ...process]) {
    if (!item.runId || !item._uid || !retainedRuns.has(item.runId)) continue
    const explicitId = item._afterMessageId || item._beforeMessageId
    const explicit = explicitId ? anchors.get(explicitId) : undefined
    const anchor = explicit?.runId === item.runId ? explicit : firstByRun.get(item.runId)
    if (!anchor?.messageId) continue // Do not carry progress across a branch or conversation change.
    const before = explicit?.runId === item.runId ? !!item._beforeMessageId : anchor.role === 'assistant'
    const key = `${before ? 'before' : 'after'}:${anchor.messageId}`
    const group = groups.get(key) || new Map<string, Msg>()
    group.set(item._uid, { ...item, _afterMessageId: before ? undefined : anchor.messageId,
      _beforeMessageId: before ? anchor.messageId : undefined })
    groups.set(key, group)
  }
  return archive.flatMap(item => {
    const progress = (side: string) => [...(groups.get(`${side}:${item.messageId}`)?.values() || [])]
      .sort((left, right) => (left._seq || 0) - (right._seq || 0) || Number(!!left._toolEvent) - Number(!!right._toolEvent))
    return [...progress('before'), item, ...progress('after')]
  })
}
