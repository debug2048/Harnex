import { computed, ref, toRef, watch } from 'vue'
import { api, getRuntimeRevision } from '../api/client'
import { useConversationStore } from '../stores/conversation'
import { useRuntimeStore } from '../stores/runtime'
import { errorMessage } from '../utils/unknown'
import { t } from '../i18n'

export interface PendingUpload {
  id: string
  name: string
  size: number
  file: File
  requestId: string
  error?: string
}

export function formatUploadSize(bytes: number) {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  if (bytes < 1024 * 1024 * 1024) return `${(bytes / 1024 / 1024).toFixed(2)} MB`
  return `${(bytes / 1024 / 1024 / 1024).toFixed(2)} GB`
}

/** Scope-owned imports; selecting an attachment never creates a conversation. */
export function useChatUploads() {
  const conversation = useConversationStore()
  const runtime = useRuntimeStore()
  const attachments = toRef(conversation, 'draftAttachments')
  const pendingUploads = ref<PendingUpload[]>([])
  const uploadError = ref<string | null>(null)
  const dragOver = ref(false)
  const uploadSupported = (globalThis as typeof globalThis & { __HARNEX_IDE__?: boolean })
    .__HARNEX_IDE__ !== true
  const uploading = computed(() => pendingUploads.value.length)
  let revision = 0

  function uploadPolicy() {
    const policy = runtime.statusline?.upload
    return {
      // Match AgentConfig.Upload defaults when statusline has not loaded yet. The former
      // 50 MiB fallback incorrectly rejected valid uploads accepted by the 200 MiB backend.
      maxBytes: typeof policy?.maxSingleFileBytes === 'number' && policy.maxSingleFileBytes > 0
        ? Math.min(policy.maxSingleFileBytes, 64 * 1024 * 1024) : 64 * 1024 * 1024,
      maxFiles: typeof policy?.maxAttachmentsPerMessage === 'number'
        && policy.maxAttachmentsPerMessage > 0
        ? policy.maxAttachmentsPerMessage : 8,
    }
  }

  function mintPending(file: File): PendingUpload {
    const id = globalThis.crypto?.randomUUID?.()
      || `upload-${Date.now()}-${Math.random().toString(36).slice(2, 10)}`
    return { id, name: file.name, size: file.size, file, requestId: crypto.randomUUID() }
  }

  function friendlyUploadError(name: string, cause: unknown) {
    const message = errorMessage(cause)
    if (/file type not allowed|file type could not be identified/i.test(message)) {
      return t('chat.uploadUnsupported', { name })
    }
    if (/per-thread upload quota exceeded/i.test(message)) {
      return t('chat.uploadQuotaExceeded', { name })
    }
    return `${name}: ${message}`
  }

  async function uploadOne(file: File, pending: PendingUpload, version: number, runtimeRevision: number) {
    try {
      const asset = await api.importArtifact(file, file.name, pending.requestId)
      const info = { artifactId: asset.artifactId, name: asset.name, mime: asset.mime, size: Number(asset.bytes), extracted: false }
      if (version === revision && runtimeRevision === getRuntimeRevision() && pendingUploads.value.some(item => item.id === pending.id)) {
        attachments.value = [...attachments.value.filter(item => item.artifactId !== info.artifactId), info]
        pendingUploads.value = pendingUploads.value.filter(item => item.id !== pending.id)
      }
    } catch (cause: unknown) {
      if (version === revision && runtimeRevision === getRuntimeRevision()) {
        uploadError.value = friendlyUploadError(file.name, cause)
        const item = pendingUploads.value.find(item => item.id === pending.id)
        if (item) item.error = uploadError.value
      }
    }
  }
  function removePending(id: string) { pendingUploads.value = pendingUploads.value.filter(item => item.id !== id) }
  async function retryPending(id: string) {
    const item = pendingUploads.value.find(item => item.id === id)
    if (!item?.error) return
    item.error = undefined; uploadError.value = null
    await uploadOne(item.file, item, revision, getRuntimeRevision())
  }

  async function onPickFiles(files: File[]) {
    if (!uploadSupported) {
      uploadError.value = t('chat.uploadUnavailable')
      return
    }
    if (!files.length) return
    const policy = uploadPolicy()
    const occupied = attachments.value.length + pendingUploads.value.length
    const slots = Math.max(0, policy.maxFiles - occupied)
    const accepted: Array<{ file: File; pending: PendingUpload }> = []
    let rejected = 0
    let validationError: string | null = null

    for (const file of files) {
      if (accepted.length >= slots) {
        rejected++
        continue
      }
      if (file.size <= 0) {
        validationError = t('chat.uploadEmpty', { name: file.name })
        continue
      }
      if (file.size > policy.maxBytes) {
        validationError = t('chat.uploadTooLarge', {
          name: file.name,
          limit: formatUploadSize(policy.maxBytes),
        })
        continue
      }
      accepted.push({ file, pending: mintPending(file) })
    }
    if (rejected > 0) {
      uploadError.value = t('chat.uploadTooMany', { n: policy.maxFiles })
    } else if (validationError) {
      uploadError.value = validationError
    } else if (accepted.length) {
      uploadError.value = null
    }
    if (!accepted.length) return

    const version = revision, runtimeRevision = getRuntimeRevision()
    pendingUploads.value = [...pendingUploads.value, ...accepted.map(item => item.pending)]
    // Keep disk/MIME work bounded and preserve deterministic error order. Every queued
    // file is already visible as a compact pending chip, so serial transfer does not feel lost.
    for (const item of accepted) {
      if (version !== revision || runtimeRevision !== getRuntimeRevision()) break
      if (!pendingUploads.value.some(pending => pending.id === item.pending.id)) continue
      await uploadOne(item.file, item.pending, version, runtimeRevision)
    }
  }

  async function onDrop(event: DragEvent) {
    event.preventDefault()
    dragOver.value = false
    if (!event.dataTransfer?.files) return
    await onPickFiles(Array.from(event.dataTransfer.files))
  }

  async function removeAttachment(artifactId: string) {
    attachments.value = attachments.value.filter(item => item.artifactId !== artifactId)
  }

  function clearUploadError() {
    uploadError.value = null
  }

  watch(() => [conversation.threadId, conversation.executionKind, conversation.draftRevision], () => {
    revision++
    pendingUploads.value = []
    uploadError.value = null
    dragOver.value = false
  }, { flush: 'sync' })

  return {
    attachments, pendingUploads, uploading, uploadError, dragOver, uploadSupported,
    formatSize: formatUploadSize, onPickFiles, onDrop, removeAttachment, clearUploadError, retryPending, removePending,
  }
}
