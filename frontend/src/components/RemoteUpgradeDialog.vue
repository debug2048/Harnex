<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { remoteConnections, type RemoteAttempt } from '../api/client'
import { locale, t } from '../i18n'
import { errorMessage } from '../utils/unknown'
const attempt = ref<RemoteAttempt | null>(null), answer = ref(''), error = ref(''), submitting = ref(false)
const panel=ref<HTMLElement|null>(null),answerInput=ref<HTMLInputElement|null>(null)
let previousFocus:HTMLElement|null=null
let disposed = false, timer: ReturnType<typeof setTimeout> | undefined
const label = (zh:string,en:string) => locale.value === 'zh-CN' ? zh : en
async function poll() {
  try {
    const next = await remoteConnections.pending()
    if (disposed) return
    const changed=next?.challenge?.id !== attempt.value?.challenge?.id
    if (changed) { answer.value='';submitting.value=false }
    attempt.value=next
    if(changed&&next?.challenge){await nextTick();(answerInput.value||panel.value)?.focus()}
  } catch(cause) { if(!disposed) error.value=errorMessage(cause) }
  if(!disposed) timer=setTimeout(poll,300)
}
async function reply(value=answer.value) {
  if(!attempt.value?.challenge || submitting.value)return
  const current=attempt.value;submitting.value=true;answer.value='';error.value=''
  try { await remoteConnections.reply(current.id,current.challenge!.id,value) }
  catch(cause) { if(!disposed){error.value=errorMessage(cause);submitting.value=false} }
}
async function cancel() { if(attempt.value)try {await remoteConnections.cancel(attempt.value.id)}catch(cause){error.value=errorMessage(cause)} }
function trapTab(event:KeyboardEvent){
  const items=Array.from(panel.value?.querySelectorAll<HTMLElement>('button:not(:disabled),input:not(:disabled)')||[])
  const first=items[0],last=items[items.length-1]
  if(!first){event.preventDefault();panel.value?.focus();return}
  if(event.shiftKey&&(document.activeElement===first||document.activeElement===panel.value)){event.preventDefault();last?.focus()}
  else if(!event.shiftKey&&(document.activeElement===last||document.activeElement===panel.value)){event.preventDefault();first.focus()}
}
onMounted(()=>{previousFocus=document.activeElement instanceof HTMLElement?document.activeElement:null;panel.value?.focus();void poll()})
onBeforeUnmount(()=>{disposed=true;answer.value='';if(timer)clearTimeout(timer);previousFocus?.focus()})
</script>
<template>
  <Teleport to="body"><section class="remote-upgrade-overlay" role="dialog" aria-modal="true" aria-labelledby="remote-upgrade-title">
    <div ref="panel" class="remote-upgrade-dialog" tabindex="-1" @keydown.tab="trapTab" @keydown.esc.stop.prevent="cancel"><h3 id="remote-upgrade-title">{{ label('更新远端服务', 'Update remote service') }}</h3>
      <p>{{ t(`remote.phase.${attempt?.phase || 'starting'}`) }}</p>
      <form v-if="attempt?.challenge" @submit.prevent="reply()"><strong>{{ t(attempt.challenge.kind==='hostKey'?'remote.trustTitle':'remote.authTitle') }}</strong>
        <pre>{{ attempt.challenge.prompt }}</pre>
        <button v-if="attempt.challenge.kind==='hostKey'" :disabled="submitting" type="button" @click="reply('yes')">{{ t('remote.trust') }}</button>
        <template v-else><label>{{ t('remote.answer') }}<input ref="answerInput" v-model="answer" type="password" autocomplete="off" :disabled="submitting" /></label><button :disabled="submitting">{{ t('remote.continue') }}</button></template>
      </form>
      <p v-if="error" role="alert">{{ error }}</p>
      <p class="hint">{{ label('只在空闲时更新，不会取消运行中的任务。取消会结束本机等待，已经开始的服务重启不能撤销。', 'Only an idle service is updated; running tasks are not cancelled. Cancelling ends the local wait and cannot undo a restart already in progress.') }}</p>
      <button type="button" :disabled="!attempt" @click="cancel">{{ label('取消等待', 'Cancel wait') }}</button>
    </div>
  </section></Teleport>
</template>
<style scoped>
.remote-upgrade-overlay{position:fixed;inset:0;z-index:250;display:grid;place-items:center;padding:18px;background:#0007}
.remote-upgrade-dialog{width:min(480px,100%);box-sizing:border-box;border:1px solid var(--border);border-radius:12px;padding:20px;background:var(--surface-1);color:var(--text-primary)}
p,pre{font-size:13px;line-height:1.6;overflow-wrap:anywhere;white-space:pre-wrap}.hint{color:var(--text-muted)}
label{display:grid;gap:6px;font-size:13px}input{padding:9px;background:var(--bg);color:inherit;border:1px solid var(--border);border-radius:6px}
button{margin-top:10px;padding:7px 12px;background:var(--surface-2);color:inherit;border:1px solid var(--border);border-radius:6px;cursor:pointer}button:disabled{opacity:.5;cursor:wait}
</style>
