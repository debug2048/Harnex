<script setup lang="ts">
import { computed, ref } from 'vue'
import type { Msg, ToolEvent, ApprovalPrompt, PlanReviewPrompt, QuestionPrompt } from '../../domain/chat'
import { groupToolActivities, toolActivityFamily } from '../../domain/toolActivity'
import Message from '../Message.vue'
import MessageAssets from './MessageAssets.vue'
import ImageOperationCard from './ImageOperationCard.vue'
import ToolActivityGroup from './ToolActivityGroup.vue'
import InteractionStack from './InteractionStack.vue'
import { t } from '../../i18n'
import SemanticActivity from './SemanticActivity.vue'
import type { SemanticActivity as Activity } from '../../domain/activity'
import { interleaveActivities } from '../../domain/activityTimeline'
import { groupHistoricalSubagentWaves } from '../../domain/subagentHistory'
import { subagentNeedsAttention } from '../../domain/subagentStatus'
import RunChangesNotice from './RunChangesNotice.vue'
import VirtualTimeline from './VirtualTimeline.vue'
import type { RunChangeSummary } from '../../domain/runChanges'
import { providerErrorPresentation } from '../../domain/providerError'

export type TranscriptItem =
  | { kind: 'message'; key: string; seq?: number; message: Msg; showHeader: boolean; actionable?: boolean }
  | { kind: 'toolGroup'; key: string; seq?: number; events: ToolEvent[] }

const props = defineProps<{
  items: TranscriptItem[]
  threadId?: string
  scopeKey?: string
  workspaceLabel: string
  emptyDescription?: string
  starterPrompts: string[]
  busy: boolean
  handoffEnabled?: boolean
  loading?: boolean
  hiddenCount: number
  hasMore?: boolean
  imageOperationIds?: string[]
  loadCount: number
  liveTools: ToolEvent[]
  liveToolsShowHeader: boolean
  activities: Activity[]
  currentAssistant: string
  currentThinking: string
  currentAssistantShowHeader: boolean
  error: string | null
  incompleteDetail?: string | null
  approvals: ApprovalPrompt[]
  questions: QuestionPrompt[]
  plan: PlanReviewPrompt | null
  runChanges: RunChangeSummary | null
  resolveApproval: (callId: string, choice: 'allow' | 'deny' | 'whitelist', toolName: string) => Promise<void>
  answerQuestion: (callId: string, value: string) => Promise<void>
  reviewPlan: (callId: string, decision: 'approve' | 'reject', feedback?: string) => Promise<void>
  cancelPlan: () => void
}>()

const friendlyError = computed(() => providerErrorPresentation(props.error))
const friendlyIncomplete = computed(() => providerErrorPresentation(props.incompleteDetail))
const liveToolGroups = computed(() => groupToolActivities(props.liveTools))
const representedDelegates = computed(() => {
  const ids = new Set<string>()
  for (const item of props.items) {
    if (item.kind !== 'toolGroup') continue
    for (const event of item.events) {
      if (toolActivityFamily(event) === 'delegate') ids.add(event.id)
    }
  }
  for (const event of props.liveTools) {
    if (toolActivityFamily(event) === 'delegate') ids.add(event.id)
  }
  return ids
})
const visibleActivities = computed(() => props.activities.filter(item => {
  if (['tool', 'approval', 'question', 'plan_review'].includes(item.kind)) return false
  // Provider switches and context compression explain pauses in the current Run. Keep their
  // terminal card on screen after the answer lands, but do not replay them when history is opened.
  if (item.kind === 'provider' || item.kind === 'context_compression') return !item.historical
  return item.kind !== 'subagent' || !item.delegateCallId || !representedDelegates.value.has(item.delegateCallId)
}))
const renderedActivities = computed(() => {
  const visible = visibleActivities.value
  const historicalChildren = new Set(visible
    .filter(item => item.kind === 'subagent' && item.historical).map(item => item.id))
  const batches = groupHistoricalSubagentWaves(visible).map(group => {
    if (group.length === 1) return group[0]
    const completed = group.filter(item => item.outcome === 'completed').length
    const failed = group.filter(item => ['failed', 'cancelled', 'timed_out'].includes(item.outcome)).length
    const attention = group.filter(item => subagentNeedsAttention(item.outcome)).length
    const running = group.filter(item => item.outcome === 'running').length
    return {
      id: `subagent-batch:${group[0].id}`,
      seq: group[0].seq,
      ts: group[0].ts,
      eventType: 'subagent_batch',
      kind: 'subagent_batch',
      title: t('chat.subagentBatchTitle', { n: group.length }),
      summary: t(attention ? 'chat.subagentBatchAttentionSummary' : 'chat.subagentBatchSummary', { completed, failed, running, attention }),
      targets: [],
      outcome: running ? 'running' : failed ? 'failed' : attention ? 'attention' : 'completed',
      historical: true,
      details: group,
    } satisfies Activity
  })
  return [...visible.filter(item => !historicalChildren.has(item.id)), ...batches]
})
const timeline = computed(() => {
  return interleaveActivities(props.items, renderedActivities.value)
})
const element = ref<HTMLElement | null>(null)
const liveOutput = ref<HTMLElement | null>(null)
const virtualTimeline = ref<{
  getItemTop(key: string): number | null
  scrollToKey(key: string, align?: 'center' | 'start'): Promise<boolean>
  syncScrollPosition(): void
} | null>(null)
const turnStartKey = computed(() => {
  let start = 0
  for (let index = timeline.value.length - 1; index >= 0; index--) {
    const entry = timeline.value[index]!
    if (entry.kind === 'item' && entry.item.kind === 'message'
      && entry.item.message.role === 'user' && !entry.item.message.runInputIds?.length) {
      start = index + 1; break
    }
  }
  return timeline.value.slice(start).find(entry => entry.kind === 'item'
    && (entry.item.kind === 'toolGroup' || entry.item.message.role === 'assistant'))?.key
})
const hasLiveOutput = computed(() => Boolean(props.currentAssistant || props.currentThinking
  || props.liveTools.length || props.imageOperationIds?.length))
