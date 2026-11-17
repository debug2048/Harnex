import { computed, ref, watch, type Ref } from 'vue'
import type { UploadInfo } from '../api/client'
export type SubmissionMode = 'STEER' | 'QUEUE'

interface SubmissionOptions {
  scope?: Readonly<Ref<string | null>>
  input: Ref<string>
  attachments: Ref<UploadInfo[]>
  uploading: Readonly<Ref<number>>
  streaming: Readonly<Ref<boolean>>
  providerAvailable: Readonly<Ref<boolean>>
  threadId: Readonly<Ref<string | null>>
  beforeSubmit: () => void
  beginTurn: () => void
  send: (text: string, attachments: UploadInfo[]) => Promise<unknown>
  enqueue: (text: string, attachments: UploadInfo[], mode: SubmissionMode) => Promise<boolean>
}

/** Keep the editor usable while a running task acknowledges a submitted update. */
export function useComposerSubmission(options: SubmissionOptions) {
  const { input, attachments, uploading, streaming, providerAvailable, threadId } = options
  const pending = ref(false)
  let scopeRevision = 0
  let textRevision = 0
  watch(threadId, (_id, previous) => { if (previous) { scopeRevision++; pending.value = false } }, { flush: 'sync' })
  if (options.scope) watch(options.scope, () => { scopeRevision++; pending.value = false }, { flush: 'sync' })
  watch(input, () => { textRevision++ }, { flush: 'sync' })
  const canSubmit = computed(() => !pending.value && uploading.value === 0
    && (providerAvailable.value || streaming.value)
    && Boolean(input.value.trim() || attachments.value.length))

  async function submit(mode: SubmissionMode = 'STEER') {
    if (!canSubmit.value) return
    // An explicit queue action must not silently start a new task if this one just ended.
    if (mode === 'QUEUE' && !streaming.value) return
    options.beforeSubmit()
    const text = input.value
    const submittedAttachments = [...attachments.value]
    const wasStreaming = streaming.value
    const scope = scopeRevision
    const revision = textRevision
    pending.value = true
    try {
      const accepted = wasStreaming ? await options.enqueue(text, submittedAttachments, mode)
        : await options.send(text, submittedAttachments)
      if (accepted && scope === scopeRevision) {
        if (!wasStreaming) options.beginTurn()
        if (revision === textRevision) input.value = ''
        // Files added or replaced while awaiting the acknowledgment belong to the new draft.
        attachments.value = attachments.value.filter(item => !submittedAttachments.includes(item))
      }
    } finally {
      if (scope === scopeRevision) pending.value = false
    }
  }

  return { pending, canSubmit, submit }
}
