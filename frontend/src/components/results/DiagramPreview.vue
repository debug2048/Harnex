<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue'
import { t } from '../../i18n'
const props = defineProps<{ source: string }>()
const url = ref(''), error = ref('')
let revision = 0
function clear() { if (url.value) URL.revokeObjectURL(url.value); url.value = '' }
watch(() => props.source, async value => {
  const version = ++revision; clear(); error.value = ''
  if (value.length > 20000) { error.value = t('preview.diagramLarge'); return }
  try {
    const { default: mermaid } = await import('mermaid')
    mermaid.initialize({ startOnLoad: false, securityLevel: 'strict', maxTextSize: 20000, maxEdges: 300,
      suppressErrorRendering: true, flowchart: { htmlLabels: false },
      secure: ['secure', 'securityLevel', 'startOnLoad', 'maxTextSize', 'maxEdges', 'themeCSS', 'themeVariables'] })
    const { svg } = await mermaid.render('diagram_' + crypto.randomUUID().replace(/-/g, ''), value)
    if (version === revision) url.value = URL.createObjectURL(new Blob([svg], { type: 'image/svg+xml' }))
  } catch { if (version === revision) error.value = t('preview.diagramError') }
}, { immediate: true })
onBeforeUnmount(() => { revision++; clear() })
</script>
<template><figure class="diagram-preview"><img v-if="url" :src="url" :alt="t('preview.diagram')" /><p v-else>{{ error || t('common.loading') }}</p><details><summary>{{ t('preview.source') }}</summary><pre>{{ source }}</pre></details></figure></template>
<style scoped>.diagram-preview { margin:12px 0; overflow:auto; } img { max-width:100%; height:auto; } pre { white-space:pre-wrap; } summary { cursor:pointer; font-size:12px; color:var(--text-muted); }</style>
