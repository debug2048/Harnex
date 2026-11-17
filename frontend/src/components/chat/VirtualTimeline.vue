<script setup lang="ts" generic="T extends { key: string }">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { historyAnchor, rememberHistoryAnchor } from '../../domain/historyViewCache'
const props = defineProps<{ items: T[]; scopeKey?: string; estimate?: (item: T) => number }>()
defineSlots<{ default(props: { item: T }): unknown }>()
const root = ref<HTMLElement | null>(null)
const top = ref(0), viewport = ref(800), measurement = ref(0), pinned = ref(new Set<string>())
const heights = new Map<string, number>(), elements = new Map<string, HTMLElement>()
let scroller: HTMLElement | null = null, resize: ResizeObserver | null = null, frame = 0, restoring = false, width = 0
let anchor: { key: string; offset: number } | null = null, atEnd = true, sequence = 0
let restoredSaved = false
const prefix = computed(() => {
  measurement.value
  const values = [0]
  for (const item of props.items) values.push(values[values.length - 1]! + (heights.get(item.key) ?? Math.max(32, props.estimate?.(item) || 150)))
  return values
})
const virtual = computed(() => props.items.length > 100 || prefix.value[prefix.value.length - 1]! > 40000)
const positions = computed(() => new Map(props.items.map((item, index) => [item.key, index])))
function indexAt(offset: number) {
  let low = 0, high = props.items.length
  while (low < high) { const mid = (low + high) >>> 1; if (prefix.value[mid + 1]! < offset) low = mid + 1; else high = mid }
  return low
}
const blocks = computed(() => {
  const indices = new Set<number>()
  const start = virtual.value ? Math.max(0, indexAt(top.value - 600)) : 0
  const end = virtual.value ? Math.min(props.items.length, indexAt(top.value + viewport.value + 600) + 1) : props.items.length
  for (let index = start; index < end; index++) indices.add(index)
  for (const key of pinned.value) { const index = positions.value.get(key); if (index !== undefined) indices.add(index) }
  const result: Array<{ key: string; item?: T; height?: number }> = []
  let previous = 0
  for (const index of [...indices].sort((a, b) => a - b)) {
    if (index > previous) result.push({ key: `gap:${previous}`, height: prefix.value[index]! - prefix.value[previous]! })
    const item = props.items[index]!; result.push({ key: item.key, item }); previous = index + 1
  }
  if (previous < props.items.length) result.push({ key: `gap:${previous}`, height: prefix.value[props.items.length]! - prefix.value[previous]! })
  return result
})
function captureAnchor() {
  if (!scroller) return null
  const boundary = scroller.getBoundingClientRect().top
  for (const element of root.value?.querySelectorAll<HTMLElement>('[data-timeline-key]') || []) {
    const rect = element.getBoundingClientRect()
    if (rect.bottom > boundary + 1 && rect.top < boundary + scroller.clientHeight)
      return { key: element.dataset.timelineKey!, offset: rect.top - boundary }
  }
  // A user can scroll into a virtual gap before the next animation frame mounts its rows.
  if (root.value) {
    const origin = root.value.getBoundingClientRect().top
    const index = indexAt(Math.max(0, boundary - origin))
    if (props.items[index]) return { key: props.items[index].key, offset: origin + prefix.value[index]! - boundary }
  }
  return null
}
function retainInteraction() {
  const keep = new Set<string>()
  const selection = document.getSelection()
  for (const [key, element] of elements) {
    if (element.contains(document.activeElement) || element.querySelector('[data-editing="true"]')) keep.add(key)
    if (selection && !selection.isCollapsed && selection.rangeCount) {
      try { if (selection.getRangeAt(0).intersectsNode(element)) keep.add(key) } catch { /* selection was removed */ }
    }
  }
  if (restoring && anchor) keep.add(anchor.key)
  if ([...keep].join('\0') !== [...pinned.value].join('\0')) pinned.value = keep
}
function update() {
  if (!scroller || !root.value) return
  top.value = Math.max(0, scroller.getBoundingClientRect().top - root.value.getBoundingClientRect().top)
  viewport.value = scroller.clientHeight
  if (!restoring) {
    anchor = captureAnchor(); atEnd = scroller.scrollHeight - scroller.scrollTop - scroller.clientHeight < 96
    if (anchor && props.scopeKey) rememberHistoryAnchor(props.scopeKey, { ...anchor, atEnd })
  }
  retainInteraction()
}
function schedule() { if (!frame) frame = requestAnimationFrame(() => { frame = 0; update() }) }
async function restorePosition(saved: typeof anchor, follow: boolean, version: number) {
  for (let pass = 0; pass < 4; pass++) {
    await nextTick()
    if (!scroller || !root.value || version !== sequence) return
    if (follow) scroller.scrollTop = scroller.scrollHeight
    else if (saved) {
      const index = positions.value.get(saved.key)
      if (index !== undefined) {
        const origin = scroller.scrollTop + root.value.getBoundingClientRect().top - scroller.getBoundingClientRect().top
        scroller.scrollTop = origin + prefix.value[index]! - saved.offset
      }
    }
    top.value = Math.max(0, scroller.getBoundingClientRect().top - root.value.getBoundingClientRect().top)
    await nextTick()
    if (version !== sequence) return
    let changed = false
    for (const [key, element] of elements) {
      const height = element.getBoundingClientRect().height
      if (height > 0 && Math.abs((heights.get(key) || 0) - height) > .5) { heights.set(key, height); changed = true }
    }
    if (!changed) break
    measurement.value++
  }
  restoring = false; update()
}
function measure(entries: ResizeObserverEntry[]) {
  const saved = anchor || captureAnchor(), follow = atEnd
  let changed = false
  for (const entry of entries) {
    const element = entry.target as HTMLElement, key = element.dataset.timelineKey
    if (key) {
      const height = element.getBoundingClientRect().height
      if (height > 0 && Math.abs((heights.get(key) || 0) - height) > .5) { heights.set(key, height); changed = true }
    } else if (element === scroller) {
      if (Math.abs(width - element.clientWidth) > 1) { width = element.clientWidth; heights.clear(); changed = true }
      if (viewport.value !== element.clientHeight) { viewport.value = element.clientHeight; changed = true }
    }
  }
  if (changed) {
    const version = ++sequence
    restoring = true; if (saved) pinned.value = new Set([...pinned.value, saved.key]); measurement.value++
    void restorePosition(saved, follow, version)
  }
}
function bind(key: string, value: unknown) {
  const previous = elements.get(key)
  if (previous && previous !== value) { resize?.unobserve(previous); elements.delete(key) }
  if (value instanceof HTMLElement && value !== previous) { elements.set(key, value); resize?.observe(value) }
}
function restoreSavedAnchor() {
  if (restoredSaved || !props.scopeKey || !props.items.length || !scroller) return false
  const saved = historyAnchor(props.scopeKey)
  restoredSaved = true
  if (!saved || saved.atEnd || !positions.value.has(saved.key)) return false
  anchor = saved; atEnd = false; restoring = true; pinned.value = new Set([saved.key])
  void restorePosition(saved, false, ++sequence); return true
}
watch(() => props.items, (_items, old) => {
  if (restoreSavedAnchor()) return
  if (!old.length || !scroller) { void nextTick(schedule); return }
  // Status/summary updates with unchanged row identities are handled by ResizeObserver.
  if (_items.length === old.length && _items.every((item, index) => item.key === old[index].key)) return
  const captured = captureAnchor()
  // An authoritative page may replace a provisional cached branch entirely.
  const saved = captured && positions.value.has(captured.key) ? captured : null
  const follow = !!captured && !saved || scroller.scrollHeight - scroller.scrollTop - scroller.clientHeight < 96
  const version = ++sequence
  anchor = saved; restoring = true; if (saved) pinned.value = new Set([...pinned.value, saved.key])
  void restorePosition(saved, follow, version)
})
watch(() => props.scopeKey, () => { sequence++; heights.clear(); pinned.value = new Set(); anchor = null; restoring = false; atEnd = true; restoredSaved = false; measurement.value++; void nextTick(() => { if (!restoreSavedAnchor()) schedule() }) })
function getItemTop(key: string) {
  const index = positions.value.get(key)
  if (index === undefined || !root.value) return null
  return elements.get(key)?.getBoundingClientRect().top ?? root.value.getBoundingClientRect().top + prefix.value[index]!
}
function syncScrollPosition() {
  // Navigation to live content outside this list must also cancel any pending bottom restoration.
  const version = ++sequence
  restoring = false; update()
  // Mounting the destination rows replaces estimated heights. Settle those measurements
  // before a queued scroll event can mistake the extra height for leaving the bottom.
  if (atEnd) { restoring = true; void restorePosition(null, true, version) }
}
async function scrollToKey(key: string, align: 'center' | 'start' = 'center') {
  const index = props.items.findIndex(item => item.key === key)
  if (index < 0 || !scroller || !root.value) return false
  atEnd = false
  if (align === 'start') {
    anchor = { key, offset: 16 }; restoring = true; pinned.value = new Set([...pinned.value, key])
    await restorePosition(anchor, false, ++sequence)
    return true
  }
  scroller.scrollTop += root.value.getBoundingClientRect().top - scroller.getBoundingClientRect().top + prefix.value[index]!
  update(); await nextTick()
  const element = elements.get(key)
  if (!element) return false
  element.scrollIntoView({ block: 'center' }); element.classList.add('history-target')
  setTimeout(() => element.classList.remove('history-target'), 1800)
  update(); return true
}
defineExpose({ scrollToKey, captureAnchor, getItemTop, syncScrollPosition })
onMounted(() => {
  scroller = root.value?.closest<HTMLElement>('.chat-messages, .inspector-body') || root.value?.parentElement || null
  width = scroller?.clientWidth || 0
  if (typeof ResizeObserver !== 'undefined') {
    resize = new ResizeObserver(measure); for (const element of elements.values()) resize.observe(element)
    if (scroller) resize.observe(scroller)
  }
  scroller?.addEventListener('scroll', schedule, { passive: true })
  document.addEventListener('selectionchange', schedule); root.value?.addEventListener('focusin', schedule); root.value?.addEventListener('focusout', schedule)
  if (!restoreSavedAnchor()) update()
})
onBeforeUnmount(() => {
  sequence++; resize?.disconnect(); if (frame) cancelAnimationFrame(frame)
  scroller?.removeEventListener('scroll', schedule); document.removeEventListener('selectionchange', schedule)
  root.value?.removeEventListener('focusin', schedule); root.value?.removeEventListener('focusout', schedule)
})
</script>
<template>
  <div ref="root" class="virtual-timeline">
    <template v-for="block in blocks" :key="block.key">
      <div v-if="block.item" :ref="value => bind(block.key, value)" :data-timeline-key="block.key" class="timeline-row"><slot :item="block.item" /></div>
      <div v-else class="timeline-gap" :style="{ height: `${block.height}px` }" aria-hidden="true" />
    </template>
  </div>
</template>
<style scoped>
.virtual-timeline { min-width: 0; overflow-anchor: none; }
.timeline-row { min-width: 0; display: flow-root; }
.history-target { outline: 2px solid var(--accent); outline-offset: -2px; border-radius: 8px; }
.timeline-gap { pointer-events: none; }
</style>
