<script setup lang="ts">
import { computed, ref } from 'vue'
const props = withDefaults(defineProps<{ value: unknown; depth?: number }>(), { depth: 0 })
const entries = computed(() => props.value !== null && typeof props.value === 'object' ? Object.entries(props.value) : null)
const expanded = ref(props.depth < 2)
const summary = computed(() => Array.isArray(props.value) ? `[${entries.value?.length || 0}]` : '{' + (entries.value?.length || 0) + '}')
</script>
<template>
  <details v-if="entries && depth < 12" :open="expanded" class="json-node" @toggle="expanded = ($event.target as HTMLDetailsElement).open">
    <summary>{{ summary }}</summary>
    <div v-for="[key, child] in expanded ? entries.slice(0, 100) : []" :key="key" class="json-entry">
      <strong>{{ key }}:</strong><JsonTree :value="child" :depth="depth + 1" />
    </div>
    <span v-if="entries.length > 100">…</span>
  </details>
  <code v-else>{{ entries ? '…' : JSON.stringify(value) }}</code>
</template>
<style scoped>
.json-node { min-width:0; } summary { cursor:pointer; color:var(--text-muted); }
.json-entry { padding:3px 0 3px 18px; display:flex; align-items:baseline; gap:8px; overflow-wrap:anywhere; }
code { white-space:pre-wrap; } strong { font-weight:500; color:var(--accent); }
</style>
