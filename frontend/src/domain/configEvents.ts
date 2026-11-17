// Configuration changes do not change Runtime identity or discard conversation state.
const listeners = new Set<() => void>()

export function onConfigurationChanged(listener: () => void) {
  listeners.add(listener)
  return () => listeners.delete(listener)
}

export function notifyConfigurationChanged() {
  for (const listener of listeners) listener()
}
