<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import BrandLogo from '../components/BrandLogo.vue'
import ControlPlaneSettings from '../components/settings/ControlPlaneSettings.vue'
import ImSettings from '../components/settings/ImSettings.vue'
import RemoteRuntimeSettings from '../components/settings/RemoteRuntimeSettings.vue'
import { locale, t, type Locale } from '../i18n'
import type { Theme } from '../domain/themes'
import { useThemeStore } from '../stores/theme'
import { useWorkspaceStore } from '../stores/workspace'
import { useSettings } from '../stores/settings'
import { primaryModifierLabel } from '../utils/platform'

type ControlTab = 'providers' | 'images' | 'pricing' | 'network' | 'safety' | 'toolsets' | 'search' | 'memory' | 'skills' | 'integrations' | 'diagnostics'
type Section = Exclude<ControlTab, 'providers' | 'images'> | 'models' | 'behavior' | 'appearance' | 'im'
type ThemeOption = {
  key: Theme
  label: string
  desc: string
  preview: string[]
  followsSystem?: boolean
}

const router = useRouter()
const route = useRoute()
const themeStore = useThemeStore()
const workspace = useWorkspaceStore()
const settings = useSettings()
const modifiedEnter = `${primaryModifierLabel()}+Enter`
themeStore.initialize()
const section = ref<Section>('appearance')
const modelTab = ref<'providers' | 'images'>('providers')
const languages: Array<{ key: Locale; label: string }> = [
  { key: 'zh-CN', label: '简体中文' },
  { key: 'en', label: 'English' },
]
const themes = computed<ThemeOption[]>(() => [
  { key: 'system', label: t('settings.themeSystem'), desc: t('settings.themeSystemDesc'), preview: ['#F3F6FA', '#232731', '#4B7AB2'], followsSystem: true },
  { key: 'dark', label: t('settings.themeDark'), desc: t('settings.themeDarkDesc'), preview: ['#232731', '#2E3440', '#88C0D0'] },
  { key: 'light', label: t('settings.themeLight'), desc: t('settings.themeLightDesc'), preview: ['#F3F6FA', '#E8EEF5', '#4B7AB2'] },
  { key: 'liuguang', label: t('settings.themeLiuguang'), desc: t('settings.themeLiuguangDesc'), preview: ['#BFDDF4', '#DCD4F4', '#2574E8'], followsSystem: true },
  { key: 'changan', label: t('settings.themeChangan'), desc: t('settings.themeChanganDesc'), preview: ['#F6EBDC', '#2C2420', '#9E2B25'], followsSystem: true },
  { key: 'yanzhi', label: t('settings.themeYanzhi'), desc: t('settings.themeYanzhiDesc'), preview: ['#FFF8F6', '#EEEAF5', '#B05479'], followsSystem: true },
  { key: 'vitesse', label: t('settings.themeVitesse'), desc: t('settings.themeVitesseDesc'), preview: ['#F1F0E9', '#222222', '#4D9375'], followsSystem: true },
])

const nav = computed<Array<{ key: Section; label: string; group?: string }>>(() => [
  { key: 'appearance', label: t('settings.navAppearance') },
  { key: 'models', label: t('settings.models') },
  { key: 'pricing', label: t('settings.pricing') },
  { key: 'network', label: t('settings.navNetwork') },
  { key: 'safety', label: t('settings.navSafety') },
  { key: 'behavior', label: t('settings.navBehavior') },
  { key: 'memory', label: t('settings.navMemory') },
  { key: 'toolsets', label: t('settings.navToolsets'), group: t('settings.groupTools') },
  { key: 'search', label: t('searchSettings.title') },
  { key: 'skills', label: t('settings.navSkills') },
  { key: 'integrations', label: t('settings.navIntegrations') },
  { key: 'im', label: t('settings.navIm') },
  { key: 'diagnostics', label: t('settings.navDiagnostics'), group: t('settings.groupSystem') },
])

