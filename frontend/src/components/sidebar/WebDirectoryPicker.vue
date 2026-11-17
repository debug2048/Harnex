<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { supervisor, type SupervisorDirectoryListing } from '../../api/client'
import { t } from '../../i18n'
import { errorMessage } from '../../utils/unknown'

defineProps<{ submitting: boolean; externalError?: string }>()

const emit = defineEmits<{
  back: []
  select: [path: string]
}>()

const listing = ref<SupervisorDirectoryListing | null>(null)
const address = ref('')
const selectedPath = ref('')
const loading = ref(false)
const error = ref('')
let navigationId = 0

const selectedName = computed(() => {
  const match = listing.value?.directories.find(item => item.path === selectedPath.value)
  if (match) return match.name
  const parts = selectedPath.value.split(/[\\/]/).filter(Boolean)
  return parts[parts.length - 1] || selectedPath.value
})

async function navigate(path?: string) {
  const id = ++navigationId
  loading.value = true
  error.value = ''
  try {
    const result = await supervisor.browseDirectory(path)
    if (id !== navigationId) return
    listing.value = result
    address.value = result.path
    selectedPath.value = result.path
  } catch (cause: unknown) {
    if (id === navigationId) error.value = errorMessage(cause)
  } finally {
    if (id === navigationId) loading.value = false
  }
}

function openDirectory(path: string) {
  void navigate(path)
}

function submitAddress() {
  if (address.value.trim()) void navigate(address.value)
}

onMounted(() => { void navigate() })
</script>

<template>
  <div class="directory-picker">
    <div class="directory-toolbar">
      <button type="button" class="icon-button back-button" :title="t('workspace.backToRecent')"
        :aria-label="t('workspace.backToRecent')" :disabled="submitting" @click="emit('back')">
        <svg width="17" height="17" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8">
          <path d="m15 18-6-6 6-6" />
        </svg>
      </button>
      <button type="button" class="icon-button" :title="t('workspace.parentDirectory')"
        :aria-label="t('workspace.parentDirectory')" :disabled="loading || !listing?.parentPath"
        @click="listing?.parentPath && openDirectory(listing.parentPath)">
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8">
          <path d="m18 15-6-6-6 6" />
        </svg>
      </button>
      <form class="directory-address" @submit.prevent="submitAddress">
        <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" aria-hidden="true">
          <path d="M3 6.5h7l2 2h9v10H3z" />
        </svg>
        <input v-model="address" :aria-label="t('workspace.directoryAddress')"
          :placeholder="t('workspace.directoryPlaceholder')" autocomplete="off" spellcheck="false"
          :disabled="loading || submitting" />
      </form>
    </div>

    <div class="directory-browser">
      <aside class="directory-locations" :aria-label="t('workspace.locations')">
        <p>{{ t('workspace.locations') }}</p>
        <button v-for="location in listing?.locations || []" :key="location.path" type="button"
          :class="{ active: listing?.path === location.path }" :disabled="loading || submitting"
          :title="location.path" @click="openDirectory(location.path)">
          <svg v-if="location.kind === 'home'" width="15" height="15" viewBox="0 0 24 24"
            fill="none" stroke="currentColor" stroke-width="1.7" aria-hidden="true">
            <path d="m3 11 9-8 9 8M5 10v10h14V10M9 20v-6h6v6" />
          </svg>
          <svg v-else width="15" height="15" viewBox="0 0 24 24" fill="none"
            stroke="currentColor" stroke-width="1.7" aria-hidden="true">
            <rect x="3" y="5" width="18" height="14" rx="2" /><path d="M7 15h.01M3 11h18" />
          </svg>
          <span>{{ location.kind === 'home' ? t('workspace.home') : location.name }}</span>
        </button>
      </aside>

      <div class="directory-content" :aria-busy="loading">
        <div v-if="loading && !listing" class="directory-state">
          <span class="directory-spinner" aria-hidden="true" />{{ t('workspace.loadingDirectories') }}
        </div>
        <div v-else-if="error && !listing" class="directory-state error-state" role="alert">
          <span>{{ error }}</span>
          <button type="button" @click="navigate(address || undefined)">{{ t('workspace.retry') }}</button>
        </div>
        <template v-else>
          <div class="directory-list-head">
            <span>{{ t('workspace.folderName') }}</span>
            <small>{{ t('workspace.doubleClickHint') }}</small>
          </div>
          <div class="directory-list" role="listbox" :aria-label="t('workspace.folders')">
            <button v-for="directory in listing?.directories || []" :key="directory.path" type="button"
              role="option" :aria-selected="selectedPath === directory.path"
              :class="{ selected: selectedPath === directory.path, hidden: directory.hidden }"
              :disabled="loading || submitting" @click="selectedPath = directory.path"
              @dblclick="openDirectory(directory.path)" @keydown.enter.prevent="openDirectory(directory.path)">
              <span class="folder-icon" aria-hidden="true">
                <svg width="17" height="17" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7">
                  <path d="M3 6.5h7l2 2h9v10H3z" />
                </svg>
              </span>
              <span>{{ directory.name }}</span>
              <svg class="folder-enter" width="14" height="14" viewBox="0 0 24 24" fill="none"
                stroke="currentColor" stroke-width="1.8" aria-hidden="true">
                <path d="m9 18 6-6-6-6" />
              </svg>
            </button>
            <div v-if="!loading && listing && listing.directories.length === 0" class="directory-state">
              {{ t('workspace.emptyDirectory') }}
            </div>
          </div>
          <div v-if="loading" class="directory-loading-overlay" aria-hidden="true">
            <span class="directory-spinner" />
          </div>
        </template>
      </div>
    </div>

    <div v-if="(error || externalError) && listing" class="directory-inline-error" role="alert">
      {{ error || externalError }}
    </div>
    <footer class="directory-footer">
      <div class="directory-selection">
        <small>{{ t('workspace.selectedFolder') }}</small>
        <strong :title="selectedPath">{{ selectedName }}</strong>
        <span :title="selectedPath">{{ selectedPath }}</span>
      </div>
      <button type="button" class="modal-cancel" :disabled="submitting" @click="emit('back')">
        {{ t('workspace.cancel') }}
      </button>
      <button type="button" class="modal-confirm" :disabled="loading || submitting || !selectedPath"
        @click="emit('select', selectedPath)">
        <span v-if="submitting" class="button-spinner" aria-hidden="true" />
        {{ submitting ? t('workspace.adding') : t('workspace.selectFolder') }}
      </button>
    </footer>
  </div>
