import { nextTick, onBeforeUnmount, ref, watch, type Ref } from 'vue'

export interface TranscriptScrollTarget {
  element: HTMLElement | null
  getTurnStartTop(): number | null
  scrollToTurnStart(): Promise<boolean>
  syncScrollPosition(): void
}

/** Keep navigation tied to the rendered viewport, including streaming and resize. */
export function useTranscriptScroll(container: Ref<HTMLElement | null>, transcript: Ref<TranscriptScrollTarget | null>) {
  const followingLatest = ref(true)
  const showReturnLatest = ref(false)
  const showReturnStart = ref(false)
  let resize: ResizeObserver | undefined
  let frame = 0
  let disposed = false

  function updateNavigation() {
    const element = container.value
    if (!element) { showReturnLatest.value = false; showReturnStart.value = false; return }
    const distance = element.scrollHeight - element.scrollTop - element.clientHeight
    showReturnLatest.value = distance > 96
    const start = transcript.value?.getTurnStartTop()
    showReturnStart.value = !showReturnLatest.value && start != null
      && start < element.getBoundingClientRect().top - 1
  }

  function updateScrollAnchor() {
    const element = container.value
    if (!element) return
    followingLatest.value = element.scrollHeight - element.scrollTop - element.clientHeight <= 96
    updateNavigation()
  }

  function scheduleNavigation() {
    if (!frame) frame = requestAnimationFrame(() => { frame = 0; updateNavigation() })
  }

  function scrollBottom(force = false) {
    if (!force && !followingLatest.value) { scheduleNavigation(); return }
    void nextTick(() => {
      if (disposed || (!force && !followingLatest.value)) return
      const element = container.value
      if (!element) return
      element.scrollTop = element.scrollHeight
      transcript.value?.syncScrollPosition()
      followingLatest.value = true
      updateNavigation()
    })
  }

  function returnToLatest() { followingLatest.value = true; scrollBottom(true) }
  async function returnToTurnStart() {
    followingLatest.value = false
    await transcript.value?.scrollToTurnStart()
    updateNavigation()
  }

  watch([container, transcript], ([element, content]) => {
    resize?.disconnect()
    if (element && typeof ResizeObserver !== 'undefined') {
      resize = new ResizeObserver(() => {
        // Images, expanded tools and composer resizing can change the viewport without a scroll event.
        if (followingLatest.value) scrollBottom()
        else scheduleNavigation()
      })
      resize.observe(element)
      if (content?.element) resize.observe(content.element)
    }
    scheduleNavigation()
  }, { flush: 'post' })

  onBeforeUnmount(() => { disposed = true; resize?.disconnect(); if (frame) cancelAnimationFrame(frame) })
  return { followingLatest, showReturnLatest, showReturnStart, updateScrollAnchor, scheduleNavigation,
    scrollBottom, returnToLatest, returnToTurnStart }
}
