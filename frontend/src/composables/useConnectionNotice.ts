import { onScopeDispose, ref } from 'vue'
import type { RuntimeConnectionState } from '../api/client'

/** Show sustained outages while keeping a brief, automatically recovered transport gap from flashing the UI. */
export function useConnectionNotice() {
  const disconnected = ref(false), message = ref('')
  let timer: ReturnType<typeof setTimeout> | null = null
  function reset() {
    if (timer !== null) clearTimeout(timer)
    timer = null; disconnected.value = false; message.value = ''
  }
  function update(state: RuntimeConnectionState) {
    if (state.connected) { reset(); return }
    message.value = state.message || ''
    if (disconnected.value || timer !== null) return
    timer = setTimeout(() => { timer = null; disconnected.value = true }, 1000)
  }
  onScopeDispose(reset)
  return { disconnected, message, update, reset }
}
