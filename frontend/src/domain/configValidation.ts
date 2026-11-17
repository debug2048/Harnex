export type ConfigTab = 'providers' | 'images' | 'pricing' | 'network' | 'safety' | 'toolsets' | 'search' | 'integrations'
export interface ConfigIssue { raw: string; path: string; message: string; tab?: ConfigTab }
const kebab = (key: string) => key.replace(/([a-z0-9])([A-Z])/g, '$1-$2').replace(/_/g, '-').toLowerCase()

/** Resolve the existing validation API's field paths without changing its authoritative save rules. */
export function configIssue(raw: string): ConfigIssue {
  let path = ''
  const message = raw.replace(/^Provider configuration error:\s*/i, '')
  const qualified = message.match(/\bharnex\.([\w.-]+)/)
  const provider = message.match(/^provider ([\w-]+) (?:requires (profile)|([\w-]+))/i)
  const image = message.match(/^image service ([\w-]+)\s*:?\s*(?:([\w-]+))/i)
  const direct = message.match(/^(router|summarizer|http|workspace|safety|execution|toolsets|web-search|mcp|hooks)(?:\.([\w.-]+))?\b/)
  if (qualified) path = qualified[1]
  else if (provider) path = `providers.${provider[1]}${provider[2] || provider[3] === 'base-url' || provider[3] === 'baseUrl' ? `.${provider[2] || provider[3]}` : ''}`
  else if (image) {
    const field = image[2] || ''
    path = `image-services.${image[1]}` + (field === 'pricing' || field === 'prices' || field === 'choose' ? '.pricing'
      : ['base-url', 'timeout-millis', 'model', 'profile', 'api-key'].includes(field) ? `.${field}` : '')
    if (field === 'pricing' && /currency/.test(message)) path += '.currency'
  } else if (direct) path = direct[1] + (direct[2] ? `.${direct[2]}` : '')
  path = path.split('.').map((part, index) => index === 1 && /^(providers|image-services)\./.test(path) ? part : kebab(part)).join('.')
  const section = path.split('.')[0]
  const tab: ConfigTab | undefined = ({ providers: 'providers', router: 'providers', summarizer: 'providers',
    'image-services': path.includes('.pricing') ? 'pricing' : 'images', http: 'network',
    workspace: 'safety', safety: 'safety', execution: 'safety', toolsets: 'toolsets',
    'web-search': 'search', mcp: 'integrations', hooks: 'integrations' } as Record<string, ConfigTab>)[section]
  return { raw, path, message, tab }
}

export function configValueAt(value: unknown, path: string): unknown {
  for (const part of path.split('.')) {
    if (!value || typeof value !== 'object') return undefined
    const record = value as Record<string, unknown>
    const key = Object.prototype.hasOwnProperty.call(record, part) ? part : Object.keys(record).find(key => kebab(key) === part)
    if (!key) return undefined
    value = record[key]
  }
  return value
}
