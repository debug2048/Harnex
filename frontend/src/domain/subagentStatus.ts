import { t } from '../i18n'

/** A returned report, a blocked task and an execution error are distinct outcomes. */
export function subagentDisplayOutcome(terminalStatus: string, fallback: string) {
  switch (terminalStatus) {
    case 'UNCONFIRMED': return 'unconfirmed'
    case 'BLOCKED': return 'blocked'
    case 'BUDGET_EXHAUSTED': return 'incomplete'
    case 'TIMED_OUT': return 'timed_out'
    case 'CANCELLED': case 'SUPERSEDED': return 'cancelled'
    case 'PAUSED': return 'paused'
    default: return fallback
  }
}

export function activityOutcomeLabel(outcome: string) {
  switch (outcome) {
    case 'running': return t('tool.running')
    case 'waiting': return t('tool.waiting')
    case 'completed': return t('tool.done')
    case 'failed': return t('tool.failed')
    case 'cancelled': return t('activity.cancelled')
    case 'unconfirmed': return t('chat.subagentUnconfirmed')
    case 'blocked': return t('chat.subagentBlocked')
    case 'incomplete': return t('chat.subagentIncomplete')
    case 'timed_out': return t('chat.subagentTimedOut')
    case 'paused': return t('chat.subagentPaused')
    case 'attention': return t('chat.subagentAttention')
    default: return outcome
  }
}

export function subagentNeedsAttention(outcome: string) {
  return ['unconfirmed', 'blocked', 'incomplete', 'paused', 'attention'].includes(outcome)
}

export function subagentOutcomeExplanation(outcome: string) {
  if (outcome === 'unconfirmed') return t('chat.subagentUnconfirmedDesc')
  if (outcome === 'blocked') return t('chat.subagentBlockedDesc')
  if (outcome === 'incomplete') return t('chat.subagentIncompleteDesc')
  return ''
}
