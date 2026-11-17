import type * as Workbench from '../../../packages/protocol/workbench'
import type { NotificationMode } from './im'

export type AutomationTarget = Required<Workbench.AutomationTarget>
export type AutomationTargetView = { target: AutomationTarget; online: boolean }
export type AutomationJob = Omit<Required<Workbench.CronJobView>, 'target'> & { target: AutomationTarget }
export type AutomationAttempt = Omit<Required<Workbench.AutomationAttempt>, 'target'> & { target: AutomationTarget }
export type AutomationResult = Required<Workbench.AutomationExecutionResult>
export type SchedulePreview = Required<Workbench.SchedulePreview>

export interface AutomationDraft {
  id?: string
  name: string
  targetId: string
  prompt: string
  cron: string
  timeZone: string
  provider: string
  model: string
  toolsets: string[]
  allowMutations: boolean
  notificationMode: NotificationMode
  disabled: boolean
  maxWorkIterations?: number
  planReview?: 'AUTOMATIC' | 'REQUIRED'
}

export type ScheduleFrequency = 'daily' | 'weekdays' | 'weekly' | 'hourly' | 'custom'
export interface ScheduleInput { frequency: ScheduleFrequency; time: string; weekday: string; minute: number; custom: string }

export function readSchedule(cron: string): ScheduleInput {
  const result: ScheduleInput = { frequency: 'custom', time: '09:00', weekday: '1', minute: 0, custom: cron }
  const match = /^0 (\d{1,2}) (\d{1,2}|\*) \* \* (\*|1-5|[0-7])$/.exec(cron.trim())
  if (!match || +match[1] > 59 || match[2] !== '*' && +match[2] > 23) return result
  result.minute = +match[1]
  if (match[2] === '*') { if (match[3] === '*') result.frequency = 'hourly'; return result }
  result.time = `${match[2].padStart(2, '0')}:${match[1].padStart(2, '0')}`
  result.frequency = match[3] === '*' ? 'daily' : match[3] === '1-5' ? 'weekdays' : 'weekly'
  if (result.frequency === 'weekly') result.weekday = match[3] === '7' ? '0' : match[3]
  return result
}

export function scheduleExpression(input: ScheduleInput): string {
  if (input.frequency === 'custom') return input.custom.trim()
  if (input.frequency === 'hourly') return `0 ${input.minute} * * * *`
  const [hour, minute] = input.time.split(':').map(Number)
  return `0 ${minute} ${hour} * * ${input.frequency === 'weekdays' ? '1-5' : input.frequency === 'weekly' ? input.weekday : '*'}`
}

export function newAutomation(): AutomationDraft {
  return { name: '', targetId: 'personal', prompt: '', cron: '0 0 9 * * *', timeZone: Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC',
    provider: '', model: '', toolsets: [], allowMutations: false, notificationMode: 'OFF', disabled: false, planReview: 'AUTOMATIC' }
}

/** Only editable configuration goes back to the server; run history remains server-owned. */
export function editAutomation(job: AutomationJob): AutomationDraft {
  return { id: job.id, name: job.name || '', targetId: job.target.id, prompt: job.prompt, cron: job.cron, timeZone: job.timeZone,
    provider: job.provider || '', model: job.model || '', toolsets: [...(job.toolsets || [])],
    allowMutations: job.allowMutations, disabled: job.disabled, maxWorkIterations: job.maxWorkIterations ?? undefined,
    planReview: job.planReview, notificationMode: job.notificationMode || 'OFF' }
}
