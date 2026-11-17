import { marked, type TokensList } from 'marked'
import { renderMarkdown, sanitizeRenderedHtml } from './markdown'

export interface MarkdownBlock { key: string; html: string; language?: string; source?: string; complete?: boolean; code?: boolean }
const cache = new Map<string, string>()
let cacheSize = 0
const MAX_CACHE = 4_000_000
function cached(key: string, build: () => string) {
  const existing = cache.get(key)
  if (existing !== undefined) { cache.delete(key); cache.set(key, existing); return existing }
  const html = build(), cost = key.length + html.length
  if (cost <= MAX_CACHE / 4) {
    while (cache.size >= 512 || cacheSize + cost > MAX_CACHE) {
      const oldest = cache.entries().next().value
      if (!oldest) break
      cacheSize -= oldest[0].length + oldest[1].length; cache.delete(oldest[0])
    }
    cache.set(key, html); cacheSize += cost
  }
  return html
}

/** Stable lexer blocks retain their DOM while only an unfinished tail changes. */
export function markdownBlocks(source: string): MarkdownBlock[] {
  if (!source) return []
  const tokens = marked.lexer(source, { gfm: true, breaks: true })
  // Raw HTML containers can span lexer blocks. Sanitize their complete structure together.
  if (tokens.some(token => token.type === 'html')) return [{ key: 'document', html: cached('html:' + source, () => renderMarkdown(source)),
    ...(/^\s*(?:<!doctype\s+html|<html\b)/i.test(source) ? { language: 'html', source, complete: /<\/html>\s*$/i.test(source) } : {}) }]
  const links = JSON.stringify(tokens.links)
  return tokens.filter(token => token.type !== 'space').map((token, index) => {
    const code = token.type === 'code' ? { code: true, language: String(token.lang || '').split(/\s/)[0].toLowerCase(), source: String(token.text || ''),
      complete: /^\s*(`{3,}|~{3,})/.test(token.raw) && /(?:\r?\n)[ \t]*(?:`{3,}|~{3,})[ \t]*(?:\r?\n)?$/.test(token.raw) } : {}
    return { key: `${index}:${token.type}`, ...code, html: token.type === 'code' ? '' : cached(token.raw + '\0' + links, () => {
      const block = [token] as TokensList; block.links = tokens.links
      return sanitizeRenderedHtml(marked.parser(block, { gfm: true, breaks: true }))
    }) }
  })
}
