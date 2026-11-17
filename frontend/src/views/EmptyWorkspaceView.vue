<script setup lang="ts">
import { t } from '../i18n'

function toggleMobileNav() {
  window.dispatchEvent(new Event('harnex-toggle-sidebar'))
}
</script>

<template>
  <div class="empty-workbench">
    <header class="empty-topbar">
      <button class="mobile-nav-button" :title="t('sidebar.openNavigation')"
        :aria-label="t('sidebar.openNavigation')" @click="toggleMobileNav">
        <svg width="17" height="17" viewBox="0 0 24 24" fill="none"
          stroke="currentColor" stroke-width="1.7">
          <path d="M4 6h16M4 12h16M4 18h16" />
        </svg>
      </button>
      <strong>{{ t('chat.emptyTitle') }}</strong>
    </header>
    <section class="empty-conversation" />
    <footer class="inactive-composer">
      <div class="inactive-compose-shell" aria-disabled="true">
        {{ t('workspace.inactiveComposer') }}
      </div>
    </footer>
  </div>
</template>

<style scoped>
.empty-workbench {
  width:100%;
  height:100%;
  display:grid;
  grid-template-rows:55px minmax(0,1fr) auto;
  background:var(--bg);
}
.empty-topbar {
  display:flex; align-items:center; gap:10px; padding:0 20px; border-bottom:1px solid var(--border);
  background:var(--surface-1);
}
.empty-topbar strong { font-size:13px; }
.mobile-nav-button {
  display:none;
  width:32px;
  height:32px;
  place-items:center;
  border-radius:7px;
  color:var(--text-secondary);
}
.mobile-nav-button:hover { background:var(--surface-hover); color:var(--text-primary); }
.empty-conversation { min-height:0; }
.inactive-composer { padding:9px 22px 17px; }
.inactive-compose-shell {
  width:min(var(--content-width), 100%);
  min-height:82px;
  margin:0 auto;
  padding:15px 16px;
  border:1px solid var(--border);
  border-radius:var(--radius-lg);
  background:var(--surface-1);
  color:var(--text-faint);
  font-size:12.5px;
  box-shadow:var(--shadow-md);
}
@media (max-width: 760px) {
  .empty-topbar { padding:0 10px; }
  .mobile-nav-button { display:grid; }
  .inactive-composer { padding:8px 10px 12px; }
}
</style>
