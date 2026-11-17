<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import ToolEventView from '../ToolEventView.vue'
import type { ToolEvent } from '../../domain/chat'
import {
  toolActivityFamily,
  toolActivityTargets,
  singleDirectToolActivity,
  shouldInitiallyExpandToolActivity,
  type ToolActivityFamily,
} from '../../domain/toolActivity'
import { t } from '../../i18n'
import { useActivityStore } from '../../stores/activity'
import { subagentForTool } from '../../domain/subagentHistory'
import { activityOutcomeLabel, subagentNeedsAttention } from '../../domain/subagentStatus'
import { updateOverflowTitle } from '../../utils/overflowTitle'

const props = defineProps<{
  events: ToolEvent[]
  threadId?: string
}>()
const emit = defineEmits<{
  openSubagent: [callId: string, content?: string]
}>()

const family = computed<ToolActivityFamily>(() =>
  toolActivityFamily(props.events[0] || { id: '', name: '', done: true }),
)
const activities = useActivityStore()
const child = computed(() => family.value === 'delegate' && props.events[0]
  ? subagentForTool(activities.items, props.events[0]) : undefined)
const attention = computed(() => subagentNeedsAttention(child.value?.outcome || ''))
const failed = computed(() => child.value ? ['failed', 'cancelled', 'timed_out'].includes(child.value.outcome)
  : props.events.some(event => event.error))
const running = computed(() => child.value ? child.value.outcome === 'running' : props.events.some(event => !event.done))
const queued = computed(() => running.value && props.events.every(event => event.status === 'queued'))
const expanded = ref(shouldInitiallyExpandToolActivity(props.events))
watch(failed, (hasFailed) => {
  if (hasFailed) expanded.value = true
})
const targets = computed(() => toolActivityTargets(props.events))
const duration = computed(() => props.events.reduce((sum, event) => sum + Number(event.durationMs || 0), 0))
const singleShellEvent = computed(() => singleDirectToolActivity(props.events))

const title = computed(() => {
  const purpose = props.events[0]?.purpose
  if (purpose) return purpose
  const count = props.events.length
  const targetCount = targets.value.length
  switch (family.value) {
    case 'read':
      return t('activity.readGroup', { files: targetCount || count, count })
    case 'edit':
      return t('activity.editGroup', { files: targetCount || count, count })
    case 'web':
      return t('activity.webGroup', { count: targetCount || count })
    case 'shell':
      return t('activity.shell')
    case 'delegate':
      return t('activity.delegate')
    case 'memory':
      return t('activity.memory')
    case 'todo':
      return t('activity.todo')
    default:
      return t('activity.tool', { count })
  }
})

const status = computed(() => {
  if (child.value) return activityOutcomeLabel(child.value.outcome)
  if (queued.value) return t('tool.waiting')
  if (running.value) return t('tool.running')
  if (failed.value) return t('tool.failed')
  return t('tool.done')
})

function activate() {
  if (family.value === 'delegate') {
    const event = props.events[0]
    if (event) emit('openSubagent', event.id, event.content)
    return
  }
  expanded.value = !expanded.value
}
</script>

<template>
  <ToolEventView v-if="singleShellEvent" :event="singleShellEvent" :thread-id="threadId"
    @open-subagent="(callId, content) => $emit('openSubagent', callId, content)" />
  <article v-else class="tool-activity-group" :class="{ failed, running, expanded, attention }">
    <button type="button" class="tool-activity-summary" :aria-expanded="family === 'delegate' ? undefined : expanded"
      @click="activate">
      <span class="tool-activity-mark" aria-hidden="true">
        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
          <template v-if="failed || attention"><circle cx="12" cy="12" r="9" /><path d="M12 8v4m0 4h.01" /></template>
          <template v-else-if="running"><circle cx="12" cy="12" r="9" /><path d="M12 7v5l3 2" /></template>
          <path v-else d="m5 12 4 4L19 6" />
        </svg>
      </span>
      <span class="tool-activity-copy">
        <strong :class="{ 'has-purpose': events[0]?.purpose }"
          @mouseenter="updateOverflowTitle($event, events[0]?.purpose)" @mouseleave="updateOverflowTitle($event)">{{ title }}</strong>
        <small v-if="targets.length">{{ targets.slice(0, 3).join(' · ') }}<template v-if="targets.length > 3"> · +{{ targets.length - 3 }}</template></small>
      </span>
      <span class="tool-activity-meta">
        <small v-if="duration">{{ duration }}ms</small>
        <span>{{ status }}</span>
        <svg class="tool-activity-chevron" :class="{ open: expanded && family !== 'delegate' }" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="m9 5 7 7-7 7" /></svg>
      </span>
    </button>
    <div v-if="expanded && family !== 'delegate'" class="tool-activity-details">
      <ToolEventView v-for="(event, index) in events" :key="`${event.id}:${index}`"
        :event="event" :thread-id="threadId"
        @open-subagent="(callId, content) => $emit('openSubagent', callId, content)" />
    </div>
  </article>
