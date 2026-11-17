<script setup lang="ts">
import type { ExecutionSpec } from '../../../packages/protocol/workbench'
import { ref, onMounted, onUnmounted, onActivated, onDeactivated, nextTick, watch, computed, toRef } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { conversationAttachmentViews, type Msg, type PlanReviewPrompt, type ToolEvent } from '../domain/chat'
import { useRunController } from '../services/runController'
import { useSettings } from '../stores/settings'
import { composerSubmitMode } from '../domain/composerShortcuts'
import { api, ApiHttpError, getRuntimeRevision } from '../api/client'
import Transcript, { type TranscriptItem } from '../components/chat/Transcript.vue'
import { useTranscriptScroll } from '../composables/useTranscriptScroll'
import TaskProgress from '../components/chat/TaskProgress.vue'
import Composer from '../components/chat/Composer.vue'
import TaskNotificationMenu from '../components/chat/TaskNotificationMenu.vue'
import WorkspaceGitStatus from '../components/chat/WorkspaceGitStatus.vue'
import ImageFailureNotice from '../components/chat/ImageFailureNotice.vue'
import { isImageFailure } from '../domain/imageFailure'
import ReviewDrawer from '../components/chat/ReviewDrawer.vue'
import ChangesDrawer from '../components/chat/ChangesDrawer.vue'
import SubagentDrawer from '../components/chat/SubagentDrawer.vue'
import ContextSourcesDrawer from '../components/chat/ContextSourcesDrawer.vue'
import RunDiagnosticsDrawer from '../components/chat/RunDiagnosticsDrawer.vue'
import RestoreDialog from '../components/chat/RestoreDialog.vue'
import { t } from '../i18n'
import { useActivityStore } from '../stores/activity'
import { useThreadStore } from '../stores/threads'
import { useChangesStore } from '../stores/changes'
import { useWorkspaceStore } from '../stores/workspace'
import { useConversationStore, type PermissionMode } from '../stores/conversation'
import { useInteractionStore } from '../stores/interaction'
import { useRunStore } from '../stores/run'
import { useChatUploads } from '../composables/useChatUploads'
import { useThreadTodos } from '../composables/useThreadTodos'
import { useThreadRouteBinding } from '../composables/useThreadRouteBinding'
import { useComposerCatalog } from '../composables/useComposerCatalog'
import { useRuntimeStore } from '../stores/runtime'
import { historyAnchor, historyViewKey } from '../domain/historyViewCache'
import { useTaskHandoffStore } from '../stores/taskHandoff'
import { useComposerSubmission } from '../composables/useComposerSubmission'
import { composerInputHistory, useComposerHistory } from '../composables/useComposerHistory'
import { useWorkbenchInspector } from '../composables/useWorkbenchInspector'
import { errorMessage } from '../utils/unknown'
import { canMergeToolActivity, toolActivityFamily } from '../domain/toolActivity'
import { summarizeRunChanges } from '../domain/runChanges'

const conversationStore = useConversationStore()
const agentEnabled = computed(() => conversationStore.executionKind === 'AGENT')
const currentRunAgent = computed(() => runStore.currentRun?.executionSpec.kind === 'AGENT')
const showAgentControls = computed(() => agentEnabled.value || currentRunAgent.value && !!runStore.currentRun?.ownsConversation)
const route = useRoute()
const router = useRouter()
const runStore = useRunStore()
const interactionStore = useInteractionStore()
const runController = useRunController()
// Component-local view binding only: it owns no state. Conversation, Run and Interaction
// remain separate Pinia stores; the controller owns only transport ordering.
const chat = {
  get messages() { return conversationStore.messages },
  set messages(value: Msg[]) { conversationStore.messages = value },
  get threadId() { return conversationStore.threadId },
  set threadId(value: string | null) { conversationStore.threadId = value },
  get permissionMode() { return conversationStore.permissionMode },
  set permissionMode(value: PermissionMode) { conversationStore.permissionMode = value },
  get streaming() { return runStore.streaming },
  get currentAssistant() { return runStore.currentAssistant },
  get currentThinking() { return runStore.currentThinking },
  get toolEvents() { return runStore.toolEvents },
  get todoRevision() { return runStore.todoRevision },
  get error() { return runStore.error },
  set error(value: string | null) { runStore.error = value },
  get goalProgress() { return runStore.goalProgress },
  get latestSnapshotSeq() { return runStore.latestSnapshotSeq },
  get tokenUsage() { return runStore.tokenUsage },
  get executionFacts() { return runStore.executionFacts },
  get activeRunId() { return runStore.activeRunId },
  get runInputs() { return runStore.runInputs },
  get pendingApprovals() { return interactionStore.pendingApprovals },
  get pendingQuestions() { return interactionStore.pendingQuestions },
  get pendingPlanReview() { return interactionStore.pendingPlanReview },
  set pendingPlanReview(value: PlanReviewPrompt | null) { interactionStore.pendingPlanReview = value },
  send: runController.send,
  loadThread: runController.loadThread,
  reset: runController.reset,
  resolveApproval: runController.resolveApproval,
  answerQuestion: runController.answerQuestion,
  reviewPlan: runController.reviewPlan,
  cancel: runController.cancel,
  resendFromUserMessage: runController.resendFromUserMessage,
  regenerateAssistant: runController.regenerateAssistant,
  enqueueRunInput: runController.enqueueRunInput,
  withdrawRunInput: runController.withdrawRunInput,
  pauseGoal: runController.pauseGoal,
  resumeGoal: runController.resumeGoal,
  confirmGoal: runController.confirmGoal,
}
const settings = useSettings()
const activities = useActivityStore()
const threads = useThreadStore()
const changes = useChangesStore()
const workspace = useWorkspaceStore()
const runtime = useRuntimeStore()
const input = toRef(conversationStore, 'draftText')
const composer = ref<InstanceType<typeof Composer> | null>(null)
const viewActive = ref(true)
onActivated(() => { viewActive.value = true })
onDeactivated(() => { viewActive.value = false })
watch([() => conversationStore.composerFocusRequested, composer, viewActive], ([requested, editor, active]) => {
  if (!requested || !editor || !active) return
  editor.focus()
  conversationStore.composerFocusRequested = false
}, { flush: 'post' })
const {
  attachments, pendingUploads, uploading, uploadError, dragOver, uploadSupported,
  formatSize: fmtSize, onPickFiles, onDrop: onUploadDrop, removeAttachment, clearUploadError,
  retryPending, removePending,
} = useChatUploads()
const {
  todos, visible: todoVisible, expanded: showTodos, summary: todoSummary,
  active: activeTodo, activeTitle: activeTodoTitle,
  progressPercent: todoProgressPercent, beginTurn: beginTodoTurn,
} = useThreadTodos()
const { syncFromRoute } = useThreadRouteBinding()
const {
  completionItems, completionIndex, showCompletion,
  groupedCatalog, providerConfigured, modelsLoading, modelsError, statusline, updateCompletion, applyCompletion,
  selectedModelRef, reasoningEffortOptions,
  modelSelectionNotice, dismissModelSelectionNotice,
  load: loadComposerCatalog, updateModel: updateComposerModel,
  updateReasoningEffort: updateComposerReasoningEffort,
} = useComposerCatalog(input, agentEnabled)
const {
  showSnapshots, inspectorTab, inspectorOpen, selectedSubagentCallId,
  selectedSubagent, snapshots, snapBusy, snapError, snapshotViews,
  restoreDialog, restoreModes,
  drawerTitle, refreshSnapshots, openChangesPanel,
  selectInspectorTab, openSubagentPanel, toggleSnapshots, openContextPanel,
  takeSnap, restoreSnap,
  restoreSelectedFile, restoreRunChanges, updateRestoreMode,
  confirmRestoreSnap, restoreModeDisabled, restoreConfirmDisabled,
  restoreConflictCount, restoreFileCount, restorePlanActionSummary,
  restorePreviewFiles, restorePreviewMore, restoreModeLabel, branchSnap,
  snapshotCoverageLabel, snapshotConversationLabel, snapshotPreviewFiles,
  snapshotPreviewMore, displaySnapshotPath, fileChangeClass, fileDiffLabel,
} = useWorkbenchInspector()

