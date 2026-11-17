import type { ExecutionSpec } from '../../../packages/protocol/workbench'

interface Draft {
  executionKind: 'CONVERSATION' | 'AGENT'
  permissionMode: 'ask' | 'accept-edits' | 'yolo'
}
interface TextSettings { provider: string; model: string; reasoningEffort: string }

/** Copy composer choices before any asynchronous upload/admission work can observe a later edit. */
export function executionDraft(draft: Draft, settings: TextSettings): ExecutionSpec {
  // Match the composer's "default" option: a partial pair must not pin a hidden target.
  const selected = Boolean(settings.provider && settings.model)
  const text = { provider: selected ? settings.provider : undefined, model: selected ? settings.model : undefined,
    reasoningEffort: settings.reasoningEffort || 'auto' }
  if (draft.executionKind === 'AGENT') return {
    // An explicit empty list clears legacy thread overrides and resolves the current user default.
    kind: 'AGENT', text, toolsets: [], permissionMode: draft.permissionMode,
    readOnly: false, planReview: 'REQUIRED', interactionTarget: 'local-api',
  }
  return { kind: 'CONVERSATION', action: 'AUTO', text }
}
