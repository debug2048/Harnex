<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { api, onRuntimeIdentityChanged } from '../../api/client'
import type { ImageOperationRecord } from '../../../../packages/protocol/workbench'
import { useRunStore } from '../../stores/run'
import { errorMessage } from '../../utils/unknown'
import { t } from '../../i18n'
import ArtifactCard from './ArtifactCard.vue'
import ImageFailureNotice from './ImageFailureNotice.vue'
const props = defineProps<{ operationId: string; excludedArtifactIds?: string[] }>()
const run = useRunStore()
const operation = ref<ImageOperationRecord | null>(null), error = ref(''), model = ref(''), loading = ref(false)
const active = computed(() => !operation.value || ['QUEUED', 'RUNNING', 'SAVING', 'CANCEL_REQUESTED'].includes(operation.value.state))
const placeholders = computed(() => active.value
  ? Math.max(0, Math.min(16, operation.value?.requestSnapshot.parameters?.count || 1) - (operation.value?.artifactIds.length || 0)) : 0)
const aspectRatio = computed(() => {
  const size = operation.value?.requestSnapshot.parameters?.size?.match(/^(\d+)x(\d+)$/)
  return size && Number(size[1]) > 0 && Number(size[2]) > 0 ? `${size[1]} / ${size[2]}` : '1 / 1'
})
let version = 0, pending = false, reloadRequested = false, disposed = false, timer: ReturnType<typeof setTimeout> | undefined
let modelRequestVersion: number | null = null
function clear() { version++; operation.value = null; error.value = ''; model.value = ''; loading.value = false; if (timer) clearTimeout(timer) }
const unsubscribe = onRuntimeIdentityChanged(() => { clear(); void load() })
async function loadModel(value: ImageOperationRecord, current: number) {
  if (model.value || modelRequestVersion === current) return
  modelRequestVersion = current
  try {
    const calls = await api.modelCalls(value.runId)
    if (current === version && !disposed) model.value = calls.find(call => call.callId === `call_${value.operationId}`)?.requestedModel || ''
  } catch { /* Model diagnostics are optional; image status remains available. */ }
  finally { if (modelRequestVersion === current) modelRequestVersion = null }
}
async function load() {
  if (disposed) return
  if (pending) { reloadRequested = true; return }
  const current = version
  pending = true; loading.value = true
  try {
    const value = await api.imageOperation(props.operationId)
    if (current === version && !disposed && (!operation.value || value.revision >= operation.value.revision)) {
      operation.value = value; error.value = ''; void loadModel(value, current)
    }
  } catch (cause) { if (current === version) error.value = errorMessage(cause) }
  finally {
    pending = false
    if (current === version) loading.value = false
    if (timer) clearTimeout(timer)
    if (reloadRequested && !disposed) { reloadRequested = false; void load() }
    else if (!disposed && current === version && active.value) timer = setTimeout(load, 2500)
  }
}
watch(() => props.operationId, () => { clear(); void load() }, { immediate: true })
watch(() => run.resourceRevision, () => { void load() })
onBeforeUnmount(() => { disposed = true; clear(); unsubscribe() })
</script>

<template>
  <section class="image-operation" :data-state="operation?.state || 'QUEUED'" :aria-busy="active" aria-live="polite">
    <div class="image-operation-status">
      <strong>{{ t('image.state.' + (operation?.state || 'QUEUED')) }}</strong>
      <span v-if="operation">{{ operation.artifactIds.length }} / {{ operation.requestSnapshot.parameters?.count || 1 }}</span>
      <span v-if="model" class="image-model">{{ t('image.model', { model }) }}</span>
      <button v-if="error || operation?.state === 'UNKNOWN'" :disabled="loading" @click="load">{{ t('image.refresh') }}</button>
    </div>
    <p v-if="operation?.state === 'UNKNOWN'">{{ t('image.unknownDetail') }}</p>
    <p v-if="operation?.state === 'SAVING'">{{ t('image.savingDetail') }}</p>
    <ImageFailureNotice v-if="operation?.state === 'FAILED' && operation.errorCode" :code="operation.errorCode" :run-id="operation.runId" :detail="operation.detail" />
    <p v-else-if="operation?.detail" role="status">{{ operation.detail }}</p>
    <p v-if="error" role="status">{{ error }}</p>
    <ArtifactCard v-for="id in operation?.artifactIds.filter(id => !excludedArtifactIds?.includes(id)) || []" :key="id" :artifact-id="id" />
    <div v-for="index in placeholders" :key="index" class="image-generating" :style="{ aspectRatio }">
      <div class="image-generating-colors" aria-hidden="true"></div>
      <div class="image-generating-caption">
        <span class="image-generating-indicator" aria-hidden="true"></span>
        <strong>{{ t('image.state.' + (operation?.state || 'QUEUED')) }}</strong>
        <span>{{ t('image.generatingHint') }}</span>
      </div>
    </div>
  </section>
</template>
<style scoped>
.image-operation { margin: 12px 0; padding: 12px; border: 1px solid var(--border); border-radius: 12px; }
.image-operation-status { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; font-size: 12px; }
.image-operation p { color: var(--text-muted); font-size: 12px; white-space: pre-wrap; }
.image-model { color: var(--text-muted); overflow-wrap: anywhere; }
.image-generating { position: relative; isolation: isolate; width: 100%; max-width: 520px; margin-top: 12px; border-radius: 12px; overflow: hidden; background: var(--surface-2); }
.image-generating-colors { position: absolute; inset: -20%; z-index: -1; opacity: .65; filter: blur(32px); background: radial-gradient(ellipse at 25% 30%, #a6b7e9 0%, transparent 48%), radial-gradient(ellipse at 75% 42%, #c8addc 0%, transparent 45%), radial-gradient(ellipse at 50% 85%, #82b9b3 0%, transparent 50%); animation: image-drift 9s ease-in-out infinite alternate; }
.image-generating-caption { position: absolute; inset: 0; display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 12px; padding: 24px; text-align: center; font-size: 13px; color: var(--text-primary); }
.image-generating-caption > span:last-child { color: var(--text-secondary); font-size: 12px; }
.image-generating-indicator { width: 24px; height: 24px; border: 2px solid var(--border); border-top-color: var(--text-secondary); border-radius: 50%; animation: image-spin 1.4s linear infinite; }
@keyframes image-drift { from { transform: translate(-3%, -3%) scale(1); } to { transform: translate(5%, 4%) scale(1.15) rotate(12deg); } }
@keyframes image-spin { to { transform: rotate(360deg); } }
@media (prefers-reduced-motion: reduce) { .image-generating-colors, .image-generating-indicator { animation: none; } }
button { background: transparent; color: inherit; border: 1px solid var(--border); border-radius: 6px; padding: 4px 7px; cursor: pointer; }
[data-state=FAILED], [data-state=UNKNOWN] { border-color: var(--warning, #bd8b38); }
</style>
