<script setup lang="ts">
import type { RunChangeSummary } from '../../domain/runChanges'
import { t } from '../../i18n'

defineProps<{ summary: RunChangeSummary }>()
defineEmits<{ open: [] }>()
</script>

<template>
  <div class="run-changes-notice" role="status">
    <span>{{ t('chat.runChangesNotice', {
      n: summary.count,
      added: summary.added,
      removed: summary.removed,
    }) }}</span>
    <button type="button" @click="$emit('open')">{{ t('chat.reviewChanges') }}</button>
  </div>
</template>

<style scoped>
.run-changes-notice {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin: 10px 0 2px var(--conversation-inset);
  padding: 8px 10px;
  border: 1px solid var(--border);
  border-radius: 12px;
  background: var(--surface-subtle, var(--surface));
  color: var(--text-muted);
  font-size: 12px;
}

.run-changes-notice button {
  flex: none;
  min-height: 28px;
  padding: 4px 8px;
  color: var(--accent);
  border-color: transparent;
  background: transparent;
  font-size: 12px;
}

.run-changes-notice button:hover {
  border-color: var(--border);
  background: var(--surface-hover);
}

</style>
