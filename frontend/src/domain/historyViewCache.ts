import type { Msg } from './chat'
import type { MessagePage } from '../../../packages/protocol/workbench'
import { archivePage, replaceArchiveTail } from './archiveMessages'

export interface HistoryAnchor { key: string; offset: number; atEnd: boolean }
interface Window { messages: Msg[]; cursor: string | null; weight: number }
const windows = new Map<string, Window>(), anchors = new Map<string, HistoryAnchor>()
let weight = 0
export function rememberHistoryWindow(key: string, messages: Msg[], cursor: string | null) {
  const items = messages.filter(message => !!message.messageId || message._process)
  const size = items.reduce((sum, message) => sum + message.content.length + (message.reasoning?.length || 0)
    + (message._toolEvent?.content?.length || 0) + (message._toolEvent?.arguments?.length || 0), 0)
  if (!items.length || items.length > 20000 || size > 6_000_000) return
  const old = windows.get(key); if (old) { weight -= old.weight; windows.delete(key) }
  while (windows.size >= 8 || weight + size > 20_000_000) {
    const first = windows.entries().next().value
    if (!first) break
    weight -= first[1].weight; windows.delete(first[0]); anchors.delete(first[0])
  }
  windows.set(key, { messages: [...items], cursor, weight: size }); weight += size
}
export function restoreHistoryWindow(key: string, page: MessagePage) {
  const tail = archivePage(page), previous = windows.get(key)
  const messages = previous ? replaceArchiveTail(previous.messages, tail) : tail
  const retained = !!previous?.messages[0]?.messageId && messages[0]?.messageId === previous.messages[0].messageId
  if (!retained) anchors.delete(key)
  return { messages, cursor: retained ? previous!.cursor : page.nextCursor || null }
}
/** A provisional view only: reuse message bodies, but do not let live array edits mutate the cached window. */
export function peekHistoryWindow(key: string) {
  const previous = windows.get(key)
  return previous ? [...previous.messages] : []
}
export function discardHistoryWindow(key: string) {
  const previous = windows.get(key)
  if (previous) { weight -= previous.weight; windows.delete(key) }
  anchors.delete(key)
}
export function rememberHistoryAnchor(key: string, anchor: HistoryAnchor) {
  anchors.delete(key); anchors.set(key, anchor)
  if (anchors.size > 16) anchors.delete(anchors.keys().next().value!)
}
export function historyAnchor(key: string) { return anchors.get(key) }
export function discardHistoryAnchor(key: string) { anchors.delete(key) }
export function historyViewKey(scopeId: string | null | undefined, conversationId: string | null | undefined) { return `${scopeId || 'direct'}:${conversationId || 'draft'}` }
