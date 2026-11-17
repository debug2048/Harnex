import DOMPurify from 'dompurify'
import { marked } from 'marked'

const SAFE_URI_REGEXP = /^(?:(?:https?|mailto|tel):|[^a-z]|[a-z+.\-]+(?:[^a-z+.\-:]|$))/i
const HOOK_FLAG = '__HarnexMarkdownHooksInstalled'

marked.setOptions({
  gfm: true,
  breaks: true,
})

function installSanitizerHooks() {
  const state = globalThis as unknown as Record<string, unknown>
  if (state[HOOK_FLAG]) return
  DOMPurify.addHook('afterSanitizeAttributes', (node: Element) => {
    if (node.tagName === 'A') {
      const href = node.getAttribute('href') || ''
      if (/^https?:\/\//i.test(href)) node.setAttribute('target', '_blank')
      if (node.getAttribute('target') === '_blank') node.setAttribute('rel', 'noopener noreferrer')
    }
    if (node.tagName === 'IMG') {
      node.setAttribute('loading', 'lazy')
      node.setAttribute('decoding', 'async')
      node.setAttribute('referrerpolicy', 'no-referrer')
    }
    // marked emits disabled checkbox inputs for GFM task lists. Keep precisely that inert form, while removing raw
    // HTML inputs that could impersonate application controls.
    if (node.tagName === 'INPUT') {
      if ((node.getAttribute('type') || '').toLowerCase() === 'checkbox') {
        node.setAttribute('disabled', '')
        node.removeAttribute('name')
        node.removeAttribute('form')
      } else {
        node.remove()
      }
    }
  })
  state[HOOK_FLAG] = true
}

export function parseMarkdown(source: string): string {
  return marked.parse(source || '', { async: false, gfm: true, breaks: true }) as string
}

/** Allow structural HTML/Markdown while stripping scripts, embedded pages, forms, styling and unsafe URL schemes. */
export function sanitizeRenderedHtml(rawHtml: string): string {
  installSanitizerHooks()
  const fragment = DOMPurify.sanitize(rawHtml, {
    RETURN_DOM_FRAGMENT: true,
    ADD_ATTR: ['target', 'rel', 'loading', 'decoding', 'referrerpolicy', 'disabled'],
    ALLOWED_URI_REGEXP: SAFE_URI_REGEXP,
    FORBID_TAGS: ['script', 'style', 'iframe', 'object', 'embed', 'form', 'button', 'textarea', 'select', 'option',
      'link', 'meta', 'base'],
    FORBID_ATTR: ['style'],
  })
  const content = document.createElement('div')
  content.append(fragment)
  // Each table owns its scrollbar, including tables nested in raw HTML or lists.
  // Wrapping the sanitized DOM preserves table semantics and avoids touching code examples.
  for (const table of content.querySelectorAll('table')) {
    const scroller = content.ownerDocument.createElement('div')
    scroller.className = 'markdown-table-scroll'
    scroller.tabIndex = 0
    table.replaceWith(scroller)
    scroller.append(table)
  }
  return content.innerHTML
}

export function renderMarkdown(source: string): string {
  return sanitizeRenderedHtml(parseMarkdown(source))
}
