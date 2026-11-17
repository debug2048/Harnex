<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useResultPreviewStore } from '../../stores/resultPreview'
import { useConversationStore } from '../../stores/conversation'
import { csvRows, resultKind } from '../../domain/resultPreview'
import { t } from '../../i18n'
import { openPreviewBrowser } from '../../api/client'
import MarkdownBody from '../chat/MarkdownBody.vue'
import FileCodePreview from '../FileCodePreview.vue'
import JsonTree from './JsonTree.vue'
import DiffViewer from './DiffViewer.vue'
const preview = useResultPreviewStore()
const conversation = useConversationStore()
type ViewMode = 'diff' | 'preview' | 'source'
const frame = ref<HTMLIFrameElement | null>(null), mode = ref<ViewMode>('preview'), zoom = ref(1)
const dialog = ref<HTMLElement | null>(null)
const pageLoading = ref(false)
watch(() => preview.pageUrl, value => { pageLoading.value = !!value })
let previousFocus: HTMLElement | null = null
const kind = computed(() => resultKind(preview.name, preview.mime))
const isDiff = computed(() => preview.source?.kind === 'diff')
const fileAvailable = computed(() => preview.source?.kind !== 'diff' || !preview.source.deleted)
// The shared file loader determines the actual MIME; an unfamiliar extension must not hide a valid preview.
const diffPreviewAvailable = computed(() => isDiff.value && fileAvailable.value)
const busy = computed(() => preview.loading || mode.value !== 'diff' && preview.contentLoading)
const sourceAvailable = computed(() => ['html', 'markdown', 'json', 'csv'].includes(kind.value) || preview.mime === 'image/svg+xml')
const table = computed(() => kind.value === 'csv' ? csvRows(preview.text) : { rows: [], truncated: false })
const json = computed(() => { try { return { value: JSON.parse(preview.text), valid: true } } catch { return { value: null, valid: false } } })
const renderedAvailable = computed(() => !preview.loading && sourceAvailable.value
  && (kind.value !== 'json' || json.value.valid) && (kind.value !== 'html' || !!preview.pageUrl))
