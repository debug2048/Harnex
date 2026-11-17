import type { MessageRecord, ContentPart } from '../../../packages/protocol/workbench'

export interface Msg {
  messageId?: string
  parentMessageId?: string
  runId?: string
  turnId?: string
  status?: MessageRecord['status']
  contentParts?: ContentPart[]
  role: 'user' | 'assistant' | 'tool' | 'system'
  content: string
  toolCalls?: Array<Record<string, unknown>>
  toolCallId?: string
  channel?: string
  source?: string
  runInputIds?: string[]
  _uid?: string
  _toolEvent?: ToolEvent
  _backendIndex?: number
  _historical?: boolean
  /** Display-only Agent progress, anchored to an immutable conversation message. */
  _process?: boolean
  _afterMessageId?: string
  _beforeMessageId?: string
  /** Run event sequence used only to interleave live UI entries chronologically. */
  _seq?: number
  originalInput?: string
  reasoning?: string
  reasoningBlock?: boolean
  _reasoningBlock?: boolean
  attachments?: MessageAttachmentView[] | null
}

export interface MessageAttachmentView {
  artifactId?: string
  name: string
  mime?: string
  size?: number
  path?: string
  extracted?: boolean
}

/** Persisted JSON uses null for messages without attachments; UI consumers always need an array. */
export function normalizeMessageAttachments(
  attachments?: MessageAttachmentView[] | null,
): MessageAttachmentView[] {
  return Array.isArray(attachments) ? attachments : []
}

/** Stable attachment projection from persisted user messages; never includes composer drafts or upload-directory orphans. */
export function conversationAttachmentViews(messages: Msg[]): MessageAttachmentView[] {
  const attachments = new Map<string, MessageAttachmentView>()
  for (const message of messages) {
    if (message.role !== 'user' || !Array.isArray(message.attachments)) continue
    for (const attachment of message.attachments) {
      if (!attachment?.name?.trim()) continue
      const key = attachment.artifactId || attachment.path?.trim() || attachment.name.trim()
      attachments.set(key, attachment)
    }
  }
  return [...attachments.values()]
}

export interface ToolEventDiff {
  path: string
  added: number
  removed: number
  beforeBytes?: number
  afterBytes?: number
  unchanged?: boolean
  binary?: boolean
  whitespaceOnly?: boolean
  truncated?: boolean
  unifiedDiff: string
}

export interface ToolEvent {
  id: string
  name: string
  /** Short user-facing intent for this invocation, separate from its arguments and result. */
  purpose?: string
  arguments?: string
  content?: string
  error?: boolean
  durationMs?: number
  done: boolean
  resultPending?: boolean
  status?: 'queued' | 'running' | 'completed' | 'failed'
  /** Sequence of the tool_use_start event. Completion updates retain this anchor. */
  seq?: number
  /** Historical summaries are not result bodies; full details are fetched for this occurrence on expansion. */
  summary?: string
  detailSource?: { runId: string; seq: number }
  metadata?: { diff?: ToolEventDiff } & Record<string, unknown>
}

export interface ApprovalPrompt {
  callId: string
  toolName: string
  arguments: string
  outsideWrite?: boolean
  sourceCallId?: string
  subagentThreadId?: string
}

export interface QuestionOption {
  key: string
  label: string
  description: string
  recommended: boolean
  value: string
  index: number
}

export interface QuestionPrompt {
  callId: string
  question: string
  options: QuestionOption[]
  allowFreeText: boolean
}

export interface PlanReviewPrompt {
  callId: string
  plan: string
}

export interface GoalProgress {
  goalId?: string
  runId?: string
  runRevision?: number
  objective?: string
  turnsUsed: number
  maxTurns: number
  modelCalls?: number
  maxModelCalls?: number
  toolCalls?: number
  maxToolCalls?: number
  tokensUsed: number
  tokenBudget: number
  costUsed?: number
  costBudget?: number
  costKnown?: boolean
  criteria?: Array<{
    criterionId: string
    type: string
    required: boolean
    status?: 'PASS' | 'FAIL' | 'REVIEW_REQUIRED'
    detail?: string
  }>
  status: string
  done?: boolean
  reason?: string
}

let messageSequence = 0

export function newMessageUid(): string {
  return `m${Date.now().toString(36)}-${(messageSequence++).toString(36)}`
}

export function withMessageUid(message: Msg): Msg {
  if (message._uid) return message
  return { ...message, _uid: newMessageUid() }
}

function isUiOnlyMessage(message: Msg): boolean {
  return message.role === 'system' && /^↪ provider switched:/.test(message.content || '')
}

export function backendKeepCountBefore(items: Msg[], visibleIndex: number): number {
  const target = items[visibleIndex]
  if (typeof target?._backendIndex === 'number') return target._backendIndex
  let keep = 0
  for (let index = 0; index < visibleIndex; index++) {
    if (!isUiOnlyMessage(items[index])) keep++
  }
  return keep
}

function isInternalHistoryMessage(message: Msg): boolean {
  const content = String(message.content || '').trim()
  return content.startsWith('[Compressed history summary]')
    || content.startsWith('[system-reminder]')
    || content.startsWith('[system_reminder]')
    || content.startsWith('[memory-reminder]')
    || content.startsWith('[memory_reminder]')
}

const BRIEF_TOOL_COMMENTARY_MAX_CHARS = 180

/**
 * Best-effort distinction for providers that put both user-facing narration and internal work notes in `content`.
 * A short, prose-like status line is useful commentary; longer, paragraph/list-shaped text is safer to treat as
 * intermediate reasoning. Explicit provider reasoning never goes through this heuristic.
 */
export function isBriefToolCommentary(content: string): boolean {
  const text = String(content || '').trim()
  if (!text || Array.from(text).length > BRIEF_TOOL_COMMENTARY_MAX_CHARS) return false
  if (/\r?\n\s*\r?\n/.test(text)) return false
  const lines = text.split(/\r?\n/).map(line => line.trim()).filter(Boolean)
  if (lines.length > 2) return false
  return !lines.slice(1).some(line => /^(?:[-*+] |\d+[.)]\s)/.test(line))
}

export function historicalDisplayMessages(raw: Msg[]): Msg[] {
  const visible: Msg[] = []
  for (const message of raw) {
    if (isInternalHistoryMessage(message)) continue
    if (message.role === 'assistant' && ((message.content || '').trim() || (message.reasoning || '').trim())) {
      // Tool-dispatch text is ambiguous across OpenAI-compatible providers: a brief status such as "I'll inspect the
      // config" is useful user-facing commentary, while some reasoning models put long work notes in the same
      // `content` field. Keep short prose visible, collapse everything else, and always keep provider-native reasoning
      // collapsed. Backend history remains untouched for exact replay.
      if (message.toolCalls?.length) {
        const content = String(message.content || '')
        if (isBriefToolCommentary(content)) {
          visible.push({ ...message, content, _reasoningBlock: false })
        } else {
          const reasoning = [message.reasoning, content].filter(part => (part || '').trim()).join('\n\n')
          visible.push({ ...message, content: '', reasoning, _reasoningBlock: true })
        }
      } else {
        visible.push(message)
      }
    } else if (message.role === 'user') {
      visible.push(message)
    }
  }
  return visible
}
