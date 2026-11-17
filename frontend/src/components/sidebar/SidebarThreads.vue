<script setup lang="ts">
import type { Hit as SearchHit } from '../../../../packages/protocol/workbench'
import { computed, onUnmounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api, getRuntimeRevision, onRuntimeIdentityChanged } from '../../api/client'
import { t } from '../../i18n'
import { useRunController } from '../../services/runController'
import { useConversationStore } from '../../stores/conversation'
import { useThreadStore } from '../../stores/threads'
import { useRunStore } from '../../stores/run'
import { useWorkspaceStore } from '../../stores/workspace'
import { useHistoryLocationStore } from '../../stores/historyLocation'
import { errorMessage } from '../../utils/unknown'

const route = useRoute()
const router = useRouter()
const threads = useThreadStore()
const conversation = useConversationStore()
const workspace = useWorkspaceStore()
const untitledTitle = () => t(workspace.profile === 'PROJECT' ? 'sidebar.untitledTask' : 'sidebar.untitled')
const runController = useRunController()
const history = useHistoryLocationStore()
const threadGroups = computed(() => {
  const now = new Date()
  const today = new Date(now.getFullYear(), now.getMonth(), now.getDate()).getTime()
  const yesterday = today - 86_400_000
  const groups = [
    { key: 'today', label: t('sidebar.today'), items: [] as typeof threads.items },
    { key: 'yesterday', label: t('sidebar.yesterday'), items: [] as typeof threads.items },
    { key: 'earlier', label: t('sidebar.earlier'), items: [] as typeof threads.items },
  ]
  for (const item of threads.items) {
    const time = new Date(item.updatedAt).getTime()
    if (time >= today) groups[0].items.push(item)
    else if (time >= yesterday) groups[1].items.push(item)
    else groups[2].items.push(item)
  }
  return groups.filter(group => group.items.length)
})

const searchQuery = ref('')
const searchHits = ref<SearchHit[]>([])
const searchCursor = ref<string | null>(null)
const searching = ref(false)
const searchError = ref<string | null>(null)
const searchState = ref('READY')
const searchFocused = ref(false)
const searchInput = ref<HTMLInputElement | null>(null)
const cursor = ref(-1)
const deleting = ref(new Set<string>())
const deleteError = ref<string | null>(null)
let searchDebounce: ReturnType<typeof setTimeout> | null = null
let searchSeq = 0

function formatThreadTime(value: string) {
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return ''
  return new Intl.DateTimeFormat(undefined, { hour: '2-digit', minute: '2-digit' }).format(date)
}

function clearSearch() {
  searchQuery.value = ''
  searchHits.value = []
  searching.value = false
  searchError.value = null
  cursor.value = -1
  searchCursor.value = null
  searchSeq++
  if (searchDebounce) {
    clearTimeout(searchDebounce)
    searchDebounce = null
  }
}

function pick(id: string, hit?: SearchHit) {
  const revision = getRuntimeRevision()
  void runController.loadThread(id).then(async () => {
    if (hit && revision === getRuntimeRevision() && conversation.threadId === id) await history.open(hit)
  }).catch(cause => { if (revision === getRuntimeRevision()) useRunStore().error = errorMessage(cause) })
  threads.select(id)
  if (searchQuery.value) clearSearch()
  const target = `/t/${id}`
  if (route.path !== target) {
    router.push(target).catch(error => console.warn('[sidebar] navigation failed:', target, error))
  }
}

async function runSearch(query: string, sequence: number, after?: string) {
  try {
    const result = await api.search(query, after)
    if (sequence !== searchSeq) return
    searchHits.value = after ? [...searchHits.value, ...(result.items || [])] : result.items || []
    searchState.value = result.state || 'READY'
    if (result.state === 'UNAVAILABLE') searchError.value = t('history.searchUnavailable')
    searchCursor.value = result.nextCursor || null
    cursor.value = searchHits.value.length ? 0 : -1
  } catch (cause: unknown) {
    if (sequence !== searchSeq) return
    searchError.value = errorMessage(cause, t('sidebar.searchFailed'))
    searchHits.value = []
    cursor.value = -1
  } finally {
    if (sequence === searchSeq) searching.value = false
  }
}

