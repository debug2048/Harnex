<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { remoteConnections, type SupervisorWorkspace } from '../../api/client'
import { t } from '../../i18n'
import WebDirectoryPicker from './WebDirectoryPicker.vue'
import RemoteWorkspacePicker from './RemoteWorkspacePicker.vue'

const props = defineProps<{
  open: boolean
  requestSequence?: number
  activeProject: SupervisorWorkspace | null
  currentScopeId?: string | null
  otherProjects: SupervisorWorkspace[]
  selectedProjectId: string | null
  switching: boolean
  needsStop: boolean
  error: string
  nativePicker: boolean
  directorySubmitting: boolean
  initialProject?: SupervisorWorkspace | null
}>()

const emit = defineEmits<{
  close: []
  select: [id: string]
  openOther: []
  openPath: [path: string]
  confirm: []
  remoteSelected: [project: SupervisorWorkspace, stopAcknowledged?: boolean]
  refresh: []
}>()

const dialog = ref<HTMLElement | null>(null)
const browsingDirectories = ref(false)
const browsingRemote = ref(false)
const remoteBusy = ref(false)
const remotePicker = ref<InstanceType<typeof RemoteWorkspacePicker> | null>(null)
const remoteInitial = ref<SupervisorWorkspace | null>(null)
let backdropPressed = false
const missingProfiles = ref(new Set<string>())
let connectionCheck = 0
const recentCount = computed(() => props.otherProjects.length + (props.activeProject ? 1 : 0))

watch(() => [props.open, props.requestSequence] as const, ([open]) => {
  if (open) {
    browsingDirectories.value = false
    browsingRemote.value = Boolean(props.initialProject?.remoteProfile)
    remoteInitial.value = props.initialProject || null
    backdropPressed = false
    void nextTick(() => dialog.value?.focus())
    const check = ++connectionCheck
    if (props.otherProjects.some(project => project.remoteProfile) || props.activeProject?.remoteProfile) {
      void remoteConnections.list().then(profiles => {
        if (!props.open || check !== connectionCheck) return
        const ids = new Set(profiles.map(profile => profile.id))
        missingProfiles.value = new Set([...(props.activeProject ? [props.activeProject] : []), ...props.otherProjects]
          .flatMap(project => project.remoteProfile && !ids.has(project.remoteProfile) ? [project.remoteProfile] : []))
      }).catch(() => {})
    }
  }
}, {immediate:true})

function dismissBackdrop(event: MouseEvent) {
  const intentional = backdropPressed && event.target === event.currentTarget
  backdropPressed = false
  // Editing paths and connection drafts requires an explicit close action. A drag
  // starting in an input can otherwise produce a click retargeted to the backdrop.
  if (intentional && !browsingRemote.value && !browsingDirectories.value && !props.switching && !props.directorySubmitting) emit('close')
}

function connectionRemoved(id: string) { ++connectionCheck; missingProfiles.value.add(id) }

function projectRecency(value: string | null) {
  if (!value) return t('workspace.neverOpened')
  const elapsed = Date.now() - new Date(value).getTime()
  if (!Number.isFinite(elapsed) || elapsed < 0) return t('workspace.recent')
  if (elapsed < 3_600_000) return t('workspace.minutesAgo', { n: Math.max(1, Math.floor(elapsed / 60_000)) })
  if (elapsed < 86_400_000) return t('workspace.hoursAgo', { n: Math.floor(elapsed / 3_600_000) })
  return t('workspace.daysAgo', { n: Math.floor(elapsed / 86_400_000) })
}

function openOther() {
  if (props.nativePicker) emit('openOther')
  else browsingDirectories.value = true
}

function chooseProject(project: SupervisorWorkspace) {
  if (project.isActive && project.state === 'READY') emit('remoteSelected', project)
  else if (project.remoteProfile) { remoteInitial.value = project; browsingRemote.value = true }
  else if (project.isActive) emit('remoteSelected', project)
  else emit('select', project.id)
}

