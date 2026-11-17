import type { RouteRecordRaw } from 'vue-router'
import RootView from './views/RootView.vue'
import SettingsView from './views/SettingsView.vue'
import AutomationsView from './views/AutomationsView.vue'

// Both URLs share the same cached workbench and composer instance.
export const workbenchRoutes: RouteRecordRaw[] = [
  { path: '/', component: RootView },
  { path: '/t/:tid', component: RootView },
  { path: '/automations', component: AutomationsView },
  { path: '/settings', component: SettingsView },
  // Hosts serve the SPA for page URLs too. An unmatched URL must still mount a workbench.
  { path: '/:pathMatch(.*)*', redirect: to => ({ path: '/', query: to.query, hash: to.hash }) },
]
