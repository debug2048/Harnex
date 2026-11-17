export interface ChatModel {
  ref: string
  provider: string
  model: string
  label: string
  providerLabel: string
}

function record(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

/** Expose only display metadata to the webview, never the provider configuration. */
export function chatModels(value: unknown): ChatModel[] {
  if (!record(value) || !Array.isArray(value.providersDetailed)) throw new Error('模型列表格式无效')
  const result: ChatModel[] = []
  const seen = new Set<string>()
  for (const provider of value.providersDetailed) {
    if (!record(provider) || typeof provider.name !== 'string' || !provider.name) continue
    for (const model of Array.isArray(provider.models) ? provider.models : []) {
      if (!record(model) || typeof model.id !== 'string' || !model.id) continue
      const ref = `${provider.name}:${model.id}`
      if (seen.has(ref)) continue
      seen.add(ref)
      result.push({ ref, provider: provider.name, model: model.id,
        label: typeof model.label === 'string' && model.label ? model.label : model.id,
        providerLabel: typeof provider.label === 'string' && provider.label ? provider.label : provider.name })
    }
  }
  return result
}
