<script setup lang="ts">
import { computed, ref, nextTick } from 'vue'
import { t } from '../i18n'
import { sanitizeRenderedHtml } from '../utils/markdown'
import MarkdownBody from './chat/MarkdownBody.vue'
import ArtifactThumbnail from './results/ArtifactThumbnail.vue'
import { normalizeMessageAttachments, type MessageAttachmentView } from '../domain/chat'
import { primaryModifierLabel } from '../utils/platform'
import { useSettings } from '../stores/settings'

const props = withDefaults(defineProps<{
  role: string
  content: string
  streaming?: boolean
  /** Set by the parent for messages eligible for retry / edit / delete actions. The
   *  toolbar is hidden on the live-streaming bubble (where uid is not yet stable). */
  uid?: string
  /** Enables actions that mutate conversation state (edit/retry). Historical transcript
   *  entries cannot safely use these because compaction removes their backend rewind index. */
  actionable?: boolean
  handoffEnabled?: boolean
  /** Copy is read-only and remains safe for historical input/output even when mutation
   *  actions are unavailable. Disabled only for the unstable live-streaming bubble. */
  copyable?: boolean
  /** Visual grouping: subsequent assistant chunks in the same turn align under the
   *  first avatar/role row instead of repeating it. */
  showHeader?: boolean
  /** Disable Retry / Edit while a stream is in flight \u2014 the backend will refuse anyway. */
  busy?: boolean
  /** Input channel, displayed as secondary metadata apart from the role label. */
  channel?: string
  inputApplied?: boolean
  /** B4: optional sub-source (IM user id, cron job id, alertname). Tooltip on the badge. */
  source?: string
  /** Reasoning/thinking content from reasoning models (DeepSeek-R1, Kimi, Qwen).
   *  Displayed separately from final content in a collapsible section. */
  reasoning?: string
  /** True when this assistant message is a standalone reasoning/thinking block
   *  (not a final answer). Used to hide action buttons on pure reasoning nodes. */
  reasoningBlock?: boolean
  attachments?: MessageAttachmentView[] | null
}>(), {
  actionable: true,
  handoffEnabled: false,
  copyable: true,
  showHeader: true,
  busy: false,
  channel: '',
  inputApplied: false,
  source: '',
  reasoning: '',
  reasoningBlock: false,
  attachments: () => [],
})

const emit = defineEmits<{
  (e: 'retry', uid: string): void
  (e: 'edit', uid: string, newText: string): void
  (e: 'handoff', uid: string, selection: string): void
}>()

function escapeHtml(s: string) {
  return s
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;')
}
function handoffSelection(event: MouseEvent) {
  if (!props.uid || !props.handoffEnabled || normalizedRole.value !== 'assistant') return
  const selection = document.getSelection(), container = (event.currentTarget as HTMLElement).closest('.message')
  const selected = selection && !selection.isCollapsed && container?.contains(selection.anchorNode) && container.contains(selection.focusNode) ? selection.toString() : ''
  emit('handoff', props.uid, selected)
}

