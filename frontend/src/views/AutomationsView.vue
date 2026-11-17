<script setup lang="ts">
import { computed, onActivated, onDeactivated, onMounted, onUnmounted, ref, watch } from 'vue'
import { automationRuntimeClient, type RuntimeClient } from '../api/client'
import MarkdownBody from '../components/chat/MarkdownBody.vue'
import ImNotificationToggle from '../components/ImNotificationToggle.vue'
import { parseModelCatalog, type ModelOption } from '../domain/modelCatalog'
import { editAutomation, newAutomation, readSchedule, scheduleExpression, type AutomationJob, type SchedulePreview, type AutomationTarget, type AutomationTargetView, type AutomationAttempt, type AutomationResult } from '../domain/automations'
import { t } from '../i18n'
import { errorMessage, isRecord } from '../utils/unknown'

let api: RuntimeClient['api']
let connectionRevision = 0
const getRuntimeRevision = () => connectionRevision
const ready = ref(false), connecting = ref(false)
const targets = ref<AutomationTargetView[]>([])
const historyJob = ref<AutomationJob | null>(null), attempts = ref<AutomationAttempt[]>([])
const result = ref<AutomationResult | null>(null), resultLoading = ref(false), resultError = ref(''), selectedAttempt = ref('')
let resultSequence = 0
interface LatestRun { signature: string; loading: boolean; loaded: boolean; attempt?: AutomationAttempt; result?: AutomationResult; error?: string }
const latestRuns = ref<Record<string, LatestRun>>({})
const selectedRun = computed(() => attempts.value.find(attempt => attempt.id === selectedAttempt.value))
const unfinished = (status: string) => ['DISPATCHING', 'RUNNING', 'WAITING_RESULT'].includes(status)
const hasHistory = (job: AutomationJob) => Boolean(job.runCount || job.lastRunAt || job.lastFailureAt)
const runSignature = (job: AutomationJob) => [job.runCount, job.lastRunAt, job.lastFailureAt, job.lastStatus, job.lastRunId].join(':')
const waiting = (job: AutomationJob) => job.pending || ['WAITING_IDLE', 'WAITING_RUNTIME', 'WAITING_RESULT'].includes(job.state)
const canRetry = (job: AutomationJob) => !job.running && !waiting(job) && (Boolean(job.lastFailureReason) || ['FAILED', 'INCOMPLETE', 'INTERRUPTED'].includes(job.lastStatus))
const targetLabel = (target: AutomationTarget) => target.profile === 'PERSONAL' ? t('automations.personalSpace') : target.label
const selectedTarget = computed(() => targets.value.find(item => item.target.id === draft.value.targetId)?.target)
const personalTask = computed(() => selectedTarget.value?.profile === 'PERSONAL' || !selectedTarget.value && draft.value.targetId === 'personal')
const jobs = ref<AutomationJob[]>([]), toolsets = ref<Record<string, string[]>>({}), models = ref<ModelOption[]>([])
const loading = ref(false), saving = ref(false), editorOpen = ref(false), busy = ref<string[]>([])
const advancedOpen = ref(false)
const notificationGuide = ref(false)
const loadError = ref(''), actionError = ref(''), catalogError = ref(''), message = ref(''), deleting = ref<string | null>(null)
const draft = ref(newAutomation()), schedule = ref(readSchedule(draft.value.cron))
const editorBaseline = ref<ReturnType<typeof newAutomation> | null>(null)
const pendingDiscard = ref<{ kind: 'close' } | { kind: 'open'; job?: AutomationJob } | null>(null)
let editorVersion = 0, disposed = false
const preview = ref<SchedulePreview | null>(null), previewError = ref(''), previewLoading = ref(false)
const expression = computed(() => scheduleExpression(schedule.value))
const editorDirty = computed(() => editorOpen.value && JSON.stringify({ ...draft.value, cron: expression.value }) !== JSON.stringify(editorBaseline.value))
const enabledCount = computed(() => jobs.value.filter(job => !job.disabled).length)
const storageWarning = computed(() => jobs.value.find(job => job.persistenceError)?.persistenceError)
const timeZones = [...new Set([newAutomation().timeZone, 'Asia/Shanghai', 'Asia/Singapore', 'UTC', 'America/New_York', 'Europe/London'])]
const toolsetNames = computed(() => [...new Set([...Object.keys(toolsets.value), ...draft.value.toolsets])])
const selectedModel = computed({
  get: () => draft.value.model ? `${draft.value.provider}:${draft.value.model}` : '',
  set: (value: string) => {
    const model = models.value.find(item => `${item.provider}:${item.id}` === value)
    draft.value.provider = model?.provider || ''; draft.value.model = model?.id || ''
  },
})
const unknownModel = computed(() => draft.value.model && !models.value.some(item => `${item.provider}:${item.id}` === selectedModel.value))
let active = false, generation = 0, readSequence = 0, previewSequence = 0
let pollTimer: ReturnType<typeof setTimeout> | undefined, previewTimer: ReturnType<typeof setTimeout> | undefined
const current = (version: number, revision: number) => active && ready.value && generation === version && revision === getRuntimeRevision()

