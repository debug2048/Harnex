import { createApp } from 'vue'
import { createPinia } from 'pinia'
import { createRouter, createWebHashHistory, createWebHistory } from 'vue-router'
import App from './App.vue'
import { workbenchRoutes } from './workbenchRoutes'
import { api, isTauriHost, setRuntimeApiBase } from './api/client'
import { useRunController } from './services/runController'
import { useConversationStore } from './stores/conversation'
import './style.css'
import './themes/liuguang.css'

const IDE = (globalThis as unknown as Record<string, unknown>).__HARNEX_IDE__ === true
const NATIVE = isTauriHost()

const router = createRouter({
  // Bundled Tauri assets have no HTTP history fallback. Hash routing keeps direct
  // refresh/navigation of settings and threads inside the trusted packaged SPA.
  history: IDE || NATIVE ? createWebHashHistory() : createWebHistory(),
  routes: workbenchRoutes,
})

router.beforeEach(async (to) => {
  if (IDE) return true

  // Hosts may attach a one-shot boot token when explicit API authentication is enabled.
  // It is exchanged silently; the user-level GUI never exposes an interactive login flow.
  if (typeof to.query.bootToken === 'string' && to.query.bootToken) {
    try { await api.bootToken(to.query.bootToken) } catch { /* API calls will surface an actionable 401 */ }
    const query = { ...to.query }
    delete (query as Record<string, unknown>).bootToken
    return { path: to.path, query, replace: true }
  }

  return true
})

async function mountApp() {
  if (isTauriHost()) {
    try {
      const invoke = (globalThis as typeof globalThis & {
        __TAURI_INTERNALS__?: { invoke?: (command: string) => Promise<unknown> }
      }).__TAURI_INTERNALS__?.invoke
      const base = await invoke?.('runtime_base_url')
      if (typeof base === 'string') setRuntimeApiBase(base)
    } catch (cause) {
      console.warn('[desktop] failed to resolve Runtime URL:', cause)
    }
  }
  createApp(App).use(createPinia()).use(router).mount('#app')
}

void mountApp()

// The VS Code host uses the same Run/Interaction protocol but owns workspace lifecycle itself.
if (IDE) {
  window.addEventListener('message', async (event: MessageEvent) => {
    const message = event.data as { __harnex?: boolean; kind?: string; action?: string; text?: string; mode?: string }
    if (!message || message.__harnex !== true || message.kind !== 'ui') return
    const runController = useRunController()
    const conversation = useConversationStore()
    if (message.action === 'prompt' && message.text?.trim()) {
      await router.push('/').catch(() => {})
      void runController.send(message.text)
    } else if (message.action === 'newThread') {
      runController.reset()
      await router.push('/').catch(() => {})
    } else if (message.action === 'setMode') {
      const mode = message.mode as 'ask' | 'accept-edits' | 'yolo'
      if (mode === 'ask' || mode === 'accept-edits' || mode === 'yolo') {
        conversation.permissionMode = mode
        if (conversation.threadId) void api.setMode(conversation.threadId, mode).catch(() => {})
      }
    }
  })
}
