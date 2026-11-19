<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { supervisor, SupervisorApiError, type SupervisorWorkspace } from '../api/client'
import { t } from '../i18n'
import { useInteractionStore } from '../stores/interaction'
import { useRunStore } from '../stores/run'
import { useThreadStore } from '../stores/threads'
import { useWorkspaceStore } from '../stores/workspace'
import { useConversationStore, type WorkingMode } from '../stores/conversation'
import { useRuntimeStore } from '../stores/runtime'
import { useExplorerStore } from '../stores/explorer'
import { CAPABILITIES } from '../domain/capabilities'
import BrandLogo from './BrandLogo.vue'
import WorkspaceExplorer from './WorkspaceExplorer.vue'
import SidebarFooterNav from './sidebar/SidebarFooterNav.vue'
import SidebarThreads from './sidebar/SidebarThreads.vue'
import WorkspaceSwitcherDialog from './sidebar/WorkspaceSwitcherDialog.vue'
import { errorMessage } from '../utils/unknown'

const workspace = useWorkspaceStore()
const threads = useThreadStore()
const interactions = useInteractionStore()
const run = useRunStore()
const conversation = useConversationStore()
const router = useRouter()
const runtime = useRuntimeStore()
const explorer = useExplorerStore()
const projectSection = computed(() => workspace.profile === 'PROJECT')
const workbenchVisible = computed(() => router.currentRoute.value.path === '/' || router.currentRoute.value.path.startsWith('/t/'))
let navigationRevision = 0

async function selectMode(kind: WorkingMode) {
  if (workspace.switching) return
  contextMode.value = 'threads'
  const revision = ++navigationRevision
  try {
    if (kind === 'CONVERSATION' && workspace.profile !== 'PERSONAL') {
      if (!workspace.available) return
      await workspace.openPersonal()
      if (revision !== navigationRevision) return
      conversation.requestWorkbench()
      await nextTick()
      window.dispatchEvent(new Event('harnex-close-sidebar'))
      return
    } else if (kind === 'AGENT' && workspace.profile !== 'PROJECT') {
      if (workspace.fixedWorkspaceId) {
        await workspace.open(workspace.fixedWorkspaceId)
        await finishProjectSelection()
        return
      }
      const project = workspace.active
      if (project?.state === 'READY') await switchTo(project, false)
      else showWorkspaceSwitcher(project)
      return
    }
    if (revision !== navigationRevision) return
    conversation.requestWorkbench()
    window.dispatchEvent(new Event('harnex-close-sidebar'))
  } catch (cause) { run.error = errorMessage(cause) }
}

async function finishProjectSelection() {
  conversation.requestWorkbench()
  await nextTick()
  window.dispatchEvent(new Event('harnex-close-sidebar'))
}

const contextMode = ref<'threads' | 'files'>('threads')
function showProjectFiles() {
  if (contextMode.value === 'files') return
  contextMode.value = 'files'
  // Initial workspace loading already fetches the root; reuse that request if it is still in flight.
  if (workspace.ready && runtime.has(CAPABILITIES.filesRead) && !explorer.directories['']?.loading) {
    void explorer.refresh()
  }
}
const switcherOpen = ref(false)
const switcherRequestSequence = ref(0)
const initialProject = ref<SupervisorWorkspace | null>(null)
const selectedProjectId = ref<string | null>(null)
const workspaceTrigger = ref<HTMLButtonElement | null>(null)
const remoteWorkspaceBlocker = ref(false)
const localWorkspaceError = ref('')
const directorySubmitting = ref(false)
let refreshTimer: ReturnType<typeof setInterval> | null = null

const activeProject = computed(() => workspace.profile === 'PROJECT' ? workspace.active : null)
const otherProjects = computed(() => workspace.workspaces.filter(item => !item.isActive))
const selectedProject = computed(() =>
  workspace.workspaces.find(item => item.id === selectedProjectId.value) || null,
)
const hasWorkspaceBlocker = computed(() =>
  workspace.profile === 'PROJECT' && (run.streaming
    || interactions.pendingApprovals.length > 0
    || interactions.pendingQuestions.length > 0
    || Boolean(interactions.pendingPlanReview)),
)
const workspaceSwitchNeedsStop = computed(() =>
  !(workspace.active?.remoteProfile && (workspace.active.remoteKeepRunning || workspace.active.remoteService))
    && (hasWorkspaceBlocker.value || remoteWorkspaceBlocker.value),
)

async function refreshProjects() {
  if (typeof document !== 'undefined' && document.hidden) return
  try { await workspace.refresh() } catch { /* retain the last good registry projection */ }
  // IM and Webhook turns can create conversations while the user is looking at another page.
  if (workspace.ready && !threads.loading) await threads.refresh(true)
}

onMounted(async () => {
  await workspace.bootstrap()
  if (workspace.available) {
    await refreshProjects()
    refreshTimer = setInterval(refreshProjects, 5000)
  }
  if (workspace.ready) await threads.refresh()
})

onUnmounted(() => {
  if (refreshTimer) clearInterval(refreshTimer)
})

