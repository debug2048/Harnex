import * as vscode from 'vscode'
import { HarnexClient, HttpError } from '../client/HarnexClient'
import { DiffReview, FILE_TOOLS } from './DiffReview'
import { StreamEnvelope } from './SseTap'
import { log } from '../util/log'

/**
 * Routes interaction events from the run stream to native UX (docs/03 §3.32.6):
 *   - file-tool approval  → DiffReview (native diff editor as the approval surface)
 *   - other tool approval → inline card in the chat panel; an OS notification is added
 *     ONLY when the panel is hidden, so the user never misses a blocked run
 *   - approval_resolved / terminal events → close stale diff views
 * question / plan_review render inline in the chat panel (ChatController).
 */
export class InteractionRouter {
  /** callIds we've already surfaced natively — the stream can replay on reattach. */
  private readonly seen = new Set<string>()

  constructor(
    private readonly client: HarnexClient,
    private readonly diffReview: DiffReview,
    private readonly isChatVisible: () => boolean = () => false,
  ) {}

  handle(env: StreamEnvelope): void {
    const conversationId = env.conversationId ?? String(env.data?.conversationId ?? '')
    switch (env.type) {
      case 'approval_required': {
        const callId = String(env.data.callId ?? '')
        if (!conversationId || !callId || this.seen.has(callId)) return
        this.seen.add(callId)
        void this.routeApproval(
          conversationId, callId,
          String(env.data.toolName ?? ''),
          typeof env.data.arguments === 'string' ? env.data.arguments : JSON.stringify(env.data.arguments ?? {}))
        break
      }
      case 'approval_resolved': {
        const callId = env.data?.callId ? String(env.data.callId) : undefined
        if (conversationId) void this.diffReview.close(conversationId, callId)
        break
      }
      case 'done':
      case 'cancelled':
      case 'error':
        if (conversationId) void this.diffReview.close(conversationId)
        break
    }
  }

  private async routeApproval(conversationId: string, callId: string, toolName: string, argsJson: string): Promise<void> {
    try {
      if (FILE_TOOLS.has(toolName)) {
        const handled = await this.diffReview.review(conversationId, callId, toolName, argsJson)
        if (handled) return
      }
      // Non-file tools (and reconstruction fallbacks) are decided in the chat panel's inline
      // card; only raise an OS notification when the panel can't be seen.
      if (!this.isChatVisible()) await this.notifyApproval(conversationId, callId, toolName, argsJson)
    } catch (err) {
      log(`approval routing failed for ${toolName}/${callId}: ${err instanceof Error ? err.message : err}`)
    }
  }

  private async notifyApproval(conversationId: string, callId: string, toolName: string, argsJson: string): Promise<void> {
    const preview = summarizeArgs(toolName, argsJson)
    const pick = await vscode.window.showWarningMessage(
      `Harnex 请求执行 ${toolName}${preview ? `: ${preview}` : ''}`,
      'Allow', 'Deny', 'Always Allow (session)')
    if (pick === undefined) return   // dismissed — webview approval card remains available
    const decision = pick === 'Deny' ? 'deny' : 'allow'
    try {
      await this.client.resolveApproval(conversationId, callId, decision, pick === 'Always Allow (session)')
    } catch (err) {
      if (err instanceof HttpError && err.status === 410) return   // resolved elsewhere
      void vscode.window.showErrorMessage(`Harnex 审批回写失败: ${err instanceof Error ? err.message : err}`)
    }
  }

  /** Activation catch-up (docs/03 §3.32.6): surface approvals that fired while no window was open. */
  async surfacePending(): Promise<void> {
    let pending
    try {
      pending = await this.client.pendingInteractions()
    } catch {
      return
    }
    for (const p of pending) {
      if (p.kind !== 'approval' || this.seen.has(p.callId)) continue
      this.seen.add(p.callId)
      void this.routeApproval(p.conversationId, p.callId, String(p.payload.toolName ?? ''), String(p.payload.arguments ?? ''))
    }
  }
}

function summarizeArgs(toolName: string, argsJson: string): string {
  try {
    const a = JSON.parse(argsJson || '{}') as Record<string, unknown>
    if (toolName === 'bash' && typeof a.command === 'string') return truncate(a.command, 160)
    if (typeof a.path === 'string') return a.path
    return truncate(argsJson, 120)
  } catch {
    return truncate(argsJson, 120)
  }
}

function truncate(s: string, n: number): string {
  return s.length > n ? `${s.slice(0, n)}…` : s
}
