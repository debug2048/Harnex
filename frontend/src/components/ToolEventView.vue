<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { t } from '../i18n'
import { api, getRuntimeRevision } from '../api/client'
import type { ToolEvent, ToolEventDiff } from '../domain/chat'
import type { RunToolDetail } from '../../../packages/protocol/workbench'
import { errorMessage, isRecord, type UnknownRecord } from '../utils/unknown'
import { updateOverflowTitle } from '../utils/overflowTitle'
import ArtifactCard from './chat/ArtifactCard.vue'
import DiffViewer from './results/DiffViewer.vue'

const props = defineProps<{
  event: ToolEvent
  threadId?: string
}>()
const emit = defineEmits<{
  openSubagent: [callId: string, content?: string]
}>()

const expanded = ref(false)
const diffExpanded = ref(true)
const detail = ref<RunToolDetail | null>(null)
const detailsLoading = ref(false), detailsError = ref(false)
let detailRequest: AbortController | null = null
const event = computed<ToolEvent>(() => detail.value ? { ...props.event,
  arguments: detail.value.arguments ?? props.event.arguments,
  purpose: detail.value.purpose || props.event.purpose,
  content: detail.value.content ?? undefined,
  metadata: detail.value.metadata as ToolEvent['metadata'],
  durationMs: detail.value.durationMs ?? props.event.durationMs,
  resultPending: detail.value.resultPending && props.event.resultPending !== false,
  detailSource: undefined,
} : props.event)

async function loadDetails() {
  const source = props.event.detailSource
  if (!source || detail.value || detailsLoading.value) return
  const request = new AbortController()
  const revision = getRuntimeRevision()
  detailRequest = request; detailsLoading.value = true; detailsError.value = false
  try {
    const result = await api.runToolDetails(source.runId, source.seq, request.signal)
    if (request.signal.aborted || detailRequest !== request || revision !== getRuntimeRevision()) return
    if (result.runId !== source.runId || result.seq !== source.seq || result.id !== props.event.id) throw new Error('Tool identity mismatch')
    detail.value = result
  } catch {
    if (!request.signal.aborted && detailRequest === request) detailsError.value = true
  } finally { if (detailRequest === request) detailsLoading.value = false }
}
watch([() => props.threadId, () => props.event.detailSource?.runId, () => props.event.seq, () => props.event.id,
  () => props.event.done, () => props.event.resultPending, () => props.event.content, () => props.event.arguments], () => {
  detailRequest?.abort(); detailRequest = null; detail.value = null; detailsLoading.value = false; detailsError.value = false
  if (expanded.value) void loadDetails()
})
onBeforeUnmount(() => detailRequest?.abort())

const diff = computed<ToolEventDiff | undefined>(() => event.value.metadata?.diff)
const artifactIds = computed(() => [...new Set([...String(event.value.content || '').matchAll(/\[artifact:([A-Za-z0-9_-]+)\]/g)].map(match => match[1]))].slice(0, 8))
const isDelegate = computed(() => String(event.value.name || '').toLowerCase() === 'delegate')

const PERSIST_REF_RE = /ref:([0-9a-fA-F]{8,64})/g
interface PersistRef { sha: string; loading: boolean; error: string | null; body: string | null; open: boolean }
const refStates = ref<Record<string, PersistRef>>({})

const persistRefs = computed<PersistRef[]>(() => {
  const c = event.value.content
  if (!c) return []
  const seen = new Set<string>()
  const out: PersistRef[] = []
  const re = new RegExp(PERSIST_REF_RE.source, 'g')
  let m: RegExpExecArray | null
  while ((m = re.exec(c)) !== null) {
    const sha = m[1].toLowerCase()
    if (seen.has(sha)) continue
    seen.add(sha)
    const existing = refStates.value[sha]
    out.push(existing ?? { sha, loading: false, error: null, body: null, open: false })
  }
  return out
})

