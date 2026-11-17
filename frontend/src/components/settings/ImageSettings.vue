<script setup lang="ts">
import ConfigField from './ConfigField.vue'
import { computed, nextTick, onMounted, onBeforeUnmount, ref, watch } from 'vue'
import { api, getRuntimeRevision, revealNativeProviderSecret } from '../../api/client'
import type { Catalog } from '../../../../packages/protocol/workbench'
import { useConfigStore } from '../../stores/config'
import { IMAGE_PROFILES, changeImageProfile, imageSettingsMap, independentImageConnection, newImageModel, imageModelLabel, normalizeImageSettings } from '../../domain/imageSettings'
import { isRecord, errorMessage, type UnknownRecord } from '../../utils/unknown'
import { t } from '../../i18n'
import { onConfigurationChanged } from '../../domain/configEvents'
import { imageFailureKey } from '../../domain/imageFailure'

defineProps<{ writable: boolean }>()
const config = useConfigStore()
const services = computed(() => imageSettingsMap(Object.prototype.hasOwnProperty.call(config.draft, 'image-services')
  ? config.draft['image-services'] : config.effective.imageServices))
const providers = computed(() => imageSettingsMap(Object.prototype.hasOwnProperty.call(config.draft, 'providers')
  ? config.draft.providers : config.effective.providers))
const defaultId = computed(() => String(config.draft['default-image-service'] ?? config.effective.defaultImageService ?? ''))
const catalog = ref<Catalog | null>(null), error = ref(''), busy = ref(false)
const panel = ref<HTMLElement | null>(null)
const visibleKeys = ref<Record<string, boolean>>({})
const revealedKeys = ref<Record<string, string>>({})
const revealingKeys = ref<Record<string, number>>({})
let keySequence = 0
const importing = ref<string | null>(null), importId = ref<string | null>(null), importProvider = ref('')
const renaming = ref<string | null>(null)
let generation = 0
const state = (id: string) => catalog.value?.services?.find(service => service.serviceId === id)
const label = (service: UnknownRecord) => imageModelLabel(service) || t('imageSettings.unnamed')
const text = (event: Event) => (event.target as HTMLInputElement).value
const storedKey = (value: unknown) => value === '••••••••' || String(value || '').startsWith('${HARNEX_KEYCHAIN_')
function clearKeys() {
  visibleKeys.value = {}; revealedKeys.value = {}; revealingKeys.value = {}
}
watch(() => config.document, clearKeys, { flush: 'sync' })
watch(services, (next, previous) => {
  for (const id of Object.keys(previous)) {
    if (next[id]?.['api-key'] === previous[id]['api-key'] && next[id]) continue
    delete revealedKeys.value[id]; delete revealingKeys.value[id]
    if (!next[id] || storedKey(next[id]['api-key'])) delete visibleKeys.value[id]
  }
}, { flush: 'sync' })
async function toggleKey(id: string) {
  if (revealingKeys.value[id]) return
  if (visibleKeys.value[id]) {
    delete visibleKeys.value[id]; delete revealedKeys.value[id]
    return
  }
  const configured = services.value[id]?.['api-key']
  if (!storedKey(configured)) { visibleKeys.value[id] = true; return }
  const sequence = ++keySequence, runtime = getRuntimeRevision()
  const current = () => revealingKeys.value[id] === sequence && runtime === getRuntimeRevision()
    && !!services.value[id] && services.value[id]['api-key'] === configured
  revealingKeys.value[id] = sequence; error.value = ''
  try {
    const nativeKey = await revealNativeProviderSecret(`image:${id}`)
    if (!current()) return
    const key = nativeKey ?? (await api.revealImageServiceApiKey(id)).apiKey
    if (!current()) return
    if (!key || storedKey(key)) throw new Error(t('imageSettings.importKeyMissing'))
    revealedKeys.value[id] = key; visibleKeys.value[id] = true
  } catch (cause) { if (current()) error.value = errorMessage(cause) }
  finally { if (revealingKeys.value[id] === sequence) delete revealingKeys.value[id] }
}
function update(id: string, key: string, value: unknown) {
  config.setSection('image-services', { ...services.value, [id]: { ...services.value[id], [key]: value } })
}
function updateProfile(id: string, profile: string) {
  config.setSection('image-services', { ...services.value, [id]: changeImageProfile(services.value[id], profile) })
}
async function add() {
  const { id, value } = newImageModel()
  config.setSection('image-services', { ...services.value, [id]: value })
  if (!defaultId.value) config.setSection('default-image-service', id)
  await nextTick()
  const card = panel.value?.querySelector<HTMLElement>(`[data-service-id="${id}"]`)
  card?.scrollIntoView({ block: 'nearest' })
  card?.querySelector<HTMLInputElement>('input[data-model]')?.focus()
}
function remove(id: string) {
  const next = { ...services.value }; delete next[id]
  config.setSection('image-services', next)
  if (defaultId.value === id) config.setSection('default-image-service', Object.keys(next).find(key => next[key].enabled !== false) || '')
}
function chooseImport(id: string) {
  importId.value = id
  importProvider.value = Object.keys(providers.value)[0] || ''
}
async function copyConnection(id: string, providerId: string) {
  const source = providers.value[providerId]
  if (!source || importing.value) return
  const version = ++generation, runtime = getRuntimeRevision()
  importing.value = id; error.value = ''
  try {
    let key = String(source['api-key'] || '')
    if (key === '••••••••' || key.startsWith('${HARNEX_KEYCHAIN_')) {
      const nativeKey = await revealNativeProviderSecret(providerId)
      if (version !== generation || runtime !== getRuntimeRevision()) return
      key = nativeKey ?? (await api.revealProviderApiKey(providerId)).apiKey
      if (!key || key === '••••••••') throw new Error(t('imageSettings.importKeyMissing'))
    }
    if (version !== generation || runtime !== getRuntimeRevision() || !services.value[id]) return
    const profiles = isRecord(config.effective.providerProfileDefaults) ? config.effective.providerProfileDefaults : {}
    const defaults = normalizeImageSettings(profiles[String(source.profile || '')])
    const connection = independentImageConnection(services.value[id], source, defaults, key)
    if (!String(connection['base-url']).trim()) throw new Error(t('imageSettings.importUrlMissing'))
    config.setSection('image-services', { ...services.value, [id]: connection })
    importId.value = null
  } catch (cause) { if (version === generation) error.value = errorMessage(cause) }
  finally { if (version === generation) importing.value = null }
}
async function refresh() {
  const runtime = getRuntimeRevision(); busy.value = true
  try { const next = await api.imageServices(); if (runtime === getRuntimeRevision()) catalog.value = next }
  catch (cause) { if (runtime === getRuntimeRevision()) error.value = errorMessage(cause) }
  finally { busy.value = false }
}
const unsubscribeConfiguration = onConfigurationChanged(() => { void refresh() })
onMounted(refresh)
onBeforeUnmount(() => { generation++; clearKeys(); unsubscribeConfiguration() })
</script>