function getTurnStartTop() {
  if (turnStartKey.value) return virtualTimeline.value?.getItemTop(turnStartKey.value) ?? null
  return hasLiveOutput.value ? liveOutput.value?.getBoundingClientRect().top ?? null : null
}
function syncScrollPosition() { virtualTimeline.value?.syncScrollPosition() }
async function scrollToTurnStart() {
  if (turnStartKey.value) return await virtualTimeline.value?.scrollToKey(turnStartKey.value, 'start') ?? false
  const scroller = element.value?.closest<HTMLElement>('.chat-messages')
  const top = getTurnStartTop()
  if (scroller && top != null) {
    scroller.scrollTop += top - scroller.getBoundingClientRect().top - 16
    syncScrollPosition()
    return true
  }
  return false
}
defineExpose({ element, getTurnStartTop, scrollToTurnStart, syncScrollPosition })
function estimateEntry(entry: (typeof timeline.value)[number]) {
  if (entry.kind === 'activity') return 140
  if (entry.item.kind === 'toolGroup') return 100 + Math.min(entry.item.events.length, 8) * 35
  const message = entry.item.message
  const lines = Math.max(String(message.content || '').split('\n').length, Math.ceil((message.content?.length || 0) / 85))
  return 64 + lines * 20 + (message.contentParts?.filter(part => part.type !== 'TEXT').length || 0) * 280
}

defineEmits<{
  handoff: [uid: string, selection: string]
  loadOlder: []
  retry: [uid: string]
  edit: [uid: string, content: string]
  starter: [prompt: string]
  dismissError: []
  openSubagent: [callId: string, content?: string]
  openChanges: []
}>()
</script>

