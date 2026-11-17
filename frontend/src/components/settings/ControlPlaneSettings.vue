<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { api, getRuntimeRevision, isTauriHost, revealNativeProviderSecret } from '../../api/client'
import { CAPABILITIES } from '../../domain/capabilities'
import { editProviderConfig, knownProviderModelWindow, normalizeProviderConfig, providerModelOptions, providerProfileMismatch, reconcileProviderModels,
  type ProviderConfig } from '../../domain/providerSettings'
import { useConfigStore } from '../../stores/config'
import { useWorkspaceStore } from '../../stores/workspace'
import { useSkillStore } from '../../stores/skills'
import { useRuntimeStore } from '../../stores/runtime'
import type { McpServerStatus, MemoryDocument } from '../../api/contracts'
import { t } from '../../i18n'
import { errorMessage, isRecord, type UnknownRecord } from '../../utils/unknown'
import { isMacPlatform } from '../../utils/platform'
import ExecutionSettings from './ExecutionSettings.vue'
import ImageSettings from './ImageSettings.vue'
import ImagePricingSettings from './ImagePricingSettings.vue'
import WebSearchSettings from './WebSearchSettings.vue'
import ConfigField from './ConfigField.vue'
import type { ConfigIssue } from '../../domain/configValidation'

type Tab = 'providers' | 'images' | 'pricing' | 'network' | 'safety' | 'toolsets' | 'search' | 'memory' | 'skills' | 'integrations' | 'diagnostics'
interface RouterConfig extends UnknownRecord {
  defaultProvider?: string
  'default-provider'?: string
  selectedProviderMaxAttempts?: number
  'selected-provider-max-attempts'?: number
}
interface SummarizerConfig extends UnknownRecord {
  enabled?: boolean
  provider?: string
  model?: string
  maxOutputTokens?: number
  'max-output-tokens'?: number
  timeoutMs?: number
  'timeout-ms'?: number
  attemptTimeoutMs?: number
  'attempt-timeout-ms'?: number
}
interface HttpConfig extends UnknownRecord {
  proxy?: string
  proxySupport?: string
  'proxy-support'?: string
  proxyBypass?: string[]
  'proxy-bypass'?: string[]
}
interface ProviderTestFeedback {
  kind: 'success' | 'warning' | 'error'
  message: string
}

const SECRET_PLACEHOLDER = '••••••••'
const MCP_CONFIG_EXAMPLE = JSON.stringify({
  servers: [
    {
      name: 'filesystem',
      transport: 'stdio',
      command: 'npx',
      args: ['-y', '@modelcontextprotocol/server-filesystem', 'C:\\path\\to\\workspace'],
      exposeToPresets: ['act'],
    },
    {
      name: 'remote-http-tools',
      transport: 'streamable-http',
      url: 'https://mcp.example.com/mcp',
      headers: { Authorization: 'Bearer ${MCP_TOKEN:}' },
      exposeToPresets: ['act'],
    },
    {
      name: 'remote-tools',
      transport: 'sse',
      url: 'https://mcp.example.com/sse',
      headers: { Authorization: 'Bearer ${MCP_TOKEN:}' },
      exposeToPresets: ['act'],
    },
  ],
}, null, 2)
const HOOKS_CONFIG_EXAMPLE = JSON.stringify({
  rules: [
    { name: 'audit-all', phase: 'preToolCall', toolPattern: '*', action: 'log' },
    {
      name: 'block-dangerous-bash',
      phase: 'preToolCall',
      toolPattern: 'bash',
      action: 'veto',
      vetoReason: 'Bash blocked by hook policy',
      argsMatch: 'rm\\s+-rf\\s+/',
    },
    { name: 'notify-mcp-calls', phase: 'postToolCall', toolPattern: 'mcp__*', action: 'notify' },
    { name: 'log-errors', phase: 'onError', toolPattern: '*', action: 'log' },
  ],
}, null, 2)

function providerMap(value: unknown): Record<string, ProviderConfig> {
  if (!isRecord(value)) return {}
  return Object.fromEntries(Object.entries(value).filter((entry): entry is [string, ProviderConfig] =>
    isRecord(entry[1])).map(([id, provider]) => [id, normalizeProviderConfig(provider)]))
}

const props = defineProps<{ activeTab?: Tab }>()
const emit = defineEmits<{ navigateTab: [tab: Tab] }>()
const formRoot = ref<HTMLElement | null>(null)
const config = useConfigStore()
const workspace = useWorkspaceStore()
const skillStore = useSkillStore()
const runtime = useRuntimeStore()
const tab = ref<Tab>('providers')
const currentTab = computed(() => props.activeTab || tab.value)
const supported = computed(() => runtime.has(CAPABILITIES.configRead))
const initializing = ref(true)
const writable = computed(() => runtime.has(CAPABILITIES.configWrite))
const skills = computed(() => skillStore.items)
const memory = ref<MemoryDocument | null>(null)
const savedMemory = ref<MemoryDocument | null>(null)
const runtimeToolsets = ref<Record<string, string[]>>({})
const runtimeMcpServers = ref<McpServerStatus[] | null>(null)
const mcpStatusReadFailed = ref(false)
const localMessage = ref('')
const localError = ref('')
const testing = ref<string | null>(null)
const providerTestResults = ref<Record<string, ProviderTestFeedback>>({})
const apiKeyVisible = ref<Record<string, boolean>>({})
const revealedApiKeys = ref<Record<string, string>>({})
const apiKeyErrors = ref<Record<string, string>>({})
const revealingApiKey = ref<string | null>(null)
const renamingProvider = ref(false)
const applyingConfiguration = ref(false)
const nativeKeychain = isTauriHost() && isMacPlatform()
const providerModelCatalogs = ref<Record<string, string[]>>({})
const providerModelLoading = ref<Record<string, boolean>>({})
const providerModelErrors = ref<Record<string, string>>({})
const providerCustomModelInputs = ref<Record<string, boolean>>({})
const providerModelRequestIds: Record<string, number> = {}
const toolsetEditing = ref(false)

const tabs = computed<Array<{ key: Tab; label: string }>>(() => [
  { key: 'providers', label: t('settings.tabProvider') },
  { key: 'images', label: t('imageSettings.title') },
  { key: 'pricing', label: t('settings.pricing') },
  { key: 'network', label: t('settings.tabProxy') },
  { key: 'safety', label: t('settings.tabSafety') },
  { key: 'toolsets', label: t('settings.tabToolset') },
  { key: 'memory', label: t('settings.tabMemory') },
  { key: 'skills', label: t('settings.tabSkills') },
  { key: 'integrations', label: 'MCP / Hooks' },
  { key: 'search', label: t('searchSettings.title') },
  { key: 'diagnostics', label: t('settings.tabDiagnostics') },
])

const providers = computed(() => Object.prototype.hasOwnProperty.call(config.draft, 'providers')
  ? providerMap(config.draft.providers)
  : providerMap(config.effective.providers))
const effectiveRouter = computed<RouterConfig>(() =>
  isRecord(config.effective.router) ? config.effective.router as RouterConfig : {})
const routerConfig = computed<RouterConfig>(() => ({
  ...effectiveRouter.value,
  ...(isRecord(config.draft.router) ? config.draft.router as RouterConfig : {}),
}))
const effectiveSummarizer = computed<SummarizerConfig>(() =>
  isRecord(config.effective.summarizer) ? config.effective.summarizer as SummarizerConfig : {})
const summarizerConfig = computed<SummarizerConfig>(() => ({
  ...effectiveSummarizer.value,
  ...(isRecord(config.draft.summarizer) ? config.draft.summarizer as SummarizerConfig : {}),
}))
const httpConfig = computed<HttpConfig>(() =>
  isRecord(config.draft.http) ? config.draft.http as HttpConfig : {})
const profileDefaultMap = computed(() => isRecord(config.effective.providerProfileDefaults)
  ? config.effective.providerProfileDefaults : {})
const storagePaths = computed(() => isRecord(runtime.diagnostics?.storage) ? runtime.diagnostics.storage : {})
async function openDataDirectory() {
  localError.value = ''
  try { await api.openDataDirectory() } catch (cause) { localError.value = errorMessage(cause) }
}
const profileOptions = computed(() => [...new Set([
  ...Object.keys(profileDefaultMap.value),
  ...Object.values(providers.value).map(provider => String(provider?.profile || '')).filter(Boolean),
])])
function profileName(id: string) {
  const defaults = profileDefaultMap.value[id]
  return isRecord(defaults) && typeof defaults.label === 'string' && defaults.label.trim() ? defaults.label : id
}
function profileLabel(id: string) {
  const name = profileName(id)
  return name === 'openai-compatible' ? t('settings.genericChatProfile') : name
}
function profileHint(id: string) {
  const name = profileName(id)
  if (name === 'openai-compatible') return t('settings.genericChatProfileHint')
  if (name === 'openai-chat') return t('settings.openaiChatProfileHint')
  return ''
}
const providerProfileWarnings = computed(() => Object.fromEntries(Object.entries(providers.value).map(([id, provider]) => {
  if (providerTestResults.value[id]?.kind === 'success') return [id, '']
  const family = providerProfileMismatch(provider, profileDefaults(provider))
  return [id, family ? t('settings.profileModelMismatch', {
    model: providerDefaultModel(provider), family, profile: profileLabel(String(provider.profile)),
  }) : '']
})))
const safetyConfig = computed<UnknownRecord>(() =>
  isRecord(config.draft.safety) ? config.draft.safety : {})
const effectiveSafety = computed<UnknownRecord>(() =>
  isRecord(config.effective.safety) ? config.effective.safety : {})
const workspaceConfig = computed<UnknownRecord>(() =>
  isRecord(config.draft.workspace) ? config.draft.workspace : {})
const effectiveWorkspace = computed<UnknownRecord>(() =>
  isRecord(config.effective.workspace) ? config.effective.workspace : {})
const currentTabDirty = computed(() => ({
  providers: config.isDirtySection('providers', 'router', 'summarizer'),
  images: config.isDirtySection('image-services', 'default-image-service'),
  pricing: config.isDirtySection('image-services'),
  network: config.isDirtySection('http'),
  safety: config.isDirtySection('safety', 'workspace', 'execution'),
  toolsets: config.isDirtySection('toolsets'),
  search: config.isDirtySection('web-search'),
  integrations: config.isDirtySection('mcp', 'hooks'),
  memory: false,
  skills: false,
  diagnostics: false,
})[currentTab.value])

async function loadSectionData() {
  const revision = getRuntimeRevision(), section = currentTab.value
  if (section === 'skills' && runtime.has(CAPABILITIES.skillsToggle)) await skillStore.load()
  if (section === 'toolsets') {
    const projectReady = workspace.profile === 'PROJECT' && workspace.ready
    const [next, mcp] = await Promise.all([
      projectReady ? api.toolsets().catch(() => ({})) : Promise.resolve({}),
      projectReady ? api.mcpServers().catch(() => null) : Promise.resolve(null),
    ])
    if (revision === getRuntimeRevision()) {
      runtimeToolsets.value = next
      runtimeMcpServers.value = mcp?.servers || null
      mcpStatusReadFailed.value = projectReady && mcp == null
    }
  }
  if (section === 'memory') {
    const next = await api.memory().catch(() => null)
    if (revision === getRuntimeRevision()) {
      memory.value = next ? JSON.parse(JSON.stringify(next)) : null
      savedMemory.value = next ? JSON.parse(JSON.stringify(next)) : null
    }
  }
  if (section === 'diagnostics') await runtime.load({ diagnostics: true })
}

async function initialize() {
  initializing.value = true
  runtimeToolsets.value = {}
  runtimeMcpServers.value = null
  mcpStatusReadFailed.value = false
  localError.value = ''
  try {
    await runtime.load({ force: true })
    if (supported.value && !config.dirty) await config.load()
    await loadSectionData()
    return true
  } catch (cause: unknown) {
    localError.value = errorMessage(cause)
    return false
  } finally {
    initializing.value = false
  }
}

onMounted(initialize)
watch(currentTab, () => {
  localMessage.value = ''
  localError.value = ''
  config.message = ''
  if (!initializing.value) void loadSectionData().catch(cause => { localError.value = errorMessage(cause) })
})
watch(() => workspace.scopeInstanceId, (current, previous) => {
  if (current && current !== previous && !applyingConfiguration.value) void initialize()
})