function onSearchInput() {
  searchError.value = null
  cursor.value = -1
  if (searchDebounce) clearTimeout(searchDebounce)
  const query = searchQuery.value.trim()
  if (!query) {
    searching.value = false
    searchHits.value = []
    searchCursor.value = null
  searchSeq++
    return
  }
  searching.value = true
  const sequence = ++searchSeq
  searchDebounce = setTimeout(() => runSearch(query, sequence), 220)
}

function onSearchKeydown(event: KeyboardEvent) {
  if (event.key === 'Escape') {
    if (searchQuery.value) clearSearch()
    else searchInput.value?.blur()
    return
  }
  if (!searchHits.value.length) return
  if (event.key === 'ArrowDown') {
    event.preventDefault()
    cursor.value = (cursor.value + 1) % searchHits.value.length
  } else if (event.key === 'ArrowUp') {
    event.preventDefault()
    cursor.value = cursor.value <= 0 ? searchHits.value.length - 1 : cursor.value - 1
  } else if (event.key === 'Enter') {
    event.preventDefault()
    const hit = searchHits.value[Math.max(cursor.value, 0)]
    if (hit?.conversationId) pick(hit.conversationId, hit)
  }
}

function escapeHtml(value: string) {
  return value.replace(/[&<>"']/g, character =>
    ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[character] as string))
}
function escapeRegex(value: string) {
  return value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
}
function highlight(text: string | null | undefined, query: string) {
  const safe = escapeHtml(text || '')
  const terms = query.trim().split(/\s+/).filter(Boolean).map(escapeRegex).sort((a, b) => b.length - a.length)
  if (!terms.length) return safe
  return safe.replace(new RegExp(`(${terms.join('|')})`, 'gi'), '<mark class="hl">$1</mark>')
}

function newThread() {
  runController.reset()
  threads.select(null)
  void router.push('/')
}

async function removeThread(id: string, event: Event) {
  event.stopPropagation()
  if (deleting.value.has(id)) return
  const thread = threads.items.find(item => item.id === id)
  if (!window.confirm(t('sidebar.deleteConfirm', { title: thread?.title || untitledTitle() }))) return
  const revision = getRuntimeRevision()
  deleting.value.add(id)
  deleteError.value = null
  try {
    if (!await threads.remove(id) || revision !== getRuntimeRevision()) return
    if (conversation.threadId === id) {
      runController.reset()
      if (route.params.tid === id) await router.replace('/')
    }
  } catch (cause) {
    if (revision === getRuntimeRevision()) deleteError.value = errorMessage(cause)
  } finally {
    deleting.value.delete(id)
  }
}

const unsubscribe = onRuntimeIdentityChanged(() => { clearSearch(); deleteError.value = null })
onUnmounted(() => {
  unsubscribe()
  if (searchDebounce) clearTimeout(searchDebounce)
})
</script>

