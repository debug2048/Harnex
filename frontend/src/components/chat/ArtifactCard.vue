<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { api, getRuntimeRevision, onRuntimeIdentityChanged } from '../../api/client'
import type { ArtifactRecord } from '../../../../packages/protocol/workbench'
import { useConversationStore } from '../../stores/conversation'
import { t } from '../../i18n'
import { errorMessage } from '../../utils/unknown'
import { useResultPreviewStore } from '../../stores/resultPreview'

const props = defineProps<{ artifactId: string }>()
const host = ref<HTMLElement | null>(null)
const asset = ref<ArtifactRecord | null>(null)
const url = ref(''), error = ref(''), loading = ref(false)
const conversation = useConversationStore()
const preview = useResultPreviewStore()
function open() { void preview.open({ kind: 'artifact', id: props.artifactId }) }
const image = computed(() => /^image\/(png|jpeg|webp|gif)$/.test(asset.value?.mime || ''))
const selectedSource = computed(() => conversation.draftAttachments.some(item => item.artifactId === props.artifactId))
let revision = 0, observer: IntersectionObserver | null = null, visible = false
function clear() { revision++; if (url.value) URL.revokeObjectURL(url.value); url.value = ''; asset.value = null }
const unsubscribe = onRuntimeIdentityChanged(() => { clear(); error.value = ''; loading.value = false; if (visible) void load() })
async function load() {
  const version = ++revision, runtime = getRuntimeRevision(), id = props.artifactId
  loading.value = true; error.value = ''
  try {
    const metadata = await api.artifact(id)
    if (version !== revision || runtime !== getRuntimeRevision()) return
    asset.value = metadata
    if (image.value && metadata.availability === 'LOCAL') {
      const bytes = await api.artifactContent(id)
      if (version !== revision || runtime !== getRuntimeRevision()) return
      if (url.value) URL.revokeObjectURL(url.value)
      url.value = URL.createObjectURL(bytes)
    }
  } catch (cause) { if (version === revision) error.value = errorMessage(cause) }
  finally { if (version === revision) loading.value = false }
}
async function download() {
  const version = revision, runtime = getRuntimeRevision()
  try {
    const blob = await api.artifactContent(props.artifactId)
    if (version !== revision || runtime !== getRuntimeRevision()) return
    const link = document.createElement('a'), objectUrl = URL.createObjectURL(blob)
    link.href = objectUrl; link.download = asset.value?.name || props.artifactId
    link.click(); setTimeout(() => URL.revokeObjectURL(objectUrl), 1000)
  } catch (cause) { error.value = errorMessage(cause) }
}
function source() {
  if (!asset.value) return
  if (selectedSource.value) conversation.draftAttachments = conversation.draftAttachments.filter(item => item.artifactId !== props.artifactId)
  else try { preview.attach(asset.value) } catch (cause) { error.value = errorMessage(cause) }
}
watch(() => props.artifactId, () => { clear(); if (visible) void load() })
onMounted(() => {
  if (typeof IntersectionObserver === 'undefined') { visible = true; void load(); return }
  observer = new IntersectionObserver(entries => {
    if (entries.some(entry => entry.isIntersecting)) { visible = true; observer?.disconnect(); void load() }
  }, { rootMargin: '150px' })
  if (host.value) observer.observe(host.value)
})
onBeforeUnmount(() => { observer?.disconnect(); unsubscribe(); clear() })
</script>

<template>
  <figure ref="host" class="artifact-card">
    <button v-if="url" class="artifact-image-open" @click="open" :aria-label="t('preview.open')"><img :src="url" :alt="asset?.name || t('image.result')" loading="lazy" /></button>
    <figcaption>
      <strong>{{ asset?.name || t('image.result') }}</strong>
      <span v-if="asset?.width">{{ asset.width }} × {{ asset.height }}</span>
      <span v-if="loading">{{ t('common.loading') }}</span>
      <p v-if="error" role="alert">{{ error }}</p>
      <p v-if="asset?.availability === 'UNAVAILABLE'">{{ t('image.assetUnavailable') }}</p>
      <div class="artifact-actions">
        <button v-if="asset?.availability === 'LOCAL'" @click="open">{{ t('preview.open') }}</button>
        <button v-if="asset?.availability === 'LOCAL'" @click="download">{{ t('image.download') }}</button>
        <button v-if="image && asset?.availability === 'LOCAL'" :aria-pressed="selectedSource" @click="source">{{ t('image.useSource') }}</button>
        <button v-if="error" @click="load">{{ t('image.refresh') }}</button>
      </div>
    </figcaption>
  </figure>
</template>

<style scoped>
.artifact-card { margin: 10px 0; max-width: 520px; border: 1px solid var(--border); border-radius: 12px; overflow: hidden; }
.artifact-card img { display: block; max-width: 100%; max-height: 560px; object-fit: contain; background: var(--surface); }
.artifact-image-open { display:block; width:100%; padding:0; border:0; cursor:zoom-in; }
figcaption { padding: 10px 12px; font-size: 12px; display: grid; gap: 6px; }
figcaption p { margin: 0; color: var(--muted); overflow-wrap: anywhere; }
.artifact-actions { display: flex; gap: 8px; flex-wrap: wrap; }
button { background: transparent; border: 1px solid var(--border); border-radius: 6px; padding: 5px 8px; color: inherit; cursor: pointer; }
button[aria-pressed=true] { border-color: var(--accent); color: var(--accent); }
</style>
