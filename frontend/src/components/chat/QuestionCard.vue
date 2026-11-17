<script setup lang="ts">
import { computed, ref } from 'vue'
import type { QuestionPrompt } from '../../domain/chat'
import { t } from '../../i18n'
import MarkdownBody from './MarkdownBody.vue'

const props = defineProps<{
  question: QuestionPrompt
  answerQuestion: (callId: string, value: string) => Promise<void>
}>()
const selection = ref(props.question.options.length ? '' : 'custom')
const draft = ref('')
const pending = ref(false)
const submitted = ref(false)
const failed = ref(false)
const answer = computed(() => selection.value === 'custom' ? draft.value.trim()
  : props.question.options.find(option => option.key === selection.value)?.value || '')
const disabled = computed(() => pending.value || submitted.value)

async function submit() {
  if (!answer.value || disabled.value) return
  pending.value = true
  failed.value = false
  try {
    await props.answerQuestion(props.question.callId, answer.value)
    submitted.value = true
  } catch {
    failed.value = true
  } finally {
    pending.value = false
  }
}
function onKeydown(event: KeyboardEvent) {
  if (event.isComposing || event.keyCode === 229) return
  if (event.key === 'Enter' && (event.ctrlKey || event.metaKey)) {
    event.preventDefault()
    void submit()
  }
}
</script>

<template>
  <form class="ask-card" :aria-labelledby="`question-${question.callId}`" :aria-busy="pending" @submit.prevent="submit">
    <div class="ask-heading"><span class="ask-icon" aria-hidden="true">?</span>{{ t('chat.questionTitle') }}</div>
    <div :id="`question-${question.callId}`" class="ask-question"><MarkdownBody :source="question.question" /></div>
    <fieldset class="ask-choices" :disabled="disabled" :aria-labelledby="`question-${question.callId}`">
      <label v-for="option in question.options" :key="option.key" class="ask-option" :class="{ selected: selection === option.key }">
        <input v-model="selection" type="radio" :name="`answer-${question.callId}`" :value="option.key" />
        <span class="ask-option-content">
          <span class="ask-option-title">{{ option.label }}<span v-if="option.recommended" class="ask-recommended">{{ t('chat.questionRecommended') }}</span></span>
          <span v-if="option.description" class="ask-option-description">{{ option.description }}</span>
        </span>
      </label>
      <label v-if="question.allowFreeText && question.options.length" class="ask-option ask-custom" :class="{ selected: selection === 'custom' }">
        <input v-model="selection" type="radio" :name="`answer-${question.callId}`" value="custom" />
        <span class="ask-option-title">{{ t('chat.questionCustom') }}</span>
      </label>
      <textarea v-if="question.allowFreeText && selection === 'custom'" v-model="draft" class="ask-input" rows="3"
        :aria-label="t('chat.questionCustom')" :placeholder="t('chat.questionPlaceholder')" @keydown="onKeydown" />
    </fieldset>
    <p v-if="failed" class="ask-error" role="alert">{{ t('chat.questionFailed') }}</p>
    <div class="ask-footer">
      <span class="ask-hint">{{ selection === 'custom' ? t('chat.questionKeyboardHint') : t('chat.questionSelectionHint') }}</span>
      <button type="submit" class="ask-submit" :disabled="!answer || disabled">
        {{ submitted ? t('chat.questionAnswered') : pending ? t('chat.questionSubmitting') : t('chat.questionSubmit') }}
      </button>
    </div>
  </form>
</template>

<style scoped>
.ask-card { --ask-action: var(--accent); margin: 12px 0; padding: 16px; min-width: 0; border: 1px solid var(--border-strong); border-radius: 12px; background: var(--surface-1); color: var(--text-primary); }
:global([data-theme-mode="light"] .ask-card) { --ask-action: color-mix(in srgb, var(--accent) 94%, var(--text-primary)); }
.ask-heading { display: flex; align-items: center; gap: 8px; color: var(--text-muted); font-size: 12px; font-weight: 600; }
.ask-icon { display: inline-grid; place-items: center; width: 20px; height: 20px; border-radius: 6px; background: var(--accent-soft); color: var(--text-primary); font-size: 13px; }
.ask-question { margin: 10px 0 14px; font-size: 14px; line-height: 1.65; overflow-wrap: anywhere; }
.ask-question :deep(p:first-child) { margin-top: 0; }
.ask-question :deep(p:last-child) { margin-bottom: 0; }
.ask-choices { display: grid; gap: 8px; min-width: 0; margin: 0; padding: 0; border: 0; }
.ask-option { display: flex; align-items: flex-start; gap: 10px; min-width: 0; padding: 11px 12px; border: 1px solid var(--border); border-radius: 8px; background: var(--bg); cursor: pointer; transition: border-color .15s, background .15s; }
.ask-option:hover { border-color: var(--border-strong); background: var(--surface-hover); }
.ask-option.selected { border-color: var(--accent); background: var(--accent-soft); }
.ask-option:focus-within { outline: 2px solid var(--accent); outline-offset: 2px; }
.ask-option input { flex: 0 0 auto; width: 15px; height: 15px; margin: 3px 0 0; accent-color: var(--accent); }
.ask-option-content { display: grid; gap: 4px; min-width: 0; }
.ask-option-title { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; min-width: 0; font-size: 13px; font-weight: 600; line-height: 1.6; overflow-wrap: anywhere; }
.ask-option-description { color: color-mix(in srgb, var(--text-muted) 70%, var(--text-primary)); font-size: 12px; line-height: 1.6; overflow-wrap: anywhere; }
.ask-recommended { flex-shrink: 0; padding: 0 6px; border-radius: 4px; background: var(--accent-soft); color: var(--text-primary); font-size: 10px; font-weight: 500; line-height: 1.8; }
.ask-custom { padding-block: 9px; background: transparent; }
.ask-custom .ask-option-title { font-weight: 400; color: var(--text-secondary); }
.ask-input { box-sizing: border-box; width: 100%; min-height: 82px; max-height: 240px; padding: 10px 12px; border: 1px solid var(--border-strong); border-radius: 8px; background: var(--bg); color: var(--text-primary); font: inherit; font-size: 13px; line-height: 1.6; resize: vertical; }
.ask-input:focus { outline: 2px solid var(--accent); outline-offset: 1px; }
.ask-input::placeholder { color: var(--text-muted); }
.ask-footer { display: flex; justify-content: space-between; align-items: center; gap: 12px; margin-top: 12px; }
.ask-hint { color: var(--text-muted); font-size: 11px; line-height: 1.5; }
.ask-submit { flex-shrink: 0; min-height: 34px; padding: 6px 14px; border: 1px solid transparent; border-radius: 7px; background: var(--ask-action); color: var(--accent-contrast); font-size: 12px; font-weight: 600; cursor: pointer; }
.ask-submit:hover:not(:disabled) { background: var(--accent-hover); }
.ask-submit:disabled { opacity: .45; cursor: default; }
.ask-submit:focus-visible { outline: 2px solid var(--accent); outline-offset: 3px; }
.ask-choices:disabled .ask-option { cursor: default; opacity: .7; }
.ask-error { margin: 10px 0 0; color: color-mix(in srgb, var(--danger) 70%, var(--text-primary)); font-size: 12px; }
@media (max-width: 480px) { .ask-card { padding: 12px; } .ask-option { padding: 10px; } .ask-footer { gap: 8px; } }
</style>
