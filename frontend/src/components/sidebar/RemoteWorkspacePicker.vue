<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { remoteConnections, supervisor, type RemoteAttempt, type RemoteConnectionProfile, type SupervisorWorkspace, type RemoteServiceInfo, type RemoteServiceRef } from '../../api/client'
import { t } from '../../i18n'
import { errorMessage, isRecord } from '../../utils/unknown'
import WorkspaceFailure from '../WorkspaceFailure.vue'

const props = defineProps<{ projects: SupervisorWorkspace[]; initialProject?: SupervisorWorkspace | null; opening?: boolean; externalError?: string; needsStop?: boolean }>()
const emit = defineEmits<{ back: []; clearError: []; select: [project: SupervisorWorkspace, stopAcknowledged: boolean]; busy: [value: boolean]; removed: [id: string]; forgotten: [] }>()
const missingInitial = ref(false)
const profiles = ref<RemoteConnectionProfile[]>([])
const aliases = ref<string[]>([])
const origin = ref({ machine: '', configPath: '' })
const stage = ref<'hosts' | 'form' | 'folders' | 'services'>('hosts')
const services = ref<RemoteServiceInfo[]>([])
const serviceDirectory = ref('~/.harnex')
const servicePort = ref<number | string | null>(null)
const servicesLoaded = ref(false)
const blank = (): RemoteConnectionProfile => ({ id: globalThis.crypto?.randomUUID?.() || `ssh-${Date.now()}-${Math.random().toString(36).slice(2, 10)}`, name: '', host: '', java: 'java' })
const draft = ref<RemoteConnectionProfile>(blank())
const user = ref('')
const active = ref<RemoteConnectionProfile | null>(null)
const root = ref('~')
const home = ref('~')
const directories = ref<Array<{ name: string; path: string; hidden: boolean }>>([])
const parent = ref<string | null>(null)
const showHidden = ref(false)
const truncated = ref(false)
const listingLoaded = ref(false)
const busy = ref(false)
const cancelling = ref(false)
const loading = ref(true)
const error = ref('')
const failureCode = ref('')
const attempt = ref<RemoteAttempt | null>(null)
const answer = ref('')
const answerInput = ref<HTMLInputElement | null>(null)
const authPanel = ref<HTMLFormElement | null>(null)
const connectingHost = ref('')
const submittingAnswer = ref(false)
const removing = ref('')
let attemptId = ''
let generation = 0
let disposed = false
let lifecyclePoll = 0
const discovered = computed(() => aliases.value.filter(alias => !profiles.value.some(profile => profile.host === alias)))
const recent = computed(() => props.projects.filter(project => project.remoteProfile === active.value?.id && !project.remoteService))
const needsStop = computed(() => Boolean(props.needsStop) && !props.projects.some(project => project.isActive && project.remoteProfile === active.value?.id && project.root === root.value))
const visibleDirectories = computed(() => directories.value.filter(directory => showHidden.value || !directory.hidden))
const crumbs = computed(() => [{ name: '/', path: '/' }, ...root.value.split('/').filter(Boolean).map((name, i, parts) =>
  ({ name, path: `/${parts.slice(0, i + 1).join('/')}` }))])

