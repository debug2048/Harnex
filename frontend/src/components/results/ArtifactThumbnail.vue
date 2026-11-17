<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue'
import { api, getRuntimeRevision, onRuntimeIdentityChanged } from '../../api/client'
import { useResultPreviewStore } from '../../stores/resultPreview'
const props = defineProps<{ id: string; name: string; mime?: string }>()
const preview = useResultPreviewStore(), url = ref('')
const name = ref(props.name), mime = ref(props.mime)
let sequence = 0
function clear() { sequence++; if (url.value) URL.revokeObjectURL(url.value); url.value = ''; name.value = props.name; mime.value = props.mime }
async function load() {
  clear(); const current = sequence, runtime = getRuntimeRevision(), id = props.id
  const active = () => current === sequence && runtime === getRuntimeRevision()
  try {
    // Archived input messages retain artifact IDs; resolve their names and types before showing a thumbnail.
    if (!mime.value || name.value === id) {
      const asset = await api.artifact(id)
      if (!active()) return
      name.value = asset.name; mime.value = asset.mime
      if (asset.availability !== 'LOCAL') return
    }
    if (!mime.value?.startsWith('image/')) return
    const value = await api.artifactContent(id)
    if (active()) url.value = URL.createObjectURL(value)
  } catch { /* The full viewer reports missing assets and offers retry. */ }
}
const unsubscribe = onRuntimeIdentityChanged(clear)
watch(() => [props.id, props.name, props.mime], load, { immediate: true })
onBeforeUnmount(() => { unsubscribe(); clear() })
</script>
<template>
  <button type="button" class="artifact-thumbnail" :title="name" @click="preview.open({ kind: 'artifact', id, name, mime })">
    <img v-if="url" :src="url" :alt="name" /><span v-else aria-hidden="true">▧</span><span class="thumbnail-name">{{ name }}</span>
  </button>
</template>
<style scoped>
.artifact-thumbnail { display:flex; align-items:center; gap:6px; min-width:0; border:0; background:transparent; color:inherit; cursor:pointer; padding:2px; }
img { width:38px; height:38px; object-fit:cover; border-radius:4px; } .thumbnail-name { overflow:hidden; text-overflow:ellipsis; white-space:nowrap; max-width:180px; }
</style>
