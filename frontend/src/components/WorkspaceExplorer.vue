<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { CAPABILITIES } from '../domain/capabilities'
import { useExplorerStore } from '../stores/explorer'
import { useRuntimeStore } from '../stores/runtime'
import { useWorkspaceStore } from '../stores/workspace'
import type { FileNode, FileSearchHit } from '../api/contracts'
import { t } from '../i18n'
import { errorMessage } from '../utils/unknown'
import { useResultPreviewStore } from '../stores/resultPreview'

type TreeRow =
  | { kind: 'node'; node: FileNode; depth: number }
  | { kind: 'more'; path: string; depth: number }

const props = withDefaults(defineProps<{ embedded?: boolean }>(), { embedded: false })
const explorer = useExplorerStore()
const resultPreview = useResultPreviewStore()
const runtime = useRuntimeStore()
const workspace = useWorkspaceStore()
const supported = computed(() => workspace.ready && runtime.has(CAPABILITIES.filesRead))
const expanded = ref(props.embedded)
const contentInput = ref<HTMLInputElement | null>(null)
let workspaceSyncSequence = 0
const treeRows = computed<TreeRow[]>(() => {
  const rows: TreeRow[] = []
  const visit = (path: string, depth: number) => {
    const directory = explorer.directories[path]
    if (!directory) return
    for (const node of directory.entries) {
      rows.push({ kind: 'node', node, depth })
      if (node.type === 'directory' && explorer.expandedPaths.includes(node.path)) {
        visit(node.path, depth + 1)
      }
    }
    if (directory.nextCursor) rows.push({ kind: 'more', path, depth })
  }
  visit('', 0)
  return rows
})

async function syncWorkspaceFiles() {
  const sequence = ++workspaceSyncSequence
  const targetWorkspaceId = workspace.activeWorkspaceId
  const targetInstanceId = workspace.instanceId
  if (!workspace.ready || !targetWorkspaceId || !targetInstanceId) {
    return
  }
  try {
    await runtime.load()
    if (sequence !== workspaceSyncSequence
        || workspace.activeWorkspaceId !== targetWorkspaceId
        || workspace.instanceId !== targetInstanceId
        || !workspace.ready) return
    if (supported.value) await explorer.loadDirectory('')
  } catch (cause: unknown) {
    if (sequence === workspaceSyncSequence) {
      explorer.error = errorMessage(cause)
    }
  }
}

watch(
  () => [workspace.ready, workspace.activeWorkspaceId, workspace.instanceId, workspace.epoch,
    runtime.loadedIdentity] as const,
  () => { void syncWorkspaceFiles() },
  { immediate: true },
)

onMounted(() => {
  window.addEventListener('keydown', onGlobalKeydown)
})
onUnmounted(() => {
  workspaceSyncSequence++
  window.removeEventListener('keydown', onGlobalKeydown)
})

function onGlobalKeydown(event: KeyboardEvent) {
  if (!(event.ctrlKey || event.metaKey) || !event.shiftKey || event.key.toLowerCase() !== 'f') return
  event.preventDefault()
  explorer.showContentSearch()
  void nextTick(() => contentInput.value?.focus())
}

function selectNode(node: FileNode) {
  if (node.type === 'directory') return explorer.toggleDirectory(node)
  explorer.selectedPath = node.path
  return resultPreview.open({ kind: 'file', path: node.path })
}

function chooseResult(hit: FileSearchHit) {
  void selectNode({ path: hit.path, name: hit.name, type: 'file' })
}

function onNodeKeydown(event: KeyboardEvent, node: FileNode) {
  if (node.type === 'directory' && (event.key === 'Enter' || event.key === 'ArrowRight')) {
    event.preventDefault()
    void explorer.toggleDirectory(node)
  } else if (node.type === 'directory' && event.key === 'ArrowLeft') {
    event.preventDefault()
    if (explorer.expandedPaths.includes(node.path)) void explorer.toggleDirectory(node)
  } else if (node.type === 'file' && event.key === 'Enter') {
    event.preventDefault()
    void selectNode(node)
  } else if (node.type === 'file' && event.key === ' ') {
    event.preventDefault()
    void selectNode(node)
  }
}

