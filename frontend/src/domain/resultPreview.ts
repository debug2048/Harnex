export type ResultSource =
  | { kind: 'artifact'; id: string; name?: string; mime?: string }
  | { kind: 'file'; path: string; name?: string; mime?: string }
  | { kind: 'diff'; path: string; runId: string; deleted?: boolean; restore?: () => void }
  | { kind: 'inline'; content: string; name: string; mime: string }
export type ResultKind = 'html' | 'image' | 'markdown' | 'pdf' | 'audio' | 'video' | 'json' | 'csv' | 'text' | 'binary'
const extensions: Record<string, string> = {
  html: 'text/html', htm: 'text/html', svg: 'image/svg+xml', png: 'image/png', jpg: 'image/jpeg', jpeg: 'image/jpeg',
  gif: 'image/gif', webp: 'image/webp', md: 'text/markdown', markdown: 'text/markdown', pdf: 'application/pdf',
  mp3: 'audio/mpeg', wav: 'audio/wav', ogg: 'audio/ogg', mp4: 'video/mp4', webm: 'video/webm',
  json: 'application/json', csv: 'text/csv', txt: 'text/plain', js: 'text/javascript', css: 'text/css',
}
export function resultMime(name: string, mime = '') {
  const known = mime.split(';')[0].toLowerCase()
  // Preserve verified media types. OS registries frequently label CSV as Excel and Markdown as plain text.
  if (/^(?:image|audio|video)\//.test(known) || known === 'application/pdf') return known
  return extensions[name.split('.').pop()?.toLowerCase() || ''] || known
}
export function resultKind(name: string, mime = ''): ResultKind {
  const type = resultMime(name, mime)
  if (type === 'text/html') return 'html'
  if (type.startsWith('image/')) return 'image'
  if (type.startsWith('audio/')) return 'audio'
  if (type.startsWith('video/')) return 'video'
  if (type === 'application/pdf') return 'pdf'
  if (type === 'text/markdown') return 'markdown'
  if (type === 'application/json' || type.endsWith('+json')) return 'json'
  if (type === 'text/csv') return 'csv'
  if (type.startsWith('text/') || /\.(vue|tsx?|jsx|rs|java|py|ya?ml|toml|log|sh|sql|xml)$/i.test(name)) return 'text'
  return 'binary'
}
/** Bounded RFC4180 reader, including quoted delimiters/newlines and escaped quotes. */
export function csvRows(source: string, limit = 501): { rows: string[][]; truncated: boolean } {
  const rows: string[][] = []; let row: string[] = [], field = '', quoted = false, truncated = false
  for (let i = 0; i < source.length; i++) {
    const c = source[i]
    if (c === '"' && (quoted || field === '')) {
      if (quoted && source[i + 1] === '"') { if (field.length < 16000) field += '"'; else truncated = true; i++ } else quoted = !quoted
    } else if (!quoted && (c === ',' || c === '\n' || c === '\r')) {
      if (row.length < 100) row.push(field); else truncated = true; field = ''
      if (c !== ',') {
        if (c === '\r' && source[i + 1] === '\n') i++
        rows.push(row); row = []
        if (rows.length >= limit) return { rows, truncated: truncated || i < source.length - 1 }
      }
    } else if (field.length < 16000) field += c
    else truncated = true
  }
  if (field || row.length) { if (row.length < 100) row.push(field); else truncated = true; rows.push(row) }
  return { rows, truncated }
}
export function previewableCode(language: string) { return ['html', 'htm', 'svg'].includes(language.trim().toLowerCase()) }
