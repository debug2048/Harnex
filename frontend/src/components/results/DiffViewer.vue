<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { parseUnifiedDiff } from '../../domain/unifiedDiff'
import { t } from '../../i18n'

const props = defineProps<{ source: string; compact?: boolean; truncated?: boolean }>()
const root = ref<HTMLElement | null>(null), scroll = ref<HTMLElement | null>(null)
const layout = ref<'unified' | 'split'>('unified'), wide = ref(false), wrap = ref(true), activeHunk = ref(0)
const diff = computed(() => parseUnifiedDiff(props.source))
const split = computed(() => !props.compact && wide.value && layout.value === 'split')
const rows = computed(() => split.value ? diff.value.splitRows : diff.value.lines.map(line => ({
  id: line.id, kind: line.kind === 'hunk' || line.kind === 'meta' ? line.kind : 'line',
  text: line.text, hunk: line.hunk, left: line, right: undefined,
})))
const partial = computed(() => props.truncated || diff.value.truncated)
const numberColumns = computed(() => diff.value.lines.reduce((width, line) => Math.max(width, String(Math.max(line.oldLine || 0, line.newLine || 0)).length), 3) + 2)
// Reserve equal columns without clipping either side when wrapping is disabled (including CJK and tabs).
const splitWidth = computed(() => {
  if (!split.value || wrap.value) return '100%'
  const longest = diff.value.lines.reduce((width, line) => {
    if (!['add', 'del', 'context'].includes(line.kind)) return width
    const columns = Array.from(line.text).reduce((count, char) => count + (char === '\t' ? 4 - count % 4 : char.codePointAt(0)! > 255 ? 2 : 1), 0)
    return Math.max(width, columns)
  }, 0)
  return `${2 * (longest + numberColumns.value + 6)}ch`
})
let observer: ResizeObserver | undefined
let navigationScrollTop: number | null = null
onMounted(() => {
  observer = new ResizeObserver(entries => { wide.value = entries[0].contentRect.width >= 760 })
  if (root.value) observer.observe(root.value)
})
onBeforeUnmount(() => observer?.disconnect())
watch(() => props.source, () => {
  activeHunk.value = 0
  navigationScrollTop = null
  scroll.value?.scrollTo({ top: 0, left: 0 })
})
async function moveHunk(direction: number) {
  const count = diff.value.hunkCount
  if (!count) return
  activeHunk.value = (activeHunk.value + direction + count) % count
  await nextTick()
  const target = scroll.value?.querySelector<HTMLElement>(`[data-hunk="${activeHunk.value}"]`)
  if (target && scroll.value) {
    scroll.value.scrollTop += target.getBoundingClientRect().top - scroll.value.getBoundingClientRect().top - (split.value ? 29 : 0)
    // Near the file's end the browser may clamp scrolling before the selected hunk reaches the top.
    navigationScrollTop = scroll.value.scrollTop
  }
}
function trackHunk() {
  if (!scroll.value) return
  if (navigationScrollTop === scroll.value.scrollTop) return
  navigationScrollTop = null
  const top = scroll.value.getBoundingClientRect().top
  let current = 0
  scroll.value.querySelectorAll<HTMLElement>('[data-hunk]').forEach(element => {
    if (element.getBoundingClientRect().top <= top + 40) current = Number(element.dataset.hunk)
  })
  activeHunk.value = current
}
</script>