function handleKeydown(event: KeyboardEvent) {
  if (event.key === 'Escape') {
    event.preventDefault()
    if (props.switching) { if (browsingRemote.value) void remotePicker.value?.cancel(); return }
    if (remoteBusy.value) { void remotePicker.value?.cancel(); return }
    if (browsingRemote.value) { browsingRemote.value = false; return }
    if (browsingDirectories.value && !props.directorySubmitting) browsingDirectories.value = false
    else emit('close')
    return
  }
  if (event.key !== 'Tab' || !dialog.value) return
  const focusable = Array.from(dialog.value.querySelectorAll<HTMLElement>(
    'button:not(:disabled), input:not(:disabled), [tabindex]:not([tabindex="-1"])',
  ))
  if (!focusable.length) return
  const first = focusable[0]
  const last = focusable[focusable.length - 1]
  if (event.shiftKey && document.activeElement === first) {
    event.preventDefault()
    last.focus()
  } else if (!event.shiftKey && document.activeElement === last) {
    event.preventDefault()
    first.focus()
  }
}
</script>

<template>
  <Teleport to="body">
    <Transition name="workspace-dialog">
      <div v-if="open" class="workspace-modal-overlay" @pointerdown="backdropPressed = $event.target === $event.currentTarget" @click="dismissBackdrop">
        <section ref="dialog" class="workspace-modal" :class="{ browsing: browsingDirectories }"
          role="dialog" aria-modal="true" tabindex="-1" aria-labelledby="workspace-modal-title"
          @keydown="handleKeydown">
          <header class="workspace-modal-head">
            <div class="workspace-title-icon" aria-hidden="true">
              <svg width="19" height="19" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7">
                <path d="M3 6.5h7l2 2h9v10H3z" />
              </svg>
            </div>
            <div>
              <h2 id="workspace-modal-title">
                {{ browsingRemote ? t('remote.open') : browsingDirectories ? t('workspace.chooseDirectory') : t('workspace.openTitle') }}
              </h2>
              <p>{{ browsingDirectories ? t('workspace.chooseDirectoryHint') : t('workspace.singleActive') }}</p>
            </div>
            <button type="button" class="workspace-close" :aria-label="t('workspace.cancel')"
              :disabled="switching || directorySubmitting" @click="remoteBusy ? remotePicker?.cancel() : emit('close')">
              <svg width="17" height="17" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8">
                <path d="M6 6l12 12M18 6 6 18" />
              </svg>
            </button>
          </header>

          <RemoteWorkspacePicker v-if="browsingRemote" :key="requestSequence" ref="remotePicker" :opening="switching" :external-error="error" :needs-stop="needsStop && (!remoteInitial || remoteInitial.id !== activeProject?.id)" :initial-project="remoteInitial" :projects="[...(activeProject ? [activeProject] : []), ...otherProjects]" @back="browsingRemote = false" @busy="remoteBusy = $event"
            @removed="connectionRemoved" @forgotten="emit('refresh')"
            @select="(project, stopAcknowledged) => emit('remoteSelected', project, stopAcknowledged)" />
          <WebDirectoryPicker v-else-if="browsingDirectories" :submitting="directorySubmitting"
            :external-error="error" @back="browsingDirectories = false" @select="emit('openPath', $event)" />

          <template v-else>
            <div class="workspace-option-list">
              <div class="workspace-section-head">
                <span>{{ t('workspace.recentProjects') }}</span>
                <small>{{ recentCount }}</small>
              </div>
              <button v-if="activeProject" type="button" class="workspace-option current"
                :disabled="activeProject.state === 'READY' && currentScopeId === activeProject.id || switching" @click="chooseProject(activeProject)">
                <span class="workspace-glyph" aria-hidden="true">
                  <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7">
                    <path d="M3 6.5h7l2 2h9v10H3z" />
                  </svg>
                </span>
                <span class="workspace-option-copy">
                  <strong>{{ activeProject.name }}</strong><small>{{ activeProject.remoteProfile ? `SSH · ${activeProject.remoteHost || activeProject.remoteProfile}` : t(nativePicker ? 'remote.local' : 'remote.localWeb') }} · {{ activeProject.root }}</small>
                </span>
                <em class="current-badge" :class="{ failed: activeProject.state === 'FAILED' }"><span />{{ activeProject.state === 'FAILED' ? t('remote.reconnect') : t('workspace.current') }}</em>
              </button>
              <button v-for="project in otherProjects" :key="project.id" type="button"
                class="workspace-option" :class="{ selected: selectedProjectId === project.id }"
                :disabled="switching" @click="chooseProject(project)" @dblclick="!project.remoteProfile && emit('confirm')">
                <span class="workspace-glyph" aria-hidden="true">
                  <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7">
                    <path d="M3 6.5h7l2 2h9v10H3z" />
                  </svg>
                </span>
                <span class="workspace-option-copy"><strong>{{ project.name }}</strong><small>{{ project.remoteProfile ? `SSH · ${project.remoteHost || project.remoteProfile}` : t(nativePicker ? 'remote.local' : 'remote.localWeb') }} · {{ project.root }}</small>
                  <small v-if="project.remoteProfile && missingProfiles.has(project.remoteProfile)" class="connection-missing">{{ t('remote.connectionRemoved') }}</small></span>
                <em>{{ project.remoteRetainedAt ? t('remote.retained') : projectRecency(project.lastOpened) }}</em>
                <span class="selected-mark" aria-hidden="true">
                  <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                    <path d="m5 12 4 4L19 6" />
                  </svg>
                </span>
              </button>
              <div v-if="recentCount === 0" class="workspace-empty-recent">
                {{ t('workspace.emptyRecent') }}
              </div>
              <button type="button" class="workspace-open-other" :disabled="switching" @click="openOther">
                <span class="open-other-icon" aria-hidden="true">
                  <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7">
                    <path d="M3 6.5h7l2 2h9v10H3z" /><path d="M12 12v5M9.5 14.5h5" />
                  </svg>
                </span>
                <span><strong>{{ t(nativePicker ? 'remote.localOpen' : 'remote.localOpenWeb') }}</strong><small>{{ t(nativePicker ? 'remote.localHint' : 'remote.localHintWeb') }}</small></span>
                <svg class="open-other-arrow" width="15" height="15" viewBox="0 0 24 24" fill="none"
                  stroke="currentColor" stroke-width="1.8" aria-hidden="true">
                  <path d="m9 18 6-6-6-6" />
                </svg>
              </button>
              <button type="button" class="workspace-open-other" :disabled="switching || directorySubmitting" @click="remoteInitial = null; browsingRemote = true">
                <span class="open-other-icon" aria-hidden="true"><svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7"><rect x="3" y="3" width="18" height="7" rx="2"/><rect x="3" y="14" width="18" height="7" rx="2"/><path d="M7 6.5h.01M7 17.5h.01M11 6.5h6M11 17.5h6"/></svg></span>
                <span><strong>{{ t('remote.open') }}</strong><small>{{ t('remote.manage') }}</small></span>
                <svg class="open-other-arrow" width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" aria-hidden="true"><path d="m9 18 6-6-6-6" /></svg>
              </button>
            </div>
            <div v-if="needsStop" class="workspace-switch-warning" role="note">
              <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" aria-hidden="true">
                <path d="M12 3 2.5 20h19zM12 9v5M12 17.5v.01" />
              </svg>
              <span>{{ t('workspace.switchWarning') }}</span>
            </div>
            <div v-if="error" class="workspace-switch-warning error" role="alert">{{ error }}</div>
            <footer class="workspace-modal-foot">
              <span>{{ t('workspace.doubleClickWorkspaceHint') }}</span>
              <button type="button" class="modal-cancel" :disabled="switching" @click="emit('close')">
                {{ t('workspace.cancel') }}
              </button>
              <button type="button" class="modal-confirm" :disabled="switching || !selectedProjectId"
                @click="emit('confirm')">
                <template v-if="selectedProjectId">
                  {{ needsStop ? t('workspace.stopAndOpen') : t('workspace.open') }}
                  {{ otherProjects.find(project => project.id === selectedProjectId)?.name || '' }}
                </template>
                <template v-else>{{ t('workspace.selectOne') }}</template>
              </button>
            </footer>
          </template>
        </section>
      </div>
    </Transition>
  </Teleport>
