import { computed, onScopeDispose, ref, watch } from 'vue'
import { api, getRuntimeRevision, withReadTimeout } from '../api/client'
import type {
  RestoreDialogState,
  RestoreFilePlan,
  RestoreMode,
  RestorePlan,
} from '../components/chat/RestoreDialog.vue'
import { locale, t } from '../i18n'
import { useActivityStore } from '../stores/activity'
import { useChangesStore } from '../stores/changes'
import { useConversationStore } from '../stores/conversation'
import { useRunStore } from '../stores/run'
import { useRunController } from '../services/runController'
import { errorMessage } from '../utils/unknown'

export type InspectorTab = 'changes' | 'snapshots' | 'context' | 'subagent' | 'calls'
type SnapshotKind = 'manual' | 'run_start' | 'pre_tool' | 'pre_restore' | 'branch' | 'auto'
type SnapshotFileEntry = {
  scope?: string
  path?: string
  skipped?: boolean
  skippedReason?: string
  changeType?: string
  linesAdded?: number
  linesDeleted?: number
  diffAvailable?: boolean
  diffBinary?: boolean
  diffWhitespaceOnly?: boolean
  diffTruncated?: boolean
}
type SnapshotModel = {
  seq: number
  label?: string
  takenAt?: string
  msgCount?: number
  fileCoverage?: 'verified' | 'partial' | 'unverified' | string
  fileRestorable?: boolean
  conversationRestorable?: boolean
  warnings?: string[]
  fileEntries?: SnapshotFileEntry[]
  kind?: SnapshotKind | string
  runId?: string
  iteration?: number
  toolSummaries?: string[]
}
type RestorePlanCounts = {
  restore: number
  delete: number
  noop: number
  skip: number
  conflicts: number
}
type SnapshotView = {
  snapshot: SnapshotModel
  kind: SnapshotKind
  title: string
  subtitle: string
  badge: string
  time: string
  meta: string[]
  operations: string[]
  rawTitle: string
}

