<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import ChatView from './ChatView.vue'
import EmptyWorkspaceView from './EmptyWorkspaceView.vue'
import RuntimeLoading from '../components/RuntimeLoading.vue'
import { useWorkspaceStore } from '../stores/workspace'
import { errorMessage } from '../utils/unknown'

const workspace = useWorkspaceStore()
const checking = ref(true)

// A workspace restore/open is still in flight (desktop: the Supervisor reopens the
// last workspace right after the JVM boots, and READY arrives via lifecycle SSE a
// moment after this view mounts). Keep the neutral loading surface during that gap
// instead of flashing the empty-workbench ("picker") state before ChatView.
// NOTE: keyed strictly on in-flight signals (switching / OPENING). Persistent ids
// like lastWorkspaceId must NOT hold this surface, otherwise an explicitly closed
// workspace would leave the web UI stuck on a blank loader forever.
const pendingRestore = computed(() =>
  workspace.available
  && !workspace.ready
  && !workspace.error
  && workspace.scopeLifecycle !== 'FAILED'
  && (workspace.switching || workspace.scopeLifecycle === 'OPENING'),
)

onMounted(async () => {
  try {
    await workspace.bootstrap()
    checking.value = false
    if (workspace.available) {
      await workspace.restoreMostRecent()
    }
  } catch (cause) {
    workspace.error ||= errorMessage(cause)
  } finally {
    checking.value = false
  }
})
</script>

<template>
  <!-- Runtime readiness owns the visible workbench; a pending bootstrap promise
       must not hide a scope already made ready by the host. -->
  <ChatView v-if="workspace.resourceReady" />
  <div v-else-if="checking || pendingRestore" class="root-loading" role="status" aria-live="polite">
    <RuntimeLoading :profile="workspace.loadingProfile" :phase="workspace.loadingPhase" />
  </div>
  <EmptyWorkspaceView v-else />
</template>

<style scoped>
.root-loading { width:100%; height:100%; display:grid; place-items:center; background:var(--bg); color:var(--text-muted); font-size:14px; }
</style>