const collapseSuccess = ref(localStorage.getItem('harnex_collapse_success') !== '0')
const restoreBeforeEdit = ref(localStorage.getItem('harnex_restore_before_edit') !== '0')

const title = computed(() => nav.value.find(item => item.key === section.value)?.label || t('settings.title'))
const description = computed(() => ({
  models: t('settings.modelsDescription'),
  pricing: t('imageSettings.pricingHint'),
  network: t('settings.descNetwork'),
  safety: t('settings.descSafety'),
  behavior: t('settings.descBehavior'),
  memory: t('settings.descMemory'),
  appearance: t('settings.descAppearance'),
  toolsets: t('settings.descToolsets'),
  search: t('searchSettings.description'),
  skills: t('settings.descSkills'),
  integrations: t('settings.descIntegrations'),
  im: t('settings.descIm'),
  diagnostics: t('settings.descDiagnostics'),
})[section.value])

const controlSection = computed<ControlTab | null>(() =>
  section.value === 'models' ? modelTab.value : ['pricing', 'network', 'safety', 'toolsets', 'search', 'memory', 'skills', 'integrations', 'diagnostics'].includes(section.value)
    ? section.value as ControlTab
    : null,
)

function select(next: Section) {
  section.value = next
  void router.replace({ path: '/settings', query: { section: next, ...(next === 'models' ? { tab: modelTab.value === 'images' ? 'images' : 'language' } : {}) } })
  document.querySelector('.system-content')?.scrollTo({ top: 0, behavior: 'smooth' })
}

function backWorkbench() {
  const target = sessionStorage.getItem('harnex_settings_return') || '/'
  router.push(target)
}

function saveBehavior() {
  localStorage.setItem('harnex_collapse_success', collapseSuccess.value ? '1' : '0')
  localStorage.setItem('harnex_restore_before_edit', restoreBeforeEdit.value ? '1' : '0')
}

function selectModelTab(next: 'providers' | 'images') { modelTab.value = next; select('models') }
function selectControlTab(next: ControlTab) {
  if (next === 'providers' || next === 'images') selectModelTab(next)
  else select(next)
}
watch(() => route.query, query => {
  const requested = String(query.section || 'appearance')
  if (nav.value.some(item => item.key === requested)) {
    section.value = requested as Section
    if (requested === 'models') modelTab.value = query.tab === 'images' ? 'images' : 'providers'
  }
}, { immediate: true })
</script>

