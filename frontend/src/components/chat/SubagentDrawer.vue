<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { api, getRuntimeRevision } from '../../api/client'
import type { SemanticActivity } from '../../domain/activity'
import { locale, t } from '../../i18n'
import { errorMessage } from '../../utils/unknown'
import { useRunStore } from '../../stores/run'
import { useConversationStore } from '../../stores/conversation'
import { subagentTimeline, subagentResultText } from '../../domain/subagentTimeline'
import ToolEventView from '../ToolEventView.vue'
import MarkdownBody from './MarkdownBody.vue'
import VirtualTimeline from './VirtualTimeline.vue'
import { activityOutcomeLabel, subagentNeedsAttention, subagentOutcomeExplanation } from '../../domain/subagentStatus'

const props = defineProps<{
  activity?: SemanticActivity | null
  callId?: string | null
}>()
const childOutput = ref('')
const run = useRunStore()
let loadRevision = 0
const childLoading = ref(false)
const childError = ref('')
const conversation = useConversationStore()
const parentRequest = computed(() => {
  for (let index = conversation.messages.length - 1; index >= 0; index--) {
    const message = conversation.messages[index]
    if (message.role === 'user' && message.content?.trim()) return message.content.trim()
  }
  return ''
})

watch(
  () => [props.activity?.runId || run.activeRunId, props.activity?.subagentThreadId, props.activity?.outcome] as const,
  async ([runId, threadId, outcome], _previous, onCleanup) => {
    const revision = ++loadRevision
    const runtimeRevision = getRuntimeRevision()
    let cancelled = false
    onCleanup(() => { cancelled = true })
    const current = () => !cancelled && revision === loadRevision && runtimeRevision === getRuntimeRevision()
    childOutput.value = ''
    childError.value = ''
    childLoading.value = false
    if (!runId || !threadId || outcome === 'running') return
    childLoading.value = true
    try {
      const result = await api.subagentResult(runId, threadId)
      if (current()) childOutput.value = result.output
    } catch (cause: unknown) {
      if (current()) childError.value = errorMessage(cause)
    } finally {
      if (current()) childLoading.value = false
    }
  },
  { immediate: true },
)

const fullResult = computed(() => subagentResultText(childOutput.value || props.activity?.output || props.activity?.summary || ''))
const timeline = computed(() => subagentTimeline(props.activity).map(step => ({ ...step, key: step.id })))

const statusLabel = computed(() => {
  if (!props.activity) return t('chat.subagentWaiting')
  return activityOutcomeLabel(props.activity.outcome)
})
const outcomeExplanation = computed(() => subagentOutcomeExplanation(props.activity?.outcome || ''))

function stepTime(value?: number) {
  if (!value) return ''
  return new Intl.DateTimeFormat(locale.value, {
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
  }).format(new Date(value))
}
function outcomeLabel(value: string) {
  if (value === 'running') return t('tool.running')
  if (value === 'completed') return t('tool.done')
  if (value === 'failed') return t('tool.failed')
  if (value === 'waiting') return t('tool.waiting')
  return value
}
</script>

<template>
  <div :key="activity?.subagentThreadId" class="inspector-body subagent-drawer">
    <section class="subagent-hero" :class="{ attention: subagentNeedsAttention(activity?.outcome || '') }" :data-outcome="activity?.outcome || 'running'">
      <div class="subagent-heading">
        <span class="subagent-pulse" aria-hidden="true" />
        <div>
          <span>{{ t('chat.subagentProcess') }}</span>
          <strong>{{ activity?.title || t('chat.subagentWaiting') }}</strong>
        </div>
        <em>{{ statusLabel }}</em>
      </div>
      <p v-if="!activity">{{ t('chat.subagentWaitingDesc') }}</p>
      <p v-else-if="outcomeExplanation">{{ outcomeExplanation }}</p>
    </section>

    <dl class="subagent-facts">
      <dt>{{ t('chat.subagentThread') }}</dt>
      <dd><code>{{ activity?.subagentThreadId || '—' }}</code></dd>
      <dt>{{ t('chat.subagentCall') }}</dt>
      <dd><code>{{ activity?.delegateCallId || callId || '—' }}</code></dd>
    </dl>

    <section v-if="parentRequest" class="subagent-result">
      <div class="drawer-eyebrow">{{ t('chat.subagentParentRequest') }}</div>
      <pre>{{ parentRequest }}</pre>
    </section>

    <section class="subagent-timeline">
      <div class="drawer-eyebrow">{{ t('chat.subagentTimeline') }}</div>
      <p v-if="!timeline.length && activity?.outcome === 'running'" class="empty-panel">{{ t('chat.subagentNoSteps') }}</p>
      <VirtualTimeline :key="activity?.subagentThreadId" :items="timeline" :estimate="step => step.kind === 'tool' ? 130 : 90">
      <template #default="{ item: step }">
      <article
        class="subagent-step-card" :data-outcome="step.kind === 'tool' ? step.tool.status : step.activity.outcome">
        <span class="subagent-step-dot" aria-hidden="true" />
        <div v-if="step.kind === 'tool'" class="subagent-step-content subagent-tool">
          <time v-if="step.ts">{{ stepTime(step.ts) }}</time>
          <ToolEventView :event="step.tool" :thread-id="activity?.subagentThreadId" />
        </div>
        <div v-else class="subagent-step-content">
          <header>
            <strong>{{ step.activity.title }}</strong>
            <time v-if="step.ts">{{ stepTime(step.ts) }}</time>
          </header>
          <pre v-if="step.activity.summary">{{ step.activity.summary }}</pre>
          <code v-for="target in step.activity.targets" :key="target" class="subagent-target">{{ target }}</code>
        </div>
        <small v-if="step.kind === 'activity'">{{ outcomeLabel(step.activity.outcome) }}</small>
      </article>
      </template>
      </VirtualTimeline>
      <article v-if="activity && !['running', 'waiting'].includes(activity.outcome)"
        class="subagent-step-card subagent-final-result" :data-outcome="activity.outcome">
        <span class="subagent-step-dot" aria-hidden="true" />
        <div class="subagent-step-content">
          <header><strong>{{ t('chat.subagentResult') }}</strong></header>
          <p v-if="childLoading" class="empty-panel">{{ t('settings.loading') }}</p>
          <p v-if="childError" class="empty-panel">{{ childError }}</p>
          <MarkdownBody v-if="fullResult" class="md-content" :source="fullResult" />
        </div>
      </article>
    </section>


  </div>