const URL_RE = /https?:\/\/[^\s<>"']+/g
function linkifyPlainText(text: string) {
  let out = ''
  let last = 0
  text.replace(URL_RE, (raw: string, offset: number) => {
    let url = raw
    let tail = ''
    while (/[.,!?;:]$/.test(url)) {
      tail = url.slice(-1) + tail
      url = url.slice(0, -1)
    }
    out += escapeHtml(text.slice(last, offset))
    out += `<a href="${escapeHtml(url)}" target="_blank" rel="noopener noreferrer">${escapeHtml(url)}</a>${escapeHtml(tail)}`
    last = offset + raw.length
    return raw
  })
  out += escapeHtml(text.slice(last))
  return out
}

const userSafeHtml = computed(() => {
  return sanitizeRenderedHtml(linkifyPlainText(props.content || ''))
})

const settings = useSettings()
const roleLabel = computed(() => {
  // Localised via the lightweight i18n module; falls through to the raw role string for any
  // unknown role (e.g. provider-specific custom roles) so we never render "undefined".
  switch (props.role) {
    case 'user': return settings.userRoleName.trim() || t('message.role.user')
    case 'assistant': return settings.assistantRoleName.trim() || t('message.role.assistant')
    case 'tool': return t('message.role.tool')
    case 'system': return t('message.role.system')
    default: return props.role
  }
})

const normalizedRole = computed(() => (props.role || '').trim().toLowerCase())
const sourceChannel = computed(() => props.channel.trim().toLowerCase())
const sourceLabel = computed(() => ({ webui: 'GUI', gui: 'GUI', tui: 'TUI', cli: 'CLI', vscode: 'VS Code',
  dingtalk: 'dingtalk', wecom: 'wecom', feishu: 'feishu', webhook: 'webhook' }[sourceChannel.value] || props.channel.trim()))
const sourceTitle = computed(() => t('message.sourceFrom', { channel: sourceLabel.value })
  + (props.source ? '\n' + t('message.sourceSender', { source: props.source }) : ''))
const hasVisibleContent = computed(() => (props.content || '').trim().length > 0)
const normalizedAttachments = computed(() => normalizeMessageAttachments(props.attachments))
const hasAttachments = computed(() => normalizedAttachments.value.length > 0)
const hasReasoning = computed(() => (props.reasoning || '').trim().length > 0)
const copyTarget = computed(() => {
  // Mixed messages: copy the final answer only; reasoning is an internal chain-of-thought.
  if (hasVisibleContent.value) return props.content || ''
  // Reasoning-only response: apply the copy action to the whole message.
  return props.reasoning || ''
})
const showActions = computed(() => {
  // Reasoning-only nodes are read-only chain-of-thought; do not surface copy/retry/edit.
  if (props.reasoningBlock && !hasVisibleContent.value) return false
  // Show actions if there's content OR reasoning (mixed messages still need copy/retry)
  if (!hasVisibleContent.value && !hasReasoning.value) return false
  if (normalizedRole.value === 'tool' || normalizedRole.value === 'system') return false
  return props.copyable || props.actionable !== false
})

// Reasoning collapse state
const reasoningExpanded = ref(false)
const toggleReasoning = () => { reasoningExpanded.value = !reasoningExpanded.value }

// ---------------- action toolbar state ----------------
const editing = ref(false)
const draftText = ref('')
const editArea = ref<HTMLTextAreaElement | null>(null)
const copied = ref(false)
let copyResetTimer: number | null = null

async function onCopy() {
  if (!props.copyable) return
  // Copy the raw markdown / text the user originally saw \u2014 NOT the sanitised HTML \u2014 so the
  // value pastes cleanly into another chat or a code editor. Mixed messages copy the final
  // answer only; reasoning-only messages copy the thinking text.
  const text = copyTarget.value
  try {
    if (navigator.clipboard?.writeText) {
      await navigator.clipboard.writeText(text)
    } else {
      // Fallback for environments without secure-context clipboard (e.g. http://localhost on
      // some old browsers). Uses an off-screen textarea + execCommand.
      const ta = document.createElement('textarea')
      ta.value = text
      ta.style.position = 'fixed'
      ta.style.opacity = '0'
      document.body.appendChild(ta)
      ta.select()
      try { document.execCommand('copy') } finally { document.body.removeChild(ta) }
    }
    copied.value = true
    if (copyResetTimer) window.clearTimeout(copyResetTimer)
    copyResetTimer = window.setTimeout(() => { copied.value = false }, 1500)
  } catch (e) {
    console.warn('[Message] copy failed:', e)
  }
}

async function onStartEdit() {
  if (!props.uid || props.busy) return
  draftText.value = props.content || ''
  editing.value = true
  await nextTick()
  if (editArea.value) {
    editArea.value.focus()
    // Auto-grow rough heuristic so a long message doesn't open as a tiny textarea.
    editArea.value.style.height = 'auto'
    editArea.value.style.height = Math.min(400, editArea.value.scrollHeight + 4) + 'px'
  }
}

function onCancelEdit() {
  editing.value = false
  draftText.value = ''
}

function onSaveEdit() {
  if (!props.uid) return
  const text = draftText.value.trim()
  if (!text) return
  // If unchanged, treat Save as a plain Retry instead of issuing the same request again.
  if (text === (props.content || '').trim()) {
    emit('retry', props.uid)
  } else {
    emit('edit', props.uid, text)
  }
  editing.value = false
  draftText.value = ''
}

function onEditKey(e: KeyboardEvent) {
  if (e.isComposing || e.keyCode === 229) return
  if (e.key === 'Escape') { e.preventDefault(); onCancelEdit() }
  // Ctrl/Cmd + Enter submits, matching the composer's keymap.
  if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) { e.preventDefault(); onSaveEdit() }
}
const editShortcutModifier = primaryModifierLabel()

