<script setup lang="ts">
import { ref } from 'vue'
import type { ApprovalPrompt, PlanReviewPrompt, QuestionPrompt } from '../../domain/chat'
import { t } from '../../i18n'
import QuestionCard from './QuestionCard.vue'

const props = defineProps<{
  approvals: ApprovalPrompt[]
  questions: QuestionPrompt[]
  plan: PlanReviewPrompt | null
  resolveApproval: (callId: string, choice: 'allow' | 'deny' | 'whitelist', toolName: string) => Promise<void>
  answerQuestion: (callId: string, value: string) => Promise<void>
  reviewPlan: (callId: string, decision: 'approve' | 'reject', feedback?: string) => Promise<void>
  cancelPlan: () => void
}>()

const planFeedback = ref('')
const submitting = ref(false)

async function submitPlan(decision: 'approve' | 'reject') {
  if (!props.plan || submitting.value) return
  submitting.value = true
  try {
    await props.reviewPlan(
      props.plan.callId,
      decision,
      decision === 'reject' ? planFeedback.value.trim() || undefined : undefined,
    )
    planFeedback.value = ''
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <section v-if="approvals.length || questions.length || plan" id="pending-interactions"
    class="interaction-stack" aria-live="polite">
    <div v-for="p in approvals" :key="p.callId" class="approval-card">
      <div class="approval-head">
        <div class="approval-icon" aria-hidden="true">!</div>
        <span>{{ t('chat.approvalTitle') }}</span>
        <code class="approval-tool">{{ p.toolName }}</code>
      </div>
      <pre class="approval-args">{{ p.arguments }}</pre>
      <div class="approval-actions">
        <button class="btn-approve" @click="resolveApproval(p.callId, 'allow', p.toolName)">{{ t('chat.approvalAllowOnce') }}</button>
        <button v-if="!p.outsideWrite" class="btn-approve-always"
          @click="resolveApproval(p.callId, 'whitelist', p.toolName)">{{ t('chat.approvalAllowAlways') }}</button>
        <button class="btn-deny" @click="resolveApproval(p.callId, 'deny', p.toolName)">{{ t('chat.approvalDeny') }}</button>
      </div>
    </div>

    <div v-if="plan" class="plan-review-card">
      <div class="plan-review-head">
        <div class="approval-icon" aria-hidden="true">✓</div>
        <span>{{ t('chat.planReviewTitle') }}</span>
      </div>
      <pre class="plan-review-body">{{ plan.plan }}</pre>
      <textarea v-model="planFeedback" class="plan-feedback" rows="2" :placeholder="t('chat.planFeedbackPlaceholder')" />
      <div class="approval-actions">
        <button class="btn-approve" :disabled="submitting" @click="submitPlan('approve')">{{ t('chat.planApprove') }}</button>
        <button class="btn-deny" :disabled="submitting" @click="submitPlan('reject')">{{ t('chat.planReject') }}</button>
        <button class="btn-cancel-plan" :disabled="submitting" @click="cancelPlan">{{ t('chat.planCancel') }}</button>
      </div>
    </div>

    <QuestionCard v-for="q in questions" :key="q.callId" :question="q" :answer-question="answerQuestion" />
  </section>
</template>
