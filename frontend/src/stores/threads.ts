import { defineStore } from 'pinia'
import { ref } from 'vue'
import { api, getRuntimeRevision } from '../api/client'
import { errorMessage } from '../utils/unknown'
import { useWorkspaceStore } from './workspace'
import { useConversationStore } from './conversation'

export interface ThreadSummary { id: string; title: string; fullTitle?: string; updatedAt: string }

export const useThreadStore = defineStore('threads', () => {
  const workspace = useWorkspaceStore()
  const items = ref<ThreadSummary[]>([])
  const current = ref<string | null>(null)
  const nextCursor = ref<string | null>(null)
  const loading = ref(false), error = ref<string | null>(null)
  const cache = new Map<string, { items: ThreadSummary[]; cursor: string | null }>()
  const identity = () => JSON.stringify([workspace.scopeId, workspace.scopeInstanceId])
  let sequence = 0
  function remember() {
    const key = identity()
    cache.delete(key); cache.set(key, { items: [...items.value], cursor: nextCursor.value })
    if (cache.size > 8) cache.delete(cache.keys().next().value!)
  }

  async function refresh(keepLoaded = false) {
    if (!workspace.ready) return
    const requestEpoch = workspace.epoch, revision = getRuntimeRevision(), request = ++sequence, key = identity()
    const saved = cache.get(key)
    if (!items.value.length && saved) { items.value = [...saved.items]; nextCursor.value = saved.cursor }
    loading.value = true; error.value = null
    const valid = () => request === sequence && requestEpoch === workspace.epoch && revision === getRuntimeRevision()
    try {
      const result = await api.conversations()
      if (!valid()) return
      const incoming = result.items.map(item => ({ id: item.conversationId, title: item.title,
        updatedAt: new Date(item.updatedAt).toISOString() }))
      const seen = new Set(incoming.map(item => item.id))
      const retained = keepLoaded ? items.value.filter(item => !seen.has(item.id)) : []
      items.value = [...incoming, ...retained]
      if (!keepLoaded || !retained.length) nextCursor.value = result.nextCursor || null
      remember()
    } catch (cause) { if (valid()) error.value = errorMessage(cause) }
    finally { if (valid()) loading.value = false }
  }
  async function loadMore() {
    if (!nextCursor.value) return
    const epoch = workspace.epoch, cursor = nextCursor.value, revision = getRuntimeRevision(), request = sequence
    const page = await api.conversations(cursor)
    if (epoch !== workspace.epoch || cursor !== nextCursor.value || revision !== getRuntimeRevision() || request !== sequence) return
    const seen = new Set(items.value.map(item => item.id))
    items.value.push(...page.items.filter(item => !seen.has(item.conversationId)).map(item => ({
      id: item.conversationId, title: item.title, updatedAt: new Date(item.updatedAt).toISOString(),
    })))
    nextCursor.value = page.nextCursor || null
    remember()
  }
  function select(id: string | null) { current.value = id }
  function updateTitle(id: string, title: string, fullTitle?: string) {
    if (!id || !title.trim()) return
    sequence++; loading.value = false
    const index = items.value.findIndex(item => item.id === id)
    if (index >= 0) {
      items.value.splice(index, 1, { ...items.value[index], title, fullTitle: fullTitle || title })
    } else {
      items.value.unshift({ id, title, fullTitle: fullTitle || title, updatedAt: new Date().toISOString() })
    }
    remember()
  }
  async function remove(id: string) {
    const requestEpoch = workspace.epoch, revision = getRuntimeRevision()
    const valid = () => requestEpoch === workspace.epoch && revision === getRuntimeRevision()
    const meta = await api.conversation(id)
    if (!valid()) return false
    await api.deleteConversation(id, { commandId: crypto.randomUUID(), expectedHistoryRevision: meta.historyRevision })
    if (!valid()) return false
    // Invalidate pending list reads before committing the deletion locally.
    sequence++; loading.value = false
    items.value = items.value.filter(item => item.id !== id)
    if (current.value === id) current.value = null
    useConversationStore().forgetConversation(id)
    remember()
    return true
  }
  function reset() {
    sequence++; loading.value = false; error.value = null
    items.value = []
    current.value = null
    nextCursor.value = null
  }

  return { items, current, nextCursor, loading, error, refresh, loadMore, select, updateTitle, remove, reset }
})
