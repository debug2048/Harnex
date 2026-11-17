<script setup lang="ts">
import { nextTick, ref, watch } from 'vue'
import { useHistoryLocationStore } from '../../stores/historyLocation'
import Message from '../Message.vue'
import MessageAssets from './MessageAssets.vue'
import VirtualTimeline from './VirtualTimeline.vue'
import { t } from '../../i18n'
import { useTaskHandoffStore } from '../../stores/taskHandoff'
const history = useHistoryLocationStore()
const panel = ref<HTMLElement | null>(null), list = ref<{ scrollToKey(key: string): Promise<boolean> } | null>(null)
let located = ''
watch(() => history.hit, () => { located = '' })
watch(() => history.visible, async visible => {
  if (visible) { await nextTick(); panel.value?.focus() }
  else {
    located = ''
    const input = document.querySelector<HTMLInputElement>('.sidebar-search input')
    if (input?.offsetParent) input.focus()
    else document.querySelector<HTMLTextAreaElement>('.composer textarea')?.focus()
  }
})
watch(() => [history.messages, history.hit?.messageId, history.loading] as const, async () => {
  const id = history.hit?.messageId
  if (!id || history.loading || located === id) return
  await nextTick(); if (await list.value?.scrollToKey(id)) located = id
})
function handoff() {
  const message = history.messages.find(item => item.messageId === history.hit?.messageId)
  if (message) { void useTaskHandoffStore().begin(message); history.close() }
}
function keydown(event: KeyboardEvent) {
  if (event.key === 'Escape') { event.preventDefault(); history.close(); return }
  if (event.key !== 'Tab') return
  const elements = [...(panel.value?.querySelectorAll<HTMLElement>('button:not(:disabled),a[href],input,textarea,select') || [])]
  const first = elements[0], last = elements[elements.length - 1]
  if (event.shiftKey && (document.activeElement === first || document.activeElement === panel.value)) { last?.focus(); event.preventDefault() }
  else if (!event.shiftKey && document.activeElement === last) { first?.focus(); event.preventDefault() }
}
</script>
<template>
  <Teleport to="body"><div v-if="history.visible" class="history-scrim" @click.self="history.close">
    <section ref="panel" class="history-location" role="dialog" aria-modal="true" aria-labelledby="history-location-title" tabindex="-1" @keydown="keydown">
      <header><div><h2 id="history-location-title">{{ t('history.location') }}</h2><p>{{ history.hit?.title }}</p></div><button :aria-label="t('common.close')" @click="history.close">×</button></header>
      <div class="chat-messages history-context">
        <button v-if="history.cursor" :disabled="history.loading" @click="history.loadMore">{{ t('chat.loadHistory') }}</button>
        <p v-if="history.loading" role="status">{{ t('common.loading') }}</p>
        <p v-if="history.error" role="alert">{{ history.error }}</p>
        <template v-if="history.record"><h3>{{ history.record.title }}</h3><pre class="retained-output">{{ history.record.text }}</pre></template>
        <VirtualTimeline v-else ref="list" :items="history.messages.map(message => ({ key: message.messageId!, message }))">
          <template #default="{ item }"><Message :role="item.message.role" :content="item.message.content" :actionable="false" /><MessageAssets :parts="item.message.contentParts" /></template>
        </VirtualTimeline>
      </div>
      <footer v-if="history.canContinue"><button :disabled="history.busy" @click="handoff">{{ t('handoff.action') }}</button><button :disabled="history.busy" @click="history.continueHere(true)">{{ t('history.fork') }}</button><button :disabled="history.busy" @click="history.continueHere()">{{ t('history.continue') }}</button></footer>
    </section>
  </div></Teleport>
</template>
<style scoped>
.history-scrim { position: fixed; inset: 0; z-index: 90; display: grid; place-items: center; padding: 18px; background: #0006; }
.history-location { width: min(800px, 100%); height: min(850px, calc(100dvh - 36px)); display: flex; flex-direction: column; min-height: 0; min-width: 0; background: var(--surface-1); border: 1px solid var(--border); border-radius: 12px; overflow: hidden; color: var(--text-primary); }
header, footer { display: flex; align-items: center; justify-content: space-between; gap: 10px; padding: 14px 18px; flex-shrink: 0; }
header { border-bottom: 1px solid var(--border); } h2, h3 { font-size: 14px; margin: 0; } p { color: var(--text-muted); font-size: 12px; overflow-wrap: anywhere; }
.history-context { min-height: 0; flex: 1; overflow: auto; padding: 10px 18px; }
button { border: 1px solid var(--border); border-radius: 6px; background: var(--surface-2); color: inherit; padding: 6px 10px; cursor: pointer; }
button:disabled { opacity: .5; } footer { justify-content: flex-end; border-top: 1px solid var(--border); flex-wrap: wrap; }
.retained-output { white-space: pre-wrap; overflow-wrap: anywhere; font-size: 12px; line-height: 1.6; }
</style>
