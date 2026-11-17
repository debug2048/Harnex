import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import {
  api,
  setApiIdentity,
  setRemoteRuntime,
  setRuntimeApiBase,
  supervisor,
  SupervisorApiError,
  type SupervisorWorkspace,
  type PersonalScope,
} from '../api/client'
import { errorMessage } from '../utils/unknown'
import { t } from '../i18n'

export type WorkspaceLifecycle = SupervisorWorkspace['state']

/**
 * Stable Supervisor projection. `epoch` changes before a switch starts, allowing every workspace-scoped store to
 * discard stale async responses atomically even when an old fetch resolves during the replacement JVM's startup.
 */
export const useWorkspaceStore = defineStore('workspace', () => {
  const available = ref(false)
  const fixedWorkspaceId = ref<string | null>(null)
  const canSelectProject = computed(() => available.value && !fixedWorkspaceId.value)
  const workspaces = ref<SupervisorWorkspace[]>([])
  const activeWorkspaceId = ref<string | null>(null)
  const lastWorkspaceId = ref<string | null>(null)
  const instanceId = ref<string | null>(null)
  const profile = ref<'PERSONAL' | 'PROJECT'>('PROJECT')
  const personal = ref<PersonalScope | null>(null)
  const scopeId = computed(() => profile.value === 'PERSONAL' ? personal.value?.scopeId || null : active.value?.runtimeScopeId || activeWorkspaceId.value)
  const storageScopeId = computed(() => profile.value === 'PERSONAL' ? scopeId.value : activeWorkspaceId.value)
  const scopeInstanceId = computed(() => profile.value === 'PERSONAL' ? personal.value?.instanceId || null : instanceId.value)
  const scopeLifecycle = computed(() => profile.value === 'PERSONAL' ? personal.value?.state || 'CLOSED' : lifecycle.value)
  let personalOpening: Promise<void> | null = null
  const lifecycle = ref<WorkspaceLifecycle>('CLOSED')
  const epoch = ref(0)
  const switching = ref(false)
  const upgrading = ref(false)
  const error = ref<string | null>(null)
  const previousWorkspaceId = ref<string | null>(null)
  const failedWorkspaceId = ref<string | null>(null)
  const openRequest = ref<{ id: string | null; sequence: number } | null>(null)
  let restoreAttempted = false
  let lifecycleEvents: EventSource | null = null
  let lifecyclePoll: ReturnType<typeof setInterval> | null = null
  let bootstrapPromise: Promise<void> | null = null
  let bootstrapped = false
  const initializing = ref(true)
  const startupProfile = ref<'PERSONAL' | 'PROJECT' | null>(null)
  let restorePromise: Promise<SupervisorWorkspace | null> | null = null
  // A user-triggered switch is one atomic UI transaction even though the host legitimately
  // passes through CLOSED between the old JVM and the target JVM. Polling must not turn that
  // internal phase into the public first-run/explicit-close empty workspace surface.
  let localTransitionInFlight = false

  const active = computed(() =>
    workspaces.value.find((workspace) => workspace.id === activeWorkspaceId.value) || null,
  )
  const projectReady = computed(() => lifecycle.value === 'READY' && Boolean(active.value && instanceId.value))
  const resourceReady = computed(() => scopeLifecycle.value === 'READY' && Boolean(scopeId.value && scopeInstanceId.value))
  const ready = computed(() => resourceReady.value && !switching.value)
  // Startup presentation follows the host's restore target, independently of personal Runtime warmup.
  const loadingProfile = computed(() => initializing.value ? startupProfile.value : profile.value)
  const loadingPhase = computed(() => loadingProfile.value === 'PROJECT' ? lifecycle.value : personal.value?.state || 'OPENING')

  function invalidate(clearProjectInstance = true) {
    epoch.value++
    if (clearProjectInstance) instanceId.value = null
    setApiIdentity(null)
  }

  function publishIdentity() {
    if (!resourceReady.value || !scopeId.value || !scopeInstanceId.value) { setApiIdentity(null); return }
    setRemoteRuntime(profile.value === 'PROJECT' && Boolean(active.value?.remoteProfile))
    if (supervisor.native && profile.value === 'PERSONAL' && personal.value?.endpoint) setRuntimeApiBase(personal.value.endpoint)
    setApiIdentity({ scopeId: scopeId.value, instanceId: scopeInstanceId.value })
  }

  function syncSwitching() {
    const hostTransitioning = lifecycle.value === 'DRAINING'
      || lifecycle.value === 'CLOSING'
      || lifecycle.value === 'OPENING'
    switching.value = profile.value === 'PERSONAL' ? Boolean(personalOpening) : localTransitionInFlight || hostTransitioning
  }

  function applyList(result: Awaited<ReturnType<typeof supervisor.list>>) {
    const replacement = profile.value === 'PERSONAL'
      ? personal.value?.instanceId && result.personal?.instanceId && personal.value.instanceId !== result.personal.instanceId
      : instanceId.value && result.instanceId && (instanceId.value !== result.instanceId || activeWorkspaceId.value !== result.activeWorkspaceId)
    if (replacement) invalidate(false)
    workspaces.value = result.workspaces
    fixedWorkspaceId.value = result.fixedWorkspaceId || null
    if (result.personal) personal.value = result.personal
    if (profile.value === 'PERSONAL' && result.personal?.state === 'FAILED') {
      error.value = result.personal.failureMessage || t('workspace.personalOpenFailureHint')
    }
    const internalClosedPhase = localTransitionInFlight && activeWorkspaceId.value !== null && result.lifecycle === 'CLOSED'
    // CLOSED is observable internally between two JVMs, but it is not an application-level
    // "no workspace" state during an atomic switch. Preserve the last stable sidebar identity
    // until OPENING publishes the target instead of flashing the first-run workbench.
    if (!internalClosedPhase) activeWorkspaceId.value = result.activeWorkspaceId
    lastWorkspaceId.value = result.lastWorkspaceId
    lifecycle.value = internalClosedPhase ? 'CLOSING' : result.lifecycle
    if (result.lifecycle === 'FAILED' && profile.value === 'PROJECT') error.value = result.failureMessage || error.value || t('workspace.openFailureHint')
    instanceId.value = result.instanceId
    syncSwitching()
    if (result.activeWorkspaceId && result.instanceId && result.lifecycle === 'READY') {
      failedWorkspaceId.value = null
    }
    publishIdentity()
  }

  function connectLifecycle() {
    if (supervisor.native) {
      if (!lifecyclePoll) {
        lifecyclePoll = setInterval(() => {
          void refresh().catch(() => { /* the native host may be between JVM instances */ })
        }, 750)
      }
      return
    }
    if (lifecycleEvents || typeof EventSource === 'undefined') return
    lifecycleEvents = new EventSource('/supervisor/api/events')
    lifecycleEvents.addEventListener('workspace_lifecycle', (raw) => {
      try {
        const envelope = JSON.parse((raw as MessageEvent).data || '{}')
        const data = envelope?.data || {}
        const phase = data.phase as WorkspaceLifecycle
        if (!['CLOSED', 'DRAINING', 'CLOSING', 'OPENING', 'READY', 'FAILED'].includes(phase)) return

        lifecycle.value = localTransitionInFlight && phase === 'CLOSED' ? 'CLOSING' : phase
        if (phase === 'DRAINING' || phase === 'CLOSING' || phase === 'OPENING') {
          if (profile.value === 'PROJECT') invalidate()
        }
        if (phase === 'OPENING' && data.workspaceId) {
          activeWorkspaceId.value = String(data.workspaceId)
        }
        if (data.previousWorkspaceId) {
          previousWorkspaceId.value = String(data.previousWorkspaceId)
        }
        if (phase === 'READY') {
          failedWorkspaceId.value = null
          activeWorkspaceId.value = data.workspaceId ? String(data.workspaceId) : activeWorkspaceId.value
          instanceId.value = data.instanceId ? String(data.instanceId) : null
          error.value = null
          publishIdentity()
          void refresh().catch(() => { /* the next polling refresh reconciles the list */ })
        } else if (phase === 'FAILED') {
          failedWorkspaceId.value = data.targetWorkspaceId || data.workspaceId || null
          instanceId.value = null
          if (profile.value === 'PROJECT') {
            error.value = String(data.message || t('workspace.openFailureHint'))
            setApiIdentity(null)
          }
        }
        syncSwitching()
      } catch {
        // Ignore malformed control events and retain the last stable projection.
      }
    })
    lifecycleEvents.onerror = () => {
      // Native EventSource reconnects automatically; polling is the fallback projection.
    }
    lifecycleEvents.addEventListener('personal_lifecycle', raw => {
      try {
        const next = JSON.parse((raw as MessageEvent).data).data as PersonalScope
        if (profile.value === 'PERSONAL' && personal.value?.instanceId !== next.instanceId) invalidate(false)
        personal.value = next
        if (profile.value === 'PERSONAL') {
          error.value = next.failureMessage || null
          publishIdentity(); syncSwitching()
        }
      } catch { /* retain the last verified host projection */ }
    })
  }

  async function bootstrap() {
    if (bootstrapped) return
    if (bootstrapPromise) return bootstrapPromise
    bootstrapPromise = (async () => {
      available.value = await supervisor.available()
      if (!available.value) {
        // Direct Runtime and IDE embedded hosts own exactly one Runtime.
        // Project the Runtime identity into the same store so the normal sidebar,
        // thread list and file explorer remain fully usable without inventing a
        // second workspace registry or exposing a switch action the host cannot do.
        const scope = await api.scope()
        startupProfile.value = scope.runtimeProfile === 'PERSONAL' ? 'PERSONAL' : 'PROJECT'
        if (scope.runtimeProfile === 'PERSONAL') {
          personal.value = { ...scope, runtimeProfile: 'PERSONAL', name: 'Personal', state: 'READY' }
          profile.value = 'PERSONAL'
          publishIdentity()
          return
        }
        const fixed = await api.workspace()
        const projected: SupervisorWorkspace = {
          id: fixed.workspaceId,
          name: fixed.name,
          root: fixed.root,
          state: 'READY',
          instanceId: fixed.instanceId,
          lastOpened: null,
          createdAt: '',
          isActive: true,
        }
        workspaces.value = [projected]
        activeWorkspaceId.value = projected.id
        lastWorkspaceId.value = projected.id
        instanceId.value = projected.instanceId
        lifecycle.value = 'READY'
        switching.value = false
        error.value = null
        setApiIdentity({ scopeId: projected.runtimeScopeId || projected.id, instanceId: projected.instanceId! })
        return
      }
      const requestEpoch = epoch.value
      const result = await supervisor.list()
      startupProfile.value = result.activeWorkspaceId || result.lastWorkspaceId ? 'PROJECT' : 'PERSONAL'
      if (requestEpoch === epoch.value) applyList(result)
      connectLifecycle()
      let lastPersonal = true
      try { lastPersonal = localStorage.getItem('harnex:selected-space') !== 'PROJECT' } catch {}
      const hasProject = Boolean(result.activeWorkspaceId || result.lastWorkspaceId)
      if (fixedWorkspaceId.value) startupProfile.value = lastPersonal ? 'PERSONAL' : 'PROJECT'
      if (lastPersonal || !hasProject && result.lifecycle === 'CLOSED') await openPersonal()
      else if (fixedWorkspaceId.value) await open(fixedWorkspaceId.value)
    })()
    try {
      await bootstrapPromise
      bootstrapped = true
    } finally {
      initializing.value = false
      bootstrapPromise = null
    }
  }

  async function refresh() {
    if (!available.value) return
    const requestEpoch = epoch.value
    const result = await supervisor.list()
    // A list request started before STOP/OPEN must not repaint the replacement JVM
    // with the old workspace projection when its response arrives late.
    if (requestEpoch === epoch.value) applyList(result)
  }

  async function open(id: string, stopActiveRun = false) {
    if (switching.value) return
    if (id === activeWorkspaceId.value && projectReady.value) {
      error.value = null
      if (profile.value !== 'PROJECT') {
        switching.value = true
        localTransitionInFlight = true
        invalidate(false); profile.value = 'PROJECT'
        try { await supervisor.activateProjectEndpoint(); publishIdentity() }
        catch (cause) { lifecycle.value = 'FAILED'; error.value = errorMessage(cause); throw cause }
        finally { localTransitionInFlight = false; syncSwitching() }
      }
      try { localStorage.setItem('harnex:selected-space', 'PROJECT') } catch {}
      return
    }
    profile.value = 'PROJECT'
    const expectedInstanceId = instanceId.value
    if (activeWorkspaceId.value && activeWorkspaceId.value !== id) {
      previousWorkspaceId.value = activeWorkspaceId.value
    }
    switching.value = true
    localTransitionInFlight = true
    syncSwitching()
    error.value = null
    invalidate()
    lifecycle.value = activeWorkspaceId.value ? 'DRAINING' : 'OPENING'
    try {
      const opened = await supervisor.switchTo(id, { expectedInstanceId, stopActiveRun })
      await supervisor.activateProjectEndpoint()
      failedWorkspaceId.value = null
      error.value = null
      setRemoteRuntime(Boolean(opened.remoteProfile))
      activeWorkspaceId.value = opened.id
      lastWorkspaceId.value = opened.id
      instanceId.value = opened.instanceId
      lifecycle.value = opened.state
      workspaces.value = workspaces.value.map((item) =>
        item.id === opened.id ? opened : { ...item, isActive: false, state: 'CLOSED', instanceId: null },
      )
      if (opened.instanceId) setApiIdentity({ scopeId: opened.runtimeScopeId || opened.id, instanceId: opened.instanceId })
      try { localStorage.setItem('harnex:selected-space', 'PROJECT') } catch {}
    } catch (cause: unknown) {
      failedWorkspaceId.value = id
      if (cause instanceof SupervisorApiError && cause.status === 409) {
        await refresh().catch(() => {})
      } else {
        lifecycle.value = 'FAILED'
      }
      error.value = errorMessage(cause)
      throw cause
    } finally {
      localTransitionInFlight = false
      syncSwitching()
    }
  }

  async function openPersonal(force = false, restartIfCurrent = false) {
    if (personalOpening) return personalOpening
    if (profile.value === 'PERSONAL' && ready.value && !force) return
    if (!available.value) return
    invalidate(false)
    profile.value = 'PERSONAL'
    error.value = null
    if (personal.value?.state === 'READY' && personal.value.instanceId && !force && !restartIfCurrent) {
      publishIdentity()
      syncSwitching()
      try { localStorage.setItem('harnex:selected-space', 'PERSONAL') } catch {}
      return
    }
    switching.value = true
    if (personal.value) personal.value = { ...personal.value, state: 'OPENING' }
    const operation = (async () => {
      try {
        personal.value = await supervisor.personal(restartIfCurrent)
        publishIdentity()
        try { localStorage.setItem('harnex:selected-space', 'PERSONAL') } catch {}
      } catch (cause) {
        if (personal.value) personal.value = { ...personal.value, state: 'FAILED', instanceId: null }
        error.value = errorMessage(cause); throw cause
      }
      finally { personalOpening = null; syncSwitching() }
    })()
    personalOpening = operation
    return operation
  }

  /**
   * Restore the application entry state without introducing a second workspace launcher.
   *
   * Local host restoration may already be in progress. SSH restoration opens the
   * authentication chooser first. Attempt automatic restoration only once per app
   * session; a failed project must leave the workbench available for recovery.
   */
  async function restoreMostRecent(): Promise<SupervisorWorkspace | null> {
    if (!available.value) return null
    if (profile.value === 'PERSONAL') return null
    if (ready.value) return active.value
    if (restorePromise) return restorePromise
    if (restoreAttempted || lifecycle.value === 'FAILED') return null
    restoreAttempted = true
    restorePromise = (async () => {
      if (switching.value) return null
      const candidateId = lastWorkspaceId.value
        || [...workspaces.value]
          .sort((left, right) => String(right.lastOpened || right.createdAt)
            .localeCompare(String(left.lastOpened || left.createdAt)))[0]?.id
      if (!candidateId) return null
      if (workspaces.value.find(item => item.id === candidateId)?.remoteProfile) {
        requestOpen(candidateId)
        return null
      }
      await open(candidateId)
      return ready.value ? active.value : null
    })()
    try {
      return await restorePromise
    } finally {
      restorePromise = null
    }
  }

  function requestOpen(id: string | null = null) {
    openRequest.value = {id, sequence:(openRequest.value?.sequence || 0) + 1}
  }

  async function close(stopRemote = false) {
    if (switching.value) return
    const expectedInstanceId = instanceId.value
    switching.value = true
    localTransitionInFlight = true
    syncSwitching()
    invalidate()
    lifecycle.value = 'DRAINING'
    try {
      await supervisor.close({ expectedInstanceId, stopActiveRun: true, stopRemote })
      setRemoteRuntime(false)
      activeWorkspaceId.value = null
      lifecycle.value = 'CLOSED'
      workspaces.value = workspaces.value.map((item) => ({
        ...item, isActive: false, state: 'CLOSED', instanceId: null,
      }))
      await openPersonal()
      await refresh()
    } catch (cause) {
      await refresh().catch(() => { lifecycle.value = 'FAILED' })
      error.value = errorMessage(cause)
      throw cause
    } finally {
      localTransitionInFlight = false
      syncSwitching()
    }
  }

  async function restart(upgradeRemote = false) {
    if (profile.value === 'PERSONAL') return openPersonal(true, true)
    if (!activeWorkspaceId.value || switching.value) return
    const id = activeWorkspaceId.value
    const expectedInstanceId = instanceId.value
    switching.value = true
    localTransitionInFlight = true
    syncSwitching()
    error.value = null
    upgrading.value = upgradeRemote
    invalidate()
    lifecycle.value = 'DRAINING'
    try {
      const opened = await supervisor.switchTo(id, {
        expectedInstanceId,
        ...(upgradeRemote ? {upgradeRemote:true} : {stopActiveRun:true,restartIfCurrent:true}),
      })
      activeWorkspaceId.value = opened.id
      lastWorkspaceId.value = opened.id
      instanceId.value = opened.instanceId
      lifecycle.value = opened.state
      workspaces.value = workspaces.value.map(item =>
        item.id === opened.id ? opened : { ...item, isActive: false, state: 'CLOSED', instanceId: null },
      )
      if (opened.instanceId) setApiIdentity({ scopeId: opened.runtimeScopeId || opened.id, instanceId: opened.instanceId })
    } catch (cause: unknown) {
      if (upgradeRemote) await refresh().catch(() => { lifecycle.value = 'FAILED' })
      else lifecycle.value = 'FAILED'
      error.value = errorMessage(cause)
      throw cause
    } finally {
      upgrading.value = false
      localTransitionInFlight = false
      syncSwitching()
    }
  }

  async function register(root: string, name?: string) {
    const workspace = await supervisor.register(root, name)
    if (!workspaces.value.some((item) => item.id === workspace.id)) workspaces.value.push(workspace)
    return workspace
  }

  async function chooseAndRegister() {
    if (!available.value || switching.value) return null
    const root = await supervisor.pickDirectory()
    if (!root) return null
    return register(root)
  }

  return {
    available, fixedWorkspaceId, canSelectProject, workspaces, activeWorkspaceId, lastWorkspaceId, instanceId, lifecycle, epoch, switching, upgrading,
    profile, personal, scopeId, storageScopeId, scopeInstanceId, scopeLifecycle, openPersonal,
    error, previousWorkspaceId, active, ready, resourceReady, loadingProfile, loadingPhase, invalidate, bootstrap, refresh, open, close, restart, register,
    chooseAndRegister, connectLifecycle, restoreMostRecent,
    openRequest, requestOpen, failedWorkspaceId,
  }
})
