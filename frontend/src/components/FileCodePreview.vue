<script setup lang="ts">
import { computed, ref } from 'vue'
import { t } from '../i18n'
const props = defineProps<{ content: string }>()
const numbers = computed(() => Array.from({length:props.content.split(/\r\n|\r|\n/).length}, (_, index) => index + 1).join('\n'))
const region = ref<HTMLElement | null>(null)
const source = ref<HTMLElement | null>(null)
function onKeydown(event: KeyboardEvent) {
  if (!(event.ctrlKey || event.metaKey) || event.altKey || event.shiftKey || event.key.toLowerCase() !== 'a' || !source.value) return
  event.preventDefault()
  event.stopPropagation()
  const range = document.createRange()
  range.selectNodeContents(source.value)
  const selection = window.getSelection()
  selection?.removeAllRanges()
  selection?.addRange(range)
}
</script>

<template>
  <div ref="region" class="source-code" role="region" :aria-label="t('files.textPreview')" tabindex="0"
    @pointerdown="region?.focus({ preventScroll: true })" @keydown="onKeydown">
    <div class="line-numbers" aria-hidden="true" :data-lines="numbers" />
    <pre ref="source" class="source-text">{{ content }}</pre>
  </div>
</template>

<style scoped>
.source-code { display:grid; grid-template-columns:max-content minmax(0,1fr); overflow:auto; border:1px solid var(--border); border-radius:7px; background:var(--surface-1); font:12px/1.6 var(--font-mono); tab-size:4; }
.line-numbers { position:sticky; left:0; z-index:1; min-width:3ch; padding:10px 9px; border-right:1px solid var(--border); background:var(--surface-2); color:var(--text-faint); text-align:right; user-select:none; -webkit-user-select:none; pointer-events:none; }
/* Generated content has no text nodes, so even a selection spanning the gutter copies only the source. */
.line-numbers::before { content:attr(data-lines); display:block; white-space:pre; }
.source-text { box-sizing:border-box; width:max-content; min-width:100%; margin:0; padding:10px 12px; border:0; border-radius:0; overflow:visible; background:transparent; color:var(--text-primary); font:inherit; white-space:pre; user-select:text; -webkit-user-select:text; }
</style>