function setBusy(value: boolean) { busy.value = value; emit('busy', value) }
function connectionError(cause: unknown): string {
  if (isRecord(cause) && cause.code === 'ssh_component_outdated') return t('remote.componentOutdated')
  if (isRecord(cause) && cause.code === 'ssh_component_missing') return t('remote.componentMissing')
  return errorMessage(cause)
}
async function load() {
  const [saved, discovered] = await Promise.all([remoteConnections.list(), remoteConnections.discover()])
  if (disposed) return
  profiles.value = saved; aliases.value = discovered.aliases; origin.value = discovered
}
function edit(profile?: RemoteConnectionProfile) {
  error.value = ''; removing.value = ''; user.value = ''
  draft.value = profile ? { ...profile } : blank(); stage.value = 'form'
}
async function pickKey() {
  try { const path = await remoteConnections.pickIdentity(); if (path) draft.value.identityFile = path }
  catch (cause) { error.value = connectionError(cause) }
}
function listing(result: Record<string, unknown>) {
  if (typeof result.root !== 'string' || !result.root.startsWith('/')) throw new Error(t('remote.invalidDirectory'))
  root.value = result.root
  if (typeof result.home === 'string') home.value = result.home
  parent.value = typeof result.parentPath === 'string' ? result.parentPath : null
  directories.value = Array.isArray(result.directories) ? result.directories.filter(isRecord)
    .filter(item => typeof item.name === 'string' && typeof item.path === 'string')
    .map(item => ({ name: String(item.name), path: String(item.path), hidden: item.hidden === true })) : []
  truncated.value = result.truncated === true
  listingLoaded.value = true
}
async function perform(profile: RemoteConnectionProfile, operation: 'connect' | 'directories' | 'prepare' | 'services' | 'service', path = '~', openAfter = false, stopAcknowledged = false, service?: RemoteServiceRef) {
  if (busy.value || props.opening) return
  const token = ++generation
  connectingHost.value = profile.host
  setBusy(true); error.value = ''; failureCode.value = ''; answer.value = ''; attempt.value = null
  try {
    const started = service ? await remoteConnections.start(profile, operation, path, service) : await remoteConnections.start(profile, operation, path)
    if (token !== generation || disposed) { await remoteConnections.cancel(started.id); return }
    attemptId = started.id
    while (token === generation && !disposed) {
      const status = await remoteConnections.status(started.id)
      if (token !== generation || disposed) return
      const newChallenge = status.challenge?.id !== attempt.value?.challenge?.id
      attempt.value = status
      if (newChallenge) {
        answer.value = ''; submittingAnswer.value = false; await nextTick()
        if (status.challenge) { (answerInput.value || authPanel.value)?.focus(); authPanel.value?.scrollIntoView({block:'nearest'}) }
      }
      if (status.state === 'failed') { failureCode.value = status.errorCode || ''; throw new Error(status.error || t('remote.failed')) }
      if (status.state === 'cancelled') return
      if (status.state === 'succeeded') {
        attemptId = ''
        if (operation === 'services') {
          services.value = Array.isArray(status.result?.services) ? status.result.services.filter(isRecord)
            .filter(item => typeof item.catalogId === 'string' && typeof item.root === 'string' && isRecord(item.service)) as unknown as RemoteServiceInfo[] : []
          if (typeof status.result?.userDataDir === 'string') serviceDirectory.value = status.result.userDataDir
          servicesLoaded.value = true; stage.value = 'services'
        } else if (operation !== 'prepare' && operation !== 'service') {
          listing(status.result || {}); active.value = { ...profile }; stage.value = 'folders'
          if (operation === 'connect') await load()
          if (openAfter && !needsStop.value && token === generation && !disposed) { setBusy(false); open(root.value); return }
        } else {
          if (operation === 'prepare' && props.initialProject && props.initialProject.root === path && status.result?.workspaceId !== props.initialProject.id) throw new Error(t('remote.identityChanged'))
          const project = await remoteConnections.registerWorkspace(profile.id, path, started.id)
          if (token === generation && !disposed) emit('select', project, stopAcknowledged)
        }
        return
      }
      await new Promise(resolve => setTimeout(resolve, 200))
    }
  } catch (cause) {
    if (token === generation && !disposed) error.value = connectionError(cause)
  } finally {
    if (token === generation && !disposed) { setBusy(false); attemptId = ''; attempt.value = null; answer.value = '' }
  }
}
async function connectDraft() {
  if (busy.value) return
  try {
    let profile = { ...draft.value, host: draft.value.host.trim() }
    if (/^ssh\s/.test(profile.host)) profile = { ...profile, ...await remoteConnections.parse(profile.host) }
    if (user.value.trim()) {
      if (profile.host.includes('@')) throw new Error(t('remote.duplicateUser'))
      profile.host = `${user.value.trim()}@${profile.host}`
    }
    const port: unknown = profile.port
    profile.port = port == null || port === '' ? null : Number(port)
    if (profile.port !== null && (!Number.isInteger(profile.port) || profile.port < 1 || profile.port > 65535)) throw new Error(`${t('remote.port')}: 1–65535`)
    profile.name = profile.name.trim() || profile.host
    profile.identityFile = profile.identityFile?.trim() || null
    draft.value = profile; user.value = ''
    await connect(profile)
  } catch (cause) { error.value = connectionError(cause) }
}
function connect(profile: RemoteConnectionProfile) {
  // A listing belongs to exactly one SSH transport snapshot, never to a local path.
  active.value = null; root.value = '~'; home.value = '~'; parent.value = null; directories.value = []; listingLoaded.value = false
  return perform(profile, 'connect')
}
function connectAlias(host: string) { void connect({ ...blank(), name: host, host }) }
function browse(path = root.value) { if (active.value) void perform(active.value, 'directories', path) }
function discoverServices() {
  if (!active.value) return
  stage.value = 'services'; servicesLoaded.value = false
  void perform(active.value, 'services', serviceDirectory.value)
}
function serviceNeedsStop(item: RemoteServiceInfo) {
  return Boolean(props.needsStop) && !props.projects.some(project => project.isActive
    && (project.runtimeScopeId || project.id) === item.workspaceId && project.instanceId === item.instanceId)
}
function selectService(item: RemoteServiceInfo) {
  if (!active.value) return
  const port = servicePort.value === '' || servicePort.value === null ? null : Number(servicePort.value)
  if (port !== null && (!Number.isInteger(port) || port < 1 || port > 65535)) { error.value = t('remote.invalidServicePort'); return }
  const reference = {...item.service, port}
  void perform(active.value, 'service', item.root, false, serviceNeedsStop(item), reference)
}
function open(path = root.value) {
  if (!active.value || busy.value || props.opening) return
  const known = props.projects.find(project => project.remoteProfile === active.value?.id && project.root === path && !project.remoteService)
  // The host validates the saved remote workspace identity while connecting. Do not prepare/register it twice.
  if (known) emit('select', known, needsStop.value)
  else void perform(active.value, 'prepare', path, false, needsStop.value)
}
function openRecent(path: string) {
  const current = props.projects.some(project => project.isActive && project.remoteProfile === active.value?.id && project.root === path)
  if (props.needsStop && !current) browse(path)
  else open(path)
}
async function reply(value = answer.value) {
  if (!attemptId || !attempt.value?.challenge || submittingAnswer.value) return
  submittingAnswer.value = true
  const secret = value; answer.value = ''
  try { await remoteConnections.reply(attemptId, attempt.value.challenge.id, secret) }
  catch (cause) { error.value = connectionError(cause); submittingAnswer.value = false }
}
async function cancel() {
  if (cancelling.value) return
  cancelling.value = true
  const id = attemptId
  ++generation; attemptId = ''; attempt.value = null; answer.value = ''; submittingAnswer.value = false
  try { if (id) await remoteConnections.cancel(id) }
  catch (cause) { error.value = connectionError(cause); cancelling.value = false }
  finally { setBusy(false); if (!props.opening) cancelling.value = false }
}
async function back() {
  if (props.opening) { if (attemptId) await cancel(); return }
  if (busy.value) { await cancel(); return }
  error.value = ''
  if (stage.value === 'hosts') emit('back')
  else {
    stage.value = 'hosts'
    active.value = null
    emit('clearError')
  }
}
async function remove(id: string) {
  if (removing.value !== id) { removing.value = id; return }
  try { await remoteConnections.remove(id); removing.value = ''; emit('removed', id); await load() }
  catch (cause) { error.value = connectionError(cause) }
}

