<script setup lang="ts">
import type { PendingSubmission } from '../../../../packages/protocol/workbench'
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import type { UploadInfo } from '../../api/client'
import type { PendingUpload } from '../../composables/useChatUploads'
import type { RunInputView } from '../../api/contracts'
import { t } from '../../i18n'
import { imageFilesFromClipboard } from '../../utils/clipboardFiles'
import ArtifactThumbnail from '../results/ArtifactThumbnail.vue'
import { primaryModifierLabel } from '../../utils/platform'
import { useSettings } from '../../stores/settings'

export interface CompletionItem {
  label: string
  insert: string
  hint?: string
}

export interface ComposerModel {
  id: string
  ref: string
  label: string
  provider: string
  tier?: string
  description?: string
}

const props = defineProps<{
  modelValue: string
  agentEnabled?: boolean
  streaming: boolean
  submitting: boolean
  canSubmit: boolean
  uploading: number
  uploadError: string | null
  attachments: UploadInfo[]
  pendingAttachments: PendingUpload[]
  uploadSupported: boolean
  contexts: string[]
  completionItems: CompletionItem[]
  completionIndex: number
  showCompletion: boolean
  selectedModel: string
  selectedReasoningEffort: string
  reasoningEfforts: string[]
  groupedModels: Array<{ provider: string; models: ComposerModel[] }>
  providerAvailable: boolean
  modelsLoading?: boolean
  modelsError?: string | null
  modelSelectionNotice?: string
  permissionMode: 'ask' | 'accept-edits' | 'yolo'
  formatSize: (size: number) => string
  runInputs: RunInputView[]
  submissions?: PendingSubmission[]
}>()

const emit = defineEmits<{
  'update:modelValue': [value: string]
  'update:completionIndex': [value: number]
  'update:selectedModel': [value: string]
  dismissModelSelectionNotice: []
  'update:selectedReasoningEffort': [value: string]
  'update:permissionMode': [value: 'ask' | 'accept-edits' | 'yolo']
  input: []
  keydown: [event: KeyboardEvent]
  editorPointer: []
  completion: [item: CompletionItem]
  files: [files: File[]]
  removeAttachment: [artifactId: string]
  retryUpload: [id: string]
  removeUpload: [id: string]
  dismissUploadError: []
  openContext: []
  submit: []
  followUp: []
  stop: []
  withdrawInput: [inputId: string]
  withdrawSubmission: [submissionId: string]
  configureProvider: []
  retryModels: []
}>()

const fileInput = ref<HTMLInputElement | null>(null)
const textarea = ref<HTMLTextAreaElement | null>(null)
function focus() { textarea.value?.focus() }
defineExpose({ focus })
const actionsRoot = ref<HTMLElement | null>(null)
const actionsTrigger = ref<HTMLButtonElement | null>(null)
const queueAction = ref<HTMLButtonElement | null>(null)
const actionsOpen = ref(false)
const settings = useSettings()
const sendShortcut = computed(() => settings.sendShortcut === 'enter' ? 'Enter' : `${primaryModifierLabel()}+Enter`)
const newlineShortcut = computed(() => settings.sendShortcut === 'enter' ? 'Shift+Enter' : 'Enter')
const shortcutHint = computed(() => t('chat.composeShortcutHint', { send: sendShortcut.value, newline: newlineShortcut.value }))
const placeholder = computed(() => `${t(props.streaming ? 'chat.composeRunningPlaceholder' : props.agentEnabled ? 'chat.composePlaceholder' : 'chat.conversationPlaceholder')}\n${shortcutHint.value}`)
const hasDraft = computed(() => Boolean(props.modelValue.trim() || props.attachments.length
  || props.pendingAttachments.length || props.uploading))
const showStop = computed(() => props.streaming && !hasDraft.value && !props.submitting)
const primaryTitle = computed(() => props.submitting ? t('chat.sending')
  : showStop.value ? t('chat.stopTitle')
    : `${props.streaming ? t(props.agentEnabled ? 'chat.steerTitle' : 'chat.followUpTitle') : t('chat.send')} (${sendShortcut.value})`)
const queueShortcut = computed(() => `${primaryModifierLabel()}+${settings.sendShortcut === 'ctrl-enter' ? 'Shift+' : ''}Enter`)
const visibleRunInputs = computed(() => props.runInputs
  .filter(item => ['QUEUED', 'APPLYING', 'HELD', 'REJECTED'].includes(item.status)
    || (item.status === 'CANCELLED' && item.terminalReason === 'source_run_terminal')))

