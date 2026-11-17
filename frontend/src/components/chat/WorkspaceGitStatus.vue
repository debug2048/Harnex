<script setup lang="ts">
import { computed, onMounted, onActivated, onDeactivated, onUnmounted, watch } from 'vue'
import { useWorkspaceStore } from '../../stores/workspace'
import { useWorkspaceGitStore } from '../../stores/workspaceGit'
import { useRunStore } from '../../stores/run'
import { t } from '../../i18n'

const workspace = useWorkspaceStore()
const status = useWorkspaceGitStore()
const run = useRunStore()
const label = computed(() => {
  const git = status.git
  if (git?.state === 'branch') return git.branch
  if (git?.state === 'detached') return `HEAD · ${git.commit}`
  if (git?.state === 'unavailable') return t('workspace.gitUnavailable')
  return null
})
const title = computed(() => `${workspace.active?.root || ''}\n${t('workspace.currentGitBranch')}: ${label.value}`)
onMounted(status.start)
onActivated(status.start)
onDeactivated(status.stop)
onUnmounted(status.stop)
watch(() => run.streaming, (running, previous) => { if (previous && !running) void status.refresh() })
</script>

<template>
  <div v-if="label" class="workspace-git-status" :title="title">
    <span class="git-project">{{ workspace.active?.name }}</span>
    <span aria-hidden="true">·</span>
    <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" aria-hidden="true">
      <circle cx="6" cy="5" r="3" /><circle cx="6" cy="19" r="3" /><circle cx="18" cy="6" r="3" />
      <path d="M6 8v8M18 9a10 10 0 0 1-9 10" />
    </svg>
    <span class="git-branch" :aria-label="`${t('workspace.currentGitBranch')}: ${label}`">{{ label }}</span>
  </div>
</template>

<style scoped>
.workspace-git-status { display:flex; align-items:center; gap:5px; min-width:0; margin-top:3px; color:var(--text-muted); font-size:11px; line-height:14px; font-weight:400; }
.workspace-git-status svg { flex:0 0 auto; }
.git-project { flex:0 1 auto; max-width:35%; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
.git-branch { min-width:0; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
</style>
