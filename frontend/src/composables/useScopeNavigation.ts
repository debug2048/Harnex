import { watch } from 'vue'
import type { Router } from 'vue-router'
import { getRuntimeRevision } from '../api/client'
import { useRunController } from '../services/runController'
import { useConversationStore } from '../stores/conversation'
import { useThreadStore } from '../stores/threads'
import { useWorkspaceStore } from '../stores/workspace'

/** One owner publishes the target draft, route and history-loading gate at every scope boundary. */
export function useScopeNavigation(router: Router, onReady: () => void = () => {}) {
  const workspace = useWorkspaceStore(), conversation = useConversationStore(), threads = useThreadStore()
  const controller = useRunController()
  let invalidated = false, navigation = 0
  let handledWorkbenchRequest = conversation.workbenchRequestRevision
  watch(() => workspace.epoch, () => {
    navigation++
    conversation.rememberDraft()
    conversation.navigationReady = false
    invalidated = true
    controller.reset(true)
    threads.reset()
  }, { flush: 'sync' })
  watch(() => [workspace.ready, workspace.scopeId, workspace.scopeInstanceId, conversation.draftRequestRevision, conversation.workbenchRequestRevision] as const,
    async ([ready]) => {
      if (!ready) return
      const version = ++navigation, revision = getRuntimeRevision()
      const workbenchRequest = conversation.workbenchRequestRevision
      const current = () => version === navigation && revision === getRuntimeRevision() && workspace.ready
      conversation.navigationReady = false
      let selected = conversation.setScope(workspace.storageScopeId, workspace.profile)
      const pending = conversation.preparedScopeDraft(workspace.scopeId)
      const prepared = pending && (!pending.instanceId || pending.instanceId === workspace.scopeInstanceId) ? pending : null
      if (prepared) {
        controller.reset()
        conversation.permissionMode = 'ask'
        conversation.draftText = prepared.text
        conversation.draftAttachments = prepared.attachments
        conversation.rememberDraft()
        conversation.clearPreparedDraft(prepared.revision)
        selected = null
      }
      const route = router.currentRoute.value
      if (workbenchRequest !== handledWorkbenchRequest) {
        const target = selected ? '/t/' + encodeURIComponent(selected) : '/'
        // A scope switch must not leave the previous scope's thread URL in this scope's back history.
        if (invalidated && (route.path === '/' || route.path.startsWith('/t/'))) await router.replace(target)
        else await router.push(target)
      } else if (prepared || invalidated && (route.path === '/' || route.path.startsWith('/t/'))) {
        await router.replace(selected ? `/t/${encodeURIComponent(selected)}` : '/')
      }
      if (!current()) return
      handledWorkbenchRequest = workbenchRequest
      invalidated = false
      conversation.navigationReady = true
      onReady()
      void threads.refresh().catch(() => {})
    }, { immediate: true })
}