async function toggleRef(r: PersistRef) {
  const tid = props.threadId
  if (!tid) return
  const cur = refStates.value[r.sha] ?? { ...r }
  if (cur.body != null) {
    cur.open = !cur.open
    refStates.value = { ...refStates.value, [r.sha]: cur }
    return
  }
  cur.loading = true
  cur.error = null
  cur.open = true
  refStates.value = { ...refStates.value, [r.sha]: cur }
  try {
    const body = await api.toolResult(tid, r.sha)
    refStates.value = { ...refStates.value, [r.sha]: { ...cur, loading: false, body } }
  } catch (cause: unknown) {
    refStates.value = {
      ...refStates.value,
      [r.sha]: { ...cur, loading: false, error: errorMessage(cause) },
    }
  }
}

const contentWithoutDiff = computed(() => {
  const c = event.value.content
  if (!c || !diff.value) return c
  const headerEnd = c.indexOf('\n')
  return headerEnd >= 0 ? c.slice(0, headerEnd) : c
})

const parsedArgs = computed<UnknownRecord | null>(() => {
  const raw = event.value.arguments
  if (!raw) return null
  try {
    const parsed = JSON.parse(raw)
    return isRecord(parsed) ? parsed : null
  } catch {
    return null
  }
})

const formattedArgs = computed(() => {
  const raw = event.value.arguments
  if (!raw) return ''
  if (!parsedArgs.value) return raw
  return JSON.stringify(parsedArgs.value, null, 2)
})

const statusLabel = computed(() => {
  if (event.value.status === 'queued') return t('tool.waiting')
  if (!event.value.done) return t('tool.running')
  if (event.value.error) return t('tool.failed')
  return t('tool.done')
})

const statusClass = computed(() => {
  if (event.value.status === 'queued') return 'queued'
  if (!event.value.done) return 'running'
  return event.value.error ? 'error' : 'success'
})

const actionSummary = computed(() => event.value.purpose
  || summarizeAction(event.value.name, parsedArgs.value, event.value.arguments, diff.value))

const outcomeSummary = computed(() => {
  if (event.value.status === 'queued') return t('tool.waiting')
  if (!event.value.done) return t('tool.waiting')
  if (event.value.detailSource) return event.value.summary ? previewText(event.value.summary, 140) : t('tool.showDetails')
  const c = contentWithoutDiff.value?.trim() || ''
  if (event.value.error) {
    return c ? previewText(c, 140) : t('tool.failed')
  }
  if (!c) return t('tool.noOutput')
  return t('tool.outputAvailable', { size: formatSize(c.length) })
})

const hasDetails = computed(() =>
  Boolean(event.value.purpose || event.value.detailSource || formattedArgs.value || contentWithoutDiff.value || diff.value?.unifiedDiff || persistRefs.value.length)
)

function openDetails() {
  if (isDelegate.value) {
    emit('openSubagent', event.value.id, event.value.content)
    return
  }
  expanded.value = !expanded.value
  if (expanded.value) void loadDetails()
}

function summarizeAction(name: string, args: UnknownRecord | null, rawArgs?: string, d?: ToolEventDiff) {
  const lower = (name || '').toLowerCase()
  const value = (keys: string[]) => {
    for (const k of keys) {
      const v = args?.[k]
      if (v !== undefined && v !== null && String(v).trim()) return stringifyBrief(v)
    }
    return ''
  }

  if (d?.path) {
    if (d.unchanged) return `${d.path} · ${t('tool.diffNoop')}`
    if (d.binary) return `${d.path} · ${t('tool.diffBinary')}`
    return `${d.path} · +${d.added} -${d.removed}`
  }
  if (lower.includes('bash') || lower.includes('shell')) return compact(value(['command', 'cmd', 'script']) || rawArgs || '', 180)
  if (lower.includes('file_read')) return compact(joinParts([value(['path', 'file']), rangeSummary(args)]), 180)
  if (lower.includes('file_write')) return compact(joinParts([value(['path', 'file']), sizeHint(args, ['content', 'text'])]), 180)
  if (lower.includes('file_edit')) return compact(joinParts([value(['path', 'file']), replaceHint(args)]), 180)
  if (lower.includes('grep')) return compact(joinParts([value(['pattern', 'query']), value(['path', 'glob', 'include'])]), 180)
  if (lower.includes('glob')) return compact(value(['pattern', 'glob']) || rawArgs || '', 180)
  if (lower.includes('web_fetch')) return compact(value(['url', 'href']) || rawArgs || '', 180)
  if (lower.includes('delegate')) return compact(value(['prompt', 'task', 'message']) || rawArgs || '', 180)
  if (lower.includes('memory_remember')) return compact(value(['memory', 'content', 'text']) || rawArgs || '', 180)
  if (lower.includes('todo_write')) return todoSummary(args)

  const first = firstArg(args)
  return compact(first || rawArgs || props.event.id, 180)
}

