<script setup lang="ts">
import { nextTick, onBeforeUnmount, ref, shallowRef, watch } from 'vue'
import { markdownBlocks, type MarkdownBlock } from '../../utils/markdownBlocks'
import { previewableCode } from '../../domain/resultPreview'
import { useResultPreviewStore } from '../../stores/resultPreview'
import DiagramPreview from '../results/DiagramPreview.vue'
import { t } from '../../i18n'
import 'katex/dist/katex.min.css'
const props = withDefaults(defineProps<{ source: string; state?: 'streaming' | 'static' }>(), { state: 'static' })
const blocks = shallowRef<MarkdownBlock[]>([])
const host = ref<HTMLElement | null>(null), preview = useResultPreviewStore()
let timer: ReturnType<typeof setTimeout> | null = null
let generation = 0
function render() {
  timer = null; blocks.value = markdownBlocks(props.source)
  const current = ++generation
  if (props.state === 'static' && /\$|\\\(|\\\[/.test(props.source)) void nextTick(async () => {
    const { default: renderMath } = await import('katex/contrib/auto-render')
    if (current !== generation || !host.value) return
    renderMath(host.value, { throwOnError: false, trust: false, maxExpand: 500, delimiters: [
      { left: '$$', right: '$$', display: true }, { left: '\\[', right: '\\]', display: true },
      { left: '\\(', right: '\\)', display: false }, { left: '$', right: '$', display: false },
    ] })
  })
}
function open(block: MarkdownBlock) { void preview.open({ kind: 'inline', content: block.source || '',
  name: block.language === 'svg' ? 'result.svg' : 'result.html', mime: block.language === 'svg' ? 'image/svg+xml' : 'text/html' }) }
watch(() => [props.source, props.state], () => {
  if (props.state === 'static') { if (timer) clearTimeout(timer); render() }
  else if (!timer) timer = setTimeout(render, 60)
}, { immediate: true })
onBeforeUnmount(() => { generation++; if (timer) clearTimeout(timer) })
</script>
<template>
  <div ref="host" class="markdown-body" :data-markdown-state="state">
    <div v-for="block in blocks" :key="block.key" class="markdown-block">
      <DiagramPreview v-if="block.language === 'mermaid' && (state === 'static' || block.complete)" :source="block.source || ''" />
      <template v-else>
        <button v-if="previewableCode(block.language || '')" class="preview-code" :disabled="state === 'streaming' && !block.complete" @click="open(block)">{{ t('preview.open') }}</button>
        <pre v-if="block.code"><code :class="block.language ? `language-${block.language}` : undefined">{{ block.source }}</code></pre>
        <div v-else v-html="block.html" />
      </template>
    </div>
  </div>
</template>
<style scoped>
.markdown-body, .markdown-block { min-width: 0; }
.markdown-body :deep(.markdown-table-scroll) {
  max-width: 100%;
  overflow-x: auto;
  overscroll-behavior-x: contain;
  margin: 10px 0;
}
.markdown-body :deep(.markdown-table-scroll:focus-visible) { outline: 2px solid var(--accent); outline-offset: 2px; }
.markdown-block + .markdown-block { margin-top: 12px; }
.preview-code { padding:5px 10px; border:1px solid var(--border); background:var(--surface-1); color:var(--accent); border-radius:6px; cursor:pointer; }
</style>