let workbenchDragDepth = 0
function acceptsWorkbenchDrag(event: DragEvent) {
  const types = Array.from(event.dataTransfer?.types || [])
  return (agentEnabled.value && types.includes('application/x-harnex-path')) || (uploadSupported && types.includes('Files'))
}
function onWorkbenchDragEnter(event: DragEvent) {
  if (!acceptsWorkbenchDrag(event)) return
  event.preventDefault()
  workbenchDragDepth++
  dragOver.value = true
}
function onWorkbenchDragOver(event: DragEvent) {
  if (!acceptsWorkbenchDrag(event)) return
  event.preventDefault()
  if (event.dataTransfer) event.dataTransfer.dropEffect = 'copy'
}
function onWorkbenchDragLeave(event: DragEvent) {
  if (!dragOver.value) return
  event.preventDefault()
  workbenchDragDepth = Math.max(0, workbenchDragDepth - 1)
  if (workbenchDragDepth === 0) dragOver.value = false
}
function onWorkbenchDrop(event: DragEvent) {
  workbenchDragDepth = 0
  dragOver.value = false
  const path = event.dataTransfer?.getData('application/x-harnex-path')?.trim()
  if (path && agentEnabled.value) {
    event.preventDefault()
    dragOver.value = false
    conversationStore.addWorkspaceContext(path)
    return
  }
  void onUploadDrop(event)
}

function cancelPlan() {
  chat.pendingPlanReview = null
  chat.cancel('Plan abandoned by user.', false)
}

// ---------------- goal progress ----------------
const goalStatusLabel = computed(() => {
  const g = chat.goalProgress
  if (!g) return ''
  switch (g.status) {
    case 'COMPLETE':
    case 'COMPLETED': return t('chat.goalComplete')
    case 'READY_FOR_REVIEW': return t('chat.goalReadyForReview')
    case 'WAITING_RETRY': return t('chat.goalWaitingRetry')
    case 'INTERRUPTED': return t('chat.goalInterrupted')
    case 'BLOCKED': return t('chat.goalBlocked')
    case 'BUDGET_LIMITED': return t('chat.goalBudgetLimited')
    case 'PAUSED': return t('chat.goalPaused')
    case 'CANCEL_REQUESTED': return t('chat.goalCancelling')
    case 'CANCELLED': return t('chat.goalCancelled')
    default: return t('chat.goalActive')
  }
})

const msgContainer = ref<HTMLElement | null>(null)
const transcript = ref<InstanceType<typeof Transcript> | null>(null)
const { followingLatest, showReturnLatest, showReturnStart, updateScrollAnchor, scheduleNavigation,
  scrollBottom, returnToLatest, returnToTurnStart: scrollToTurnStart } = useTranscriptScroll(msgContainer, transcript)
const locatingTurnStart = ref(false)

// ---------------- message windowing ----------------
const MESSAGE_WINDOW = 200
const displayLimit = ref(MESSAGE_WINDOW)
const viewportKey = computed(() => historyViewKey(workspace.scopeId, chat.threadId))
let restoredViewport: string | null = null
watch(() => chat.threadId, () => { restoredViewport = null })
watch(() => chat.messages.length, () => {
  if (!chat.messages.length || restoredViewport === viewportKey.value) return
  const saved = historyAnchor(viewportKey.value)
  if (saved && !saved.atEnd) {
    const index = chat.messages.findIndex(message => `item:${message._uid}` === saved.key)
    if (index >= 0) { displayLimit.value = Math.max(MESSAGE_WINDOW, chat.messages.length - index + 20); followingLatest.value = false; showReturnLatest.value = true }
  } else followingLatest.value = true
  restoredViewport = viewportKey.value
}, { flush: 'sync' })
const latestTurnIndex = computed(() => {
  for (let index = chat.messages.length - 1; index >= 0; index--) {
    const message = chat.messages[index]!
    if (message.role === 'user' && !message.runInputIds?.length) return index
  }
  return -1
})
const messageWindowStart = computed(() => Math.min(Math.max(0, latestTurnIndex.value), Math.max(0, chat.messages.length - displayLimit.value)))
const displayedMessages = computed(() => {
  const all = chat.messages
  // Retain the current turn's start even after 200 entries; VirtualTimeline still bounds the rendered DOM.
  return messageWindowStart.value ? all.slice(messageWindowStart.value) : all
})
function messageKey(m: Msg, fallback: number) {
  return m._uid || `${m.role}:${fallback}:${m.content?.length || 0}`
}