function joinParts(parts: string[]) {
  return parts.filter(Boolean).join(' · ')
}

function stringifyBrief(v: unknown) {
  if (typeof v === 'string') return v
  if (typeof v === 'number' || typeof v === 'boolean') return String(v)
  if (Array.isArray(v)) return `${v.length} item${v.length === 1 ? '' : 's'}`
  return JSON.stringify(v)
}

function firstArg(args: UnknownRecord | null) {
  if (!args) return ''
  for (const [k, v] of Object.entries(args)) {
    if (v === undefined || v === null || v === '') continue
    return `${k}: ${stringifyBrief(v)}`
  }
  return ''
}

function rangeSummary(args: UnknownRecord | null) {
  if (!args) return ''
  const offset = args.offset ?? args.start
  const limit = args.limit ?? args.maxBytes ?? args.maxLines
  if (offset !== undefined && limit !== undefined) return `${offset}+${limit}`
  if (limit !== undefined) return `limit ${limit}`
  return ''
}

function sizeHint(args: UnknownRecord | null, keys: string[]) {
  if (!args) return ''
  for (const k of keys) {
    const v = args[k]
    if (typeof v === 'string') return `${v.length} chars`
  }
  return ''
}

function replaceHint(args: UnknownRecord | null) {
  if (!args) return ''
  const oldText = args.oldString ?? args.old_string ?? args.search
  const newText = args.newString ?? args.new_string ?? args.replace
  if (typeof oldText === 'string' && typeof newText === 'string') {
    return `${compact(oldText, 40)} -> ${compact(newText, 40)}`
  }
  return ''
}

function todoSummary(args: UnknownRecord | null) {
  const todos = args?.todos
  if (Array.isArray(todos)) return `${todos.length} todo${todos.length === 1 ? '' : 's'}`
  return firstArg(args)
}

function previewText(text: string, max = 160) {
  const lines = text.split(/\r?\n/)
  const useful = lines.map(s => s.trim()).filter(Boolean)
    .find(line => !line.startsWith('$ ') && !/^\[exit\s+-?\d+\]/i.test(line)) || ''
  const source = useful || text
  const truncated = source.length < text.trim().length || compact(source, max) !== source.replace(/\s+/g, ' ').trim()
  return compact(source, max) + (truncated ? ` ${t('tool.previewTruncated')}` : '')
}

function formatSize(chars: number) {
  if (chars < 1024) return t('tool.sizeChars', { n: chars })
  return t('tool.sizeKb', { n: (chars / 1024).toFixed(chars < 10_240 ? 1 : 0) })
}

function compact(text: string, max = 120) {
  const oneLine = String(text || '').replace(/\s+/g, ' ').trim()
  if (oneLine.length <= max) return oneLine
  return oneLine.slice(0, Math.max(0, max - 3)).trimEnd() + '...'
}
</script>