function inputStatus(item: RunInputView) {
  if (item.status === 'HELD') return t('chat.inputHeld')
  if (item.status === 'REJECTED') return t('chat.inputRejected')
  if (item.status === 'CANCELLED') return t('chat.inputNotApplied')
  if (item.status === 'APPLYING') return t(item.mode === 'STEER' ? 'chat.inputApplying' : 'chat.inputStarting')
  return t(item.mode === 'STEER' ? 'chat.inputQueued' : 'chat.inputWaiting')
}

function closeActions(restoreFocus = false) {
  actionsOpen.value = false
  if (restoreFocus) actionsTrigger.value?.focus()
}

async function toggleActions() {
  actionsOpen.value = !actionsOpen.value
  if (actionsOpen.value) {
    await nextTick()
    queueAction.value?.focus()
  }
}

function queueMessage() {
  if (!props.streaming || !props.canSubmit) return
  closeActions()
  emit('followUp')
  textarea.value?.focus()
}

function onOutsidePointer(event: PointerEvent) {
  if (event.target instanceof Node && !actionsRoot.value?.contains(event.target)) closeActions()
}

function onEditorKey(event: KeyboardEvent) {
  if (event.isComposing || event.keyCode === 229) return
  if (event.key === 'Escape' && actionsOpen.value) {
    event.preventDefault()
    event.stopPropagation()
    closeActions()
    return
  }
  emit('keydown', event)
}

function onActionsFocusOut(event: FocusEvent) {
  if (!(event.relatedTarget instanceof Node) || !actionsRoot.value?.contains(event.relatedTarget)) closeActions()
}

function resizeEditor() {
  if (!textarea.value) return
  textarea.value.style.height = 'auto'
  textarea.value.style.height = `${Math.min(textarea.value.scrollHeight, 220)}px`
}

watch(() => props.modelValue, resizeEditor, { flush: 'post' })
watch(() => props.streaming, () => closeActions())
onMounted(() => { document.addEventListener('pointerdown', onOutsidePointer); resizeEditor() })
onUnmounted(() => document.removeEventListener('pointerdown', onOutsidePointer))

function onFiles(event: Event) {
  const input = event.target as HTMLInputElement
  if (input.files?.length) emit('files', Array.from(input.files))
  input.value = ''
}

function onPaste(event: ClipboardEvent) {
  if (!props.uploadSupported) return
  const files = imageFilesFromClipboard(event.clipboardData)
  if (!files.length) return
  // A clipboard image has no useful textual insertion. Prevent the browser from
  // inserting an object replacement character/data URL, then reuse the normal
  // attachment flow. Text-only clipboard payloads never enter this branch.
  event.preventDefault()
  emit('files', files)
}

function setMode(event: Event) {
  emit('update:permissionMode', (event.target as HTMLSelectElement).value as 'ask' | 'accept-edits' | 'yolo')
}
</script>

