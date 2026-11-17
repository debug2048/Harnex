import { defineStore } from 'pinia'
import { computed, ref, onScopeDispose } from 'vue'
import { api, deleteNativeProviderSecrets, protectNativeProviderSecrets, getRuntimeRevision, onRuntimeIdentityChanged } from '../api/client'
import type { ConfigDocument } from '../api/contracts'
import { errorMessage } from '../utils/unknown'
import { t } from '../i18n'
import { configuredSecretIds } from '../domain/imageSettings'
import { notifyConfigurationChanged } from '../domain/configEvents'
import { configIssue, configValueAt } from '../domain/configValidation'

function clone<T>(value: T): T {
  return JSON.parse(JSON.stringify(value ?? {}))
}

export const useConfigStore = defineStore('config', () => {
  const document = ref<ConfigDocument | null>(null)
  const draft = ref<Record<string, unknown>>({})
  const loading = ref(false)
  const dirty = ref(false)
  const dirtySections = ref<string[]>([])
  const errors = ref<string[]>([])
  const issues = computed(() => errors.value.map(configIssue))
  const message = ref('')
  const pendingSecretDeletions = new Set<string>()
  const pendingCredentialChanges = new Set<string>()
  let loadedRevision = -1
  let loadSequence = 0
  const unsubscribe = onRuntimeIdentityChanged(() => {
    loadedRevision = -1; loadSequence++
    document.value = null; draft.value = {}; dirty.value = false; dirtySections.value = []
    errors.value = []; message.value = ''; pendingSecretDeletions.clear(); pendingCredentialChanges.clear(); loading.value = false
  })
  onScopeDispose(unsubscribe)

  function assertScope(revision: number) {
    if (revision !== loadedRevision || revision !== getRuntimeRevision()) throw new Error('Workspace changed; reload its configuration before saving')
  }

  const effective = computed(() => document.value?.effective || {})
  const revision = computed(() => document.value?.revision || '')
  const restartRequired = computed(() => !!document.value && (document.value.restartRequired ?? document.value.reloadPolicy !== 'live'))
  const reloadError = computed(() => document.value?.reloadError || '')

  function savedFeedback() {
    if (reloadError.value) {
      errors.value = [t('settings.reloadFailed')]
      message.value = t('settings.savedNotApplied')
      return false
    }
    message.value = t(restartRequired.value
      ? document.value?.reloadPolicy === 'mixed'
        ? 'settings.savedMixed' : 'settings.savedReopen'
      : 'settings.savedLive')
    return true
  }

  async function load() {
    const revision = getRuntimeRevision()
    const sequence = ++loadSequence
    loading.value = true
    errors.value = []
    message.value = ''
    try {
      const next = await api.config()
      if (revision !== getRuntimeRevision() || sequence !== loadSequence) return
      loadedRevision = revision
      document.value = next
      draft.value = clone(next.values)
      dirty.value = false
      dirtySections.value = []
    } finally {
      if (sequence === loadSequence) loading.value = false
    }
  }

  function touch(section?: string) {
    dirty.value = true
    if (section && !dirtySections.value.includes(section)) {
      dirtySections.value = [...dirtySections.value, section]
    }
    message.value = ''
  }

  function setSection(section: string, value: unknown) {
    const next = { ...draft.value, [section]: value }
    errors.value = errors.value.filter(error => {
      const path = configIssue(error).path
      return !path || JSON.stringify(configValueAt(draft.value, path)) === JSON.stringify(configValueAt(next, path))
    })
    draft.value = next
    touch(section)
  }

  function isDirtySection(...sections: string[]) {
    return sections.some(section => dirtySections.value.includes(section))
  }

  function clearFeedback() {
    errors.value = []
    message.value = ''
  }

  async function validate() {
    const scope = loadedRevision
    const result = await api.validateConfig(draft.value)
    if (scope !== loadedRevision || scope !== getRuntimeRevision()) return false
    errors.value = result.errors || []
    return result.valid === true
  }

  async function save() {
    if (!document.value) return false
    const scope = loadedRevision
    loading.value = true
    message.value = ''
    try {
      if (!await validate()) return false
      assertScope(scope)
      const previousIds = configuredSecretIds(document.value.values || {})
      const nextIds = configuredSecretIds(draft.value)
      const removedProviderIds = previousIds.filter(id => !nextIds.includes(id))
      const protectedDraft = await protectNativeProviderSecrets(draft.value, reference => pendingCredentialChanges.add(reference))
      assertScope(scope)
      draft.value = protectedDraft
      await api.saveConfig(document.value.revision, draft.value, [...pendingCredentialChanges])
      assertScope(scope)
      pendingCredentialChanges.clear()
      removedProviderIds.forEach(id => pendingSecretDeletions.add(id))
      nextIds.forEach(id => pendingSecretDeletions.delete(id))
      await load()
      assertScope(scope)
      await deleteNativeProviderSecrets([...pendingSecretDeletions])
      pendingSecretDeletions.clear()
      notifyConfigurationChanged()
      return savedFeedback()
    } catch (cause: unknown) {
      errors.value = [errorMessage(cause)]
      return false
    } finally {
      loading.value = false
    }
  }

  async function applySavedConfiguration() {
    if (dirty.value || loading.value || !document.value) return false
    const scope = loadedRevision
    loading.value = true
    clearFeedback()
    try {
      await api.configReload()
      assertScope(scope)
      await load()
      assertScope(scope)
      notifyConfigurationChanged()
      return savedFeedback()
    } catch (cause: unknown) {
      errors.value = [errorMessage(cause)]
      return false
    } finally {
      loading.value = false
    }
  }

  function resetDraft() {
    draft.value = clone(document.value?.values || {})
    dirty.value = false
    dirtySections.value = []
    errors.value = []
    message.value = ''
  }

  return {
    document, draft, effective, revision, loading, dirty, dirtySections, errors, issues, message, restartRequired, reloadError,
    load, touch, setSection, isDirtySection, clearFeedback, validate, save, applySavedConfiguration, resetDraft,
  }
})