function messageHasContent(m: Msg) {
  return String(m.content || '').trim().length > 0 || String(m.reasoning || '').trim().length > 0 || Boolean(m.contentParts?.some(part => part.type !== 'TEXT'))
}

function hasInterveningStatusActivity(fromSeq: number | undefined, toSeq: number | undefined) {
  if (!fromSeq || !toSeq || toSeq <= fromSeq) return false
  return activities.items.some(activity => !activity.historical
    && (activity.kind === 'provider' || activity.kind === 'context_compression')
    && activity.seq > fromSeq && activity.seq <= toSeq)
}

const historyReady = computed(() => !chat.threadId || conversationStore.loadedThreadId === chat.threadId)
const transcriptItems = computed<TranscriptItem[]>(() => {
  const items: TranscriptItem[] = []
  let activeSpeaker: Msg['role'] | null = null
  let previousWasReasoning = false

  displayedMessages.value.forEach((m, index) => {
    if (isSystemIntroMessage(m)) return
    if (m.role === 'tool' && !m._toolEvent) return
    // Sequence numbers restart for every Run. Only the current Run interleaves with its activity store.
    const seq = m.runId && m.runId !== chat.activeRunId ? undefined : m._seq || m._toolEvent?.seq

    if (m._toolEvent) {
      if (activeSpeaker !== 'assistant') {
        items.push({
          kind: 'message',
          key: `assistant-header:${messageKey(m, index)}`,
          seq,
          message: { role: 'assistant', content: '' },
          showHeader: true,
          actionable: false,
        })
      }
      activeSpeaker = 'assistant'
      previousWasReasoning = false
      const previous = items[items.length - 1]
      if (previous?.kind === 'toolGroup'
          && !hasInterveningStatusActivity(previous.seq, m._toolEvent.seq || m._seq)
          && canMergeToolActivity({
            family: toolActivityFamily(previous.events[0]),
            events: previous.events,
          }, m._toolEvent)) {
        previous.events.push(m._toolEvent)
      } else {
        items.push({
          kind: 'toolGroup', key: `tool:${messageKey(m, index)}`,
          seq, events: [m._toolEvent],
        })
      }
      return
    }

    if (m.role === 'assistant') {
      // The archive creates an empty answer before streaming begins. It is an
      // anchor for progress, not a visible assistant bubble of its own.
      if (!messageHasContent(m) && ['PENDING', 'STREAMING'].includes(m.status || '')) return
      const isReasoning = !!m._reasoningBlock
      const showHeader = isReasoning || activeSpeaker !== 'assistant' || previousWasReasoning
      activeSpeaker = 'assistant'
      if (!messageHasContent(m) && !showHeader) return
      items.push({
        kind: 'message', key: messageKey(m, index), seq,
        message: m, showHeader, actionable: historyReady.value && !!m.messageId && !['PROGRESS', 'PENDING', 'STREAMING'].includes(m.status || ''),
      })
      previousWasReasoning = isReasoning
      return
    }

    activeSpeaker = m.role
    previousWasReasoning = false
    items.push({
      kind: 'message', key: messageKey(m, index), seq,
      message: m, showHeader: true, actionable: historyReady.value && !!m.messageId && !['PROGRESS', 'PENDING', 'STREAMING'].includes(m.status || ''),
    })
  })

  return items
})
const hiddenMessageCount = computed(() => messageWindowStart.value)
const liveToolEvents = computed(() => Object.values(chat.toolEvents).filter(ev =>
  !ev.done && !chat.messages.some(m => m._toolEvent?.id === ev.id)
))
const lastTranscriptSpeaker = computed<Msg['role'] | null>(() => {
  const last = transcriptItems.value[transcriptItems.value.length - 1]
  if (!last) return null
  return last.kind === 'toolGroup' ? 'assistant' : last.message.role
})
const liveToolEventsShowHeader = computed(() =>
  liveToolEvents.value.length > 0 && lastTranscriptSpeaker.value !== 'assistant'
)
const currentAssistantShowHeader = computed(() =>
  liveToolEvents.value.length === 0 && lastTranscriptSpeaker.value !== 'assistant'
)
async function loadOlderMessages() {
  followingLatest.value = false
  if (!hiddenMessageCount.value && conversationStore.historyCursor) await runController.loadMoreMessages()
  displayLimit.value += MESSAGE_WINDOW
}
watch(() => chat.threadId, () => { displayLimit.value = MESSAGE_WINDOW })

let externalRunTimer: ReturnType<typeof setInterval> | undefined
onMounted(async () => {
  externalRunTimer = setInterval(() => {
    if (!document.hidden) void runController.discoverExternalRun()
  }, 3000)
  await Promise.all([syncFromRoute(), loadComposerCatalog()])
})
onUnmounted(() => {
  if (externalRunTimer) clearInterval(externalRunTimer)
  // The shared run controller owns the SSE subscription across route changes.
  // Unmounting this view must not abort it.
})

function scrollToInteraction() {
  document.getElementById('pending-interactions')?.scrollIntoView({ block: 'center' })
}
async function returnToTurnStart() {
  if (locatingTurnStart.value) return
  const scope = viewportKey.value
  locatingTurnStart.value = true
  followingLatest.value = false
  try {
    // An archived turn can span several server pages. Find its actual boundary before jumping.
    while (latestTurnIndex.value < 0 && conversationStore.historyCursor && viewportKey.value === scope) {
      const cursor = conversationStore.historyCursor
      await runController.loadMoreMessages()
      if (conversationStore.historyCursor === cursor) break
    }
    await nextTick()
    if (viewportKey.value === scope) await scrollToTurnStart()
  } finally { locatingTurnStart.value = false }
}
watch(() => chat.messages.length, () => scrollBottom())
watch(() => chat.currentAssistant, () => scrollBottom())
watch(() => chat.currentThinking, () => scrollBottom())
watch(transcriptItems, () => nextTick(scheduleNavigation))
watch(() => chat.threadId, () => { const saved = historyAnchor(viewportKey.value); if (!saved || saved.atEnd) scrollBottom(true) })