/** Review drawer navigation plus Snapshot/Restore workflows. */
export function useWorkbenchInspector() {
  const activities = useActivityStore()
  const changes = useChangesStore()
  const conversation = useConversationStore()
  const run = useRunStore()
  const runController = useRunController()
  const showSnapshots = ref(false)
  const inspectorTab = ref<InspectorTab>('changes')
  const inspectorOpen = ref(false)
  const selectedSubagentCallId = ref<string | null>(null)
  const selectedSubagentThreadId = ref<string | null>(null)
  let subagentRead: { key: string; controller: AbortController } | null = null
  let lastSubagentRead = { key: '', at: 0 }
  onScopeDispose(() => subagentRead?.controller.abort())
  const snapshots = ref<SnapshotModel[]>([])
  const snapBusy = ref(false)
  const snapError = ref<string | null>(null)
  const restoreDialog = ref<RestoreDialogState | null>(null)
  const restoreModes: RestoreMode[] = ['conversation_only', 'files_only', 'conversation_and_files']

  const selectedSubagent = computed(() => {
    const subagents = activities.items.filter(item => item.kind === 'subagent')
    if (selectedSubagentThreadId.value) {
      return subagents.find(item => item.subagentThreadId === selectedSubagentThreadId.value) || null
    }
    if (selectedSubagentCallId.value) {
      // Tool call ids may repeat in later model turns; the latest matching child owns the currently selected card.
      return [...subagents].reverse()
        .find(item => item.delegateCallId === selectedSubagentCallId.value) || null
    }
    return null
  })
  const snapshotViews = computed<SnapshotView[]>(() => snapshots.value.map(toSnapshotView))
  const drawerTitle = computed(() => ({
    changes: t('chat.changes'),
    snapshots: t('chat.snapshotsTitle'),
    context: t('chat.contextSources'),
    subagent: t('chat.subagentProcess'),
    calls: t('diagnostics.title'),
  })[inspectorTab.value])

  function reset() {
    subagentRead?.controller.abort(); subagentRead = null; lastSubagentRead = { key: '', at: 0 }
    inspectorOpen.value = false
    inspectorTab.value = 'changes'
    showSnapshots.value = false
    selectedSubagentCallId.value = null
    selectedSubagentThreadId.value = null
    restoreDialog.value = null
  }

  async function refreshSnapshots() {
    const tid = conversation.threadId
    if (!tid) {
      snapshots.value = []
      return
    }
    snapBusy.value = true
    snapError.value = null
    try {
      const result = await api.listSnapshots(tid)
      if (tid === conversation.threadId) snapshots.value = result
    } catch (cause: unknown) {
      if (tid === conversation.threadId) snapError.value = errorMessage(cause)
    } finally {
      if (tid === conversation.threadId) snapBusy.value = false
    }
  }

  function openChangesPanel() {
    inspectorTab.value = 'changes'
    inspectorOpen.value = true
    showSnapshots.value = false
  }

  async function selectInspectorTab(target: 'changes' | 'snapshots' | 'context' | 'calls') {
    inspectorTab.value = target
    inspectorOpen.value = true
    showSnapshots.value = target === 'snapshots'
    if (target === 'snapshots') await refreshSnapshots()
  }

  function openSubagentPanel(callId: string, content?: string) {
    const threadMatch = String(content || '').match(/\bthread:\s*([^) \r\n]+)/i)
    selectedSubagentCallId.value = callId || null
    selectedSubagentThreadId.value = threadMatch?.[1] || null
    inspectorTab.value = 'subagent'
    inspectorOpen.value = true
    showSnapshots.value = false
    // Reconcile with the authoritative live Run projection on open. Subagent step events are best-effort SSE frames;
    // a route refresh or bounded replay miss must not leave the drawer empty until the child terminates.
    const activeRunId = run.activeRunId
    const runtimeRevision = getRuntimeRevision()
    if (activeRunId) {
      const key = `${runtimeRevision}:${activeRunId}`
      if (subagentRead?.key === key || lastSubagentRead.key === key && Date.now() - lastSubagentRead.at < 1000) return
      subagentRead?.controller.abort()
      const request = { key, controller: new AbortController() }; subagentRead = request
      void withReadTimeout(signal => api.run(activeRunId, signal), request.controller.signal).then(view => {
        if (getRuntimeRevision() === runtimeRevision && run.activeRunId === activeRunId) {
          activities.hydrate(view.runId, view.activities, view.subagents, false, false)
          lastSubagentRead = { key, at: Date.now() }
        }
      }).catch(() => { /* the live SSE remains the fallback */ })
        .finally(() => { if (subagentRead === request) subagentRead = null })
    }
  }

  async function toggleSnapshots() {
    inspectorTab.value = inspectorTab.value === 'snapshots' ? 'changes' : 'snapshots'
    inspectorOpen.value = true
    showSnapshots.value = inspectorTab.value === 'snapshots'
    if (showSnapshots.value) await refreshSnapshots()
  }

  function openContextPanel() {
    inspectorTab.value = 'context'
    inspectorOpen.value = true
  }

  async function takeSnap() {
    if (!conversation.threadId) return
    snapBusy.value = true
    try {
      await api.takeSnapshot(conversation.threadId, 'manual')
      await refreshSnapshots()
    } finally {
      snapBusy.value = false
    }
  }

  const restoreRequests = new WeakMap<RestoreDialogState, number>()
  async function loadRestorePlan(dialog: RestoreDialogState, conversationId: string) {
    const sequence = (restoreRequests.get(dialog) || 0) + 1, revision = getRuntimeRevision()
    restoreRequests.set(dialog, sequence); dialog.loading = true; dialog.error = null
    const current = () => restoreDialog.value === dialog && conversation.threadId === conversationId
      && restoreRequests.get(dialog) === sequence && getRuntimeRevision() === revision
    try {
      const plan = await api.restorePlan(conversationId, dialog.seq, dialog.mode, dialog.paths || null)
      if (current()) dialog.plan = plan
    } catch (cause: unknown) {
      if (!current()) return
      const message = errorMessage(cause); snapError.value = message; dialog.error = message; dialog.plan = null
      if (dialog.intent === 'run') changes.error = message
    } finally { if (current()) dialog.loading = false }
  }
  async function restoreSnap(seq: number) {
    if (!conversation.threadId) return
    const snapshot = snapshots.value.find(item => item.seq === seq)
    const canRestoreConversation = snapshot?.conversationRestorable !== false
    const mode: RestoreMode = snapshot?.fileCoverage === 'verified' && snapshot?.fileRestorable
      ? (canRestoreConversation ? 'conversation_and_files' : 'files_only')
      : canRestoreConversation ? 'conversation_only' : 'files_only'
    restoreDialog.value = { seq, mode, force: false, plan: null, loading: true, intent: 'snapshot', error: null }
    await loadRestorePlan(restoreDialog.value, conversation.threadId)
  }
  async function restoreSelectedFile(seq: number, path: string) {
    if (!conversation.threadId || !path) return
    restoreDialog.value = { seq, mode: 'files_only', force: false, plan: null, loading: true, paths: [path], intent: 'file', error: null }
    await loadRestorePlan(restoreDialog.value, conversation.threadId)
  }
  async function restoreRunChanges(seq: number, paths: string[]) {
    if (!conversation.threadId) return
    const selected = [...new Set(paths.filter(Boolean))]
    if (!selected.length) return
    restoreDialog.value = { seq, mode: 'files_only', force: false, plan: null, loading: true, paths: selected, intent: 'run', error: null }
    await loadRestorePlan(restoreDialog.value, conversation.threadId)
  }
  async function updateRestoreMode(mode: RestoreMode) {
    const dialog = restoreDialog.value
    if (!dialog || !conversation.threadId) return
    dialog.mode = mode; dialog.force = false
    await loadRestorePlan(dialog, conversation.threadId)
  }

  async function confirmRestoreSnap() {
    const dialog = restoreDialog.value
    if (!dialog || !conversation.threadId) return
    const restoreRunId = changes.runId || run.activeRunId
    const conversationId = conversation.threadId
    const commandHash = JSON.stringify({ seq: dialog.seq, mode: dialog.mode, force: dialog.force, paths: dialog.paths,
      revision: dialog.plan?.historyRevision })
    if (dialog.commandHash !== commandHash) { dialog.commandId = crypto.randomUUID(); dialog.commandHash = commandHash }
    snapBusy.value = true
    dialog.error = null
    try {
      await api.restoreSnapshot(conversationId, dialog.seq, {
        commandId: dialog.commandId!,
        expectedHistoryRevision: dialog.plan?.historyRevision,
        mode: dialog.mode,
        force: dialog.force,
        paths: dialog.paths || undefined,
      })
      if (restoreDialog.value !== dialog || conversation.threadId !== conversationId) return
      await runController.loadThread(conversationId, { resetViewport: true })
      if (restoreRunId) await changes.load(restoreRunId)
      await refreshSnapshots()
      restoreDialog.value = null
    } catch (cause: unknown) {
      if (restoreDialog.value !== dialog || conversation.threadId !== conversationId) return
      const message = errorMessage(cause)
      snapError.value = message
      dialog.error = message
    } finally {
      snapBusy.value = false
    }
  }

  function restoreModeDisabled(mode: RestoreMode) {
    if (restoreDialog.value?.paths?.length) return mode !== 'files_only'
    const plan = restoreDialog.value?.plan
    if (!plan) return false
    if ((mode === 'conversation_only' || mode === 'conversation_and_files')
      && plan.conversationRestorable === false) return true
    if (mode === 'conversation_only') return false
    return !plan.canRestoreFiles
  }

  function restoreConfirmDisabled() {
    const dialog = restoreDialog.value
    if (!dialog || snapBusy.value || dialog.loading || !dialog.plan) return true
    if ((dialog.mode === 'conversation_only' || dialog.mode === 'conversation_and_files')
      && dialog.plan.conversationRestorable === false) return true
    if (restoreConflictCount(dialog.plan) > 0 && !dialog.force) return true
    return dialog.mode !== 'conversation_only' && !dialog.plan.canRestoreFiles
  }

  function restoreConflictCount(plan?: RestorePlan | null) {
    return Array.isArray(plan?.conflicts) ? plan.conflicts.length : 0
  }

  function restoreFileCount(plan?: RestorePlan | null) {
    return Array.isArray(plan?.files) ? plan.files.length : 0
  }

  function restorePlanCounts(plan?: RestorePlan | null): RestorePlanCounts {
    const counts: RestorePlanCounts = {
      restore: 0,
      delete: 0,
      noop: 0,
      skip: 0,
      conflicts: restoreConflictCount(plan),
    }
    for (const file of plan?.files || []) {
      const action = String(file.action || 'restore')
      if (action === 'delete') counts.delete++
      else if (action === 'noop') counts.noop++
      else if (action === 'skip') counts.skip++
      else counts.restore++
    }
    return counts
  }

  function restorePlanActionSummary(plan?: RestorePlan | null) {
    const counts = restorePlanCounts(plan)
    const parts = [
      counts.restore ? t('chat.snapshotRestoreActionRestore', { n: counts.restore }) : '',
      counts.delete ? t('chat.snapshotRestoreActionDelete', { n: counts.delete }) : '',
      counts.noop ? t('chat.snapshotRestoreActionNoop', { n: counts.noop }) : '',
      counts.skip ? t('chat.snapshotRestoreActionSkip', { n: counts.skip }) : '',
      counts.conflicts ? t('chat.snapshotRestoreActionConflict', { n: counts.conflicts }) : '',
    ].filter(Boolean)
    return parts.length ? parts.join(' · ') : t('chat.snapshotRestoreNoFiles')
  }

  function restorePreviewFiles(plan?: RestorePlan | null) {
    return (plan?.files || []).slice(0, 8)
  }

  function restorePreviewMore(plan?: RestorePlan | null) {
    return Math.max(0, restoreFileCount(plan) - restorePreviewFiles(plan).length)
  }

  function restoreModeLabel(mode: RestoreMode) {
    return t(`chat.snapshotRestoreMode.${mode}`)
  }

  let pendingBranch: { conversationId: string; seq: number; title?: string; commandId: string } | null = null
  async function branchSnap(seq: number) {
    if (!conversation.threadId || snapBusy.value) return
    const entered = window.prompt(t('chat.snapshotBranchPrompt'), '')
    if (entered === null) return
    const title = entered.trim() || undefined, conversationId = conversation.threadId, revision = getRuntimeRevision()
    if (!pendingBranch || pendingBranch.conversationId !== conversationId || pendingBranch.seq !== seq || pendingBranch.title !== title) {
      pendingBranch = { conversationId, seq, title, commandId: crypto.randomUUID() }
    }
    snapBusy.value = true
    try {
      const result = await api.branchSnapshot(conversationId, seq, title, pendingBranch.commandId)
      pendingBranch = null
      if (conversation.threadId !== conversationId || getRuntimeRevision() !== revision) return
      if (result?.conversationId) { await runController.loadThread(result.conversationId); showSnapshots.value = false }
    } catch (cause: unknown) {
      if (conversation.threadId === conversationId && getRuntimeRevision() === revision) snapError.value = errorMessage(cause)
    } finally { snapBusy.value = false }
  }

  function toSnapshotView(snapshot: SnapshotModel): SnapshotView {
    const kind = normalizeSnapshotKind(snapshot)
    const operations = snapshotOperations(snapshot.toolSummaries)
    const iteration = typeof snapshot.iteration === 'number' && Number.isFinite(snapshot.iteration)
      ? snapshot.iteration
      : null
    const rawLabel = String(snapshot.label || 'manual')
    const customLabel = isFriendlyCustomLabel(rawLabel) ? rawLabel : ''
    let title = t('chat.snapshotTitleAuto')
    let subtitle = t('chat.snapshotDescAuto')
    let badge = t('chat.snapshotBadgeAuto')
    if (kind === 'manual') {
      title = customLabel && customLabel !== 'manual' ? customLabel : t('chat.snapshotTitleManual')
      subtitle = t('chat.snapshotDescManual')
      badge = t('chat.snapshotBadgeManual')
    } else if (kind === 'pre_tool') {
      title = t('chat.snapshotTitlePreTool')
      subtitle = operations.length && operations.every(isBareToolName)
        ? t('chat.snapshotLegacyToolDetailsMissing')
        : ''
      badge = t('chat.snapshotBadgeTool')
    } else if (kind === 'run_start') {
      title = t('chat.snapshotTitleRunStart')
      subtitle = t('chat.snapshotDescRunStart')
      badge = t('chat.snapshotBadgeRunStart')
    } else if (kind === 'pre_restore') {
      title = t('chat.snapshotTitlePreRestore')
      subtitle = t('chat.snapshotDescPreRestore')
      badge = t('chat.snapshotBadgeRestore')
    } else if (kind === 'branch') {
      title = t('chat.snapshotTitleBranch')
      subtitle = t('chat.snapshotDescBranch')
      badge = t('chat.snapshotBadgeBranch')
    }
    return {
      snapshot,
      kind,
      title,
      subtitle,
      badge,
      time: formatSnapshotTime(snapshot.takenAt),
      meta: [
        t('chat.snapshotSeq', { seq: snapshot.seq }),
        t('chat.snapshotMessages', { n: snapshot.msgCount ?? 0 }),
        snapshotCoverageLabel(snapshot),
        snapshotConversationLabel(snapshot),
        snapshotFileCountLabel(snapshot),
        snapshotDiffCountLabel(snapshot),
        snapshotWarningCountLabel(snapshot),
        iteration != null && kind === 'pre_tool'
          ? t('chat.snapshotIteration', { n: iteration + 1 })
          : '',
      ].filter(Boolean) as string[],
      operations: kind === 'pre_tool' ? operations : [],
      rawTitle: operations.length ? operations.join('\n') : t('chat.snapshotRawLabel', { label: rawLabel }),
    }
  }

  function snapshotCoverageLabel(snapshot: Partial<SnapshotModel>) {
    if (snapshot.fileCoverage === 'verified' && snapshot.fileRestorable) {
      return t('chat.snapshotCoverageVerified')
    }
    if (snapshot.fileCoverage === 'partial' && snapshot.fileRestorable) {
      return t('chat.snapshotCoveragePartial')
    }
    return t('chat.snapshotCoverageUnverified')
  }

  function snapshotConversationLabel(snapshot: Partial<SnapshotModel>) {
    return snapshot.conversationRestorable === false
      ? t('chat.snapshotConversationUnavailable')
      : t('chat.snapshotConversationAvailable')
  }

  function snapshotFileCountLabel(snapshot: Partial<SnapshotModel>) {
    const entries = Array.isArray(snapshot.fileEntries) ? snapshot.fileEntries : []
    if (!entries.length) return ''
    return t('chat.snapshotFilesMeta', {
      n: entries.filter(entry => !entry.skipped).length,
      total: entries.length,
    })
  }

  function snapshotWarningCountLabel(snapshot: Partial<SnapshotModel>) {
    const count = Array.isArray(snapshot.warnings) ? snapshot.warnings.length : 0
    return count > 0 ? t('chat.snapshotWarningsMeta', { n: count }) : ''
  }

  function snapshotDiffCountLabel(snapshot: Partial<SnapshotModel>) {
    const stats = fileChangeStats(snapshot.fileEntries || [])
    return stats.added || stats.deleted ? `+${stats.added} -${stats.deleted}` : ''
  }

  function fileChangeStats(entries: SnapshotFileEntry[]) {
    return entries.reduce((accumulator, file) => {
      if (!file || file.skipped || file.diffBinary) return accumulator
      accumulator.added += typeof file.linesAdded === 'number' ? file.linesAdded : 0
      accumulator.deleted += typeof file.linesDeleted === 'number' ? file.linesDeleted : 0
      return accumulator
    }, { added: 0, deleted: 0 })
  }

  function snapshotPreviewFiles(snapshot: SnapshotModel) {
    return (snapshot.fileEntries || []).filter(file => file?.path).slice(0, 4)
  }

  function snapshotPreviewMore(snapshot: SnapshotModel) {
    const entries = (snapshot.fileEntries || []).filter(file => file?.path)
    return Math.max(0, entries.length - snapshotPreviewFiles(snapshot).length)
  }

  function displaySnapshotPath(file: SnapshotFileEntry | RestoreFilePlan) {
    const path = String(file?.path || '')
    return path && file?.scope === 'outside' ? `outside:${path}` : path
  }

  function fileChangeClass(file: SnapshotFileEntry | RestoreFilePlan) {
    const action = (file as RestoreFilePlan)?.action
    if ((file as SnapshotFileEntry)?.skipped || action === 'skip') return 'skipped'
    const type = String(file?.changeType || action || '')
    if (type === 'created' || type === 'delete') return 'created'
    if (type === 'deleted' || type === 'restore') return 'deleted'
    if (type === 'unchanged' || type === 'noop') return 'unchanged'
    return 'modified'
  }

  function fileDiffLabel(file: SnapshotFileEntry | RestoreFilePlan) {
    if (!file) return ''
    const entry = file as SnapshotFileEntry
    const plan = file as RestoreFilePlan
    if (entry.skipped) return entry.skippedReason || t('chat.snapshotFileSkipped')
    if (plan.action === 'skip') return t('chat.snapshotFileSkipped')
    if (file.diffBinary) return t('chat.snapshotFileBinary')
    const added = typeof file.linesAdded === 'number' ? file.linesAdded : null
    const deleted = typeof file.linesDeleted === 'number' ? file.linesDeleted : null
    if (added != null || deleted != null) return `+${added ?? 0} -${deleted ?? 0}`
    return file.changeType || ''
  }

  function normalizeSnapshotKind(snapshot: SnapshotModel): SnapshotKind {
    const kind = String(snapshot.kind || '')
    return ['manual', 'run_start', 'pre_tool', 'pre_restore', 'branch', 'auto'].includes(kind)
      ? kind as SnapshotKind
      : 'auto'
  }

  function snapshotOperations(names?: string[]) {
    return [...new Set((names || []).map(name => String(name || '').trim()).filter(Boolean))]
  }

  function isBareToolName(summary: string) {
    return /^[a-z][a-z0-9_.-]*$/i.test(summary)
  }

  function isFriendlyCustomLabel(label: string) {
    return Boolean(label && !label.includes('/') && !/^snap-\d+$/i.test(label))
  }

  function formatSnapshotTime(value?: string) {
    if (!value) return ''
    const date = new Date(value)
    if (Number.isNaN(date.getTime())) return ''
    const sameYear = date.getFullYear() === new Date().getFullYear()
    return new Intl.DateTimeFormat(locale.value, {
      year: sameYear ? undefined : 'numeric',
      month: '2-digit',
      day: '2-digit',
      hour: '2-digit',
      minute: '2-digit',
    }).format(date)
  }

  watch(() => conversation.threadId, reset)

  return {
    showSnapshots, inspectorTab, inspectorOpen, selectedSubagentCallId,
    selectedSubagentThreadId, selectedSubagent, snapshots, snapBusy, snapError,
    snapshotViews, restoreDialog, restoreModes,
    drawerTitle, reset, refreshSnapshots, openChangesPanel,
    selectInspectorTab, openSubagentPanel,
    toggleSnapshots, openContextPanel,
    takeSnap, restoreSnap, restoreSelectedFile, restoreRunChanges,
    updateRestoreMode, confirmRestoreSnap, restoreModeDisabled, restoreConfirmDisabled,
    restoreConflictCount, restoreFileCount, restorePlanActionSummary,
    restorePreviewFiles, restorePreviewMore, restoreModeLabel, branchSnap,
    snapshotCoverageLabel, snapshotConversationLabel, snapshotPreviewFiles,
    snapshotPreviewMore, displaySnapshotPath, fileChangeClass, fileDiffLabel,
  }
}
