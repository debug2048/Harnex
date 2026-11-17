<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { api, getRuntimeRevision } from '../../api/client'
import { locale } from '../../i18n'
import type { ImChannel, ImConnection, ImDocument, ImSavedCredential, NotificationSettings } from '../../domain/im'
import { notificationRecipientLabel } from '../../domain/im'
import { errorMessage } from '../../utils/unknown'
import { chooseDownloadDestination } from '../../utils/saveDownload'

const platforms = {
  dingtalk: { zh: '钉钉', en: 'DingTalk', id: 'Client ID / AppKey', secret: 'Client Secret / AppSecret' },
  wecom: { zh: '企业微信', en: 'WeCom', id: 'Bot ID', secret: 'Secret' },
  feishu: { zh: '飞书', en: 'Feishu', id: 'App ID', secret: 'App Secret' },
  telegram: { zh: 'Telegram', en: 'Telegram', id: 'Bot ID', secret: 'Bot Token' },
} satisfies Record<ImChannel, { zh: string; en: string; id: string; secret: string }>
const channels = Object.keys(platforms) as ImChannel[]
const router = useRouter()
const selected = ref<ImChannel>('dingtalk')
const document = ref<ImDocument | null>(null)
const notifications = ref<NotificationSettings | null>(null)
const drafts = ref<Partial<Record<ImChannel, ImConnection>>>({})
const dirty = ref<Partial<Record<ImChannel, boolean>>>({})
const credentialChoices = ref<Partial<Record<ImChannel, ImSavedCredential>>>({})
const busy = ref(false)
const error = ref('')
const notificationSaving = ref(false), notificationError = ref('')
const notificationLoadError = ref('')
const statusError = ref('')
const message = ref('')
const secretVisible = ref(false)
const revealedSecret = ref<string | null>(null)
const revealingSecret = ref(false)
const secretError = ref('')
let secretSequence = 0
type ActionKind = 'connect' | 'disconnect' | 'reconnect' | 'pair'
const pendingAction = ref<{ channel: ImChannel; kind: ActionKind } | null>(null)
const connectionFeedback = ref<HTMLElement | null>(null)
const connectionActionError = ref<{ channel: ImChannel; text: string } | null>(null)
const downloading = ref(false)
const downloadAbort = new AbortController()
let disposed = false
let requestSequence = 0
let timer: ReturnType<typeof setTimeout> | undefined
const runtimeRevision = getRuntimeRevision()
const cn = computed(() => locale.value === 'zh-CN')
const label = (zh: string, en: string) => cn.value ? zh : en
const channelName = (channel: ImChannel) => label(platforms[channel].zh, platforms[channel].en)
const current = computed(() => drafts.value[selected.value])
const feishuChatUrl = computed(() => {
  const saved = document.value?.connections.feishu
  if (selected.value !== 'feishu' || !saved?.enabled || !saved.clientId
    || current.value?.clientId.trim() !== saved.clientId || document.value?.statuses.feishu?.state !== 'CONNECTED') return ''
  return 'https://applink.feishu.cn/client/bot/open?appId=' + encodeURIComponent(saved.clientId)
})
const savedCredentials = computed(() => (document.value?.savedCredentials || []).filter(item => item.channel === selected.value))
const selectedCredential = computed(() => credentialChoices.value[selected.value])
const secretValue = computed(() => revealedSecret.value ?? current.value?.clientSecret ?? '')
const status = computed(() => document.value?.statuses[selected.value])
const connectionState = computed(() => {
  if (pendingAction.value?.channel === selected.value) {
    if (pendingAction.value.kind === 'connect') return 'SAVING'
    if (pendingAction.value.kind === 'reconnect') return 'CONNECTING'
    if (pendingAction.value.kind === 'disconnect') return 'DISCONNECTING'
  }
  return statusError.value ? 'UNKNOWN' : status.value?.state || 'DISCONNECTED'
})
const connecting = computed(() => ['SAVING', 'CONNECTING', 'AUTHENTICATING', 'RECONNECTING', 'DISCONNECTING'].includes(connectionState.value))
const connectionHint = computed(() => {
  switch (connectionState.value) {
    case 'SAVING': return label('正在保存设置并发起连接…', 'Saving settings and starting the connection…')
    case 'DISCONNECTING': return label('正在断开连接…', 'Disconnecting…')
    case 'CONNECTED': return dirty.value[selected.value]
      ? label('当前连接使用已保存的设置；你的修改尚未生效。', 'Connected using the saved settings. Your changes are not applied yet.')
      : label('已连接到平台。直接给机器人发消息即可；首次使用会自动申请授权，在下方确认后重新发送任务。', 'Connected to the platform. Message the bot directly. First contact requests access; approve it below, then resend the task.')
    case 'CONNECTING': return label('正在建立连接，请稍候…', 'Establishing the connection…')
    case 'AUTHENTICATING': return label('正在验证机器人身份，请稍候…', 'Verifying the bot credentials…')
    case 'RECONNECTING': return status.value?.detail || label('连接已中断，正在自动重试。', 'Connection interrupted. Retrying automatically.')
    case 'ERROR': return status.value?.detail || label('连接失败，请检查凭据与平台设置后重试。', 'Connection failed. Check the credentials and platform configuration, then retry.')
    case 'UNKNOWN': return label('暂时无法取得最新状态，不能确认是否已连接。请刷新状态。', 'Unable to obtain the latest status. The connection cannot be confirmed; refresh to check.')
    default: return label('当前未连接。点击“保存并连接”启用机器人。', 'Not connected. Select Save and connect to enable the bot.')
  }
})
const pairing = computed(() => document.value?.pairing.filter(item => item.channel === selected.value) || [])
const problems = computed(() => document.value?.deliveries.filter(item => item.channel === selected.value && ['FAILED', 'UNKNOWN'].includes(item.state)) || [])
const activeTasks = computed(() => document.value?.activeTasks.filter(item => item.channel === selected.value) || [])
const stateLabel = (state = '') => ({
  CONNECTED: label('已连接', 'Connected'), CONNECTING: label('正在连接', 'Connecting'),
  AUTHENTICATING: label('正在认证', 'Authenticating'), RECONNECTING: label('正在重连', 'Reconnecting'),
  ERROR: label('连接失败', 'Connection failed'), DISCONNECTED: label('未连接', 'Disconnected'),
  SAVING: label('保存并连接中', 'Saving and connecting'), DISCONNECTING: label('正在断开', 'Disconnecting'), UNKNOWN: label('状态未知', 'Status unknown'),
}[state] || state)

