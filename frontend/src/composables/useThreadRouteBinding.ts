import { watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useConversationStore } from '../stores/conversation'
import { useRunController } from '../services/runController'
import { useWorkspaceStore } from '../stores/workspace'
import { ApiHttpError, getRuntimeRevision } from '../api/client'

/** Keeps Thread identity and deep-link routing synchronized in both directions. */
export function useThreadRouteBinding() {
  const route = useRoute()
  const router = useRouter()
  const conversation = useConversationStore()
  const runController = useRunController()
  const workspace = useWorkspaceStore()
  const active = () => route.path === '/' || route.path.startsWith('/t/')

  async function syncFromRoute() {
    if (!active() || !workspace.ready || !conversation.navigationReady) return
    const tid = String(route.params.tid || '')
    const revision = getRuntimeRevision()
    if (tid) {
      try {
        await runController.loadThread(tid)
      } catch (cause) {
        if (cause instanceof ApiHttpError && cause.status === 404
            && cause.code === 'conversation_not_found'
            && revision === getRuntimeRevision() && conversation.threadId === tid && route.params.tid === tid) {
          // A removed/stale conversation must not pin its ID to an otherwise valid new draft.
          conversation.navigationReady = false
          conversation.forgetConversation(tid)
          runController.reset(true)
          conversation.rememberDraft()
          await router.replace('/')
          if (revision === getRuntimeRevision() && workspace.ready) conversation.navigationReady = true
          return
        }
        console.warn('[chat] route→thread load failed:', cause)
      }
    }
  }

  watch(() => [route.params.tid, workspace.ready, conversation.navigationReady], syncFromRoute)
  watch(() => conversation.threadId, tid => {
    if (!active() || !workspace.ready || !conversation.navigationReady) return
    if (!tid || route.params.tid === tid) return
    const target = `/t/${encodeURIComponent(tid)}`
    if (route.path !== target) {
      router.push(target).catch(cause =>
        console.warn('[chat] URL sync failed:', target, cause))
    }
  })

  return { route, router, syncFromRoute }
}
