<script setup lang="ts">
import { nextTick, ref, watch } from 'vue'
import { useTaskHandoffStore } from '../../stores/taskHandoff'
import { useWorkspaceStore } from '../../stores/workspace'
import { t } from '../../i18n'
const handoff = useTaskHandoffStore(), workspace = useWorkspaceStore()
const panel = ref<HTMLElement | null>(null)
let previous: HTMLElement | null = null
watch(() => handoff.visible, async visible => {
  if (visible) { previous = document.activeElement as HTMLElement; await nextTick(); panel.value?.querySelector<HTMLTextAreaElement>('.handoff-instruction')?.focus() }
  else if (previous?.isConnected) previous.focus()
})
async function prepare() { await handoff.prepare() }
function keydown(event: KeyboardEvent) {
  if (event.key === 'Escape') { event.preventDefault(); handoff.close(); return }
  if (event.key !== 'Tab') return
  const elements = [...(panel.value?.querySelectorAll<HTMLElement>('button:not(:disabled),textarea,select,input') || [])]
  if (event.shiftKey && document.activeElement === elements[0]) { elements[elements.length-1]?.focus(); event.preventDefault() }
  else if (!event.shiftKey && document.activeElement === elements[elements.length-1]) { elements[0]?.focus(); event.preventDefault() }
}
</script>
<template>
  <Teleport to="body"><div v-if="handoff.visible" class="handoff-scrim" @click.self="handoff.close">
    <section ref="panel" class="handoff-dialog" role="dialog" aria-modal="true" aria-labelledby="handoff-title" @keydown="keydown">
      <header><h2 id="handoff-title">{{ t('handoff.title') }}</h2><button :aria-label="t('common.close')" @click="handoff.close">×</button></header>
      <p>{{ t('handoff.description', { source: handoff.source }) }}</p>
      <label>{{ t('handoff.instruction') }}<textarea v-model="handoff.instruction" class="handoff-instruction" rows="3" maxlength="10000" :disabled="handoff.busy" /></label>
      <label>{{ t('handoff.context') }}<textarea v-model="handoff.text" rows="6" :disabled="handoff.busy" /></label>
      <p v-if="handoff.text.length > 100000" role="alert">{{ t('handoff.tooLong') }}</p>
      <label v-for="item in handoff.attachments" :key="item.id" class="attachment"><input v-model="item.selected" type="checkbox" :disabled="handoff.busy" /><span>{{ item.name }}<small v-if="item.error">{{ item.error }}</small></span></label>
      <p v-if="handoff.loading" role="status">{{ t('common.loading') }}</p>
      <label>{{ t('handoff.project') }}<select v-model="handoff.projectId" :disabled="handoff.busy"><option value="" disabled>{{ t('handoff.chooseProject') }}</option><option v-for="project in workspace.workspaces" :key="project.id" :value="project.id">{{ project.name }}{{ project.remoteProfile ? ` · SSH ${project.remoteHost || project.remoteProfile}` : '' }}</option></select></label>
      <button v-if="handoff.projectId !== workspace.activeWorkspaceId || workspace.lifecycle !== 'READY'" :disabled="handoff.loading || handoff.busy || !workspace.available" @click="workspace.requestOpen(handoff.projectId || null)">{{ t('handoff.openProject') }}</button>
      <p v-if="handoff.error" role="alert">{{ handoff.error }}</p>
      <footer><button @click="handoff.close">{{ t('common.cancel') }}</button><button :disabled="handoff.loading || handoff.busy || !handoff.instruction.trim() || handoff.text.length > 100000 || !handoff.projectId || handoff.projectId !== workspace.activeWorkspaceId || workspace.lifecycle !== 'READY'" @click="prepare">{{ t(handoff.busy ? 'handoff.preparing' : 'handoff.prepare') }}</button></footer>
    </section>
  </div></Teleport>
</template>
<style scoped>
.handoff-scrim { position:fixed; inset:0; z-index:95; padding:18px; display:grid; place-items:center; background:#0006; }
.handoff-dialog { width:min(600px,100%); max-height:calc(100dvh - 36px); overflow:auto; display:grid; gap:14px; padding:20px; background:var(--surface-1); color:var(--text-primary); border:1px solid var(--border); border-radius:12px; }
header,footer { display:flex; align-items:center; gap:10px; justify-content:space-between; } h2 { margin:0; font-size:16px; } p { margin:0; color:var(--text-muted); font-size:12px; line-height:1.6; }
label { display:grid; gap:6px; font-size:12px; min-width:0; } textarea,select { width:100%; min-width:0; border:1px solid var(--border); border-radius:6px; background:var(--surface-2); color:inherit; padding:8px; } textarea { resize:vertical; }
button { border:1px solid var(--border); border-radius:6px; padding:7px 10px; color:inherit; background:var(--surface-2); cursor:pointer; } button:disabled { opacity:.5; } footer { justify-content:flex-end; }
.attachment { display:flex; align-items:center; } .attachment span { overflow-wrap:anywhere; } .attachment small { display:block; color:var(--danger,#c44); }
[role=alert] { color:var(--danger,#c44); }
</style>
