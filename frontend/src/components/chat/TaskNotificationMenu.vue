<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { locale } from '../../i18n'
import ImNotificationToggle from '../ImNotificationToggle.vue'
import { notificationRecipientLabel, type NotificationMode, type NotificationSettings } from '../../domain/im'
import type { RunNotification } from '../../../../packages/protocol/workbench'

const props = defineProps<{
  modelValue: NotificationMode
  contextKey: string
  load: () => Promise<NotificationSettings>
  current?: RunNotification | null
  locked?: boolean
  disabled?: boolean
}>()
const emit = defineEmits<{ 'update:modelValue': [value: NotificationMode]; configure: []; open: [] }>()
const open = ref(false), root = ref<HTMLElement | null>(null), trigger = ref<HTMLButtonElement | null>(null)
const chinese = computed(() => locale.value === 'zh-CN')
const label = (zh: string, en: string) => chinese.value ? zh : en
const enabled = computed(() => props.modelValue !== 'OFF')
const title = computed(() => enabled.value ? label('任务提醒已开启', 'Task reminder is on') : label('任务提醒', 'Task reminder'))
function close(focus = false) { open.value = false; if (focus) trigger.value?.focus() }
function toggle() { open.value = !open.value; if (open.value) emit('open') }
function outside(event: PointerEvent) { if (open.value && !root.value?.contains(event.target as Node)) close() }
watch(() => props.contextKey, () => close())
onMounted(() => document.addEventListener('pointerdown', outside))
onBeforeUnmount(() => document.removeEventListener('pointerdown', outside))
function configure() { close(); emit('configure') }
</script>
<template>
  <div ref="root" class="task-notification-menu" @keydown.esc.stop.prevent="close(true)"
    @focusout="($event.relatedTarget && !root?.contains($event.relatedTarget as Node)) && close()">
    <button ref="trigger" type="button" class="notification-trigger" :class="{ enabled, expanded: open }"
      :title="title" :aria-label="title" aria-haspopup="dialog" :aria-expanded="open" @click="toggle">
      <svg width="17" height="17" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M18 8a6 6 0 0 0-12 0c0 7-3 7-3 9h18c0-2-3-2-3-9"/><path d="M10 21h4"/></svg>
      <span v-if="enabled" class="notification-enabled-dot" aria-hidden="true" />
    </button>
    <section v-if="open" class="notification-popover" role="dialog" :aria-label="label('任务提醒', 'Task reminder')">
      <header>{{ label('任务提醒', 'Task reminder') }}</header>
      <p>{{ label('按项目记住开关，后续 Agent 和 Goal 任务沿用，直到手动关闭。', 'Remember this setting for future Agent and Goal tasks in this project until you turn it off.') }}</p>
      <ImNotificationToggle :model-value="modelValue" :context-key="contextKey" :load="load" :disabled="disabled"
        @update:model-value="emit('update:modelValue', $event)" @configure="configure" />
      <div v-if="locked" class="current-run-reminder">
        <p v-if="current?.recipient" class="current-recipient" :title="notificationRecipientLabel(current.recipient, chinese)">{{ label('本次任务：', 'Current task: ') }}{{ notificationRecipientLabel(current.recipient, chinese, 'compact') }}</p>
        <small>{{ current?.recipient ? label('本次任务的通知已固定，修改开关只影响后续任务。', 'The current reminder is fixed; changes apply to future tasks.') : label('本次任务未开启提醒，新设置用于后续任务。', 'This task has no reminder; the new setting applies to future tasks.') }}</small>
      </div>
    </section>
  </div>
</template>
<style scoped>
.task-notification-menu { position:relative; flex:0 0 auto; }
.notification-trigger { position:relative; display:grid; place-items:center; width:32px; height:32px; padding:0; border:1px solid transparent; border-radius:8px; background:transparent; color:var(--text-muted); cursor:pointer; }
.notification-trigger:hover,.notification-trigger.expanded { background:var(--surface-hover); color:var(--text-primary); border-color:var(--border); }
.notification-trigger.enabled { color:var(--accent); }
.notification-trigger:focus-visible { outline:2px solid var(--accent); outline-offset:2px; }
.notification-enabled-dot { position:absolute; width:5px; height:5px; border-radius:50%; background:var(--accent); top:5px; right:5px; box-shadow:0 0 0 2px var(--bg); }
.notification-popover { position:absolute; top:calc(100% + 10px); right:0; z-index:90; box-sizing:border-box; width:310px; max-width:calc(100vw - 26px); padding:16px; border:1px solid var(--border); border-radius:12px; background:var(--surface-1); box-shadow:0 12px 36px #0002; color:var(--text-secondary); font-size:12px; line-height:1.6; text-align:left; }
.notification-popover header { font-size:13px; font-weight:600; color:var(--text-primary); }
.notification-popover p { margin:7px 0 14px; color:var(--text-muted); }
.notification-popover small { color:var(--text-muted); }
.notification-popover .current-recipient { color:var(--text-secondary); overflow-wrap:anywhere; }
.current-run-reminder { margin-top:12px; padding-top:10px; border-top:1px solid var(--border); }
@media(max-width:600px) { .notification-popover { position:fixed; top:58px; right:12px; } }
</style>