<template>
  <div class="tool-event" :class="[statusClass, { expanded }]">
    <button class="tool-summary" type="button" @click="openDetails"
      :aria-expanded="isDelegate ? undefined : expanded">
      <span class="tool-indicator" aria-hidden="true">
        <svg v-if="!event.done" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" class="spin">
          <path d="M12 2v4M12 18v4M4.93 4.93l2.83 2.83M16.24 16.24l2.83 2.83M2 12h4M18 12h4M4.93 19.07l2.83-2.83M16.24 7.76l2.83-2.83"/>
        </svg>
        <svg v-else-if="event.error" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5">
          <circle cx="12" cy="12" r="10"/><line x1="15" y1="9" x2="9" y2="15"/><line x1="9" y1="9" x2="15" y2="15"/>
        </svg>
        <svg v-else width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round">
          <polyline points="20 6 9 17 4 12"/>
        </svg>
      </span>

      <span class="tool-main">
        <span class="tool-title-row">
          <span class="tool-name">{{ event.name }}</span>
          <span class="tool-status" :class="statusClass">{{ statusLabel }}</span>
          <span v-if="event.done && event.durationMs" class="tool-duration">{{ event.durationMs }}ms</span>
          <span v-if="diff && diff.added > 0" class="diff-chip diff-add">+{{ diff.added }}</span>
          <span v-if="diff && diff.removed > 0" class="diff-chip diff-del">-{{ diff.removed }}</span>
          <span v-if="diff && diff.unchanged" class="diff-chip diff-meta">{{ t('tool.diffNoop') }}</span>
          <span v-if="diff && diff.whitespaceOnly" class="diff-chip diff-meta">{{ t('tool.diffWhitespace') }}</span>
          <span v-if="diff && diff.binary" class="diff-chip diff-meta">{{ t('tool.diffBinary') }}</span>
        </span>
        <span v-if="actionSummary" class="tool-action"
          @mouseenter="updateOverflowTitle($event, event.purpose)" @mouseleave="updateOverflowTitle($event)">{{ actionSummary }}</span>
        <span class="tool-outcome" :class="{ error: event.error }">
          <span class="preview-label">{{ event.detailSource ? t('tool.summary') : event.error ? t('tool.errorPrefix') : t('tool.outputMeta') }}</span>
          {{ outcomeSummary }}
        </span>
      </span>

      <span v-if="isDelegate || hasDetails" class="tool-expand">
        {{ isDelegate ? t('tool.viewSubagent') : expanded ? t('tool.hideDetails') : t('tool.showDetails') }}
      </span>
    </button>

    <div v-if="expanded && !isDelegate" class="tool-body">
      <div v-if="event.purpose" class="tool-section">
        <div class="tool-section-label">{{ t('tool.purpose') }}</div>
        <p class="tool-purpose">{{ event.purpose }}</p>
      </div>
      <div v-if="detailsLoading" class="tool-section detail-status" role="status">{{ t('tool.detailsLoading') }}</div>
      <div v-if="detailsError" class="tool-section detail-status" role="status">
        {{ t('tool.detailsUnavailable') }}
        <button type="button" @click="loadDetails">{{ t('tool.detailsRetry') }}</button>
      </div>
      <div v-if="event.detailSource && (event.content || event.summary)" class="tool-section">
        <div class="tool-section-label">{{ t('tool.summary') }}</div>
        <pre class="tool-pre">{{ event.content || event.summary }}</pre>
      </div>
      <div v-if="formattedArgs && !event.detailSource" class="tool-section">
        <div class="tool-section-label">{{ t('tool.args') }}</div>
        <pre class="tool-pre">{{ formattedArgs }}</pre>
      </div>

      <div v-if="!event.detailSource && diff && diff.unifiedDiff && !diff.unchanged && !diff.binary && !diff.whitespaceOnly" class="tool-section">
        <div class="tool-section-label diff-label">
          <span>{{ t('tool.diffLabel') }} <span class="diff-add-text">+{{ diff.added }}</span> <span class="diff-del-text">-{{ diff.removed }}</span></span>
          <button class="diff-toggle" type="button" @click.stop="diffExpanded = !diffExpanded">
            {{ diffExpanded ? t('tool.refCollapse') : t('tool.refExpand') }}
          </button>
        </div>
        <DiffViewer v-if="diffExpanded" :source="diff.unifiedDiff" :truncated="diff.truncated" compact />
      </div>

      <div v-if="contentWithoutDiff && !event.detailSource" class="tool-section">
        <ArtifactCard v-for="id in artifactIds" :key="id" :artifact-id="id" />
        <div class="tool-section-label">{{ event.error ? t('tool.errorOutput') : t('tool.output') }}</div>
        <pre class="tool-pre" :class="{ 'tool-pre-error': event.error }">{{ contentWithoutDiff }}</pre>
      </div>

      <div v-if="persistRefs.length && threadId" class="tool-section">
        <div class="tool-section-label">{{ t('tool.persistedRefs') }}</div>
        <div v-for="r in persistRefs" :key="r.sha" class="persist-ref">
          <button class="ref-toggle" type="button" @click.stop="toggleRef(r)" :disabled="r.loading">
            <span class="ref-sha">ref:{{ r.sha.slice(0, 12) }}</span>
            <span v-if="r.loading">{{ t('tool.refLoading') }}</span>
            <span v-else-if="r.error" class="ref-error">{{ r.error }}</span>
            <span v-else>{{ (refStates[r.sha] ?? r).open ? t('tool.refCollapse') : t('tool.refExpand') }}</span>
          </button>
          <pre v-if="(refStates[r.sha] ?? r).open && (refStates[r.sha] ?? r).body" class="tool-pre ref-body">{{ (refStates[r.sha] ?? r).body }}</pre>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.detail-status { color:var(--text-muted); font-size:12px; }