function onRetry() {
  if (!props.uid || props.busy) return
  emit('retry', props.uid)
}

function attachmentSize(size?: number) {
  if (!size || size < 1) return ''
  if (size < 1024) return `${size} B`
  if (size < 1024 * 1024) return `${(size / 1024).toFixed(1)} KB`
  return `${(size / 1024 / 1024).toFixed(1)} MB`
}
</script>

<template>
  <div class="message" :data-editing="editing" :class="[role, { 'empty-content': !hasVisibleContent && !hasAttachments, 'no-header': !showHeader }]">
    <div v-if="showHeader" class="message-avatar" :class="[role]">
      <template v-if="role === 'user'">
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><path d="M20 21v-2a4 4 0 00-4-4H8a4 4 0 00-4 4v2"/><circle cx="12" cy="7" r="4"/></svg>
      </template>
      <template v-else-if="role === 'assistant'">
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><path d="M12 2L2 7l10 5 10-5-10-5zM2 17l10 5 10-5M2 12l10 5 10-5"/></svg>
      </template>
      <template v-else>
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><rect x="2" y="7" width="20" height="14" rx="2"/><path d="M16 7V5a2 2 0 00-2-2h-4a2 2 0 00-2 2v2"/></svg>
      </template>
    </div>
    <div v-else class="message-avatar-spacer" aria-hidden="true" />
    <div class="message-body">
      <div v-if="showHeader" class="message-role">
        <span class="role-name" :title="roleLabel">{{ roleLabel }}</span>
        <span v-if="inputApplied && normalizedRole === 'user'" class="input-applied-badge"
          :title="t('chat.inputApplied')">{{ t('chat.steer') }}</span>
        <span v-if="sourceChannel && (normalizedRole === 'user' || normalizedRole === 'system')"
              class="channel-badge"
              :data-channel="sourceChannel"
              :title="sourceTitle">
          <svg v-if="['webui', 'gui', 'vscode'].includes(sourceChannel)" width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" aria-hidden="true"><rect x="3" y="4" width="18" height="13" rx="2"/><path d="M8 21h8M12 17v4"/></svg>
          <svg v-else-if="['tui', 'cli'].includes(sourceChannel)" width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="m5 6 6 6-6 6M13 18h6"/></svg>
          <svg v-else width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M21 11.5a8.4 8.4 0 0 1-.9 3.8 8.5 8.5 0 0 1-7.6 4.7 8.4 8.4 0 0 1-3.8-.9L3 21l1.9-5.7a8.4 8.4 0 0 1-.9-3.8 8.5 8.5 0 0 1 4.7-7.6 8.4 8.4 0 0 1 3.8-.9h.5a8.5 8.5 0 0 1 8 8v.5Z"/></svg>
          <span class="channel-label">{{ sourceLabel }}</span>
        </span>
      </div>

      <!-- Edit mode (user messages only): inline textarea + Save/Cancel buttons. -->
      <template v-if="editing && normalizedRole === 'user'">
        <div class="edit-box">
          <textarea ref="editArea" v-model="draftText" @keydown="onEditKey"
            class="edit-textarea" rows="3" :placeholder="t('chat.editPlaceholder', { modifier: editShortcutModifier })" />
          <div class="edit-actions">
            <button class="msg-action-btn primary" @click="onSaveEdit"
              :disabled="!draftText.trim()">{{ t('chat.editSubmit') }}</button>
            <button class="msg-action-btn" @click="onCancelEdit">{{ t('chat.editCancel') }}</button>
          </div>
        </div>
      </template>

      <template v-else>
        <!-- Reasoning/thinking section (collapsible, no action buttons) -->
        <div v-if="hasReasoning && normalizedRole === 'assistant'" class="reasoning-section">
          <button class="reasoning-toggle" @click="toggleReasoning" :aria-expanded="reasoningExpanded">
            <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor"
              stroke-width="2" stroke-linecap="round" stroke-linejoin="round"
              :style="{ transform: reasoningExpanded ? 'rotate(90deg)' : 'rotate(0deg)' }">
              <polyline points="9 18 15 12 9 6" />
            </svg>
            <span>{{ reasoningExpanded ? t('chat.hideThinking') : t('chat.showThinking') }}</span>
          </button>
          <div v-show="reasoningExpanded" class="reasoning-content">
            <pre>{{ reasoning }}</pre>
          </div>
        </div>

        <!-- Final content -->
        <template v-if="hasVisibleContent">
          <div v-if="normalizedRole === 'user'" class="message-text user-text" v-html="userSafeHtml" />
          <div v-else-if="normalizedRole === 'tool'" class="message-text"><pre style="margin:0;">{{ content }}</pre></div>
          <MarkdownBody v-else class="message-text md-content" :source="content" :state="streaming ? 'streaming' : 'static'" />
        </template>
        <div v-if="normalizedRole === 'user' && hasAttachments" class="message-attachments">
          <span v-for="attachment in normalizedAttachments" :key="attachment.name" class="message-attachment"
            :title="attachment.mime || attachment.name">
            <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor"
              stroke-width="1.8" aria-hidden="true">
              <rect x="3" y="3" width="18" height="18" rx="3" />
              <circle cx="8.5" cy="8.5" r="1.5" />
              <path d="m5 17 4.5-4.5 3 3 2-2L19 18" />
            </svg>
            <ArtifactThumbnail v-if="attachment.artifactId" :id="attachment.artifactId" :name="attachment.name" :mime="attachment.mime" />
            <span v-else>{{ attachment.name }}</span>
            <small v-if="attachmentSize(attachment.size)">{{ attachmentSize(attachment.size) }}</small>
          </span>
        </div>

        <!-- Action buttons: only after final content, not in reasoning section -->
        <div v-if="showActions" class="msg-actions" :class="{ active: copied }">
          <button v-if="normalizedRole === 'assistant' && handoffEnabled && uid && actionable" class="msg-action-btn" @click="handoffSelection">{{ t('handoff.action') }}</button>
          <button v-if="copyable" class="msg-action-btn" @click="onCopy" :title="t('chat.copyTitle')">
            <svg v-if="!copied" width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor"
              stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
              <rect x="9" y="9" width="13" height="13" rx="2" ry="2" />
              <path d="M5 15H4a2 2 0 01-2-2V4a2 2 0 012-2h9a2 2 0 012 2v1" />
            </svg>
            <svg v-else width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor"
              stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
              <polyline points="20 6 9 17 4 12" />
            </svg>
            <span>{{ copied ? t('chat.copied') : t('chat.copy') }}</span>
          </button>
          <button v-if="actionable !== false && normalizedRole === 'user'" class="msg-action-btn" @click="onStartEdit"
            :disabled="busy || !uid" :title="!uid ? 'no uid \u2014 cannot route' : t('chat.editTitle')">
            <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor"
              stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
              <path d="M12 20h9" /><path d="M16.5 3.5a2.121 2.121 0 013 3L7 19l-4 1 1-4L16.5 3.5z" />
            </svg>
            <span>{{ t('chat.edit') }}</span>
          </button>
          <button v-if="actionable !== false && normalizedRole === 'assistant'" class="msg-action-btn" @click="onRetry"
            :disabled="busy || !uid" :title="!uid ? 'no uid \u2014 cannot route' : t('chat.retryTitle')">
            <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor"
              stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
              <polyline points="23 4 23 10 17 10" />
              <path d="M20.49 15a9 9 0 11-2.12-9.36L23 10" />
            </svg>
            <span>{{ t('chat.retry') }}</span>
          </button>
        </div>
      </template>
    </div>
  </div>