function valid() { return !disposed && getRuntimeRevision() === runtimeRevision }
async function chooseNotification(event: Event) {
  const config = notifications.value
  if (!config || !valid() || notificationSaving.value) return
  const recipientId = (event.target as HTMLSelectElement).value
  ++requestSequence
  notificationSaving.value = true; notificationError.value = ''
  try {
    const next = await api.imSetNotificationRecipient(config.revision, recipientId || null)
    if (valid()) { notifications.value = next; message.value = label('默认通知会话已保存。', 'Default notification recipient saved.') }
  } catch (cause) { if (valid()) { notificationSaving.value = false; notificationError.value = errorMessage(cause); await refresh() } }
  finally { if (valid()) notificationSaving.value = false }
}
function hideSecret() {
  ++secretSequence; secretVisible.value = false; revealedSecret.value = null; revealingSecret.value = false; secretError.value = ''
}
async function toggleSecret() {
  if (secretVisible.value) { hideSecret(); return }
  if (!current.value || !document.value || !valid() || revealingSecret.value) return
  if (current.value.clientSecret !== '••••••••') { secretVisible.value = true; return }
  const channel = selected.value, clientId = current.value.clientId, revision = document.value.revision
  const sequence = ++secretSequence
  revealingSecret.value = true; secretError.value = ''
  try {
    const credential = selectedCredential.value
    const result = credential ? await api.imRevealSavedSecret(credential.id, credential.revision)
      : await api.imRevealSecret(channel, revision, clientId)
    if (!valid() || sequence !== secretSequence || selected.value !== channel || document.value?.revision !== revision
      || current.value?.clientId !== clientId || current.value?.clientSecret !== '••••••••') return
    revealedSecret.value = result.clientSecret; secretVisible.value = true
  } catch (cause) { if (valid() && sequence === secretSequence) secretError.value = errorMessage(cause) }
  finally { if (valid() && sequence === secretSequence) revealingSecret.value = false }
}
function apply(next: ImDocument) {
  if (!valid()) return
  if (document.value && document.value.revision !== next.revision) hideSecret()
  document.value = next
  statusError.value = ''
  for (const channel of channels) {
    if (!dirty.value[channel]) drafts.value[channel] = structuredClone(next.connections[channel])
  }
}
async function refresh() {
  if (!valid() || busy.value || notificationSaving.value) return
  const sequence = ++requestSequence
  const [connectionResult, notificationResult] = await Promise.allSettled([api.imSettings(), api.imNotificationSettings()])
  if (!valid() || sequence !== requestSequence) return
  if (connectionResult.status === 'fulfilled') apply(connectionResult.value)
  else statusError.value = errorMessage(connectionResult.reason)
  if (notificationResult.status === 'fulfilled') { notifications.value = notificationResult.value; notificationLoadError.value = '' }
  else notificationLoadError.value = errorMessage(notificationResult.reason)
}
async function poll() {
  await refresh()
  if (valid()) timer = setTimeout(poll, connecting.value ? 1000 : 3000)
}
function touch() { dirty.value[selected.value] = true; message.value = ''; connectionActionError.value = null }
function chooseCredential(event: Event) {
  if (!current.value || busy.value) return
  const id = (event.target as HTMLSelectElement).value
  const credential = savedCredentials.value.find(item => item.id === id)
  hideSecret()
  credentialChoices.value[selected.value] = credential ? { ...credential } : undefined
  if (credential) {
    if (current.value.clientId !== credential.clientId) {
      current.value.peers = []; current.value.allowMutations = false; current.value.allowYolo = false
    }
    current.value.clientId = credential.clientId
    current.value.clientSecret = '••••••••'
    current.value.cardTemplateId = credential.cardTemplateId
  } else { current.value.clientSecret = '' }
  touch()
}
function changeId(event: Event) {
  if (!current.value) return
  hideSecret()
  credentialChoices.value[selected.value] = undefined
  current.value.clientId = (event.target as HTMLInputElement).value
  current.value.clientSecret = ''; current.value.peers = []; touch()
}
function changeSecret(event: Event) {
  if (current.value) {
    credentialChoices.value[selected.value] = undefined
    ++secretSequence; revealedSecret.value = null; revealingSecret.value = false; secretError.value = ''
    current.value.clientSecret = (event.target as HTMLInputElement).value
    if (selected.value === 'telegram') {
      const id = current.value.clientSecret.trim().match(/^([1-9][0-9]*):/)?.[1] || ''
      if (current.value.clientId !== id) {
        current.value.peers = []; current.value.allowMutations = false; current.value.allowYolo = false
      }
      current.value.clientId = id
    }
    touch()
  }
}
function changePermission(event: Event) {
  if (current.value) {
    current.value.allowMutations = (event.target as HTMLInputElement).checked
    if (!current.value.allowMutations) current.value.allowYolo = false
    touch()
  }
}
function changeYolo(event: Event) { if (current.value) { current.value.allowYolo = (event.target as HTMLInputElement).checked; touch() } }
function changeTemplate(event: Event) { if (current.value) { current.value.cardTemplateId = (event.target as HTMLInputElement).value; touch() } }
async function downloadTemplate() {
  if (downloading.value || !valid()) return
  downloading.value = true
  let destination: Awaited<ReturnType<typeof chooseDownloadDestination>> = null
  try {
    destination = await chooseDownloadDestination('harnex-dingtalk-card.json')
    if (!destination) return
    const template = await api.imCardTemplate()
    if (!valid()) { await destination.cancel(); return }
    await destination.write(new Blob([JSON.stringify(template, null, 2)], { type: 'application/json' }), downloadAbort.signal)
  } catch (cause) { await destination?.cancel(); if (valid()) error.value = errorMessage(cause) }
  finally { if (valid()) downloading.value = false }
}
async function action(operation: () => Promise<ImDocument>, savedChannel?: ImChannel, kind: ActionKind = 'pair') {
  if (busy.value || !valid()) return
  ++requestSequence
  hideSecret()
  const channel = savedChannel || selected.value
  pendingAction.value = { channel, kind }
  connectionActionError.value = null
  if (kind !== 'pair') scrollConnectionFeedback(channel)
  busy.value = true; error.value = ''; message.value = ''
  try {
    const next = await operation()
    if (!valid()) return
    if (savedChannel) { dirty.value[savedChannel] = false; credentialChoices.value[savedChannel] = undefined }
    apply(next)
    message.value = kind === 'pair' ? label('授权已确认。', 'Access approved.')
      : kind === 'disconnect' ? label('已保存并断开连接。', 'Saved and disconnected.')
      : channelName(channel) + ' · ' + label('设置已保存，请查看按钮下方的实时连接状态。', 'Settings saved. See the live connection status below the buttons.')
  } catch (cause) {
    if (valid()) {
      error.value = errorMessage(cause)
      if (kind !== 'pair') connectionActionError.value = { channel, text: error.value }
    }
  } finally {
    if (valid()) { busy.value = false; pendingAction.value = null; if (kind !== 'pair') scrollConnectionFeedback(channel); void refresh() }
  }
}
function scrollConnectionFeedback(channel: ImChannel) {
  void nextTick(() => { if (valid() && selected.value === channel) connectionFeedback.value?.scrollIntoView?.({ block: 'nearest' }) })
}
function save(enabled: boolean) {
  const channel = selected.value
  if (!document.value || !current.value) return
  const connection = { ...current.value, enabled }
  const credential = selectedCredential.value
  void action(() => credential
    ? api.imSave(channel, document.value!.revision, connection, { credentialId: credential.id, credentialRevision: credential.revision })
    : api.imSave(channel, document.value!.revision, connection), channel, enabled ? 'connect' : 'disconnect')
}
function revoke(index: number) {
  if (!current.value) return
  current.value.peers.splice(index, 1); touch()
}
onMounted(poll)
watch(selected, () => { hideSecret(); message.value = ''; error.value = '' }, { flush: 'sync' })
onBeforeUnmount(() => { disposed = true; hideSecret(); clearTimeout(timer); downloadAbort.abort() })
</script>

