<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import Sidebar from './components/Sidebar.vue'
import WorkspaceFailure from './components/WorkspaceFailure.vue'
import RuntimeLoading from './components/RuntimeLoading.vue'
import RemoteUpgradeDialog from './components/RemoteUpgradeDialog.vue'
import ResultPreview from './components/results/ResultPreview.vue'
import HistoryLocationDialog from './components/chat/HistoryLocationDialog.vue'
import TaskHandoffDialog from './components/chat/TaskHandoffDialog.vue'
import { onRuntimeConnection } from './api/client'
import { useScopeNavigation } from './composables/useScopeNavigation'
import { useConnectionNotice } from './composables/useConnectionNotice'
import { useRuntimeStore } from './stores/runtime'
import { useThreadStore } from './stores/threads'
import { useWorkspaceStore } from './stores/workspace'
import { useThemeStore } from './stores/theme'
import { t } from './i18n'
const route = useRoute()
const router = useRouter()
const mobileSidebarOpen = ref(false)
const workspace = useWorkspaceStore()
const runtime = useRuntimeStore()
const threads = useThreadStore()
const theme = useThemeStore()
const { disconnected: runtimeDisconnected, message: runtimeConnectionMessage,
  update: updateRuntimeConnection, reset: resetRuntimeConnection } = useConnectionNotice()
useScopeNavigation(router, resetRuntimeConnection)
let removeRuntimeConnectionListener: (() => void) | null = null
theme.initialize()
function toggleMobileSidebar() {
  mobileSidebarOpen.value = !mobileSidebarOpen.value
}
function closeMobileSidebar() { mobileSidebarOpen.value = false }
onMounted(() => {
  window.addEventListener('harnex-toggle-sidebar', toggleMobileSidebar)
  window.addEventListener('harnex-close-sidebar', closeMobileSidebar)
  removeRuntimeConnectionListener = onRuntimeConnection(updateRuntimeConnection)
})
onUnmounted(() => {
  window.removeEventListener('harnex-toggle-sidebar', toggleMobileSidebar)
  window.removeEventListener('harnex-close-sidebar', closeMobileSidebar)
  removeRuntimeConnectionListener?.()
})
watch(() => route.fullPath, () => { mobileSidebarOpen.value = false })
// Hide the chrome on Settings only. The root route is always the main workbench
// shell, including first run with no registered workspace, so the sidebar's single
// WorkspaceSwitcher entry remains reachable.
const isChromeHidden = computed(() => {
  const p = route.path
  return p === '/settings' || p.endsWith('/settings')
})
const showChrome = computed(() => !isChromeHidden.value)
async function retryWorkspace() {
  if (workspace.profile === 'PERSONAL') { await workspace.openPersonal(true).catch(() => {}); return }
  const id = workspace.failedWorkspaceId || workspace.activeWorkspaceId || workspace.lastWorkspaceId
  if (!id) { workspace.requestOpen(); return }
  if (workspace.workspaces.find(item => item.id === id)?.remoteProfile) { workspace.requestOpen(id); return }
  try { await workspace.open(id) } catch { /* workspace.error renders the recovery surface */ }
}
async function reopenPreviousWorkspace() {
  if (!workspace.previousWorkspaceId) return
  try {
    await workspace.open(workspace.previousWorkspaceId)
    await router.replace('/')
  } catch { /* workspace.error renders the recovery surface */ }
}
async function reconnectRuntime() {
  try {
    if (workspace.profile === 'PERSONAL') await workspace.openPersonal(true)
    else await workspace.refresh()
    await runtime.load({ force: true })
    await threads.refresh()
    resetRuntimeConnection()
  } catch {
    updateRuntimeConnection({ connected: false, message: runtimeConnectionMessage.value })
  }
}
</script>
<template>
  <div class="app" :class="{ 'no-sidebar': !showChrome, 'mobile-sidebar-open': mobileSidebarOpen }">
    <ResultPreview />
    <HistoryLocationDialog />
    <TaskHandoffDialog />
    <button v-if="showChrome" class="mobile-sidebar-scrim" :aria-label="t('sidebar.closeNavigation')" @click="mobileSidebarOpen = false" />
    <Sidebar v-show="showChrome" />
    <RemoteUpgradeDialog v-if="workspace.upgrading" />
    <main class="main">
      <router-view v-slot="{ Component }">
        <KeepAlive include="RootView,AutomationsView">
          <component :is="Component" />
        </KeepAlive>
      </router-view>
    </main>
    <div v-if="showChrome && workspace.ready && runtimeDisconnected && !workspace.switching"
      class="runtime-disconnected" role="alert">
      <span><strong>{{ t('runtime.disconnected') }}</strong>{{ runtimeConnectionMessage }}</span>
      <button type="button" @click="reconnectRuntime">{{ t('runtime.reconnect') }}</button>
      <button type="button" @click="$router.push('/settings?section=diagnostics')">{{ t('workspace.diagnostics') }}</button>
    </div>
    <div v-if="showChrome && workspace.switching && !workspace.resourceReady && !workspace.error" class="workspace-transition" role="status" aria-live="polite">
      <RuntimeLoading :profile="workspace.loadingProfile" :phase="workspace.loadingPhase" />
    </div>
    <div v-else-if="showChrome && workspace.error" class="workspace-failure" role="alert">
      <WorkspaceFailure :message="workspace.error" :personal="workspace.profile === 'PERSONAL'"
        :remote="workspace.profile === 'PROJECT' && Boolean(workspace.active?.remoteProfile)" />
      <button type="button" @click="retryWorkspace">{{ t('workspace.retry') }}</button>
      <button v-if="workspace.profile === 'PROJECT'" type="button" @click="workspace.requestOpen()">{{ t('workspace.chooseAnother') }}</button>
      <button v-if="workspace.profile === 'PROJECT' && workspace.previousWorkspaceId" type="button"
        @click="reopenPreviousWorkspace">{{ t('workspace.reopenPrevious') }}</button>
      <button type="button" @click="$router.push('/settings?section=diagnostics')">{{ t('workspace.diagnostics') }}</button>
    </div>
  </div>
</template>