<template>
  <section ref="root" class="diff-viewer" :class="{ compact, 'is-split': split, 'is-wrapped': wrap }" :style="{ '--diff-number-width': `${numberColumns}ch`, '--diff-split-width': splitWidth }" :aria-label="t('tool.diffLabel')">
    <div v-if="!compact" class="diff-toolbar">
      <div class="diff-summary">
        <span class="diff-added">+{{ diff.added }} {{ t('diff.added') }}</span>
        <span class="diff-removed">−{{ diff.removed }} {{ t('diff.removed') }}</span>
        <span v-if="partial" class="diff-partial">{{ t('diff.shown') }}</span>
      </div>
      <div class="diff-controls">
        <div v-if="diff.hunkCount" class="diff-navigation" role="group" :aria-label="t('diff.navigate')">
          <button type="button" :disabled="diff.hunkCount < 2" :title="t('diff.previous')" :aria-label="t('diff.previous')" @click="moveHunk(-1)">↑</button>
          <span aria-live="polite">{{ activeHunk + 1 }} / {{ diff.hunkCount }}</span>
          <button type="button" :disabled="diff.hunkCount < 2" :title="t('diff.next')" :aria-label="t('diff.next')" @click="moveHunk(1)">↓</button>
        </div>
        <button type="button" :aria-pressed="wrap" @click="wrap = !wrap">{{ t('diff.wrap') }}</button>
        <div v-if="wide" class="diff-layout" role="group" :aria-label="t('diff.layout')">
          <button type="button" :aria-pressed="!split" @click="layout = 'unified'">{{ t('diff.unified') }}</button>
          <button type="button" :aria-pressed="split" @click="layout = 'split'">{{ t('diff.split') }}</button>
        </div>
      </div>
    </div>
    <div v-if="!compact && !split" class="diff-column-labels" aria-hidden="true">
      <span>{{ t('diff.oldLine') }}</span><span>{{ t('diff.newLine') }}</span><span class="diff-content-label">{{ t('diff.changes') }}</span>
    </div>
    <div ref="scroll" class="diff-scroll" tabindex="0" role="region" :aria-label="t('diff.changes')" @scroll.passive="trackHunk">
      <table class="diff-table" :aria-label="split ? t('diff.split') : t('diff.unified')">
        <colgroup v-if="split"><col class="number-col"><col class="sign-col"><col><col class="number-col"><col class="sign-col"><col></colgroup>
        <colgroup v-else><col class="number-col"><col class="number-col"><col class="sign-col"><col></colgroup>
        <thead v-if="split"><tr><th colspan="3" scope="colgroup">{{ t('diff.before') }}</th><th colspan="3" scope="colgroup">{{ t('diff.after') }}</th></tr></thead>
        <tbody>
          <tr v-for="row in rows" :key="row.id" :class="'diff-row-' + row.kind" :data-hunk="row.kind === 'hunk' ? row.hunk : undefined">
            <td v-if="row.kind !== 'line'" :colspan="split ? 6 : 4" class="diff-separator"><span>{{ row.text }}</span></td>
            <template v-else>
              <template v-for="(line, side) in split ? [row.left, row.right] : [row.left]" :key="side">
                <td class="diff-number" :class="'diff-cell-' + (line?.kind || 'empty')" :data-number="split ? (side ? line?.newLine : line?.oldLine) : line?.oldLine" aria-hidden="true" />
                <td v-if="!split" class="diff-number" :class="'diff-cell-' + line?.kind" :data-number="line?.newLine" aria-hidden="true" />
                <td class="diff-sign" :class="'diff-cell-' + (line?.kind || 'empty')" :data-sign="line?.kind === 'add' ? '+' : line?.kind === 'del' ? '−' : ''" :aria-label="line?.kind === 'add' ? t('diff.added') : line?.kind === 'del' ? t('diff.removed') : undefined" />
                <td class="diff-code" :class="'diff-cell-' + (line?.kind || 'empty')">
                  <template v-if="line"><span v-for="(segment, index) in line.segments" :key="index" :class="{ 'diff-inline-change': segment.changed }">{{ segment.text }}</span><span v-if="line.noNewline" class="diff-no-newline">{{ t('diff.noNewline') }}</span></template>
                </td>
              </template>
            </template>
          </tr>
        </tbody>
      </table>
    </div>
    <p v-if="partial && !compact" class="diff-truncated" role="status">{{ t('chat.previewTruncated') }}</p>
  </section>
</template>