// ---------------- submit / stop ----------------
const inputHistory = useComposerHistory({
  input,
  entries: computed(() => composerInputHistory(chat.messages, chat.runInputs)),
  scope: computed(() => `${workspace.epoch}:${chat.threadId || ''}`),
})
const { pending: submittingInput, canSubmit, submit } = useComposerSubmission({
  scope: computed(() => `${workspace.scopeId}:${conversationStore.executionKind}:${conversationStore.draftRevision}`),
  input, attachments, uploading,
  streaming: computed(() => chat.streaming),
  threadId: computed(() => chat.threadId),
  providerAvailable: computed(() => providerConfigured.value && workspace.ready && conversationStore.navigationReady
    && historyReady.value),
  beforeSubmit: () => { showCompletion.value = false; inputHistory.reset() },
  beginTurn: beginTodoTurn,
  send: chat.send,
  enqueue: chat.enqueueRunInput,
})
function onKey(e: KeyboardEvent) {
  // Enter confirms a candidate in macOS/Asian IMEs. It must never also choose a slash
  // completion or send the conversation message.
  if (e.isComposing || e.keyCode === 229) return
  // When the slash autocomplete is open, the arrow/Tab/Enter/Esc keys drive it.
  if (showCompletion.value && completionItems.value.length) {
    inputHistory.reset()
    if (e.key === 'ArrowDown') {
      e.preventDefault()
      completionIndex.value = (completionIndex.value + 1) % completionItems.value.length
      return
    }
    if (e.key === 'ArrowUp') {
      e.preventDefault()
      completionIndex.value = (completionIndex.value - 1 + completionItems.value.length) % completionItems.value.length
      return
    }
    if (e.key === 'Tab' || e.key === 'Enter') {
      e.preventDefault()
      applyCompletion(completionItems.value[completionIndex.value])
      return
    }
    if (e.key === 'Escape') {
      e.preventDefault()
      showCompletion.value = false
      return
    }
  }
  if (inputHistory.onKey(e)) return
  if (e.key === 'Escape' && chat.streaming) {
    e.preventDefault()
    e.stopPropagation()
    stop()
    return
  }
  const mode = composerSubmitMode(e, settings.sendShortcut, chat.streaming)
  if (mode) {
    e.preventDefault()
    if (e.repeat) return
    void submit(mode)
  }
}
function stop() { chat.cancel() }
function stopExternalRun() {
  if (window.confirm(t('chat.endExternalTaskConfirm'))) void chat.cancel()
}
function followUp() { void submit('QUEUE') }
function configureProvider() {
  sessionStorage.setItem('harnex_settings_return', route.fullPath || '/')
  void router.push('/settings?section=models&tab=language')
}
function configureNotifications() {
  sessionStorage.setItem('harnex_settings_return', route.fullPath || '/')
  void router.push('/settings?section=im')
}
function fmtTokens(n: number) {
  if (!Number.isFinite(n) || n <= 0) return '0'
  if (n < 1000) return String(n)
  if (n < 1_000_000) return (n / 1000).toFixed(1) + 'k'
  return (n / 1_000_000).toFixed(2) + 'M'
}
const workspaceLabel = computed(() => {
  if (!agentEnabled.value) return t('chat.conversationWelcome')
  if (workspace.profile === 'PERSONAL') return t('workspace.personal')
  if (workspace.active?.name) return workspace.active.name
  const raw = statusline.value?.workspace || ''
  if (!raw) return 'workspace'
  const parts = raw.replace(/\\/g, '/').split('/').filter(Boolean)
  return parts[parts.length - 1] || raw
})
watch(() => [workspace.scopeId, workspace.scopeInstanceId, workspace.ready] as const, ([, , ready]) => {
  if (ready) void loadComposerCatalog()
})
const currentThreadTitle = computed(() =>
  threads.items.find(item => item.id === chat.threadId)?.title
    || (chat.threadId ? t('sidebar.untitled') : t(agentEnabled.value ? 'sidebar.newTask' : 'sidebar.newChat')),
)
const currentThreadFullTitle = computed(() =>
  threads.items.find(item => item.id === chat.threadId)?.fullTitle || currentThreadTitle.value,
)
const runChangeSummary = computed(() => summarizeRunChanges(changes.view))
const currentRunChanges = computed(() => {
  if (chat.streaming || !chat.activeRunId || changes.runId !== chat.activeRunId) return null
  return runChangeSummary.value.count ? runChangeSummary.value : null
})
const changeCount = computed(() =>
  changes.runId === chat.activeRunId ? runChangeSummary.value.count : 0,
)
const showExecutionFacts = computed(() => Boolean(
  chat.activeRunId
  && (chat.streaming || chat.executionFacts.actualModel || chat.executionFacts.requestedModel
    || chat.executionFacts.usage.total),
))
const executionFactsPanel = ref<HTMLDetailsElement | null>(null)
function closeExecutionFacts() { if (executionFactsPanel.value) executionFactsPanel.value.open = false }
const executionModel = computed(() =>
  chat.executionFacts.actualModel || chat.executionFacts.requestedModel || '',
)
const executionContextBudget = computed(() => {
  const provider = chat.executionFacts.actualProvider || chat.executionFacts.requestedProvider
  return groupedCatalog.value.find(group => group.provider.toLowerCase() === provider?.toLowerCase())?.models
    .find(model => model.id.toLowerCase() === executionModel.value.toLowerCase())?.contextBudget
})
const executionTokens = computed(() => {
  const total = Number(chat.executionFacts.usage.total || 0)
  return total > 0 ? `${fmtTokens(total)} Token` : ''
})
const executionCacheHitRate = computed(() => {
  const prompt = Number(chat.executionFacts.usage.prompt || 0)
  const cached = Number(chat.executionFacts.usage.cachedInput || 0)
  return prompt > 0 && cached > 0 ? `${(cached / prompt * 100).toFixed(1)}%` : '—'
})
const requestedExecution = computed(() =>
  [chat.executionFacts.requestedProvider, chat.executionFacts.requestedModel].filter(Boolean).join(' / '),
)
const actualExecution = computed(() =>
  [chat.executionFacts.actualProvider, chat.executionFacts.actualModel].filter(Boolean).join(' / '),
)
const executionDiffersFromSelection = computed(() => {
  const facts = chat.executionFacts
  return Boolean(facts.actualModel && (
    facts.requestedModel && facts.requestedModel !== facts.actualModel
    || facts.requestedProvider && facts.actualProvider && facts.requestedProvider !== facts.actualProvider
  ))
})
const executionStatusLabel = computed(() =>
  chat.streaming ? t('sidebar.running') : t('chat.runFinished'),
)
function toggleWorkbenchPanel(tab: 'changes' | 'calls') {
  closeExecutionFacts()
  if (inspectorOpen.value) {
    inspectorOpen.value = false
    return
  }
  void selectInspectorTab(tab)
}
watch(
  () => [chat.activeRunId, chat.streaming] as const,
  ([runId, streaming]) => {
    if (runId && !streaming && currentRunAgent.value) void changes.load(runId)
  },
)
const explicitContexts = computed(() => {
  const paths = new Set<string>()
  const expression = /@"([^"]+)"|@([^\s]+)/g
  let match: RegExpExecArray | null
  while ((match = expression.exec(input.value)) !== null) paths.add(match[1] || match[2])
  return [...paths]
})
const conversationAttachments = computed(() => conversationAttachmentViews(chat.messages))
const invokedSkill = computed(() => {
  for (let index = chat.messages.length - 1; index >= 0; index--) {
    const message = chat.messages[index]
    if (message.role !== 'user') continue
    const match = String(message.originalInput || message.content || '').trim().match(/^\/skill\s+([^\s]+)/i)
    return match?.[1] || ''
  }
  return ''
})
const starterPrompts = computed(() => agentEnabled.value ? [
  t('chat.starterUnderstand'),
  t('chat.starterChanges'),
  t('chat.starterTests'),
] : [])
function chooseStarter(prompt: string) {
  input.value = prompt
  nextTick(() => composer.value?.focus())
}
function toggleMobileNav() {
  window.dispatchEvent(new Event('harnex-toggle-sidebar'))
}
function isSystemIntroMessage(m: Msg) {
  if (m?.role !== 'system' && m?.role !== 'user') return false
  const c = String(m?.content || '').trim()
  return c.startsWith('You are Harnex')
    || c.startsWith('[system_reminder]')
    || c.startsWith('[system-reminder]')
    || c.startsWith('[plan-mode]')
    || c.startsWith('[goal-continuation]')
}

