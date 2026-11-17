import { defineStore } from 'pinia'
import { ref, watch } from 'vue'
import type { SendShortcut } from '../domain/composerShortcuts'

export const useSettings = defineStore('settings', () => {
  const sendShortcut = ref<SendShortcut>(localStorage.getItem('harnex_send_shortcut') === 'ctrl-enter' ? 'ctrl-enter' : 'enter')
  const userRoleName = ref(localStorage.getItem('harnex_user_role_name') || '')
  const assistantRoleName = ref(localStorage.getItem('harnex_assistant_role_name') || '')
  watch(sendShortcut, value => localStorage.setItem('harnex_send_shortcut', value))
  watch(userRoleName, value => localStorage.setItem('harnex_user_role_name', value))
  watch(assistantRoleName, value => localStorage.setItem('harnex_assistant_role_name', value))
  // The composer selects a provider/model pair. Restoring only a provider would display
  // "default" while silently overriding the backend's current default provider.
  const model = ref<string>('')
  const provider = ref<string>('')
  /** Sticky conversation-level override; "auto" delegates to the selected Provider configuration. */
  const reasoningEffort = ref<string>('auto')
  return { model, provider, reasoningEffort, sendShortcut, userRoleName, assistantRoleName }
})