function canRenameProvider(id: string) {
  return !['primary', 'fallback'].includes(id.toLowerCase())
}

async function renameProvider(id: string) {
  if (!canRenameProvider(id) || !writable.value || renamingProvider.value || applyingConfiguration.value) return
  const nextId = window.prompt(t('settings.providerRenamePrompt'), id)?.trim()
  if (!nextId || nextId === id) return
  localError.value = ''
  if (!/^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$/.test(nextId) || !canRenameProvider(nextId)) {
    localError.value = t('settings.providerIdInvalid')
    return
  }
  if (Object.keys(providers.value).some(existing => existing !== id && existing.toLowerCase() === nextId.toLowerCase())) {
    localError.value = t('settings.providerIdExists', { id: nextId })
    return
  }
  const revision = getRuntimeRevision()
  const original = JSON.stringify(providers.value[id])
  const provider = { ...providers.value[id] }
  renamingProvider.value = true
  try {
    // Placeholders are resolved by provider ID when saving. Carry the credential to the new ID
    // so it cannot be dropped or left pointing at an old Keychain entry that will be removed.
    if (isStoredApiKey(provider)) {
      const nativeSecret = await revealNativeProviderSecret(id)
      provider['api-key'] = nativeSecret ?? (await api.revealProviderApiKey(id)).apiKey
      if (!provider['api-key'] || provider['api-key'] === SECRET_PLACEHOLDER) {
        throw new Error(t('settings.providerRenameSecretFailed'))
      }
    }
    if (revision !== getRuntimeRevision() || original !== JSON.stringify(providers.value[id])) return
    if (Object.keys(providers.value).some(existing => existing !== id && existing.toLowerCase() === nextId.toLowerCase())) {
      localError.value = t('settings.providerIdExists', { id: nextId })
      return
    }
    const wasDefault = defaultProviderId().toLowerCase() === id.toLowerCase()
    const wasSummarizer = summarizerProviderId.value.toLowerCase() === id.toLowerCase()
    const next = Object.fromEntries(Object.entries(providers.value)
      .map(([key, value]) => key === id ? [nextId, provider] : [key, value]))
    config.setSection('providers', next)
    if (wasDefault) setDefaultProvider(nextId)
    if (wasSummarizer) updateSummarizer('provider', nextId)
    invalidateProviderModels(id)
    apiKeyVisible.value = removeRecordEntry(apiKeyVisible.value, id)
    revealedApiKeys.value = removeRecordEntry(revealedApiKeys.value, id)
    apiKeyErrors.value = removeRecordEntry(apiKeyErrors.value, id)
    providerTestResults.value = removeRecordEntry(providerTestResults.value, id)
    providerCustomModelInputs.value = removeRecordEntry(providerCustomModelInputs.value, id)
  } catch (cause: unknown) {
    if (revision === getRuntimeRevision()) localError.value = errorMessage(cause)
  } finally {
    renamingProvider.value = false
  }
}

function addProvider() {
  const entered = window.prompt(t('settings.providerIdPrompt'))?.trim()
  if (!entered) return
  const id = entered.toLowerCase() === 'fallback' ? 'fallback' : entered
  const next = { ...providers.value }
  if (Object.keys(next).some(existing => existing.toLowerCase() === id.toLowerCase())) return
  next[id] = { enabled: true, profile: 'openai-compatible-chat-safe', 'base-url': '', 'api-key': '', 'default-model': '' }
  config.setSection('providers', next)
}

function removeProvider(id: string) {
  if (!window.confirm(t('settings.removeProviderConfirm', { id }))) return
  invalidateProviderModels(id)
  const next = { ...providers.value }
  delete next[id]
  config.setSection('providers', next)
  if (defaultProviderId() === id) {
    setDefaultProvider(nextDefaultProvider(next, id))
  }
}

function updateProvider(id: string, key: string, value: unknown) {
  const previous = providers.value[id]
  const next = { ...providers.value, [id]: editProviderConfig(previous, key, value,
    String(profileDefaults(previous).baseUrl || '')) }
  config.setSection('providers', next)
  if (['profile', 'base-url', 'baseUrl', 'api-key', 'apiKey'].includes(key)) {
    const sameEndpoint = key === 'profile'
      && providerBaseUrl(previous).replace(/\/+$/, '') === providerBaseUrl(next[id]).replace(/\/+$/, '')
    invalidateProviderModels(id, sameEndpoint)
    if (key !== 'profile') providerCustomModelInputs.value = removeRecordEntry(providerCustomModelInputs.value, id)
  }
  if (providerTestResults.value[id]) {
    const { [id]: _discarded, ...remaining } = providerTestResults.value
    providerTestResults.value = remaining
  }
  if (key === 'enabled' && value === false && defaultProviderId() === id) {
    setDefaultProvider(nextDefaultProvider(next, id))
  }
}

function defaultProviderId() {
  return String(routerConfig.value['default-provider'] ?? routerConfig.value.defaultProvider ?? '')
}

function nextDefaultProvider(catalogue: Record<string, ProviderConfig>, excluding = '') {
  return Object.entries(catalogue)
    .find(([id, provider]) => id !== excluding && id !== 'fallback' && provider.enabled !== false)?.[0] || ''
}

function updateRouter(key: string, value: unknown) {
  const draft = isRecord(config.draft.router) ? config.draft.router : {}
  config.setSection('router', { ...draft, [key]: value })
}

function setDefaultProvider(id: string) {
  updateRouter('default-provider', id)
}

function summaryNumberValue(kebab: string, camel: string, fallback: number) {
  for (const key of [kebab, camel]) {
    const value = summarizerConfig.value[key]
    if (value === null || value === undefined || value === '') continue
    const parsed = Number(value)
    if (Number.isFinite(parsed)) return parsed
  }
  return fallback
}

function updateSummarizer(key: string, value: unknown) {
  const draft = isRecord(config.draft.summarizer) ? config.draft.summarizer : {}
  config.setSection('summarizer', { ...draft, [key]: value })
}

function modelOptions(providerId: string) {
  return providerModelOptions(providers.value[providerId], providerModelCatalogs.value[providerId])
}

function providerDefaultModel(provider: ProviderConfig) {
  return String(provider['default-model'] || '')
}

function providerModelSelectValue(id: string, provider: ProviderConfig) {
  const model = providerDefaultModel(provider)
  if (providerCustomModelInputs.value[id]) return '__custom__'
  if (modelOptions(id).includes(model)) return model
  return model ? '__custom__' : ''
}

function usesCustomProviderModel(id: string, provider: ProviderConfig) {
  return !modelOptions(id).length || providerModelSelectValue(id, provider) === '__custom__'
}

function selectProviderModel(id: string, value: string) {
  if (value === '__custom__') {
    providerCustomModelInputs.value = { ...providerCustomModelInputs.value, [id]: true }
    return
  }
  providerCustomModelInputs.value = removeRecordEntry(providerCustomModelInputs.value, id)
  updateProvider(id, 'default-model', value)
}

function updateCustomProviderModel(id: string, value: string) {
  providerCustomModelInputs.value = { ...providerCustomModelInputs.value, [id]: true }
  updateProvider(id, 'default-model', value)
}

// An empty provider means “follow the actual task provider/model”; it must not silently turn into the router default
// in the editor, because the provider that ultimately served a task can be different after fallback.
const summarizerProviderId = computed(() => String(summarizerConfig.value.provider || ''))
const summarizerModelOptions = computed(() => modelOptions(summarizerProviderId.value))

function selectSummarizerProvider(providerId: string) {
  const draft = isRecord(config.draft.summarizer) ? config.draft.summarizer : {}
  // A model id is scoped to its provider. Clear it atomically on every provider switch so an invisible stale model
  // can never be saved against (or sent to) the new endpoint.
  config.setSection('summarizer', { ...draft, provider: providerId, model: '' })
}

function selectSummarizerModel(model: string) {
  const providerId = summarizerProviderId.value
  if (!providerId || !model) {
    updateSummarizer('model', '')
    return
  }
  const provider = providers.value[providerId]
  if (provider && providerDefaultModel(provider).toLowerCase() !== model.toLowerCase()) {
    const configured = Array.isArray(provider.models) ? provider.models : []
    const declared = configured.some(item => {
      const id = typeof item === 'string' ? item : isRecord(item) ? String(item.id || '') : ''
      return id.toLowerCase() === model.toLowerCase()
    })
    if (!declared) {
      // Persist the selected discovered id as a bounded selectable model. Provider validation intentionally rejects
      // arbitrary summarizer models that are not declared by that endpoint.
      updateProvider(providerId, 'models', [...configured, { id: model }])
    }
  }
  updateSummarizer('model', model)
}

function configuredProviderApiKey(provider: ProviderConfig) {
  return String(provider['api-key'] || '')
}

function providerApiKey(id: string, provider: ProviderConfig) {
  return revealedApiKeys.value[id] ?? configuredProviderApiKey(provider)
}

function isStoredApiKey(provider: ProviderConfig) {
  return configuredProviderApiKey(provider) === SECRET_PLACEHOLDER
}

function removeRecordEntry<T>(record: Record<string, T>, id: string) {
  const { [id]: _discarded, ...remaining } = record
  return remaining
}

function invalidateProviderModels(id: string, keepCatalogue = false) {
  providerModelRequestIds[id] = (providerModelRequestIds[id] || 0) + 1
  if (!keepCatalogue) providerModelCatalogs.value = removeRecordEntry(providerModelCatalogs.value, id)
  providerModelErrors.value = removeRecordEntry(providerModelErrors.value, id)
  providerModelLoading.value = removeRecordEntry(providerModelLoading.value, id)
}

function resetProviderModels() {
  Object.keys(providerModelRequestIds).forEach(id => invalidateProviderModels(id))
  providerCustomModelInputs.value = {}
}

watch(() => config.document, resetProviderModels, { flush: 'sync' })
watch(() => config.dirty, dirty => { if (!dirty) resetProviderModels() }, { flush: 'sync' })
onBeforeUnmount(resetProviderModels)

function updateApiKey(id: string, value: string) {
  revealedApiKeys.value = removeRecordEntry(revealedApiKeys.value, id)
  apiKeyErrors.value = removeRecordEntry(apiKeyErrors.value, id)
  updateProvider(id, 'api-key', value)
}

function providerBaseUrl(provider: ProviderConfig) {
  return String(provider['base-url'] || profileDefaults(provider).baseUrl || '').trim()
}

function canLoadProviderModels(provider: ProviderConfig) {
  return Boolean(provider.profile && providerBaseUrl(provider))
}

async function refreshProviderModels(id: string) {
  if (providerModelLoading.value[id]) return
  const provider = providers.value[id]
  if (!provider || !canLoadProviderModels(provider)) return
  const requestId = (providerModelRequestIds[id] || 0) + 1
  const runtimeRevision = getRuntimeRevision()
  providerModelRequestIds[id] = requestId
  providerModelLoading.value = { ...providerModelLoading.value, [id]: true }
  providerModelCatalogs.value = removeRecordEntry(providerModelCatalogs.value, id)
  providerModelErrors.value = removeRecordEntry(providerModelErrors.value, id)
  try {
    const result = await api.providerModels(id, { ...config.draft, providers: providers.value })
    if (providerModelRequestIds[id] !== requestId || getRuntimeRevision() !== runtimeRevision) return
    providerModelCatalogs.value = { ...providerModelCatalogs.value, [id]: result.models || [] }
    const current = providers.value[id]
    if (current && Array.isArray(current.models)) {
      const models = reconcileProviderModels(current, result.models || [])
      if (models.length !== current.models.length) updateProvider(id, 'models', models)
    }
    if (!result.models?.length) {
      providerModelErrors.value = { ...providerModelErrors.value, [id]: t('settings.modelsEmpty') }
    }
  } catch (cause: unknown) {
    if (providerModelRequestIds[id] !== requestId || getRuntimeRevision() !== runtimeRevision) return
    providerModelErrors.value = {
      ...providerModelErrors.value,
      [id]: t('settings.modelsLoadFailed', { message: errorMessage(cause) }),
    }
  } finally {
    if (providerModelRequestIds[id] === requestId) {
      providerModelLoading.value = removeRecordEntry(providerModelLoading.value, id)
    }
  }
}

