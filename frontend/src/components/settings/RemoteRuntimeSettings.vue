<script setup lang="ts">
import { computed, onBeforeUnmount, ref } from 'vue'
import { supervisor } from '../../api/client'
import { locale } from '../../i18n'
import { useWorkspaceStore } from '../../stores/workspace'
import { errorMessage } from '../../utils/unknown'
import WorkspaceFailure from '../WorkspaceFailure.vue'

const workspace = useWorkspaceStore()
const project = computed(() => workspace.active)
const attached = computed(() => Boolean(project.value?.remoteService) || project.value?.remoteRuntime?.ownership === 'attached')
const busy = ref(false)
const error = ref('')
const message = ref('')
const confirmStop = ref(false)
let disposed = false
onBeforeUnmount(() => { disposed = true })
const label = (zh: string, en: string) => locale.value === 'zh-CN' ? zh : en
async function keepRunning(event: Event) {
  const current = project.value
  if (!current?.remoteProfile || !current.instanceId || busy.value) return
  busy.value = true; error.value = ''; workspace.error = null; message.value = ''; confirmStop.value = false
  try {
    const next = await supervisor.background(current.id, current.instanceId, (event.target as HTMLInputElement).checked)
    if (disposed || workspace.activeWorkspaceId !== current.id || workspace.instanceId !== current.instanceId) return
    workspace.workspaces = workspace.workspaces.map(item => item.id === next.id ? next : item)
    message.value = next.remoteKeepRunning
      ? label('已保存。离开此项目或退出应用时，远端任务和 IM 连接会继续运行。', 'Saved. Remote tasks and IM connections will keep running when you leave this project or quit the app.')
      : label('已保存。离开此项目或退出应用时，会停止远端服务。当前服务仍在运行。', 'Saved. Leaving this project or quitting the app will stop the remote service. It is still running now.')
  } catch (cause) { if (!disposed) error.value = errorMessage(cause) }
  finally {
    if (!disposed) {
      busy.value = false
      ;(event.target as HTMLInputElement).checked = project.value?.remoteKeepRunning === true
    }
  }
}
async function leave(stop: boolean) {
  if (busy.value || workspace.switching) return
  busy.value = true; error.value = ''; workspace.error = null
  try { await workspace.close(stop) }
  catch (cause) { if (!disposed) error.value = errorMessage(cause) }
  finally { if (!disposed) busy.value = false }
}
async function upgrade() {
  if (busy.value || workspace.switching || attached.value) return
  busy.value = true; error.value = ''; workspace.error = null
  try { await workspace.restart(true) }
  catch (cause) { if (!disposed) error.value = errorMessage(cause) }
  finally { if (!disposed) busy.value = false }
}
</script>

