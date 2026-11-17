import { defineStore } from 'pinia'
import { computed, ref, watch } from 'vue'
import { api } from '../api/client'
import type { EffectiveSkillDto } from '../api/contracts'
import { useWorkspaceStore } from './workspace'
import { errorMessage } from '../utils/unknown'

/**
 * Shared effective Skill projection for Settings and Composer. The Runtime is
 * authoritative for user/workspace merge, override and enabled state.
 */
export const useSkillStore = defineStore('skills', () => {
  const workspace = useWorkspaceStore()
  const items = ref<EffectiveSkillDto[]>([])
  const loading = ref(false)
  const loaded = ref(false)
  const error = ref<string | null>(null)
  let loadedIdentity = ''
  let requestSequence = 0

  const effective = computed(() =>
    items.value.filter(skill => skill.enabled && skill.effective),
  )

  function identityKey() {
    return workspace.instanceId
      ? `${workspace.activeWorkspaceId || ''}:${workspace.instanceId}`
      : `direct:${workspace.epoch}`
  }

  function invalidate() {
    requestSequence++
    items.value = []
    loaded.value = false
    loadedIdentity = ''
    loading.value = false
    error.value = null
  }

  async function load(force = false) {
    const identity = identityKey()
    if (!force && loaded.value && loadedIdentity === identity) return items.value
    const sequence = ++requestSequence
    loading.value = true
    error.value = null
    try {
      const result = await api.skills()
      if (sequence !== requestSequence || identity !== identityKey()) return items.value
      items.value = Array.isArray(result) ? result : []
      loadedIdentity = identity
      loaded.value = true
      return items.value
    } catch (cause: unknown) {
      if (sequence === requestSequence) error.value = errorMessage(cause)
      throw cause
    } finally {
      if (sequence === requestSequence) loading.value = false
    }
  }

  async function setEnabled(skill: EffectiveSkillDto, enabled: boolean) {
    await api.setSkillEnabled(skill.name, skill.source, enabled)
    return load(true)
  }

  async function toggle(skill: EffectiveSkillDto) {
    return setEnabled(skill, !skill.enabled)
  }

  async function rescan() {
    await api.rescanSkills()
    return load(true)
  }

  watch(
    () => [workspace.activeWorkspaceId, workspace.instanceId, workspace.epoch] as const,
    invalidate,
  )

  return { items, effective, loading, loaded, error, load, setEnabled, toggle, rescan, invalidate }
})
