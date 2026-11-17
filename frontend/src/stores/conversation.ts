import type { NotificationMode } from '../domain/im'
import { defineStore } from 'pinia'
import { computed, ref, watch } from 'vue'
import type { Msg } from '../domain/chat'
import type { UploadInfo } from '../api/client'
import type { PendingSubmission } from '../../../packages/protocol/workbench'

export type PermissionMode = 'ask' | 'accept-edits' | 'yolo'
export type WorkingMode = 'CONVERSATION' | 'AGENT'
interface WorkingDraft {
  text: string
  attachments: UploadInfo[]
  conversationId: string | null
  permission: PermissionMode
}

/**
 * Durable conversation/view state only. Network streaming and Run facts deliberately live in
 * runStore; pending approvals/questions live in interactionStore.
 */
export const useConversationStore = defineStore('conversation', () => {
  const messages = ref<Msg[]>([])
  const draftText = ref('')
  const composerFocusRequested = ref(false)
  const draftAttachments = ref<UploadInfo[]>([])
  const threadId = ref<string | null>(null)
  const loadedThreadId = ref<string | null>(null)
  const loadingHistory = ref(false)
  const navigationReady = ref(false)
  const draftRevision = ref(0)
  const permissionMode = ref<PermissionMode>('ask')
  const notificationMode = ref<NotificationMode>('OFF')
  const scopeProfile = ref<'PERSONAL' | 'PROJECT'>('PERSONAL')
  const executionKind = computed<WorkingMode>(() => scopeProfile.value === 'PERSONAL' ? 'CONVERSATION' : 'AGENT')
  const historyRevision = ref(0)
  const parentMessageId = ref<string | null>(null)
  const historyCursor = ref<string | null>(null)
  const submissions = ref<PendingSubmission[]>([])
  const draftRequestRevision = ref(0)
  const workbenchRequestRevision = ref(0)
  function requestWorkbench() { workbenchRequestRevision.value++ }
  function addWorkspaceContext(path: string): boolean {
    const value = path.trim()
    if (executionKind.value !== 'AGENT' || !value) return false
    const reference = value.includes(' ') ? `@"${value}"` : `@${value}`
    draftText.value = draftText.value.trim() ? `${draftText.value.trimEnd()}\n${reference} ` : `${reference} `
    composerFocusRequested.value = true
    return true
  }
  let preparedDraft: { scopeId: string; instanceId?: string; revision: number; text: string; attachments: UploadInfo[] } | null = null

  function prepareScopeDraft(id: string, text: string, attachments: UploadInfo[], instanceId?: string) {
    const revision = ++draftRequestRevision.value
    preparedDraft = { scopeId: id, instanceId, revision, text, attachments: [...attachments] }
    return revision
  }
  function preparedScopeDraft(id: string | null) { return preparedDraft?.scopeId === id ? preparedDraft : null }
  function clearPreparedDraft(revision: number) { if (preparedDraft?.revision === revision) preparedDraft = null }

  const drafts = new Map<string, WorkingDraft>()
  const notificationPreferences = new Map<string, NotificationMode>()
  let scopeId: string | null = null
  const draftKey = () => scopeId || 'direct'
  const notificationKey = () => 'harnex:task-notification:' + encodeURIComponent(draftKey())
  function rememberedNotification() {
    if (scopeProfile.value !== 'PROJECT') return 'OFF'
    const remembered = notificationPreferences.get(draftKey())
    if (remembered) return remembered
    try {
      const saved = localStorage.getItem(notificationKey())
      return saved === 'ALL' || saved === 'FAILURES' ? saved : 'OFF'
    } catch { return 'OFF' }
  }
  watch(notificationMode, value => {
    if (scopeProfile.value !== 'PROJECT') return
    notificationPreferences.set(draftKey(), value)
    try { localStorage.setItem(notificationKey(), value) } catch { /* Keep this session's preference if storage is unavailable. */ }
  }, { flush: 'sync' })
  function rememberDraft() {
    drafts.set(draftKey(), { text: draftText.value, attachments: [...draftAttachments.value],
      conversationId: threadId.value || drafts.get(draftKey())?.conversationId || null, permission: permissionMode.value })
  }
  function restoreDraft() {
    draftRevision.value++
    const saved = drafts.get(draftKey())
    draftText.value = saved?.text || ''
    draftAttachments.value = saved?.attachments || []
    permissionMode.value = saved?.permission || 'ask'
    notificationMode.value = rememberedNotification()
    return saved?.conversationId || null
  }
  function forgetConversation(id: string) {
    const saved = drafts.get(draftKey())
    if (saved && (saved.conversationId === id || threadId.value === id)) drafts.set(draftKey(), { ...saved, conversationId: null })
  }
  function setScope(id: string | null, profile: 'PERSONAL' | 'PROJECT') {
    if (scopeId === id && scopeProfile.value === profile) return threadId.value || drafts.get(draftKey())?.conversationId || null
    composerFocusRequested.value = false
    rememberDraft()
    scopeId = id; scopeProfile.value = profile
    return restoreDraft()
  }

  function reset(preserveDraft = false) {
    composerFocusRequested.value = false
    messages.value = []
    threadId.value = null
    loadedThreadId.value = null
    loadingHistory.value = false
    if (!preserveDraft) {
      drafts.delete(draftKey())
      draftRevision.value++
      permissionMode.value = 'ask'
      draftText.value = ''
      draftAttachments.value = []
      rememberDraft()
    }
    historyRevision.value = 0
    parentMessageId.value = null
    historyCursor.value = null
    submissions.value = []
  }

  return { messages, draftText, draftAttachments, composerFocusRequested, addWorkspaceContext, threadId, loadedThreadId, loadingHistory, navigationReady, draftRevision, permissionMode, notificationMode, executionKind,
    historyRevision, parentMessageId, historyCursor, submissions, reset, setScope, rememberDraft, forgetConversation,
    draftRequestRevision, prepareScopeDraft, preparedScopeDraft, clearPreparedDraft, workbenchRequestRevision, requestWorkbench }
})