function pollLater() {
  clearTimeout(pollTimer)
  if (active && ready.value) pollTimer = setTimeout(() => { void refresh() }, 5000)
}
async function refresh() {
  if (!active || !ready.value) return
  const version = generation, revision = getRuntimeRevision(), request = ++readSequence
  loading.value = true
  try {
    const [listed, environments] = await Promise.all([api.cronList(), api.cronTargets()])
    if (!current(version, revision) || request !== readSequence) return
    const openHistory = historyJob.value
    jobs.value = listed; targets.value = environments; loadError.value = ''
    for (const id of Object.keys(latestRuns.value)) if (!listed.some(job => job.id === id)) delete latestRuns.value[id]
    const updated = listed.find(job => job.id === openHistory?.id)
    if (openHistory && !updated) closeHistory()
    await Promise.all(listed.filter(hasHistory).map(job => loadLatest(job)))
    if (current(version, revision) && updated && historyJob.value?.id === updated.id) {
      historyJob.value = updated
      if (runSignature(updated) !== runSignature(openHistory!) || attempts.value.some(attempt => unfinished(attempt.status)) || !result.value && !resultError.value) await showHistory(updated, true)
    }
  } catch (cause) {
    if (current(version, revision) && request === readSequence) loadError.value = errorMessage(cause)
  } finally {
    if (current(version, revision) && request === readSequence) { loading.value = false; pollLater() }
  }
}
async function loadCatalogs() {
  const version = generation, revision = getRuntimeRevision()
  const results = await Promise.allSettled([api.cronToolsets(), api.models()])
  if (!current(version, revision)) return
  const [tools, catalog] = results
  catalogError.value = ''
  if (tools.status === 'fulfilled') toolsets.value = tools.value
  else catalogError.value = errorMessage(tools.reason)
  if (catalog.status === 'fulfilled') {
    try { models.value = parseModelCatalog(catalog.value).models }
    catch (cause) { catalogError.value = errorMessage(cause) }
  } else catalogError.value = errorMessage(catalog.reason)
}
async function connect() {
  if (connecting.value) return
  const version = generation
  connecting.value = true; loadError.value = ''
  try {
    const connection = await automationRuntimeClient()
    if (!active || generation !== version) return
    api = connection.api; connectionRevision++; ready.value = true
    await Promise.all([refresh(), loadCatalogs()])
  } catch (cause) { if (active && generation === version) { ready.value = false; loadError.value = errorMessage(cause) } }
  finally {
    connecting.value = false
    if (active && !ready.value && generation !== version) void connect()
  }
}
function activate() {
  if (active) return
  active = true
  if (ready.value) { void refresh(); void loadCatalogs(); if (editorOpen.value) queuePreview() }
  else void connect()
}
function deactivate() {
  active = false; generation++; readSequence++; previewSequence++; resultSequence++
  clearTimeout(pollTimer); clearTimeout(previewTimer)
  loading.value = false; previewLoading.value = false
  Object.values(latestRuns.value).forEach(run => { run.loading = false })
  resultLoading.value = false
}
onMounted(activate); onActivated(activate); onDeactivated(deactivate)
onMounted(() => { if (typeof window !== 'undefined') window.addEventListener('beforeunload', beforeUnload) })
onUnmounted(() => {
  disposed = true; deactivate()
  if (typeof window !== 'undefined') window.removeEventListener('beforeunload', beforeUnload)
})

function beforeUnload(event: BeforeUnloadEvent) {
  if (!editorDirty.value) return
  event.preventDefault(); event.returnValue = ''
}

