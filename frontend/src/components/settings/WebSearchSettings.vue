<script setup lang="ts">
import ConfigField from './ConfigField.vue'
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { api, getRuntimeRevision } from '../../api/client'
import { useConfigStore } from '../../stores/config'
import { changeSearchService, searchServices, searchSettings, type SearchSettings, type SearchProbeResult } from '../../domain/searchSettings'
import { t } from '../../i18n'

defineProps<{ writable: boolean }>()
const config = useConfigStore()
const settings = computed(() => searchSettings(config.effective.webSearch, config.draft['web-search']))
const selected = computed(() => searchServices.find(service => service.id === settings.value.provider))
const keyless = computed(() => settings.value.provider === 'searxng')
const ready = computed(() => Boolean(selected.value && (keyless.value ? settings.value.endpoint.trim() : settings.value['api-key'].trim())))
const visible = ref(false), busy = ref(false), feedback = ref<SearchProbeResult | null>(null), testedAt = ref('')
const testedFingerprint = ref('')
const fingerprint = computed(() => JSON.stringify([settings.value, config.draft.http ?? config.effective.http]))
const stale = computed(() => Boolean(feedback.value && testedFingerprint.value !== fingerprint.value))
let generation = 0

function update(key: keyof SearchSettings, value: string | boolean | number) {
  config.setSection('web-search', { ...settings.value, [key]: value })
}
function serviceChanged(event: Event) {
  visible.value = false
  config.setSection('web-search', changeSearchService(settings.value, (event.target as HTMLSelectElement).value))
}
const input = (event: Event) => (event.target as HTMLInputElement).value
function link(url: string) {
  try { const value = new URL(url); return ['http:', 'https:'].includes(value.protocol) ? value.href : undefined }
  catch { return undefined }
}
async function testSearch() {
  const request = ++generation, runtime = getRuntimeRevision(), tested = fingerprint.value
  busy.value = true
  try {
    const result = await api.probeWebSearch({ 'web-search': { ...settings.value },
      ...(config.draft.http ? { http: config.draft.http } : {}) })
    if (request !== generation || runtime !== getRuntimeRevision()) return
    feedback.value = result; testedFingerprint.value = tested; testedAt.value = new Date().toLocaleTimeString()
  } catch {
    if (request !== generation || runtime !== getRuntimeRevision()) return
    feedback.value = { success: false, code: 'search_failed', latencyMs: 0, results: [] }
    testedFingerprint.value = tested; testedAt.value = new Date().toLocaleTimeString()
  } finally { if (request === generation) busy.value = false }
}
watch(fingerprint, () => { generation++; busy.value = false }, { flush: 'sync' })
onBeforeUnmount(() => { generation++ })
</script>

