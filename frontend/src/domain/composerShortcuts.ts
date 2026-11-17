export type SendShortcut = 'enter' | 'ctrl-enter'

type KeyEvent = Pick<KeyboardEvent, 'key' | 'shiftKey' | 'ctrlKey' | 'metaKey' | 'altKey' | 'isComposing' | 'keyCode'>

/** Resolve submission keys without consuming IME confirmation or newline keys. */
export function composerSubmitMode(event: KeyEvent, shortcut: SendShortcut, streaming: boolean): 'STEER' | 'QUEUE' | null {
  if (event.key !== 'Enter' || event.isComposing || event.keyCode === 229 || event.altKey) return null
  const modified = event.ctrlKey || event.metaKey
  if (shortcut === 'ctrl-enter') {
    if (!modified) return null
    if (event.shiftKey) return streaming ? 'QUEUE' : null
    return 'STEER'
  }
  if (event.shiftKey) return null
  return streaming && modified ? 'QUEUE' : 'STEER'
}
