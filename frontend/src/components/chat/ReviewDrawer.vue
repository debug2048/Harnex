<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { t } from '../../i18n'

const props = defineProps<{ open: boolean; label?: string; wide?: boolean }>()
const emit = defineEmits<{ close: [] }>()
const panel = ref<HTMLElement | null>(null)
let returnFocus: HTMLElement | null = null
const widthKey = 'harnex:inspector-width'
function readWidth() {
  try {
    const value = Number(localStorage.getItem(widthKey))
    return Number.isFinite(value) && value > 0 ? value : null
  } catch { return null }
}
const preferredWidth = ref<number | null>(readWidth())
const bounds = ref({ min: 320, max: typeof window === 'undefined' ? 960 : Math.max(0, window.innerWidth - 24) })
const panelWidth = computed(() => clampWidth(preferredWidth.value ?? (props.wide ? 440 : 352)))
const resizing = ref(false)
let resizeObserver: ResizeObserver | undefined
let drag: { pointerId: number; x: number; width: number; handle: HTMLElement } | null = null

function clampWidth(width: number) { return Math.round(Math.min(bounds.value.max, Math.max(bounds.value.min, width))) }
function measureBounds() {
  const element = panel.value
  if (!element) return
  const available = element.parentElement?.clientWidth || window.innerWidth
  const overlay = getComputedStyle(element).position === 'fixed'
  const max = overlay ? Math.max(0, window.innerWidth - 24) : Math.min(available, Math.max(320, available - 320))
  bounds.value = { min: Math.min(320, max), max }
}
function rememberWidth() {
  try {
    if (preferredWidth.value === null) localStorage.removeItem(widthKey)
    else localStorage.setItem(widthKey, String(preferredWidth.value))
  } catch { /* The width still applies for this session when storage is unavailable. */ }
}
function startResize(event: PointerEvent) {
  if (event.button !== 0 || !event.isPrimary) return
  event.preventDefault()
  measureBounds()
  const handle = event.currentTarget as HTMLElement
  handle.focus({ preventScroll: true })
  drag = { pointerId: event.pointerId, x: event.clientX, width: panelWidth.value, handle }
  handle.setPointerCapture(event.pointerId)
  resizing.value = true
  document.documentElement.classList.add('inspector-resizing')
}
function resize(event: PointerEvent) {
  if (!drag || event.pointerId !== drag.pointerId) return
  preferredWidth.value = clampWidth(drag.width + drag.x - event.clientX)
}
function stopResize(event?: PointerEvent | Event) {
  if (!drag || event instanceof PointerEvent && event.pointerId !== drag.pointerId) return
  const current = drag
  drag = null
  resizing.value = false
  document.documentElement.classList.remove('inspector-resizing')
  if (current.handle.hasPointerCapture(current.pointerId)) current.handle.releasePointerCapture(current.pointerId)
  rememberWidth()
}
function resetWidth() {
  stopResize()
  preferredWidth.value = null
  rememberWidth()
}
function resizeWithKeyboard(event: KeyboardEvent) {
  if (!['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key)) return
  event.preventDefault()
  event.stopPropagation()
  measureBounds()
  const step = event.shiftKey ? 64 : 24
  preferredWidth.value = event.key === 'Home' ? bounds.value.min : event.key === 'End' ? bounds.value.max
    : clampWidth(panelWidth.value + (event.key === 'ArrowLeft' ? step : -step))
  rememberWidth()
}

watch(panel, element => {
  resizeObserver?.disconnect()
  if (!element) return
  measureBounds()
  resizeObserver = new ResizeObserver(measureBounds)
  if (element.parentElement) resizeObserver.observe(element.parentElement)
}, { flush: 'post' })
onMounted(() => {
  window.addEventListener('resize', measureBounds)
  window.addEventListener('blur', stopResize)
})

watch(() => props.open, async (open) => {
  if (open) {
    returnFocus = document.activeElement instanceof HTMLElement ? document.activeElement : null
    await nextTick()
    const target = panel.value?.querySelector<HTMLElement>(
      '[data-autofocus], button:not(:disabled), [href], input:not(:disabled), select:not(:disabled), textarea:not(:disabled), [tabindex]:not([tabindex="-1"])',
    )
    ;(target || panel.value)?.focus()
  } else {
    stopResize()
    returnFocus?.focus()
    returnFocus = null
  }
}, { flush: 'post' })

function onKeydown(event: KeyboardEvent) {
  if (event.key !== 'Escape') return
  event.preventDefault()
  emit('close')
}

onBeforeUnmount(() => {
  stopResize()
  resizeObserver?.disconnect()
  window.removeEventListener('resize', measureBounds)
  window.removeEventListener('blur', stopResize)
  returnFocus?.focus()
})
</script>

<template>
  <aside v-if="open" ref="panel" class="inspector-panel" :class="{ wide: props.wide, resizing }" tabindex="-1"
    :style="{ '--inspector-width': `${panelWidth}px` }"
    :aria-label="label || 'Review'" @keydown="onKeydown">
    <slot :close="() => emit('close')" />
    <div class="inspector-resize-handle" role="separator" tabindex="0" aria-orientation="vertical"
      :aria-label="t('chat.resizeInspector')" :title="t('chat.resizeInspectorHint')"
      :aria-valuemin="bounds.min" :aria-valuemax="bounds.max" :aria-valuenow="panelWidth"
      @pointerdown="startResize" @pointermove="resize" @pointerup="stopResize"
      @pointercancel="stopResize" @lostpointercapture="stopResize"
      @dblclick="resetWidth" @keydown="resizeWithKeyboard" />
  </aside>
</template>
<style src="./WorkbenchInspector.global.css"></style>
