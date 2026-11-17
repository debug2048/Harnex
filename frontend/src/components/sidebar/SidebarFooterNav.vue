<script setup lang="ts">
import { onUnmounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { t } from '../../i18n'

defineProps<{ activeWorkspace: boolean }>()

const route = useRoute()
const router = useRouter()
let lastWorkbenchPath = route.path === '/settings' ? '/' : route.fullPath
const removeRouteHook = router.afterEach(to => {
  if (to.path !== '/settings') lastWorkbenchPath = to.fullPath
})

function openAutomations() {
  void router.push('/automations')
}

function openSettings() {
  sessionStorage.setItem('harnex_settings_return', lastWorkbenchPath || '/')
  void router.push('/settings')
}

onUnmounted(removeRouteHook)
</script>

<template>
  <nav class="sidebar-footer" :aria-label="t('sidebar.secondaryNav')">
    <button class="sidebar-link"
      :class="{ active: route.path.endsWith('/automations') }" @click="openAutomations">
      <svg width="14" height="14" viewBox="0 0 24 24" fill="none"
        stroke="currentColor" stroke-width="1.7">
        <circle cx="12" cy="12" r="8" /><path d="M12 7v5l3 2" />
      </svg>
      <span>{{ t('sidebar.automations') }}</span>
    </button>
    <button class="sidebar-link" :title="t('sidebar.settings')" @click="openSettings">
      <svg width="14" height="14" viewBox="0 0 24 24" fill="none"
        stroke="currentColor" stroke-width="2" stroke-linecap="round">
        <circle cx="12" cy="12" r="3"/>
        <path d="M12 1v2M12 21v2M4.22 4.22l1.42 1.42M18.36 18.36l1.42 1.42M1 12h2M21 12h2M4.22 19.78l1.42-1.42M18.36 5.64l1.42-1.42"/>
      </svg>
      <span>{{ t('sidebar.settings') }}</span>
    </button>
  </nav>
</template>

<style scoped>
.sidebar-footer { flex:0 0 auto; padding:10px 14px; border-top:1px solid var(--border-soft); }
.sidebar-link {
  width:100%; height:35px; display:flex; align-items:center; gap:9px; padding:0 9px;
  border:0; border-radius:7px; background:transparent; color:var(--text-muted);
  font-size:12px; text-align:left;
}
.sidebar-link span { flex:1; }
.sidebar-link:hover,.sidebar-link.active {
  background:var(--surface-hover); color:var(--text-secondary);
}
</style>