function ensureSummarizerModelCatalogue(id: string) {
  if (!id || providerModelLoading.value[id] || providerModelCatalogs.value[id] !== undefined) return
  const provider = providers.value[id]
  if (!provider || !canLoadProviderModels(provider)) return
  const configured = Array.isArray(provider.models) && provider.models.length > 0
  if (!configured) void refreshProviderModels(id)
}

watch(summarizerProviderId, ensureSummarizerModelCatalogue, { flush: 'post' })

async function toggleApiKeyVisibility(id: string, provider: ProviderConfig) {
  if (apiKeyVisible.value[id]) {
    apiKeyVisible.value = { ...apiKeyVisible.value, [id]: false }
    if (isStoredApiKey(provider)) {
      revealedApiKeys.value = removeRecordEntry(revealedApiKeys.value, id)
    }
    return
  }
  if (!isStoredApiKey(provider)) {
    apiKeyVisible.value = { ...apiKeyVisible.value, [id]: true }
    return
  }
  revealingApiKey.value = id
  apiKeyErrors.value = removeRecordEntry(apiKeyErrors.value, id)
  try {
    const nativeSecret = await revealNativeProviderSecret(id)
    const apiKey = nativeSecret == null ? (await api.revealProviderApiKey(id)).apiKey || '' : nativeSecret
    revealedApiKeys.value = { ...revealedApiKeys.value, [id]: apiKey }
    apiKeyVisible.value = { ...apiKeyVisible.value, [id]: true }
  } catch (cause: unknown) {
    apiKeyErrors.value = { ...apiKeyErrors.value, [id]: errorMessage(cause) }
  } finally {
    revealingApiKey.value = null
  }
}

function updateProviderJson(id: string, event: Event) {
  try {
    const value = JSON.parse((event.target as HTMLTextAreaElement).value || '{}')
    if (!isRecord(value)) throw new Error('Provider must be an object')
    invalidateProviderModels(id)
    config.setSection('providers', { ...providers.value, [id]: normalizeProviderConfig(value) })
    localError.value = ''
  } catch {
    localError.value = t('settings.providerJsonInvalid', { id })
  }
}

function profileDefaults(provider: ProviderConfig) {
  const defaults = provider.profile ? profileDefaultMap.value[provider.profile] : null
  return isRecord(defaults) ? defaults : {}
}

const providerModelWindows = computed(() => Object.fromEntries(Object.entries(providers.value)
  .map(([id, provider]) => [id, knownProviderModelWindow(provider, profileDefaults(provider))])))

function effectiveProvider(id: string) {
  const provider = isRecord(config.effective.providers) ? config.effective.providers[id] : null
  if (!isRecord(provider)) return {}
  const normalized = normalizeProviderConfig(provider)
  const defaults = profileDefaults(normalized)
  return {
    baseUrl: normalized['base-url'] || defaults.baseUrl,
    defaultModel: normalized['default-model'] || defaults.defaultModel,
  }
}

async function testProvider(id: string) {
  if (testing.value) return
  const provider = providers.value[id] || {}
  const model = providerDefaultModel(provider) || undefined
  const revision = getRuntimeRevision()
  const candidate = JSON.stringify(provider)
  testing.value = id
  const { [id]: _discarded, ...remaining } = providerTestResults.value
  providerTestResults.value = remaining
  try {
    const result = await api.probeProvider(id, model, { ...config.draft, providers: providers.value })
    if (revision !== getRuntimeRevision() || candidate !== JSON.stringify(providers.value[id])) return
    const inconclusive = result.outcome === 'inconclusive' || !result.outcome
      && ['timeout', 'network_error', 'rate_limited', 'upstream_error', 'protocol_mismatch', 'empty_response'].includes(result.code)
    providerTestResults.value = {
      ...providerTestResults.value,
      [id]: {
        kind: result.success ? 'success' : inconclusive ? 'warning' : 'error',
        message: result.success
          ? t('settings.providerProbeSuccess', { id, model: result.model, ms: result.latencyMs })
          : inconclusive ? t('settings.providerProbeInconclusive', { id, message: result.message, ms: result.latencyMs })
          : t('settings.providerProbeFailed', { id, code: result.code, message: result.message }),
      },
    }
  } catch (cause: unknown) {
    if (revision !== getRuntimeRevision() || candidate !== JSON.stringify(providers.value[id])) return
    providerTestResults.value = {
      ...providerTestResults.value,
      [id]: { kind: 'error', message: errorMessage(cause) },
    }
  } finally {
    testing.value = null
  }
}

function updateHttp(key: string, value: unknown) {
  config.setSection('http', { ...httpConfig.value, [key]: value })
}

function safetyValue<T>(kebab: string, camel: string, fallback: T): T {
  if (Object.prototype.hasOwnProperty.call(safetyConfig.value, kebab)) return safetyConfig.value[kebab] as T
  if (Object.prototype.hasOwnProperty.call(safetyConfig.value, camel)) return safetyConfig.value[camel] as T
  if (Object.prototype.hasOwnProperty.call(effectiveSafety.value, camel)) return effectiveSafety.value[camel] as T
  return fallback
}

function updateSafety(key: string, value: unknown) {
  config.setSection('safety', { ...safetyConfig.value, [key]: value })
}

function outsideWritePolicy(): string {
  const draft = workspaceConfig.value['outside-write-policy'] ?? workspaceConfig.value.outsideWritePolicy
  const effective = effectiveWorkspace.value['outside-write-policy'] ?? effectiveWorkspace.value.outsideWritePolicy
  return String(draft ?? effective ?? 'ask')
}

function updateOutsideWritePolicy(value: string) {
  config.setSection('workspace', { 'outside-write-policy': value })
}

function normalizeToolsetPresets(value: unknown): Record<string, string[]> {
  if (!isRecord(value)) return {}
  const out: Record<string, string[]> = {}
  for (const [name, tools] of Object.entries(value)) {
    if (!Array.isArray(tools)) continue
    out[name] = [...new Set(tools.filter((tool): tool is string => typeof tool === 'string' && Boolean(tool.trim()))
      .map(tool => tool.trim()))]
  }
  return out
}

function toolsetConfigSnapshot(): UnknownRecord {
  const draft = isRecord(config.draft.toolsets) ? { ...config.draft.toolsets } : {}
  const effective = isRecord(config.effective.toolsets) ? config.effective.toolsets : {}
  const draftPresets = normalizeToolsetPresets(draft.presets)
  const effectivePresets = normalizeToolsetPresets(effective.presets)
  const presets = Object.keys(draftPresets).length ? draftPresets
    : Object.keys(effectivePresets).length ? effectivePresets
      : Object.fromEntries(Object.entries(runtimeToolsets.value).filter(([name]) => name !== 'vscode')
        .map(([name, tools]) => [name, tools.filter(tool => !tool.startsWith('mcp__'))]))
  const rawDefault = draft['default-set'] ?? draft.defaultSet
    ?? effective['default-set'] ?? effective.defaultSet
  const defaults = Array.isArray(rawDefault)
    ? rawDefault.filter((item): item is string => typeof item === 'string' && Boolean(item.trim()))
    : []
  delete draft.defaultSet
  const fallback = Object.prototype.hasOwnProperty.call(presets, 'act') ? ['act'] : Object.keys(presets).slice(0, 1)
  return {
    ...draft,
    'default-set': defaults.length ? [...new Set(defaults)] : fallback,
    presets,
  }
}

const configuredToolsets = computed(() => normalizeToolsetPresets(toolsetConfigSnapshot().presets))
const inheritedVscodeToolset = computed(() => !Object.prototype.hasOwnProperty.call(configuredToolsets.value, 'vscode'))
const editableToolsets = computed(() => {
  const presets = configuredToolsets.value
  // Match the Runtime's generated vscode preset without pinning its inherited tools in user configuration.
  return inheritedVscodeToolset.value && Object.keys(presets).length
    ? { ...presets, vscode: [...(presets.act || [])] } : presets
})
const displayedToolsets = computed<Record<string, string[]>>(() => Object.fromEntries(Object.entries(editableToolsets.value).map(([name, tools]) => [
  name, [...new Set([...tools, ...(runtimeToolsets.value[name] || []).filter(tool => tool.startsWith('mcp__'))])],
])))
const toolsetMcpServers = computed(() => {
  const mcp = isRecord(config.draft.mcp) ? config.draft.mcp
    : isRecord(config.effective.mcp) ? config.effective.mcp : {}
  const groups: Record<string, Array<{ name: string; status: string; toolCount: number }>> = {}
  const seen = new Set<string>()
  for (const server of Array.isArray(mcp.servers) ? mcp.servers : []) {
    if (!isRecord(server) || typeof server.name !== 'string' || !server.name.trim() || seen.has(server.name)) continue
    seen.add(server.name)
    const targets = server['expose-to-presets'] ?? server.exposeToPresets
    const presets = Array.isArray(targets) && targets.length ? targets.filter(value => typeof value === 'string') : ['act']
    const live = runtimeMcpServers.value?.find(value => value.name === server.name)
    const status = live?.status || (live ? live.connected ? 'connected' : 'disconnected'
      : runtimeMcpServers.value ? 'pending' : mcpStatusReadFailed.value ? 'unknown' : 'on_demand')
    for (const preset of presets) (groups[preset] ||= []).push({ name: server.name, status, toolCount: live?.tools.length || 0 })
  }
  return groups
})
function mcpStatusLabel(status: string) {
  const labels: Record<string, string> = {
    on_demand: 'settings.mcpNotStarted', not_started: 'settings.mcpNotStarted',
    connecting: 'settings.mcpConnecting', connected: 'settings.mcpConnected',
    disconnected: 'settings.mcpDisconnected', pending: 'settings.mcpPending', unknown: 'settings.mcpUnknown',
  }
  return t(labels[status] || 'settings.mcpUnknown')
}
const defaultToolsets = computed(() => {
  const value = toolsetConfigSnapshot()['default-set']
  return Array.isArray(value) ? value.map(String) : []
})
const defaultToolset = computed(() => defaultToolsets.value.length === 1
  && Object.prototype.hasOwnProperty.call(editableToolsets.value, defaultToolsets.value[0])
  ? defaultToolsets.value[0] : '')
const defaultToolsetTools = computed(() => displayedToolsets.value[defaultToolset.value] || [])

function commitToolsets(next: UnknownRecord) {
  config.setSection('toolsets', next)
  localError.value = ''
}

function toggleToolsetEditing() {
  toolsetEditing.value = !toolsetEditing.value
}

function updateToolsetTools(name: string, event: Event) {
  const tools = [...new Set((event.target as HTMLTextAreaElement).value
    .split(/[\s,·]+/).map(value => value.trim()).filter(Boolean))]
  const next = toolsetConfigSnapshot()
  next.presets = { ...normalizeToolsetPresets(next.presets), [name]: tools }
  commitToolsets(next)
}

function setDefaultToolset(name: string) {
  if (name === defaultToolset.value) return
  // Selecting a default must not copy runtime/MCP tools into the user's preset definitions.
  const next = isRecord(config.draft.toolsets) ? { ...config.draft.toolsets } : {}
  delete next.defaultSet
  next['default-set'] = [name]
  commitToolsets(next)
}

function addToolset() {
  const name = window.prompt(t('settings.toolsetNamePrompt'))?.trim()
  if (!name) return
  if (!/^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$/.test(name)) {
    localError.value = t('settings.toolsetNameInvalid')
    return
  }
  const next = toolsetConfigSnapshot()
  const presets = normalizeToolsetPresets(next.presets)
  if (Object.prototype.hasOwnProperty.call(editableToolsets.value, name)) {
    localError.value = t('settings.toolsetExists', { name })
    return
  }
  next.presets = { ...presets, [name]: [] }
  commitToolsets(next)
}

