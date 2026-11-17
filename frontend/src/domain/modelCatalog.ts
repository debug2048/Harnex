import { isRecord } from '../utils/unknown'

export interface ModelOption {
  id: string
  ref: string
  label: string
  provider: string
  tier?: string
  description?: string
  default?: boolean
  reasoningEfforts: string[]
  defaultReasoningEffort: string
  functionToolReasoningEffort?: string
  contextBudget?: ModelContextBudget
}

export interface ModelContextBudget {
  windowTokens: number
  inputTokens: number
  source: 'model' | 'provider' | 'fallback'
  softThresholdTokens: number
  hardThresholdTokens: number
  targetTokens: number
}

export function parseModelCatalog(response: unknown): { models: ModelOption[]; hasProviders: boolean } {
  if (!isRecord(response) || !Array.isArray(response.providersDetailed)) throw new Error('Invalid model catalog response')
  const models: ModelOption[] = [], seen = new Set<string>()
  for (const provider of response.providersDetailed) {
    if (!isRecord(provider)) continue
    for (const model of Array.isArray(provider.models) ? provider.models : []) {
      if (!isRecord(model) || !model.id) continue
      const id = String(model.id), name = String(provider.name || ''), ref = String(model.ref || `${name}:${id}`)
      if (seen.has(ref.toLowerCase())) continue
      seen.add(ref.toLowerCase())
      const budget = model.contextBudget
      const contextBudget = isRecord(budget) && ['model', 'provider', 'fallback'].includes(String(budget.source))
        && ['windowTokens', 'inputTokens', 'softThresholdTokens', 'hardThresholdTokens', 'targetTokens']
          .every(key => typeof budget[key] === 'number' && Number.isFinite(budget[key]) && Number(budget[key]) > 0)
        ? budget as unknown as ModelContextBudget : undefined
      models.push({ id, ref, provider: name, label: String(model.label || id), default: Boolean(model.default),
        contextBudget,
        tier: model.tier ? String(model.tier) : undefined, description: model.description ? String(model.description) : undefined,
        reasoningEfforts: Array.isArray(model.reasoningEfforts) ? model.reasoningEfforts.map(String) : [],
        defaultReasoningEffort: String(model.defaultReasoningEffort || 'auto'),
        functionToolReasoningEffort: model.functionToolReasoningEffort ? String(model.functionToolReasoningEffort) : undefined })
    }
  }
  return { models, hasProviders: response.providersDetailed.length > 0 }
}
