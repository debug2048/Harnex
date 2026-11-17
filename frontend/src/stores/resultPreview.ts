import { defineStore } from 'pinia'
import { computed, onScopeDispose, ref, shallowRef, watch } from 'vue'
import { RuntimeClient, getRuntimeRevision, onRuntimeIdentityChanged, type PreviewSession } from '../api/client'
import { useConversationStore } from './conversation'
import { useRuntimeStore } from './runtime'
import type { ArtifactRecord } from '../../../packages/protocol/workbench'
import { resultKind, resultMime, type ResultSource } from '../domain/resultPreview'
import { errorMessage } from '../utils/unknown'
import { t } from '../i18n'
import type { FileDiff } from '../api/contracts'
import { useWorkspaceStore } from './workspace'
import { chooseDownloadDestination, type DownloadDestination } from '../utils/saveDownload'

export const useResultPreviewStore = defineStore('resultPreview', () => {
  const conversation = useConversationStore()
  const workspace = useWorkspaceStore()
  const source = shallowRef<ResultSource | null>(null), blob = shallowRef<Blob | null>(null)
  const diff = shallowRef<FileDiff | null>(null)
  const name = ref(''), mime = ref(''), text = ref(''), url = ref(''), pageUrl = ref('')
  const session = shallowRef<PreviewSession | null>(null)
  const loading = ref(false), error = ref(''), notice = ref('')
  const contentLoading = ref(false), contentError = ref('')
  const remote = ref(false)
  const canReveal = computed(() => (source.value?.kind === 'file' || source.value?.kind === 'diff')
    && !remote.value && !workspace.fixedWorkspaceId)
  const downloading = ref(false)
  let downloadController: AbortController | null = null
  let revision = 0, client: RuntimeClient | null = null
  function release() {
    if (url.value) URL.revokeObjectURL(url.value)
    // Browser tabs may still use a grant after this panel closes; grants expire server-side.
    downloadController?.abort(); downloadController = null; downloading.value = false
    url.value = ''; pageUrl.value = ''; blob.value = null; session.value = null; text.value = ''; diff.value = null
  }
  function close() { revision++; release(); source.value = null; name.value = ''; mime.value = ''; loading.value = false; error.value = ''; notice.value = ''; contentLoading.value = false; contentError.value = '' }
  const unsubscribe = onRuntimeIdentityChanged(close)
  onScopeDispose(() => { unsubscribe(); close() })
  watch(() => conversation.threadId, close, { flush: 'sync' })
  watch(() => [workspace.epoch, workspace.activeWorkspaceId, workspace.instanceId], close, { flush: 'sync' })
  async function open(value: ResultSource) {
    close(); source.value = value; client = new RuntimeClient()
    remote.value = client.target.remote
    name.value = value.kind === 'file' || value.kind === 'diff' ? value.path : value.name || (value.kind === 'artifact' ? value.id : '')
    mime.value = resultMime(name.value)
    const version = revision, runtime = getRuntimeRevision(), owner = client
    const current = () => version === revision && runtime === getRuntimeRevision()
    loading.value = true
    try {
      if (value.kind === 'diff') {
        const result = await owner.api.fileDiff(value.runId, value.path)
        if (current()) diff.value = result
        return
      }
      await readContent(value, owner, current)
    } catch (cause) { if (current()) error.value = errorMessage(cause) }
    finally { if (current()) loading.value = false }
  }
  async function readContent(value: ResultSource, owner: RuntimeClient, current: () => boolean) {
    let bytes: Blob
    if (value.kind === 'artifact') {
      const metadata = await owner.api.artifact(value.id)
      if (!current()) return
      name.value = metadata.name; mime.value = resultMime(metadata.name, metadata.mime)
      if (metadata.availability !== 'LOCAL') throw new Error(t('image.assetUnavailable'))
      if (Number(metadata.bytes) > 32 * 1024 * 1024) throw new Error(t('preview.tooLarge'))
      bytes = await owner.api.artifactContent(value.id)
    } else if (value.kind === 'file' || value.kind === 'diff') {
      const metadata = await owner.api.fileContent(value.path)
      if (!current()) return
      if (metadata.size > 32 * 1024 * 1024) throw new Error(t('preview.tooLarge'))
      mime.value = resultMime(name.value, metadata.mime)
      if (!metadata.binary && resultKind(name.value, mime.value) === 'binary') mime.value = 'text/plain'
      bytes = await owner.api.downloadFile(value.path)
    } else {
      name.value = value.name; mime.value = value.mime; bytes = new Blob([value.content], { type: value.mime })
    }
    if (!current()) return
    if (bytes.size > 32 * 1024 * 1024) throw new Error(t('preview.tooLarge'))
    blob.value = new Blob([bytes], { type: mime.value || 'application/octet-stream' })
    if (url.value) URL.revokeObjectURL(url.value)
    url.value = URL.createObjectURL(blob.value)
    const kind = resultKind(name.value, mime.value)
    if (['html', 'markdown', 'json', 'csv', 'text'].includes(kind) || mime.value === 'image/svg+xml') {
      const content = await bytes.slice(0, 2 * 1024 * 1024).text()
      if (!current()) return
      text.value = content
      if (bytes.size > 2 * 1024 * 1024) notice.value = t('preview.sourceTruncated')
    }
    if (kind === 'html' || kind === 'pdf' && value.kind !== 'inline') {
      const created = await owner.api.createPreview(value.kind === 'file' || value.kind === 'diff' ? { path: value.path }
        : value.kind === 'artifact' ? { artifactId: value.id } : { html: value.content })
      if (!current()) { void owner.api.closePreview(created.id).catch(() => {}); return }
      session.value = created
      pageUrl.value = new URL(created.url, owner.target.base || window.location.origin).href
    }
  }
  /** A change opens as a diff; fetch the current file only when the user asks to preview it. */
  async function ensureContent() {
    if (!source.value || !client || source.value.kind !== 'diff' || source.value.deleted || contentLoading.value) return
    if (blob.value && !contentError.value) return
    const version = revision, runtime = getRuntimeRevision(), value = source.value, owner = client
    const current = () => version === revision && runtime === getRuntimeRevision()
    contentLoading.value = true; contentError.value = ''
    try { await readContent(value, owner, current) }
    catch (cause) { if (current()) contentError.value = errorMessage(cause) }
    finally { if (current()) contentLoading.value = false }
  }
  async function refresh() { if (source.value) await open(source.value) }
  async function reveal() {
    if (!canReveal.value || !source.value || !('path' in source.value) || !client) return
    const version = revision
    try { await client.api.openContainingFolder(source.value.path) }
    catch (cause) { if (version === revision) error.value = errorMessage(cause) }
  }
  async function original(signal?: AbortSignal) {
    if (blob.value) return blob.value
    if (!source.value || !client) return null
    return source.value.kind === 'file' || source.value.kind === 'diff' ? client.api.downloadFile(source.value.path, signal)
      : source.value.kind === 'artifact' ? client.api.artifactContent(source.value.id) : null
  }
  async function download() {
    if (!source.value || source.value.kind === 'diff' && source.value.deleted || downloading.value) return
    const version = revision, controller = new AbortController()
    downloadController = controller; downloading.value = true; error.value = ''
    let destination: DownloadDestination | null = null
    const cancelDestination = () => { void destination?.cancel().catch(() => {}) }
    try {
      destination = await chooseDownloadDestination(name.value.split('/').pop() || 'download')
      if (!destination || controller.signal.aborted || version !== revision) return
      controller.signal.addEventListener('abort', cancelDestination, { once: true })
      const bytes = await original(controller.signal)
      if (!bytes || controller.signal.aborted || version !== revision) return
      await destination.write(bytes, controller.signal)
    } catch (cause) {
      if (!controller.signal.aborted && version === revision) error.value = `${t('files.downloadFailed')} ${errorMessage(cause)}`
    } finally {
      controller.signal.removeEventListener('abort', cancelDestination)
      await destination?.cancel().catch(() => {})
      if (downloadController === controller) { downloadController = null; downloading.value = false }
    }
  }
  function restore() {
    const action = source.value?.kind === 'diff' ? source.value.restore : undefined
    if (action) { close(); action() }
  }
  function attach(asset: ArtifactRecord, edit = false) {
    if (!conversation.draftAttachments.some(item => item.artifactId === asset.artifactId)) {
      const max = useRuntimeStore().statusline?.upload?.maxAttachmentsPerMessage || 8
      if (conversation.draftAttachments.length >= max) throw new Error(t('chat.uploadTooMany', { n: max }))
      conversation.draftAttachments.push({ artifactId: asset.artifactId, name: asset.name, mime: asset.mime,
        size: Number(asset.bytes), extracted: false })
    }
    if (edit && !conversation.draftText.trim()) conversation.draftText = t('preview.editPrompt')
    notice.value = t('preview.added')
  }
  async function editImage() {
    if (!source.value || !client) return
    if (!/^image\/(png|jpeg|webp|gif)$/.test(mime.value)) return
    const version = revision, owner = client, value = source.value, currentBlob = blob.value
    try {
      const asset = value.kind === 'artifact' ? await owner.api.artifact(value.id)
        : currentBlob ? await owner.api.importArtifact(currentBlob, name.value, crypto.randomUUID()) : null
      if (!asset || version !== revision) return
      attach(asset, true)
    } catch (cause) { if (version === revision) error.value = errorMessage(cause) }
  }
  return { source, name, mime, text, blob, diff, url, pageUrl, session, loading, error, notice, contentLoading, contentError, remote, canReveal, downloading, open, close, refresh, ensureContent, reveal, editImage, attach, download, restore }
})