function reconfigure() {
  const project = props.initialProject
  if (!project?.remoteProfile) return
  const address = /^([^:\s]+)(?::(\d+))?$/.exec(project.remoteHost || '')
  draft.value = {...blank(), id:project.remoteProfile, name:project.remoteHost || '', host:address?.[1] || '', port:address?.[2] ? Number(address[2]) : null}
  missingInitial.value = false; stage.value = 'form'; error.value = ''
}

async function forgetInitial() {
  if (!props.initialProject || props.initialProject.isActive) return
  try { await supervisor.remove(props.initialProject.id); emit('forgotten'); emit('back') }
  catch (cause) { error.value = connectionError(cause) }
}
defineExpose({ cancel })
watch(() => props.opening, async opening => {
  const token = ++lifecyclePoll
  if (!opening) { cancelling.value = false; if (!busy.value) { attempt.value = null; attemptId = ''; answer.value = '' }; return }
  while (props.opening && token === lifecyclePoll && !disposed) {
    try {
      const status = await remoteConnections.pending()
      if (!props.opening || token !== lifecyclePoll || disposed) return
      const newChallenge = status?.challenge?.id !== attempt.value?.challenge?.id
      attempt.value = status; attemptId = status?.id || ''
      if (status?.host) connectingHost.value = status.host
      if (newChallenge) {
        answer.value = ''; submittingAnswer.value = false; await nextTick()
        if (status?.challenge) { (answerInput.value || authPanel.value)?.focus(); authPanel.value?.scrollIntoView({block:'nearest'}) }
      }
    } catch (cause) { if (!disposed) error.value = connectionError(cause) }
    await new Promise(resolve => setTimeout(resolve, 200))
  }
})
onMounted(() => { void load().then(async () => {
  if (props.initialProject && !disposed) {
    const profile = profiles.value.find(profile => profile.id === props.initialProject?.remoteProfile)
    if (!profile) { missingInitial.value = true; return }
    active.value = { ...profile }; root.value = props.initialProject.root; connectingHost.value = profile.host; stage.value = props.initialProject.remoteService ? 'services' : 'folders'
    if (props.initialProject.remoteService) { serviceDirectory.value = props.initialProject.remoteService.userDataDir; servicePort.value = props.initialProject.remoteService.port || null }
    if (props.initialProject.remoteService) { if (!props.needsStop || props.initialProject.isActive) emit('select', props.initialProject, false) }
    else if (!needsStop.value) emit('select', props.initialProject, false)
    else await perform(profile, 'connect', props.initialProject.root, true)
  }
}).catch(cause => { error.value = connectionError(cause) }).finally(() => { loading.value = false }) })
onBeforeUnmount(() => { disposed = true; ++generation; if (attemptId) void remoteConnections.cancel(attemptId).catch(() => {}); emit('busy', false) })
</script>