<template>
  <section class="im-settings">
    <p class="intro">{{ label('机器人由当前工作区的 Harnex 服务连接，无需公网地址。服务运行期间可接收任务；网络恢复后自动重连。', 'The Harnex service for this workspace connects your bot without a public endpoint. It accepts tasks while running and reconnects when the network recovers.') }}</p>
    <section v-if="document" class="notification-default">
      <template v-if="notifications">
      <label>{{ label('项目默认通知会话', 'Default notification recipient for this project') }}
        <select :value="notifications.defaultRecipient?.id || ''" :disabled="notificationSaving || busy || !!notificationLoadError" @change="chooseNotification">
          <option value="">{{ label('暂不设置', 'Not configured') }}</option>
          <option v-if="notifications.defaultRecipient && !notifications.recipients.some(option => option.recipient.id === notifications!.defaultRecipient!.id)" :value="notifications.defaultRecipient.id" disabled>{{ label('已失效：', 'Unavailable: ') }}{{ notificationRecipientLabel(notifications.defaultRecipient, cn) }}</option>
          <option v-for="option in notifications.recipients" :key="option.recipient.id" :value="option.recipient.id" :disabled="!option.available">{{ notificationRecipientLabel(option.recipient, cn) }} · {{ option.available ? label(option.online ? '已连接' : '未连接', option.online ? 'Connected' : 'Offline') : option.reason }}</option>
        </select>
      </label>
      <p>{{ label('开启 IM 提醒的项目任务会使用此会话。更改仅影响后续启动的任务，正在运行的任务保留原收件方。', 'Project tasks with IM reminders use this recipient. Changes apply to future tasks; running tasks keep their original recipient.') }}</p>
      <p v-if="!notifications.recipients.length">{{ label('请先连接任一平台，并在下方确认会话授权。', 'Connect a platform and approve a conversation below first.') }}</p>
      <p v-if="notificationError || notifications.defaultRecipient && !notifications.valid" class="error" role="alert">{{ notificationError || label('原通知会话已失效，请重新选择。', 'The previous recipient is unavailable. Choose another conversation.') }}</p>
      </template>
      <strong v-else>{{ label('项目默认通知会话', 'Default notification recipient for this project') }}</strong>
      <p v-if="notificationLoadError" class="error" role="alert">{{ label('通知会话暂时无法读取：', 'Unable to load notification recipients: ') }}{{ notificationLoadError }} <button type="button" @click="refresh">{{ label('重试', 'Retry') }}</button></p>
      <p v-else-if="!notifications">{{ label('正在读取通知会话…', 'Loading notification recipients…') }}</p>
    </section>
    <nav class="channel-tabs" :aria-label="label('选择 IM 平台', 'IM platform')">
      <button v-for="channel in channels" :key="channel" :class="{ active: selected === channel }" :aria-pressed="selected === channel" @click="selected = channel">{{ channelName(channel) }}</button>
    </nav>
    <p v-if="error || statusError" role="alert" class="error">{{ error || statusError }} <button type="button" @click="refresh">{{ label('刷新', 'Refresh') }}</button></p>
    <p v-if="message" role="status">{{ message }}</p>
    <p v-if="document?.deliveryError" class="error" role="alert">{{ document.deliveryError }}</p>
    <p v-if="!document && !error && !statusError" role="status">{{ label('正在读取连接设置…', 'Loading connections…') }}</p>
    <template v-if="current && document">
      <p class="workspace-path">{{ label('操作工作区：', 'Workspace: ') }}{{ document.workspace }}</p>
      <p v-if="document.machine" class="workspace-path">{{ label('运行位置：', 'Running on: ') }}{{ document.machine }}</p>
      <header class="connection-status">
        <strong>{{ channelName(selected) }} · {{ stateLabel(connectionState) }}</strong>
        <small v-if="dirty[selected]">{{ label('有未保存的修改', 'Unsaved changes') }}</small>
        <p v-if="status?.detail">{{ status.detail }}</p>
        <p v-if="status?.cardDetail" class="error">{{ status.cardDetail }} · {{ label('文字命令仍可使用。', 'Text commands remain available.') }}</p>
      </header>
      <section v-if="selected === 'dingtalk' || selected === 'feishu'" class="direct-chat-guide">
        <strong>{{ label('推荐私聊：无需建群，也不用 @', 'Recommended: chat directly, without a group or @mention') }}</strong>
        <p v-if="selected === 'dingtalk'">{{ label('在钉钉搜索机器人名称，在“功能”结果中打开会话，即可像聊天一样连续发送任务。', 'Search for the bot by name in DingTalk, open it under Functions, then send tasks directly in the conversation.') }}</p>
        <p v-else>{{ label('在飞书搜索应用机器人名称，打开单聊后直接发送任务；连接成功后也可使用下方入口。', 'Search for the app bot in Feishu and message it directly. Once connected, you can also use the link below.') }}</p>
        <a v-if="feishuChatUrl" :href="feishuChatUrl" target="_blank" rel="noopener noreferrer">{{ label('打开飞书私聊', 'Open Feishu bot chat') }} ↗</a>
        <small>{{ label('首次私聊也需在下方确认授权，群聊授权不会自动带入。找不到机器人时，请检查应用已发布、当前组织和应用可见范围。', 'Approve access below on first contact; group access does not carry into private chats. If the bot is missing, check app publication, your organization and app visibility.') }}</small>
      </section>
      <details class="setup-help">
      <summary>{{ label('首次接入：获取机器人凭据', 'First-time setup: obtain bot credentials') }}</summary>
      <ol class="setup-steps">
        <li v-if="selected === 'dingtalk'">{{ label('在钉钉开放平台创建企业内部应用，开启机器人并选择 Stream 接收模式，发布应用并设置可见范围。', 'Create an internal DingTalk app, enable its robot in Stream mode, publish it and configure its visibility.') }} <a href="https://open.dingtalk.com/" target="_blank" rel="noopener noreferrer">{{ label('打开平台', 'Open platform') }} ↗</a></li>
        <li v-else-if="selected === 'wecom'">{{ label('在企业微信创建智能机器人，选择 API 模式的长连接接入，获取 Bot ID 和 Secret。', 'Create a WeCom AI bot with API long-connection access and obtain its Bot ID and Secret.') }} <a href="https://developer.work.weixin.qq.com/" target="_blank" rel="noopener noreferrer">{{ label('打开平台', 'Open platform') }} ↗</a></li>
        <li v-else-if="selected === 'feishu'">{{ label('在飞书开放平台创建企业自建应用，添加机器人能力，获取 App ID 和 App Secret。', 'Create an internal Feishu app, enable its bot capability, and obtain its App ID and App Secret.') }} <a href="https://open.feishu.cn/app" target="_blank" rel="noopener noreferrer">{{ label('打开平台', 'Open platform') }} ↗</a></li>
        <li v-else>{{ label('在 Telegram 的 @BotFather 中用 /newbot 创建机器人，复制 Bot Token 填入下方。', 'Create a bot with /newbot in Telegram @BotFather and paste its Bot Token below.') }} <a href="https://t.me/BotFather" target="_blank" rel="noopener noreferrer">BotFather ↗</a></li>
        <li>{{ label('填写下方凭据并连接。推荐在机器人单聊窗口直接发送文字；只有群聊需要 @机器人。', 'Enter the credentials and connect. Prefer messaging the bot directly; @mentions are needed only in groups.') }}</li>
        <li>{{ label('首次消息自动申请授权。核对下方的用户、会话和确认码，确认后重新发送任务。', 'First contact requests access automatically. Verify the user, conversation and confirmation code below, approve access, then resend the task.') }}</li>
      </ol>
      </details>
      <form @submit.prevent="save(true)">
        <p v-if="document.credentialError" class="error" role="alert">{{ document.credentialError }}</p>
        <label v-if="savedCredentials.length" class="saved-credentials">{{ label('复用已保存的机器人', 'Reuse a saved bot') }}
          <select :value="selectedCredential?.id || ''" :disabled="busy" @change="chooseCredential">
            <option value="">{{ label('手动填写', 'Enter credentials manually') }}</option>
            <option v-for="credential in savedCredentials" :key="credential.id" :value="credential.id">{{ channelName(credential.channel) }} · {{ credential.clientId }}</option>
          </select>
          <small>{{ label('选择后点击“保存并连接”。仅复用凭据和卡片模板，当前工作区的权限与会话授权独立。', 'Select a bot, then Save and connect. Only credentials and the card template are reused; workspace permissions and conversation grants remain separate.') }}</small>
        </label>
        <p v-else class="credential-hint">{{ label('保存的机器人凭据可在此设备上的其他工作区复用。', 'Saved bot credentials can be reused in other workspaces on this device.') }}</p>
        <div class="fields">
          <label v-if="selected !== 'telegram'">{{ platforms[selected].id }}
            <input :value="current.clientId" autocomplete="off" required :disabled="busy" @input="changeId" />
          </label>
          <label>{{ platforms[selected].secret }}
            <span class="secret-input">
              <input :value="secretValue" :type="secretVisible ? 'text' : 'password'" autocomplete="new-password" required :disabled="busy" @input="changeSecret" />
              <button type="button" :aria-label="secretVisible ? label('隐藏 Secret', 'Hide secret') : label('显示 Secret', 'Show secret')"
                :title="secretVisible ? label('隐藏 Secret', 'Hide secret') : label('显示 Secret', 'Show secret')"
                :aria-pressed="secretVisible" :disabled="busy || revealingSecret" @click="toggleSecret">
                <svg v-if="!secretVisible" aria-hidden="true" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7"><path d="M2.5 12s3.5-6 9.5-6 9.5 6 9.5 6-3.5 6-9.5 6-9.5-6-9.5-6z"/><circle cx="12" cy="12" r="2.5"/></svg>
                <svg v-else aria-hidden="true" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7"><path d="M3 3l18 18M10.6 6.2A10.5 10.5 0 0112 6c6 0 9.5 6 9.5 6a15 15 0 01-2.2 2.8M6.1 6.1C3.8 7.8 2.5 12 2.5 12s3.5 6 9.5 6a9.8 9.8 0 003-.5"/></svg>
              </button>
            </span>
            <small v-if="revealingSecret" role="status">{{ label('正在读取已保存的 Secret…', 'Reading the saved secret…') }}</small>
            <small v-if="secretError" class="error" role="alert">{{ secretError }}</small>
          </label>
        </div>
        <section v-if="selected === 'dingtalk'" class="card-setup">
          <h3>{{ label('按钮审批与点选问答', 'Approval buttons and answer choices') }}</h3>
          <p>{{ document.connections.dingtalk.cardTemplateId ? label('已配置卡片模板。模板可用且权限齐备时，待处理请求将使用按钮卡片。', 'A card template is configured. Requests use cards when the template and permissions are available.') : label('当前使用文字交互。完成以下一次性设置后，可直接点击卡片处理请求。', 'Currently using text interactions. Complete this one-time setup to handle requests with buttons.') }}</p>
          <ol><li>{{ label('为应用开通 Card.Instance.Write 权限（创建、投放和更新互动卡片）。', 'Grant the app Card.Instance.Write permission to create, deliver and update cards.') }}</li>
            <li>{{ label('在钉钉卡片平台新建模板：类型选“消息卡片”，场景选“普通卡片”，关联当前机器人应用。', 'Create a template in DingTalk Card Builder: choose Message Card, the Normal Card scenario, and link the current bot app.') }}</li>
            <li>{{ label('下载模板，在编辑器“更多 → 导入”中导入 JSON，确认预览后保存并发布，再复制模板 ID。', 'Download the template, import its JSON through More → Import in the editor, preview, save and publish it, then copy the template ID.') }}</li></ol>
          <button type="button" :disabled="downloading" @click="downloadTemplate">{{ label('下载卡片模板', 'Download card template') }}</button>
          <label class="template-field">{{ label('卡片模板 ID', 'Card template ID') }}
            <input :value="current.cardTemplateId || ''" autocomplete="off" :disabled="busy" @input="changeTemplate" placeholder="xxxxxxxx.schema" />
          </label>
        </section>
        <section v-else-if="selected === 'feishu'" class="card-setup">
          <h3>{{ label('连接后完成飞书配置', 'Finish setup in Feishu after connecting') }}</h3>
          <ol>
            <li>{{ label('先点击下方“保存并连接”，再到飞书“事件与回调”选择长连接接收事件及回调。', 'Select Save and connect below, then choose long connections for events and callbacks in the Feishu console.') }}</li>
            <li>{{ label('订阅接收消息 im.message.receive_v1，以及新版卡片回传交互 card.action.trigger。', 'Subscribe to im.message.receive_v1 and the card.action.trigger callback.') }}</li>
            <li>{{ label('开通读取单聊、接收群内 @机器人及以应用身份发消息的权限；发布版本并设置可用范围。完成后重新连接。', 'Enable direct messages, group mentions and sending messages as the bot. Publish a version, set its availability, and reconnect.') }}</li>
          </ol>
          <p>{{ label('使用原生消息卡片，无需创建卡片模板。私聊直接发送，群聊需 @当前机器人；回复只发回任务来源会话。', 'Native message cards need no template. Send directly in private chats, or mention this bot in groups. Replies stay in the originating conversation.') }}</p>
        </section>
        <section v-else-if="selected === 'telegram'" class="card-setup">
          <h3>{{ label('长轮询与按钮交互', 'Long polling and buttons') }}</h3>
          <p>{{ label('仅需 Bot Token，无需公网地址、发布应用或卡片模板。私聊可先发送 /start；普通群聊请 @机器人 或使用 /status@机器人用户名。', 'Only a Bot Token is needed; no public URL, app publishing or card template. Send /start privately; in regular groups, mention the bot or use /status@bot_username.') }}</p>
          <p>{{ label('群隐私模式下若收不到普通 @消息，可回复机器人的消息并明确 @它，或在 BotFather 关闭 Group Privacy 后重新加群。暂不接收频道、匿名发送和论坛话题。', 'If group privacy mode hides plain mentions, reply to a bot message and explicitly mention it, or disable Group Privacy in BotFather and add the bot again. Channels, anonymous senders and forum topics are not supported.') }}</p>
          <p>{{ label('同一机器人只能有一处长轮询接入；已有 Webhook 时需先在原服务关闭。网络受限时可在全局设置配置代理。', 'Use one polling connection per bot. Disable any existing webhook in its original service first. Configure a global proxy if your network requires it.') }}</p>
        </section>
        <p v-else>{{ label('企微使用原生按钮卡片，无需另建模板。点击后更新卡片；超时等状态会另发通知，旧按钮不能再执行。', 'WeCom uses native button cards without a custom template. Cards update after clicks; expiry and other state changes are also notified, and stale buttons cannot execute.') }}</p>
        <p v-if="status?.lastCardAction" class="card-action-feedback" role="status">{{ label('最近卡片操作：', 'Latest card action: ') }}{{ status.lastCardAction }}</p>
        <label class="permission"><input :checked="current.allowMutations" type="checkbox" :disabled="busy" @change="changePermission" />
          <span>{{ label('允许已绑定会话请求修改工作区', 'Allow paired conversations to request workspace changes') }}
            <small>{{ label('默认只读。开启后仍遵守工作区权限，必要的审批会发回 IM；每次同意只批准提示中的操作。', 'Read-only by default. Workspace permissions still apply; required approvals return to IM and authorize only the displayed operation.') }}</small>
          </span>
        </label>
        <label class="permission"><input :checked="current.allowYolo || false" type="checkbox" :disabled="busy || !current.allowMutations" @change="changeYolo" />
          <span>{{ label('允许在 IM 中选择 YOLO', 'Allow YOLO selection in IM') }}
            <small>{{ label('开启后，已绑定用户可用 /模式 yolo 自动执行普通工具操作，跳过普通确认；工作区权限和禁用规则仍生效。', 'Paired users can use /mode yolo to run ordinary tools without confirmation. Workspace limits and deny rules still apply.') }}</small></span>
        </label>
        <div class="actions">
          <button class="primary" type="submit" :disabled="busy || !current.clientId.trim() || !current.clientSecret.trim()">{{ pendingAction?.channel === selected && pendingAction.kind === 'connect' ? label('保存并连接中…', 'Saving and connecting…') : label('保存并连接', 'Save and connect') }}</button>
          <button v-if="document.connections[selected].enabled" type="button" :disabled="busy" @click="save(false)">{{ label('保存并断开', 'Save and disconnect') }}</button>
          <button v-if="document.connections[selected].enabled && !dirty[selected]" type="button" :disabled="busy" @click="action(() => api.imReconnect(selected), undefined, 'reconnect')">{{ label('重新连接', 'Reconnect') }}</button>
        </div>
        <div ref="connectionFeedback" class="connection-feedback" :class="{ connected: connectionState === 'CONNECTED', failed: ['ERROR', 'UNKNOWN'].includes(connectionState) }" role="status" aria-live="polite" aria-atomic="true">
          <div class="feedback-heading"><span class="status-dot" :class="{ spinning: connecting }" aria-hidden="true" /><strong>{{ channelName(selected) }} · {{ stateLabel(connectionState) }}</strong></div>
          <p>{{ connectionHint }}</p>
          <p v-if="connectionActionError?.channel === selected" class="error">{{ label('本次操作未成功：', 'This action failed: ') }}{{ connectionActionError.text }}</p>
          <button v-if="connectionState === 'UNKNOWN'" type="button" :disabled="busy" @click="refresh">{{ label('刷新状态', 'Refresh status') }}</button>
        </div>
      </form>
      <section v-if="activeTasks.length" class="active-im-tasks" aria-live="polite">
        <h3>{{ label('正在执行的 IM 任务', 'Active IM tasks') }}</h3>
        <div v-for="task in activeTasks" :key="task.runId" class="peer">
          <div><strong>{{ task.userId }} · {{ task.chatId }}</strong><small>{{ task.progress }}</small>
            <small>{{ task.model }}<template v-if="task.permissionMode"> · {{ task.permissionMode }}</template></small></div>
          <button type="button" @click="router.push('/t/' + encodeURIComponent(task.conversationId))">{{ label('查看执行过程', 'View execution') }}</button>
        </div>
      </section>
      <section class="pairing">
        <h3>{{ label('会话授权', 'Conversation access') }}</h3>
        <p>{{ label('授权限定机器人、用户和会话。只有你在本机确认的来源才能操作工作区。IM 任务一次处理一项，忙时不会排队。', 'Access is scoped to the bot, user and conversation. Only sources approved locally can use this workspace. IM tasks run one at a time; busy requests are not queued.') }}</p>
        <div v-for="request in pairing" :key="request.id" class="peer">
          <div><strong>{{ request.code }} · {{ request.label || request.userId }}</strong><small>{{ label('请与 IM 中的确认码核对', 'Match the code shown in IM') }}</small><small>User: {{ request.userId }} · Chat: {{ request.chatId }}</small></div>
          <button :disabled="busy || dirty[selected]" @click="action(() => api.imPair(request.id, document!.revision))">{{ label('确认授权', 'Approve access') }}</button>
        </div>
        <p v-if="pairing.length === 0 && current.peers.length === 0">{{ label('尚无授权。直接给机器人发一条消息，授权申请会出现在这里。', 'No authorized conversations. Send the bot a message to request access.') }}</p>
        <div v-for="(peer, index) in current.peers" :key="`${peer.userId}:${peer.chatId}`" class="peer">
          <div><strong>{{ peer.label || peer.userId }}</strong><small>User: {{ peer.userId }} · Chat: {{ peer.chatId }}</small></div>
          <button :disabled="busy" @click="revoke(index)">{{ label('移除授权', 'Remove access') }}</button>
        </div>
        <small v-if="dirty[selected]">{{ label('授权修改须点击「保存并连接」才生效。保存后再确认新的授权申请。', 'Save your changes before approving another access request.') }}</small>
      </section>
      <section class="usage">
        <h3>{{ label('在 IM 中使用', 'Using the bot') }}</h3>
        <p>{{ label('/模型 查看已启用模型；/模型 编号 切换；/模型 默认 恢复默认。/强度 查看可选档位，/模式 查看 ask/yolo。设置仅影响你在本会话的下一条任务，模型无法连通时也可使用这些命令。', 'Use /model to list enabled models, /model number to switch, and /model default to reset. /effort lists supported levels; /mode shows ask/yolo. Choices affect your next task in this conversation. These commands work even when a model is unreachable.') }}</p>
        <p>{{ label('直接发文字开始任务；/状态 查看，/取消 停止，/结果 找回上一项结果。审批可点击卡片，或用 /同意 编号、/拒绝 编号。只有一个待回答问题时，可直接回复“2”“选第二个”或答案；多个问题请点击对应卡片或用 /回答 编号 内容。', 'Send text to start a task. Use /status, /cancel and /result to control it. Approve using card buttons or /approve CODE and /deny CODE. With exactly one pending question, reply with a number or your answer; for multiple questions, choose the matching card or use /answer CODE TEXT.') }}</p>
        <p>{{ label('当前支持文字任务；企微也支持平台转写的语音文字。图片和文件请先放入工作区。聊天内容与文件操作可在 Harnex 的 Agent 会话中查看。', 'Text tasks are supported, including WeCom voice transcripts. Place images and files in the workspace first. Review chat history and file operations in Harnex Agent conversations.') }}</p>
      </section>
      <section v-if="problems.length" class="delivery-problems">
        <h3>{{ label('需要关注的回复', 'Delivery issues') }}</h3>
        <p>{{ label('发送结果未知时不会自动重复发送。请在原会话发送 /结果 获取任务结果。', 'Unknown deliveries are not resent automatically. Send /result in the source conversation to retrieve the task result.') }}</p>
        <div v-for="item in problems" :key="item.id" class="delivery"><strong>{{ item.state === 'UNKNOWN' ? label('结果未知', 'Unknown') : label('发送失败', 'Failed') }}</strong><span>{{ item.error }}</span><small>{{ item.userId }} · {{ item.chatId }}</small></div>
      </section>
    </template>
  </section>