<template>
  <section v-if="project?.remoteProfile" class="remote-runtime-settings" aria-labelledby="remote-runtime-title">
    <h3 id="remote-runtime-title">{{ attached ? label('已连接服务器上的 Harnex 服务', 'Connected to an existing server service') : label('离开后，远端是否继续运行', 'Keep the remote workspace running after you leave') }}</h3>
    <p class="remote-target">SSH · {{ project.remoteHost || project.remoteProfile }} · {{ project.root }}</p>
    <p v-if="project.remoteRuntime">{{ label('运行版本：', 'Running version: ') }}{{ project.remoteRuntime.runtimeVersion }}<small v-if="project.remoteRuntime.runtimeSha256"> · {{ project.remoteRuntime.runtimeSha256.slice(0, 8) }}</small></p>
    <template v-if="attached">
      <p>{{ label('此服务由服务器独立管理。离开项目或退出应用只断开界面连接，不会停止服务；版本更新也请在服务器进行。', 'The server manages this service independently. Leaving the project or quitting only disconnects this interface. Update or stop the service on the server.') }}</p>
      <button type="button" :disabled="busy || workspace.switching" @click="leave(false)">{{ label('断开界面连接', 'Disconnect this interface') }}</button>
    </template>
    <template v-else>
    <div v-if="project.remoteRuntime?.updateAvailable" class="remote-update" role="status">
      <p>{{ label('检测到新的 Runtime 构建。当前连接继续使用原服务，更新不会自动进行。', 'A new Runtime build is available. This connection keeps using the running service until you choose to update.') }}</p>
      <template v-if="project.remoteRuntime.canUpgrade"><p>{{ label('更新会重启远端服务，仅在没有任务运行时执行；有任务时会提示稍后重试。', 'Updating restarts the remote service only while idle. If work is active, retry after it finishes.') }}</p>
        <button type="button" :disabled="busy || workspace.switching" @click="upgrade">{{ label('空闲时更新并重启', 'Update and restart if idle') }}</button></template>
      <p v-else>{{ label('当前旧服务尚不支持空闲升级。请先用下方按钮明确停止旧服务，再重新连接以启动新版。', 'This older service cannot upgrade atomically while idle. Explicitly stop it below, then reconnect to start the current version.') }}</p>
    </div>
    <label class="remote-keep"><input type="checkbox" :checked="project.remoteKeepRunning || false" :disabled="busy || workspace.switching" @change="keepRunning" />
      <span>{{ label('离开后保持远端运行', 'Keep running after leaving') }}
        <small>{{ label('切换项目或退出应用，只断开界面连接；手机 IM 仍可处理任务。关闭此选项后，下次离开会停止远端服务。', 'Switching projects or quitting disconnects this interface; IM remains available. Turn this off to stop the remote service the next time you leave.') }}</small>
      </span>
    </label>
    <p class="remote-hint">{{ label('服务器需保持开机和联网。服务器重启后，请重新连接此项目；此选项不会设置系统开机自启。', 'The server must stay powered on and online. Reconnect after a server reboot; this option does not install a boot service.') }}</p>
    <p class="remote-hint">{{ label('机器人会继续服务这个项目。切到其他项目不会自动转移机器人连接。', 'The bot keeps serving this project. Switching projects does not transfer its connection.') }}</p>
    <p v-if="message" role="status">{{ message }}</p>
    <div v-if="confirmStop" class="remote-stop-confirm" role="group" :aria-label="label('确认停止远端服务', 'Confirm stopping the remote service')">
      <p>{{ label('停止远端服务会取消该项目正在执行的任务，并断开机器人。再次使用时需要重新连接。', 'Stopping cancels running tasks in this project and disconnects its bots. Reconnect to use it again.') }}</p>
      <button type="button" :disabled="busy" @click="confirmStop = false">{{ label('保留运行', 'Keep running') }}</button>
      <button type="button" class="danger" :disabled="busy" @click="leave(true)">{{ label('确认停止远端服务', 'Stop remote service') }}</button>
    </div>
    <div v-else class="remote-actions">
      <button v-if="project.remoteKeepRunning" type="button" :disabled="busy || workspace.switching" @click="leave(false)">{{ label('仅断开界面，保留远端运行', 'Disconnect and keep running') }}</button>
      <button type="button" :disabled="busy || workspace.switching" @click="confirmStop = true">{{ label('停止远端服务…', 'Stop remote service…') }}</button>
    </div>
    </template>
    <WorkspaceFailure v-if="error || workspace.error" :message="error || workspace.error || ''" remote class="error" />
  </section>
</template>

<style scoped>
.remote-runtime-settings { margin-bottom:16px; padding:18px; border:1px solid var(--border); border-radius:12px; background:var(--surface-1); }
h3 { margin:0 0 8px; font-size:14px; }
p { font-size:13px; line-height:1.65; }
.remote-target { overflow-wrap:anywhere; color:var(--text-muted); }
.remote-keep { display:flex; align-items:flex-start; gap:9px; font-size:13px; }
.remote-keep input { flex-shrink:0; margin-top:3px; }
.remote-keep small { display:block; margin-top:5px; line-height:1.6; color:var(--text-muted); }
.remote-hint { color:var(--text-muted); font-size:12px; }
.remote-update { border:1px solid var(--border); border-radius:8px; padding:10px; margin:12px 0; }
.remote-actions, .remote-stop-confirm { display:flex; flex-wrap:wrap; gap:8px; }
.remote-stop-confirm p { width:100%; margin-bottom:0; }
button { padding:7px 11px; border:1px solid var(--border); border-radius:6px; background:var(--surface-2); color:var(--text-primary); font:inherit; font-size:12px; cursor:pointer; }
button:disabled { opacity:.55; cursor:wait; }
.error, .danger { color:var(--danger,#c85050); }
</style>