</template>

<style scoped>
.tool-activity-group.attention { border-color:color-mix(in srgb, var(--warning) 45%, var(--border)); }
.tool-activity-group.attention .tool-activity-mark { color:var(--warning); background:var(--warning-soft); }
.tool-activity-group {
  min-width:0;
  margin:8px 0 8px var(--conversation-inset);
  overflow:hidden;
  border:1px solid var(--border);
  border-radius:12px;
  background:color-mix(in srgb,var(--surface-1) 65%,var(--chat-background, var(--bg)));
}
.tool-activity-group.failed { border-color:color-mix(in srgb,var(--danger) 46%,var(--border)); }
.tool-activity-group.running { border-color:color-mix(in srgb,var(--accent) 42%,var(--border)); }
.tool-activity-summary {
  width:100%;
  min-height:62px;
  display:grid;
  grid-template-columns:22px minmax(0,1fr) auto;
  gap:10px;
  align-items:center;
  border:0;
  border-radius:11px;
  background:transparent;
  padding:12px 14px;
  color:var(--text-primary);
  text-align:left;
}
.tool-activity-summary:hover { background:var(--surface-hover); }
.expanded > .tool-activity-summary { border-radius:11px 11px 0 0; }
.tool-activity-summary:focus-visible { outline:2px solid var(--accent); outline-offset:-3px; }
.tool-activity-summary:active { transform:none; }
.tool-activity-mark {
  width:22px;
  height:22px;
  display:grid;
  place-items:center;
  border-radius:6px;
  background:var(--success-soft);
  color:var(--success);
  font-size:12px;
  font-weight:800;
}
.failed .tool-activity-mark { background:var(--danger-soft); color:var(--danger); }
.running .tool-activity-mark { background:var(--accent-soft); color:var(--accent); }
.tool-activity-copy { min-width:0; display:grid; gap:4px; }
.tool-activity-copy strong { font-size:12.5px; font-weight:600; line-height:1.5; }
.tool-activity-copy strong.has-purpose { overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
.tool-activity-copy small {
  overflow:hidden;
  color:var(--text-muted);
  font-size:12px;
  text-overflow:ellipsis;
  white-space:nowrap;
}
.tool-activity-meta { display:flex; align-items:center; gap:7px; color:var(--text-muted); font-size:12px; }
.tool-activity-meta small { font:12px var(--font-mono); }
.tool-activity-chevron { flex-shrink:0; color:var(--text-faint); transition:transform var(--transition); }
.tool-activity-chevron.open { transform:rotate(90deg); }
.tool-activity-details { padding:0 14px; border-top:1px solid var(--border-soft); }
.tool-activity-details :deep(.tool-event) { margin:0; border:0; border-radius:0; background:transparent; }
.tool-activity-details :deep(.tool-event + .tool-event) { border-top:1px solid var(--border-soft); }
.tool-activity-details :deep(.tool-summary) { padding:12px 0; border-radius:0; }
.tool-activity-details :deep(.tool-body) { padding-left:32px; }
@media (max-width:760px) {
  .tool-activity-meta small { display:none; }
}
@container conversation (max-width:760px) {
  .tool-activity-meta small { display:none; }
}
@media (prefers-reduced-motion:reduce) {
  .tool-activity-chevron { transition:none; }
}
</style>
