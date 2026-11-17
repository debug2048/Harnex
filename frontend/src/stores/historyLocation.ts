import { defineStore } from 'pinia'
import { computed, onScopeDispose, ref, shallowRef, watch } from 'vue'
import type { Hit, ExecutionSearchRecord } from '../../../packages/protocol/workbench'
import { RuntimeClient, onRuntimeIdentityChanged } from '../api/client'
import { archivePage, prependArchivePage } from '../domain/archiveMessages'
import type { Msg } from '../domain/chat'
import { errorMessage } from '../utils/unknown'
import { useConversationStore } from './conversation'
import { useRunController } from '../services/runController'

/** Search context is independent of the active Run's live projection and subscription. */
export const useHistoryLocationStore = defineStore('historyLocation', () => {
  const conversation = useConversationStore()
  const visible = ref(false), loading = ref(false), busy = ref(false), error = ref('')
  const hit = shallowRef<Hit | null>(null), record = shallowRef<ExecutionSearchRecord | null>(null)
  const messages = shallowRef<Msg[]>([]), cursor = ref<string | null>(null)
  const selected = computed(() => messages.value.find(message => message.messageId === hit.value?.messageId))
  const canContinue = computed(() => !!selected.value && !['PENDING', 'STREAMING'].includes(selected.value.status || ''))
  let sequence = 0, client: RuntimeClient | null = null, forkRequest: string | null = null
  let branchRequest: { commandId: string; expectedHistoryRevision: number } | null = null
  function close() { sequence++; visible.value = false; loading.value = false; busy.value = false; messages.value = []; record.value = null; hit.value = null; client = null; forkRequest = null; branchRequest = null }
  const unsubscribe = onRuntimeIdentityChanged(close); onScopeDispose(unsubscribe)
  watch(() => conversation.threadId, id => { if (visible.value && id !== hit.value?.conversationId) close() })
  async function open(value: Hit) {
    if (!value.conversationId || !value.messageId && !value.executionRecordId) return
    const version = ++sequence, target = new RuntimeClient(); client = target; hit.value = value; forkRequest = null; branchRequest = null
    visible.value = true; loading.value = true; messages.value = []; record.value = null; error.value = ''; cursor.value = null
    try {
      if (value.executionRecordId) {
        const result = await target.api.executionRecord(value.conversationId, value.executionRecordId)
        if (version === sequence) record.value = result
      } else {
        const page = await target.api.messages(value.conversationId, undefined, value.messageId, 40)
        if (version === sequence) { messages.value = archivePage(page); cursor.value = page.nextCursor || null }
      }
    } catch (cause) { if (version === sequence) error.value = errorMessage(cause) }
    finally { if (version === sequence) loading.value = false }
  }
  async function loadMore() {
    if (!cursor.value || !hit.value?.conversationId || !client || loading.value) return
    const version = sequence, after = cursor.value, target = client, value = hit.value
    loading.value = true
    try {
      const page = await target.api.messages(value.conversationId!, after, value.messageId, 40)
      if (version === sequence) { messages.value = prependArchivePage(messages.value, page); cursor.value = page.nextCursor || null }
    } catch (cause) { if (version === sequence) error.value = errorMessage(cause) }
    finally { if (version === sequence) loading.value = false }
  }
  async function continueHere(fork = false) {
    if (!canContinue.value || !client || !hit.value?.conversationId || !hit.value.messageId || busy.value) return
    const target = client, value = hit.value, version = sequence
    busy.value = true; error.value = ''
    try {
      let destination = value.conversationId!
      if (fork) {
        forkRequest ||= crypto.randomUUID()
        const receipt = await target.api.forkConversation(destination, { commandId: forkRequest, leafId: value.messageId, title: value.title })
        destination = receipt.conversationId!
      } else {
        if (!branchRequest) {
          const meta = await target.api.conversation(destination)
          if (version !== sequence) return
          branchRequest = { commandId: crypto.randomUUID(), expectedHistoryRevision: meta.historyRevision }
        }
        await target.api.selectBranch(destination, { ...branchRequest, leafId: value.messageId })
      }
      if (version !== sequence) return
      close(); await useRunController().loadThread(destination, { resetViewport: true })
    } catch (cause) { if (version === sequence) error.value = errorMessage(cause) }
    finally { if (version === sequence) busy.value = false }
  }
  return { visible, loading, busy, error, hit, record, messages, cursor, canContinue, open, close, loadMore, continueHere }
})