</template>

<style scoped>
.current-badge.failed { color:var(--warning,#c58c35); }
.current-badge.failed span { background:currentColor; }
.workspace-modal-overlay {
  position:fixed; z-index:130; inset:0; display:grid; place-items:center; padding:22px;
  background:color-mix(in srgb,#05070b 70%,transparent); backdrop-filter:blur(8px) saturate(.85);
}
.workspace-modal {
  width:min(600px,100%); max-height:calc(100vh - 44px); overflow:hidden;
  border:1px solid var(--border-strong); border-radius:16px;
  background:var(--surface-2); box-shadow:0 28px 90px rgba(0,0,0,.48),0 0 0 1px rgba(255,255,255,.025);
}
.workspace-modal.browsing { width:min(760px,100%); }
.workspace-modal-head {
  min-height:74px; display:grid; grid-template-columns:38px minmax(0,1fr) 32px;
  align-items:center; gap:11px; padding:13px 15px 12px 18px; border-bottom:1px solid var(--border);
  background:linear-gradient(145deg,color-mix(in srgb,var(--surface-2) 94%,var(--accent)),var(--surface-2));
}
.workspace-title-icon {
  width:38px; height:38px; display:grid; place-items:center; border:1px solid color-mix(in srgb,var(--accent) 28%,var(--border));
  border-radius:10px; background:var(--accent-soft); color:var(--accent-hover);
}
.workspace-modal-head h2 { margin:0; color:var(--text-primary); font-size:15px; letter-spacing:-.015em; }
.workspace-modal-head p { margin:3px 0 0; color:var(--text-muted); font-size:11.5px; }
.workspace-close { width:32px; height:32px; display:grid; place-items:center; border-radius:8px; color:var(--text-muted); }
.workspace-close:hover:not(:disabled) { background:var(--surface-hover); color:var(--text-primary); }
.workspace-option-list { max-height:min(430px,calc(100vh - 255px)); overflow-y:auto; padding:13px 12px 11px; }
.workspace-section-head { height:25px; display:flex; align-items:flex-start; gap:6px; padding:0 5px; color:var(--text-muted); font-size:11px; font-weight:650; }
.workspace-section-head small { min-width:18px; padding:0 5px; border-radius:10px; background:var(--surface-3); color:var(--text-faint); text-align:center; font-size:10px; }
.workspace-option {
  width:100%; min-height:59px; display:grid; grid-template-columns:36px minmax(0,1fr) auto 20px;
  align-items:center; gap:10px; margin-bottom:3px; padding:8px 10px; border:1px solid transparent;
  border-radius:10px; background:transparent; text-align:left; transition:border-color var(--transition),background var(--transition);
}
.workspace-option:hover:not(:disabled) { border-color:var(--border); background:var(--surface-hover); }
.workspace-option.selected { border-color:color-mix(in srgb,var(--accent) 46%,var(--border)); background:var(--accent-soft); }
.workspace-option.current { opacity:.8; }
.workspace-glyph { width:34px; height:34px; display:grid; place-items:center; border-radius:9px; background:var(--surface-3); color:var(--text-secondary); }
.workspace-option.selected .workspace-glyph { background:color-mix(in srgb,var(--accent) 20%,var(--surface-3)); color:var(--accent-hover); }
.workspace-option-copy { min-width:0; }
.workspace-option strong,.workspace-option small { display:block; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
.workspace-option strong { color:var(--text-primary); font-size:12.5px; font-weight:650; }
.workspace-option small { margin-top:2px; color:var(--text-muted); font:10.5px var(--font-mono); }
.workspace-option em { color:var(--text-faint); font-size:10.5px; font-style:normal; }
.current-badge { display:flex; align-items:center; gap:5px; padding:3px 7px; border-radius:10px; background:var(--success-soft); color:var(--success)!important; }
.current-badge span { width:5px; height:5px; border-radius:50%; background:var(--success); }
.selected-mark { display:grid; place-items:center; color:var(--accent-hover); opacity:0; }
.workspace-option.selected .selected-mark { opacity:1; }
.workspace-empty-recent { padding:18px 12px 24px; color:var(--text-muted); font-size:11.5px; text-align:center; }
.workspace-open-other {
  width:100%; min-height:59px; display:grid; grid-template-columns:36px minmax(0,1fr) 20px;
  align-items:center; gap:10px; margin-top:9px; padding:8px 10px;
  border:1px dashed var(--border-strong); border-radius:10px; color:var(--text-secondary); text-align:left;
}
.workspace-open-other:hover:not(:disabled) { border-color:color-mix(in srgb,var(--accent) 50%,var(--border)); background:var(--accent-soft); color:var(--text-primary); }
.open-other-icon { width:34px; height:34px; display:grid; place-items:center; border-radius:9px; background:var(--accent-soft); color:var(--accent-hover); }
.workspace-open-other strong,.workspace-open-other small { display:block; }
.workspace-open-other strong { color:var(--text-primary); font-size:12.5px; }
.workspace-open-other small { margin-top:2px; color:var(--text-muted); font-size:10.5px; }
.open-other-arrow { justify-self:end; color:var(--text-faint); }
.workspace-switch-warning { display:flex; align-items:flex-start; gap:8px; margin:0 14px 12px; padding:9px 10px; border:1px solid color-mix(in srgb,var(--warning) 32%,var(--border)); border-radius:8px; background:var(--warning-soft); color:var(--text-secondary); font-size:11.5px; line-height:1.5; }
.workspace-switch-warning svg { flex:0 0 auto; margin-top:1px; color:var(--warning); }
.workspace-switch-warning.error { border-color:color-mix(in srgb,var(--danger) 35%,var(--border)); background:var(--danger-soft); color:var(--danger); }
.workspace-modal-foot { min-height:58px; display:flex; justify-content:flex-end; align-items:center; gap:8px; padding:11px 14px; border-top:1px solid var(--border); background:var(--surface-1); }
.workspace-modal-foot > span { min-width:0; flex:1; color:var(--text-faint); font-size:10.5px; }
.workspace-modal-foot button { min-height:34px; padding:0 13px; border:1px solid var(--border); border-radius:8px; font-size:12px; }
.modal-cancel:hover:not(:disabled) { background:var(--surface-hover); }
.modal-confirm { max-width:280px; overflow:hidden; border-color:var(--accent)!important; background:var(--accent); color:var(--accent-contrast); text-overflow:ellipsis; white-space:nowrap; }
.modal-confirm:hover:not(:disabled) { background:var(--accent-hover); }
.workspace-dialog-enter-active,.workspace-dialog-leave-active { transition:opacity 150ms ease; }
.workspace-dialog-enter-active .workspace-modal,.workspace-dialog-leave-active .workspace-modal { transition:transform 160ms ease,opacity 150ms ease; }
.workspace-dialog-enter-from,.workspace-dialog-leave-to { opacity:0; }
.workspace-dialog-enter-from .workspace-modal,.workspace-dialog-leave-to .workspace-modal { opacity:0; transform:translateY(8px) scale(.985); }
@media (max-width:640px) {
  .workspace-modal-overlay { padding:8px; align-items:end; }
  .workspace-modal { max-height:calc(100vh - 16px); border-radius:15px; }
  .workspace-option-list { max-height:calc(100vh - 230px); }
  .workspace-option { grid-template-columns:36px minmax(0,1fr) 20px; }
  .workspace-option em { display:none; }
  .workspace-modal-foot > span { display:none; }
}
</style>