function onDragStart(event: DragEvent, node: FileNode) {
  if (node.type !== 'file' || !event.dataTransfer) return
  event.dataTransfer.effectAllowed = 'copy'
  event.dataTransfer.setData('application/x-harnex-path', node.path)
  event.dataTransfer.setData('text/plain', `@${node.path}`)
}

function gitStatusLabel(status?: string) {
  if (!status) return ''
  const normalized = status.trim().toUpperCase()
  if (normalized.includes('CONFLICT') || normalized === 'U') return t('files.statusConflict')
  if (normalized === '??' || normalized.includes('ADDED') || normalized === 'A') return 'A'
  if (normalized.includes('DELETED') || normalized === 'D') return 'D'
  if (normalized.includes('MODIFIED') || normalized.includes('CHANGED') || normalized === 'M') return 'M'
  return normalized.slice(0, 3)
}
</script>

<template>
  <section v-if="supported" class="workspace-explorer" :class="{ embedded }">
    <button v-if="!embedded" class="explorer-toggle" @click="expanded = !expanded" :aria-expanded="expanded">
      <span>{{ t('sidebar.files') }}</span><span>{{ expanded ? '−' : '+' }}</span>
    </button>
    <div v-if="embedded || expanded" class="explorer-body">
      <form class="file-toolbar" @submit.prevent="explorer.search()">
        <select v-model="explorer.searchMode" :aria-label="t('files.searchMode')" :title="t('files.matchHint')">
          <option value="name">{{ t('files.nameMode') }}</option>
          <option value="content">{{ t('files.content') }}</option>
        </select>
        <div class="file-search-input">
          <input ref="contentInput" v-model="explorer.searchQuery" :placeholder="t(explorer.searchMode === 'name' ? 'files.nameSearch' : 'files.contentSearch')"
            :aria-label="t(explorer.searchMode === 'name' ? 'files.nameSearch' : 'files.contentSearch')" :title="t('files.matchHint')" />
          <button v-if="explorer.searchQuery" type="button" class="clear-search" :aria-label="t('sidebar.searchClear')" @click="explorer.clearSearch()">×</button>
        </div>
        <button type="submit" :disabled="explorer.searching || !explorer.searchQuery.trim()" :title="t('files.searchAction')" :aria-label="t('files.searchAction')">
          <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" aria-hidden="true"><circle cx="10.5" cy="10.5" r="6.5"/><path d="m16 16 5 5"/></svg>
        </button>
        <button type="button" class="refresh-files" :disabled="explorer.refreshing" :aria-busy="explorer.refreshing"
          :title="t(explorer.refreshing ? 'files.refreshing' : 'files.refresh')" :aria-label="t('files.refresh')" @click="explorer.refresh()">
          <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" aria-hidden="true" :class="{spinning:explorer.refreshing}"><path d="M20 11a8 8 0 1 0-2 6M20 4v7h-7"/></svg>
        </button>
      </form>
      <div v-if="explorer.searchActive" class="content-results">
        <p v-if="!explorer.searching && !explorer.error && !explorer.searchResults.length"
          class="explorer-state">{{ t(explorer.searchMode === 'name' ? 'files.noNameResults' : 'files.noContentResults') }}</p>
        <button v-for="hit in explorer.searchResults" :key="hit.path" @click="chooseResult(hit)">
          <span class="file-icon">⌕</span>
          <span><strong>{{ hit.path }}</strong><small v-if="hit.snippet">{{ hit.snippet }}</small></span>
        </button>
        <button v-if="explorer.searchNextCursor" class="load-more"
          @click="explorer.search(true)">{{ t('files.loadMore') }}</button>
      </div>
      <template v-else>
        <div class="file-tree" role="tree">
          <template v-for="row in treeRows" :key="row.kind === 'node' ? row.node.path : `more:${row.path}`">
            <button v-if="row.kind === 'node'" role="treeitem"
              :aria-expanded="row.node.type === 'directory' ? explorer.expandedPaths.includes(row.node.path) : undefined"
              :class="{ selected: explorer.selectedPath === row.node.path }"
              :style="{ paddingLeft: `${8 + row.depth * 15}px` }"
              :draggable="row.node.type === 'file'"
              @click="selectNode(row.node)"
              @keydown="onNodeKeydown($event, row.node)" @dragstart="onDragStart($event, row.node)">
              <span class="file-icon" aria-hidden="true">{{ row.node.type === 'directory'
                ? explorer.expandedPaths.includes(row.node.path) ? '▾' : '▸' : '·' }}</span>
              <span :title="row.node.path">{{ row.node.name }}</span>
              <i v-if="row.node.gitStatus" :data-status="gitStatusLabel(row.node.gitStatus)"
                :title="row.node.gitStatus">{{ gitStatusLabel(row.node.gitStatus) }}</i>
            </button>
            <button v-else class="load-more tree-more" :style="{ paddingLeft: `${24 + row.depth * 15}px` }"
              @click="explorer.loadDirectory(row.path, true)">{{ t('files.loadMore') }}</button>
          </template>
        </div>
      </template>
      <div v-if="explorer.refreshing || explorer.searching" class="explorer-state" role="status">{{ t(explorer.refreshing ? 'files.refreshing' : 'files.loading') }}</div>
      <div v-if="explorer.error" class="explorer-state error">{{ explorer.error }}</div>
    </div>
  </section>