<template>
  <div class="remote-picker" :aria-busy="loading || busy || opening" @keydown.esc.stop.prevent="back">
    <header v-if="!opening && !attempt?.challenge" class="picker-head">
      <div class="picker-context">
        <span class="scope">SSH</span>
        <div class="picker-context-copy">
          <strong :title="active?.name">{{ active?.name || t(stage === 'form' ? 'remote.connectionSettings' : 'remote.hostsTitle') }}</strong>
          <small v-if="active && active.host !== active.name" :title="active.host">{{ active.host }}</small>
        </div>
      </div>
      <button v-if="!busy" type="button" class="picker-back" @click="back">{{ t(stage === 'hosts' ? 'remote.back' : 'remote.hostsBack') }}</button>
    </header>
    <fieldset v-if="!opening">
    <p class="origin">{{ remoteConnections.native ? t('remote.desktopOrigin') : t('remote.webOrigin') }}
      <span v-if="origin.machine" class="machine">{{ origin.machine }}</span></p>
    <template v-if="!attempt?.challenge">
    <template v-if="stage === 'hosts'">
      <div v-if="missingInitial" class="missing-connection" role="status">
        <strong>{{ props.initialProject?.name }}</strong><p>{{ t('remote.missingConnection') }}</p>
        <div class="actions"><button @click="reconfigure">{{ t('remote.reconfigure') }}</button>
          <button v-if="!props.initialProject?.isActive" @click="forgetInitial">{{ t('remote.forgetRecent') }}</button></div>
      </div>
      <div v-if="profiles.length" class="section-label">{{ t('remote.connection') }}</div>
      <div v-for="profile in profiles" :key="profile.id" class="host-row">
        <button class="host-main" :disabled="busy" @click="connect(profile)"><span class="host-icon" aria-hidden="true">⇄</span>
          <span><strong>{{ profile.name }}</strong><small>{{ profile.host }}{{ profile.port ? `:${profile.port}` : '' }}</small></span><span class="arrow" aria-hidden="true">›</span></button>
        <button class="quiet" :disabled="busy" :aria-label="`${t('remote.edit')} ${profile.name}`" @click="edit(profile)">{{ t('remote.edit') }}</button>
        <button class="quiet" :disabled="busy" @click="remove(profile.id)">{{ t(removing === profile.id ? 'remote.confirmRemove' : 'remote.remove') }}</button>
      </div>
      <p v-if="removing" class="remove-hint">{{ t('remote.removeHint') }}</p>
      <template v-if="discovered.length"><div class="section-label">{{ t('remote.sshConfig') }}<small :title="origin.configPath">{{ origin.configPath }}</small></div>
        <button v-for="alias in discovered" :key="alias" class="alias-row" :disabled="busy" @click="connectAlias(alias)"><strong>{{ alias }}</strong><span>›</span></button></template>
      <p v-if="!loading && !profiles.length && !discovered.length">{{ t('remote.emptyHosts') }}</p>
      <p v-if="loading" role="status">{{ t('remote.phase.discovery') }}</p>
      <button class="new-host" :disabled="busy || loading" @click="edit()">+ {{ t('remote.newConnection') }}</button>
    </template>
    <form v-else-if="stage === 'form'" @submit.prevent="connectDraft">
      <label>{{ t('remote.host') }}<input v-model="draft.host" autofocus placeholder="devbox / user@hostname / ssh user@host" :disabled="busy" autocomplete="off" spellcheck="false" required /></label>
      <label>{{ t('remote.user') }}<input v-model="user" :placeholder="t('remote.fromSshConfig')" :disabled="busy" autocomplete="username" spellcheck="false" /></label>
      <p>{{ t('remote.authHint') }}</p>
      <details><summary>{{ t('remote.advanced') }}</summary>
        <label>{{ t('remote.name') }}<input v-model="draft.name" :placeholder="t('remote.autoName')" :disabled="busy" /></label>
        <label>{{ t('remote.port') }}<input v-model.number="draft.port" type="number" min="1" max="65535" placeholder="SSH config / 22" :disabled="busy" /></label>
        <label>{{ t('remote.identity') }}<span class="input-row"><input v-model="draft.identityFile" placeholder="~/.ssh/id_ed25519" :disabled="busy" spellcheck="false" />
          <button v-if="remoteConnections.native" type="button" :disabled="busy" @click="pickKey">{{ t('remote.chooseKey') }}</button></span></label>
        <p>{{ t('remote.advancedHint') }}</p>
      </details>
      <div class="actions">
        <button class="primary" :disabled="busy || !draft.host.trim()">{{ t('remote.connect') }}</button></div>
    </form>
    <template v-else-if="active && stage === 'services'">
      <h3>{{ t('remote.existingServices') }}</h3>
      <p>{{ t('remote.existingServicesHint') }}</p>
      <form class="path-row" @submit.prevent="discoverServices"><label class="sr-only" for="ssh-service-data">{{ t('remote.serviceDataDir') }}</label>
        <input id="ssh-service-data" v-model="serviceDirectory" placeholder="~/.harnex" :disabled="busy" /><button :disabled="busy || !serviceDirectory.trim()">{{ t('remote.findServices') }}</button></form>
      <p>{{ t('remote.serviceDataDirHint') }}</p>
      <details><summary>{{ t('remote.advanced') }}</summary><label>{{ t('remote.servicePort') }}<input v-model.number="servicePort" type="number" min="1" max="65535" :disabled="busy" :placeholder="t('remote.servicePortDefault')" /></label></details>
      <div class="host-recents"><button v-for="item in services" :key="item.catalogId" :disabled="busy" @click="selectService(item)">
        <span>{{ item.name }} · {{ item.runtimeVersion }}<small>{{ item.root }} · {{ item.domainId }}</small></span>
        <strong>{{ t(serviceNeedsStop(item) ? 'workspace.stopAndOpen' : 'remote.connectService') }}</strong>
      </button></div>
      <p v-if="servicesLoaded && !services.length">{{ t('remote.noServices') }}</p>
      <p v-if="props.needsStop" class="stop-warning">{{ t('workspace.switchWarning') }}</p>
      <div class="actions"><button type="button" :disabled="busy" @click="browse(home)">{{ t('remote.browseProjects') }}</button>
        <button v-if="initialProject?.remoteService && !services.length" :disabled="busy" @click="emit('select', initialProject, Boolean(props.needsStop))">{{ t(props.needsStop ? 'workspace.stopAndOpen' : 'remote.reconnect') }}</button></div>
    </template>
    <template v-else-if="active">
      <button type="button" :disabled="busy" @click="discoverServices">{{ t('remote.existingServices') }}</button>
      <p>{{ t('remote.remoteDirectoryHint') }}</p>
      <form class="path-row" @submit.prevent="browse()"><label class="sr-only" for="ssh-remote-path">{{ t('remote.directory') }}</label>
        <input id="ssh-remote-path" v-model="root" :disabled="busy" spellcheck="false" /><button :disabled="busy || !root.trim()">{{ t('remote.go') }}</button></form>
      <nav class="breadcrumbs" :aria-label="t('remote.directory')"><button v-for="crumb in crumbs" :key="crumb.path" :disabled="busy" @click="browse(crumb.path)">{{ crumb.name }}</button></nav>
      <div class="directory-tools"><button :disabled="busy" @click="browse(home)">{{ t('remote.home') }}</button><button :disabled="busy || !parent" @click="browse(parent!)">{{ t('remote.parent') }}</button>
        <label><input v-model="showHidden" type="checkbox" />{{ t('remote.showHidden') }}</label></div>
      <div class="directories"><button v-for="directory in visibleDirectories" :key="directory.path" :disabled="busy" @click="browse(directory.path)"><span aria-hidden="true">▱</span>{{ directory.name }}<span class="arrow" aria-hidden="true">›</span></button>
        <p v-if="!visibleDirectories.length && !busy && !opening" class="empty-directory">{{ t(listingLoaded ? 'remote.emptyDirectory' : 'remote.browseOnDemand') }}</p></div>
      <p v-if="truncated">{{ t('remote.truncated') }}</p>
      <div v-if="recent.length" class="host-recents"><div class="section-label">{{ t('remote.recentOnHost') }}</div>
        <button v-for="project in recent" :key="project.id" :disabled="busy" @click="openRecent(project.root)"><span>{{ project.name }}</span><small>{{ project.root }}</small></button></div>
      <p class="prepare-hint">{{ t('remote.prepareHint') }}</p>
      <p v-if="needsStop" class="stop-warning" role="note">{{ t('workspace.switchWarning') }}</p>
      <div class="actions">
        <button class="primary" :disabled="busy || !root.trim()" @click="open()">{{ t(needsStop ? 'workspace.stopAndOpen' : 'remote.openFolder') }}</button></div>
    </template>
    </template>
    </fieldset>
    <template v-if="opening && !attempt?.challenge">
      <div class="connected-host"><span class="scope">SSH</span><strong>{{ active?.name || connectingHost }}</strong><small>{{ active?.host }}</small></div>
      <p class="opening-target">{{ root }}</p>
    </template>
    <div v-if="(busy || opening) && !attempt?.challenge" class="progress" role="status" aria-live="polite"><span class="spinner" aria-hidden="true" />{{ t(`remote.phase.${cancelling ? 'cancelling' : attempt?.phase || (opening ? 'starting' : 'connecting')}`) }}
      <button type="button" :disabled="cancelling || opening && !attemptId" @click="cancel">{{ t('workspace.cancel') }}</button></div>
    <form v-if="attempt?.challenge" ref="authPanel" tabindex="-1" class="authentication" @submit.prevent="reply()">
      <div class="connected-host"><span class="scope">SSH</span><strong>{{ connectingHost }}</strong></div>
      <strong>{{ t(attempt.challenge.kind === 'hostKey' ? 'remote.trustTitle' : 'remote.authTitle') }}</strong>
      <p>{{ t(attempt.challenge.kind === 'hostKey' ? 'remote.trustHint' : 'remote.secretHint') }}</p><pre>{{ attempt.challenge.prompt }}</pre>
      <div v-if="attempt.challenge.kind === 'hostKey'" class="actions"><button type="button" @click="cancel">{{ t('workspace.cancel') }}</button>
        <button class="primary" type="button" :disabled="submittingAnswer" @click="reply('yes')">{{ t('remote.trust') }}</button></div>
      <template v-else><label>{{ t('remote.answer') }}<input ref="answerInput" v-model="answer" type="password" autocomplete="off" :disabled="submittingAnswer" /></label>
        <div class="actions"><button type="button" @click="cancel">{{ t('workspace.cancel') }}</button><button class="primary" :disabled="submittingAnswer">{{ t('remote.continue') }}</button></div></template>
    </form>
    <WorkspaceFailure v-if="error || externalError" class="error" :message="error || externalError || ''" remote :code="error ? failureCode : ''" />
  </div>