<template>
  <div class="chat-input-area">
    <slot name="progress" />
    <div v-if="streaming" class="composer-run-bar">
      <span class="composer-run-label"><span class="composer-run-dot" aria-hidden="true" />{{ t(agentEnabled ? 'chat.agentRunning' : 'chat.generating') }}</span>
      <button v-if="!showStop" type="button" class="composer-stop-link"
        :title="t('chat.stopTitle')" @click="$emit('stop')">{{ t('chat.stop') }} <kbd>Esc</kbd></button>
      <span v-else class="composer-run-hint">{{ t('chat.composeRunningHint') }}</span>
    </div>
    <div v-if="submissions?.some(item => ['QUEUED', 'HELD', 'STARTING'].includes(item.state || ''))" class="run-input-strip" aria-live="polite">
      <div v-for="item in submissions.filter(item => ['QUEUED', 'HELD', 'STARTING'].includes(item.state || ''))" :key="item.submissionId" class="run-input-chip">
        <span>{{ t('submission.' + item.state) }} · {{ item.request?.input }}</span>
        <small v-if="item.reason">{{ item.reason }}</small>
        <button v-if="item.state !== 'STARTING' && item.submissionId" @click="$emit('withdrawSubmission', item.submissionId)">{{ t('submission.withdraw') }}</button>
      </div>
    </div>

    <div v-if="visibleRunInputs.length" class="run-input-strip" role="status" aria-live="polite">
      <div v-for="item in visibleRunInputs" :key="item.inputId" class="run-input-chip"
        :class="['input-' + item.status.toLowerCase(), 'mode-' + item.mode.toLowerCase()]"
        :title="[item.text, ...item.attachmentIds].filter(Boolean).join('\n')">
        <span class="run-input-mode">{{ item.mode === 'STEER' ? t('chat.steer') : t('chat.followUp') }}</span>
        <span class="run-input-text">{{ item.text || item.attachmentIds.join(', ') }}</span>
        <span class="run-input-status">{{ inputStatus(item) }}</span>
        <button v-if="item.status === 'QUEUED' || item.status === 'HELD'" type="button" class="run-input-withdraw"
          :title="t('chat.withdrawInput')" :aria-label="t('chat.withdrawInput')"
          @click="$emit('withdrawInput', item.inputId)">×</button>
      </div>
    </div>
    <div v-if="!providerAvailable && !streaming && !modelsLoading && !modelsError" class="provider-empty" role="status">
      <span><strong>{{ t('chat.providerMissing') }}</strong>{{ t('chat.providerMissingDesc') }}</span>
      <button type="button" @click="$emit('configureProvider')">{{ t('chat.configureProvider') }}</button>
    </div>
    <div v-if="modelsError" class="provider-empty" role="status">
      <span>{{ t('chat.modelsLoadFailed') }}</span><button type="button" @click="$emit('retryModels')">{{ t('common.retry') }}</button>
    </div>
    <div v-if="modelSelectionNotice" class="model-selection-notice" role="status">
      <span>{{ modelSelectionNotice }}</span>
      <button type="button" :aria-label="t('chat.dismissError')" @click="$emit('dismissModelSelectionNotice')">×</button>
    </div>
    <div class="compose-shell">
      <div v-if="attachments.length || pendingAttachments.length" class="attach-strip">
        <div v-for="a in attachments" :key="a.artifactId" class="attach-chip"
          :title="`${a.name} · ${formatSize(a.size)}`">
          <ArtifactThumbnail :id="a.artifactId" :name="a.name" :mime="a.mime" />
          <button type="button" class="attach-x" @click="$emit('removeAttachment', a.artifactId)"
            :title="t('chat.attachRemove')">×</button>
        </div>
        <div v-for="a in pendingAttachments" :key="a.id" class="attach-chip pending"
          :title="`${a.name} · ${formatSize(a.size)}`">
          <span v-if="!a.error" class="attach-spinner" aria-hidden="true" />
          <span v-else :title="a.error" aria-hidden="true">!</span>
          <span class="attach-name">{{ a.name }}</span>
          <button v-if="a.error" type="button" @click="$emit('retryUpload', a.id)">{{ t('preview.retryUpload') }}</button>
          <button type="button" class="attach-x" @click="$emit('removeUpload', a.id)" :aria-label="t('preview.removeUpload')">×</button>
        </div>
      </div>
      <div v-if="uploadError" class="upload-error" role="alert">
        <span>{{ uploadError }}</span>
        <button type="button" :aria-label="t('chat.dismissUploadError')"
          @click="$emit('dismissUploadError')">×</button>
      </div>
      <div v-if="agentEnabled && showCompletion" class="slash-completion">
        <button v-for="(item, index) in completionItems" :key="item.label" type="button"
          class="slash-item" :class="{ active: index === completionIndex }"
          @mousedown.prevent="$emit('completion', item)"
          @mouseenter="$emit('update:completionIndex', index)">
          <span class="slash-label">{{ item.label }}</span>
          <span v-if="item.hint" class="slash-hint">{{ item.hint }}</span>
        </button>
      </div>
      <div v-if="agentEnabled && contexts.length" class="context-chips" @click="$emit('openContext')">
        <button v-for="path in contexts" :key="path" type="button" class="context-chip" :title="path">
          <svg width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7">
            <path d="M6 3h9l3 3v15H6zM15 3v4h4" />
          </svg>
          <span>{{ path.split('/').pop() }}</span>
        </button>
      </div>
      <textarea ref="textarea" :value="modelValue"
        :title="`${shortcutHint}\n${t('chat.inputHistoryHint')}`" @pointerdown="$emit('editorPointer')"
        @input="$emit('update:modelValue', ($event.target as HTMLTextAreaElement).value); $emit('input')"
        @keydown="onEditorKey" @paste="onPaste" :aria-label="t('chat.messageInput')"
        :placeholder="placeholder" rows="1" />
      <div class="compose-toolbar">
        <div class="compose-left">
          <button v-if="uploadSupported" class="compose-btn attach"
            :disabled="uploading > 0"
            @click="fileInput?.click()" :title="t('chat.attachBtnTitle')">
            <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7">
              <path d="M8.5 12.5l5.7-5.7a3 3 0 114.2 4.2l-8.1 8.1a5 5 0 01-7.1-7.1l8.5-8.5" />
            </svg>
          </button>
          <input v-if="uploadSupported" ref="fileInput" type="file" multiple class="file-hidden"
            @change="onFiles" />
          <select :value="selectedModel" class="opt-select composer-model-select"
            :disabled="modelsLoading && !groupedModels.length"
            :title="t('chat.modelOverrideTitle')"
            @change="$emit('update:selectedModel', ($event.target as HTMLSelectElement).value)">
            <option value="">{{ t(modelsLoading && !groupedModels.length ? 'chat.modelsLoading' : 'chat.modelDefault') }}</option>
            <optgroup v-for="group in groupedModels" :key="group.provider" :label="group.provider">
              <option v-for="model in group.models" :key="model.ref" :value="model.ref"
                :title="model.description || model.id">
                {{ model.label }}
              </option>
            </optgroup>
            <option v-if="selectedModel && !groupedModels.some(g => g.models.some(m => m.ref === selectedModel))"
              :value="selectedModel" disabled>{{ selectedModel }} · {{ t('chat.modelUnavailable') }}</option>
          </select>
          <select v-if="reasoningEfforts.length" :value="selectedReasoningEffort" class="opt-select"
            :disabled="!providerAvailable && !streaming" :title="t('chat.reasoningEffortTitle')"
            @change="$emit('update:selectedReasoningEffort', ($event.target as HTMLSelectElement).value)">
            <option value="auto">{{ t('chat.reasoningEffortAuto') }}</option>
            <option v-for="effort in reasoningEfforts" :key="effort" :value="effort">
              {{ t('chat.reasoningEffortValue', { effort }) }}
            </option>
          </select>
          <select v-if="agentEnabled" class="mode-select composer-mode" :value="permissionMode"
            :disabled="!providerAvailable && !streaming" @change="setMode" :title="t('chat.permModeTitle')">
            <option value="ask">{{ t('chat.permissionAsk') }}</option>
            <option value="accept-edits">{{ t('chat.permissionEdit') }}</option>
            <option value="yolo">{{ t('chat.permissionAuto') }}</option>
          </select>
        </div>
        <div class="compose-actions">
          <div v-if="streaming" ref="actionsRoot" class="composer-more"
            @focusout="onActionsFocusOut" @keydown.esc.stop.prevent="closeActions(true)">
            <button ref="actionsTrigger" type="button" class="compose-btn composer-more-trigger"
              :title="t('chat.sendOptions')" :aria-label="t('chat.sendOptions')"
              :aria-expanded="actionsOpen" @click="toggleActions">
              <svg width="16" height="16" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true">
                <circle cx="5" cy="12" r="1.7" /><circle cx="12" cy="12" r="1.7" /><circle cx="19" cy="12" r="1.7" />
              </svg>
            </button>
            <div v-if="actionsOpen" class="composer-actions-popover" :aria-label="t('chat.sendOptions')">
              <button ref="queueAction" type="button" class="composer-queue-action" :disabled="!canSubmit"
                :title="t('chat.followUpTitle')" @click="queueMessage">
                <span>{{ t('chat.followUp') }}<small>{{ t('chat.followUpTitle') }}</small></span>
                <kbd>{{ queueShortcut }}</kbd>
              </button>
            </div>
          </div>
          <button type="button" class="compose-btn composer-primary" :class="showStop ? 'stop' : 'send'"
            :disabled="!showStop && !canSubmit" :title="primaryTitle" :aria-label="primaryTitle"
            :aria-busy="submitting" @click="showStop ? $emit('stop') : $emit('submit')">
            <span v-if="submitting" class="composer-submit-spinner" aria-hidden="true" />
            <svg v-else-if="showStop" width="14" height="14" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true">
              <rect x="5" y="5" width="14" height="14" rx="2" />
            </svg>
            <svg v-else width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true">
              <path d="M12 19V5M6 11l6-6 6 6" />
            </svg>
          </button>
        </div>
      </div>
    </div>
  </div>
</template>
<style src="./Composer.global.css"></style>