</template>

<style scoped>
.workspace-explorer { border-top:1px solid var(--border); max-height:44%; display:flex; flex-direction:column; }
.explorer-toggle { border:0; border-radius:0; padding:8px 14px; display:flex; justify-content:space-between; background:var(--bg2); font-weight:650; }
.explorer-body { min-height:0; overflow:auto; padding:8px; background:var(--bg); }
.file-toolbar { display:flex; align-items:center; gap:3px; margin-bottom:7px; min-height:34px; padding:0 3px; border:1px solid var(--border); border-radius:7px; background:var(--surface-1); }
.file-toolbar select { flex:0 0 auto; width:65px; padding:5px 0 5px 3px; border:0; background:transparent; color:var(--text-secondary); font-size:11px; cursor:pointer; }
.file-toolbar select option { background:var(--surface-1); color:var(--text-primary); }
.file-search-input { display:flex; align-items:center; min-width:0; flex:1; }
.file-search-input input { width:100%; min-width:0; padding:5px 2px; border:0; outline:0; background:transparent; font-size:12px; }
.file-toolbar button { flex:0 0 25px; width:25px; height:28px; padding:0; display:grid; place-items:center; border:0; border-radius:4px; background:transparent; color:var(--text-muted); }
.file-toolbar button:hover:not(:disabled) { background:var(--surface-hover); color:var(--text-primary); }
.file-toolbar button:disabled { opacity:.4; cursor:default; }
.file-toolbar:focus-within { border-color:var(--accent); }
.file-toolbar .clear-search { flex-basis:18px; width:18px; }
.spinning { animation:files-spin 1s linear infinite; }
@keyframes files-spin { to { transform:rotate(360deg); } }
@media (prefers-reduced-motion:reduce) { .spinning { animation:none; } }
.file-tree,.content-results { display:flex; flex-direction:column; }
.file-tree > button,.content-results > button {
  width:100%;
  min-height:30px;
  display:flex;
  align-items:center;
  gap:6px;
  border:0;
  border-radius:5px;
  background:transparent;
  padding:4px 7px;
  color:var(--text-secondary);
  font-size:12px;
  text-align:left;
}
.file-tree > button:hover,.content-results > button:hover,.file-tree > button.selected {
  background:var(--surface-hover);
  color:var(--text-primary);
}
.file-tree > button > span:nth-child(2) { min-width:0; flex:1; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
.file-tree i {
  min-width:18px;
  padding:1px 3px;
  border:1px solid var(--border);
  border-radius:4px;
  color:var(--warning);
  font:12px var(--font-mono);
  text-align:center;
}
.file-tree i[data-status="D"],.file-tree i[data-status="Conflict"] { color:var(--danger); }
.file-icon { flex:0 0 12px; color:var(--text-muted); }
.tree-more { color:var(--accent) !important; }
.content-results > button > span:last-child { min-width:0; display:grid; gap:2px; }
.content-results strong,.content-results small { overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
.content-results small { color:var(--text-muted); font-size:12px; }
.explorer-state { padding:7px; color:var(--text-muted); font-size:12px; }
.explorer-state.error { color:var(--danger); }
.workspace-explorer.embedded { min-height:0; max-height:none; flex:1; border-top:1px solid var(--border); }
.embedded .explorer-body { padding:8px 7px 12px; }
</style>
