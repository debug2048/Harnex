import { computed, ref, watch } from 'vue'
import { defineStore } from 'pinia'
import { api, getRuntimeRevision } from '../api/client'
import type { WorkspaceGit } from '../api/contracts'
import { useWorkspaceStore } from './workspace'

export const useWorkspaceGitStore = defineStore('workspaceGit', () => {
  const workspace = useWorkspaceStore()
  const git = ref<WorkspaceGit | null>(null)
  const active = ref(false)
  const enabled = computed(() => active.value && workspace.profile === 'PROJECT' && workspace.ready)
  let revision = 0
  let pending: AbortController | null = null
  let timer: ReturnType<typeof setInterval> | null = null

  async function refresh() {
    if (!enabled.value || pending || (typeof document !== 'undefined' && document.hidden)) return
    const controller = new AbortController()
    pending = controller
    const expected = revision
    const runtimeRevision = getRuntimeRevision()
    const timeout = setTimeout(() => controller.abort(), 8000)
    try {
      const next = await api.workspaceGit(controller.signal)
      if (expected === revision && runtimeRevision === getRuntimeRevision()) git.value = next
    } catch {
      if (expected === revision && runtimeRevision === getRuntimeRevision()) {
        git.value = { state: 'unavailable', branch: null, commit: null }
      }
    } finally {
      clearTimeout(timeout)
      if (pending === controller) pending = null
    }
  }

  watch(() => [enabled.value, workspace.scopeId, workspace.scopeInstanceId, workspace.epoch], () => {
    revision++
    pending?.abort()
    pending = null
    git.value = null
    if (timer) clearInterval(timer)
    timer = null
    if (enabled.value) {
      void refresh()
      timer = setInterval(() => void refresh(), 5000)
    }
  }, { flush: 'sync' })

  function start() {
    if (active.value) return
    window.addEventListener('focus', refresh)
    document.addEventListener('visibilitychange', refresh)
    active.value = true
  }

  function stop() {
    active.value = false
    window.removeEventListener('focus', refresh)
    document.removeEventListener('visibilitychange', refresh)
  }

  return { git, refresh, start, stop }
})
