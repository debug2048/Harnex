import { defineStore } from 'pinia'
import { ref, watch, onScopeDispose } from 'vue'
import { api, getRuntimeRevision, onRuntimeIdentityChanged } from '../api/client'
import { parseModelCatalog, type ModelOption } from '../domain/modelCatalog'
import { useWorkspaceStore } from './workspace'
import { useRuntimeStore } from './runtime'
import { errorMessage } from '../utils/unknown'
import { onConfigurationChanged } from '../domain/configEvents'

export const useModelCatalogStore = defineStore('modelCatalog', () => {
  const workspace = useWorkspaceStore(), runtime = useRuntimeStore()
  const models = ref<ModelOption[]>([]), hasProviders = ref(false), loading = ref(false), error = ref<string | null>(null)
  const ready = ref(false)
  const cache = new Map<string, ReturnType<typeof parseModelCatalog>>()
  let loadedKey: string | null = null, sequence = 0
  let inFlight: { key: string; revision: number; promise: Promise<void> } | null = null
  const identity = () => JSON.stringify([workspace.scopeId, workspace.scopeInstanceId])
  function invalidate() {
    sequence++; inFlight = null; loading.value = false; error.value = null
    ready.value = false
    const key = identity(), saved = cache.get(key)
    models.value = saved?.models || []; hasProviders.value = saved?.hasProviders || false
    // Cached choices are a visual placeholder; another Runtime may have saved new Provider settings.
    loadedKey = null
  }
  const unsubscribe = onRuntimeIdentityChanged(invalidate)
  onScopeDispose(unsubscribe)
  watch(() => [workspace.scopeId, workspace.scopeInstanceId, workspace.epoch], invalidate, { flush: 'sync' })

  async function load(force = false) {
    if (!workspace.ready) return
    const key = identity(), revision = getRuntimeRevision()
    if (!force && loadedKey === key && runtime.ready) return
    if (inFlight?.key === key && inFlight.revision === revision) return inFlight.promise
    const request = ++sequence
    const current = () => request === sequence && revision === getRuntimeRevision() && key === identity() && workspace.ready
    const promise = (async () => {
      loading.value = true; error.value = null
      try {
        await runtime.load({ force })
        if (!current()) return
        if (!force && loadedKey === key) return
        const result = parseModelCatalog(await api.models())
        if (!current()) return
        models.value = result.models; hasProviders.value = result.hasProviders; loadedKey = key
        ready.value = true
        cache.delete(key); cache.set(key, result)
        if (cache.size > 8) cache.delete(cache.keys().next().value!)
      } catch (cause) {
        if (current()) {
          error.value = errorMessage(cause)
          hasProviders.value ||= Boolean(runtime.statusline?.providers?.length)
        }
      } finally { if (current()) loading.value = false }
    })()
    const operation = { key, revision, promise }
    inFlight = operation
    try { await promise } finally { if (inFlight === operation) inFlight = null }
  }

  const unsubscribeConfiguration = onConfigurationChanged(() => {
    cache.clear()
    invalidate()
    void load(true)
  })
  onScopeDispose(unsubscribeConfiguration)
  if (typeof window !== 'undefined' && typeof window.addEventListener === 'function') {
    const refreshVisible = () => {
      if (workspace.ready && !document.hidden) void load(true)
    }
    // Also discover saves from another browser window or a different Runtime using the same user file.
    const timer = window.setInterval(refreshVisible, 10_000)
    window.addEventListener('focus', refreshVisible)
    onScopeDispose(() => { window.clearInterval(timer); window.removeEventListener('focus', refreshVisible) })
  }
  return { models, hasProviders, loading, error, ready, load }
})