<style scoped>
.diff-viewer { display:flex; flex-direction:column; min-width:0; min-height:0; overflow:hidden; color:var(--text-primary); background:var(--bg); }
.diff-toolbar { display:flex; align-items:center; justify-content:space-between; flex-wrap:wrap; gap:10px; padding:10px 16px; background:var(--surface-1); border-bottom:1px solid var(--border); font-size:12px; }
.diff-summary,.diff-controls,.diff-navigation,.diff-layout { display:flex; align-items:center; gap:8px; }
.diff-summary { gap:14px; font-variant-numeric:tabular-nums; }
.diff-added { color:var(--diff-add-fg); font-weight:650; }
.diff-removed { color:var(--diff-del-fg); font-weight:650; }
.diff-partial { color:var(--text-muted); }
.diff-controls { flex-wrap:wrap; }
.diff-controls button { min-height:28px; padding:3px 9px; border:1px solid var(--border); border-radius:5px; background:transparent; color:var(--text-secondary); font:inherit; cursor:pointer; }
.diff-controls button:hover:not(:disabled) { background:var(--surface-hover); }
.diff-controls button[aria-pressed=true] { color:var(--accent); background:var(--accent-soft); border-color:var(--accent); }
.diff-controls button:disabled { opacity:.4; cursor:default; }
.diff-controls button:focus-visible,.diff-scroll:focus-visible { outline:2px solid var(--accent); outline-offset:-2px; }
.diff-navigation { gap:4px; font-variant-numeric:tabular-nums; }
.diff-navigation span { min-width:5ch; text-align:center; color:var(--text-muted); }
.diff-layout { gap:0; }
.diff-layout button:first-child { border-radius:5px 0 0 5px; }
.diff-layout button:last-child { border-radius:0 5px 5px 0; margin-left:-1px; }
.diff-column-labels { display:grid; grid-template-columns:var(--diff-number-width) var(--diff-number-width) 1fr; flex-shrink:0; background:var(--surface-1); border-bottom:1px solid var(--border); color:var(--text-muted); font:13px/28px var(--font-mono); }
.diff-column-labels > span { text-align:center; }
.diff-column-labels .diff-content-label { text-align:left; padding-left:3ch; }
.diff-scroll { flex:1; min-height:0; overflow:auto; overscroll-behavior:contain; }
.diff-table { width:100%; border-collapse:separate; border-spacing:0; font:13px/24px var(--font-mono); tab-size:4; }
.diff-table th { position:sticky; top:0; z-index:2; padding:0 16px; text-align:left; background:var(--surface-1); color:var(--text-muted); font-weight:400; line-height:28px; border-bottom:1px solid var(--border); }
.diff-table th + th { border-left:1px solid var(--border-strong); }
.number-col { width:var(--diff-number-width); }
.sign-col { width:3ch; }
.diff-table td { border:0; padding:0; vertical-align:top; height:24px; }
.diff-table .diff-number { width:var(--diff-number-width); min-width:var(--diff-number-width); padding:0 8px; text-align:right; color:var(--text-muted); background:var(--surface-1); user-select:none; }
.diff-number::before { content:attr(data-number); }
.diff-table .diff-sign { width:3ch; min-width:3ch; text-align:center; user-select:none; border-left:3px solid transparent; font-weight:700; }
.diff-sign::before { content:attr(data-sign); }
.diff-sign.diff-cell-context { background:var(--bg); }
.diff-viewer:not(.is-split) .diff-number,.diff-viewer:not(.is-split) .diff-sign { position:sticky; z-index:1; }
.diff-viewer:not(.is-split) .diff-number:first-child { left:0; }
.diff-viewer:not(.is-split) .diff-number:nth-child(2) { left:var(--diff-number-width); }
.diff-viewer:not(.is-split) .diff-sign { left:calc(var(--diff-number-width) * 2); }
.diff-table .diff-code { padding-right:16px; white-space:pre; }
.is-wrapped .diff-table { table-layout:fixed; }
.is-split .diff-table { table-layout:fixed; min-width:var(--diff-split-width); }
.is-wrapped .diff-code { white-space:pre-wrap; overflow-wrap:anywhere; }
.diff-table .diff-cell-add { background:var(--diff-add-bg); }
.diff-table .diff-cell-del { background:var(--diff-del-bg); }
.diff-number.diff-cell-add,.diff-sign.diff-cell-add { background:var(--diff-add-gutter); color:var(--diff-add-fg); }
.diff-number.diff-cell-del,.diff-sign.diff-cell-del { background:var(--diff-del-gutter); color:var(--diff-del-fg); }
.diff-sign.diff-cell-add { border-left-color:var(--diff-add-fg); }
.diff-sign.diff-cell-del { border-left-color:var(--diff-del-fg); }
.diff-inline-change { border-radius:2px; padding:1px 0; font-weight:650; }
.diff-cell-add .diff-inline-change { background:var(--diff-add-word); }
.diff-cell-del .diff-inline-change { background:var(--diff-del-word); }
.diff-table .diff-cell-empty { background:repeating-linear-gradient(135deg,var(--surface-1),var(--surface-1) 4px,var(--bg) 4px,var(--bg) 8px); }
.is-split .diff-code:nth-child(3) { border-right:1px solid var(--border-strong); }
.diff-table .diff-separator { padding:5px 16px; white-space:pre-wrap; overflow-wrap:anywhere; }
.diff-row-hunk .diff-separator { background:var(--accent-soft); color:var(--text-secondary); border-block:1px solid var(--border); }
.diff-row-meta .diff-separator { color:var(--text-muted); font-size:11px; background:var(--surface-1); }
.diff-no-newline { display:block; color:var(--text-muted); font-size:11px; font-style:italic; }
.diff-truncated { margin:0; padding:8px 16px; border-top:1px solid var(--border); color:var(--text-muted); font-size:12px; }
.compact { max-height:360px; border:1px solid var(--border); border-radius:6px; }
.compact .diff-table { font-size:12px; line-height:22px; }
.compact .diff-table td { height:22px; }
</style>