function removeToolset(name: string) {
  if (!window.confirm(t('settings.removeToolsetConfirm', { name }))) return
  const next = toolsetConfigSnapshot()
  const presets = normalizeToolsetPresets(next.presets)
  delete presets[name]
  next.presets = presets
  const defaults = defaultToolsets.value.filter(value => value !== name)
  next['default-set'] = defaults.length ? defaults : Object.keys(presets).slice(0, 1)
  commitToolsets(next)
}

function toolsetJson() {
  return JSON.stringify(toolsetConfigSnapshot(), null, 2)
}

function jsonSection(name: string) {
  return JSON.stringify(config.draft[name] || {}, null, 2)
}

function updateJson(name: string, event: Event) {
  try {
    const value = JSON.parse((event.target as HTMLTextAreaElement).value || '{}')
    config.setSection(name, value)
    localError.value = ''
  } catch {
    localError.value = t('settings.sectionJsonInvalid', { name })
  }
}

async function copyConfigExample(value: string) {
  try {
    await navigator.clipboard.writeText(value)
    localMessage.value = t('settings.exampleCopied')
    localError.value = ''
  } catch (cause: unknown) {
    localError.value = errorMessage(cause)
  }
}

async function toggleSkill(skill: (typeof skills.value)[number]) {
  await skillStore.toggle(skill)
}

async function rescanSkills() {
  await skillStore.rescan()
}

async function saveMemory(scope: 'USER' | 'WORKSPACE', kind: 'profile' | 'memory' | 'lessons', content: string) {
  localError.value = ''
  try {
    const result = await api.saveMemory(scope, kind, content)
    memory.value = JSON.parse(JSON.stringify(result.memory))
    savedMemory.value = JSON.parse(JSON.stringify(result.memory))
    localMessage.value = t('settings.memorySaved', { scope, kind })
  } catch (cause: unknown) {
    localError.value = errorMessage(cause)
  }
}

function memoryValue(document: MemoryDocument, scope: 'USER' | 'WORKSPACE',
  kind: 'profile' | 'memory' | 'lessons') {
  return scope === 'USER' ? document.user.profile : document.workspace[kind]
}

function memoryDirty(scope: 'USER' | 'WORKSPACE', kind: 'profile' | 'memory' | 'lessons') {
  if (!memory.value || !savedMemory.value) return false
  return memoryValue(memory.value, scope, kind) !== memoryValue(savedMemory.value, scope, kind)
}

function resetMemory(scope: 'USER' | 'WORKSPACE', kind: 'profile' | 'memory' | 'lessons') {
  if (!memory.value || !savedMemory.value) return
  if (scope === 'USER') memory.value.user.profile = savedMemory.value.user.profile
  else memory.value.workspace[kind] = savedMemory.value.workspace[kind]
}

async function clearMemory(scope: 'USER' | 'WORKSPACE', kind: 'profile' | 'memory' | 'lessons') {
  if (!window.confirm(t('settings.clearMemoryConfirm'))) return
  await saveMemory(scope, kind, '')
}

async function restartRuntime() {
  if (applyingConfiguration.value || workspace.switching) return
  if (!workspace.available) {
    config.message = t('settings.manualRestartRequired')
    return
  }
  applyingConfiguration.value = true
  config.clearFeedback()
  localError.value = ''
  localMessage.value = t('settings.applyingConfiguration')
  try {
    await workspace.restart()
    if (!await initialize()) throw new Error(localError.value || t('settings.controlPlaneUnavailable'))
    config.clearFeedback()
    localMessage.value = t('settings.savedApplied')
  } catch (cause: unknown) {
    config.message = t('settings.savedReopen')
    localMessage.value = ''
    localError.value = t('settings.savedApplyFailed', { message: errorMessage(cause) })
  } finally {
    applyingConfiguration.value = false
  }
}

async function saveConfiguration() {
  if (config.loading || applyingConfiguration.value || renamingProvider.value || workspace.switching) return
  localMessage.value = ''
  localError.value = ''
  if (config.isDirtySection('toolsets')) {
    // Legacy configuration and the JSON editor must obey the same single-default rule as the form.
    if (!defaultToolset.value) {
      localError.value = t('settings.toolsetDefaultRequired')
      return
    }
    const next = isRecord(config.draft.toolsets) ? { ...config.draft.toolsets } : {}
    delete next.defaultSet
    next['default-set'] = [defaultToolset.value]
    config.setSection('toolsets', next)
  }
  if (!await config.save() && config.issues.length) await focusIssue(config.issues[0])
}

async function focusIssue(issue: ConfigIssue) {
  if (issue.tab && issue.tab !== currentTab.value) {
    if (props.activeTab) emit('navigateTab', issue.tab)
    else tab.value = issue.tab
  }
  await nextTick()
  const fields = Array.from(formRoot.value?.querySelectorAll?.<HTMLElement>('[data-config-field]') || [])
  const field = fields.filter(element => issue.path === element.dataset.configField
    || issue.path.startsWith(`${element.dataset.configField}.`))
    .sort((left, right) => (right.dataset.configField?.length || 0) - (left.dataset.configField?.length || 0))[0]
  if (!field) { formRoot.value?.querySelector?.<HTMLElement>('.validation-summary')?.focus(); return }
  for (let parent = field.parentElement; parent; parent = parent.parentElement) {
    if (parent instanceof HTMLDetailsElement) parent.open = true
  }
  field.scrollIntoView({ block: 'center', behavior: 'smooth' })
  field.querySelector<HTMLElement>('[aria-invalid=true],input,select,textarea')?.focus({ preventScroll: true })
}
</script>

