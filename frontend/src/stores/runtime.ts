import { defineStore } from 'pinia'
import { computed, ref, watch } from 'vue'
import { api, getRuntimeRevision } from '../api/client'
import type {
  ProtocolDocument,
  RuntimeDiagnostics,
  RuntimeStatusline,
} from '../api/contracts'
import { useWorkspaceStore } from './workspace'
import { errorMessage } from '../utils/unknown'

type LoadOptions = {
  diagnostics?: boolean
  force?: boolean
}

/**
 * Projection of the currently open Workspace JVM.
 *
 * This is intentionally separate from runStore: one Runtime can execute many
 * Agent Runs, while protocol/diagnostics live for the JVM instance.
 */
export const useRuntimeStore = defineStore('runtime', () => {
  const workspace = useWorkspaceStore()
  const protocol = ref<ProtocolDocument | null>(null)
  // Compatibility name for view code; both references now have one authoritative source.
  const capabilities = computed(() => protocol.value)
  const diagnostics = ref<RuntimeDiagnostics | null>(null)
  const statusline = ref<RuntimeStatusline | null>(null)
  const loading = ref(false)
  const error = ref<string | null>(null)
  const loadedIdentity = ref<string | null>(null)
  let requestSequence = 0
  let inFlight: { identity: string; promise: Promise<void> } | null = null

  const ready = computed(() =>
    Boolean(protocol.value && loadedIdentity.value === identityKey()),
  )

  function identityKey() {
    return workspace.scopeInstanceId
      ? `${workspace.scopeId || ''}:${workspace.scopeInstanceId}`
      : `direct:${workspace.epoch}`
  }

  function has(capability: string) {
    return Boolean(protocol.value?.capabilities?.includes(capability))
  }

  function invalidate() {
    requestSequence++
    protocol.value = null
    diagnostics.value = null
    statusline.value = null
    loadedIdentity.value = null
    loading.value = false
    error.value = null
  }

  async function load(options: LoadOptions = {}) {
    if (!workspace.ready) return
    const identity = identityKey()
    const revision = getRuntimeRevision()
    if (!options.force && ready.value && (!options.diagnostics || diagnostics.value)) return

    // Several READY consumers mount at the same time (composer, explorer, diagnostics).
    // Previously every caller incremented requestSequence, so an earlier caller could
    // finish successfully, discard its result as stale, and still return to its consumer.
    // The file explorer would then observe an empty protocol and hide itself forever.
    // Share one request per Runtime identity; a diagnostics caller can make a small
    // follow-up request after the shared basic load when necessary.
    const pending = inFlight
    if (pending?.identity === identity) {
      await pending.promise
      if (identity !== identityKey() || revision !== getRuntimeRevision() || !workspace.ready) return
      if (!options.force && ready.value && (!options.diagnostics || diagnostics.value)) return
    }

    const sequence = ++requestSequence
    const operation = (async () => {
      loading.value = true
      error.value = null
      try {
        const [nextProtocol, nextStatusline, nextDiagnostics] = await Promise.all([
          api.protocol(),
          api.statusline().catch(() => null),
          options.diagnostics ? api.diagnostics() : Promise.resolve(null),
        ])
        if (sequence !== requestSequence || identity !== identityKey() || revision !== getRuntimeRevision()) return
        protocol.value = nextProtocol
        statusline.value = nextStatusline
        if (options.diagnostics) diagnostics.value = nextDiagnostics
        loadedIdentity.value = identity
      } catch (cause: unknown) {
        if (sequence === requestSequence && revision === getRuntimeRevision()) error.value = errorMessage(cause)
        throw cause
      } finally {
        if (sequence === requestSequence) loading.value = false
      }
    })()
    const current = { identity, promise: operation }
    inFlight = current
    try {
      await operation
    } finally {
      if (inFlight === current) inFlight = null
    }
  }

  async function refreshStatusline() {
    const identity = identityKey()
    const sequence = requestSequence
    const next = await api.statusline()
    if (sequence === requestSequence && identity === identityKey()) statusline.value = next
  }

  watch(
    () => [workspace.scopeId, workspace.scopeInstanceId, workspace.epoch] as const,
    invalidate,
    { flush: 'sync' },
  )

  return {
    capabilities, protocol, diagnostics, statusline, loading, error, loadedIdentity, ready,
    has, load, refreshStatusline, invalidate,
  }
})
