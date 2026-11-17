import { isRecord, type UnknownRecord } from '../utils/unknown'

export interface ProviderConfig extends UnknownRecord {
  enabled?: boolean
  profile?: string
  'base-url'?: string
  'api-key'?: string
  'default-model'?: string
  models?: unknown[]
}

const wireFields = new Set(['request-options', 'enabled-object', 'disabled-object', 'enabled-value', 'disabled-value'])

function kebabCase(key: string) {
  return key.replace(/([a-z0-9])([A-Z])/g, '$1-$2').replace(/_/g, '-').toLowerCase()
}

// DTO fields have one editable spelling. Explicit kebab-case values, including empty strings,
// take precedence over legacy camelCase aliases, independently of JSON/YAML property order.
export function normalizeProviderConfig(value: UnknownRecord): ProviderConfig {
  function normalize(value: unknown): unknown {
    if (Array.isArray(value)) return value.map(normalize)
    if (!isRecord(value)) return value
    const result: UnknownRecord = {}
    for (const [key, item] of Object.entries(value)) {
      const canonical = kebabCase(key)
      if (key !== canonical && Object.prototype.hasOwnProperty.call(value, canonical)) continue
      if (item == null) continue
      result[canonical] = wireFields.has(canonical) ? item : normalize(item)
    }
    return result
  }
  return normalize(value) as ProviderConfig
}

export function editProviderConfig(provider: ProviderConfig, key: string, value: unknown, previousProfileBaseUrl = '') {
  const next = normalizeProviderConfig(provider)
  const canonical = kebabCase(key)
  if (next[canonical] !== value && canonical === 'base-url') {
    delete next.models
    delete next['default-model']
  }
  if (next[canonical] !== value && canonical === 'profile') {
    // Keep the selected model while correcting its protocol, especially on multi-vendor relays.
    // A vendor's default endpoint/catalogue can follow the new profile without losing that choice.
    const url = String(next['base-url'] || '').trim().replace(/\/+$/, '')
    if (!url || url === previousProfileBaseUrl.trim().replace(/\/+$/, '')) {
      delete next['base-url']
      delete next.models
    }
  }
  next[canonical] = value
  return next
}

function modelFamily(model: string): string | undefined {
  const families: Array<[string, RegExp]> = [
    ['OpenAI', /(?:^|[/:])(?:gpt-|chatgpt-|o\d+(?:-|$)|codex(?:-|$))/i],
    ['Claude', /(?:^|[/:])claude(?:-|$)/i],
    ['Gemini', /(?:^|[/:])gemini(?:-|$)/i],
    ['DeepSeek', /(?:^|[/:])deepseek(?:-|$)/i],
    ['Kimi', /(?:^|[/:])(?:kimi|moonshot)(?:-|$)/i],
    ['Grok', /(?:^|[/:])grok(?:-|$)/i],
    ['Qwen', /(?:^|[/:])qwen(?:\d|[-.]|$)/i],
    ['GLM', /(?:^|[/:])glm(?:-|$)/i],
  ]
  return families.find(([, pattern]) => pattern.test(model.trim()))?.[0]
}

/** A naming hint, not a protocol verdict: relays may translate protocols and expose arbitrary aliases. */
export function providerProfileMismatch(provider: ProviderConfig, defaults: UnknownRecord): string | undefined {
  if (!provider.profile || provider.profile === 'openai-compatible-chat-safe') return
  const model = String(provider['default-model'] || '')
  const family = modelFamily(model)
  const profileFamily = modelFamily(String(defaults.defaultModel || '')) || modelFamily(provider.profile)
    || (['openai-chat', 'openai-responses'].includes(provider.profile) ? 'OpenAI'
      : defaults.type === 'anthropic' ? 'Claude' : defaults.type === 'gemini' ? 'Gemini' : undefined)
  return family && profileFamily && family !== profileFamily ? family : undefined
}

function modelId(model: unknown) {
  return typeof model === 'string' ? model : isRecord(model) ? String(model.id || '') : ''
}

/** A model name in an upstream list is not a capacity declaration. Use only explicit model/profile metadata. */
export function knownProviderModelWindow(provider: ProviderConfig, defaults: UnknownRecord) {
  const selected = String(provider['default-model'] || defaults.defaultModel || '').trim().toLowerCase()
  if (!selected) return
  const model = (provider.models || []).find(item => modelId(item).trim().toLowerCase() === selected)
  const explicit = isRecord(model) ? model['context-window'] ?? model.contextWindow : undefined
  const declared = Number(explicit)
  if (Number.isInteger(declared) && declared > 0) return { tokens: declared, source: 'model' as const }
  const windows = isRecord(defaults.modelContextWindows) ? defaults.modelContextWindows : {}
  const builtIn = Number(Object.entries(windows).find(([id]) => id.trim().toLowerCase() === selected)?.[1])
  if (Number.isInteger(builtIn) && builtIn > 0) return { tokens: builtIn, source: 'profile' as const }
}

export function providerModelOptions(provider: ProviderConfig | undefined, catalogue?: string[]) {
  const models = catalogue ?? [
    ...(Array.isArray(provider?.models) ? provider.models : []).map(modelId),
    provider?.['default-model'] || '',
  ]
  return [...new Set(models.filter(Boolean))].sort((left, right) => left.localeCompare(right))
}

export function reconcileProviderModels(provider: ProviderConfig, catalogue: string[]) {
  const available = new Set(catalogue.map(id => id.toLowerCase()))
  return (Array.isArray(provider.models) ? provider.models : [])
    .filter(model => available.has(modelId(model).toLowerCase()))
}
