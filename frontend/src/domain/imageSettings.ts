import { isRecord, type UnknownRecord } from '../utils/unknown'

export const IMAGE_PROFILES = [
  { id: 'openai-images', label: 'OpenAI Images', baseUrl: 'https://api.openai.com/v1', placeholder: 'https://api.openai.com/v1' },
  { id: 'openai-responses-image', label: 'OpenAI Responses · Images', baseUrl: 'https://api.openai.com/v1', placeholder: 'https://api.openai.com/v1' },
  { id: 'gemini-images', label: 'Google Gemini · Images', baseUrl: 'https://generativelanguage.googleapis.com', placeholder: 'https://generativelanguage.googleapis.com' },
  { id: 'dashscope-images', label: 'Qwen · DashScope Images', baseUrl: '', placeholder: 'https://<workspace>.cn-beijing.maas.aliyuncs.com/api/v1' },
]

/** Switch only a factory default address. Custom relay URLs, model IDs and credentials stay user-owned. */
export function changeImageProfile(service: UnknownRecord, profile: string): UnknownRecord {
  const next: UnknownRecord = { ...service, profile }
  const current = String(service['base-url'] || '').replace(/\/+$/, '')
  if (!current || IMAGE_PROFILES.some(item => item.baseUrl && item.baseUrl === current)) {
    next['base-url'] = IMAGE_PROFILES.find(item => item.id === profile)?.baseUrl || ''
  }
  return next
}

/** Canonical field spelling; service IDs remain exactly as configured. */
export function normalizeImageSettings(value: unknown): UnknownRecord {
  if (!isRecord(value)) return {}
  const result: UnknownRecord = {}
  for (const [key, item] of Object.entries(value)) {
    const canonical = key.replace(/([a-z0-9])([A-Z])/g, '$1-$2').replace(/_/g, '-').toLowerCase()
    if (key !== canonical && Object.prototype.hasOwnProperty.call(value, canonical)) continue
    if (item != null) result[canonical] = isRecord(item) ? normalizeImageSettings(item) : item
  }
  return result
}

export function imageSettingsMap(value: unknown): Record<string, UnknownRecord> {
  return isRecord(value) ? Object.fromEntries(Object.entries(value).filter(([, item]) => isRecord(item))
    .map(([id, item]) => [id, normalizeImageSettings(item)])) : {}
}

/** Storage identities are generated once; users configure a connection and a model. */
export function newImageModel(): { id: string; value: UnknownRecord } {
  return { id: `image_${crypto.randomUUID().replace(/-/g, '')}`,
    value: { enabled: true, profile: 'openai-images', model: '', 'base-url': 'https://api.openai.com/v1', 'api-key': '' } }
}

/** Copy connection values once. Protocol, model, capabilities and pricing belong to the image configuration. */
export function independentImageConnection(service: UnknownRecord, provider: UnknownRecord, defaults: UnknownRecord, apiKey: string): UnknownRecord {
  const source = { ...normalizeImageSettings(defaults), ...normalizeImageSettings(provider) }
  const next: UnknownRecord = { ...service, 'base-url': String(source['base-url'] || ''), 'api-key': apiKey }
  delete next.proxy
  return next
}

export function imageModelLabel(service: UnknownRecord): string {
  return String(service.label || service.model || '')
}

export function configuredSecretIds(values: UnknownRecord): string[] {
  return [...Object.keys(isRecord(values.providers) ? values.providers : {}),
    ...Object.keys(isRecord(values['image-services']) ? values['image-services'] : {}).map(id => `image:${id}`)]
}