async function showMode(value: ViewMode) {
  const source = preview.source
  mode.value = value
  if (value !== 'diff') await preview.ensureContent()
  await nextTick()
  if (source !== preview.source || mode.value !== value) return
  if (value === 'source') dialog.value?.querySelector<HTMLElement>('.source-code')?.focus({ preventScroll: true })
}
async function refresh() {
  const previousMode = mode.value, source = preview.source
  await preview.refresh()
  if (source && preview.source === source) await showMode(previousMode)
}
async function referenceFile() {
  if (!preview.source || !['file', 'diff'].includes(preview.source.kind) || !('path' in preview.source) || !fileAvailable.value) return
  const path = preview.source.path
  preview.close()
  await nextTick()
  if (conversation.addWorkspaceContext(path)) conversation.requestWorkbench()
}
watch(() => preview.source, async (value, previous) => {
  mode.value = value?.kind === 'diff' ? 'diff' : 'preview'; zoom.value = 1
  if (value && !previous) previousFocus = document.activeElement instanceof HTMLElement ? document.activeElement : null
  await nextTick()
  if (value) dialog.value?.querySelector<HTMLButtonElement>('button:not(:disabled)')?.focus()
  else previousFocus?.focus()
})
function message(event: MessageEvent) {
  if (event.source !== frame.value?.contentWindow || !preview.session || event.data?.session !== preview.session.id) return
  if (typeof event.data.text !== 'string') return
  if (event.data.type === 'harnex-preview-error') preview.notice = event.data.text.slice(0, 1000)
}
function key(event: KeyboardEvent) {
  if (!preview.source) return
  if (event.key === 'Escape') { event.stopPropagation(); preview.close(); return }
  if (event.key === 'Tab') {
    const controls = [...dialog.value?.querySelectorAll<HTMLElement>('button:not(:disabled),a[href],iframe,audio,video,summary,[tabindex="0"]') || []]
    const first = controls[0], last = controls[controls.length - 1]
    if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last?.focus() }
    else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first?.focus() }
  }
}
onMounted(() => { window.addEventListener('message', message); window.addEventListener('keydown', key, true) })
onBeforeUnmount(() => { window.removeEventListener('message', message); window.removeEventListener('keydown', key, true) })
</script>
<template>
  <Teleport to="body">
    <div v-if="preview.source" class="result-preview-backdrop" @click.self="preview.close()">
      <section ref="dialog" class="result-preview" role="dialog" aria-modal="true" :aria-label="t('preview.title')">
        <header>
          <div class="file-heading">
            <strong :title="preview.name">{{ mode === 'diff' ? t('tool.diffLabel') + ' · ' : '' }}{{ preview.name || t('preview.title') }}</strong>
            <button type="button" class="refresh-preview" :disabled="busy" :aria-busy="busy"
              :title="t('preview.refresh')" :aria-label="t('preview.refresh')" @click="refresh">
              <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" aria-hidden="true" :class="{ spinning: preview.loading }"><path d="M20 11a8 8 0 1 0-2 6M20 4v7h-7" /></svg>
            </button>
          </div>
          <button type="button" class="close-preview" @click="preview.close()" :aria-label="t('preview.close')">×</button>
        </header>
        <nav :aria-label="t('preview.actions')">
          <div class="view-controls">
            <div v-if="isDiff || renderedAvailable" class="preview-tabs">
              <button v-if="isDiff" :aria-pressed="mode === 'diff'" @click="showMode('diff')">{{ t('tool.diffLabel') }}</button>
              <button v-if="diffPreviewAvailable || !isDiff && renderedAvailable" :aria-pressed="mode === 'preview'" @click="showMode('preview')">{{ t('preview.rendered') }}</button>
              <button v-if="fileAvailable && sourceAvailable && (isDiff || renderedAvailable)" :aria-pressed="mode === 'source'" @click="showMode('source')">{{ t('preview.source') }}</button>
            </div>
            <div v-if="mode === 'preview' && kind === 'image'" class="zoom-controls">
              <button @click="zoom = Math.max(.25, zoom - .25)">−</button>
              <button @click="zoom = 1">{{ Math.round(zoom * 100) }}%</button>
              <button @click="zoom = Math.min(4, zoom + .25)">+</button>
            </div>
          </div>
          <div class="file-actions">
            <button v-if="['file', 'diff'].includes(preview.source.kind) && fileAvailable && conversation.executionKind === 'AGENT'" class="reference-file" :title="t('chat.addContextHint')" @click="referenceFile">{{ t('chat.addContext') }}</button>
            <button v-if="preview.source.kind === 'diff' && preview.source.restore" class="restore-file" @click="preview.restore()">{{ t('chat.restoreSelected') }}</button>
            <button v-if="preview.canReveal" class="reveal-file" @click="preview.reveal()">{{ t('chat.openContainingFolder') }}</button>
            <button v-else-if="fileAvailable && !busy" class="download-file" :disabled="preview.downloading" @click="preview.download()">{{ t(preview.downloading ? 'files.downloading' : 'image.download') }}</button>
            <button v-if="preview.pageUrl" @click="openPreviewBrowser(preview.pageUrl).catch(error => preview.error = String(error))">{{ t('preview.browser') }}</button>
            <button v-if="/^image\/(png|jpeg|webp|gif)$/.test(preview.mime) && preview.blob" @click="preview.editImage()">{{ t('preview.editImage') }}</button>
          </div>

        </nav>
        <p v-if="busy || mode === 'preview' && pageLoading" class="preview-status" role="status">{{ t('common.loading') }}</p>
        <p v-if="preview.error" class="preview-status" role="alert">{{ preview.error }}</p>
        <p v-if="mode !== 'diff' && preview.contentError" class="preview-status" role="alert">{{ preview.contentError }}</p>
        <p v-if="mode !== 'diff' && preview.notice" class="preview-status" role="status">{{ preview.notice }}</p>
        <div v-if="mode === 'diff' && !preview.loading && preview.diff" class="result-body diff-body">
          <p v-if="preview.diff.binary" class="preview-status">{{ t('chat.binaryPreview') }}</p>
          <p v-else-if="preview.diff.available === false" class="preview-status">{{ t('chat.nonGitDiffUnavailable') }}</p>
          <DiffViewer v-else-if="preview.diff.unifiedDiff" :key="preview.name" :source="preview.diff.unifiedDiff" :truncated="preview.diff.truncated" />
          <p v-else class="preview-status">{{ t('chat.noTextDiff') }}</p>
        </div>
        <div v-else-if="mode !== 'diff' && !busy && preview.blob" class="result-body">
          <FileCodePreview v-if="mode === 'source' || kind === 'text' || sourceAvailable && !renderedAvailable" :content="preview.text" />
          <iframe v-else-if="kind === 'html' && preview.pageUrl" ref="frame" :src="preview.pageUrl" sandbox="allow-scripts allow-forms" referrerpolicy="no-referrer" :title="preview.name" @load="pageLoading = false" />
          <div v-else-if="kind === 'image'" class="image-stage"><img :src="preview.url" :alt="preview.name" :style="{ width: `${zoom * 100}%`, maxWidth: zoom === 1 ? '100%' : 'none' }" /></div>
          <iframe v-else-if="kind === 'pdf'" :src="preview.url" :title="preview.name" />
          <audio v-else-if="kind === 'audio'" :src="preview.url" controls preload="metadata" />
          <video v-else-if="kind === 'video'" :src="preview.url" controls preload="metadata" />
          <MarkdownBody v-else-if="kind === 'markdown'" :source="preview.text" class="md-content" />
          <JsonTree v-else-if="kind === 'json' && json.valid" :value="json.value" />
          <div v-else-if="kind === 'csv'" class="table-scroll"><table><tbody><tr v-for="(row, index) in table.rows" :key="index"><component :is="index ? 'td' : 'th'" v-for="(cell, column) in row" :key="column">{{ cell }}</component></tr></tbody></table><p v-if="table.truncated">{{ t('preview.rowsTruncated') }}</p></div>
          <p v-else>{{ t('preview.unsupported') }}</p>
        </div>
        <footer v-if="mode !== 'diff' && kind === 'html'">{{ t('preview.htmlHint') }}</footer>
      </section>
    </div>
  </Teleport>
