export interface DiffSegment { text: string; changed: boolean }
export interface DiffLine {
  id: number
  kind: 'add' | 'del' | 'context' | 'hunk' | 'meta'
  text: string
  oldLine: number | null
  newLine: number | null
  hunk: number | null
  segments: DiffSegment[]
  noNewline?: boolean
}
export interface SplitDiffRow {
  id: number
  kind: 'line' | 'hunk' | 'meta'
  text: string
  hunk: number | null
  left?: DiffLine
  right?: DiffLine
}

// A linear prefix/suffix comparison keeps long lines cheap and never splits a Unicode code point.
// Unrelated replacements already have a full-line highlight; only emphasize a shared-line edit.
function highlightPair(before: DiffLine, after: DiffLine) {
  const a = Array.from(before.text), b = Array.from(after.text)
  let start = 0, end = 0
  while (start < a.length && start < b.length && a[start] === b[start]) start++
  while (end < a.length - start && end < b.length - start && a[a.length - end - 1] === b[b.length - end - 1]) end++
  if (!(a.slice(0, start).join('') + a.slice(a.length - end).join('')).trim()) return
  const segments = (chars: string[]): DiffSegment[] => [
    { text: chars.slice(0, start).join(''), changed: false },
    { text: chars.slice(start, chars.length - end).join(''), changed: true },
    { text: chars.slice(chars.length - end).join(''), changed: false },
  ].filter(segment => segment.text.length > 0)
  before.segments = segments(a)
  after.segments = segments(b)
}

export function parseUnifiedDiff(source: string) {
  const lines: DiffLine[] = []
  const rawLines = source.split(/\r\n|\n/)
  if (rawLines[rawLines.length - 1] === '') rawLines.pop()
  let oldLine: number | null = null, newLine: number | null = null
  let oldRemaining = 0, newRemaining = 0, inHunk = false, hunkCount = 0
  let added = 0, removed = 0
  for (const raw of rawLines) {
    if (raw.startsWith('\\ No newline at end of file')) {
      const previous = lines[lines.length - 1]
      if (previous && ['add', 'del', 'context'].includes(previous.kind)) {
        previous.noNewline = true
        continue
      }
    }
    const header = /^@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@/.exec(raw)
    const line: DiffLine = { id: lines.length, kind: 'meta', text: raw, oldLine: null, newLine: null, hunk: null, segments: [] }
    if (header) {
      oldLine = Number(header[1]); newLine = Number(header[3])
      oldRemaining = Number(header[2] ?? 1); newRemaining = Number(header[4] ?? 1)
      inHunk = true
      line.kind = 'hunk'; line.hunk = hunkCount++
    } else if (!inHunk && /^(--- |\+\+\+ |diff |index |new file |deleted file |similarity |rename |old mode |new mode )/.test(raw)) {
      oldLine = null; newLine = null
    } else if (raw.startsWith('+')) {
      line.kind = 'add'; line.text = raw.slice(1); line.newLine = newLine
      if (newLine !== null) newLine++
      newRemaining--; added++
    } else if (raw.startsWith('-')) {
      line.kind = 'del'; line.text = raw.slice(1); line.oldLine = oldLine
      if (oldLine !== null) oldLine++
      oldRemaining--; removed++
    } else if (inHunk && raw.startsWith(' ')) {
      line.kind = 'context'; line.text = raw.slice(1); line.oldLine = oldLine; line.newLine = newLine
      if (oldLine !== null) oldLine++
      if (newLine !== null) newLine++
      oldRemaining--; newRemaining--
    }
    if (line.kind !== 'meta' && line.kind !== 'hunk') line.segments = [{ text: line.text, changed: false }]
    lines.push(line)
    if (inHunk && oldRemaining <= 0 && newRemaining <= 0) {
      inHunk = false; oldLine = null; newLine = null
    }
  }

  const splitRows: SplitDiffRow[] = []
  for (let index = 0; index < lines.length;) {
    const line = lines[index]
    if (line.kind === 'add' || line.kind === 'del') {
      const deleted: DiffLine[] = [], inserted: DiffLine[] = []
      while (index < lines.length && ['add', 'del'].includes(lines[index].kind)) {
        const change = lines[index++]
        ;(change.kind === 'del' ? deleted : inserted).push(change)
      }
      for (let offset = 0; offset < Math.max(deleted.length, inserted.length); offset++) {
        const left = deleted[offset], right = inserted[offset]
        // Only pair equally sized runs for intraline highlighting; unequal runs often contain shifted lines.
        if (left && right && deleted.length === inserted.length) highlightPair(left, right)
        splitRows.push({ id: (left || right).id, kind: 'line', text: '', hunk: null, left, right })
      }
    } else {
      splitRows.push(line.kind === 'context'
        ? { id: line.id, kind: 'line', text: '', hunk: null, left: line, right: line }
        : { id: line.id, kind: line.kind, text: line.text, hunk: line.hunk })
      index++
    }
  }
  return { lines, splitRows, added, removed, hunkCount,
    truncated: lines.some(line => line.kind === 'meta' && /^(…|\.\.\.).*truncated/i.test(line.text)) }
}