<template>
  <section class="sidebar-threads">
    <div class="new-thread-wrap">
    <button class="new-thread" @click="newThread">
      <svg width="14" height="14" viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="2">
        <line x1="8" y1="3" x2="8" y2="13"/><line x1="3" y1="8" x2="13" y2="8"/>
      </svg>
      {{ t(workspace.profile === 'PROJECT' ? 'sidebar.newTask' : 'sidebar.newChat') }}
    </button>
    </div>

    <div class="sidebar-search-wrap">
      <div :class="['sidebar-search', { focused: searchFocused, hasQuery: !!searchQuery }]">
      <svg class="search-icon" width="14" height="14" viewBox="0 0 24 24" fill="none"
        stroke="currentColor" stroke-width="2" stroke-linecap="round">
        <circle cx="11" cy="11" r="7"/><line x1="21" y1="21" x2="16.65" y2="16.65"/>
      </svg>
      <input ref="searchInput" v-model="searchQuery" type="text" autocomplete="off" spellcheck="false"
        :placeholder="t(workspace.profile === 'PROJECT' ? 'sidebar.searchTasks' : 'sidebar.searchConversations')" class="search-input"
        @input="onSearchInput" @keydown="onSearchKeydown"
        @focus="searchFocused = true" @blur="searchFocused = false" />
        <button v-if="searchQuery" class="search-clear" :title="t('sidebar.searchClear') + '  (Esc)'"
          @click="clearSearch">
          <svg width="10" height="10" viewBox="0 0 16 16" fill="none" stroke="currentColor"
            stroke-width="2" stroke-linecap="round">
            <line x1="3" y1="3" x2="13" y2="13"/><line x1="13" y1="3" x2="3" y2="13"/>
          </svg>
        </button>
      </div>
    </div>

    <div class="thread-scroll">
    <div v-if="workspace.profile === 'PERSONAL' && !searchQuery.trim()" class="thread-group-label">{{ t('sidebar.conversationRecords') }}</div>
    <template v-if="searchQuery.trim()">
      <div class="search-header">
        <span class="search-count">
          <template v-if="searching">{{ t('sidebar.searchSearching') }}</template>
          <template v-else-if="searchError">{{ t('sidebar.searchEmpty') }}</template>
          <template v-else>{{ searchHits.length }} {{ searchHits.length === 1
            ? t('sidebar.searchResultOne') : t('sidebar.searchResultMany') }}</template>
        </span>
        <span v-if="searchHits.length > 1" class="search-kbd">
          <kbd>↑</kbd><kbd>↓</kbd><span class="search-kbd-sep">·</span><kbd>↵</kbd>
        </span>
      </div>
      <p v-if="searchState === 'CATCHING_UP' || searchState === 'REBUILDING'" role="status" class="search-state">{{ t('history.indexing') }}</p>
      <div v-if="searching" class="search-state"><div class="search-spinner" /></div>
      <div v-else-if="searchError" class="search-state search-state-error">
        <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor"
          stroke-width="1.6" stroke-linecap="round">
          <circle cx="12" cy="12" r="9"/><line x1="12" y1="8" x2="12" y2="13"/><circle cx="12" cy="16" r="0.5"/>
        </svg>
        <div>{{ searchError }}</div>
      </div>
      <div v-else-if="!searchHits.length" class="search-state">
        <svg width="28" height="28" viewBox="0 0 24 24" fill="none" stroke="currentColor"
          stroke-width="1.4" stroke-linecap="round" opacity="0.4">
          <circle cx="11" cy="11" r="7"/><line x1="21" y1="21" x2="16.65" y2="16.65"/>
        </svg>
        <div class="search-state-title">{{ t('sidebar.searchEmpty') }}</div>
        <div class="search-state-hint">{{ t('sidebar.searchEmptyHint') }}</div>
      </div>
      <div v-else class="search-results">
        <button v-for="(hit, index) in searchHits" :key="[hit.conversationId, hit.messageId, hit.executionRecordId, hit.sourceKind].join(':')" type="button"
          :class="['search-hit', { active: cursor === index || threads.current === hit.conversationId }]"
          @click="hit.conversationId && pick(hit.conversationId, hit)" @mouseenter="cursor = index">
          <div class="hit-row">
            <div class="hit-title" :title="hit.title || untitledTitle()"
              v-html="highlight(hit.title || untitledTitle(), searchQuery)" />
            <svg class="hit-arrow" width="12" height="12" viewBox="0 0 16 16" fill="none"
              stroke="currentColor" stroke-width="2" stroke-linecap="round">
              <polyline points="6 4 10 8 6 12"/>
            </svg>
          </div>
          <div v-if="hit.snippet" class="hit-snippet" v-html="highlight(hit.snippet, searchQuery)" />
        </button>
        <button v-if="searchCursor" class="load-older-btn" @click="runSearch(searchQuery.trim(), searchSeq, searchCursor)">{{ t('chat.loadHistory') }}</button>
      </div>
    </template>
    <template v-else>
      <p v-if="deleteError" class="search-state search-state-error" role="alert">{{ deleteError }}</p>
      <button v-if="threads.nextCursor" class="load-older-btn" @click="threads.loadMore">{{ t('chat.loadHistory') }}</button>
      <div v-if="threads.error" class="empty-threads" role="status">{{ t('sidebar.recordsLoadFailed') }} <button @click="threads.refresh()">{{ t('common.retry') }}</button></div>
      <div v-else-if="threads.loading && !threads.items.length" class="empty-threads" role="status">{{ t('common.loading') }}</div>
      <div v-else-if="!threads.items.length" class="empty-threads">{{ t(workspace.profile === 'PROJECT' ? 'sidebar.emptyTasks' : 'sidebar.empty') }}</div>
      <template v-for="group in threadGroups" :key="group.key">
        <div class="thread-group-label">{{ group.label }}</div>
        <div v-for="thread in group.items" :key="thread.id"
          :class="['thread-item-shell', { active: threads.current === thread.id }]">
          <div class="thread-item" role="button" tabindex="0" @click="pick(thread.id)"
            @keydown.enter.prevent="pick(thread.id)" @keydown.space.prevent="pick(thread.id)">
            <span class="thread-line">
              <strong class="thread-title" :title="thread.fullTitle || thread.title || untitledTitle()">
                {{ thread.title || untitledTitle() }}
              </strong>
              <time>{{ formatThreadTime(thread.updatedAt) }}</time>
            </span>
          </div>
          <button type="button" class="thread-delete"
            :disabled="deleting.has(thread.id)"
            :title="t('sidebar.deleteThread')" :aria-label="t('sidebar.deleteThread')"
            @click="removeThread(thread.id, $event)">✕</button>
        </div>
      </template>
    </template>
    </div>
  </section>
