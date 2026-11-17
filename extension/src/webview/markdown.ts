import DOMPurify from 'dompurify'
import { marked } from 'marked'

const SAFE_URI_REGEXP = /^(?:(?:https?|mailto|tel):|[^a-z]|[a-z+.\-]+(?:[^a-z+.\-:]|$))/i

marked.setOptions({ gfm: true, breaks: true })

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

/** Full GFM plus safe structural HTML; active/embedded HTML and inline styling are deliberately removed. */
export function renderMarkdown(source: string): string {
  const raw = marked.parse(source || '', { async: false, gfm: true, breaks: true }) as string
  return DOMPurify.sanitize(raw, {
    ADD_ATTR: ['target', 'rel', 'loading', 'decoding', 'referrerpolicy', 'disabled'],
    ALLOWED_URI_REGEXP: SAFE_URI_REGEXP,
    FORBID_TAGS: ['script', 'style', 'iframe', 'object', 'embed', 'form', 'button', 'textarea', 'select', 'option',
      'link', 'meta', 'base'],
    FORBID_ATTR: ['style'],
  })
}