<template>
  <div class="search-settings">
    <p class="intro">{{ t('searchSettings.intro') }}</p>
    <article class="search-card">
      <label class="enable-row">
        <input type="checkbox" :checked="settings.enabled" :disabled="!writable"
          @change="update('enabled', ($event.target as HTMLInputElement).checked)" />
        <strong>{{ t('searchSettings.enable') }}</strong>
        <span class="status">{{ t(!settings.enabled ? 'searchSettings.disabled' : ready ? 'searchSettings.configured' : 'searchSettings.incomplete') }}</span>
      </label>
      <fieldset :disabled="!writable">
        <ConfigField path="web-search.provider" v-slot="{ attrs }">{{ t('searchSettings.service') }}
          <select v-bind="attrs" :value="settings.provider" @change="serviceChanged">
            <option v-if="!selected" :value="settings.provider">{{ settings.provider }}</option>
            <option v-for="service in searchServices" :key="service.id" :value="service.id">{{ service.label }}</option>
          </select>
        </ConfigField>
        <label v-if="!keyless">API Key
          <div class="key-field">
            <input :type="visible ? 'text' : 'password'" :value="settings['api-key']" autocomplete="new-password"
              :placeholder="t('searchSettings.keyPlaceholder')" @input="update('api-key', input($event))" />
            <button type="button" :aria-label="t(visible ? 'settings.hideSecret' : 'settings.showSecret')"
              :aria-pressed="visible" @click="visible = !visible">{{ t(visible ? 'settings.hideSecret' : 'settings.showSecret') }}</button>
            <button v-if="settings['api-key']" type="button" @click="update('api-key', '')">{{ t('searchSettings.clear') }}</button>
          </div>
          <small v-if="settings['api-key'] === '••••••••'">{{ t('searchSettings.savedKey') }}</small>
        </label>
        <ConfigField path="web-search.endpoint" v-slot="{ attrs }" v-else>{{ t('searchSettings.instanceUrl') }}
          <input v-bind="attrs" type="url" :value="settings.endpoint" :placeholder="selected?.endpoint" @input="update('endpoint', input($event))" />
          <small>{{ t('searchSettings.searxngHint') }}</small>
        </ConfigField>
        <details>
          <summary>{{ t('searchSettings.advanced') }}</summary>
          <div class="advanced-fields">
            <ConfigField path="web-search.endpoint" v-slot="{ attrs }" v-if="!keyless">{{ t('searchSettings.endpoint') }}
              <input v-bind="attrs" type="url" :value="settings.endpoint" :placeholder="selected?.endpoint" @input="update('endpoint', input($event))" />
              <small>{{ t('searchSettings.endpointHint') }}</small>
            </ConfigField>
            <div class="numbers">
              <ConfigField path="web-search.max-results" v-slot="{ attrs }">{{ t('searchSettings.maxResults') }}
                <input v-bind="attrs" type="number" min="1" :value="settings['max-results']" @input="update('max-results', Number(input($event)))" />
              </ConfigField>
              <ConfigField path="web-search.timeout-ms" v-slot="{ attrs }">{{ t('searchSettings.timeout') }}
                <input v-bind="attrs" type="number" min="1" max="120" :value="settings['timeout-ms'] / 1000" @input="update('timeout-ms', Number(input($event)) * 1000)" />
              </ConfigField>
            </div>
            <ConfigField path="web-search.language" v-slot="{ attrs }" v-if="['anysearch', 'tinyfish'].includes(settings.provider)">{{ t('searchSettings.language') }}
              <select v-bind="attrs" :value="settings.language" @change="update('language', input($event))">
                <option value="">{{ t('searchSettings.serviceDefault') }}</option>
                <option value="zh">中文</option>
                <option value="en">English</option>
                <option v-if="settings.language && !['zh', 'en'].includes(settings.language)" :value="settings.language">{{ settings.language }}</option>
              </select>
              <small v-if="settings.provider === 'tinyfish'">{{ t('searchSettings.tinyfishLanguage') }}</small>
            </ConfigField>
            <small>{{ t('searchSettings.proxyHint') }}</small>
          </div>
        </details>
      </fieldset>
      <div class="test-row">
        <button type="button" :disabled="!writable || !ready || busy" @click="testSearch">
          {{ t(busy ? 'searchSettings.testing' : 'searchSettings.test') }}
        </button>
        <small>{{ t('searchSettings.testHint') }}</small>
      </div>
      <p v-if="!feedback" class="feedback">{{ t('searchSettings.untested') }}</p>
      <p v-else-if="stale" class="feedback" role="status">{{ t('searchSettings.stale') }}</p>
      <template v-else>
        <p class="feedback" :class="{ failure: !feedback.success }" role="status">
          {{ t(`searchSettings.result.${['success', 'not_configured', 'auth_failed', 'quota_exhausted', 'rate_limited', 'timeout', 'invalid_config'].includes(feedback.code) ? feedback.code : 'search_failed'}`) }}
          · {{ testedAt }} · {{ feedback.latencyMs }} ms
        </p>
        <ul v-if="feedback.success && feedback.results.length" class="results">
          <li v-for="(result, index) in feedback.results" :key="index">
            <a v-if="link(result.url)" :href="link(result.url)" target="_blank" rel="noopener noreferrer">{{ result.title || result.url }}</a>
            <span v-else>{{ result.title }}</span>
            <small v-if="result.snippet">{{ result.snippet }}</small>
          </li>
        </ul>
        <small v-else-if="feedback.success">{{ t('searchSettings.emptyResults') }}</small>
      </template>
    </article>
  </div>
</template>

<style scoped>
.search-settings { max-width:760px; }
.intro { margin:0 0 20px; color:var(--text-secondary); font-size:13px; line-height:1.7; }
.search-card { border:1px solid var(--border); border-radius:12px; padding:20px; background:var(--surface-1); }
.enable-row { display:flex; align-items:center; gap:10px; margin-bottom:22px; }
.enable-row input { accent-color:var(--accent); }
.status { margin-left:auto; font-size:12px; color:var(--text-secondary); }
fieldset { padding:0; margin:0; border:0; min-width:0; display:grid; gap:18px; }
fieldset:disabled { opacity:.65; }
fieldset label { display:grid; gap:7px; font-size:13px; }
input:not([type=checkbox]),select { box-sizing:border-box; width:100%; min-width:0; padding:9px 10px; border:1px solid var(--border); border-radius:7px; color:var(--text-primary); background:var(--bg); font:inherit; }
small { color:var(--text-secondary); font-size:12px; line-height:1.6; }
.key-field { display:flex; align-items:center; gap:8px; }
.key-field input { flex:1; }
button { border:1px solid var(--border); border-radius:7px; padding:8px 12px; color:var(--text-primary); background:var(--surface-1); cursor:pointer; white-space:nowrap; }
button:disabled { opacity:.5; cursor:default; }
summary { font-size:13px; color:var(--text-secondary); cursor:pointer; }
.advanced-fields { display:grid; gap:16px; margin-top:16px; }
.numbers { display:grid; grid-template-columns:1fr 1fr; gap:16px; }
.test-row { display:flex; align-items:center; gap:12px; margin-top:22px; flex-wrap:wrap; }
.feedback { margin:14px 0 0; font-size:12px; color:var(--text-secondary); }
.failure { color:var(--danger); }
.results { padding-left:20px; font-size:13px; line-height:1.6; }
.results li { margin-top:10px; overflow-wrap:anywhere; }
.results a { color:var(--accent); }
.results small { display:block; }
@media(max-width:540px) { .search-card { padding:14px; } .enable-row { flex-wrap:wrap; } .numbers { grid-template-columns:1fr; } }
</style>