</template>

<style scoped>
.sidebar-threads { min-height:0; flex:1; display:flex; flex-direction:column; }
.new-thread-wrap { padding:0 14px; }
.new-thread {
  width:100%; height:36px; display:flex; align-items:center; justify-content:center; gap:6px;
  border:1px solid var(--border); border-radius:9px;
  background:var(--accent-soft); color:color-mix(in srgb,var(--accent-hover) 82%,var(--text-primary)); box-shadow:var(--shadow-sm);
}
.new-thread:hover { border-color:var(--accent); }
.sidebar-search-wrap { padding:10px 14px 0; }
.sidebar-search {
  height:32px; display:flex; align-items:center; gap:8px; padding:0 10px;
  border:1px solid transparent; border-radius:8px; background:var(--bg);
  transition:border-color var(--transition),background var(--transition),box-shadow var(--transition);
}
.sidebar-search:hover { border-color:color-mix(in srgb,var(--accent) 40%,var(--border)); }
.sidebar-search.focused { border-color:var(--border-strong); box-shadow:0 0 0 2px var(--accent-soft); }
.search-icon { flex-shrink:0; color:var(--text-muted); transition:color var(--transition); }
.sidebar-search.focused .search-icon,.sidebar-search.hasQuery .search-icon { color:var(--accent); }
.search-input {
  min-width:0; height:100%; flex:1; border:0; outline:0; background:transparent;
  padding:0; color:var(--text-primary); font:12px/1 inherit;
}
.search-input::placeholder { color:var(--text-muted); }
.search-clear {
  width:18px; height:18px; display:inline-flex; align-items:center; justify-content:center;
  border:0; border-radius:4px; background:transparent; color:var(--text-muted);
}
.search-clear:hover { background:var(--surface-3); color:var(--text-primary); }
.thread-scroll { min-height:0; flex:1; overflow-y:auto; padding:14px 10px; }
.search-header {
  display:flex; align-items:center; justify-content:space-between; padding:4px 8px 6px;
  color:var(--text-muted); font-size:12px; letter-spacing:.06em; text-transform:uppercase;
}
.search-count { font-weight:600; }
.search-kbd { display:inline-flex; align-items:center; gap:3px; }
.search-kbd kbd {
  min-width:16px; height:16px; display:inline-flex; align-items:center; justify-content:center;
  border:1px solid var(--border); border-radius:3px; background:var(--bg); padding:0 3px;
  color:var(--text-muted); font:12px/1 var(--font-mono);
}
.search-kbd-sep { padding:0 1px; font-size:12px; opacity:.5; }
.search-results { display:flex; flex-direction:column; gap:2px; padding:0 2px; }
.search-hit {
  width:100%; display:block;
  position:relative; padding:8px 10px 9px; border:1px solid transparent; border-radius:8px;
  background:transparent; text-align:left;
  transition:background var(--transition),border-color var(--transition);
}
.search-hit:hover { background:var(--surface-hover); }
.search-hit.active { border-color:color-mix(in srgb,var(--accent) 22%,transparent); background:var(--accent-soft); }
.hit-row { display:flex; align-items:center; gap:6px; }
.hit-title {
  min-width:0; flex:1; overflow:hidden; color:var(--text-primary); font-size:13px;
  font-weight:600; letter-spacing:-.005em; text-overflow:ellipsis; white-space:nowrap;
}
.hit-arrow {
  flex-shrink:0; color:var(--text-muted); opacity:0; transform:translateX(-2px);
  transition:all var(--transition);
}
.search-hit:hover .hit-arrow,.search-hit.active .hit-arrow {
  color:var(--accent); opacity:1; transform:translateX(0);
}
.hit-snippet {
  display:-webkit-box; margin-top:3px; overflow:hidden; color:var(--text-muted);
  font-size:12px; line-height:1.45; word-break:break-word;
  -webkit-box-orient:vertical; -webkit-line-clamp:2;
}
:deep(.hl) {
  border-radius:2px; background:color-mix(in srgb,var(--accent) 22%,transparent);
  padding:0 2px; color:var(--accent-hover); font-weight:600;
}
.search-state {
  display:flex; flex-direction:column; align-items:center; gap:6px; padding:28px 16px;
  color:var(--text-muted); font-size:12px; text-align:center;
}
.search-state-title { margin-top:2px; color:var(--text-primary); font-weight:600; }
.search-state-hint { font-size:12px; opacity:.75; }
.search-state-error { color:var(--danger); }
.search-spinner {
  width:16px; height:16px; border:2px solid var(--border); border-top-color:var(--accent);
  border-radius:50%; animation:sidebar-spin .65s linear infinite;
}
.empty-threads { padding:24px 12px; color:var(--text-muted); font-size:12px; text-align:center; }
.thread-group-label {
  padding:8px 8px 4px; color:var(--text-faint); font-size:12px;
  font-weight:500; letter-spacing:0;
}
.thread-item-shell {
  position:relative; min-height:51px; margin:2px 0;
  border:1px solid transparent; border-radius:8px;
  color:var(--text-secondary);
}
.thread-item-shell:hover { background:var(--surface-hover); }
.thread-item-shell.active {
  border-color:transparent;
  background:var(--accent-soft); color:var(--text-primary);
}
.thread-item {
  width:100%; min-height:49px; display:block; padding:8px 32px 8px 9px;
  border:0; border-radius:7px; background:transparent;
  color:inherit; text-align:left;
}
.thread-line { display:flex; align-items:center; gap:7px; }
.thread-title {
  min-width:0; flex:1; overflow:hidden; font-size:13px; font-weight:500;
  text-overflow:ellipsis; white-space:nowrap;
}
.thread-line time { color:var(--text-faint); font-size:11px; font-weight:400; }
.thread-delete {
  position:absolute; top:50%; margin-top:-11px; right:7px; width:22px; height:22px;
  border:0; border-radius:4px; background:transparent; color:var(--text-muted);
  font-size:12px; opacity:0;
  transition:opacity var(--transition);
}
.thread-item-shell:hover .thread-delete,.thread-delete:focus-visible { opacity:1; }
.thread-delete:hover { background:var(--danger-soft); color:var(--danger); }
@keyframes sidebar-spin { to { transform:rotate(360deg); } }
</style>