// ---------------- per-message actions (retry / edit) ----------------
/** "Retry" emitted from a Message: resolves to the corresponding chat-store action.
 *  Assistant message  → regenerate by rewinding to the user message before it.
 *  User message       → resend verbatim (the Message component emits "retry" instead of
 *                       "edit" when the textarea was saved unchanged). */
const unarchivedImageOperations = computed(() => {
  const archived = new Set(chat.messages.flatMap(message => (message.contentParts || []).map(part => part.imageOperationId)))
  return runStore.imageOperationIds.filter(id => !archived.has(id))
})
const imageFailure = computed(() => {
  const run = runStore.currentRun
  if (!run?.terminal || !isImageFailure(run.errorCode) || runStore.imageOperationIds.length
    || chat.messages.some(message => message.runId === run.runId && message.contentParts?.some(part => part.imageOperationId))) return null
  return run
})
function handoffMessage(uid: string, selection: string) {
  if (agentEnabled.value) return
  const message = chat.messages.find(item => item._uid === uid)
  if (message) void useTaskHandoffStore().begin(message, selection)
}
async function allowAgentRerun(uid: string): Promise<boolean | null> {
  const message = chat.messages.find(item => item._uid === uid)
  const id = chat.threadId, revision = getRuntimeRevision()
  if (!message || !id) return null
  let source: Awaited<ReturnType<typeof api.run>> | null = null
  let execution: ExecutionSpec | undefined
  if (message.runId) {
    try { source = await api.run(message.runId); execution = source.executionSpec }
    catch (cause) { if (!(cause instanceof ApiHttpError) || cause.status !== 404) throw cause }
  }
  if (!execution && message.turnId) {
    const turn = await api.turn(id, message.turnId); execution = turn.executionSnapshot
    if (turn.runId) {
      try { source = await api.run(turn.runId) }
      catch (cause) { if (!(cause instanceof ApiHttpError) || cause.status !== 404) throw cause }
    }
  }
  if (id !== chat.threadId || revision !== getRuntimeRevision()) return null
  if (source?.goalId && source.conversationId === id) { chat.error = t('history.goalControls'); return null }
  if (execution?.kind !== 'AGENT') return false
  return window.confirm(t('chat.rerunAgentConfirm')) ? true : null
}
async function onRetryMessage(uid: string) {
  const revision = getRuntimeRevision(), id = chat.threadId
  try {
    const rerunAgent = await allowAgentRerun(uid)
    if (rerunAgent !== null) await chat.regenerateAssistant(uid, rerunAgent)
  } catch (cause) { if (revision === getRuntimeRevision() && id === chat.threadId) chat.error = errorMessage(cause) }
}
async function onEditMessage(uid: string, newText: string) {
  const revision = getRuntimeRevision(), id = chat.threadId
  try {
    const rerunAgent = await allowAgentRerun(uid)
    if (rerunAgent !== null) await chat.resendFromUserMessage(uid, newText, rerunAgent)
  } catch (cause) { if (revision === getRuntimeRevision() && id === chat.threadId) chat.error = errorMessage(cause) }
}