</template>

<style scoped>
.message {
  display: flex;
  gap: 12px;
  padding: 18px 0;
  animation: fadeIn .2s ease-out;
}

.message.user {
  flex-direction: row-reverse;
  justify-content: flex-start;
}

.message.assistant,
.message.system {
  background: transparent;
}

.message.empty-content {
  padding-top: 10px;
  padding-bottom: 2px;
}

.message.no-header {
  padding-top: 4px;
}

.message-avatar {
  width: 30px;
  height: 30px;
  border-radius: 8px;
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 13px;
  font-weight: 700;
}

.message-avatar-spacer {
  width: 30px;
  flex-shrink: 0;
}

.message-avatar.user {
  background: var(--bg2);
  border: 1px solid var(--border);
  color: var(--fg2);
}

.message-avatar.assistant {
  background: var(--accent-soft);
  color: var(--accent);
  border: 1px solid color-mix(in srgb, var(--accent) 22%, transparent);
}

.message-avatar.tool { background: var(--warning-soft); color: var(--warning); }

.message-body {
  flex: 1;
  min-width: 0;
  max-width: 100%;
}

.message.user .message-body {
  flex: 0 1 auto;
  max-width: min(76%, 680px);
}

.message-role {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size:12px;
  font-weight: 550;
  letter-spacing: .01em;
  color: var(--muted);
  margin-bottom: 6px;
}