<template>
  <div ref="element" class="transcript-inner">
    <p v-if="loading && !items.length" class="history-loading" role="status">{{ t('chat.loadingHistory') }}</p>
    <div v-if="!threadId && !busy && !loading && !items.length && !currentAssistant && !currentThinking" class="chat-empty">
      <h2>{{ workspaceLabel }}</h2>
      <p class="muted">{{ emptyDescription || t('chat.workspaceReadyDesc') }}</p>
      <div v-if="starterPrompts.length" class="starter-list">
        <button v-for="prompt in starterPrompts" :key="prompt" type="button" @click="$emit('starter', prompt)">
          <span>{{ prompt }}</span><span aria-hidden="true">→</span>
        </button>
      </div>
    </div>

    <button v-if="hiddenCount || hasMore" class="load-older-btn" :disabled="loading && !hiddenCount" @click="$emit('loadOlder')">
      {{ hiddenCount ? t('chat.loadOlder', { n: loadCount, hidden: hiddenCount }) : t('chat.loadHistory') }}
    </button>

    <VirtualTimeline ref="virtualTimeline" :key="scopeKey || threadId || 'draft'" :items="timeline" :scope-key="scopeKey" :estimate="estimateEntry"><template #default="{ item: entry }">
      <SemanticActivity v-if="entry.kind === 'activity'" :activity="entry.activity"
        @open-subagent="(callId, content) => $emit('openSubagent', callId, content)" />
      <ToolActivityGroup v-else-if="entry.item.kind === 'toolGroup'" :events="entry.item.events"
        :thread-id="threadId"
        @open-subagent="(callId, content) => $emit('openSubagent', callId, content)" />
      <template v-else>
      <Message :role="entry.item.message.role"
        :content="entry.item.message.originalInput || entry.item.message.content"
        :uid="entry.item.message._uid" :reasoning="entry.item.message.reasoning"
        :reasoning-block="entry.item.message._reasoningBlock" :show-header="entry.item.showHeader"
        :actionable="entry.item.actionable" :busy="busy" :channel="entry.item.message.channel"
        :handoff-enabled="handoffEnabled"
        :input-applied="Boolean(entry.item.message.runInputIds?.length)"
        :source="entry.item.message.source" :attachments="entry.item.message.attachments"
        @retry="$emit('retry', $event)"
        @handoff="(uid, selection) => $emit('handoff', uid, selection)"
        @edit="(uid: string, content: string) => $emit('edit', uid, content)" />
      <div v-if="entry.item.message.status && !['PENDING', 'STREAMING', 'COMPLETED'].includes(entry.item.message.status)" class="message-status">{{ t('message.state.' + entry.item.message.status) }}</div>
      <MessageAssets v-if="entry.item.message.role === 'assistant'" :parts="entry.item.message.contentParts" />
      </template>
    </template></VirtualTimeline>

    <div ref="liveOutput" class="transcript-live-output">
    <Message v-if="liveToolsShowHeader" role="assistant" content="" :actionable="false" />
    <ToolActivityGroup v-for="group in liveToolGroups" :key="'live-' + group.events[0].id"
      :events="group.events" :thread-id="threadId"
      @open-subagent="(callId, content) => $emit('openSubagent', callId, content)" />
    <InteractionStack :approvals="approvals" :questions="questions" :plan="plan"
      :resolve-approval="resolveApproval" :answer-question="answerQuestion"
      :review-plan="reviewPlan" :cancel-plan="cancelPlan" />

    <Message v-if="currentAssistant || currentThinking" role="assistant" :content="currentAssistant"
      :streaming="true"
      :reasoning="currentThinking" :show-header="currentAssistantShowHeader" :busy="busy"
      :actionable="false" :copyable="false" />
    <ImageOperationCard v-for="id in imageOperationIds || []" :key="id" :operation-id="id" />
    </div>

    <RunChangesNotice v-if="runChanges" :summary="runChanges" @open="$emit('openChanges')" />

    <div v-if="incompleteDetail" class="run-incomplete-notice" role="status">
      <template v-if="friendlyIncomplete">
        <strong>{{ t(friendlyIncomplete) }}</strong>
        <p>{{ t(`${friendlyIncomplete}Hint`) }}</p>
        <details><summary>{{ t('error.details') }}</summary><p>{{ incompleteDetail }}</p></details>
      </template>
      <template v-else>
        <strong>{{ t('chat.runIncomplete') }}</strong>
        <p>{{ incompleteDetail }}</p>
        <p>{{ t('chat.runIncompleteNext') }}</p>
      </template>
    </div>

    <div v-if="error" :key="error" class="chat-error" role="alert">
      <div v-if="friendlyError" class="chat-error-content">
        <strong class="chat-error-title">{{ t(friendlyError) }}</strong>
        <p class="chat-error-hint">{{ t(`${friendlyError}Hint`) }}</p>
        <details class="chat-error-details">
          <summary>{{ t('error.details') }}</summary>
          <p class="chat-error-msg">{{ error }}</p>
        </details>
      </div>
      <span v-else class="chat-error-msg">{{ error }}</span>
      <button type="button" class="chat-error-x" @click="$emit('dismissError')" :title="t('chat.dismissError')"
        :aria-label="t('chat.dismissError')">×</button>
    </div>
  </div>
</template>
<style src="./Transcript.global.css"></style>
