<script setup lang="ts">
import { computed } from 'vue'
import { t } from '../i18n'
import { workspaceFailure } from '../utils/workspaceFailure'
const props = defineProps<{ message: string; remote?: boolean; code?: string; personal?: boolean }>()
const failure = computed(() => workspaceFailure(props.message, props.remote, props.code, props.personal))
</script>
<template>
  <div class="failure-copy" role="alert">
    <p>{{ failure.summary }}</p>
    <details v-if="message && message !== failure.summary">
      <summary>{{ t('remote.errorDetails') }}</summary>
      <pre>{{ failure.details }}</pre>
    </details>
  </div>
</template>
<style scoped>
.failure-copy { min-width:0; flex:1; overflow-wrap:anywhere; }
p { margin:0; line-height:1.6; }
details { margin-top:6px; }
summary { cursor:pointer; font-size:12px; color:var(--text-muted); }
pre { white-space:pre-wrap; overflow-wrap:anywhere; max-height:180px; overflow:auto; font-size:11px; user-select:text; }
</style>