.input-applied-badge { color: var(--text-muted); font-size: 11px; font-weight: 400; }
.channel-badge {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  min-width: 0;
  font-size: 11px;
  font-weight: 400;
  letter-spacing: 0;
  color: var(--text-muted);
  line-height: 1.4;
}
.channel-badge svg { flex-shrink: 0; }
.channel-label {
  max-width: 16ch;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.message.empty-content .message-role {
  margin-bottom: 0;
}

.message.user .message-role {
  justify-content: flex-end;
}
.message.user .channel-badge { order: -1; margin-right: auto; padding-right: 12px; }
.role-name { min-width: 0; overflow-wrap: anywhere; }

.message-text {
  font-size: 15px;
  line-height: 1.8;
  color: var(--text-secondary);
}

.user-text {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  background: var(--chat-background-user, var(--bg2));
  color: var(--text-primary);
  border: 1px solid transparent;
  border-radius: var(--radius-lg);
  padding: 12px 16px;
}
.message-attachments {
  display:flex;
  flex-wrap:wrap;
  justify-content:flex-end;
  gap:6px;
  margin-top:6px;
}
.message-attachment {
  min-width:0;
  max-width:240px;
  min-height:28px;
  display:inline-flex;
  align-items:center;
  gap:6px;
  padding:3px 7px;
  border:1px solid var(--border);
  border-radius:7px;
  background:var(--bg2);
  color:var(--fg2);
  font-size:11px;
}
.message-attachment > span { overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
.message-attachment small { flex:0 0 auto; color:var(--muted); font-size:11px; }

/* Markdown content styles */
.md-content { min-width: 0; overflow-x: auto; overflow-wrap: anywhere; }
.message.assistant .md-content,
.message.system .md-content {
  padding: 14px 16px;
  border-radius: var(--radius-lg);
  background: var(--chat-background-assistant, transparent);
}
.md-content :deep(p) { margin: 0 0 12px; }
.md-content :deep(p:last-child) { margin-bottom: 0; }
.md-content :deep(ul), .md-content :deep(ol) { margin: 4px 0 8px; padding-left: 24px; }
.md-content :deep(li + li) { margin-top: 3px; }
.md-content :deep(li > p) { margin-bottom: 3px; }
.md-content :deep(.task-list-item) { list-style: none; }
.md-content :deep(.task-list-item input) { margin: 0 7px 0 -21px; accent-color: var(--accent); }
.md-content :deep(h1), .md-content :deep(h2), .md-content :deep(h3),
.md-content :deep(h4), .md-content :deep(h5), .md-content :deep(h6) {
  margin: 16px 0 7px; font-weight: 650; line-height: 1.3; color: var(--fg);
}
.md-content :deep(h1) { font-size: 1.45em; }
.md-content :deep(h2) { font-size: 1.28em; }
.md-content :deep(h3) { font-size: 1.14em; }
.md-content :deep(h4), .md-content :deep(h5), .md-content :deep(h6) { font-size: 1em; }
.md-content :deep(pre) {
  max-width: 100%; margin: 10px 0; padding: 10px 12px; overflow-x: auto;
  border: 1px solid var(--border); border-radius: 7px; background: var(--bg2);
  tab-size: 2; white-space: pre;
}
.md-content :deep(pre code) { padding: 0; border: 0; background: transparent; white-space: inherit; }
.md-content :deep(a) { color: var(--accent); text-decoration: underline; text-underline-offset: 2px; }
.md-content :deep(blockquote) {
  border-left: 3px solid var(--accent);
  padding-left: 12px;
  margin: 8px 0;
  color: var(--fg2);
}
.md-content :deep(:not(pre) > code) {
  padding: 1px 4px; border: 1px solid var(--border); border-radius: 4px; background: var(--bg2);
  font-size: .92em;
}
.md-content :deep(table) {
  width: max-content; min-width: 100%; max-width: none; margin: 0;
  border-spacing: 0; border-collapse: separate; border: 1px solid var(--border); border-radius: 7px;
}
.md-content :deep(th), .md-content :deep(td) {
  min-width: 7rem; padding: 7px 10px; border-right: 1px solid var(--border);
  border-bottom: 1px solid var(--border); text-align: left; vertical-align: top;
}
.md-content :deep(th) { background: var(--bg2); color: var(--fg); font-weight: 650; }
.md-content :deep(tr:last-child td) { border-bottom: 0; }
.md-content :deep(th:last-child), .md-content :deep(td:last-child) { border-right: 0; }
.md-content :deep(th[align="center"]), .md-content :deep(td[align="center"]) { text-align: center; }
.md-content :deep(th[align="right"]), .md-content :deep(td[align="right"]) { text-align: right; }
.md-content :deep(hr) { margin: 16px 0; border: 0; border-top: 1px solid var(--border); }
.md-content :deep(img), .md-content :deep(video) { max-width: 100%; height: auto; border-radius: 6px; }
.md-content :deep(details) { margin: 8px 0; padding: 7px 10px; border: 1px solid var(--border); border-radius: 7px; }
.md-content :deep(summary) { cursor: pointer; color: var(--fg); font-weight: 600; }
.md-content :deep(kbd) { padding: 1px 5px; border: 1px solid var(--border); border-bottom-width: 2px; border-radius: 4px; background: var(--bg2); font-size: .88em; }
.md-content :deep(mark) { padding: 0 2px; border-radius: 2px; background: var(--warning-soft); color: var(--fg); }
.md-content :deep(dl) { margin: 8px 0; }
.md-content :deep(dt) { font-weight: 650; }
.md-content :deep(dd) { margin: 2px 0 7px 20px; }
.md-content :deep(figure) { margin: 10px 0; }
.md-content :deep(figcaption) { margin-top: 4px; color: var(--muted); font-size: .9em; }

@keyframes fadeIn { from { opacity: 0; transform: translateY(4px); } to { opacity: 1; transform: translateY(0); } }

@media (max-width: 720px) {
  .message.user .message-body {
    max-width: calc(100% - 44px);
  }
}

/* ---------- per-message action toolbar (copy / edit / retry) ---------- */
.msg-actions {
  display: flex;
  gap: 4px;
  margin-top: 6px;
  opacity: 0;
  pointer-events: none;
  transform: translateY(-2px);
  transition: opacity .12s ease, transform .12s ease;
}
.message:hover .msg-actions,
.message:focus-within .msg-actions,
.msg-actions.active {
  opacity: 1;
  pointer-events: auto;
  transform: translateY(0);
}
.message.user .msg-actions { justify-content: flex-end; }

@media (hover: none) {
  .msg-actions {
    opacity: 1;
    pointer-events: auto;
    transform: none;
  }
}

.msg-action-btn {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 3px 8px;
  font-size:12px;
  line-height: 1.4;
  color: var(--fg2);
  background: var(--bg2);
  border: 1px solid var(--border);
  border-radius: 6px;
  cursor: pointer;
  transition: background .12s ease, color .12s ease, border-color .12s ease;
}
.msg-action-btn:hover:not(:disabled) {
  background: var(--bg3, var(--accent-soft));
  color: var(--fg);
  border-color: color-mix(in srgb, var(--accent) 40%, transparent);
}
.msg-action-btn:disabled {
  opacity: .4;
  cursor: not-allowed;
}
.msg-action-btn.primary {
  color: var(--accent);
  background: var(--accent-soft);
  border-color: color-mix(in srgb, var(--accent) 35%, transparent);
}
.msg-action-btn.primary:hover:not(:disabled) {
  background: color-mix(in srgb, var(--accent) 20%, transparent);
}

/* ---------- inline edit-box for user messages ---------- */
.edit-box {
  display: flex;
  flex-direction: column;
  gap: 6px;
  background: var(--bg2);
  border: 1px solid var(--border);
  border-radius: 10px;
  padding: 8px 10px;
}
.edit-textarea {
  width: 100%;
  min-height: 60px;
  resize: vertical;
  background: var(--bg);
  color: var(--fg);
  border: 1px solid var(--border);
  border-radius: 6px;
  padding: 6px 8px;
  font-family: inherit;
  font-size: 14px;
  line-height: 1.6;
  outline: none;
}
.edit-textarea:focus {
  border-color: var(--accent);
}
.edit-actions {
  display: flex;
  justify-content: flex-end;
  gap: 6px;
}
/* ---------- reasoning / chain-of-thought section (collapsible, no action buttons) ---------- */
.reasoning-section {
  margin: 4px 0 8px;
  border: 1px dashed var(--border-light);
  border-radius: 8px;
  background: var(--bg2);
  overflow: hidden;
}
.reasoning-toggle {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  width: 100%;
  padding: 6px 10px;
  font-size: 12px;
  color: var(--muted);
  background: transparent;
  border: 0;
  cursor: pointer;
  text-align: left;
  transition: color .12s ease, background .12s ease;
}
.reasoning-toggle:hover {
  color: var(--fg2);
  background: var(--bg3, var(--bg2));
}
.reasoning-toggle svg {
  transition: transform .15s ease;
  flex-shrink: 0;
}
.reasoning-content {
  padding: 0 10px 10px;
  color: var(--fg2);
  font-size: 13px;
  line-height: 1.65;
}
.reasoning-content pre {
  margin: 0;
  white-space: pre-wrap;
  word-break: break-word;
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
  color: var(--muted);
}
</style>