function showWorkspaceSwitcher(project: SupervisorWorkspace | null = null) {
  if (!workspace.available) return
  if (workspace.fixedWorkspaceId) {
    void workspace.open(workspace.fixedWorkspaceId).then(finishProjectSelection).catch(cause => { run.error = errorMessage(cause) })
    return
  }
  selectedProjectId.value = null
  remoteWorkspaceBlocker.value = false
  localWorkspaceError.value = ''
  initialProject.value = project
  switcherRequestSequence.value++
  switcherOpen.value = true
}

watch(() => workspace.openRequest, request => {
  if (request) showWorkspaceSwitcher(workspace.workspaces.find(item => item.id === request.id) || null)
}, {immediate:true})

function closeWorkspaceSwitcher() {
  if (workspace.switching || directorySubmitting.value) return
  selectedProjectId.value = null
  remoteWorkspaceBlocker.value = false
  switcherOpen.value = false
  void nextTick(() => workspaceTrigger.value?.focus())
}

async function switchTo(target: SupervisorWorkspace, stopActiveRun = workspaceSwitchNeedsStop.value) {
  if (workspace.switching) return
  // Recovery belongs to this selection, not the workspace restored at startup.
  initialProject.value = target
  // SSH authentication and cancellation stay in the chooser through the opening phase.
  if (!target.remoteProfile) switcherOpen.value = false
  try {
    await workspace.open(target.id, stopActiveRun)
    switcherOpen.value = false
    await finishProjectSelection()
  } catch (cause: unknown) {
    if (cause instanceof SupervisorApiError && cause.code === 'active_run') {
      remoteWorkspaceBlocker.value = true
      localWorkspaceError.value = ''
      switcherOpen.value = true
      return
    }
    localWorkspaceError.value = errorMessage(cause)
    switcherOpen.value = true
  }
}

async function chooseWorkspace() {
  if (workspace.switching || directorySubmitting.value) return
  localWorkspaceError.value = ''
  directorySubmitting.value = true
  try {
    const project = await workspace.chooseAndRegister()
    if (project) await switchTo(project)
  } catch (cause: unknown) {
    localWorkspaceError.value = errorMessage(cause)
  } finally {
    directorySubmitting.value = false
  }
}

async function openWorkspacePath(root: string) {
  if (workspace.switching || directorySubmitting.value) return
  localWorkspaceError.value = ''
  directorySubmitting.value = true
  try {
    const project = await workspace.register(root)
    await switchTo(project)
  } catch (cause: unknown) {
    localWorkspaceError.value = errorMessage(cause)
  } finally {
    directorySubmitting.value = false
  }
}

function confirmWorkspaceSwitch() {
  if (selectedProject.value) void switchTo(selectedProject.value)
}

async function openRemoteWorkspace(project: SupervisorWorkspace, stopAcknowledged = false) {
  await workspace.refresh()
  if (project.id === workspace.activeWorkspaceId) {
    try { await workspace.open(project.id); switcherOpen.value = false; await finishProjectSelection() }
    catch (cause) { localWorkspaceError.value = errorMessage(cause) }
    return
  }
  if (workspaceSwitchNeedsStop.value && !stopAcknowledged) {
    localWorkspaceError.value = t('workspace.switchWarning')
    return
  }
  await switchTo(project, stopAcknowledged)
}
</script>

