import { isRecord } from '../utils/unknown'

export const searchServices = [
  { id: 'tavily', label: 'Tavily', endpoint: 'https://api.tavily.com/search' },
  { id: 'exa', label: 'Exa', endpoint: 'https://api.exa.ai/search' },
  { id: 'brave', label: 'Brave Search', endpoint: 'https://api.search.brave.com/res/v1/web/search' },
  { id: 'searxng', label: 'SearXNG', endpoint: 'http://localhost:8888' },
  { id: 'perplexity', label: 'Perplexity (Sonar)', endpoint: 'https://api.perplexity.ai/chat/completions' },
  { id: 'anysearch', label: 'AnySearch', endpoint: 'https://api.anysearch.com/v1/search' },
  { id: 'tinyfish', label: 'TinyFish Search', endpoint: 'https://api.search.tinyfish.ai' },
]

export interface SearchSettings {
  enabled: boolean
  provider: string
  'api-key': string
  endpoint: string
  language: string
  'max-results': number
  'timeout-ms': number
  'circuit-threshold': number
  'circuit-cooldown-ms': number
}

export function searchSettings(effective: unknown, draft: unknown): SearchSettings {
  function canonical(value: unknown) {
    if (!isRecord(value)) return {}
    return Object.fromEntries(Object.entries(value).filter(([key]) => {
      const name = key.replace(/([a-z0-9])([A-Z])/g, '$1-$2').toLowerCase()
      return name === key || !(name in value)
    }).map(([key, item]) => [key.replace(/([a-z0-9])([A-Z])/g, '$1-$2').toLowerCase(), item]))
  }
  return { enabled: false, provider: 'tavily', 'api-key': '', endpoint: '', language: '', 'max-results': 10,
    'timeout-ms': 15000, 'circuit-threshold': 3, 'circuit-cooldown-ms': 600000,
    ...canonical(effective), ...canonical(draft) } as SearchSettings
}

export function changeSearchService(settings: SearchSettings, provider: string): SearchSettings {
  return provider === settings.provider ? settings : { ...settings, provider, 'api-key': '', endpoint: '' }
}

export interface SearchProbeResult {
  success: boolean
  code: string
  latencyMs: number
  results: Array<{ title: string; url: string; snippet: string }>
}
