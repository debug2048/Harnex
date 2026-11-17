import { computed, getCurrentScope, nextTick, onScopeDispose, ref, watch, type Ref } from 'vue'
import { storeToRefs } from 'pinia'
import { api, getRuntimeRevision } from '../api/client'
import type { CompletionItem } from '../components/chat/Composer.vue'
import { useSettings } from '../stores/settings'
import { useSkillStore } from '../stores/skills'
import { useRuntimeStore } from '../stores/runtime'
import { useRunStore } from '../stores/run'
import { useModelCatalogStore } from '../stores/modelCatalog'
import { useWorkspaceStore } from '../stores/workspace'
import { useConversationStore } from '../stores/conversation'
import type { ModelOption } from '../domain/modelCatalog'
import { t } from '../i18n'
export type { ModelOption } from '../domain/modelCatalog'

interface SlashCommand {
  name: string
  description: string
  argHint: string
  completion?: string
}

export function useComposerCatalog(input: Ref<string>, agentEnabled: Readonly<Ref<boolean>> = computed(() => false)) {
  const settings = useSettings()
  const skills = useSkillStore()
  const runtime = useRuntimeStore()
  const run = useRunStore()
  const slashCommands = ref<SlashCommand[]>([])
  const completionItems = ref<CompletionItem[]>([])
  const completionIndex = ref(0)
  const showCompletion = ref(false)
  const modelSelectionChange = ref<{ provider: string; previous: string; current: string } | null>(null)
  const modelSelectionNotice = computed(() => modelSelectionChange.value
    ? t('chat.modelSelectionUpdated', modelSelectionChange.value) : '')
  const catalogs = useModelCatalogStore()
  const workspace = useWorkspaceStore()
  const conversation = useConversationStore()
  const { models: modelCatalog, hasProviders: providerConfigured, loading: modelsLoading, error: modelsError } = storeToRefs(catalogs)

  // Clear before the new conversation restores its model, even when both use the same default.
  watch(() => [workspace.scopeId, workspace.scopeInstanceId, conversation.threadId], () => {
    modelSelectionChange.value = null
  }, { flush: 'sync' })

  function modelIdKey(id: string) {
    return id.trim().toLowerCase()
  }

  function sameModelId(left: string, right: string) {
    return modelIdKey(left) === modelIdKey(right)
  }

  function updateCompletion() {
    const value = input.value
    if (!agentEnabled.value || !value.startsWith('/') || value.includes('\n')) {
      showCompletion.value = false
      completionItems.value = []
      return
    }
    const afterSlash = value.slice(1)
    const spaceIndex = afterSlash.indexOf(' ')
    if (spaceIndex < 0) {
      const prefix = afterSlash.toLowerCase()
      completionItems.value = slashCommands.value
        .filter(command => command.name.startsWith(prefix))
        .map(command => ({
          label: `/${command.name}`,
          insert: `/${command.name} `,
          hint: command.argHint || command.description,
        }))
    } else {
      const commandName = afterSlash.slice(0, spaceIndex).toLowerCase()
      const argument = afterSlash.slice(spaceIndex + 1)
      const command = slashCommands.value.find(item => item.name === commandName)
      completionItems.value = command?.completion === 'skills' && !argument.includes(' ')
        ? skills.effective
          .filter(skill => skill.name.toLowerCase().startsWith(argument.toLowerCase()))
          .map(skill => ({
            label: skill.name,
            insert: `/${commandName} ${skill.name} `,
            hint: skill.description,
          }))
        : []
    }
    completionIndex.value = 0
    showCompletion.value = completionItems.value.length > 0
  }

  function applyCompletion(item: CompletionItem) {
    if (!agentEnabled.value) return
    input.value = item.insert
    showCompletion.value = false
    void nextTick(updateCompletion)
  }

  let agentRequest = 0, disposed = false
  let agentPending: { revision: number; promise: Promise<void> } | null = null
  async function load(force = false) {
    await Promise.all([catalogs.load(force), agentEnabled.value ? loadAgentCatalog() : Promise.resolve()])
    if (!disposed) reconcileReasoningEffort()
  }
  async function loadAgentCatalog() {
    if (!agentEnabled.value || !workspace.ready || disposed) return
    const revision = getRuntimeRevision()
    if (agentPending?.revision === revision) return agentPending.promise
    const request = ++agentRequest
    const current = () => !disposed && request === agentRequest && revision === getRuntimeRevision() && agentEnabled.value
    const promise = (async () => {
      const commands = await api.commands().catch(() => null)
      if (!current()) return
      if (commands) slashCommands.value = commands as SlashCommand[]
      await skills.load().catch(() => {})
    })()
    const operation = { revision, promise }; agentPending = operation
    try { await promise } finally { if (agentPending === operation) agentPending = null }
  }
  watch(agentEnabled, enabled => {
    agentRequest++; agentPending = null
    showCompletion.value = false; completionItems.value = []
    slashCommands.value = []
    if (enabled) void loadAgentCatalog()
  })
  if (getCurrentScope()) onScopeDispose(() => { disposed = true; agentRequest++ })

  const activeModel = computed(() => {
    const id = run.executionFacts.actualModel
      || run.tokenUsage?.model
      || String(runtime.statusline?.model || '')
    const provider = run.executionFacts.actualProvider
      || run.tokenUsage?.provider
      || String(runtime.statusline?.provider || '')
      || String(runtime.statusline?.providers?.[0] || '')
    if (!id) return undefined
    return modelCatalog.value.find(model => sameModelId(model.id, id)
      && (!provider || model.provider.toLowerCase() === provider.toLowerCase()))
      || modelCatalog.value.find(model => sameModelId(model.id, id))
  })
  const groupedCatalog = computed(() => {
    const groups = new Map<string, ModelOption[]>()
    for (const model of modelCatalog.value) {
      if (!groups.has(model.provider)) groups.set(model.provider, [])
      groups.get(model.provider)!.push(model)
    }
    return [...groups.entries()].map(([provider, models]) => ({ provider, models }))
  })

  const selectedModelRef = computed(() => {
    if (!settings.model || !settings.provider) return ''
    return modelCatalog.value.find(model => model.provider.toLowerCase() === settings.provider.toLowerCase()
      && sameModelId(model.id, settings.model))?.ref || `${settings.provider}:${settings.model}`
  })
  const selectedCatalogModel = computed(() => {
    if (selectedModelRef.value) {
      return modelCatalog.value.find(model => model.ref === selectedModelRef.value)
    }
    const defaultProvider = String(runtime.statusline?.provider || runtime.statusline?.providers?.[0] || '')
    const defaultModel = String(runtime.statusline?.model || '')
    const routedDefault = modelCatalog.value.find(model => sameModelId(model.id, defaultModel)
      && model.provider.toLowerCase() === defaultProvider.toLowerCase())
    if (routedDefault) return routedDefault
    return modelCatalog.value.find(model => model.default) || modelCatalog.value[0]
  })
  const reasoningEffortOptions = computed(() => {
    const model = selectedCatalogModel.value
    if (!model?.reasoningEfforts.length) return []
    const toolRequired = agentEnabled.value ? model.functionToolReasoningEffort : undefined
    return toolRequired && model.reasoningEfforts.includes(toolRequired)
      ? [toolRequired]
      : model.reasoningEfforts
  })

  function reconcileReasoningEffort() {
    const current = settings.reasoningEffort
    if (current !== 'auto' && !reasoningEffortOptions.value.includes(current)) {
      settings.reasoningEffort = 'auto'
    } else if (current !== 'auto' && (!settings.model || !settings.provider) && selectedCatalogModel.value) {
      // An explicit Level is only valid for one exact provider/model capability contract.
      // Pin the routed default so health-based routing cannot send it to a different adapter.
      settings.model = selectedCatalogModel.value.id
      settings.provider = selectedCatalogModel.value.provider
    }
  }

  function updateModel(modelRef: string) {
    modelSelectionChange.value = null
    if (!modelRef) {
      settings.model = ''
      settings.provider = ''
      reconcileReasoningEffort()
      return
    }
    const selected = modelCatalog.value.find(model => model.ref === modelRef)
    if (!selected) return
    settings.model = selected.id
    settings.provider = selected.provider
    reconcileReasoningEffort()
  }

  function updateReasoningEffort(effort: string) {
    settings.reasoningEffort = effort || 'auto'
    reconcileReasoningEffort()
  }

  watch(() => [catalogs.ready, modelsLoading.value, modelsError.value, modelCatalog.value, settings.provider, settings.model], () => {
    const change = modelSelectionChange.value
    if (change && (change.provider.toLowerCase() !== settings.provider.toLowerCase()
      || !sameModelId(change.current, settings.model))) modelSelectionChange.value = null
    if (!catalogs.ready || modelsLoading.value || modelsError.value || !settings.provider || !settings.model) return
    const choices = modelCatalog.value.filter(model => model.provider.toLowerCase() === settings.provider.toLowerCase())
    if (!choices.some(model => sameModelId(model.id, settings.model))) {
      const replacement = choices.find(model => model.default) || (choices.length === 1 ? choices[0] : undefined)
      if (replacement) {
        modelSelectionChange.value = { provider: replacement.provider, previous: settings.model, current: replacement.id }
        settings.model = replacement.id
        settings.reasoningEffort = 'auto'
      }
    }
    // Removed/disabled providers still require an explicit choice; never switch to another service here.
    reconcileReasoningEffort()
  }, { immediate: true })

  function tierBadge(tier?: string): { label: string; cls: string } | null {
    if (!tier) return null
    const key = tier.toLowerCase()
    if (key === 'high') return { label: 'high', cls: 'tier-high' }
    if (key === 'medium' || key === 'mid') return { label: 'medium', cls: 'tier-medium' }
    if (key === 'low') return { label: 'low', cls: 'tier-low' }
    return { label: tier, cls: 'tier-other' }
  }

  return {
    completionItems, completionIndex, showCompletion, modelCatalog,
    activeModel, groupedCatalog, providerConfigured, modelsLoading, modelsError, selectedModelRef,
    reasoningEffortOptions,
    modelSelectionNotice, dismissModelSelectionNotice: () => { modelSelectionChange.value = null },
    statusline: computed(() => runtime.statusline),
    updateCompletion, applyCompletion, load, updateModel, updateReasoningEffort, tierBadge,
  }
}
