import { defineStore } from 'pinia'
import { ref } from 'vue'
import type { ApprovalPrompt, PlanReviewPrompt, QuestionPrompt } from '../domain/chat'

/** Pending human interaction state. Resolution transport remains in the run controller. */
export const useInteractionStore = defineStore('interaction', () => {
  const pendingApprovals = ref<ApprovalPrompt[]>([])
  const pendingQuestions = ref<QuestionPrompt[]>([])
  const pendingPlanReview = ref<PlanReviewPrompt | null>(null)

  function reset() {
    pendingApprovals.value = []
    pendingQuestions.value = []
    pendingPlanReview.value = null
  }

  return { pendingApprovals, pendingQuestions, pendingPlanReview, reset }
})
