<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, watch } from 'vue'
import { useChangesStore } from '../../stores/changes'
import { useResultPreviewStore } from '../../stores/resultPreview'
import type { WorkspaceChange } from '../../api/contracts'
import { t } from '../../i18n'
import { summarizeRunChanges } from '../../domain/runChanges'

const props = defineProps<{ runId?: string | null }>()
const emit = defineEmits<{
  restoreFile: [snapshotSeq: number, path: string]
  undoRun: [snapshotSeq: number, paths: string[]]
}>()
const changes = useChangesStore()
const preview = useResultPreviewStore()
const taskSummary = computed(() => summarizeRunChanges(changes.view))
const canUndoRun = computed(() => Boolean(
  changes.view?.baseline?.snapshotSeq && taskSummary.value.paths.length,
))

async function refresh() {
  if (props.runId) await changes.load(props.runId)
  else changes.reset()
}

function openChange(item: WorkspaceChange) {
  const runId = props.runId
  if (!runId) return
  const seq = changes.view?.baseline?.snapshotSeq
  void preview.open({ kind: 'diff', runId, path: item.path,
    deleted: ['deleted', 'removed', 'd'].includes(item.status.toLowerCase()),
    restore: seq ? () => emit('restoreFile', seq, item.path) : undefined })
}

function undoRun() {
  const seq = changes.view?.baseline?.snapshotSeq
  if (!seq || !taskSummary.value.paths.length) return
  emit('undoRun', seq, taskSummary.value.paths)
}

onMounted(refresh)
watch(() => props.runId, () => {
  if (preview.source?.kind === 'diff' && preview.source.runId !== props.runId) preview.close()
  void refresh()
})
onBeforeUnmount(() => { if (preview.source?.kind === 'diff') preview.close() })
</script>

<template>
  <div class="changes-panel">
    <div class="panel-header">
      <div>
        <strong>{{ t('chat.changesSummary') }}</strong>
        <small v-if="changes.view?.baseline">{{ t('chat.runBaseline') }} · #{{ changes.view.baseline.snapshotSeq }}</small>
      </div>
      <div class="panel-actions">
        <button class="sm" :disabled="!canUndoRun || changes.loading" @click="undoRun">
          {{ t('chat.undoRunChanges') }}
        </button>
        <button class="sm" :disabled="!runId || changes.loading" @click="refresh">{{ t('chat.refresh') }}</button>
      </div>
    </div>
    <p v-if="!runId" class="empty-panel">{{ t('chat.noRunChanges') }}</p>
    <p v-else-if="changes.error" class="panel-error">{{ changes.error }}</p>
    <template v-if="changes.view">
      <section class="change-group">
        <h4>{{ t('chat.thisRun') }} <span>{{ changes.view.thisRun.length }}</span></h4>
        <p v-if="!changes.view.thisRun.length" class="empty-panel">{{ t('chat.noRunFileChanges') }}</p>
        <button v-for="item in changes.view.thisRun" :key="'run-' + item.path"
          :class="{ active: preview.source?.kind === 'diff' && preview.source.runId === runId && preview.source.path === item.path }" @click="openChange(item)">
          <span class="change-status" :title="item.status">{{ item.status.slice(0, 1).toUpperCase() }}</span>
          <code :title="item.path">{{ item.path }}</code>
          <span class="diff-count"><span class="diff-added" :title="t('diff.added')">+{{ item.added || 0 }}</span><span class="diff-removed" :title="t('diff.removed')">−{{ item.removed || 0 }}</span></span>
        </button>
      </section>
    </template>
  </div>
</template>

<style scoped>
.changes-panel { padding:15px 14px 24px; overflow:auto; height:100%; }
.panel-header { display:flex; align-items:center; justify-content:space-between; margin-bottom:12px; }
.panel-header > div { display:flex; flex-direction:column; gap:2px; }
.panel-header > .panel-actions { flex-direction:row; align-items:center; gap:5px; }
.panel-header small { color:var(--muted); }
.change-group { margin-bottom:14px; }
.change-group h4 { display:flex; justify-content:space-between; padding:7px 5px 4px; color:var(--text-faint); font-size:12px; text-transform:uppercase; letter-spacing:.06em; }
.change-group > button { width:100%; min-height:38px; border:1px solid transparent; border-radius:7px; background:transparent; padding:6px; display:flex; gap:7px; align-items:center; text-align:left; }
.change-group > button:hover,.change-group > button.active { border-color:var(--border); background:var(--surface-hover); }
.change-group code { min-width:0; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; flex:1; }
.change-status { color:var(--warning); font:700 12px var(--font-mono); }
.diff-count { display:flex; gap:7px; font:12px var(--font-mono); white-space:nowrap; }
.diff-added { color:var(--diff-add-fg); }
.diff-removed { color:var(--diff-del-fg); }
</style>
