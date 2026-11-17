<script setup lang="ts">
import { computed, ref } from 'vue'
import type { GoalProgress } from '../../domain/chat'
import { t } from '../../i18n'

export interface TaskItem {
  id: number
  title: string
  status: string
  progress?: string
}

const props = defineProps<{
  todos?: TaskItem[]
  expanded?: boolean
  summary?: string
  progress?: number
  activeTitle?: string
  activeStatus?: string
  goal?: GoalProgress | null
  goalLabel?: string
  tokenLabel?: string
}>()

const emit = defineEmits<{
  toggle: []
  pauseGoal: []
  resumeGoal: []
  cancelGoal: []
  confirmGoal: []
  feedbackGoal: [feedback: string]
}>()
const feedback = ref('')
const showFeedback = ref(false)
const status = computed(() => props.goal?.status || '')
const canPause = computed(() => ['RUNNING', 'QUEUED', 'WAITING_RETRY'].includes(status.value))
const canResume = computed(() => ['PAUSED', 'BLOCKED', 'BUDGET_LIMITED', 'INTERRUPTED'].includes(status.value))
const canReview = computed(() => status.value === 'READY_FOR_REVIEW')
const canCancel = computed(() => Boolean(props.goal?.runId)
  && !['CANCEL_REQUESTED', 'COMPLETED', 'FAILED', 'CANCELLED', 'EXPIRED'].includes(status.value))
function submitFeedback() {
  const value = feedback.value.trim()
  if (!value) return
  emit('feedbackGoal', value)
  feedback.value = ''
  showFeedback.value = false
}
</script>

<template>
  <div v-if="todos?.length" class="todo-dock" :class="{ expanded }">
    <button class="todo-dock-head" @click="$emit('toggle')">
      <div class="todo-dock-title">
        <span class="todo-status-dot" :class="activeStatus" />
        <span>{{ t('chat.inspectorTodos') }}</span>
        <strong>{{ summary }}</strong>
      </div>
      <div class="todo-dock-current">{{ activeTitle || t('chat.todosEmpty') }}</div>
      <span class="todo-chevron">{{ expanded ? '⌄' : '›' }}</span>
    </button>
    <div class="todo-progress"><span :style="{ width: `${progress || 0}%` }" /></div>
    <ul v-if="expanded" class="todo-list compact">
      <li v-for="item in todos" :key="item.id" :class="['todo-item', item.status]">
        <span class="todo-mark">{{ item.status === 'completed' ? '✓' : item.status === 'in-progress' ? '•' : '○' }}</span>
        <span class="todo-title"><span>{{ item.title }}</span><small v-if="item.progress">{{ item.progress }}</small></span>
      </li>
    </ul>
  </div>

  <div v-if="goal" class="goal-banner" :class="{ done: goal.done }">
    <div class="goal-summary">
      <span class="goal-dot" :class="goal.status.toLowerCase()" />
      <span class="goal-text" :title="goal.objective">
        {{ goalLabel }} · {{ t('chat.goalTurns', { used: goal.turnsUsed, max: goal.maxTurns || '∞' }) }}
        <template v-if="goal.maxModelCalls"> · {{ t('chat.goalModelCalls', { used: goal.modelCalls || 0, max: goal.maxModelCalls }) }}</template>
        <template v-if="goal.maxToolCalls"> · {{ t('chat.goalToolCalls', { used: goal.toolCalls || 0, max: goal.maxToolCalls }) }}</template>
        <template v-if="goal.tokenBudget || goal.tokensUsed"> · {{ tokenLabel }}/{{ goal.tokenBudget || '∞' }} tok</template>
        <template v-if="goal.costBudget"> · {{ goal.costKnown ? `$${(goal.costUsed || 0).toFixed(4)}` : t('chat.goalCostUnavailable') }}/{{ `$${goal.costBudget}` }}</template>
      </span>
      <span v-if="goal.reason" class="goal-reason">{{ goal.reason }}</span>
      <div class="goal-actions">
        <button v-if="canPause" type="button" @click="$emit('pauseGoal')">{{ t('chat.goalPause') }}</button>
        <button v-if="canResume" type="button" @click="$emit('resumeGoal')">{{ t('chat.goalResume') }}</button>
        <button v-if="canReview" type="button" class="primary" @click="$emit('confirmGoal')">{{ t('chat.goalConfirm') }}</button>
        <button v-if="canReview" type="button" @click="showFeedback = !showFeedback">{{ t('chat.goalFeedback') }}</button>
        <button v-if="canCancel" type="button" class="danger" @click="$emit('cancelGoal')">{{ t('chat.goalCancel') }}</button>
      </div>
    </div>
    <ul v-if="goal.criteria?.length" class="goal-criteria" :aria-label="t('chat.goalCriteria')">
      <li v-for="criterion in goal.criteria" :key="criterion.criterionId" :class="criterion.status?.toLowerCase()">
        <span>{{ criterion.status === 'PASS' ? '✓' : criterion.status === 'FAIL' ? '!' : criterion.status === 'REVIEW_REQUIRED' ? '?' : '○' }}</span>
        <span :title="criterion.detail">{{ criterion.criterionId }}</span>
        <small>{{ criterion.type }}</small>
      </li>
    </ul>
    <form v-if="showFeedback" class="goal-feedback" @submit.prevent="submitFeedback">
      <input v-model="feedback" :placeholder="t('chat.goalFeedbackPlaceholder')" />
      <button type="submit" :disabled="!feedback.trim()">{{ t('chat.goalSendFeedback') }}</button>
    </form>
  </div>
</template>