<template>
  <div ref="panel" class="image-settings">
    <p>{{ t('imageSettings.intro') }}</p>
    <div class="toolbar">
      <button class="add-model" :disabled="!writable" @click="add">＋ {{ t('imageSettings.add') }}</button>
      <button :disabled="busy" @click="refresh">{{ t('imageSettings.refresh') }}</button>
    </div>
    <p v-if="error" role="alert">{{ error }}</p>
    <p v-if="!Object.keys(services).length" class="empty-state">{{ t('imageSettings.empty') }}</p>
    <label v-else-if="Object.keys(services).length > 1" class="default-service">{{ t('imageSettings.default') }}
      <select :value="defaultId" :disabled="!writable" @change="config.setSection('default-image-service', text($event))">
        <option value="">{{ t('imageSettings.firstEnabled') }}</option>
        <option v-if="defaultId && !services[defaultId]" :value="defaultId">{{ t('imageSettings.missingDefault') }}</option>
        <option v-for="(service, id) in services" :key="id" :value="id" :disabled="service.enabled === false">{{ label(service) }}</option>
      </select>
    </label>
    <article v-for="(service, id) in services" :key="id" class="image-service" :data-service-id="id">
      <header>
        <strong>{{ label(service) }}</strong>
        <span v-if="defaultId === id" class="default-badge">{{ t('chat.modelDefault') }}</span>
        <details class="model-actions"><summary :aria-label="t('imageSettings.actions')">⋯</summary><div>
          <button :disabled="!writable || importing === id" @click="renaming = renaming === id ? null : id">{{ t('imageSettings.rename') }}</button>
          <button v-if="Object.keys(providers).length" :disabled="!writable || !!importing" @click="chooseImport(id)">{{ t('imageSettings.importConnection') }}</button>
          <button :disabled="!writable || importing === id" @click="update(id, 'enabled', service.enabled === false)">{{ t(service.enabled === false ? 'imageSettings.enabled' : 'imageSettings.disable') }}</button>
          <button :disabled="!writable || importing === id" @click="remove(id)">{{ t('imageSettings.remove') }}</button>
        </div></details>
      </header>
      <div class="service-status" role="status">
        <strong>{{ t(`imageSettings.state.${state(id)?.state || 'DRAFT'}`) }}</strong>
        <span v-if="state(id)?.code">{{ t(imageFailureKey(state(id)?.code || '')) }}</span>
        <small v-if="state(id)?.retryAt">{{ t('imageSettings.retryAt', { time: new Date(state(id)?.retryAt || 0).toLocaleTimeString() }) }}</small>
        <small v-if="config.isDirtySection('image-services', 'default-image-service')">{{ t('imageSettings.runtimeState') }}</small>
      </div>
      <fieldset :disabled="!writable || importing === id">
        <div v-if="importId === id" class="import-connection">
          <label>{{ t('imageSettings.importConnection') }}<select v-model="importProvider">
            <option v-for="(provider, key) in providers" :key="key" :value="key">{{ provider.label || provider.name || key }}</option>
          </select></label>
          <button :disabled="!providers[importProvider]" @click="copyConnection(id, importProvider)">{{ t('imageSettings.importOnce') }}</button>
          <p>{{ t('imageSettings.importHint') }}</p>
        </div>
        <label v-if="renaming === id">{{ t('imageSettings.label') }}<input :value="service.label || ''" :placeholder="String(service.model || '')" @input="update(id, 'label', text($event))" /></label>
        <div class="fields">
          <ConfigField :path="`image-services.${id}.profile`" v-slot="{ attrs }">{{ t('imageSettings.serviceType') }}<select v-bind="attrs" :value="service.profile" @change="updateProfile(id, text($event))">
            <option v-for="profile in IMAGE_PROFILES" :key="profile.id" :value="profile.id">{{ profile.label }}</option>
            <option v-if="service.profile && !IMAGE_PROFILES.some(profile => profile.id === service.profile)" :value="service.profile">{{ service.profile }}</option>
          </select></ConfigField>
          <ConfigField :path="`image-services.${id}.model`" v-slot="{ attrs }">{{ t('imageSettings.model') }}<input v-bind="attrs" data-model :value="service.model || ''" :placeholder="t('imageSettings.modelPlaceholder')" autocomplete="off" @input="update(id, 'model', text($event))" /></ConfigField>
            <ConfigField :path="`image-services.${id}.base-url`" v-slot="{ attrs }">{{ t('imageSettings.baseUrl') }}<input v-bind="attrs" :value="service['base-url'] || ''" :placeholder="IMAGE_PROFILES.find(profile => profile.id === service.profile)?.placeholder || 'https://api.example.com/v1'" @input="update(id, 'base-url', text($event))" /></ConfigField>
          <ConfigField :path="`image-services.${id}.api-key`" v-slot="{ attrs }">{{ t('imageSettings.apiKey') }}
            <span class="secret-field">
              <input v-bind="attrs" :type="visibleKeys[id] ? 'text' : 'password'" :value="revealedKeys[id] ?? service['api-key'] ?? ''"
                autocomplete="new-password" @input="update(id, 'api-key', text($event))" />
              <button type="button" :aria-label="t(visibleKeys[id] ? 'settings.hideSecret' : 'settings.showSecret')"
                :aria-pressed="!!visibleKeys[id]" :disabled="!!revealingKeys[id]" @click="toggleKey(id)">
                <svg v-if="!visibleKeys[id]" width="14" height="14" viewBox="0 0 24 24" fill="none"
                  stroke="currentColor" stroke-width="1.7" aria-hidden="true"><path d="M2.5 12s3.5-6 9.5-6 9.5 6 9.5 6-3.5 6-9.5 6-9.5-6-9.5-6z"/><circle cx="12" cy="12" r="2.5"/></svg>
                <svg v-else width="14" height="14" viewBox="0 0 24 24" fill="none"
                  stroke="currentColor" stroke-width="1.7" aria-hidden="true"><path d="M3 3l18 18M10.6 6.2A10.5 10.5 0 0112 6c6 0 9.5 6 9.5 6a15 15 0 01-2.2 2.8M6.1 6.1C3.8 7.8 2.5 12 2.5 12s3.5 6 9.5 6a9.8 9.8 0 003-.5"/></svg>
              </button>
            </span>
          </ConfigField>
          <ConfigField :path="`image-services.${id}.main-model`" v-slot="{ attrs }" v-if="service.profile === 'openai-responses-image'">{{ t('imageSettings.mainModel') }}<input v-bind="attrs" :value="service['main-model'] || ''" @input="update(id, 'main-model', text($event))" /></ConfigField>
        </div>
        <p class="hint">{{ t('imageSettings.modelHint') }}</p>
        <p class="hint">{{ t('imageSettings.networkHint') }}</p>
        <details class="advanced-settings"><summary>{{ t('imageSettings.advanced') }}</summary><div class="fields">
          <ConfigField :path="`image-services.${id}.timeout-millis`" v-slot="{ attrs }">{{ t('imageSettings.timeout') }}<input v-bind="attrs" type="number" min="1" max="1800" :value="Number(service['timeout-millis'] ?? 300000) / 1000" @change="update(id, 'timeout-millis', Number(text($event)) * 1000)" /></ConfigField>
        </div></details>
        <details v-if="state(id)?.code"><summary>{{ t('imageSettings.diagnostics') }}</summary><p>{{ state(id)?.message }}</p><code>{{ state(id)?.code }}</code></details>
      </fieldset>
    </article>
  </div>