<template>
  <aside class="sidebar">
    <div class="sidebar-brand">
      <BrandLogo size="sm" />
      <strong>Harnex</strong>
    </div>

    <nav class="work-nav" :aria-label="t('sidebar.workNavigation')">
      <button v-if="workspace.available || !projectSection" type="button" :class="{ active: workbenchVisible && !projectSection }"
        :aria-current="workbenchVisible && !projectSection ? 'page' : undefined"
        :disabled="workspace.switching" @click="selectMode('CONVERSATION')">
        <svg width="17" height="17" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" aria-hidden="true"><path d="M5 4h14a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H9l-6 3V6a2 2 0 0 1 2-2Z"/><path d="M7 9h10M7 13h6"/></svg>
        {{ t('chat.conversationMode') }}
      </button>
      <button v-if="workspace.available || runtime.has('agent.execution')" type="button"
        :class="{ active: workbenchVisible && projectSection }"
        :aria-current="workbenchVisible && projectSection ? 'page' : undefined"
        :disabled="workspace.switching" @click="selectMode('AGENT')">
        <svg width="17" height="17" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" aria-hidden="true"><rect x="4" y="7" width="16" height="14" rx="4"/><path d="M12 3v4M8 12v2M16 12v2M9 17h6"/></svg>
        Agent
      </button>
    </nav>

    <div v-if="projectSection" class="workspace-area">
      <button ref="workspaceTrigger" class="workspace-switcher" type="button"
        :aria-haspopup="workspace.canSelectProject ? 'dialog' : undefined"
        :aria-expanded="workspace.canSelectProject ? switcherOpen : undefined"
        :disabled="!workspace.canSelectProject"
        :title="activeProject?.root || t('workspace.openTitle')" @click="showWorkspaceSwitcher()">
        <span class="workspace-glyph" aria-hidden="true">
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none"
            stroke="currentColor" stroke-width="1.7">
            <path d="M3 6.5h7l2 2h9v10H3z" />
          </svg>
        </span>
        <span class="ws-meta">
          <strong class="ws-name">{{ activeProject?.name || t('workspace.openTitle') }}</strong>
          <small class="ws-path">{{ activeProject ? (activeProject.remoteProfile ? `SSH ${activeProject.remoteHost || activeProject.remoteProfile}` : t(supervisor.native ? 'remote.local' : 'remote.localWeb')) + ' · ' : '' }}{{ activeProject?.root || t('workspace.emptyRecent') }}</small>
        </span>
        <svg v-if="workspace.canSelectProject" class="caret" width="12" height="12" viewBox="0 0 16 16" fill="none"
          stroke="currentColor" stroke-width="2">
          <polyline points="4 6 8 10 12 6"/>
        </svg>
      </button>
    </div>

    <div v-if="activeProject" class="mode-tabs" role="tablist" :aria-label="t('sidebar.workspaceNav')">
      <button :class="{ active: contextMode === 'threads' }" role="tab"
        :aria-selected="contextMode === 'threads'" @click="contextMode = 'threads'">
        {{ t('sidebar.taskRecords') }}
      </button>
      <button :class="{ active: contextMode === 'files' }" role="tab"
        :aria-selected="contextMode === 'files'" @click="showProjectFiles">
        {{ t('sidebar.projectFiles') }}
      </button>
    </div>

    <SidebarThreads v-if="workspace.ready" v-show="workspace.profile === 'PERSONAL' || contextMode === 'threads'" />
    <WorkspaceExplorer v-if="activeProject" v-show="contextMode === 'files'" embedded />
    <SidebarFooterNav :active-workspace="Boolean(activeProject)" />

    <WorkspaceSwitcherDialog
      :open="switcherOpen"
      :request-sequence="switcherRequestSequence"
      :initial-project="initialProject"
      :active-project="workspace.active"
      :current-scope-id="workspace.storageScopeId"
      :other-projects="otherProjects"
      :selected-project-id="selectedProjectId"
      :switching="workspace.switching"
      :needs-stop="workspaceSwitchNeedsStop"
      :error="localWorkspaceError"
      :native-picker="supervisor.native"
      :directory-submitting="directorySubmitting"
      @close="closeWorkspaceSwitcher"
      @select="selectedProjectId = $event"
      @open-other="chooseWorkspace"
      @open-path="openWorkspacePath"
      @remote-selected="openRemoteWorkspace"
      @confirm="confirmWorkspaceSwitch"
      @refresh="refreshProjects"
      @clear-error="localWorkspaceError = ''"
    />
  </aside>
</template>

<style scoped>
.work-nav { display:grid; gap:4px; padding:4px 12px 8px; }
.work-nav button { display:flex; gap:10px; align-items:center; min-height:38px; padding:8px 12px; border:0; border-radius:8px; background:transparent; color:var(--text-secondary); text-align:left; font-size:13px; cursor:pointer; }
.work-nav button:hover { background:var(--surface-3); }
.work-nav button.active { color:var(--text-primary); background:var(--surface-3); font-weight:600; }
.sidebar {
  width:274px; min-width:274px; min-height:0; display:flex; flex-direction:column;
  border-right:1px solid var(--border);
  background:var(--navbar-background, var(--surface-1));
}
.sidebar-brand {
  height:56px; flex:0 0 56px; display:flex; align-items:center; gap:10px;
  padding:0 18px;
}
.sidebar-brand strong { font-size:18px; font-weight:650; letter-spacing:-.04em; }
.workspace-area { position:relative; margin:9px 10px 8px; }
.workspace-switcher {
  width:100%; min-height:54px; display:grid;
  grid-template-columns:28px minmax(0,1fr) 14px; align-items:center; gap:8px;
  padding:7px 8px; border:0; border-radius:8px; background:transparent; text-align:left;
}
.workspace-switcher:hover { background:var(--surface-3); }
.workspace-switcher:disabled { cursor:default; opacity:1; }
.workspace-switcher:disabled:hover { background:transparent; }
.workspace-glyph {
  width:28px; height:28px; display:grid; place-items:center; border-radius:7px;
  background:var(--accent-soft); color:var(--accent-hover);
}
.ws-meta { min-width:0; }
.ws-name,.ws-path { display:block; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
.ws-name { color:var(--text-primary); font-size:12px; font-weight:650; }
.ws-path { margin-top:2px; color:var(--text-muted); font:11px var(--font-mono); }
.caret { color:var(--text-muted); }
.mode-tabs {
  height:37px; flex:0 0 37px; display:flex; margin:0 14px 12px; padding:3px;
  border:1px solid transparent; border-radius:9px; background:var(--bg);
}
.mode-tabs button {
  flex:1; border:0; border-radius:5px; background:transparent;
  color:var(--text-muted); font-size:12px;
}
.mode-tabs button.active { background:var(--surface-3); color:var(--text-primary); box-shadow:var(--shadow-sm); }
@media (max-width:760px) {
  .sidebar { width:100%; min-width:0; }
}
</style>
