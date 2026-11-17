export interface ReportedUsage {
  prompt: number
  completion: number
  total: number
  cachedInput: number
  cacheWriteInput: number
  reasoning: number
}

export interface ProviderAttempt {
  provider?: string
  model?: string
  failover: boolean
  reason?: string
  attempts?: number
  outcome?: string
  circuitState?: string
}

export interface ExecutionFacts {
  runId?: string
  requestedProvider?: string
  requestedModel?: string
  actualProvider?: string
  actualModel?: string
  attempts: ProviderAttempt[]
  usage: ReportedUsage
  usageComplete: boolean
  lastSeq: number
}

export interface ExecutionEnvelope {
  runId?: string
  seq?: number
}

export function emptyExecutionFacts(
  requested: Pick<ExecutionFacts, 'requestedProvider' | 'requestedModel'> = {}
): ExecutionFacts {
  return {
    ...requested,
    attempts: [],
    usage: { prompt: 0, completion: 0, total: 0, cachedInput: 0, cacheWriteInput: 0, reasoning: 0 },
    usageComplete: false,
    lastSeq: 0,
  }
}

function numberOrZero(value: unknown): number {
  const n = Number(value ?? 0)
  return Number.isFinite(n) && n > 0 ? n : 0
}

/**
 * Idempotently project provider/model/usage facts from a run event.
 *
 * Run event streams are ordered by seq. Replays at or before lastSeq are ignored so reconnecting
 * from an older cursor cannot double-count provider-reported usage.
 */
export function reduceExecutionFacts(
  current: ExecutionFacts,
  type: string,
  data: Record<string, unknown> = {},
  envelope: ExecutionEnvelope = {}
): ExecutionFacts {
  const incomingRunId = envelope.runId || current.runId
  const isNewRun = Boolean(envelope.runId && current.runId && envelope.runId !== current.runId)
  let base = isNewRun
    ? emptyExecutionFacts({
        requestedProvider: current.requestedProvider,
        requestedModel: current.requestedModel,
      })
    : current

  const seq = typeof envelope.seq === 'number' ? envelope.seq : undefined
  if (!isNewRun && seq !== undefined && seq <= base.lastSeq) return base

  base = {
    ...base,
    runId: incomingRunId,
    lastSeq: seq === undefined ? base.lastSeq : Math.max(base.lastSeq, seq),
  }

  if (type === 'message_start') {
    return {
      ...base,
      actualProvider: String(data.provider || base.actualProvider || '') || undefined,
      actualModel: String(data.model || base.actualModel || '') || undefined,
    }
  }

  if (type === 'provider_attempt_started') {
    const provider = String(data.actualProvider || data.provider || data.to || '') || undefined
    const model = String(data.actualModel || data.model || '') || undefined
    const attempt: ProviderAttempt = {
      provider,
      model,
      failover: Boolean(data.failover),
      reason: String(data.reason || '') || undefined,
      outcome: String(data.outcome || 'streaming'),
    }
    return {
      ...base,
      actualProvider: provider || base.actualProvider,
      actualModel: model || base.actualModel,
      attempts: [...base.attempts, attempt],
    }
  }

  if (type === 'provider_switched') {
    const from = String(data.from || '') || undefined
    const provider = String(data.actualProvider || data.to || '') || undefined
    const model = String(data.actualModel || data.model || '') || undefined
    const attempts = numberOrZero(data.selectedAttempts)
    const reason = String(data.reason || '') || undefined
    const circuitState = String(data.circuitState || '') || undefined
    const failed: ProviderAttempt | null = from ? {
      provider: from,
      model: base.actualProvider === from
        ? (base.actualModel || base.requestedModel)
        : base.requestedModel,
      failover: false,
      reason,
      attempts,
      outcome: 'failed',
      circuitState,
    } : null
    const fallback: ProviderAttempt = {
      provider,
      model,
      failover: true,
      reason,
      outcome: 'streaming',
      circuitState,
    }
    return {
      ...base,
      actualProvider: provider || base.actualProvider,
      actualModel: model || base.actualModel,
      attempts: [...base.attempts, ...(failed ? [failed] : []), fallback],
    }
  }

  if (type === 'usage') {
    const rootTaskUsage = !data.subagentThreadId
      && (!data.kind || data.kind === 'task' || data.kind === 'model')
    const prompt = numberOrZero(data.prompt)
    const completion = numberOrZero(data.completion)
    const total = numberOrZero(data.total) || prompt + completion
    const cachedInput = numberOrZero(data.cachedInput)
    const cacheWriteInput = numberOrZero(data.cacheWriteInput)
    const reasoning = numberOrZero(data.reasoning)
    return {
      ...base,
      actualProvider: rootTaskUsage ? String(data.provider || base.actualProvider || '') || undefined : base.actualProvider,
      actualModel: rootTaskUsage ? String(data.model || base.actualModel || '') || undefined : base.actualModel,
      usage: {
        prompt: base.usage.prompt + prompt,
        completion: base.usage.completion + completion,
        total: base.usage.total + total,
        cachedInput: base.usage.cachedInput + cachedInput,
        cacheWriteInput: base.usage.cacheWriteInput + cacheWriteInput,
        reasoning: base.usage.reasoning + reasoning,
      },
      usageComplete: rootTaskUsage && data.complete === true,
    }
  }

  if (type === 'done' && data.execution && typeof data.execution === 'object') {
    const execution = data.execution as Record<string, unknown>
    return {
      ...base,
      actualProvider: String(execution.finalProvider || base.actualProvider || '') || undefined,
      actualModel: String(execution.finalModel || base.actualModel || '') || undefined,
      usageComplete: execution.usageComplete === true,
    }
  }

  return base
}