</template>

<style scoped>
.image-settings { display:grid; gap:16px; min-width:0; font-size:13px; }
p { margin:0; color:var(--text-muted); line-height:1.6; }
.toolbar, header { display:flex; align-items:center; gap:10px; flex-wrap:wrap; }
.toolbar { justify-content:space-between; } .add-model { font-weight:600; }
.default-badge { color:var(--accent); background:var(--accent-soft); padding:3px 7px; border-radius:5px; font-size:11px; }
.image-service { border:1px solid var(--border); border-radius:10px; padding:16px; min-width:0; }
header { margin-bottom:12px; } header strong { overflow-wrap:anywhere; min-width:0; }
.service-status { display:grid; gap:5px; padding:10px; margin-bottom:12px; border-radius:7px; background:var(--surface-2); overflow-wrap:anywhere; }
fieldset { border:0; padding:0; margin:0; min-width:0; }
.fields { display:grid; grid-template-columns:repeat(2,minmax(0,1fr)); gap:12px; margin-top:12px; }
label { display:grid; gap:6px; min-width:0; color:var(--text-secondary); }
input,select { width:100%; min-width:0; min-height:36px; padding:6px 9px; border:1px solid var(--border); border-radius:6px; background:var(--surface-1); color:var(--text-primary); }
button { min-height:32px; border:1px solid var(--border); border-radius:6px; background:var(--surface-2); padding:5px 10px; color:var(--text-primary); cursor:pointer; }
button:disabled,fieldset:disabled { opacity:.55; }
.secret-field { display:flex; align-items:center; position:relative; }
.secret-field input { padding-right:34px; }
.secret-field button { position:absolute; right:4px; width:28px; height:28px; min-height:28px; padding:0; display:grid; place-items:center; border:0; background:transparent; color:var(--text-muted); }
.hint { margin-top:10px; } details { margin-top:14px; border-top:1px solid var(--border); padding-top:12px; }
summary { cursor:pointer; } [role=alert] { color:var(--danger,#c04444); }
.model-actions { position:relative; margin:0 0 0 auto; padding:0; border:0; }
.model-actions summary { list-style:none; padding:2px 10px; font-size:21px; }
.model-actions > div { position:absolute; right:0; top:100%; z-index:5; display:grid; gap:4px; padding:6px; min-width:165px; border:1px solid var(--border); border-radius:8px; background:var(--surface-1); box-shadow:var(--shadow-sm); }
.model-actions button { text-align:left; border:0; white-space:nowrap; }
.import-connection { display:flex; flex-wrap:wrap; align-items:end; gap:8px; padding:10px; border:1px solid var(--border); border-radius:8px; }
.import-connection p { width:100%; } .import-connection label { flex:1; min-width:120px; }
@media(max-width:600px) { .fields { grid-template-columns:minmax(0,1fr); } .image-service { padding:12px; } }
</style>