// ---------------- permission mode ----------------
async function updatePermissionMode(mode: 'ask' | 'accept-edits' | 'yolo') {
  const previous = chat.permissionMode
  const threadId = chat.threadId
  if (mode === 'yolo' && previous !== 'yolo'
      && !window.confirm(t('chat.fullAutoConfirm'))) return
  if (runStore.currentRun?.executionSpec.kind !== 'AGENT' || !runStore.currentRun.ownsConversation
      || !threadId) {
    chat.permissionMode = mode
    return
  }
  try {
    const r = await api.setMode(threadId, mode)
    chat.permissionMode = (r?.permissionMode || mode) as 'ask' | 'accept-edits' | 'yolo'
  } catch (cause: unknown) {
    chat.permissionMode = previous
    chat.error = t('chat.permissionModeUpdateFailed', { message: errorMessage(cause) })
  }
}

</script>

<template>
  <div class="chat-layout" :class="{ 'agent-workbench': agentEnabled }">
    <header class="chat-topbar">
      <button class="mobile-nav-button" :title="t('sidebar.openNavigation')" :aria-label="t('sidebar.openNavigation')"
        @click="toggleMobileNav">
        <svg width="17" height="17" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7">
          <path d="M4 6h16M4 12h16M4 18h16" />
        </svg>
      </button>
      <div class="thread-kicker">
        <div class="thread-title-line" :title="currentThreadFullTitle">{{ currentThreadTitle }}</div>
        <WorkspaceGitStatus v-if="agentEnabled" />
      </div>
      <details v-if="showExecutionFacts" ref="executionFactsPanel" class="header-execution-facts">
        <summary :title="executionStatusLabel" :aria-label="executionStatusLabel">
          <span class="run-status-light" :class="{ live: chat.streaming }" aria-hidden="true" />
          <span v-if="executionModel" class="execution-model">{{ executionModel }}</span>
          <span v-if="executionTokens" class="execution-tokens">{{ executionTokens }}</span>
        </summary>
        <div class="header-execution-popover">
          <dl>
            <dt>{{ t('chat.usedModel') }}</dt><dd>{{ actualExecution || t('sidebar.awaitingActual') }}</dd>
            <template v-if="executionDiffersFromSelection">
              <dt>{{ t('chat.originalModel') }}</dt><dd>{{ requestedExecution }}</dd>
            </template>
            <dt>{{ t('sidebar.usagePrompt') }}</dt><dd>{{ chat.executionFacts.usage.prompt }}</dd>
            <dt>{{ t('sidebar.usageCachedInput') }}</dt><dd>{{ chat.executionFacts.usage.cachedInput }}</dd>
            <dt>{{ t('sidebar.usageCacheHitRate') }}</dt><dd>{{ executionCacheHitRate }}</dd>
            <dt v-if="chat.executionFacts.usage.cacheWriteInput">{{ t('sidebar.usageCacheWrite') }}</dt>
              <dd v-if="chat.executionFacts.usage.cacheWriteInput">{{ chat.executionFacts.usage.cacheWriteInput }}</dd>
            <dt>{{ t('sidebar.usageCompletion') }}</dt><dd>{{ chat.executionFacts.usage.completion }}</dd>
            <dt v-if="chat.executionFacts.usage.reasoning">{{ t('sidebar.usageReasoning') }}</dt>
              <dd v-if="chat.executionFacts.usage.reasoning">{{ chat.executionFacts.usage.reasoning }}</dd>
            <dt>{{ t('sidebar.usageTotal') }}</dt><dd>{{ chat.executionFacts.usage.total }}</dd>
            <template v-if="executionContextBudget">
              <dt>{{ t('chat.contextWindow') }}</dt><dd>{{ executionContextBudget.windowTokens.toLocaleString() }} Token</dd>
              <dt>{{ t('chat.contextSource') }}</dt><dd>{{ t('chat.contextSource.' + executionContextBudget.source) }}</dd>
              <template v-if="currentRunAgent">
                <dt>{{ t('chat.contextSoft') }}</dt><dd>{{ executionContextBudget.softThresholdTokens.toLocaleString() }} Token</dd>
                <dt>{{ t('chat.contextHard') }}</dt><dd>{{ executionContextBudget.hardThresholdTokens.toLocaleString() }} Token</dd>
              </template>
            </template>
          </dl>
          <p>{{ chat.executionFacts.usageComplete
            ? t('sidebar.usageComplete') : t('sidebar.usagePartial') }}</p>
        </div>
      </details>
      <div class="topbar-actions">
        <TaskNotificationMenu v-if="agentEnabled" v-model="conversationStore.notificationMode"
          :context-key="(workspace.scopeId || 'project') + ':' + workspace.scopeInstanceId + ':' + chat.threadId"
          :load="() => api.imNotificationSettings()" :disabled="submittingInput"
          :locked="chat.streaming || runStore.currentRun?.ownsConversation" :current="runStore.currentRun?.notification"
          @configure="configureNotifications" @open="closeExecutionFacts" />
        <button v-if="chat.activeRunId && !showAgentControls" class="topbar-btn" :class="{ active: inspectorOpen && inspectorTab === 'calls' }"
          :aria-expanded="inspectorOpen && inspectorTab === 'calls'" @click="toggleWorkbenchPanel('calls')">{{ t('diagnostics.title') }}</button>
        <button v-if="showAgentControls" class="topbar-btn" :class="{ active: inspectorOpen }" :aria-expanded="inspectorOpen" @click="toggleWorkbenchPanel('changes')">
          <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6">
            <path d="M5 4h14v16H5zM9 8h6M9 12h6M9 16h4" />
          </svg>
          {{ t('chat.workbench') }}<span v-if="changeCount" class="topbar-count">{{ changeCount }}</span>
        </button>
      </div>
    </header>
    <div v-if="runStore.currentRun?.controlState === 'EXTERNAL' && runStore.currentRun.ownsConversation"
      class="external-conversation-notice" role="status">
      <span>{{ t('error.conversationBusyElsewhere') }}</span>
      <button v-if="runStore.currentRun.allowedActions.includes('cancel')" type="button"
        :disabled="runStore.currentRun.cancelRequested" @click="stopExternalRun">
        {{ t('chat.endExternalTask') }}
      </button>
      <button type="button" @click="chat.threadId && runController.loadThread(chat.threadId, { force: true }).catch(() => {})">{{ t('diagnostics.refresh') }}</button>
    </div>

    <div class="chat-workbench">
      <section class="conversation-pane" :class="{ 'new-conversation': !agentEnabled && !chat.threadId && !chat.messages.length && !chat.streaming }" @dragenter="onWorkbenchDragEnter"
        @dragover="onWorkbenchDragOver" @dragleave="onWorkbenchDragLeave" @drop="onWorkbenchDrop">
        <div class="chat-messages" ref="msgContainer" @scroll.passive="updateScrollAnchor">
          <Transcript ref="transcript" :items="transcriptItems" :thread-id="chat.threadId || undefined" :scope-key="viewportKey"
            :loading="conversationStore.loadingHistory"
            :activities="activities.items"
            :handoff-enabled="!agentEnabled"
            :workspace-label="workspaceLabel" :starter-prompts="starterPrompts"
            :empty-description="t(agentEnabled ? 'chat.workspaceReadyDesc' : 'chat.conversationWelcomeDesc')"
            :busy="chat.streaming || !!runStore.currentRun?.ownsConversation" :hidden-count="hiddenMessageCount"
            :has-more="!!conversationStore.historyCursor" :image-operation-ids="unarchivedImageOperations"
            :load-count="Math.min(MESSAGE_WINDOW, hiddenMessageCount)"
            :live-tools="liveToolEvents" :live-tools-show-header="liveToolEventsShowHeader"
            :current-assistant="chat.currentAssistant" :current-thinking="chat.currentThinking"
             :current-assistant-show-header="currentAssistantShowHeader" :error="chat.error"
             :incomplete-detail="runStore.incompleteDetail"
             :approvals="chat.pendingApprovals" :questions="chat.pendingQuestions"
             :plan="chat.pendingPlanReview"
             :run-changes="currentRunChanges"
             :resolve-approval="chat.resolveApproval"
             :answer-question="chat.answerQuestion" :review-plan="chat.reviewPlan"
             :cancel-plan="cancelPlan" @load-older="loadOlderMessages"
             @retry="onRetryMessage" @edit="onEditMessage" @starter="chooseStarter" @handoff="handoffMessage"
             @dismiss-error="chat.error = null" @open-subagent="openSubagentPanel"
             @open-changes="openChangesPanel" />
          <button v-if="chat.threadId && chat.error && !conversationStore.loadingHistory && conversationStore.loadedThreadId !== chat.threadId"
            class="topbar-btn" @click="runController.loadThread(chat.threadId).catch(() => {})">{{ t('chat.retryHistory') }}</button>
          <ImageFailureNotice v-if="imageFailure" :code="imageFailure.errorCode!" :run-id="imageFailure.runId" :detail="imageFailure.error" />
        </div>

        <div v-if="dragOver" class="drop-overlay">{{ t('chat.dropOverlay') }}</div>

        <div class="conversation-navigation">
          <button v-if="showReturnLatest" type="button" class="return-latest" @click="returnToLatest">
            ↓ {{ t('chat.returnLatest') }}
          </button>
          <button v-else-if="showReturnStart" type="button" class="return-latest return-turn-start"
            :disabled="locatingTurnStart" :aria-busy="locatingTurnStart" @click="returnToTurnStart">
            ↑ {{ t('chat.returnTurnStart') }}
          </button>
        </div>

        <div v-if="chat.pendingApprovals.length || chat.pendingQuestions.length || chat.pendingPlanReview"
          class="interaction-locator-rail">
          <div class="interaction-locator" role="status">
            <span>{{ t('chat.interactionWaiting') }}</span>
            <button type="button" @click="scrollToInteraction">
              {{ t('chat.returnRequest') }}
            </button>
          </div>
        </div>

    <div v-if="(agentEnabled || currentRunAgent) && (todoVisible || chat.goalProgress)" class="execution-progress-dock">
          <div class="execution-progress-inner">
            <TaskProgress v-if="todoVisible" :todos="todos" :expanded="showTodos"
              :summary="todoSummary" :progress="todoProgressPercent" :active-title="activeTodoTitle"
              :active-status="activeTodo?.status" @toggle="showTodos = !showTodos" />
            <TaskProgress v-if="chat.goalProgress" :goal="chat.goalProgress" :goal-label="goalStatusLabel"
              :token-label="fmtTokens(chat.goalProgress.tokensUsed)" @pause-goal="chat.pauseGoal"
              @resume-goal="chat.resumeGoal()" @cancel-goal="chat.cancel()" @confirm-goal="chat.confirmGoal"
              @feedback-goal="chat.resumeGoal" />
          </div>
        </div>

        <Composer ref="composer" v-model="input" :streaming="chat.streaming" :uploading="uploading"
          :agent-enabled="agentEnabled"
          :submitting="submittingInput" :can-submit="canSubmit"
          :upload-error="uploadError" :attachments="attachments" :pending-attachments="pendingUploads"
          :upload-supported="uploadSupported"
          :contexts="explicitContexts"
          :completion-items="completionItems" v-model:completion-index="completionIndex"
          :show-completion="showCompletion" :selected-model="selectedModelRef"
          :selected-reasoning-effort="settings.reasoningEffort" :reasoning-efforts="reasoningEffortOptions"
          :grouped-models="groupedCatalog" :provider-available="providerConfigured"
          :models-loading="modelsLoading" :models-error="modelsError" @retry-models="loadComposerCatalog(true)"
          :model-selection-notice="modelSelectionNotice" @dismiss-model-selection-notice="dismissModelSelectionNotice"
          :permission-mode="chat.permissionMode"
          :format-size="fmtSize" :run-inputs="chat.runInputs" :submissions="conversationStore.submissions" @input="updateCompletion" @keydown="onKey"
          @editor-pointer="inputHistory.reset"
          @completion="applyCompletion" @files="onPickFiles" @remove-attachment="removeAttachment"
          @dismiss-upload-error="clearUploadError"
          @retry-upload="retryPending" @remove-upload="removePending"
          @update:selected-model="updateComposerModel"
          @update:selected-reasoning-effort="updateComposerReasoningEffort"
          @update:permission-mode="updatePermissionMode" @open-context="openContextPanel"
          @configure-provider="configureProvider" @submit="submit()" @follow-up="followUp" @stop="stop"
          @withdraw-input="chat.withdrawRunInput" @withdraw-submission="runController.withdrawSubmission" />
      </section>

      <ReviewDrawer v-if="showAgentControls || inspectorTab === 'calls'" :open="inspectorOpen" :label="drawerTitle" :wide="inspectorTab === 'subagent'"
        @pointerdown.capture="closeExecutionFacts" @click.capture="closeExecutionFacts"
        @close="inspectorOpen = false">
        <div class="inspector-tabs">
          <button v-if="showAgentControls && (selectedSubagentCallId || selectedSubagent)" :class="{ active: inspectorTab === 'subagent' }"
            @click="inspectorTab = 'subagent'">{{ t('chat.subagentTab') }}</button>
          <button v-if="showAgentControls" :class="{ active: inspectorTab === 'changes' }" @click="selectInspectorTab('changes')">{{ t('chat.changes') }}</button>
          <button v-if="showAgentControls" :class="{ active: inspectorTab === 'context' }" @click="selectInspectorTab('context')">{{ t('chat.contextTab') }}</button>
          <button v-if="showAgentControls" :class="{ active: inspectorTab === 'snapshots' }" @click="selectInspectorTab('snapshots')">{{ t('chat.restoreTab') }}</button>
          <button :class="{ active: inspectorTab === 'calls' }" @click="selectInspectorTab('calls')">{{ t('diagnostics.title') }}</button>
          <button class="inspector-close" @click="inspectorOpen = false" :title="t('chat.panelClose')">×</button>
        </div>

        <RunDiagnosticsDrawer v-if="inspectorTab === 'calls' && inspectorOpen" :run-id="chat.activeRunId" :executing="chat.streaming" />
        <SubagentDrawer v-else-if="inspectorTab === 'subagent'" :activity="selectedSubagent"
          :call-id="selectedSubagentCallId" />

        <ChangesDrawer v-else-if="inspectorTab === 'changes'" :run-id="chat.activeRunId"
          @restore-file="restoreSelectedFile" @undo-run="restoreRunChanges" />

        <ContextSourcesDrawer v-else-if="inspectorTab === 'context'"
          :explicit-contexts="explicitContexts" :prepared-attachments="attachments"
          :conversation-attachments="conversationAttachments" :invoked-skill="invokedSkill" />

        <div v-else class="inspector-body">
          <div class="panel-header">
            <strong>{{ t('chat.snapshotsTitle') }}</strong>
            <div class="panel-actions">
              <button class="sm" :disabled="snapBusy || !chat.threadId" @click="takeSnap">{{ t('chat.snapshotManual') }}</button>
              <button class="sm icon-only" :disabled="snapBusy || !chat.threadId" @click="refreshSnapshots" :title="t('chat.snapshotRefresh')">
                <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M21 12a9 9 0 11-2.64-6.36"/><path d="M21 3v6h-6"/></svg>
              </button>
            </div>
          </div>
          <div v-if="snapError" class="panel-error">{{ snapError }}</div>
          <div v-if="!snapError && !snapshots.length" class="empty-panel">{{ t('chat.snapshotEmpty') }}</div>
          <ul v-else class="snap-list">
            <li v-for="item in snapshotViews" :key="item.snapshot.seq" class="snap-item" :class="'snap-' + item.kind" :title="item.rawTitle">
              <div class="snap-main">
                <span class="snap-kind-mark" aria-hidden="true" />
                <div class="snap-copy">
                  <div class="snap-title-row">
                    <span class="snap-label">{{ item.title }}</span>
                    <span class="snap-badge">{{ item.badge }}</span>
                  </div>
                  <div v-if="item.subtitle" class="snap-desc">{{ item.subtitle }}</div>
                  <div v-if="item.operations.length" class="snap-operations">
                    <code v-for="operation in item.operations" :key="operation" :title="operation">{{ operation }}</code>
                  </div>
                  <div class="snap-meta">
                    <span v-for="m in item.meta" :key="m" class="snap-chip">{{ m }}</span>
                  </div>
                  <div v-if="snapshotPreviewFiles(item.snapshot).length" class="snap-file-preview">
                    <div v-for="f in snapshotPreviewFiles(item.snapshot)" :key="`${f.scope || 'workspace'}:${f.path}`"
                      class="snap-file-row" :class="'change-' + fileChangeClass(f)">
                      <span class="snap-file-dot" aria-hidden="true" />
                      <code :title="displaySnapshotPath(f)">{{ displaySnapshotPath(f) }}</code>
                      <span v-if="fileDiffLabel(f)" class="snap-file-diff">{{ fileDiffLabel(f) }}</span>
                    </div>
                    <div v-if="snapshotPreviewMore(item.snapshot)" class="snap-file-more">
                      {{ t('chat.snapshotRestoreMoreFiles', { n: snapshotPreviewMore(item.snapshot) }) }}
                    </div>
                  </div>
                </div>
                <time v-if="item.time" class="snap-time">{{ item.time }}</time>
              </div>
              <div class="snap-actions">
                <button class="sm" :disabled="snapBusy" @click="restoreSnap(item.snapshot.seq)" :title="t('chat.snapshotRestoreTitle')">{{ t('chat.snapshotRestoreHere') }}</button>
                <button class="sm" :disabled="snapBusy" @click="branchSnap(item.snapshot.seq)" :title="t('chat.snapshotBranchTitle')">{{ t('chat.snapshotBranchHere') }}</button>
              </div>
            </li>
          </ul>
        </div>
      </ReviewDrawer>

      <RestoreDialog :state="restoreDialog" :busy="snapBusy" :modes="restoreModes"
        :mode-disabled="restoreModeDisabled" :confirm-disabled="restoreConfirmDisabled"
        :conflict-count="restoreConflictCount" :file-count="restoreFileCount"
        :action-summary="restorePlanActionSummary" :preview-files="restorePreviewFiles"
        :preview-more="restorePreviewMore" :display-path="displaySnapshotPath"
        :file-diff-label="fileDiffLabel" :coverage-label="snapshotCoverageLabel"
        @close="restoreDialog = null" @mode="updateRestoreMode"
        @force="value => { if (restoreDialog) restoreDialog.force = value }"
        @confirm="confirmRestoreSnap" />
    </div>
  </div>
</template>

<style src="./ChatView.css"></style>