</template>
<style scoped>
.result-preview-backdrop { position:fixed; inset:0; z-index:1400; background:#0007; display:flex; align-items:center; justify-content:center; padding:16px; }
.result-preview { display:flex; flex-direction:column; width:min(1120px,100%); height:min(850px,94dvh); background:var(--bg,var(--surface)); color:var(--text-primary); border:1px solid var(--border); border-radius:12px; box-shadow:0 20px 70px #0004; overflow:hidden; }
header { display:flex; align-items:center; justify-content:space-between; padding:12px 16px; gap:16px; } header strong { overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
.file-heading { display:flex; align-items:center; gap:10px; min-width:0; max-width:calc(100% - 64px); }
.close-preview { flex-shrink:0; }
.refresh-preview { flex-shrink:0; display:grid; place-items:center; width:32px; height:32px; padding:0; border:0; background:transparent; color:var(--text-muted); }
.refresh-preview:hover:not(:disabled) { background:var(--surface-hover); color:var(--text-primary); }
.refresh-preview:disabled { opacity:.5; cursor:default; }
.spinning { animation:preview-spin 1s linear infinite; }
@keyframes preview-spin { to { transform:rotate(360deg); } }
@media (prefers-reduced-motion:reduce) { .spinning { animation:none; } }
nav { display:grid; grid-template-columns:auto minmax(0,1fr); grid-template-areas:"view actions"; align-items:center; column-gap:20px; row-gap:12px; padding:10px 16px 14px; border-bottom:1px solid var(--border); }
.view-controls { grid-area:view; display:flex; align-items:center; flex-wrap:wrap; gap:12px; }
.preview-tabs,.zoom-controls { display:inline-flex; align-items:center; gap:2px; padding:3px; border:1px solid var(--border); border-radius:8px; background:var(--surface-1); }
.preview-tabs button,.zoom-controls button { border:0; background:transparent; white-space:nowrap; }
.preview-tabs button[aria-pressed=true] { background:var(--surface-2); box-shadow:0 1px 3px #0002; }
.file-actions { grid-area:actions; display:flex; justify-content:flex-end; align-items:center; flex-wrap:wrap; gap:8px; min-width:0; }
.file-actions button { white-space:nowrap; }
button, a { background:var(--surface-1); color:inherit; border:1px solid var(--border); border-radius:6px; padding:5px 9px; cursor:pointer; font:inherit; font-size:12px; text-decoration:none; }
button[aria-pressed=true] { border-color:var(--accent); color:var(--accent); }
.result-body { flex:1; min-height:0; overflow:auto; padding:12px; } iframe { display:block; width:100%; height:100%; border:0; background:white; }
.diff-body { display:flex; flex-direction:column; padding:0; overflow:hidden; }
.diff-body > .diff-viewer { flex:1; }
.image-stage { display:flex; justify-content:center; align-items:start; min-height:100%; overflow:auto; } img { object-fit:contain; height:auto; }
audio { width:100%; } video { max-width:100%; max-height:100%; } .preview-status, footer { padding:8px 16px; margin:0; font-size:12px; overflow-wrap:anywhere; } footer { color:var(--text-muted); }
table { border-collapse:collapse; min-width:100%; font-size:13px; } th,td { border:1px solid var(--border); padding:8px 12px; text-align:left; white-space:pre-wrap; } th { position:sticky; top:0; background:var(--surface-1); }
@media (max-width:600px) {
  nav { grid-template-columns:minmax(0,1fr); grid-template-areas:"view" "actions"; }
  .file-actions { justify-content:flex-start; }
  .file-actions:empty { display:none; }
}
@media (max-width:600px) { .result-preview-backdrop { padding:0; } .result-preview { height:100dvh; border-radius:0; } }
</style>
