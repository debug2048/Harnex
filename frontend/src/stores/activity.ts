import { defineStore } from 'pinia'
import { ref } from 'vue'
import { projectActivity, type SemanticActivity } from '../domain/activity'
import type { SemanticActivityDto, SubagentStateDto } from '../api/contracts'

export const useActivityStore = defineStore('activity', () => {
  const items = ref<SemanticActivity[]>([])
  const ids = new Set<string>()
  const pendingSubagentSteps = new Map<string, SemanticActivity[]>()
  let hydrating = false
  const unorderedParents = new Set<SemanticActivity>()

  function appendStep(parent: SemanticActivity, activity: SemanticActivity) {
    const details = parent.details ||= []
    if (!details.length || details[details.length - 1].seq <= activity.seq) { details.push(activity); return }
    if (hydrating) { details.push(activity); unorderedParents.add(parent); return }
    let low = 0, high = details.length
    while (low < high) {
      const middle = (low + high) >>> 1
      if (details[middle].seq <= activity.seq) low = middle + 1
      else high = middle
    }
    details.splice(low, 0, activity)
  }

  function sameSubagent(item: SemanticActivity, candidate: SemanticActivity) {
    if (item.kind !== 'subagent') return false
    // Child thread ids are globally unique and therefore authoritative. Provider-generated tool-call ids are only
    // unique inside one model response (e.g. a later batch can reuse `delegate_2`), so never let a reused call id bind
    // a step to a different, already-known child thread.
    if (candidate.subagentThreadId) return item.subagentThreadId === candidate.subagentThreadId
    return Boolean(candidate.delegateCallId && !item.subagentThreadId
      && item.delegateCallId === candidate.delegateCallId)
  }

  function subagentKey(activity: SemanticActivity) {
    return activity.subagentThreadId || activity.delegateCallId || ''
  }

  function apply(type: string, data: Record<string, unknown>, envelope: { runId?: string; seq?: number; ts?: number },
    historical = false) {
    const activity = projectActivity(type, data, envelope)
    if (!activity || ids.has(activity.id)) return
    activity.historical = historical
    if (type === 'provider_switched' && activity.groupId) {
      const existing = items.value.find(item =>
        item.eventType === 'provider_switched' && item.groupId === activity.groupId)
      if (existing) {
        // A selected target may be reported again on every model call while its circuit remains
        // open. Keep one route card for the Run and retain the most informative retry count.
        if ((activity.attempts || 0) > (existing.attempts || 0)) {
          existing.summary = activity.summary
          existing.attempts = activity.attempts
        }
        // Keep the first occurrence as the timeline anchor; only refresh the visible state.
        existing.outcome = activity.outcome
        existing.historical = activity.historical
        return
      }
    }
    ids.add(activity.id)
    if (activity.kind === 'context_compression' && activity.groupId) {
      const existing = items.value.find(item =>
        item.kind === 'context_compression' && item.groupId === activity.groupId)
      if (existing) {
        const startedSeq = existing.seq
        const startedAt = existing.ts
        Object.assign(existing, activity)
        // Completion updates the running card in place instead of moving it to the live edge.
        existing.seq = startedSeq
        existing.ts = startedAt
        return
      }
    }
    if (activity.kind === 'subagent_step') {
      const parent = items.value.find(item => sameSubagent(item, activity))
      if (parent) {
        appendStep(parent, activity)
      } else {
        const key = subagentKey(activity)
        if (key) {
          const pending = pendingSubagentSteps.get(key) || []
          pending.push(activity)
          pendingSubagentSteps.set(key, pending)
        }
      }
      return
    }
    if (activity.kind === 'subagent') {
      const existing = items.value.find(item => sameSubagent(item, activity))
      if (existing) {
        // Opening the drawer merges a snapshot with SSE. Starts may be synthesized
        // before their real event arrives; they must never duplicate or reset a child.
        if (type === 'subagent_started') {
          if (!existing.seq && activity.seq) {
            existing.seq = activity.seq
            existing.ts = activity.ts
          }
          return
        }
        const details = existing.details
        const startedSeq = existing.seq
        const startedAt = existing.ts
        Object.assign(existing, activity, {
          details,
          delegateCallId: activity.delegateCallId || existing.delegateCallId,
          parentThreadId: activity.parentThreadId || existing.parentThreadId,
        })
        // Keep the task anchored where it started. Terminal state/output update in place; moving to finishedSeq makes
        // sibling tasks from one wave impossible to group and places them after the parent answer in history.
        existing.seq = startedSeq
        existing.ts = startedAt
        return
      }
    }
    items.value.push(activity)
    if (activity.kind === 'subagent') {
      const key = subagentKey(activity)
      const pending = key ? pendingSubagentSteps.get(key) : undefined
      if (pending?.length) {
        activity.details ||= []
        activity.details.push(...pending)
        activity.details.sort((left, right) => left.seq - right.seq)
        pendingSubagentSteps.delete(key)
      }
    }
    if (items.value.length > 500) {
      const removed = items.value.splice(0, items.value.length - 500)
      removed.forEach(item => ids.delete(item.id))
    }
  }

  function hydrate(runId: string, activities: SemanticActivityDto[] = [], subagents: SubagentStateDto[] = [],
    historical = true, replace = true) {
    if (replace) reset()
    hydrating = true
    try {
    for (const state of subagents) {
      apply('subagent_started', {
        ...state,
        activityKind: 'subagent',
        activityTitle: state.activityTitle || 'Subagent',
        activitySummary: state.activitySummary || '',
        activityOutcome: 'running',
      }, { runId, seq: state.startedSeq || 0, ts: state.startedAt }, historical)
      for (const detail of state.details || []) {
        apply('subagent_event', {
          ...detail,
          subagentThreadId: detail.subagentThreadId || state.subagentThreadId,
          delegateCallId: detail.delegateCallId || state.delegateCallId,
          parentThreadId: detail.parentThreadId || state.parentThreadId,
        }, { runId, seq: detail.seq, ts: detail.ts }, historical)
      }
    }
    for (const item of activities) {
      apply(item.eventType, {
        ...item,
        activityKind: item.activityKind,
        activityTitle: item.activityTitle,
        activitySummary: item.activitySummary,
        activityTargets: item.activityTargets,
        activityOutcome: item.activityOutcome,
      }, { runId, seq: item.seq, ts: item.ts }, historical)
    }
    for (const state of subagents) {
      if (state.outcome === 'running') continue
      apply('subagent_finished', {
        ...state,
        activityKind: 'subagent',
        activityTitle: state.activityTitle || 'Subagent',
        activitySummary: state.activitySummary || state.output || '',
        activityOutcome: state.outcome,
        error: state.error === true || state.outcome === 'failed' || state.outcome === 'cancelled',
      }, { runId, seq: state.finishedSeq || state.startedSeq || 0, ts: state.finishedAt }, historical)
    }
    } finally {
      // Sort a plain copy once after a snapshot merge, not a reactive array on every event.
      for (const parent of unorderedParents) parent.details = [...(parent.details || [])].sort((left, right) => left.seq - right.seq)
      unorderedParents.clear()
      hydrating = false
    }
  }

  function reset() {
    items.value = []
    ids.clear()
    pendingSubagentSteps.clear()
    unorderedParents.clear()
  }

  return { items, apply, hydrate, reset }
})