<template>
  <div class="system-shell">
    <aside class="system-nav">
      <div class="system-brand"><BrandLogo size="sm" /><strong>Harnex</strong></div>
      <button class="back-button" @click="backWorkbench">
        <span aria-hidden="true">←</span><span>{{ t('settings.backWorkspace') }}</span>
      </button>
      <div class="system-title">
        <h1>{{ t('settings.title') }}</h1>
        <p>{{ workspace.profile === 'PROJECT' && workspace.active?.remoteProfile ? `SSH ${workspace.active.remoteHost || workspace.active.remoteProfile} · ${workspace.active.root}` : t('settings.userScope') }}</p>
      </div>
      <nav :aria-label="t('settings.sectionsLabel')">
        <template v-for="item in nav" :key="item.key">
          <div v-if="item.group" class="system-group">{{ item.group }}</div>
          <button :class="{ active: section === item.key }" @click="select(item.key)">
            <span class="nav-mark" aria-hidden="true" />
            <span>{{ item.label }}</span>
          </button>
        </template>
      </nav>
    </aside>

    <main class="system-content">
      <div class="settings-column">
        <header class="system-head">
          <div>
            <button class="mobile-back" @click="backWorkbench">← {{ t('settings.backWorkspace') }}</button>
            <select class="mobile-section-nav" :value="section" :aria-label="t('settings.sectionsLabel')" @change="select(($event.target as HTMLSelectElement).value as Section)">
              <option v-for="item in nav" :key="item.key" :value="item.key">{{ item.label }}</option>
            </select>
            <h2>{{ title }}</h2>
            <p>{{ description }}</p>
          </div>
        </header>

        <nav v-if="section === 'models'" class="model-tabs" role="tablist" :aria-label="t('settings.models')">
          <button id="language-model-tab" role="tab" :aria-selected="modelTab === 'providers'" aria-controls="model-settings-panel" @click="selectModelTab('providers')">{{ t('settings.languageModels') }}</button>
          <button id="image-model-tab" role="tab" :aria-selected="modelTab === 'images'" aria-controls="model-settings-panel" @click="selectModelTab('images')">{{ t('imageSettings.title') }}</button>
        </nav>

        <section v-if="section === 'im' && (!workspace.ready || workspace.profile !== 'PROJECT')" class="settings-surface workspace-required">
          <strong>{{ t('settings.imProjectRequired') }}</strong>
          <p v-if="workspace.error" role="alert">{{ workspace.error }}</p>
          <button class="primary-button" @click="backWorkbench">{{ t('settings.backWorkspace') }}</button>
        </section>
        <template v-else-if="section === 'im'">
          <RemoteRuntimeSettings :key="`remote:${workspace.scopeId}:${workspace.scopeInstanceId}`" />
          <ImSettings :key="`im:${workspace.scopeId}:${workspace.scopeInstanceId}`" />
        </template>
        <section v-else-if="controlSection && !workspace.ready" class="settings-surface workspace-required">
          <strong>{{ t('settings.workspaceRequired') }}</strong>
          <p>{{ t('settings.workspaceRequiredDesc') }}</p>
          <button class="primary-button" @click="backWorkbench">{{ t('settings.backWorkspace') }}</button>
        </section>
        <ControlPlaneSettings v-else-if="controlSection" id="model-settings-panel" :role="section === 'models' ? 'tabpanel' : undefined" :aria-labelledby="section === 'models' ? modelTab === 'images' ? 'image-model-tab' : 'language-model-tab' : undefined" :key="`${workspace.scopeId}:${workspace.scopeInstanceId}`" :active-tab="controlSection" @navigate-tab="selectControlTab" />

        <section v-else-if="section === 'behavior'" class="settings-surface">
          <div class="setting-row shortcut-setting">
            <div id="send-shortcut-label"><strong>{{ t('settings.sendShortcut') }}</strong><small>{{ t('settings.sendShortcutDesc') }}</small></div>
            <div class="shortcut-options" role="radiogroup" aria-labelledby="send-shortcut-label">
              <label :class="{ active: settings.sendShortcut === 'enter' }">
                <input v-model="settings.sendShortcut" type="radio" name="send-shortcut" value="enter" />
                <span>{{ t('settings.shortcutOption', { newline: 'Shift+Enter', send: 'Enter' }) }}</span>
              </label>
              <label :class="{ active: settings.sendShortcut === 'ctrl-enter' }">
                <input v-model="settings.sendShortcut" type="radio" name="send-shortcut" value="ctrl-enter" />
                <span>{{ t('settings.shortcutOption', { newline: 'Enter', send: modifiedEnter }) }}</span>
              </label>
            </div>
          </div>
          <div class="setting-row">
            <div><strong>{{ t('settings.collapseSuccess') }}</strong><small>{{ t('settings.collapseSuccessDesc') }}</small></div>
            <button class="toggle" :class="{ on: collapseSuccess }" role="switch"
              :aria-label="t('settings.collapseSuccess')" :aria-checked="collapseSuccess"
              @click="collapseSuccess = !collapseSuccess; saveBehavior()" />
          </div>
          <div class="setting-row">
            <div><strong>{{ t('settings.restoreBeforeEdit') }}</strong><small>{{ t('settings.restoreBeforeEditDesc') }}</small></div>
            <button class="toggle" :class="{ on: restoreBeforeEdit }" role="switch"
              :aria-label="t('settings.restoreBeforeEdit')" :aria-checked="restoreBeforeEdit"
              @click="restoreBeforeEdit = !restoreBeforeEdit; saveBehavior()" />
          </div>
          <div class="setting-row">
            <div><strong>{{ t('settings.defaultModelToolset') }}</strong><small>{{ t('settings.defaultModelToolsetDesc') }}</small></div>
            <button class="quiet-button" @click="selectModelTab('providers')">{{ t('settings.gotoConfig') }}</button>
          </div>
        </section>

        <section v-else-if="section === 'appearance'" class="appearance-section">
          <article class="settings-surface">
            <div class="setting-row">
              <div><strong>{{ t('settings.language') }}</strong><small>{{ t('settings.languageDesc') }}</small></div>
              <div class="segmented">
                <button v-for="language in languages" :key="language.key"
                  :class="{ active: locale === language.key }" :aria-pressed="locale === language.key"
                  @click="locale = language.key">{{ language.label }}</button>
              </div>
            </div>
          </article>
          <article class="settings-surface">
            <div class="setting-row">
              <label for="user-role-name"><strong>{{ t('settings.userRoleName') }}</strong><small>{{ t('settings.roleNameDesc') }}</small></label>
              <input id="user-role-name" v-model="settings.userRoleName" class="setting-input" type="text" maxlength="32" :placeholder="t('message.role.user')" />
            </div>
            <div class="setting-row">
              <label for="assistant-role-name"><strong>{{ t('settings.assistantRoleName') }}</strong><small>{{ t('settings.roleNameDesc') }}</small></label>
              <input id="assistant-role-name" v-model="settings.assistantRoleName" class="setting-input" type="text" maxlength="32" :placeholder="t('message.role.assistant')" />
            </div>
          </article>
          <p class="theme-follow-hint">{{ t('settings.themeFollowHint') }}</p>
          <div class="theme-grid">
            <button v-for="theme in themes" :key="theme.key" class="theme-card"
              :class="{ active: themeStore.theme === theme.key, 'theme-system': theme.key === 'system' }"
              :aria-pressed="themeStore.theme === theme.key" @click="themeStore.setTheme(theme.key)">
              <span class="theme-preview" aria-hidden="true">
                <span v-for="color in theme.preview" :key="color" :style="{ background: color }" />
              </span>
              <span class="theme-copy">
                <span class="theme-title">
                  <strong>{{ theme.label }}</strong>
                  <span v-if="theme.followsSystem" class="theme-adaptive">{{ t('settings.themeAdaptive') }}</span>
                </span>
                <small>{{ theme.desc }}</small>
                <small v-if="theme.followsSystem && themeStore.theme === theme.key" class="theme-effective">
                  {{ themeStore.mode === 'dark' ? t('settings.themeCurrentDark') : t('settings.themeCurrentLight') }}
                </small>
              </span>
              <span class="theme-check">{{ themeStore.theme === theme.key ? '✓' : '' }}</span>
            </button>
          </div>
        </section>

      </div>
    </main>
  </div>