.detail-status button { margin-left:8px; color:var(--accent); background:transparent; text-decoration:underline; }
.tool-event {
  position: relative;
  min-width: 0;
  margin: 8px 0 8px var(--conversation-inset);
  border: 1px solid var(--border);
  border-radius: 12px;
  background: color-mix(in srgb, var(--surface-1) 65%, var(--chat-background, var(--bg)));
  overflow: hidden;
  animation: fadeIn .15s ease-out;
}

.tool-event.running {
  border-color: color-mix(in srgb, var(--accent) 34%, var(--border));
}

.tool-event.error {
  border-color: color-mix(in srgb, var(--danger) 42%, var(--border));
}

.tool-summary {
  width: 100%;
  display: grid;
  grid-template-columns: 22px minmax(0, 1fr) auto;
  align-items: start;
  gap: 10px;
  padding: 12px 14px;
  border: 0;
  border-radius: 11px;
  background: transparent;
  color: inherit;
  text-align: left;
}

.tool-summary:hover {
  background: var(--surface-hover);
}
.expanded > .tool-summary { border-radius: 11px 11px 0 0; }
.tool-summary:focus-visible { outline: 2px solid var(--accent); outline-offset: -3px; }

.tool-summary:active {
  transform: none;
}

.tool-indicator {
  width: 22px;
  height: 22px;
  border-radius: 6px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--success-soft);
  margin-top: 1px;
}

.tool-event.running .tool-indicator { color: var(--accent); background: var(--accent-soft); }
.tool-event.error .tool-indicator { color: var(--danger); background: var(--danger-soft); }
.tool-event.success .tool-indicator { color: var(--success); }

