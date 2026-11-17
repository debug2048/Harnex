<script setup lang="ts">
import { ref } from 'vue'
import type { SemanticActivity } from '../../domain/activity'
import { t } from '../../i18n'
import { activityOutcomeLabel as outcomeLabel, subagentNeedsAttention } from '../../domain/subagentStatus'
const props = defineProps<{ activity: SemanticActivity }>()
const emit = defineEmits<{ openSubagent: [callId: string, content?: string] }>()
const expanded = ref(false)
const isSubagent = () => props.activity.kind === 'subagent' || props.activity.kind === 'subagent_batch'
const isBatch = () => props.activity.kind === 'subagent_batch'
function openBatchChild(step: SemanticActivity) {
  emit('openSubagent', step.delegateCallId || '', step.subagentThreadId ? `thread: ${step.subagentThreadId}` : step.summary)
}
</script>

<template>
  <article class="semantic-activity" :class="{ subagent: isSubagent(), attention: subagentNeedsAttention(activity.outcome) }" :data-outcome="activity.outcome">
    <span class="activity-mark" aria-hidden="true">{{ activity.outcome === 'failed' ? '!' : activity.outcome === 'waiting' ? '?' : '·' }}</span>
    <div>
      <header>
        <button v-if="isSubagent()" type="button" class="subagent-toggle"
          :aria-expanded="expanded" @click="expanded = !expanded">
          <span>{{ expanded ? '▾' : '▸' }}</span><strong>{{ activity.title }}</strong>
        </button>
        <strong v-else>{{ activity.title }}</strong>
        <span>{{ outcomeLabel(activity.outcome) }}</span>
      </header>
      <p v-if="activity.summary">{{ activity.summary }}</p>
      <code v-for="target in activity.targets" :key="target">{{ target }}</code>
      <div v-if="isSubagent() && expanded" class="subagent-details">
        <p v-if="!activity.details?.length" class="subagent-empty">{{ t('chat.subagentNoSteps') }}</p>
        <button v-if="isBatch()" v-for="step in activity.details" :key="step.id" type="button"
          class="subagent-step batch-child" :data-outcome="step.outcome"
          @click="openBatchChild(step)">
          <span class="step-dot" />
          <span><strong>{{ step.title }}</strong><p v-if="step.summary">{{ step.summary }}</p></span>
          <small>{{ outcomeLabel(step.outcome) }}</small>
        </button>
        <div v-else v-for="step in activity.details" :key="step.id" class="subagent-step" :data-outcome="step.outcome">
          <span class="step-dot" />
          <div><strong>{{ step.title }}</strong><p v-if="step.summary">{{ step.summary }}</p></div>
          <small>{{ outcomeLabel(step.outcome) }}</small>
        </div>
        <footer v-if="activity.subagentThreadId">{{ t('chat.subagentThread') }} · <code>{{ activity.subagentThreadId }}</code></footer>
      </div>
    </div>
  </article>
</template>

<style scoped>
.semantic-activity { display:flex; gap:10px; margin:8px 0 8px var(--conversation-inset); padding:12px 14px; border:1px solid var(--border); border-radius:12px; background:color-mix(in srgb,var(--surface-1) 65%,var(--chat-background, var(--bg))); min-width:0; }
.activity-mark { display:grid; place-items:center; width:18px; height:18px; border-radius:50%; background:var(--accent-soft); color:var(--accent); font-weight:800; flex:0 0 auto; }
.semantic-activity > div { min-width:0; flex:1; }
.semantic-activity header { display:flex; justify-content:space-between; gap:10px; font-size:12px; }
.semantic-activity header span { color:var(--muted); text-transform:uppercase; font-size:12px; }
.semantic-activity p { margin:3px 0 0; color:var(--muted); font-size:12px; white-space:pre-wrap; overflow-wrap:anywhere; }
.semantic-activity code { display:block; margin-top:4px; color:var(--fg); font-size:12px; overflow:hidden; text-overflow:ellipsis; }
.semantic-activity[data-outcome=failed] { border-color:color-mix(in srgb, var(--danger) 35%, var(--border)); }
.semantic-activity[data-outcome=failed] .activity-mark { background:var(--danger-soft); color:var(--danger); }
.semantic-activity.subagent { border-color:color-mix(in srgb, var(--accent) 32%, var(--border)); }
.semantic-activity.attention { border-color:color-mix(in srgb, var(--warning) 45%, var(--border)); }
.subagent-toggle { min-width:0; display:flex; align-items:center; gap:5px; border:0; background:transparent; color:inherit; text-align:left; padding:0; }
.subagent-toggle strong { overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
.subagent-details { margin-top:9px; padding:8px 0 2px; border-top:1px solid var(--border); }
.subagent-step { display:grid; grid-template-columns:10px minmax(0,1fr) auto; gap:7px; align-items:start; padding:6px 1px; }
.batch-child { width:100%; border:0; background:transparent; color:inherit; text-align:left; cursor:pointer; }
.batch-child:hover { background:var(--bg3); }
.step-dot { width:6px; height:6px; margin-top:4px; border-radius:50%; background:var(--accent); }
.subagent-step[data-outcome=failed] .step-dot { background:var(--danger); }
.subagent-step strong { display:block; font-size:12px; }
.subagent-step p { margin:2px 0 0; font:12px/1.45 var(--font-mono); }
.subagent-step small,.subagent-details footer { color:var(--muted); font-size:12px; }
.subagent-details footer { margin-top:5px; padding-top:6px; border-top:1px dashed var(--border); }
.subagent-details footer code { display:inline; }
.subagent-empty { color:var(--muted); }
</style>