function openEditor(job?: AutomationJob) {
  if (saving.value) return
  if (editorOpen.value && draft.value.id === job?.id) return
  if (editorDirty.value) { pendingDiscard.value = { kind: 'open', job }; return }
  editorVersion++
  draft.value = job ? editAutomation(job) : newAutomation()
  if (!job && !targets.value.some(item => item.target.id === 'personal')) {
    draft.value.targetId = targets.value.find(item => item.online)?.target.id || 'personal'
    if (draft.value.targetId !== 'personal') draft.value.toolsets = ['read']
  }
  schedule.value = readSchedule(draft.value.cron)
  editorBaseline.value = { ...draft.value, cron: expression.value, toolsets: [...draft.value.toolsets] }
  pendingDiscard.value = null
  editorOpen.value = true; advancedOpen.value = false; notificationGuide.value = false; actionError.value = ''; message.value = ''; deleting.value = null
  queuePreview()
}
function closeEditor() {
  if (saving.value) return
  if (editorDirty.value) { pendingDiscard.value = { kind: 'close' }; return }
  editorOpen.value = false
}
function discardEditor() {
  const action = pendingDiscard.value
  pendingDiscard.value = null; editorOpen.value = false; editorVersion++
  if (action?.kind === 'open') openEditor(action.job)
}
function queuePreview() {
  clearTimeout(previewTimer)
  const request = ++previewSequence
  preview.value = null; previewError.value = ''; previewLoading.value = editorOpen.value
  if (!editorOpen.value || !active || !ready.value) return
  const version = generation, revision = getRuntimeRevision(), cron = expression.value, zone = draft.value.timeZone
  previewTimer = setTimeout(async () => {
    try {
      const result = await api.cronPreview(cron, zone)
      if (current(version, revision) && request === previewSequence) preview.value = result
    } catch (cause) {
      if (current(version, revision) && request === previewSequence) previewError.value = errorMessage(cause)
    } finally { if (current(version, revision) && request === previewSequence) previewLoading.value = false }
  }, 300)
}
watch([expression, () => draft.value.timeZone, editorOpen], queuePreview)
async function save() {
  if (saving.value || pendingDiscard.value || !draft.value.prompt.trim() || !preview.value || !ready.value) return
  const version = editorVersion, revision = getRuntimeRevision()
  const valid = () => !disposed && version === editorVersion && revision === getRuntimeRevision()
  saving.value = true; actionError.value = ''; message.value = ''
  try {
    await api.cronAdd({ ...draft.value, name: draft.value.name.trim(), prompt: draft.value.prompt.trim(), cron: expression.value,
      toolsets: [...draft.value.toolsets] })
    if (!valid()) return
    editorOpen.value = false; message.value = t('automations.saved'); if (active) await refresh()
  } catch (cause) { if (valid()) actionError.value = errorMessage(cause) }
  finally { if (!disposed && version === editorVersion) saving.value = false }
}
async function act(job: AutomationJob, action: 'run' | 'toggle' | 'delete' | 'cancel') {
  if (!ready.value || busy.value.includes(job.id)) return
  if (action === 'run' && (job.running || waiting(job))) return
  const revision = getRuntimeRevision()
  const valid = () => !disposed && revision === getRuntimeRevision()
  busy.value = [...busy.value, job.id]; actionError.value = ''; message.value = ''
  try {
    if (action === 'run') await api.cronRun(job.id)
    else if (action === 'toggle') await api.cronEnabled(job.id, job.disabled)
    else if (action === 'cancel') await api.cronCancel(job.id)
    else await api.cronDel(job.id)
    if (!valid()) return
    deleting.value = null
    if (action === 'run') message.value = t('automations.runAccepted')
    if (action === 'delete' && draft.value.id === job.id) editorOpen.value = false
    if (action === 'toggle' && draft.value.id === job.id) {
      draft.value.disabled = !job.disabled
      if (editorBaseline.value) editorBaseline.value.disabled = !job.disabled
    }
    if (active) await refresh()
  } catch (cause) { if (valid()) actionError.value = errorMessage(cause) }
  finally { if (!disposed) busy.value = busy.value.filter(id => id !== job.id) }
}
function toggleToolset(name: string) {
  draft.value.toolsets = draft.value.toolsets.includes(name) ? draft.value.toolsets.filter(value => value !== name) : [...draft.value.toolsets, name]
}
function formatTime(value: number, timeZone: string) {
  if (!value) return t('automations.neverRun')
  return new Intl.DateTimeFormat(undefined, { timeZone, month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' }).format(value)
}
function title(job: AutomationJob) { return job.name || job.prompt.split('\n')[0].slice(0, 120) }
function status(job: AutomationJob) {
  if (job.state === 'WAITING_RUNTIME') return t(job.target.profile === 'PERSONAL' ? 'automations.waitingPersonal' : job.target.connectionId ? 'automations.waitingRemote' : 'automations.waitingRuntime')
  if (job.state === 'WAITING_IDLE') return t('automations.waitingIdle')
  if (job.state === 'WAITING_RESULT') return t('automations.waitingResult')
  if (job.running) return t('automations.running')
  if (job.pending) return t('automations.queued')
  return t(job.disabled ? 'automations.paused' : 'automations.scheduled')
}
function runStatus(value?: string, job?: AutomationJob) {
  const key: Record<string, string> = { COMPLETED: 'completed', FAILED: 'failed', INCOMPLETE: 'incomplete', CANCELLED: 'cancelled', INTERRUPTED: 'interrupted' }
  if (value === 'WAITING_RESULT') return t('automations.waitingResult')
  if (value && unfinished(value)) return t('automations.running')
  return value && key[value] ? t(`automations.${key[value]}`)
    : job?.lastFailureReason ? t('automations.failed') : job?.lastRunAt ? t('automations.completed') : t('automations.neverRun')
}
function waitReason(job: AutomationJob) {
  if (job.state === 'WAITING_RESULT') return t('automations.waitingResultHint')
  if (job.state === 'WAITING_RUNTIME') return t(job.target.profile === 'PERSONAL' ? 'automations.waitingPersonalHint' : job.target.connectionId ? 'automations.waitingRemoteHint' : 'automations.waitingRuntimeHint')
  if (job.state === 'WAITING_IDLE') return t('automations.waitingIdleHint')
  return ''
}
function duration(value: AutomationResult) {
  if (!value.startedAt || !value.finishedAt) return '—'
  const seconds = Math.max(0, Math.round((value.finishedAt - value.startedAt) / 1000))
  return seconds < 60 ? `${seconds}s` : `${Math.floor(seconds / 60)}m ${seconds % 60}s`
}
function excerpt(value: string) {
  const text = value.trim()
  return text.length > 600 ? text.slice(0, 600) + '…' : text
}
async function readResult(attempt: AutomationAttempt) {
  if (!attempt.runId) return undefined
  try { return await api.cronResult(attempt.id) }
  catch (cause) {
    if (isRecord(cause) && cause.status === 404) return undefined
    throw cause
  }
}
async function loadLatest(job: AutomationJob) {
  const signature = runSignature(job), previous = latestRuns.value[job.id]
  if (previous?.signature === signature && (previous.loading || previous.loaded)) return
  const version = generation, revision = getRuntimeRevision()
  latestRuns.value[job.id] = { signature, loading: true, loaded: false }
  try {
    const history = await api.cronHistory(job.id)
    const attempt = history.find(item => !unfinished(item.status))
    const loaded = attempt ? await readResult(attempt) : undefined
    if (current(version, revision) && jobs.value.some(item => item.id === job.id && runSignature(item) === signature)) {
      latestRuns.value[job.id] = { signature, loading: false, loaded: true, attempt, result: loaded }
    }
  } catch (cause) {
    if (current(version, revision) && latestRuns.value[job.id]?.signature === signature) {
      latestRuns.value[job.id] = { signature, loading: false, loaded: false, error: errorMessage(cause) }
    }
  }
}
function changeTarget() {
  draft.value.provider = ''; draft.value.model = ''; draft.value.allowMutations = false
  draft.value.notificationMode = 'OFF'; notificationGuide.value = false
  draft.value.toolsets = draft.value.targetId === 'personal' ? [] : ['read']
}
function closeHistory() { historyJob.value = null; resultSequence++; resultLoading.value = false }
async function showHistory(job: AutomationJob, preserve = false) {
  const selection = preserve ? selectedAttempt.value : ''
  historyJob.value = job
  if (!preserve) { attempts.value = []; result.value = null; selectedAttempt.value = ''; resultError.value = '' }
  const version = generation, sequence = ++resultSequence
  resultLoading.value = true
  try {
    const items = await api.cronHistory(job.id)
    if (!active || version !== generation || sequence !== resultSequence) return
    attempts.value = items
    const attempt = items.find(item => item.id === selection) || items[0]
    if (attempt) await showResult(attempt)
  } catch (cause) { if (active && version === generation && sequence === resultSequence) resultError.value = errorMessage(cause) }
  finally { if (sequence === resultSequence) resultLoading.value = false }
}
async function showResult(attempt: AutomationAttempt) {
  const sequence = ++resultSequence, version = generation
  selectedAttempt.value = attempt.id; result.value = null; resultError.value = ''; resultLoading.value = true
  try {
    if (unfinished(attempt.status)) return
    const cached = latestRuns.value[attempt.jobId]
    const loaded = cached?.attempt?.id === attempt.id && cached.loaded ? cached.result : await readResult(attempt)
    if (active && version === generation && sequence === resultSequence) {
      result.value = loaded || null
      if (!loaded) resultError.value = attempt.error || t('automations.noSavedResult')
    }
  } catch (cause) {
    if (active && sequence === resultSequence) resultError.value = attempt.error || errorMessage(cause)
  } finally { if (sequence === resultSequence) resultLoading.value = false }
}
function resultCost(value: AutomationResult) {
  if (!value.usage?.costComplete) return t('automations.unknownCost')
  return Object.entries(value.usage.knownAmounts || {}).map(([currency, amount]) => `${Number(amount).toFixed(4)} ${currency}`).join(' · ') || t('automations.unknownCost')
}
</script>

<template>
  <div class="automations-page">
    <header class="automation-head">
      <div><span class="eyebrow">{{ t('automations.scope') }}</span><h1>{{ t('automations.title') }}</h1><p>{{ t('automations.desc') }}</p></div>
      <button class="primary" :disabled="!ready || !targets.length || saving" @click="openEditor()">＋ {{ t('automations.add') }}</button>
    </header>
    <details class="automation-scope-note"><summary>{{ t('automations.whenRuns') }}</summary><p>{{ t('automations.scopeLogic') }}</p></details>
    <div v-if="!ready" class="automation-empty"><strong>{{ connecting ? t('automations.connecting') : t('automations.unavailable') }}</strong><p v-if="loadError" role="alert">{{ loadError }}</p><button v-if="!connecting" @click="connect">{{ t('automations.reconnect') }}</button></div>
    <template v-else>
      <div class="automation-summary">
        <span>{{ t('automations.jobCount', { n: jobs.length }) }} · {{ t('automations.enabledCount', { n: enabledCount }) }}</span>
        <button :disabled="loading" @click="loadError ? connect() : refresh()">{{ loading ? t('automations.refreshing') : t('automations.refresh') }}</button>
      </div>
      <p v-if="loadError || actionError || storageWarning" class="automation-error" role="alert">{{ actionError || loadError || storageWarning }}</p>
      <p v-if="message" class="automation-message" role="status">{{ message }}</p>
      <form v-if="editorOpen" class="automation-editor" @submit.prevent="save">
        <header><strong>{{ draft.id ? t('automations.edit') : t('automations.add') }} <span v-if="editorDirty" class="draft-badge">{{ t('settings.unsaved') }}</span></strong><button type="button" :disabled="saving || !!pendingDiscard" :aria-label="t('common.cancel')" @click="closeEditor">×</button></header>
        <div v-if="pendingDiscard" class="draft-discard" role="alertdialog" :aria-label="t('automations.discardDraft')">
          <p>{{ t('automations.discardDraft') }}</p><div><button type="button" @click="pendingDiscard = null">{{ t('automations.keepEditing') }}</button><button type="button" class="danger" @click="discardEditor">{{ t('settings.discard') }}</button></div>
        </div>
        <p v-else-if="editorDirty" class="editor-hint draft-note" role="status">{{ t('automations.draftKept') }}</p>
        <fieldset :disabled="saving || !!pendingDiscard">
          <label>{{ t('automations.goal') }}<textarea v-model="draft.prompt" rows="4" required :placeholder="t('automations.goalPlaceholder')" /></label>
          <div class="editor-grid">
            <label>{{ t('automations.schedule') }}<select v-model="schedule.frequency"><option v-for="frequency in ['daily', 'weekdays', 'weekly', 'hourly', 'custom']" :key="frequency" :value="frequency">{{ t(`automations.${frequency}`) }}</option></select></label>
            <label v-if="schedule.frequency !== 'hourly' && schedule.frequency !== 'custom'">{{ t('automations.atTime') }}<input v-model="schedule.time" type="time" required /></label>
            <label v-if="schedule.frequency === 'hourly'">{{ t('automations.atMinute') }}<input v-model.number="schedule.minute" type="number" min="0" max="59" required /></label>
            <label v-if="schedule.frequency === 'weekly'">{{ t('automations.weekday') }}<select v-model="schedule.weekday"><option v-for="day in ['1', '2', '3', '4', '5', '6', '0']" :key="day" :value="day">{{ t(`automations.day${day}`) }}</option></select></label>
          </div>
          <label v-if="schedule.frequency === 'custom'">{{ t('automations.customCron') }}<input v-model="schedule.custom" placeholder="0 0 9 * * *" required /><small>{{ t('automations.cronHelp') }}</small></label>
          <div class="schedule-preview" aria-live="polite">
            <span v-if="previewLoading">{{ t('automations.checkingSchedule') }}</span>
            <span v-else-if="previewError" class="failure">{{ previewError }}</span>
            <template v-else-if="preview"><span>{{ t('automations.nextRun') }} <strong>{{ formatTime(preview.nextRuns[0], preview.timeZone) }}</strong> <small>({{ preview.timeZone }})</small></span></template>
          </div>
          <label>{{ t('automations.executionTarget') }}<select v-model="draft.targetId" @change="changeTarget"><option v-for="item in targets" :key="item.target.id" :value="item.target.id">{{ targetLabel(item.target) }}{{ item.online ? '' : ' · ' + t('automations.offline') }}</option><option v-if="!targets.some(item => item.target.id === draft.targetId)" :value="draft.targetId">{{ t('automations.targetRemoved') }}</option></select></label>
          <p class="target-hint">{{ personalTask ? t('automations.personalHint') : t('automations.projectHint') }}</p>
          <ImNotificationToggle v-if="!personalTask" v-model="draft.notificationMode" :context-key="draft.targetId"
            :load="() => api.cronNotificationSettings(draft.targetId)" failures-only :disabled="saving" @configure="notificationGuide = true" />
          <p v-if="notificationGuide && !personalTask" class="editor-hint">{{ t('automations.notificationGuide') }}</p>
          <details class="automation-options" :open="advancedOpen" @toggle="advancedOpen = ($event.target as HTMLDetailsElement).open">
            <summary>{{ t('automations.executionOptions') }}</summary>
            <label>{{ t('automations.name') }}<input v-model="draft.name" maxlength="120" :placeholder="t('automations.namePlaceholder')" /></label>
            <label>{{ t('automations.timeZone') }}<input v-model.trim="draft.timeZone" list="automation-zones" required /><datalist id="automation-zones"><option v-for="zone in timeZones" :key="zone" :value="zone" /></datalist></label>
            <p v-if="catalogError" class="failure">{{ catalogError }}</p>
            <label v-if="!selectedTarget?.connectionId">{{ t('automations.model') }}<select v-model="selectedModel"><option value="">{{ t('automations.defaultModel') }}</option><option v-if="unknownModel" :value="selectedModel">{{ draft.provider }} / {{ draft.model }}</option><option v-for="model in models" :key="model.ref" :value="`${model.provider}:${model.id}`">{{ model.provider }} / {{ model.label }}</option></select></label>
            <div v-if="selectedTarget?.connectionId" class="editor-grid"><label>{{ t('automations.remoteProvider') }}<input v-model.trim="draft.provider" :placeholder="t('automations.remoteDefault')" /></label><label>{{ t('automations.model') }}<input v-model.trim="draft.model" :placeholder="t('automations.remoteDefault')" /></label></div>
            <div v-if="!personalTask" class="toolsets"><span>{{ t('automations.toolset') }}</span><div><button v-for="name in toolsetNames" :key="name" type="button" :aria-pressed="draft.toolsets.includes(name)" @click="toggleToolset(name)">{{ ['read', 'inspect', 'act'].includes(name) ? t(`automations.tools_${name}`) : name }}</button></div><small v-if="!draft.toolsets.length">{{ t('automations.inheritTools') }}</small></div>
            <label v-if="!personalTask" class="check-label"><input v-model="draft.allowMutations" type="checkbox" /><span>{{ t('automations.allowMutations') }}<small>{{ t('automations.allowMutationsDesc') }}</small></span></label>
            <label class="check-label"><input v-model="draft.disabled" type="checkbox" /><span>{{ t('automations.startPaused') }}</span></label>
          </details>
          <p v-if="draft.id" class="editor-hint">{{ t('automations.editHint') }}</p>
        </fieldset>
        <footer><button type="button" :disabled="saving || !!pendingDiscard" @click="closeEditor">{{ t('common.cancel') }}</button><button class="primary" :disabled="saving || !!pendingDiscard || !draft.prompt.trim() || !preview || previewLoading">{{ saving ? t('automations.saving') : t('automations.save') }}</button></footer>
      </form>
      <div v-if="!jobs.length && !loading && !loadError && !editorOpen" class="automation-empty"><strong>{{ t('automations.empty') }}</strong><p>{{ t('automations.emptyDesc') }}</p><button @click="openEditor()">{{ t('automations.add') }}</button></div>
      <div class="job-list">
        <article v-for="job in jobs" :key="job.id" class="job-card" :data-job-id="job.id">
          <header><div class="job-title"><h2>{{ title(job) }}</h2></div><span class="automation-state" :class="{ paused: job.disabled && !job.running && !waiting(job), running: job.running || waiting(job) }">{{ status(job) }}</span></header>
          <p class="job-target">{{ targetLabel(job.target) }}<span v-if="!job.targetOnline"> · {{ t('automations.offline') }}</span><span v-else-if="job.target.connectionId"> · {{ t('automations.online') }}</span></p>
          <p v-if="waitReason(job)" class="wait-reason" role="status">{{ waitReason(job) }}</p>
          <div class="job-times"><span>{{ t('automations.nextRun') }} <strong>{{ job.running ? t('automations.afterCurrentRun') : waiting(job) ? t('automations.whenReady') : job.disabled ? t('automations.paused') : job.nextRunAt ? formatTime(job.nextRunAt, job.timeZone) : t('automations.noNextRun') }}</strong></span><small>{{ job.timeZone }}</small></div>
          <section v-if="hasHistory(job)" class="latest-result" :aria-label="t('automations.latestResult')">
            <header><strong>{{ t('automations.latestResult') }}</strong><span :class="{ failure: !!job.lastFailureReason }">{{ runStatus(job.lastStatus, job) }}</span><time>{{ formatTime(Math.max(job.lastRunAt || 0, job.lastFailureAt || 0), job.timeZone) }}</time></header>
            <p v-if="job.lastFailureReason" class="job-failure">{{ job.lastFailureReason }}</p>
            <p v-if="latestRuns[job.id]?.loading" class="result-hint">{{ t('automations.loadingResult') }}</p>
            <template v-else-if="latestRuns[job.id]?.result">
              <div class="result-preview"><MarkdownBody :source="excerpt(latestRuns[job.id].result!.output || t('automations.noTextResult'))" /></div>
              <div class="result-metrics"><span>{{ t('automations.duration') }} {{ duration(latestRuns[job.id].result!) }}</span><span>{{ t('automations.cost') }} {{ resultCost(latestRuns[job.id].result!) }}</span><span v-if="latestRuns[job.id].result!.changes?.length">{{ t('automations.changedFiles') }} {{ latestRuns[job.id].result!.changes!.length }}</span></div>
            </template>
            <p v-else-if="latestRuns[job.id]?.error" class="result-hint">{{ t('automations.resultLoadFailed') }}</p>
            <p v-else-if="!job.lastFailureReason" class="result-hint">{{ t('automations.noSavedResult') }}</p>
          </section>
          <p v-else-if="!job.running && !waiting(job)" class="result-hint">{{ t('automations.firstRunHint') }}</p>
          <footer class="job-actions">
            <button :disabled="job.running || waiting(job) || busy.includes(job.id) || saving" @click="act(job, 'run')">{{ canRetry(job) ? t('automations.retry') : t('automations.runNow') }}</button>
            <button :disabled="!hasHistory(job) && !job.running" :aria-expanded="historyJob?.id === job.id" @click="historyJob?.id === job.id ? closeHistory() : showHistory(job)">{{ historyJob?.id === job.id ? t('automations.hideResults') : t('automations.viewResults') }}</button>
            <button v-if="job.running || waiting(job)" :disabled="busy.includes(job.id)" @click="act(job, 'cancel')">{{ t('automations.cancelExecution') }}</button>
            <button :disabled="busy.includes(job.id) || saving" @click="openEditor(job)">{{ t('automations.edit') }}</button>
            <button :disabled="busy.includes(job.id) || saving" @click="act(job, 'toggle')">{{ job.disabled ? t('automations.resume') : t('automations.pause') }}</button>
            <button class="delete-button" :disabled="busy.includes(job.id) || saving" @click="deleting = deleting === job.id ? null : job.id">{{ t('automations.delete') }}</button>
          </footer>
          <div v-if="deleting === job.id" class="delete-confirm" role="group" :aria-label="t('automations.deleteConfirm')"><span>{{ job.running ? t('automations.deleteRunning') : t('automations.deleteConfirm') }}</span><button :disabled="busy.includes(job.id)" @click="deleting = null">{{ t('common.cancel') }}</button><button class="danger" :disabled="busy.includes(job.id)" @click="act(job, 'delete')">{{ t('automations.confirmDelete') }}</button></div>
          <section v-if="historyJob?.id === job.id" class="automation-results">
            <header><strong>{{ t('automations.results') }}</strong><button @click="closeHistory">{{ t('common.close') }}</button></header>
            <div class="history-layout"><nav :aria-label="t('automations.results')"><button v-for="attempt in attempts" :key="attempt.id" :class="{ selected: selectedAttempt === attempt.id }" :aria-pressed="selectedAttempt === attempt.id" @click="showResult(attempt)">{{ formatTime(attempt.startedAt, job.timeZone) }} · {{ runStatus(attempt.status) }}</button></nav>
              <div class="result-detail">
                <p v-if="selectedRun" class="result-location">{{ t('automations.executionTarget') }} · {{ targetLabel(selectedRun.target) }}</p>
                <p v-if="resultLoading" class="result-hint">{{ t('automations.loadingResult') }}</p>
                <p v-else-if="selectedRun && unfinished(selectedRun.status)" role="status">{{ t(selectedRun.status === 'WAITING_RESULT' ? 'automations.waitingResultHint' : 'automations.resultPending') }}</p>
                <p v-else-if="resultError" class="failure" role="status">{{ resultError }}</p>
                <template v-else-if="result"><p v-if="result.error" class="failure">{{ result.error }}</p><MarkdownBody :source="result.output || t('automations.noTextResult')" /><details v-if="result.changes?.length" class="result-changes"><summary>{{ t('automations.changedFiles') }} ({{ result.changes.length }})</summary><p v-for="change in result.changes" :key="change.path"><code>{{ change.path }}</code> <span>+{{ change.added }} −{{ change.removed }}</span></p></details><div class="result-metrics"><span>{{ t('automations.duration') }} {{ duration(result) }}</span><span>{{ t('automations.cost') }} {{ resultCost(result) }}</span></div></template>
                <p v-else>{{ t('automations.noRuns') }}</p>
              </div>
            </div>
          </section>
        </article>
      </div>
    </template>
  </div>
</template>

<style scoped>
.automations-page { width:min(900px,calc(100% - 48px)); margin:0 auto; overflow-y:auto; padding:36px 0 72px; }
.automation-head { display:flex; align-items:center; justify-content:space-between; gap:20px; }
.eyebrow { color:var(--text-faint); font-size:12px; }
h1 { margin:5px 0 0; font-size:23px; letter-spacing:-.025em; }
.automation-head p { margin:8px 0 0; color:var(--text-muted); font-size:13px; }
button { min-height:32px; padding:6px 11px; border:1px solid var(--border); border-radius:7px; background:var(--surface-1); color:var(--text-secondary); font-size:12px; }
button:hover:not(:disabled) { border-color:var(--accent); color:var(--text-primary); }
button:disabled { opacity:.45; cursor:not-allowed; }
button.primary { background:var(--accent); color:var(--accent-contrast); border-color:var(--accent); white-space:nowrap; }
button:focus-visible,input:focus-visible,select:focus-visible,textarea:focus-visible,summary:focus-visible { outline:2px solid var(--accent); outline-offset:2px; }
.automation-scope-note { margin:14px 0 0; color:var(--text-muted); font-size:12px; line-height:1.7; }
.automation-scope-note summary { cursor:pointer; width:fit-content; }
.automation-scope-note p { margin:8px 0 0; max-width:70ch; }
.automation-summary { display:flex; justify-content:space-between; align-items:center; gap:10px; margin:16px 0; color:var(--text-muted); font-size:12px; }
.automation-error,.automation-message { margin:12px 0; padding:10px 12px; border-radius:8px; font-size:12px; overflow-wrap:anywhere; }
.automation-error { border:1px solid var(--danger); color:var(--danger); background:var(--danger-soft); }
.automation-message { color:var(--text-secondary); background:var(--accent-soft); }
.automation-editor { display:grid; gap:16px; margin-bottom:18px; border:1px solid var(--border-strong); border-radius:12px; background:var(--surface-1); padding:18px; }
.automation-editor header,.automation-editor footer { display:flex; align-items:center; justify-content:space-between; gap:10px; }
.automation-editor header strong { font-size:14px; }
.draft-badge { display:inline-block; margin-left:8px; padding:2px 6px; border-radius:5px; font-size:11px; font-weight:400; color:var(--text-secondary); background:var(--accent-soft); }
.draft-note { margin:0; }
.draft-discard { border:1px solid var(--border); border-radius:8px; padding:12px; color:var(--text-secondary); background:var(--bg); font-size:12px; line-height:1.6; }
.draft-discard p { margin:0 0 10px; }
.draft-discard > div { display:flex; justify-content:flex-end; gap:8px; }
.automation-editor fieldset { border:0; padding:0; margin:0; display:grid; gap:14px; min-width:0; }
.automation-editor label { display:grid; gap:6px; color:var(--text-secondary); font-size:12px; }
.automation-editor input,.automation-editor textarea,.automation-editor select { width:100%; min-width:0; border:1px solid var(--border); border-radius:7px; padding:9px 10px; background:var(--bg); color:var(--text-primary); font:inherit; }
.automation-editor textarea { resize:vertical; min-height:84px; line-height:1.6; }
.editor-grid { display:grid; grid-template-columns:repeat(2,minmax(0,1fr)); gap:12px; }
.schedule-preview { border-left:2px solid var(--accent); padding:7px 11px; color:var(--text-muted); font-size:12px; line-height:1.6; }
.schedule-preview strong { color:var(--text-secondary); font-weight:500; }
.automation-options { border-top:1px solid var(--border); padding-top:12px; }
.automation-options summary { cursor:pointer; font-size:12px; color:var(--text-secondary); }
.automation-options[open] > :not(summary) { margin-top:12px; }
.check-label { display:flex!important; align-items:flex-start; gap:8px!important; line-height:1.5; }
.check-label input { width:15px; height:15px; flex-shrink:0; margin-top:2px; accent-color:var(--accent); }
.check-label small,.editor-hint { display:block; margin-top:4px; color:var(--text-muted); font-size:12px; line-height:1.6; }
.automation-editor footer { justify-content:flex-end; }
.toolsets { display:grid; gap:8px; color:var(--text-secondary); font-size:12px; }
.toolsets > div { display:flex; flex-wrap:wrap; gap:7px; }
.toolsets button[aria-pressed=true] { background:var(--accent-soft); border-color:var(--accent); color:var(--accent-hover); }
.automation-empty { min-height:220px; display:grid; place-content:center; justify-items:center; gap:10px; color:var(--text-muted); text-align:center; font-size:13px; }
.automation-empty strong { color:var(--text-secondary); }
.job-list { display:grid; gap:12px; }
.job-card { border:1px solid var(--border); border-radius:12px; background:var(--surface-1); padding:16px; min-width:0; }
.job-card > header { display:flex; align-items:flex-start; justify-content:space-between; gap:12px; }
.job-title { display:flex; flex-wrap:wrap; align-items:center; gap:8px; min-width:0; }
.job-title h2 { margin:0; font-size:14px; font-weight:600; line-height:1.6; overflow-wrap:anywhere; }
.automation-state { flex-shrink:0; border-radius:5px; padding:3px 7px; color:var(--text-secondary); background:var(--surface-2); font-size:12px; }
.automation-state.running { color:var(--accent-hover); }
.automation-state.paused { color:var(--text-faint); }
.wait-reason { margin:10px 0 0; padding:8px 10px; border-radius:6px; background:var(--accent-soft); color:var(--text-secondary); font-size:12px; line-height:1.7; }
.job-times { display:flex; flex-wrap:wrap; align-items:baseline; gap:6px 16px; margin-top:10px; color:var(--text-muted); font-size:12px; }
.job-times strong { color:var(--text-secondary); font-weight:500; }
.latest-result { margin-top:14px; padding:12px; border:1px solid var(--border); border-radius:8px; background:var(--bg); }
.latest-result > header { display:flex; flex-wrap:wrap; align-items:baseline; gap:6px 10px; color:var(--text-muted); font-size:11px; }
.latest-result > header strong { font-size:12px; color:var(--text-secondary); font-weight:500; }
.latest-result time { margin-left:auto; }
.result-preview { margin-top:10px; max-height:115px; overflow:hidden; font-size:13px; line-height:1.7; overflow-wrap:anywhere; }
.result-hint { color:var(--text-muted); font-size:12px; line-height:1.7; margin:12px 0 0; }
.failure,.job-failure { color:var(--danger); }
.job-failure { font-size:12px; line-height:1.6; margin:10px 0 0; overflow-wrap:anywhere; }
.job-actions { display:flex; flex-wrap:wrap; gap:7px; margin-top:14px; padding-top:12px; border-top:1px solid var(--border); }
.job-actions .delete-button { margin-left:auto; color:var(--text-faint); }
.delete-confirm { display:flex; flex-wrap:wrap; align-items:center; gap:8px; margin-top:12px; padding:10px; border-radius:7px; background:var(--danger-soft); font-size:12px; }
.delete-confirm span { flex:1 1 220px; color:var(--text-secondary); }
.danger { color:var(--danger); border-color:var(--danger); }
@media(max-width:600px) { .automations-page{width:calc(100% - 28px);padding-top:22px}.automation-head{align-items:flex-start}.automation-head p{line-height:1.6}.editor-grid{grid-template-columns:1fr}.automation-editor{padding:14px}.job-card{padding:13px}.job-card>header{flex-wrap:wrap}.job-actions .delete-button{margin-left:0}.schedule-preview span{overflow-wrap:anywhere} }
.target-hint,.job-target { color:var(--text-muted); font-size:12px; line-height:1.6; margin:0; }
.job-target { margin-top:7px; }
.automation-results { margin-top:16px; padding-top:14px; border-top:1px solid var(--border); }
.automation-results > header { display:flex; justify-content:space-between; align-items:center; gap:12px; font-size:13px; }
.history-layout { display:grid; grid-template-columns:200px minmax(0,1fr); gap:18px; margin-top:14px; }
.history-layout nav { display:flex; flex-direction:column; gap:7px; max-height:340px; overflow:auto; }
.history-layout nav button { text-align:left; }.history-layout nav button.selected { border-color:var(--accent); background:var(--accent-soft); }
.result-detail { min-width:0; max-height:500px; overflow:auto; font-size:13px; line-height:1.7; }
.result-metrics { display:flex; gap:6px 16px; flex-wrap:wrap; color:var(--text-muted); font-size:11px; margin-top:12px; }
.result-location { margin:0 0 12px; color:var(--text-muted); font-size:12px; overflow-wrap:anywhere; }
.result-changes { margin-top:14px; font-size:12px; }
.result-changes summary { cursor:pointer; }
.result-changes p { display:flex; flex-wrap:wrap; justify-content:space-between; gap:8px; overflow-wrap:anywhere; }
.result-changes code { min-width:0; overflow-wrap:anywhere; }
@media(max-width:600px) { .history-layout { grid-template-columns:1fr; }.history-layout nav { max-height:130px; } }
</style>