<template>
  <div v-if="initializing" class="settings-state control-bootstrap">{{ t('settings.loading') }}</div>
  <div v-else-if="!supported" class="control-errors control-bootstrap" role="alert">
    {{ localError || t('settings.controlPlaneUnavailable') }}
  </div>
  <section v-else ref="formRoot" class="control-plane" :class="{ card: !props.activeTab, embedded: !!props.activeTab }">
    <div v-if="!props.activeTab" class="control-head">
      <div>
        <h2>{{ t('settings.globalTitle') }}</h2>
        <p>{{ t('settings.globalDesc') }}</p>
      </div>
      <span class="scope-chip">{{ t('settings.scopeUserShort') }}</span>
    </div>

    <nav v-if="!props.activeTab" class="settings-tabs" :aria-label="t('settings.sections')">
      <button v-for="item in tabs" :key="item.key" :class="{ active: tab === item.key }" @click="tab = item.key">
        {{ item.label }}
      </button>
    </nav>

    <div v-if="config.loading" class="settings-state">{{ t('settings.loading') }}</div>

    <div v-if="config.errors.length" class="control-errors validation-summary" role="alert" tabindex="-1">
      <strong>{{ t('settings.validationFailed', { count: config.errors.length }) }}</strong>
      <ul><li v-for="issue in config.issues" :key="issue.raw">
        <button v-if="issue.path" type="button" @click="focusIssue(issue)">{{ issue.message }}</button>
        <span v-else>{{ issue.message }}</span>
      </li></ul>
    </div>

    <template v-if="!config.loading">
    <div v-if="currentTab === 'providers'" class="control-section">
      <div class="section-toolbar">
        <div><h3>{{ t('settings.providerTitle') }}</h3><p>{{ t('settings.providersDesc') }}</p></div>
        <button class="control-button" :disabled="!writable" @click="addProvider">{{ t('settings.addProvider') }}</button>
      </div>
      <article class="provider-policy-card">
        <header><strong>{{ t('settings.routingPolicy') }}</strong></header>
        <p>{{ t('settings.routingPolicyDesc') }}</p>
        <div class="form-grid">
          <ConfigField path="router.default-provider" v-slot="{ attrs }">{{ t('settings.defaultProviderLabel') }}
            <select v-bind="attrs" :value="defaultProviderId()" @change="setDefaultProvider(($event.target as HTMLSelectElement).value)">
              <option value="">{{ t('settings.firstEnabledProvider') }}</option>
              <option v-for="(provider, id) in providers" v-show="id !== 'fallback' && provider.enabled !== false"
                :key="id" :value="id">{{ provider.label || id }}</option>
            </select>
          </ConfigField>
          <ConfigField path="router.selected-provider-max-attempts" v-slot="{ attrs }">{{ t('settings.providerRetryAttempts') }}
            <input v-bind="attrs" type="number" min="1" max="10"
              :value="routerConfig['selected-provider-max-attempts'] ?? routerConfig.selectedProviderMaxAttempts ?? 5"
              @input="updateRouter('selected-provider-max-attempts', Number(($event.target as HTMLInputElement).value))" />
          </ConfigField>
        </div>
      </article>
      <div v-if="!Object.keys(providers).length" class="settings-state">
        {{ t('settings.providersEmpty') }}
      </div>
      <article v-for="(provider, id) in providers" :key="id" class="provider-editor">
        <header>
          <strong v-if="!canRenameProvider(id)">{{ id }}</strong>
          <strong v-else class="provider-name" role="button" :tabindex="writable ? 0 : -1"
            :title="t('settings.providerRenameHint')" :aria-label="t('settings.providerRenameLabel', { id })"
            @dblclick="renameProvider(id)" @keydown.enter.prevent="renameProvider(id)"
            @keydown.space.prevent="renameProvider(id)"
            @keydown.f2.prevent="renameProvider(id)">{{ id }}</strong>
          <label><input :checked="provider.enabled !== false" type="checkbox"
            @change="updateProvider(id, 'enabled', ($event.target as HTMLInputElement).checked)" />
            {{ id.toLowerCase() === 'fallback' ? t('settings.fallbackEnabled') : t('settings.enabled') }}</label>
          <button v-if="id.toLowerCase() !== 'fallback'" class="danger-link"
            @click="removeProvider(id)">{{ t('settings.remove') }}</button>
        </header>
        <div v-if="id.toLowerCase() === 'fallback'" class="fallback-policy">
          <strong>{{ t('settings.fallbackRole') }}</strong>
          <p>{{ t('settings.fallbackMeaning') }}</p>
        </div>
        <div class="form-grid">
          <label>{{ t('settings.displayLabel') }}<input :value="provider.label || ''" @input="updateProvider(id, 'label', ($event.target as HTMLInputElement).value)" /></label>
          <ConfigField :path="`providers.${id}.profile`" v-slot="{ attrs }">{{ t('settings.profileLabel') }}
            <select v-bind="attrs" :value="provider.profile || ''"
              @change="updateProvider(id, 'profile', ($event.target as HTMLSelectElement).value)">
              <option value="" disabled>{{ t('settings.selectProfile') }}</option>
              <option v-for="profile in profileOptions" :key="profile" :value="profile">{{ profileLabel(profile) }}</option>
            </select>
            <small v-if="profileHint(String(provider.profile || ''))">{{ profileHint(String(provider.profile || '')) }}</small>
          </ConfigField>
          <ConfigField :path="`providers.${id}.base-url`" v-slot="{ attrs }" class="wide">{{ t('settings.baseUrlLabel') }}<input v-bind="attrs" :value="provider['base-url'] || ''"
            @input="updateProvider(id, 'base-url', ($event.target as HTMLInputElement).value)"
            @change="configuredProviderApiKey(provider) && refreshProviderModels(id)" /></ConfigField>
          <ConfigField :path="`providers.${id}.api-key`" v-slot="{ attrs }" class="wide">{{ t('settings.apiKeyLabel') }}
            <span class="secret-input">
              <input v-bind="attrs" :type="apiKeyVisible[id] ? 'text' : 'password'"
                :value="providerApiKey(id, provider)"
                autocomplete="new-password"
                @input="updateApiKey(id, ($event.target as HTMLInputElement).value)"
                @change="providerApiKey(id, provider).trim() && refreshProviderModels(id)" />
              <button type="button" :aria-label="apiKeyVisible[id] ? t('settings.hideSecret') : t('settings.showSecret')"
                :aria-pressed="!!apiKeyVisible[id]" :disabled="revealingApiKey === id"
                @click="toggleApiKeyVisibility(id, provider)">
                <svg v-if="!apiKeyVisible[id]" width="14" height="14" viewBox="0 0 24 24" fill="none"
                  stroke="currentColor" stroke-width="1.7"><path d="M2.5 12s3.5-6 9.5-6 9.5 6 9.5 6-3.5 6-9.5 6-9.5-6-9.5-6z"/><circle cx="12" cy="12" r="2.5"/></svg>
                <svg v-else width="14" height="14" viewBox="0 0 24 24" fill="none"
                  stroke="currentColor" stroke-width="1.7"><path d="M3 3l18 18M10.6 6.2A10.5 10.5 0 0112 6c6 0 9.5 6 9.5 6a15 15 0 01-2.2 2.8M6.1 6.1C3.8 7.8 2.5 12 2.5 12s3.5 6 9.5 6a9.8 9.8 0 003-.5"/></svg>
              </button>
            </span>
            <small v-if="nativeKeychain" class="secret-storage-note">{{ t('settings.keychainHint') }}</small>
            <small v-if="apiKeyErrors[id]" class="secret-error" role="alert">{{ apiKeyErrors[id] }}</small>
          </ConfigField>
          <ConfigField :path="`providers.${id}.default-model`" v-slot="{ attrs }" class="wide">{{ t('settings.defaultModel') }}
            <span class="model-catalog-input">
              <select v-bind="usesCustomProviderModel(id, provider) ? {} : attrs" v-if="modelOptions(id).length" :value="providerModelSelectValue(id, provider)"
                @change="selectProviderModel(id, ($event.target as HTMLSelectElement).value)">
                <option value="" disabled>{{ t('settings.modelsChoose') }}</option>
                <option v-for="model in modelOptions(id)" :key="model" :value="model">{{ model }}</option>
                <option value="__custom__">{{ t('settings.modelsCustomOption') }}</option>
              </select>
              <input v-else v-bind="attrs" :value="providerDefaultModel(provider)"
                :placeholder="t('settings.defaultModelPlaceholder')"
                @input="updateCustomProviderModel(id, ($event.target as HTMLInputElement).value)" />
              <button type="button" :disabled="providerModelLoading[id] || !canLoadProviderModels(provider)"
                @click="refreshProviderModels(id)">
                {{ providerModelLoading[id] ? t('settings.modelsLoading') : t('settings.modelsRefresh') }}
              </button>
            </span>
            <input v-if="modelOptions(id).length && usesCustomProviderModel(id, provider)" v-bind="attrs"
              class="model-custom-input" :value="providerDefaultModel(provider)"
              :placeholder="t('settings.defaultModelPlaceholder')"
              @input="updateCustomProviderModel(id, ($event.target as HTMLInputElement).value)" />
            <small v-if="providerModelErrors[id]" class="model-catalog-note" role="status">
              {{ providerModelErrors[id] }} · {{ t('settings.modelsCustomAllowed') }}
            </small>
            <small v-else-if="modelOptions(id).length" class="model-catalog-note">
              {{ t('settings.modelsLoaded', { count: modelOptions(id).length }) }} · {{ t('settings.modelsCustomAllowed') }}
            </small>
          </ConfigField>
          <label v-if="providerModelWindows[id]" class="model-context-window">{{ t('settings.modelContextWindow') }}
            <input readonly :value="`${providerModelWindows[id]?.tokens.toLocaleString()} Token`" />
            <small>{{ t('settings.modelContextSource.' + providerModelWindows[id]?.source) }}</small>
          </label>
          <small v-else class="model-catalog-note">{{ t('settings.modelContextFallbackHint') }}</small>
          <div v-if="providerProfileWarnings[id]" class="profile-model-warning" role="status">
            <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor"
              stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
              <path d="M10.3 4.6 2.8 17.5A2 2 0 0 0 4.5 20.5h15a2 2 0 0 0 1.7-3L13.7 4.6a2 2 0 0 0-3.4 0Z" />
              <path d="M12 9v4M12 16.5h.01" />
            </svg>
            <span>{{ providerProfileWarnings[id] }}</span>
          </div>
        </div>
        <div class="provider-layers">
          <span>{{ t('settings.profileDefaults') }}：{{ profileDefaults(provider).baseUrl || '—' }} · {{ profileDefaults(provider).defaultModel || '—' }}</span>
          <span>{{ t('settings.actualEffective') }}：{{ effectiveProvider(id).baseUrl || '—' }} · {{ effectiveProvider(id).defaultModel || '—' }}</span>
        </div>
        <details>
          <summary>{{ t('settings.providerAdvanced') }}</summary>
          <div v-if="!providerModelWindows[id]" class="form-grid">
            <ConfigField :path="`providers.${id}.context-window`" v-slot="{ attrs }">{{ t('settings.providerContextWindow') }}
              <input v-bind="attrs" type="number" min="1024" max="2000000"
                :value="provider['context-window'] ?? ''" :placeholder="t('settings.contextAuto')"
                @input="updateProvider(id, 'context-window', ($event.target as HTMLInputElement).value === '' ? null : Number(($event.target as HTMLInputElement).value))" />
              <small>{{ t('settings.providerContextWindowHint') }}</small>
            </ConfigField>
          </div>
          <ConfigField :path="`providers.${id}`" prefix v-slot="{ attrs }">{{ t('settings.advancedJson') }}
            <textarea v-bind="attrs" class="json-editor provider-json" :value="JSON.stringify(provider, null, 2)"
              @change="updateProviderJson(id, $event)" />
          </ConfigField>
        </details>
        <div class="provider-test">
          <button class="control-button" :disabled="Boolean(testing)" @click="testProvider(id)">
            {{ testing === id ? t('settings.providerProbeRunning') : t('settings.testProviderCall') }}
          </button>
          <span class="provider-test-note">{{ t('settings.providerProbeBillable') }}</span>
          <span v-if="providerTestResults[id]" class="provider-test-result"
            :class="providerTestResults[id].kind"
            :role="providerTestResults[id].kind === 'error' ? 'alert' : 'status'">
            {{ providerTestResults[id].message }}
          </span>
        </div>
      </article>
      <article class="provider-policy-card summarizer-card">
        <header>
          <strong>{{ t('settings.summarizerTitle') }}</strong>
          <label><input type="checkbox" :checked="summarizerConfig.enabled !== false"
            @change="updateSummarizer('enabled', ($event.target as HTMLInputElement).checked)" />
            {{ t('settings.summaryUseModel') }}</label>
        </header>
        <p>{{ t('settings.summarizerDesc') }}</p>
        <div class="form-grid">
          <ConfigField path="summarizer.provider" v-slot="{ attrs }">{{ t('settings.summaryProvider') }}
            <select v-bind="attrs" :value="summarizerConfig.provider || ''" :disabled="summarizerConfig.enabled === false"
              @change="selectSummarizerProvider(($event.target as HTMLSelectElement).value)">
              <option value="">{{ t('settings.useDefaultProvider') }}</option>
              <option v-for="(provider, id) in providers" v-show="provider.enabled !== false"
                :key="id" :value="id">{{ provider.label || id }}</option>
            </select>
          </ConfigField>
          <ConfigField path="summarizer.model" v-slot="{ attrs }">{{ t('settings.summaryModel') }}
            <select v-bind="attrs" :value="summarizerConfig.model || ''" :disabled="!summarizerProviderId || summarizerConfig.enabled === false"
              @change="selectSummarizerModel(($event.target as HTMLSelectElement).value)">
              <option v-if="!summarizerProviderId" value="">{{ t('settings.useActualTaskModel') }}</option>
              <option v-else value="">{{ t('settings.useProviderDefaultModel') }}</option>
              <option v-for="model in summarizerModelOptions" :key="model" :value="model">{{ model }}</option>
            </select>
            <small v-if="summarizerProviderId && providerModelLoading[summarizerProviderId]">
              {{ t('settings.modelsLoading') }}
            </small>
            <small v-else-if="summarizerProviderId && providerModelErrors[summarizerProviderId]" class="secret-error">
              {{ providerModelErrors[summarizerProviderId] }}
            </small>
          </ConfigField>
        </div>
        <details v-if="summarizerConfig.enabled !== false">
          <summary>{{ t('settings.advancedLimits') }}</summary>
          <div class="form-grid">
          <ConfigField path="summarizer.max-output-tokens" v-slot="{ attrs }">{{ t('settings.summaryMaxOutputTokens') }}
            <input v-bind="attrs" type="number" min="1" max="131072"
              :value="summaryNumberValue('max-output-tokens', 'maxOutputTokens', 16384)"
              @input="updateSummarizer('max-output-tokens', Number(($event.target as HTMLInputElement).value))" />
            <small>{{ t('settings.summaryMaxOutputTokensHint') }}</small>
          </ConfigField>
          <ConfigField path="summarizer.timeout-ms" v-slot="{ attrs }">{{ t('settings.summaryTimeout') }}
            <input v-bind="attrs" type="number" min="1" max="600"
              :value="summaryNumberValue('timeout-ms', 'timeoutMs', 180000) / 1000"
              @input="updateSummarizer('timeout-ms', Number(($event.target as HTMLInputElement).value) * 1000)" />
          </ConfigField>
          <ConfigField path="summarizer.attempt-timeout-ms" v-slot="{ attrs }">{{ t('settings.summaryAttemptTimeout') }}
            <input v-bind="attrs" type="number" min="1" max="600"
              :value="summaryNumberValue('attempt-timeout-ms', 'attemptTimeoutMs', 90000) / 1000"
              @input="updateSummarizer('attempt-timeout-ms', Number(($event.target as HTMLInputElement).value) * 1000)" />
            <small>{{ t('settings.summaryAttemptTimeoutHint') }}</small>
          </ConfigField>
          </div>
        </details>
        <small>{{ t('settings.summarizerFallbackNote') }}</small>
      </article>
    </div>

    <ImageSettings v-else-if="currentTab === 'images'" :writable="writable" />
    <WebSearchSettings v-else-if="currentTab === 'search'" :writable="writable" />
    <ImagePricingSettings v-else-if="currentTab === 'pricing'" :writable="writable" />
    <div v-else-if="currentTab === 'network'" class="control-section">
      <h3>{{ t('settings.networkProxy') }}</h3>
      <div class="form-grid">
        <ConfigField path="http.proxy" v-slot="{ attrs }" class="wide">{{ t('settings.proxyUrlLabel') }}
          <input v-bind="attrs" :value="httpConfig.proxy || ''" :placeholder="t('settings.proxyUrlPlaceholder')"
            @input="updateHttp('proxy', ($event.target as HTMLInputElement).value)" />
        </ConfigField>
        <ConfigField path="http.proxy-support" v-slot="{ attrs }">{{ t('settings.proxyPolicy') }}
          <select v-bind="attrs" :value="httpConfig.proxySupport || httpConfig['proxy-support'] || 'fallback'"
            @change="updateHttp('proxy-support', ($event.target as HTMLSelectElement).value)">
            <option value="fallback">{{ t('settings.proxyFallback') }}</option>
            <option value="on">{{ t('settings.proxyAlways') }}</option>
            <option value="off">{{ t('settings.proxyOff') }}</option>
          </select>
        </ConfigField>
        <ConfigField path="http.proxy-bypass" v-slot="{ attrs }">{{ t('settings.proxyBypass') }}
          <input v-bind="attrs" :value="(httpConfig.proxyBypass || httpConfig['proxy-bypass'] || []).join?.(', ') || ''"
            @input="updateHttp('proxy-bypass', ($event.target as HTMLInputElement).value.split(',').map(v => v.trim()).filter(Boolean))" />
        </ConfigField>
      </div>
    </div>

    <div v-else-if="currentTab === 'safety'" class="control-section">
      <ExecutionSettings />
      <div class="section-toolbar">
        <div><h3>{{ t('settings.safetyPermissions') }}</h3><p>{{ t('settings.safetyPermissionsDesc') }}</p></div>
        <span class="scope-chip">{{ t('settings.userPolicy') }}</span>
      </div>
      <article class="settings-explainer">
        <strong>{{ t('settings.permissionModesTitle') }}</strong>
        <p>{{ t('settings.permissionModesDesc') }}</p>
        <div class="permission-modes">
          <span><b>{{ t('settings.modeAsk') }}</b>{{ t('settings.permissionAsk') }}</span>
          <span><b>{{ t('settings.modeAcceptEdits') }}</b>{{ t('settings.permissionEdits') }}</span>
          <span><b>{{ t('settings.modeFullAuto') }}</b>{{ t('settings.permissionAuto') }}</span>
        </div>
      </article>
      <div class="safety-list">
        <ConfigField path="workspace.outside-write-policy" v-slot="{ attrs }" class="setting-toggle-row">
          <span><strong>{{ t('settings.outsideWritePolicy') }}</strong><small>{{ t('settings.outsideWritePolicyDesc') }}</small></span>
          <select v-bind="attrs" :value="outsideWritePolicy()"
            @change="updateOutsideWritePolicy(($event.target as HTMLSelectElement).value)">
            <option value="deny">{{ t('settings.outsideWriteDeny') }}</option>
            <option value="ask">{{ t('settings.outsideWriteAsk') }}</option>
            <option value="allow">{{ t('settings.outsideWriteAllow') }}</option>
          </select>
        </ConfigField>
        <label class="setting-toggle-row">
          <span><strong>{{ t('settings.respectIgnore') }}</strong><small>{{ t('settings.respectIgnoreDesc') }}</small></span>
          <input type="checkbox" :checked="safetyValue('respect-ignore-file', 'respectIgnoreFile', true)"
            @change="updateSafety('respect-ignore-file', ($event.target as HTMLInputElement).checked)" />
        </label>
        <label class="setting-toggle-row">
          <span><strong>{{ t('settings.redactSecrets') }}</strong><small>{{ t('settings.redactSecretsDesc') }}</small></span>
          <input type="checkbox" :checked="safetyValue('redact-secrets-in-tool-results', 'redactSecretsInToolResults', true)"
            @change="updateSafety('redact-secrets-in-tool-results', ($event.target as HTMLInputElement).checked)" />
        </label>
        <label class="setting-toggle-row">
          <span><strong>{{ t('settings.redactPii') }}</strong><small>{{ t('settings.redactPiiDesc') }}</small></span>
          <input type="checkbox" :checked="safetyValue('redact-pii-in-tool-results', 'redactPiiInToolResults', false)"
            @change="updateSafety('redact-pii-in-tool-results', ($event.target as HTMLInputElement).checked)" />
        </label>
      </div>
      <label class="protected-editor">{{ t('settings.protectedPaths') }}
        <textarea :value="(safetyValue('protected-globs', 'protectedGlobs', []) || []).join('\n')"
          @change="updateSafety('protected-globs', ($event.target as HTMLTextAreaElement).value.split(/\r?\n/).map(v => v.trim()).filter(Boolean))" />
        <small>{{ t('settings.protectedPathsDesc') }}</small>
      </label>
      <details class="file-protection-note">
        <summary>{{ t('settings.fileProtectionTitle') }}</summary>
        <p>{{ t('settings.fileProtectionEdit') }}</p>
        <p>{{ t('settings.fileProtectionOverwrite') }}</p>
      </details>
    </div>

    <div v-else-if="currentTab === 'toolsets'" class="control-section toolset-section">
      <div class="section-toolbar">
        <div><h3>{{ t('settings.userToolsets') }}</h3><p>{{ t('settings.userToolsetsDesc') }}</p></div>
        <div class="toolset-toolbar-actions">
          <span class="scope-chip">{{ t('settings.scopeUserShort') }}</span>
          <button type="button" class="control-button" :disabled="!writable" @click="toggleToolsetEditing">
            {{ toolsetEditing ? t('settings.finishEditing') : t('settings.customizeToolsets') }}
          </button>
        </div>
      </div>
      <fieldset v-if="Object.keys(editableToolsets).length" class="toolset-defaults"
        data-config-field="toolsets.default-set" :disabled="!writable || config.loading"
        aria-describedby="toolset-defaults-description">
        <legend>{{ t('settings.defaultToolsets') }}</legend>
        <p id="toolset-defaults-description">{{ t('settings.defaultToolsetsDesc') }}</p>
        <div class="toolset-default-options">
          <label v-for="(_, name) in editableToolsets" :key="name" class="toolset-default-option"
            :class="{ selected: defaultToolset === name }">
            <input type="radio" name="default-toolset" :value="name" :checked="defaultToolset === name"
              @change="setDefaultToolset(name)" />
            <span>{{ name }}</span>
          </label>
        </div>
        <p v-if="!defaultToolset" class="toolset-default-notice" role="status">{{ t('settings.toolsetDefaultRequired') }}</p>
        <details v-else class="toolset-default-preview">
          <summary>{{ t('settings.defaultToolsetTools', { n: defaultToolsetTools.length }) }}</summary>
          <div class="toolset-tools">
            <code v-for="tool in defaultToolsetTools" :key="tool">{{ tool }}</code>
          </div>
        </details>
      </fieldset>
      <div v-if="!toolsetEditing && Object.keys(editableToolsets).length" class="toolset-list">
        <article v-for="(tools, name) in displayedToolsets" :key="name" class="toolset-card">
          <header><strong>{{ name }}</strong><span v-if="defaultToolset === name" class="toolset-default-badge">{{ t('settings.defaultToolset') }}</span><span>{{ t('settings.toolsCount', { n: tools.length }) }}</span></header>
          <p v-if="name === 'vscode' && inheritedVscodeToolset" class="toolset-inheritance">{{ t('settings.vscodeToolsetInherited') }}</p>
          <div class="toolset-tools">
            <code v-for="tool in tools" :key="tool">{{ tool }}</code>
          </div>
          <div v-for="server in toolsetMcpServers[name] || []" :key="server.name" class="toolset-mcp-server">
            <div><strong>MCP · {{ server.name }}</strong><span>{{ mcpStatusLabel(server.status) }}</span></div>
            <small>{{ server.status === 'connected' ? t('settings.toolsCount', { n: server.toolCount })
              : server.status === 'not_started' || server.status === 'on_demand' ? t('settings.mcpDeferredTools')
                : server.status === 'unknown' ? t('settings.mcpUnknownHint')
                  : server.status === 'disconnected' ? t('settings.mcpDisconnectedHint') : '' }}</small>
          </div>
        </article>
      </div>
      <div v-else-if="!toolsetEditing" class="settings-state">{{ t('settings.toolsetsUnavailable') }}</div>
      <div v-else class="toolset-editor-list">
        <p class="toolset-edit-note">{{ t('settings.customizeToolsetsDesc') }}</p>
        <article v-for="(tools, name) in editableToolsets" :key="name" class="toolset-edit-card">
          <header>
            <strong>{{ name }}</strong>
            <button v-if="name !== 'vscode' || !inheritedVscodeToolset" type="button" class="danger-link" @click="removeToolset(name)">{{ t('settings.remove') }}</button>
          </header>
          <textarea :value="tools.join('\n')" :aria-label="t('settings.toolsetToolsLabel', { name })"
            @change="updateToolsetTools(name, $event)" />
          <small>{{ name === 'vscode' && inheritedVscodeToolset ? t('settings.vscodeToolsetInherited') : t('settings.toolsetToolsHint') }}</small>
        </article>
        <button type="button" class="control-button add-toolset" @click="addToolset">{{ t('settings.addToolset') }}</button>
        <details class="advanced-config">
          <summary>{{ t('settings.advancedJson') }}</summary>
          <ConfigField path="toolsets" prefix v-slot="{ attrs }">
            <textarea v-bind="attrs" class="json-editor" :value="toolsetJson()" @change="updateJson('toolsets', $event)" />
          </ConfigField>
        </details>
      </div>
    </div>

    <div v-else-if="currentTab === 'memory'" class="control-section memory-section">
      <template v-if="memory">
        <div class="memory-summary">
          <div><span>{{ t('settings.effectiveProfile') }}</span><strong>{{ memory.effectiveProfileSource }}</strong></div>
          <div><span>{{ t('settings.storedCharacters') }}</span><strong>{{ memory.limits.storedCharacters }}</strong></div>
          <div><span>{{ t('settings.perDocumentLimit') }}</span><strong>{{ memory.limits.perFileCharacters }}</strong></div>
        </div>
        <p class="memory-intro">{{ t('settings.memoryDataLogic') }}</p>
        <div class="memory-docs">
          <article class="memory-document">
            <header><div><span class="scope-chip">{{ t('settings.scopeUserShort') }}</span><strong>{{ t('settings.userProfile') }}</strong></div>
              <small>{{ t('settings.userProfileScope') }}</small></header>
            <textarea v-model="memory.user.profile" />
            <footer>
              <button class="danger-link" @click="clearMemory('USER', 'profile')">{{ t('settings.clear') }}</button>
              <button :disabled="!memoryDirty('USER', 'profile')" @click="resetMemory('USER', 'profile')">{{ t('settings.discard') }}</button>
              <button class="primary-action" :disabled="!memoryDirty('USER', 'profile')"
                @click="saveMemory('USER', 'profile', memory.user.profile)">{{ t('settings.saveUserProfile') }}</button>
            </footer>
          </article>
          <article class="memory-document">
            <header><div><span class="scope-chip">{{ t('settings.scopeWorkspaceShort') }}</span><strong>{{ t('settings.workspaceOverride') }}</strong></div>
              <small>{{ t('settings.workspaceProfileScope') }}</small></header>
            <textarea v-model="memory.workspace.profile" />
            <footer>
              <button class="danger-link" @click="clearMemory('WORKSPACE', 'profile')">{{ t('settings.clear') }}</button>
              <button :disabled="!memoryDirty('WORKSPACE', 'profile')" @click="resetMemory('WORKSPACE', 'profile')">{{ t('settings.discard') }}</button>
              <button class="primary-action" :disabled="!memoryDirty('WORKSPACE', 'profile')"
                @click="saveMemory('WORKSPACE', 'profile', memory.workspace.profile)">{{ t('settings.saveOverride') }}</button>
            </footer>
          </article>
          <article class="memory-document">
            <header><div><span class="scope-chip">{{ t('settings.scopeWorkspaceShort') }}</span><strong>{{ t('settings.workspaceMemory') }}</strong></div>
              <small>{{ t('settings.workspaceMemoryScope') }}</small></header>
            <textarea v-model="memory.workspace.memory" />
            <footer>
              <button class="danger-link" @click="clearMemory('WORKSPACE', 'memory')">{{ t('settings.clear') }}</button>
              <button :disabled="!memoryDirty('WORKSPACE', 'memory')" @click="resetMemory('WORKSPACE', 'memory')">{{ t('settings.discard') }}</button>
              <button class="primary-action" :disabled="!memoryDirty('WORKSPACE', 'memory')"
                @click="saveMemory('WORKSPACE', 'memory', memory.workspace.memory)">{{ t('settings.saveMemory') }}</button>
            </footer>
          </article>
          <article class="memory-document">
            <header><div><span class="scope-chip">{{ t('settings.scopeWorkspaceShort') }}</span><strong>{{ t('settings.workspaceLessons') }}</strong></div>
              <small>{{ t('settings.workspaceLessonsScope') }}</small></header>
            <textarea v-model="memory.workspace.lessons" />
            <footer>
              <button class="danger-link" @click="clearMemory('WORKSPACE', 'lessons')">{{ t('settings.clear') }}</button>
              <button :disabled="!memoryDirty('WORKSPACE', 'lessons')" @click="resetMemory('WORKSPACE', 'lessons')">{{ t('settings.discard') }}</button>
              <button class="primary-action" :disabled="!memoryDirty('WORKSPACE', 'lessons')"
                @click="saveMemory('WORKSPACE', 'lessons', memory.workspace.lessons)">{{ t('settings.saveLessons') }}</button>
            </footer>
          </article>
        </div>
      </template>
    </div>

    <div v-else-if="currentTab === 'skills'" class="control-section">
      <div class="section-toolbar"><div><h3>{{ t('settings.skillsTitle') }}</h3><p>{{ t('settings.skillsMergeDesc') }}</p></div>
        <button class="control-button" @click="rescanSkills">{{ t('settings.rescan') }}</button></div>
      <div class="skill-list">
        <div v-for="skill in skills" :key="`${skill.source}:${skill.name}`" class="skill-row">
          <div><strong>{{ skill.name }}</strong><p class="skill-description" :title="skill.description">{{ skill.description }}</p></div>
          <span :class="['source-chip', skill.source.toLowerCase()]">{{ skill.source }}</span>
          <span v-if="skill.overridden" class="state-chip">{{ t('settings.overridden') }}</span>
          <span v-if="skill.effective" class="state-chip effective">{{ t('settings.effective') }}</span>
          <button :class="{ enabled: skill.enabled }" @click="toggleSkill(skill)">
            {{ skill.enabled ? t('settings.statusEnabled') : t('settings.statusDisabled') }}
          </button>
        </div>
      </div>
    </div>

    <div v-else-if="currentTab === 'integrations'" class="control-section split-editors">
      <div class="integration-intro">
        <strong>{{ t('settings.integrationApplyTitle') }}</strong>
        <p>{{ t('settings.integrationApplyDesc') }}</p>
      </div>
      <div class="integration-editor">
        <ConfigField path="mcp" prefix v-slot="{ attrs }">MCP <small>{{ t('settings.mcpLogic') }}</small>
          <textarea v-bind="attrs" class="json-editor" :value="jsonSection('mcp')" @change="updateJson('mcp', $event)" />
        </ConfigField>
        <details class="config-example">
          <summary>{{ t('settings.configExample') }}</summary>
          <button type="button" @click="copyConfigExample(MCP_CONFIG_EXAMPLE)">{{ t('settings.copyExample') }}</button>
          <pre>{{ MCP_CONFIG_EXAMPLE }}</pre>
        </details>
      </div>
      <div class="integration-editor">
        <ConfigField path="hooks" prefix v-slot="{ attrs }">Hooks <small>{{ t('settings.hooksLogic') }}</small>
          <textarea v-bind="attrs" class="json-editor" :value="jsonSection('hooks')" @change="updateJson('hooks', $event)" />
        </ConfigField>
        <details class="config-example">
          <summary>{{ t('settings.configExample') }}</summary>
          <button type="button" @click="copyConfigExample(HOOKS_CONFIG_EXAMPLE)">{{ t('settings.copyExample') }}</button>
          <pre>{{ HOOKS_CONFIG_EXAMPLE }}</pre>
        </details>
      </div>
      <footer class="integration-actions">
        <span>{{ currentTabDirty ? t('settings.unsaved') : t('settings.noDraftChanges') }}</span>
        <button :disabled="!currentTabDirty" @click="config.resetDraft">{{ t('settings.discard') }}</button>
        <button class="primary-action" :disabled="!currentTabDirty || config.loading || !writable"
          @click="saveConfiguration">{{ t('settings.validateSave') }}</button>
      </footer>
    </div>

    <div v-else class="control-section diagnostics">
      <dl>
        <dt>{{ t('settings.configRevision') }}</dt><dd><code>{{ config.revision }}</code></dd>
        <dt>{{ t('settings.applyMode') }}</dt><dd>{{ config.document?.reloadPolicy }}</dd>
        <dt>{{ t('settings.userConfigFile') }}</dt><dd>{{ config.document?.fileExists ? t('settings.fileCreated') : t('settings.fileNotCreated') }}</dd>
        <dt>{{ t('settings.currentWorkspace') }}</dt><dd>{{ workspace.activeWorkspaceId || t('settings.directMode') }}</dd>
        <dt>{{ t('settings.instanceId') }}</dt><dd><code>{{ workspace.instanceId || t('settings.directMode') }}</code></dd>
        <dt>{{ t('settings.protocolLabel') }}</dt><dd><code>{{ runtime.protocol?.version || '—' }}</code></dd>
        <dt>{{ t('settings.runtimeLabel') }}</dt><dd><code>{{ runtime.protocol?.runtimeVersion || '—' }}</code></dd>
        <dt>{{ t('settings.dataDirectory') }}</dt><dd class="storage-path"><code>{{ storagePaths.dataDir || '—' }}</code>
          <button v-if="storagePaths.canOpenDirectory" type="button" @click="openDataDirectory">{{ t('settings.openDataDirectory') }}</button></dd>
        <dt>{{ t('settings.userDirectory') }}</dt><dd class="storage-path"><code>{{ storagePaths.userDataDir || '—' }}</code></dd>
        <template v-if="storagePaths.projectSkillsDir"><dt>{{ t('settings.projectSkillsDirectory') }}</dt><dd class="storage-path"><code>{{ storagePaths.projectSkillsDir }}</code></dd></template>
      </dl>
      <details><summary>{{ t('settings.runtimeDiagnostics') }}</summary><pre>{{ JSON.stringify(runtime.diagnostics, null, 2) }}</pre></details>
      <details><summary>{{ t('settings.effectiveConfig') }}</summary><pre>{{ JSON.stringify(config.effective, null, 2) }}</pre></details>
    </div>

    </template>

    <div v-if="localError" class="control-errors" role="alert">
      {{ localError }}
    </div>
    <div v-if="config.message || localMessage" class="control-message">{{ config.message || localMessage }}</div>

    <footer v-if="currentTabDirty" class="dirty-bar">
      <span :class="{ 'validation-needed': config.errors.length }">{{ config.errors.length ? t('settings.fixErrors') : t('settings.unsaved') }}</span>
      <div><button @click="config.resetDraft">{{ t('settings.discard') }}</button>
        <button :disabled="config.loading || !writable || applyingConfiguration || renamingProvider || workspace.switching"
          @click="saveConfiguration">{{ t('settings.validateSave') }}</button></div>
    </footer>
    <footer v-else-if="config.message || config.restartRequired || config.reloadError" class="dirty-bar saved">
      <span>{{ applyingConfiguration ? t('settings.applyingConfiguration') : config.message || t(config.reloadError ? 'settings.savedNotApplied' : config.modelsPendingReconnect ? 'settings.savedModelsPendingReconnect' : 'settings.savedMixed') }}</span>
      <button v-if="config.reloadError" :disabled="config.loading || config.dirty || applyingConfiguration || workspace.switching"
        @click="config.applySavedConfiguration">{{ t('settings.reapplyConfiguration') }}</button>
      <button v-if="config.restartRequired" :disabled="applyingConfiguration || workspace.switching"
        @click="restartRuntime">{{ t('settings.reopenCurrent') }}</button>
    </footer>
  </section>