</template>

<style scoped>
.directory-picker { min-height:0; display:flex; flex-direction:column; }
.directory-toolbar {
  display:grid; grid-template-columns:34px 34px minmax(0,1fr); gap:6px;
  padding:10px 14px; border-bottom:1px solid var(--border); background:var(--surface-1);
}
.icon-button {
  width:34px; height:34px; display:grid; place-items:center; border:1px solid var(--border);
  border-radius:8px; color:var(--text-secondary); background:var(--surface-2);
}
.icon-button:hover:not(:disabled) { border-color:var(--border-strong); background:var(--surface-hover); color:var(--text-primary); }
.directory-address { position:relative; min-width:0; }
.directory-address svg {
  position:absolute; z-index:1; top:9px; left:10px; color:var(--text-muted); pointer-events:none;
}
.directory-address input {
  width:100%; height:34px; padding:0 10px 0 32px; border-radius:8px;
  background:var(--bg); color:var(--text-primary); font:12px var(--font-mono);
}
.directory-browser { min-height:300px; height:min(410px,calc(100vh - 260px)); display:grid; grid-template-columns:142px minmax(0,1fr); }
.directory-locations {
  min-width:0; padding:13px 9px; border-right:1px solid var(--border); background:var(--surface-1);
}
.directory-locations p {
  margin:0 8px 7px; color:var(--text-faint); font-size:10px; font-weight:700;
  letter-spacing:.08em; text-transform:uppercase;
}
.directory-locations button {
  width:100%; height:34px; display:flex; align-items:center; gap:8px; padding:0 9px;
  border-radius:7px; color:var(--text-secondary); text-align:left;
}
.directory-locations button:hover:not(:disabled),.directory-locations button.active {
  background:var(--surface-hover); color:var(--text-primary);
}
.directory-locations button.active { box-shadow:inset 2px 0 var(--accent); }
.directory-locations button span { overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
.directory-content { position:relative; min-width:0; min-height:0; display:flex; flex-direction:column; background:var(--bg); }
.directory-list-head {
  height:35px; flex:0 0 35px; display:flex; align-items:center; justify-content:space-between;
  padding:0 14px; border-bottom:1px solid var(--border); color:var(--text-muted); font-size:11px;
}
.directory-list-head small { color:var(--text-faint); font-size:10.5px; }
.directory-list { min-height:0; overflow-y:auto; padding:5px; }
.directory-list > button {
  width:100%; min-height:37px; display:grid; grid-template-columns:28px minmax(0,1fr) 18px;
  align-items:center; gap:7px; padding:3px 8px; border:1px solid transparent;
  border-radius:7px; color:var(--text-secondary); text-align:left;
}
.directory-list > button:hover:not(:disabled) { background:var(--surface-hover); color:var(--text-primary); }
.directory-list > button.selected {
  border-color:color-mix(in srgb,var(--accent) 42%,var(--border));
  background:var(--accent-soft); color:var(--text-primary);
}
.directory-list > button.hidden { opacity:.68; }
.folder-icon { width:27px; height:27px; display:grid; place-items:center; color:var(--accent-hover); }
.folder-enter { justify-self:end; color:var(--text-faint); opacity:0; }
.directory-list > button:hover .folder-enter,.directory-list > button.selected .folder-enter { opacity:1; }
.directory-state {
  min-height:100%; display:flex; align-items:center; justify-content:center; gap:9px;
  padding:24px; color:var(--text-muted); font-size:12px; text-align:center;
}
.error-state { flex-direction:column; color:var(--danger); }
.error-state button { padding:5px 9px; border:1px solid var(--border); border-radius:6px; color:var(--text-secondary); }
.directory-loading-overlay { position:absolute; inset:35px 0 0; display:grid; place-items:center; background:color-mix(in srgb,var(--bg) 78%,transparent); }
.directory-spinner,.button-spinner {
  width:15px; height:15px; display:inline-block; border:2px solid var(--border-strong);
  border-top-color:var(--accent); border-radius:50%; animation:directory-spin .7s linear infinite;
}
.directory-inline-error {
  margin:9px 14px 0; padding:8px 10px; border:1px solid color-mix(in srgb,var(--danger) 35%,var(--border));
  border-radius:7px; background:var(--danger-soft); color:var(--danger); font-size:11.5px;
}
.directory-footer {
  min-height:68px; display:flex; align-items:center; gap:8px; padding:10px 14px;
  border-top:1px solid var(--border); background:var(--surface-1);
}
.directory-selection { min-width:0; flex:1; display:grid; grid-template-columns:auto minmax(0,1fr); gap:1px 8px; }
.directory-selection small { grid-row:1 / span 2; align-self:center; color:var(--text-faint); font-size:10.5px; }
.directory-selection strong,.directory-selection span { overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
.directory-selection strong { color:var(--text-primary); font-size:11.5px; }
.directory-selection span { color:var(--text-muted); font:10.5px var(--font-mono); }
.directory-footer > button { min-height:34px; padding:0 13px; border:1px solid var(--border); border-radius:8px; font-size:12px; }
.directory-footer .modal-cancel:hover:not(:disabled) { background:var(--surface-hover); }
.directory-footer .modal-confirm {
  min-width:108px; display:flex; align-items:center; justify-content:center; gap:7px;
  border-color:var(--accent); background:var(--accent); color:var(--accent-contrast);
}
@keyframes directory-spin { to { transform:rotate(360deg); } }
@media (max-width:640px) {
  .directory-browser { grid-template-columns:1fr; height:min(440px,calc(100vh - 230px)); }
  .directory-locations { display:flex; gap:5px; overflow-x:auto; padding:7px; border-right:0; border-bottom:1px solid var(--border); }
  .directory-locations p { display:none; }
  .directory-locations button { width:auto; flex:0 0 auto; }
  .directory-selection { display:none; }
  .directory-list-head small { display:none; }
}
</style>