.tool-main {
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.tool-title-row {
  min-width: 0;
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}

.tool-name {
  font-weight: 600;
  font-size: 12.5px;
  color: var(--fg);
}

.tool-status {
  font-size:12px;
  line-height: 1;
  padding: 3px 6px;
  border-radius: 999px;
  font-weight: 500;
}

.tool-status.running { color: var(--accent); background: var(--accent-soft); }
.tool-status.error { color: var(--danger); background: var(--danger-soft); }
.tool-status.success { padding: 0; color: var(--text-muted); background: transparent; }

.tool-duration {
  color: var(--muted);
  font: 12px var(--font-mono);
}

.tool-action {
  min-width: 0;
  color: var(--fg2);
  font-size: 12px;
  line-height: 1.45;
  font-family: "JetBrains Mono", "Fira Code", "Cascadia Code", Consolas, monospace;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.tool-outcome {
  min-width: 0;
  color: var(--muted);
  font-size: 12px;
  line-height: 1.45;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.tool-outcome.error {
  color: var(--danger);
}

.preview-label {
  color: var(--muted);
  font-weight: 650;
  margin-right: 5px;
}

.tool-expand {
  align-self: center;
  color: var(--muted);
  font-size:12px;
  white-space: nowrap;
}

.diff-chip {
  font-size:12px;
  padding: 2px 6px;
  border-radius: 4px;
  font-weight: 750;
  font-family: "JetBrains Mono", "Fira Code", "Cascadia Code", Consolas, monospace;
}

.diff-chip.diff-add { background: var(--diff-add-bg); color: var(--diff-add-fg); }
.diff-chip.diff-del { background: var(--diff-del-bg); color: var(--diff-del-fg); }
.diff-chip.diff-meta { background: var(--bg3); color: var(--muted); }

.tool-body {
  padding: 0 14px 14px 46px;
}

.tool-section {
  padding-top: 10px;
}

.tool-purpose {
  margin: 0;
  font-size: 12px;
  line-height: 1.55;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}

.tool-section-label {
  font-size:12px;
  font-weight: 700;
  text-transform: uppercase;
  letter-spacing: .05em;
  color: var(--muted);
  margin-bottom: 5px;
}

.tool-pre {
  font-size:12px;
  line-height: 1.55;
  max-height: 260px;
  overflow: auto;
  margin: 0;
  white-space: pre-wrap;
  word-break: break-word;
  background: var(--bg);
  border-color: var(--border);
}

.tool-pre-error {
  border-color: color-mix(in srgb, var(--danger) 32%, var(--border));
  background: color-mix(in srgb, var(--danger-soft) 30%, var(--bg));
}

.diff-label {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.diff-add-text,
.diff-del-text {
  font-family: "JetBrains Mono", "Fira Code", "Cascadia Code", Consolas, monospace;
  font-weight: 750;
}

.diff-add-text { color: var(--diff-add-fg); }
.diff-del-text { color: var(--diff-del-fg); }

.diff-toggle,
.ref-toggle {
  background: var(--bg);
  border: 1px solid var(--border);
  color: var(--muted);
  font-size:12px;
  padding: 3px 7px;
  border-radius: 5px;
}

.diff-toggle:hover,
.ref-toggle:hover:not(:disabled) {
  color: var(--fg);
  border-color: var(--border-light);
}

.persist-ref {
  margin-bottom: 8px;
}

.persist-ref:last-child {
  margin-bottom: 0;
}

.ref-toggle {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  font-family: "JetBrains Mono", "Fira Code", "Cascadia Code", Consolas, monospace;
}

.ref-toggle:disabled {
  opacity: .6;
  cursor: wait;
}

.ref-sha {
  font-weight: 750;
  color: var(--fg);
}

.ref-error {
  color: var(--danger);
}

.ref-body {
  margin-top: 6px;
  max-height: 360px;
}

@keyframes fadeIn {
  from { opacity: 0; transform: translateY(2px); }
  to { opacity: 1; transform: translateY(0); }
}

@keyframes spin {
  from { transform: rotate(0deg); }
  to { transform: rotate(360deg); }
}

.spin { animation: spin 1s linear infinite; }

@media (max-width: 760px) {
  .tool-summary {
    grid-template-columns: 22px minmax(0, 1fr);
  }

  .tool-expand {
    grid-column: 2;
    justify-self: start;
  }

  .tool-body {
    padding-left: 14px;
  }

  .tool-action,
  .tool-outcome {
    white-space: normal;
    overflow-wrap: anywhere;
  }
}
@container conversation (max-width: 760px) {
  .tool-summary { grid-template-columns: 22px minmax(0, 1fr); }
  .tool-expand { grid-column: 2; justify-self: start; }
  .tool-body { padding-left: 14px; }
  .tool-action, .tool-outcome { white-space: normal; overflow-wrap: anywhere; }
}
@media (prefers-reduced-motion: reduce) {
  .tool-event, .spin { animation: none; }
}
</style>
