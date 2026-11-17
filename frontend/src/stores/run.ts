import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import {
  emptyExecutionFacts,
  reduceExecutionFacts,
  type ExecutionEnvelope,
  type ExecutionFacts,
} from '../domain/executionFacts'
import type { GoalView, RunInputView, RunView } from '../api/contracts'
import type { GoalProgress, ToolEvent } from '../domain/chat'

export const useRunStore = defineStore('run', () => {
  const activeRunId = ref<string | null>(null)
  const currentRun = ref<RunView | null>(null)
  const imageOperationIds = ref<string[]>([])
  const resourceRevision = ref(0)
  const executionFacts = ref<ExecutionFacts>(emptyExecutionFacts())
  const streaming = ref(false)
  const currentAssistant = ref('')
  const currentThinking = ref('')
  const toolEvents = ref<Record<string, ToolEvent>>({})
  const todoRevision = ref(0)
  const error = ref<string | null>(null)
  const incompleteDetail = ref<string | null>(null)
  const goalProgress = ref<GoalProgress | null>(null)
  const latestSnapshotSeq = ref<number | null>(null)
  const runInputs = ref<RunInputView[]>([])
  // Transport replay cursor is deliberately separate from durable facts.lastSeq: hydrating a Run GET projection
  // must not suppress replayed text/tool frames needed to rebuild an in-progress transcript.
  const appliedRunId = ref<string | null>(null)
  const appliedSeq = ref(0)
  const tokenUsage = computed(() => {
    const facts = executionFacts.value
    if (!facts.runId && !facts.actualProvider && !facts.actualModel && facts.usage.total === 0) return null
    return {
      prompt: facts.usage.prompt,
      completion: facts.usage.completion,
      total: facts.usage.total,
      cachedInput: facts.usage.cachedInput,
      cacheWriteInput: facts.usage.cacheWriteInput,
      reasoning: facts.usage.reasoning,
      cumulative: facts.usage.total,
      model: facts.actualModel,
      provider: facts.actualProvider,
      complete: facts.usageComplete,
    }
  })

  function begin(requestedProvider?: string, requestedModel?: string, runId?: string) {
    incompleteDetail.value = null
    activeRunId.value = runId || null
    executionFacts.value = {
      ...emptyExecutionFacts({ requestedProvider, requestedModel }),
      runId,
    }
    appliedRunId.value = runId || null
    appliedSeq.value = 0
    runInputs.value = []
  }

  function apply(type: string, data: Record<string, unknown>, envelope: ExecutionEnvelope = {}): boolean {
    const incomingRunId = envelope.runId || activeRunId.value
    if (incomingRunId && appliedRunId.value !== incomingRunId) {
      appliedRunId.value = incomingRunId
      appliedSeq.value = 0
    }
    if (typeof envelope.seq === 'number') {
      if (envelope.seq <= appliedSeq.value) return false
      appliedSeq.value = envelope.seq
    }
    const before = executionFacts.value
    const next = reduceExecutionFacts(before, type, data, envelope)
    if (next !== before) executionFacts.value = next
    if (next.runId) activeRunId.value = next.runId
    if (currentRun.value && ['done', 'goal_state_changed', 'goal_review_invalidated'].includes(type)
        && typeof data.status === 'string') {
      const status = data.status as RunView['status']
      const terminal = ['COMPLETED', 'INCOMPLETE', 'FAILED', 'CANCELLED', 'EXPIRED'].includes(status)
      currentRun.value = { ...currentRun.value, status, terminal, ownsConversation: !terminal,
        executing: ['RUNNING', 'PAUSE_REQUESTED'].includes(status),
        ...(type === 'done' ? { errorCode: typeof data.errorCode === 'string' ? data.errorCode : undefined,
          error: typeof data.message === 'string' ? data.message : undefined } : {}),
        revision: Number(data.revision || currentRun.value.revision), allowedActions: terminal ? [] : currentRun.value.allowedActions }
    }
    return true
  }

  /** Hydrate durable run facts before replaying the live event buffer after a reconnect. */
  function hydrate(view: RunView, resetReplayCursor = false) {
    if (!view?.runId) return
    currentRun.value = view
    if (!resetReplayCursor && executionFacts.value.runId === view.runId
        && executionFacts.value.lastSeq > Number(view.lastSeq || 0)) {
      return
    }
    if (resetReplayCursor) {
      appliedRunId.value = view.runId
      appliedSeq.value = Number(view.lastSeq || 0)
    }
    incompleteDetail.value = view.status === 'INCOMPLETE' ? (view.result?.detail || view.error || view.result?.reason || 'INCOMPLETE') : null
    activeRunId.value = view.runId
    executionFacts.value = {
      runId: view.runId,
      requestedProvider: view.requestedProvider || undefined,
      requestedModel: view.requestedModel || undefined,
      actualProvider: view.actualProvider || undefined,
      actualModel: view.actualModel || undefined,
      attempts: (view.providerAttempts || []).filter(attempt => !attempt.subagentThreadId).map(attempt => ({
        provider: attempt.provider || undefined,
        model: attempt.model || undefined,
        failover: attempt.outcome === 'failed' || Boolean(attempt.reason),
        reason: attempt.reason || undefined,
        attempts: Number.isFinite(Number(attempt.attempts)) ? Number(attempt.attempts) : undefined,
        outcome: attempt.outcome || undefined,
        circuitState: attempt.circuitState || undefined,
      })),
      usage: {
        prompt: Number(view.usage?.prompt || 0),
        completion: Number(view.usage?.completion || 0),
        total: Number(view.usage?.total || 0),
        cachedInput: Number(view.usage?.cachedInput || 0),
        cacheWriteInput: Number(view.usage?.cacheWriteInput || 0),
        reasoning: Number(view.usage?.reasoning || 0),
      },
      usageComplete: view.usage?.complete === true,
      lastSeq: Number(view.lastSeq || 0),
    }
  }

  function hydrateGoal(goal: GoalView, run?: RunView | null) {
    const activeRun = Boolean(goal.activeRunId && run?.runId === goal.activeRunId)
    const matchingTerminalRun = Boolean(!activeRun && run?.runId === goal.latestRunId
      && goal.latestRunStatus && goal.status === goal.latestRunStatus
      && ['COMPLETED', 'INCOMPLETE', 'FAILED', 'CANCELLED', 'EXPIRED'].includes(goal.status))
    const currentRun = activeRun || matchingTerminalRun ? run : null
    const execution = currentRun?.goalExecution
    const status = activeRun
      ? (currentRun?.cancelRequested ? 'CANCEL_REQUESTED' : currentRun?.status || goal.status)
      : (goal.status || 'CREATED')
    const results = new Map((execution?.criteriaResults || []).map(result => [result.criterionId, result]))
    goalProgress.value = {
      goalId: goal.goalId,
      runId: activeRun ? currentRun?.runId : undefined,
      runRevision: activeRun ? (currentRun?.revision ?? goal.activeRunRevision) : undefined,
      objective: goal.objective,
      turnsUsed: Number(execution?.turnsUsed || 0),
      maxTurns: Number(goal.budget?.maxGoalTurns || 0),
      modelCalls: Number(execution?.modelCalls || 0),
      maxModelCalls: Number(goal.budget?.maxModelCalls || 0),
      toolCalls: Number(execution?.toolCalls || 0),
      maxToolCalls: Number(goal.budget?.maxToolCalls || 0),
      tokensUsed: Number(currentRun?.usage?.total || 0),
      tokenBudget: Number(goal.budget?.tokenBudget || 0),
      costUsed: execution?.cost?.known ? Number(execution.cost.amount || 0) : undefined,
      costBudget: Number(goal.budget?.costBudget || 0),
      costKnown: execution?.cost?.known === true,
      criteria: (goal.criteria || []).map(criterion => {
        const result = results.get(criterion.criterionId)
        return {
          criterionId: criterion.criterionId,
          type: criterion.type,
          required: criterion.required,
          status: result?.status,
          detail: result?.detail,
        }
      }),
      status,
      done: ['COMPLETED', 'INCOMPLETE', 'FAILED', 'CANCELLED', 'EXPIRED'].includes(status),
      reason: execution?.stopReason || currentRun?.error,
    }
  }

  function reset() {
    currentRun.value = null
    imageOperationIds.value = []
    resourceRevision.value = 0
    activeRunId.value = null
    executionFacts.value = emptyExecutionFacts()
    appliedRunId.value = null
    appliedSeq.value = 0
    streaming.value = false
    currentAssistant.value = ''
    currentThinking.value = ''
    toolEvents.value = {}
    todoRevision.value = 0
    error.value = null
    incompleteDetail.value = null
    goalProgress.value = null
    latestSnapshotSeq.value = null
    runInputs.value = []
  }

  function setReplayCursor(runId: string, seq: number) {
    appliedRunId.value = runId
    appliedSeq.value = seq
  }

  function setRunInputs(inputs: RunInputView[]) {
    // Mailbox records are retained with terminal statuses. A list started before an
    // enqueue acknowledgment must not erase that newly acknowledged record.
    const current = new Map(runInputs.value.map(input => [input.inputId, input]))
    for (const input of inputs) {
      const previous = current.get(input.inputId)
      if (!previous || previous.revision <= input.revision) current.set(input.inputId, input)
    }
    runInputs.value = [...current.values()].sort((a, b) => a.sequence - b.sequence)
  }

  function upsertRunInput(input: RunInputView) {
    const index = runInputs.value.findIndex(item => item.inputId === input.inputId)
    if (index >= 0) {
      if (runInputs.value[index].revision > input.revision) return
      runInputs.value.splice(index, 1, input)
    }
    else runInputs.value.push(input)
    runInputs.value.sort((a, b) => a.sequence - b.sequence)
  }

  return {
    currentRun, imageOperationIds, resourceRevision, setReplayCursor,
    activeRunId,
    executionFacts,
    streaming,
    currentAssistant,
    currentThinking,
    toolEvents,
    todoRevision,
    error,
    incompleteDetail,
    goalProgress,
    latestSnapshotSeq,
    runInputs,
    tokenUsage,
    begin,
    apply,
    hydrate,
    hydrateGoal,
    setRunInputs,
    upsertRunInput,
    reset,
  }
})