</template>

<style scoped>
.im-settings { padding:24px; border:1px solid var(--border); border-radius:12px; background:var(--surface-1); }
p,small,li { color:var(--text-muted); line-height:1.65; } p { margin:8px 0 16px; font-size:13px; }
small { display:block; font-size:12px; overflow-wrap:anywhere; } h3 { margin:0 0 10px; font-size:14px; }
.channel-tabs,.actions,.connection-status { display:flex; gap:10px; flex-wrap:wrap; align-items:center; }
.channel-tabs { margin:18px 0; } button { padding:8px 14px; border:1px solid var(--border); border-radius:7px; background:var(--bg); color:var(--text-primary); cursor:pointer; }
button:disabled { opacity:.5; cursor:not-allowed; } button.active,.primary { border-color:var(--accent); color:var(--accent); }
button:focus-visible,input:focus-visible,select:focus-visible,summary:focus-visible { outline:2px solid var(--accent); outline-offset:2px; }
.notification-default { padding:14px; border:1px solid var(--border); border-radius:8px; margin:16px 0; }
.notification-default label { display:grid; gap:8px; color:var(--text-primary); }
.notification-default select { box-sizing:border-box; width:100%; min-width:0; padding:9px; border:1px solid var(--border); border-radius:6px; background:var(--bg); color:var(--text-primary); }
.notification-default p { margin:8px 0 0; font-size:12px; line-height:1.6; color:var(--text-muted); }
.notification-default p.error { color:var(--danger); }
.setup-help { margin:16px 0; font-size:13px; } summary { cursor:pointer; }
.saved-credentials { display:block; margin-top:18px; }
.saved-credentials select { display:block; box-sizing:border-box; width:100%; min-width:0; margin:7px 0; padding:10px; border:1px solid var(--border); border-radius:6px; background:var(--bg); color:var(--text-primary); }
.saved-credentials small,.credential-hint { color:var(--text-muted); }
.connection-status p { width:100%; margin:0; } .setup-steps { padding-left:20px; font-size:13px; } li { margin:8px 0; } a { color:var(--accent); }
.fields { display:grid; grid-template-columns:repeat(2,minmax(0,1fr)); gap:14px; margin:20px 0; }
label { font-size:13px; } .fields input { box-sizing:border-box; margin-top:7px; padding:10px; width:100%; border:1px solid var(--border); border-radius:6px; background:var(--bg); color:var(--text-primary); }
.secret-input { display:flex; align-items:center; position:relative; margin-top:7px; }
.fields .secret-input input { margin-top:0; padding-right:38px; min-width:0; }
.secret-input button { position:absolute; right:4px; width:28px; height:28px; padding:0; display:grid; place-items:center; border:0; background:transparent; color:var(--text-muted); }
.secret-input button:disabled { cursor:not-allowed; opacity:.45; }
.connection-feedback { margin-top:14px; padding:12px 14px; border:1px solid var(--border); border-radius:8px; background:var(--bg); }
.feedback-heading { display:flex; align-items:center; gap:8px; font-size:13px; } .connection-feedback p { margin:7px 0 0; }
.connection-feedback.connected .feedback-heading { color:var(--success); } .connection-feedback.failed .feedback-heading { color:var(--danger); }
.status-dot { flex:0 0 auto; width:7px; height:7px; border-radius:50%; background:currentColor; }
.status-dot.spinning { width:12px; height:12px; background:transparent; border:2px solid var(--border); border-top-color:var(--accent); animation:im-spin .9s linear infinite; }
@keyframes im-spin { to { transform:rotate(360deg); } } @media(prefers-reduced-motion:reduce) { .status-dot.spinning { animation:none; } }
.permission { display:flex; gap:9px; align-items:flex-start; margin:16px 0; } .permission input { margin-top:3px; flex:0 0 auto; width:14px; height:14px; }
.pairing,.usage,.delivery-problems,.active-im-tasks { border-top:1px solid var(--border); padding-top:22px; margin-top:24px; }
.active-im-tasks strong { overflow-wrap:anywhere; }
.peer { display:flex; gap:14px; justify-content:space-between; align-items:center; margin:12px 0; } .peer>div { min-width:0; } .peer button { flex-shrink:0; }
.delivery { display:flex; flex-direction:column; gap:5px; margin:12px 0; font-size:13px; } .error { color:var(--danger,#d94b55); }
.workspace-path { overflow-wrap:anywhere; }
.direct-chat-guide { padding:14px; border:1px solid var(--border); border-radius:8px; margin:14px 0; font-size:13px; line-height:1.65; }
.direct-chat-guide p { margin:7px 0; }
.direct-chat-guide a { display:inline-block; margin:2px 0 8px; color:var(--accent); }
.direct-chat-guide small { display:block; color:var(--text-muted); line-height:1.6; }
.card-setup { padding:16px; border:1px solid var(--border); border-radius:8px; margin:18px 0; } .card-setup ol { padding-left:20px; font-size:13px; }
.template-field { display:block; margin-top:14px; } .template-field input { box-sizing:border-box; width:100%; padding:10px; margin-top:7px; color:var(--text-primary); background:var(--bg); border:1px solid var(--border); border-radius:6px; }
@media(max-width:600px) { .im-settings { padding:16px; } .fields { grid-template-columns:1fr; } .peer { align-items:flex-start; flex-direction:column; } }
</style>