</template>

<style scoped>
.subagent-drawer { padding:16px 16px 30px; }
.subagent-hero {
  padding:13px;
  border:1px solid color-mix(in srgb, var(--accent) 32%, var(--border));
  border-radius:10px;
  background:linear-gradient(135deg, var(--accent-soft), var(--surface-2));
}
.subagent-hero[data-outcome=failed] { border-color:color-mix(in srgb, var(--danger) 45%, var(--border)); }
.subagent-hero.attention { border-color:color-mix(in srgb, var(--warning) 45%, var(--border)); }
.subagent-hero.attention .subagent-pulse { background:var(--warning); box-shadow:0 0 0 4px var(--warning-soft); }
.subagent-heading { display:grid; grid-template-columns:9px minmax(0,1fr) auto; gap:9px; align-items:start; }
.subagent-pulse {
  width:8px; height:8px; margin-top:5px; border-radius:50%;
  background:var(--accent); box-shadow:0 0 0 4px var(--accent-soft);
}
.subagent-hero[data-outcome=failed] .subagent-pulse { background:var(--danger); box-shadow:0 0 0 4px var(--danger-soft); }
.subagent-heading div { min-width:0; }
.subagent-heading span,.subagent-heading strong { display:block; }
.subagent-heading span { color:var(--text-muted); font-size:12px; text-transform:uppercase; letter-spacing:.07em; }
.subagent-heading strong { margin-top:3px; font-size:12px; line-height:1.45; overflow-wrap:anywhere; }
.subagent-heading em {
  border-radius:999px; background:var(--surface-1); padding:3px 6px;
  color:var(--text-muted); font-size:12px; font-style:normal; text-transform:uppercase;
}
.subagent-hero p { margin:8px 0 0 17px; color:var(--text-muted); font-size:12px; }
.subagent-facts {
  display:grid; grid-template-columns:88px minmax(0,1fr); gap:7px;
  margin:13px 0 20px; padding:0 2px;
}
.subagent-facts dt { color:var(--text-muted); font-size:12px; }
.subagent-facts dd { min-width:0; margin:0; text-align:right; }
.subagent-facts code { display:block; overflow:hidden; font-size:12px; text-overflow:ellipsis; white-space:nowrap; }
.subagent-step-card {
  position:relative;
  display:grid; grid-template-columns:10px minmax(0,1fr) auto; gap:8px;
  padding:9px 1px;
}
.subagent-step-card:not(.subagent-final-result)::after {
  content:""; position:absolute; top:20px; bottom:-4px; left:4px; width:1px; background:var(--border);
}
.subagent-step-dot { position:relative; z-index:1; width:8px; height:8px; margin-top:3px; border:2px solid var(--surface-1); border-radius:50%; background:var(--accent); }
.subagent-step-card[data-outcome=failed] .subagent-step-dot { background:var(--danger); }
.subagent-step-content { min-width:0; }
.subagent-tool,.subagent-final-result .subagent-step-content { grid-column:2 / -1; }
.subagent-tool :deep(.tool-event) { margin:4px 0 0; }
.subagent-step-content header { display:flex; justify-content:space-between; gap:8px; }
.subagent-step-content strong { font-size:12px; }
.subagent-step-content time,.subagent-step-card > small { color:var(--text-faint); font-size:12px; }
.subagent-step-content pre,.subagent-result pre {
  margin:5px 0 0; border:1px solid var(--border); border-radius:7px; background:var(--surface-2);
  padding:8px; font:12px/1.55 var(--font-mono); white-space:pre-wrap; overflow-wrap:anywhere;
}
.subagent-target { display:block; margin-top:4px; color:var(--text-secondary); font-size:12px; overflow-wrap:anywhere; }
.subagent-result { margin-top:20px; }
.subagent-result pre { max-height:none; }
.subagent-message { margin-top:8px; }
.subagent-message > strong { color:var(--text-muted); font-size:12px; text-transform:uppercase; }
</style>