</template>

<style scoped>
.remote-picker { padding:18px; overflow:auto; max-height:70vh; }
.picker-head {
  position:sticky; top:-18px; z-index:1; display:flex; align-items:center; justify-content:space-between;
  gap:16px; margin:-18px -18px 14px; padding:12px 18px;
  border-bottom:1px solid var(--border); background:var(--surface-2);
}
.picker-context { display:flex; align-items:center; gap:8px; min-width:0; }
.picker-context-copy { min-width:0; }
.picker-context strong,.picker-context small { display:block; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
.picker-context strong { font-size:13px; font-weight:600; }
.picker-context small { margin-top:2px; font-size:11px; color:var(--text-muted); }
button.picker-back { flex-shrink:0; padding:6px 8px; border-color:transparent; color:var(--text-secondary); font-size:12px; }
button.picker-back:hover { color:var(--accent-hover); }
fieldset { border:0; padding:0; margin:0; min-width:0; }
p { margin:0 0 14px; font-size:12px; line-height:1.6; color:var(--text-muted); }
.origin { overflow-wrap:anywhere; }
.scope { font-size:10px; font-weight:700; padding:2px 6px; border-radius:5px; background:var(--accent-soft); color:var(--accent-hover); }
.machine { font-family:var(--font-mono); color:var(--text-secondary); }
label { display:flex; flex-direction:column; gap:6px; margin-bottom:12px; font-size:12px; }
input { min-width:0; width:100%; box-sizing:border-box; padding:9px; border:1px solid var(--border); border-radius:7px; background:var(--surface-1); color:var(--text-primary); }
input:focus-visible,button:focus-visible { outline:2px solid var(--accent); outline-offset:2px; }
.actions { display:flex; justify-content:space-between; flex-wrap:wrap; gap:8px; margin:14px 0 0; }
.actions > .primary { margin-inline-start:auto; }
button { padding:7px 11px; border:1px solid var(--border); border-radius:7px; color:var(--text-primary); }
button:hover:not(:disabled) { background:var(--surface-hover); } button:disabled { opacity:.5; cursor:default; }
button.primary { background:var(--accent); color:var(--accent-contrast); border-color:var(--accent); }
.section-label { display:flex; justify-content:space-between; gap:12px; margin:16px 0 8px; font-size:11px; color:var(--text-muted); }
.section-label small { overflow:hidden; white-space:nowrap; text-overflow:ellipsis; }
.host-row { display:flex; gap:4px; align-items:center; border:1px solid var(--border); border-radius:9px; margin:6px 0; padding-right:6px; }
.host-main { display:flex; flex:1; min-width:0; align-items:center; gap:10px; text-align:left; border:0; padding:12px; }
.host-main strong,.host-main small { display:block; overflow-wrap:anywhere; }
.host-main small { color:var(--text-muted); font-size:11px; margin-top:3px; }
.host-icon { font-size:20px; color:var(--accent-hover); } .arrow { margin-left:auto; color:var(--text-muted); }
.quiet { border:0; font-size:11px; padding:6px; } .alias-row { display:flex; justify-content:space-between; width:100%; text-align:left; margin-bottom:5px; }
.new-host { width:100%; border-style:dashed; margin-top:12px; padding:12px; }
.input-row,.path-row { display:flex; gap:7px; } .input-row input,.path-row input { flex:1; } .input-row button,.path-row button { flex-shrink:0; }
.connected-host { display:flex; align-items:center; gap:7px; flex-wrap:wrap; margin-bottom:10px; } .connected-host small { color:var(--text-muted); }
.opening-target { overflow-wrap:anywhere; margin-bottom:0; }
.breadcrumbs { display:flex; gap:2px; overflow:auto; margin:7px 0; } .breadcrumbs button { border:0; font-size:11px; white-space:nowrap; padding:5px 6px; }
.directory-tools { display:flex; align-items:center; flex-wrap:wrap; gap:7px; margin:8px 0; } .directory-tools button { font-size:11px; padding:5px 8px; }
.directory-tools label { flex-direction:row; align-items:center; margin:0 0 0 auto; color:var(--text-muted); font-size:11px; } .directory-tools input { width:auto; }
.directories { display:flex; flex-direction:column; gap:3px; max-height:200px; min-height:60px; overflow:auto; border:1px solid var(--border); border-radius:8px; padding:5px; }
.directories button { display:flex; gap:8px; text-align:left; border:0; font-size:12px; } .directories button span:first-child { color:var(--accent-hover); }
.empty-directory { margin:auto; padding:14px; text-align:center; }
.host-recents button { display:flex; width:100%; gap:10px; border:0; font-size:11px; text-align:left; } .host-recents small { color:var(--text-muted); overflow-wrap:anywhere; }
.prepare-hint { margin:12px 0 0; }
.stop-warning { margin:12px 0 0; padding:9px; border-radius:7px; background:var(--warning-soft); color:var(--text-secondary); }
.progress { display:flex; align-items:center; gap:8px; padding:12px; margin-top:14px; border-radius:8px; background:var(--accent-soft); font-size:12px; } .progress button { margin-left:auto; flex-shrink:0; }
.spinner { width:12px; height:12px; flex-shrink:0; border:2px solid var(--border-strong); border-top-color:var(--accent); border-radius:50%; animation:spin 1s linear infinite; }
@keyframes spin { to { transform:rotate(360deg); } } @media (prefers-reduced-motion:reduce) { .spinner { animation:none; } }
.authentication { margin-top:12px; padding:14px; border:1px solid var(--border-strong); border-radius:8px; background:var(--surface-1); }
.authentication strong { font-size:13px; } .authentication p { margin:7px 0; }
pre { white-space:pre-wrap; overflow-wrap:anywhere; font:11px/1.7 var(--font-mono); padding:10px; border:1px solid var(--border); border-radius:6px; max-height:150px; overflow:auto; }
.error { color:var(--danger,#e26c6c); overflow-wrap:anywhere; margin:12px 0 0; } summary { cursor:pointer; margin-bottom:12px; font-size:12px; }
.error-detail { margin-top:8px; color:var(--text-muted); }
.sr-only { position:absolute; width:1px; height:1px; overflow:hidden; clip:rect(0,0,0,0); }
</style>
