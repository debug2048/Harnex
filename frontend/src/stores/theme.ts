import { defineStore } from 'pinia'
import { onScopeDispose, ref, watch } from 'vue'
import {
  isTheme,
  resolveTheme,
  resolveThemeMode,
  themeFollowsSystem,
  type Theme,
  type ThemeMode,
} from '../domain/themes'
import { syncNativeWindowTheme } from '../api/client'

const STORAGE_KEY = 'harnex_theme'

function storedTheme(): Theme {
  try {
    const value = globalThis.localStorage?.getItem(STORAGE_KEY)
    // Macaron is now part of Yanzhi; initialize() persists the canonical choice.
    if (value === 'macaron') return 'yanzhi'
    if (value === 'macos') return 'liuguang'
    return isTheme(value) ? value : 'system'
  } catch {
    return 'system'
  }
}

/**
 * User-level appearance state. It deliberately has no dependency on Settings,
 * Workspace, Runtime, or the active route, so the selected theme is applied
 * before any workspace JVM is available and survives every workspace switch.
 */
export const useThemeStore = defineStore('theme', () => {
  const theme = ref<Theme>(storedTheme())
  const resolved = ref<Exclude<Theme, 'system'>>('light')
  const mode = ref<ThemeMode>('light')
  const initialized = ref(false)
  let darkMedia: MediaQueryList | null = null
  let stopThemeWatch: (() => void) | null = null
  let nativeSync: Promise<void> = Promise.resolve()
  let applyRevision = 0
  let disposed = false

  function persist(value: Theme) {
    try { globalThis.localStorage?.setItem(STORAGE_KEY, value) } catch { /* privacy/quota mode */ }
  }

  function render(value: Theme = theme.value) {
    const prefersDark = darkMedia?.matches === true
    const next = resolveTheme(value, prefersDark)
    const nextMode = resolveThemeMode(value, prefersDark)
    resolved.value = next
    mode.value = nextMode
    if (typeof document !== 'undefined') {
      const root = document.documentElement
      root.setAttribute('data-theme', next)
      root.setAttribute('data-theme-preference', value)
      root.setAttribute('data-theme-mode', nextMode)
      root.style.colorScheme = nextMode
    }
  }

  function apply(value: Theme = theme.value) {
    const revision = ++applyRevision
    render(value)
    persist(value)
    // Native overrides also affect prefers-color-scheme in WebView2. Remove the override for
    // adaptive themes, then read the restored preference; media changes only repaint the page.
    nativeSync = nativeSync.then(async () => {
      if (disposed || revision !== applyRevision) return
      await syncNativeWindowTheme(themeFollowsSystem(value) ? 'system' : resolveThemeMode(value, false))
      if (!disposed && revision === applyRevision) render(value)
    }).catch(cause => {
      console.warn('[theme] failed to synchronize native window decorations:', cause)
    })
  }

  function onSystemThemeChanged() {
    if (themeFollowsSystem(theme.value)) render(theme.value)
  }

  function initialize() {
    if (initialized.value) return
    initialized.value = true
    darkMedia = typeof window !== 'undefined'
      ? window.matchMedia?.('(prefers-color-scheme: dark)') || null
      : null
    darkMedia?.addEventListener?.('change', onSystemThemeChanged)
    apply()
    stopThemeWatch = watch(theme, value => apply(value))
  }

  function setTheme(value: Theme) {
    if (!initialized.value) initialize()
    theme.value = value
  }

  onScopeDispose(() => {
    disposed = true
    applyRevision++
    stopThemeWatch?.()
    darkMedia?.removeEventListener?.('change', onSystemThemeChanged)
  })

  return { theme, resolved, mode, initialized, initialize, setTheme }
})