</template>

<style scoped>
.control-plane { padding:0; overflow:hidden; margin-bottom:18px; }
.control-bootstrap { margin-top:18px; }
.control-head { display:flex; justify-content:space-between; gap:16px; padding:20px 22px 14px; }
.control-head h2 { margin:0; font-size:18px; }.control-head p,.control-section p { margin:4px 0 0; color:var(--muted); font-size:12px; }
.scope-chip,.source-chip,.state-chip { align-self:flex-start; border:1px solid var(--border); border-radius:999px; padding:3px 7px; font-size:12px; letter-spacing:.05em; }
.settings-tabs { display:flex; gap:2px; overflow:auto; padding:0 14px; border-bottom:1px solid var(--border); }
.settings-tabs button { border:0; border-radius:6px 6px 0 0; background:transparent; padding:9px 10px; white-space:nowrap; }
.settings-tabs button.active { color:var(--accent); background:var(--accent-soft); }
.control-section { padding:18px 22px 72px; min-height:220px; }
.section-toolbar { display:flex; justify-content:space-between; gap:14px; align-items:flex-start; margin-bottom:12px; }
.section-toolbar h3,.control-section h3 { margin:0; }
.fallback-policy { margin:0 0 10px; padding:9px 10px; border-left:2px solid var(--accent); background:var(--accent-soft); }
.fallback-policy strong { font-size:12px; }
.fallback-policy p { line-height:1.45; }
.provider-policy-card { border:1px solid var(--border); border-radius:10px; padding:12px; margin:10px 0; background:var(--surface-2); }
.provider-policy-card > header { display:flex; align-items:center; gap:12px; margin-bottom:5px; }
.provider-policy-card > header strong { flex:1; }
.provider-policy-card > header label { display:flex; align-items:center; gap:5px; font-size:12px; }
.provider-policy-card > p,.provider-policy-card > small { color:var(--text-muted); line-height:1.45; }
.form-grid label.toggle-field { flex-direction:row; align-items:center; justify-content:space-between; }
.form-grid label.toggle-field input { width:auto; }
.provider-editor { border:1px solid var(--border); border-radius:10px; padding:12px; margin:10px 0; }
.provider-editor header { display:flex; gap:12px; align-items:center; margin-bottom:10px; }.provider-editor header strong { flex:1; }
.provider-editor header label { display:flex; flex-direction:row; gap:5px; font-size:12px; }
.provider-name { cursor:text; border-radius:3px; }
.provider-name:hover { color:var(--accent); }
.provider-name:focus-visible { outline:2px solid var(--accent); outline-offset:3px; }
.provider-layers { display:flex; flex-direction:column; gap:3px; margin:7px 0; color:var(--muted); font-size:12px; }
.provider-json { min-height:130px; margin:7px 0; }
.provider-test { display:flex; align-items:center; flex-wrap:wrap; gap:9px; margin-top:10px; }
.provider-test-note { color:var(--muted); font-size:11px; }
.provider-test-result { min-width:0; font-size:12px; line-height:1.4; }
.provider-test-result.success { color:var(--success); }
.provider-test-result.warning { color:var(--warning, #a87925); }
.provider-test-result.error { color:var(--danger); }
.danger-link { border:0; background:transparent; color:var(--danger); font-size:12px; }
.form-grid { display:grid; grid-template-columns:1fr 1fr; gap:9px; margin:10px 0; }
.form-grid label,.memory-grid label,.split-editors label { display:flex; flex-direction:column; gap:4px; color:var(--muted); font-size:12px; }
.form-grid label.wide { grid-column:1/-1; }.form-grid input,.form-grid select { width:100%; }
.secret-input { display:flex; align-items:center; position:relative; }
.secret-input input { padding-right:34px; }
.secret-input button { position:absolute; right:4px; width:28px; height:28px; display:grid; place-items:center; border:0; background:transparent; color:var(--text-muted); }
.secret-input button:disabled { cursor:not-allowed; opacity:.45; }
.secret-storage-note { color:var(--text-muted); line-height:1.4; }
.secret-error { color:var(--danger); line-height:1.4; }
.model-catalog-input { display:flex; gap:6px; }
.model-catalog-input input,.model-catalog-input select { min-width:0; flex:1; }
.model-catalog-input button { flex:0 0 auto; border:1px solid var(--border); border-radius:6px; background:var(--surface-2); padding:0 9px; color:var(--text-secondary); }
.model-catalog-input button:disabled { cursor:not-allowed; opacity:.5; }
.model-custom-input { margin-top:2px; }
.model-catalog-note { color:var(--text-muted); line-height:1.4; }
.profile-model-warning { grid-column:1/-1; display:flex; align-items:flex-start; gap:7px; color:color-mix(in srgb,var(--warning) 78%,var(--text-secondary)); font-size:12px; font-weight:500; line-height:1.55; }
.profile-model-warning svg { flex:0 0 15px; margin-top:2px; color:var(--warning); }
.profile-model-warning span { min-width:0; overflow-wrap:anywhere; }
.json-editor,.memory-grid textarea { width:100%; min-height:220px; resize:vertical; font:12px/1.5 var(--font-mono); }
.memory-grid { display:grid; grid-template-columns:1fr 1fr; gap:12px; }.memory-grid textarea { min-height:150px; }
.memory-grid label span { float:right; }.memory-grid button { align-self:flex-start; }
.skill-list { display:flex; flex-direction:column; gap:6px; }
.skill-row { display:flex; align-items:center; gap:8px; padding:9px 10px; border:1px solid var(--border); border-radius:8px; }
.skill-row > div { min-width:0; flex:1; }
.skill-description { display:-webkit-box; overflow:hidden; -webkit-box-orient:vertical; -webkit-line-clamp:2; line-height:1.45; }
.skill-row:hover .skill-description,.skill-description:focus { display:block; overflow:visible; }
.source-chip.user { color:var(--accent); }.source-chip.workspace { color:var(--success); }.state-chip { color:var(--muted); }
.state-chip.effective { color:var(--success); }.skill-row button.enabled { color:var(--success); }
.state-chip.warning { color:var(--danger); }
.split-editors { display:grid; grid-template-columns:1fr 1fr; gap:14px; }
.integration-intro,.integration-actions { grid-column:1/-1; }
.integration-intro { padding:12px; border:1px solid var(--border); border-radius:8px; background:var(--accent-soft); }
.integration-intro p { margin-top:4px; }
.integration-actions { display:flex; align-items:center; justify-content:flex-end; gap:7px; padding-top:4px; }
.integration-actions span { margin-right:auto; color:var(--text-muted); font-size:12px; }
.integration-actions button,.memory-document footer button { min-height:29px; border:1px solid var(--border); border-radius:6px; background:var(--surface-2); padding:0 9px; }
.integration-editor { min-width:0; }
.config-example { position:relative; margin-top:8px; border:1px solid var(--border); border-radius:8px; padding:8px 10px; background:var(--surface-2); }
.config-example summary { cursor:pointer; color:var(--text-muted); font-size:12px; }
.config-example button { position:absolute; top:5px; right:7px; min-height:25px; border:1px solid var(--border); border-radius:6px; background:var(--surface-1); padding:0 7px; font-size:11px; }
.config-example pre { max-height:330px; margin:9px 0 0; overflow:auto; white-space:pre; font:11px/1.5 var(--font-mono); color:var(--text-muted); }
.primary-action { border-color:var(--accent)!important; background:var(--accent)!important; color:var(--accent-contrast)!important; }
.settings-explainer { margin:14px 0; padding:13px; border:1px solid var(--border); border-radius:9px; background:var(--surface-2); }
.settings-explainer > p { margin:4px 0 10px; }
.permission-modes { display:grid; grid-template-columns:repeat(3,1fr); gap:6px; }
.permission-modes span { padding:8px; border:1px solid var(--border); border-radius:7px; color:var(--text-muted); font-size:12px; }
.permission-modes b { display:block; margin-bottom:3px; color:var(--text-primary); font-size:12px; }
.file-protection-note { margin-top:12px; color:var(--text-muted); font-size:12px; line-height:1.5; }
.file-protection-note summary { cursor:pointer; color:var(--text-secondary); }
.file-protection-note p { margin:8px 0 0; }
.safety-list { display:grid; gap:1px; border:1px solid var(--border); border-radius:9px; overflow:hidden; }
.setting-toggle-row { min-height:57px; display:flex; align-items:center; justify-content:space-between; gap:16px; padding:9px 12px; background:var(--surface-1); }
.setting-toggle-row + .setting-toggle-row { border-top:1px solid var(--border); }
.setting-toggle-row strong,.setting-toggle-row small { display:block; }
.setting-toggle-row strong { color:var(--text-primary); font-size:12px; }
.setting-toggle-row small { margin-top:2px; color:var(--text-muted); font-size:12px; }
.setting-toggle-row input { width:16px; height:16px; }
.setting-toggle-row select { min-width:190px; }
.protected-editor { display:grid; gap:5px; margin-top:14px; color:var(--text-muted); font-size:12px; }
.protected-editor textarea { min-height:120px; resize:vertical; font:12px/1.5 var(--font-mono); }
.toolset-list { display:grid; grid-template-columns:1fr 1fr; gap:8px; margin-top:14px; }
.toolset-section .section-toolbar { flex-wrap:wrap; }
.toolset-section .section-toolbar > div:first-child { flex:1 1 300px; }
.toolset-card { min-width:0; padding:11px; border:1px solid var(--border); border-radius:8px; background:var(--surface-1); }
.toolset-card header { display:flex; justify-content:space-between; gap:8px; }
.toolset-card header strong { flex:1; overflow-wrap:anywhere; }
.toolset-card header span { color:var(--text-muted); font-size:12px; }
.toolset-card header .toolset-default-badge { color:var(--accent); }
.toolset-defaults { min-width:0; margin:18px 0 0; padding:14px 16px 16px; border:1px solid var(--border); border-radius:12px; background:var(--surface-1); }
.toolset-defaults legend { padding:0 6px; color:var(--text-primary); font-size:13px; font-weight:600; }
.toolset-defaults p { margin:0 0 12px; color:var(--text-muted); font-size:12px; line-height:1.6; }
.toolset-default-options { display:flex; flex-wrap:wrap; gap:8px; }
.toolset-default-option { display:flex; align-items:center; gap:8px; min-width:0; padding:8px 12px; border:1px solid var(--border); border-radius:8px; color:var(--text-secondary); font-size:13px; cursor:pointer; }
.toolset-default-option span { overflow-wrap:anywhere; }
.toolset-default-option.selected { border-color:color-mix(in srgb,var(--accent) 45%,var(--border)); background:var(--accent-soft); color:var(--text-primary); }
.toolset-default-option input { flex-shrink:0; width:14px; height:14px; margin:0; accent-color:var(--accent); }
.toolset-default-option:focus-within { outline:2px solid var(--accent); outline-offset:2px; }
.toolset-defaults:disabled .toolset-default-option { cursor:not-allowed; opacity:.65; }
.toolset-defaults .toolset-default-notice { margin:12px 0 0; color:var(--warning); }
.toolset-default-preview { margin-top:12px; color:var(--text-muted); font-size:12px; }
.toolset-default-preview summary { width:fit-content; cursor:pointer; line-height:1.6; }
.toolset-default-preview summary:hover { color:var(--text-primary); }
.toolset-default-preview summary:focus-visible { outline:2px solid var(--accent); outline-offset:3px; border-radius:3px; }
.toolset-tools { display:flex; flex-wrap:wrap; gap:5px; margin-top:9px; }
.toolset-tools code { border:1px solid var(--border); border-radius:5px; background:var(--surface-2); padding:3px 6px; font-size:11px; }
.toolset-inheritance { margin:8px 0 0; color:var(--text-muted); font-size:11px; line-height:1.5; }
.toolset-mcp-server { margin-top:10px; padding-top:9px; border-top:1px solid var(--border); font-size:12px; }
.toolset-mcp-server > div { display:flex; justify-content:space-between; flex-wrap:wrap; gap:5px 10px; }
.toolset-mcp-server strong { overflow-wrap:anywhere; }
.toolset-mcp-server span,.toolset-mcp-server small { color:var(--text-muted); }
.toolset-mcp-server small { display:block; margin-top:4px; line-height:1.5; }
.toolset-toolbar-actions { display:flex; align-items:center; gap:8px; flex:0 0 auto; white-space:nowrap; }
.toolset-editor-list { display:grid; gap:9px; }
.toolset-edit-note { margin-bottom:2px!important; }
.toolset-edit-card { padding:11px; border:1px solid var(--border); border-radius:8px; background:var(--surface-1); }
.toolset-edit-card header { display:flex; align-items:center; gap:12px; margin-bottom:8px; }
.toolset-edit-card header strong { flex:1; }
.toolset-edit-card textarea { width:100%; min-height:112px; resize:vertical; font:12px/1.5 var(--font-mono); }
.toolset-edit-card small { display:block; margin-top:5px; color:var(--text-muted); font-size:11px; }
.add-toolset { justify-self:start; }
.advanced-config { margin-top:14px; border-top:1px solid var(--border); padding-top:12px; }
.advanced-config summary { cursor:pointer; font-size:12px; font-weight:650; }
.advanced-config .json-editor { margin-top:9px; }
.memory-summary { display:grid; grid-template-columns:repeat(3,1fr); gap:7px; }
.memory-summary > div { padding:10px; border:1px solid var(--border); border-radius:8px; background:var(--surface-2); }
.memory-summary span,.memory-summary strong { display:block; }
.memory-summary span { color:var(--text-muted); font-size:12px; }
.memory-summary strong { margin-top:4px; font:12px var(--font-mono); }
.memory-intro { margin:12px 0!important; padding:10px; border-left:2px solid var(--accent); background:var(--accent-soft); }
.memory-docs { display:grid; grid-template-columns:1fr 1fr; gap:10px; }
.memory-document { display:flex; flex-direction:column; min-height:245px; padding:12px; border:1px solid var(--border); border-radius:9px; background:var(--surface-1); }
.memory-document header { display:flex; justify-content:space-between; gap:10px; margin-bottom:8px; }
.memory-document header > div { display:flex; align-items:center; gap:7px; }
.memory-document header small { color:var(--text-muted); font-size:12px; text-align:right; }
.memory-document textarea { min-height:145px; flex:1; resize:vertical; font:12px/1.5 var(--font-mono); }
.memory-document footer { display:flex; justify-content:flex-end; gap:6px; margin-top:8px; }
.memory-document footer .danger-link { margin-right:auto; }
.diagnostics dl { display:grid; grid-template-columns:140px 1fr; gap:7px; }.diagnostics dt { color:var(--muted); }.diagnostics dd { margin:0; overflow:hidden; text-overflow:ellipsis; }
.diagnostics pre { overflow:auto; max-height:360px; font-size:12px; }
.diagnostics .storage-path { overflow:visible; overflow-wrap:anywhere; display:flex; align-items:center; flex-wrap:wrap; gap:8px; }
.settings-state { padding:24px; color:var(--muted); text-align:center; }
.control-errors,.control-message { margin:0 22px 12px; padding:9px 11px; border-radius:8px; font-size:12px; }
.control-errors { background:var(--danger-soft); color:var(--danger); }.control-message { background:var(--accent-soft); color:var(--accent); }
.validation-summary { margin-top:12px; scroll-margin-block:20px; }
.validation-summary ul { margin:6px 0 0; padding-left:18px; }
.validation-summary button { border:0; background:transparent; padding:3px 0; color:inherit; cursor:pointer; text-decoration:underline; text-align:left; overflow-wrap:anywhere; }
.validation-needed { color:var(--danger); }
.dirty-bar { position:sticky; bottom:0; display:flex; justify-content:space-between; align-items:center; gap:10px; padding:10px 18px; background:var(--bg2); border-top:1px solid var(--border); box-shadow:0 -6px 18px color-mix(in srgb, var(--bg) 70%, transparent); }
.dirty-bar > div { display:flex; gap:6px; }
.dirty-bar.saved { color:var(--success); }
.control-plane.embedded { overflow:visible; margin:0; background:transparent; }
.embedded .control-section { min-height:0; padding:0 0 76px; }
.embedded .provider-editor {
  border-color:var(--border);
  border-radius:9px;
  background:var(--surface-1);
  padding:14px;
}
.embedded .form-grid { gap:11px; }
.embedded .form-grid label,
.embedded .memory-grid label,
.embedded .split-editors label { color:var(--text-muted); font-size:12px; }
.embedded input,.embedded select,.embedded textarea {
  border-color:var(--border);
  background:var(--surface-1);
  color:var(--text-primary);
}
.embedded .skill-row { min-height:52px; border-radius:8px; background:var(--surface-1); }
@media (max-width:760px) {
  .form-grid,.memory-docs,.toolset-list,.split-editors,.permission-modes,.memory-summary { grid-template-columns:1fr; }
  .integration-intro,.integration-actions { grid-column:1; }
}
.control-button,
.provider-editor > button,
.skill-row > button {
  min-height:29px;
  border:1px solid var(--border);
  border-radius:6px;
  background:var(--surface-2);
  padding:0 9px;
  color:var(--text-secondary);
  font-size:12px;
}
.control-button:hover,
.provider-editor > button:hover,
.skill-row > button:hover { border-color:var(--border-strong); background:var(--surface-hover); }
.embedded .dirty-bar {
  position:fixed;
  z-index:30;
  right:max(24px,calc((100vw - 238px - 740px) / 2));
  bottom:18px;
  width:min(740px,calc(100vw - 286px));
  border:1px solid var(--border-strong);
  border-radius:9px;
  box-shadow:var(--shadow);
}
.dirty-bar button { white-space:nowrap; min-height:32px; padding:4px 9px; }
@media(max-width:760px) {
  .control-plane.embedded { padding-bottom:100px; }
  .embedded .dirty-bar { right:12px; bottom:max(12px,env(safe-area-inset-bottom)); width:calc(100vw - 24px); padding:10px 12px; flex-wrap:wrap; background:var(--surface-2); }
  .embedded .dirty-bar > span { width:100%; font-size:12px; }
  .embedded .dirty-bar > div { margin-left:auto; }
}
@media (max-width:800px) { .form-grid,.memory-grid,.split-editors { grid-template-columns:1fr; }.form-grid label.wide { grid-column:auto; }.skill-row { flex-wrap:wrap; } }
</style>
