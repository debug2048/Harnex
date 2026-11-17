import type { NotificationRecipient as WireNotificationRecipient } from '../../../packages/protocol/workbench'

export type ImChannel = 'dingtalk' | 'wecom' | 'feishu' | 'telegram'
export interface ImPeer { userId: string; chatId: string; label: string; chatType?: string }
export type NotificationMode = 'OFF' | 'ALL' | 'FAILURES'
export interface NotificationRecipient {
  id: string; channel: ImChannel; account: string; userId: string; chatId: string; label: string; chatType: string
}
export interface NotificationSettings {
  revision: string; defaultRecipient: NotificationRecipient | null; valid: boolean
  recipients: Array<{ recipient: NotificationRecipient; online: boolean; available: boolean; reason: string }>
}
export function notificationRecipientLabel(value: WireNotificationRecipient, chinese = true, detail: 'full' | 'compact' = 'full') {
  const names: Record<string, string> = chinese ? { dingtalk: '钉钉', wecom: '企业微信', feishu: '飞书', telegram: 'Telegram' }
    : { dingtalk: 'DingTalk', wecom: 'WeCom', feishu: 'Feishu', telegram: 'Telegram' }
  const kind = value.chatType === 'group' ? chinese ? '群聊' : 'Group' : value.chatType === 'single' ? chinese ? '私聊' : 'Direct' : chinese ? '会话' : 'Chat'
  if (detail === 'compact') return (names[value.channel || ''] || value.channel || 'IM') + ' · ' + (value.label || value.chatId || '') + ' · ' + kind
  return (names[value.channel || ''] || value.channel || 'IM') + ' · ' + (value.account || '') + ' · ' + kind + ' ' + (value.label || value.userId || '') + ' (' + (value.chatId || '') + ')'
}
export interface ImConnection {
  enabled: boolean; clientId: string; clientSecret: string; allowMutations: boolean; peers: ImPeer[]
  cardTemplateId?: string
  allowYolo?: boolean
}
export interface ImSavedCredential {
  id: string; revision: string; channel: ImChannel; clientId: string; cardTemplateId: string
}
export interface ImCredentialSelection { credentialId: string; credentialRevision: string }
export interface ImDocument {
  machine?: string
  revision: string
  workspace: string
  deliveryError?: string
  savedCredentials?: ImSavedCredential[]
  credentialError?: string
  connections: Record<ImChannel, ImConnection>
  statuses: Record<ImChannel, { state: string; detail: string; lastMessageAt: number; cardMode?: string; cardDetail?: string;
    lastCardActionAt?: number; lastCardAction?: string }>
  pairing: Array<ImPeer & { id: string; code: string; channel: ImChannel; createdAt: number }>
  deliveries: Array<{ id: string; channel: ImChannel; userId: string; chatId: string; state: string; error: string; createdAt: number; kind?: string; runId?: string }>
  activeTasks: Array<{ channel: ImChannel; userId: string; chatId: string; conversationId: string; runId: string;
    model?: string; permissionMode?: string; progress?: string }>
}