</template>

<style scoped>
.mobile-section-nav { display:none; width:100%; margin:0 0 18px; padding:9px 12px; border:1px solid var(--border); border-radius:8px; background:var(--surface-1); color:var(--text-primary); }
@media(max-width:760px) { .mobile-section-nav { display:block; } }
.model-tabs { display:flex; gap:8px; margin:0 0 24px; border-bottom:1px solid var(--border); }
.model-tabs button { padding:12px 18px; border:0; border-bottom:2px solid transparent; background:transparent; color:var(--text-secondary); cursor:pointer; }
.model-tabs button[aria-selected=true] { color:var(--text-primary); border-bottom-color:var(--accent); font-weight:600; }
.system-shell {
  width:100%;
  height:100%;
  min-width:0;
  min-height:0;
  display:grid;
  grid-template-columns:238px minmax(0,1fr);
  overflow:hidden;
  background:var(--bg);
}
.system-nav {
  min-height:0;
  overflow-y:auto;
  border-right:1px solid var(--border);
  background:var(--surface-1);
  padding-bottom:20px;
}
.system-brand {
  height:56px;
  display:flex;
  align-items:center;
  gap:10px;
  padding:0 15px;
  border-bottom:1px solid var(--border);
  font-size:18px;
}
.back-button {
  width:calc(100% - 18px);
  height:34px;
  display:flex;
  align-items:center;
  gap:8px;
  margin:9px;
  padding:0 9px;
  border-radius:7px;
  background:transparent;
  color:var(--text-muted);
  font-size:12px;
  text-align:left;
}
.back-button:hover { background:var(--surface-hover); color:var(--text-secondary); }
.system-title { padding:10px 17px 12px; }
.system-title h1 { font-size:18px; letter-spacing:-.02em; }
.system-title p { margin-top:3px; color:var(--text-muted); font-size:12px; }
.system-nav nav { padding:0 8px; }
.system-nav nav button {
  width:100%;
  height:34px;
  display:flex;
  align-items:center;
  gap:9px;
  padding:0 9px;
  border-radius:7px;
  background:transparent;
  color:var(--text-muted);
  text-align:left;
  font-size:12px;
}
.system-nav nav button:hover { background:var(--surface-hover); color:var(--text-secondary); }
.system-nav nav button.active { background:var(--accent-soft); color:var(--text-primary); }
.nav-mark { width:5px; height:5px; border-radius:50%; background:var(--text-faint); }
.system-nav nav button.active .nav-mark { background:var(--accent); }
.system-group { padding:14px 9px 5px; color:var(--text-faint); font-size:12px; font-weight:700; letter-spacing:.07em; text-transform:uppercase; }
.system-content { min-width:0; min-height:0; overflow-y:auto; }
.settings-column { width:min(740px, calc(100% - 48px)); margin:0 auto; padding:42px 0 96px; }
.system-head {
  min-height:64px;
  display:flex;
  align-items:flex-start;
  justify-content:space-between;
  gap:18px;
  margin-bottom:22px;
  border-bottom:1px solid var(--border);
  padding-bottom:18px;
}
.system-head h2 { font-size:22px; font-weight:650; letter-spacing:-.025em; }
.system-head p { max-width:580px; margin-top:5px; color:var(--text-muted); font-size:12px; }
.settings-surface { border:1px solid var(--border); border-radius:var(--radius-lg); background:var(--surface-1); overflow:hidden; }
.workspace-required { padding:22px; }
.workspace-required p { margin:7px 0 16px; color:var(--text-muted); font-size:12px; }
.setting-row {
  min-height:68px;
  display:grid;
  grid-template-columns:minmax(220px,1fr) auto;
  align-items:center;
  gap:22px;
  padding:16px 20px;
  border-bottom:1px solid var(--border);
}
.setting-row:last-child { border-bottom:0; }
.setting-row strong,.setting-row small { display:block; }
.setting-row strong { font-size:12px; }
.setting-row small { margin-top:3px; color:var(--text-muted); font-size:12px; }
.shortcut-setting { grid-template-columns:minmax(0,1fr); gap:12px; }
.shortcut-options { display:grid; gap:8px; }
.shortcut-options label { display:flex; align-items:center; gap:10px; padding:10px 12px; border:1px solid var(--border); border-radius:7px; cursor:pointer; font-size:12px; }
.shortcut-options label.active { border-color:var(--accent); background:var(--accent-soft); }
.shortcut-options input { flex:none; width:auto; accent-color:var(--accent); }
.setting-input { width:220px; max-width:100%; min-width:0; padding:8px 10px; border:1px solid var(--border); border-radius:7px; background:var(--bg); color:var(--text-primary); font:inherit; font-size:13px; }
.toggle { position:relative; width:34px; height:19px; border-radius:10px; background:var(--surface-3); }
.toggle::after { content:""; position:absolute; top:3px; left:3px; width:13px; height:13px; border-radius:50%; background:var(--text-muted); transition:transform var(--transition); }
.toggle.on { background:var(--accent-soft); }
.toggle.on::after { background:var(--accent); transform:translateX(15px); }
.quiet-button,.primary-button,.danger-button {
  min-height:30px;
  border:1px solid var(--border);
  border-radius:6px;
  padding:0 10px;
  background:var(--surface-2);
  color:var(--text-secondary);
  font-size:12px;
}
.primary-button { border-color:var(--accent); background:var(--accent); color:var(--accent-contrast); }
.danger-button { border-color:transparent; background:var(--danger-soft); color:var(--danger); }
.segmented { display:flex; padding:3px; border:1px solid var(--border); border-radius:7px; background:var(--bg); }
.segmented button { height:27px; border-radius:5px; background:transparent; padding:0 9px; color:var(--text-muted); font-size:12px; }
.segmented button.active { background:var(--surface-3); color:var(--text-primary); }
.appearance-section { display:grid; gap:14px; }
.theme-follow-hint {
  margin:0 2px -2px;
  color:var(--text-muted);
  font-size:12px;
  line-height:1.55;
}
.theme-grid { display:grid; grid-template-columns:repeat(2,minmax(0,1fr)); gap:8px; }
.theme-card {
  min-height:108px;
  display:grid;
  grid-template-columns:1fr auto;
  gap:10px;
  border:1px solid var(--border);
  border-radius:9px;
  background:var(--surface-1);
  padding:12px;
  text-align:left;
}
.theme-card:hover { border-color:var(--border-strong); background:var(--surface-hover); }
.theme-card.active { border-color:color-mix(in srgb,var(--accent) 55%,var(--border)); background:var(--accent-soft); }
.theme-preview { grid-column:1/-1; display:flex; gap:5px; }
.theme-preview span { width:26px; height:17px; border:1px solid color-mix(in srgb,var(--border-strong) 70%,transparent); border-radius:5px; }
.theme-system { grid-column:1/-1; grid-template-columns:auto minmax(0,1fr) auto; align-items:center; min-height:80px; }
.theme-system .theme-preview { grid-column:auto; }
.theme-copy { min-width:0; }
.theme-copy strong,.theme-copy small { display:block; }
.theme-title { display:flex; align-items:center; gap:6px; }
.theme-copy strong { font-size:12px; }
.theme-copy small {
  display:-webkit-box;
  margin-top:3px;
  overflow:hidden;
  color:var(--text-muted);
  font-size:12px;
  line-height:1.4;
  -webkit-box-orient:vertical;
  -webkit-line-clamp:2;
}
.theme-adaptive {
  border:1px solid color-mix(in srgb,var(--accent) 35%,var(--border));
  border-radius:999px;
  background:var(--accent-soft);
  padding:1px 5px;
  color:var(--accent);
  font-size:12px;
  line-height:1.3;
}
.theme-copy .theme-effective { color:var(--accent); -webkit-line-clamp:1; }
.theme-check { color:var(--accent); }
.mobile-back { display:none; margin-bottom:12px; background:transparent; color:var(--text-muted); font-size:12px; }

@media (max-width:760px) {
  .system-shell { grid-template-columns:1fr; }
  .system-nav { display:none; }
  .settings-column { width:min(740px,calc(100% - 28px)); padding-top:26px; }
  .theme-grid { grid-template-columns:1fr; }
  .theme-system { grid-template-columns:minmax(0,1fr) auto; min-height:108px; }
  .theme-system .theme-preview { grid-column:1/-1; }
  .setting-row { grid-template-columns:1fr; gap:10px; }
  .mobile-back { display:block; }
}
</style>
