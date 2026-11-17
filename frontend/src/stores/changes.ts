import { defineStore } from 'pinia'
import { ref, watch } from 'vue'
import { api } from '../api/client'
import type { ChangesView } from '../api/contracts'
import { useWorkspaceStore } from './workspace'
import { errorMessage } from '../utils/unknown'

export const useChangesStore = defineStore('changes', () => {
  const workspace = useWorkspaceStore()
  const runId = ref<string | null>(null)
  const view = ref<ChangesView | null>(null)
  const loading = ref(false)
  const error = ref<string | null>(null)

  async function load(targetRunId: string) {
    const requestEpoch = workspace.epoch
    if (runId.value !== targetRunId) {
      // A completed Run loads asynchronously. Clear the prior Run first so its file count
      // never flashes under the new task's transcript notice or Undo action.
      view.value = null
    }
    runId.value = targetRunId
    loading.value = true
    error.value = null
    try {
      const result = await api.changes(targetRunId)
      if (requestEpoch !== workspace.epoch || runId.value !== targetRunId) return
      view.value = result
    } catch (cause: unknown) {
      if (requestEpoch === workspace.epoch) error.value = errorMessage(cause)
    } finally {
      if (requestEpoch === workspace.epoch) loading.value = false
    }
  }

  function reset() {
    runId.value = null
    view.value = null
    loading.value = false
    error.value = null
  }

  watch(() => workspace.epoch, reset)

  return { runId, view, loading, error, load, reset }
})
