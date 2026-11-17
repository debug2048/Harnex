import { computed, onUnmounted, ref, watch } from 'vue'
import { api } from '../api/client'
import { useConversationStore } from '../stores/conversation'
import { useRunStore } from '../stores/run'
import { t } from '../i18n'

export type ThreadTodo = { id: number; title: string; status: string; progress?: string; _turn?: number }

export function isTodoListForTurn(items: ThreadTodo[], userTurn: number, allowLegacy = true) {
  const rawOwner = items.find(item => Number.isFinite(Number(item._turn)))?._turn
  return rawOwner == null ? allowLegacy : Number(rawOwner) === userTurn
}

/** Polling and projection for the active Thread's persisted todo list. */
export function useThreadTodos() {
  const conversation = useConversationStore()
  const run = useRunStore()
  const todos = ref<ThreadTodo[]>([])
  const visible = ref(false)
  const expanded = ref(false)
  let timer: number | null = null
  let consecutiveErrors = 0
  let suppressLegacyList = false

  function currentUserTurn() {
    return conversation.messages.filter(message => message.role === 'user').length
  }

  function belongsToCurrentTurn(items: ThreadTodo[]) {
    return isTodoListForTurn(items, currentUserTurn(), !suppressLegacyList)
  }

  async function refresh() {
    const tid = conversation.threadId
    if (!tid || conversation.executionKind !== 'AGENT') {
      todos.value = []
      visible.value = false
      return
    }
    try {
      const result = await api.todos(tid)
      if (tid !== conversation.threadId) return
      todos.value = Array.isArray(result) ? result : []
      visible.value = todos.value.length > 0 && belongsToCurrentTurn(todos.value)
      consecutiveErrors = 0
    } catch {
      if (tid !== conversation.threadId) return
      consecutiveErrors++
      if (consecutiveErrors >= 3 && timer) {
        window.clearInterval(timer)
        timer = null
      }
    }
  }

  function reset() {
    todos.value = []
    visible.value = false
    expanded.value = false
  }

  /** Start a new human-request turn without letting polling resurrect a legacy checklist from older work. */
  function beginTurn() {
    suppressLegacyList = true
    reset()
  }

  watch(() => conversation.threadId, () => {
    consecutiveErrors = 0
    suppressLegacyList = false
    reset()
    void refresh()
  })
  watch(() => run.todoRevision, () => {
    suppressLegacyList = false
    void refresh()
  })
  watch(() => run.streaming, streaming => {
    if (streaming && conversation.executionKind === 'AGENT' && !timer) {
      consecutiveErrors = 0
      timer = window.setInterval(refresh, 2500)
    } else if (!streaming && timer) {
      window.clearInterval(timer)
      timer = null
      void refresh()
    }
  })
  watch(() => conversation.executionKind, kind => {
    if (kind !== 'AGENT') { if (timer) window.clearInterval(timer); timer = null; reset() }
    else void refresh()
  })
  onUnmounted(() => {
    if (timer) window.clearInterval(timer)
    timer = null
  })

  const summary = computed(() => {
    const done = todos.value.filter(todo => todo.status === 'completed').length
    return todos.value.length ? `${done}/${todos.value.length}` : ''
  })
  const doneCount = computed(() => todos.value.filter(todo => todo.status === 'completed').length)
  const activeCount = computed(() => todos.value.filter(todo => todo.status !== 'completed').length)
  const runningCount = computed(() => todos.value.filter(todo => todo.status === 'in-progress').length)
  const active = computed(() =>
    todos.value.find(todo => todo.status === 'in-progress')
      || todos.value.find(todo => todo.status !== 'completed')
      || todos.value[todos.value.length - 1],
  )
  const activeTitle = computed(() => runningCount.value > 1
    ? t('chat.todosRunning', { n: runningCount.value })
    : active.value?.title || '')
  const progressPercent = computed(() =>
    todos.value.length ? Math.round((doneCount.value / todos.value.length) * 100) : 0,
  )

  return {
    todos, visible, expanded, summary, doneCount, activeCount, runningCount, active, activeTitle, progressPercent,
    refresh, reset, beginTurn,
  }
}
