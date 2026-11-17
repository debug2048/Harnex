import { nextTick, watch, type Ref } from 'vue'
import type { RunInputView } from '../api/contracts'
import type { Msg } from '../domain/chat'

/** Reuse the conversation projection without persisting another copy of user input. */
export function composerInputHistory(messages: Msg[], runInputs: RunInputView[]): string[] {
  const represented = new Set(messages.flatMap(message => message.runInputIds || []))
  return [
    ...messages.filter(message => message.role === 'user')
      .map(message => message.originalInput ?? message.content),
    ...runInputs.filter(input => !represented.has(input.inputId)
      && ['QUEUED', 'APPLYING', 'HELD', 'APPLIED'].includes(input.status))
      .sort((a, b) => a.sequence - b.sequence).map(input => input.text),
  ].filter(text => text.trim()).slice(-100)
}

interface HistoryOptions {
  input: Ref<string>
  entries: Readonly<Ref<readonly string[]>>
  scope: Readonly<Ref<string>>
}

export function useComposerHistory({ input, entries, scope }: HistoryOptions) {
  let history: string[] = []
  let position = -1
  let draft = ''
  let recalling = false

  function reset() {
    history = []
    position = -1
    draft = ''
  }

  watch(scope, reset, { flush: 'sync' })
  // Editing a recalled message makes it the new draft. Submission, completion and
  // other programmatic replacements must also leave history browsing mode.
  watch(input, () => { if (!recalling) reset() }, { flush: 'sync' })

  function onKey(event: KeyboardEvent): boolean {
    const backward = event.key === 'ArrowUp'
    if (!backward && event.key !== 'ArrowDown') {
      if (['ArrowLeft', 'ArrowRight', 'Home', 'End', 'PageUp', 'PageDown'].includes(event.key)) reset()
      return false
    }
    if (event.isComposing || event.keyCode === 229) return false
    if (event.altKey || event.ctrlKey || event.metaKey || event.shiftKey) {
      reset()
      return false
    }
    const editor = event.target as HTMLTextAreaElement | null
    if (editor?.tagName !== 'TEXTAREA') return false
    if (editor.selectionStart !== editor.selectionEnd) {
      reset()
      return false
    }
    if (position < 0) {
      // Enter history only at the start of the draft. This also preserves native
      // arrow navigation through soft-wrapped lines, not just explicit newlines.
      if (!backward || editor.selectionStart !== 0 || !entries.value.length) return false
      history = [...entries.value]
      draft = input.value
      position = history.length
    }
    event.preventDefault()
    position = Math.max(0, Math.min(history.length, position + (backward ? -1 : 1)))
    const text = position === history.length ? draft : history[position]
    recalling = true
    try { input.value = text } finally { recalling = false }
    if (position === history.length) reset()
    void nextTick(() => {
      if (editor.isConnected && editor.value === text) {
        editor.setSelectionRange(text.length, text.length)
      }
    })
    return true
  }

  return { onKey, reset }
}
