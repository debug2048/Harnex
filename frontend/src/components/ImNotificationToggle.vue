<script setup lang="ts">
import { computed, onActivated, onBeforeUnmount, ref, watch } from 'vue'
import { locale } from '../i18n'
import { notificationRecipientLabel, type NotificationMode, type NotificationSettings } from '../domain/im'
import { errorMessage } from '../utils/unknown'

const props = defineProps<{
  modelValue: NotificationMode
  contextKey: string
  load: () => Promise<NotificationSettings>
  failuresOnly?: boolean
  disabled?: boolean
}>()
const emit = defineEmits<{ 'update:modelValue': [value: NotificationMode]; configure: [] }>()
const settings = ref<NotificationSettings | null>(null), loading = ref(false), error = ref('')
let sequence = 0, disposed = false
const chinese = computed(() => locale.value === 'zh-CN')
const label = (zh: string, en: string) => chinese.value ? zh : en
const recipient = computed(() => settings.value?.defaultRecipient)
const online = computed(() => settings.value?.recipients.find(option => option.recipient.id === recipient.value?.id)?.online)
async function refresh() {
  const request = ++sequence
  loading.value = true; error.value = ''
  try {
    const value = await props.load()
    if (!disposed && request === sequence) settings.value = value
  } catch (cause) {
    if (!disposed && request === sequence) { settings.value = null; error.value = errorMessage(cause) }
  } finally { if (!disposed && request === sequence) loading.value = false }
}
watch(() => props.contextKey, () => { settings.value = null; void refresh() }, { immediate: true })
onActivated(() => { if (!loading.value) void refresh() })
onBeforeUnmount(() => { disposed = true; sequence++ })
function toggle(event: Event) {
  const enabled = (event.target as HTMLInputElement).checked
  if (enabled && !settings.value?.valid) { emit('configure'); return }
  emit('update:modelValue', enabled ? 'ALL' : 'OFF')
}
</script>
<template>
  <div class="im-notification-toggle">
    <label class="notification-check"><input type="checkbox" :checked="modelValue !== 'OFF'" :disabled="disabled || loading || !settings?.valid && modelValue === 'OFF'" @change="toggle" />{{ label('IM 提醒', 'IM reminder') }}</label>
    <select v-if="failuresOnly && modelValue !== 'OFF'" :value="modelValue" :disabled="disabled" :aria-label="label('通知时机', 'When to notify')" @change="emit('update:modelValue', ($event.target as HTMLSelectElement).value as NotificationMode)">
      <option value="ALL">{{ label('每次完成或失败', 'On completion or failure') }}</option>
      <option value="FAILURES">{{ label('仅失败', 'Failures only') }}</option>
    </select>
    <small v-if="loading">{{ label('正在读取通知会话…', 'Loading notification recipient…') }}</small>
    <template v-else-if="settings?.valid && recipient">
      <small v-if="modelValue !== 'OFF'" class="notification-recipient" :title="notificationRecipientLabel(recipient, chinese)">{{ label('发送到：', 'Send to: ') }}{{ notificationRecipientLabel(recipient, chinese, 'compact') }}<span v-if="!online"> · {{ label('连接恢复后发送', 'Sends after reconnection') }}</span></small>
    </template>
    <template v-else>
      <small>{{ error ? label('无法读取此项目的通知设置。', 'Notification settings are unavailable for this project.') : label('先选择项目默认通知会话。', 'Choose a default notification recipient for this project.') }}</small>
      <button type="button" :disabled="disabled" @click="emit('configure')">{{ label('设置会话', 'Choose recipient') }}</button>
      <button v-if="error" type="button" :title="error" @click="refresh">{{ label('重试', 'Retry') }}</button>
    </template>
  </div>
</template>
<style scoped>
.im-notification-toggle { display:flex; align-items:center; flex-wrap:wrap; gap:7px 10px; font-size:12px; color:var(--text-muted); min-width:0; }
.notification-check { display:flex!important; align-items:center; gap:6px; white-space:nowrap; }
.notification-check input { width:14px!important; height:14px; margin:0; accent-color:var(--accent); }
.im-notification-toggle select { width:auto!important; padding:4px 7px!important; border:1px solid var(--border); border-radius:5px; background:var(--surface-1); color:var(--text-secondary); font:inherit; }
.notification-recipient { overflow-wrap:anywhere; line-height:1.6; }
.im-notification-toggle button { padding:3px 7px; border:1px solid var(--border); border-radius:5px; color:var(--text-secondary); background:var(--surface-1); font:inherit; cursor:pointer; }
.im-notification-toggle :focus-visible { outline:2px solid var(--accent); outline-offset:2px; }
</style>
