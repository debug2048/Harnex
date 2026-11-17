// Harnex chat webview (docs/03 §3.32.3) — a pure renderer over host-fed stream envelopes.
// No framework, no backend access: state lives in the extension host (ChatController);
// this script rebuilds the transcript from replayed envelopes after every webview reload.
// Interaction style follows Codex / Claude Code panels: compact transcript, collapsible
// thinking + tool rows, inline approval/question/plan cards, composer with mode pill.

import { renderMarkdown as md } from './markdown'
import type { ChatModel } from '../chat/modelCatalog'
import type { RunToolDetail } from '../../../packages/protocol/workbench'
import { ToolRows } from './toolRows'

declare function acquireVsCodeApi(): { postMessage(msg: unknown): void }
const vscode = acquireVsCodeApi()

interface Envelope {
  type: string
  data: Record<string, unknown>
  runId?: string
  conversationId?: string
  seq?: number
  ts?: number
}

interface RunInputState { inputId: string; mode: string; text: string; status: string; terminalReason?: string }
interface UiState {
  streaming: boolean; mode: string; conversationId: string | null; runtime: string
  pendingInputs?: RunInputState[]; assetScope?: string
  models?: ChatModel[]; selectedModel?: string; modelsLoading?: boolean; modelsError?: string; modelLocked?: boolean
  runId?: string | null; navigating?: boolean; hasEarlier?: boolean; earlierLoading?: boolean
  externalBusy?: boolean
  runtimeLabel?: string
  runtimeError?: string; attached?: boolean
  clientError?: string
}

// ─── DOM scaffold ────────────────────────────────────────────────────────────

const root = document.getElementById('app')!
root.innerHTML = `
  <header class="hx-header">
    <span class="hx-title"><span class="hx-dot" id="hx-dot"></span>Harnex</span>
    <span class="hx-header-actions">
      <button id="hx-disconnect" class="hx-icon-btn" title="断开 Runtime 连接" aria-label="断开 Runtime 连接" hidden>⏻</button>
      <button id="hx-history" class="hx-icon-btn" title="历史会话" aria-label="历史会话">◷</button>
      <button id="hx-new" class="hx-icon-btn" title="新建对话" aria-label="新建对话">＋</button>
    </span>
  </header>
  <div id="hx-connection" class="hx-connection" role="status">
    <span id="hx-connection-message">选择 Runtime 连接后开始对话。</span>
    <div class="hx-connection-actions">
      <button id="hx-runtime-attach" class="hx-btn">连接已有 Runtime</button>
      <button id="hx-runtime-start" class="hx-btn">启动独立 Runtime</button>
      <button id="hx-runtime-reconnect" class="hx-btn" hidden>重试连接</button>
    </div>
  </div>
  <div id="hx-client-error" class="hx-submit-error hx-client-error" role="alert" hidden></div>
  <div id="hx-external" class="hx-external" role="status" hidden><span>此会话正在另一端使用，当前可查看历史。</span><button id="hx-refresh-conversation" class="hx-icon-btn" title="刷新会话" aria-label="刷新会话">↻</button></div>
  <button id="hx-earlier" class="hx-earlier" hidden>加载更早消息</button>
  <main class="hx-transcript" id="hx-transcript" aria-label="对话记录"></main>
  <footer class="hx-composer">
    <div id="hx-run-bar" class="hx-run-bar" hidden>
      <span>任务执行中 · 可发送补充内容</span>
      <button id="hx-stop-link" title="停止当前任务（Esc），保留草稿">停止 <kbd>Esc</kbd></button>
    </div>
    <div class="hx-run-inputs" id="hx-run-inputs"></div>
    <div id="hx-submit-error" class="hx-submit-error" role="alert" hidden></div>
    <div class="hx-composer-box">
      <div id="hx-uploads" class="hx-uploads"></div>
      <textarea id="hx-input" class="hx-input" rows="3" aria-label="消息输入框" aria-describedby="hx-input-hint"
        placeholder="向 Harnex 提问…"></textarea>
      <div class="hx-composer-toolbar">
        <button id="hx-attach" class="hx-icon-btn hx-attach" title="添加附件" aria-label="添加附件">
          <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="m8 12 6-6a3 3 0 0 1 4 4l-8 8a5 5 0 0 1-7-7l9-9M6 14l8-8"/></svg>
        </button>
        <div class="hx-composer-selectors">
          <select id="hx-model" class="hx-model" title="选择模型" aria-label="选择模型">
            <option value="">默认模型</option>
          </select>
          <select id="hx-mode" class="hx-mode" title="权限模式" aria-label="权限模式">
            <option value="ask">询问权限</option>
            <option value="accept-edits">自动编辑</option>
            <option value="yolo">完全访问</option>
          </select>
        </div>
        <div class="hx-send-actions">
          <div id="hx-more-wrap" class="hx-more-wrap" hidden>
            <button id="hx-more" class="hx-more" title="更多发送选项" aria-label="更多发送选项" aria-expanded="false">…</button>
            <div id="hx-send-options" class="hx-send-options" hidden>
              <button id="hx-follow"><span>完成后执行<small>等待当前任务正常完成后，按顺序执行</small></span><kbd id="hx-queue-shortcut"></kbd></button>
            </div>
          </div>
          <button id="hx-send" class="hx-send" title="发送" aria-label="发送">↑</button>
        </div>
      </div>
    </div>
    <div class="hx-composer-meta">
      <span id="hx-input-hint">Enter 发送 · Shift+Enter 换行</span>
      <span class="hx-usage" id="hx-usage"></span>
    </div>
    <div id="hx-model-status" class="hx-model-status" role="status" hidden>
      <span id="hx-model-message"></span><button id="hx-model-retry" class="hx-icon-btn" title="刷新模型列表" aria-label="刷新模型列表">↻</button>
    </div>
  </footer>`

const transcript = document.getElementById('hx-transcript')!
const input = document.getElementById('hx-input') as HTMLTextAreaElement
const sendBtn = document.getElementById('hx-send') as HTMLButtonElement
const followBtn = document.getElementById('hx-follow') as HTMLButtonElement
const stopLink = document.getElementById('hx-stop-link') as HTMLButtonElement
const runBar = document.getElementById('hx-run-bar')!
const moreWrap = document.getElementById('hx-more-wrap')!
const moreBtn = document.getElementById('hx-more') as HTMLButtonElement
const sendOptions = document.getElementById('hx-send-options')!
const submitError = document.getElementById('hx-submit-error')!
const runInputsEl = document.getElementById('hx-run-inputs')!
const modeSel = document.getElementById('hx-mode') as HTMLSelectElement
const modelSel = document.getElementById('hx-model') as HTMLSelectElement
const modelStatus = document.getElementById('hx-model-status')!
const modelMessage = document.getElementById('hx-model-message')!
const modelRetry = document.getElementById('hx-model-retry') as HTMLButtonElement
const usageEl = document.getElementById('hx-usage')!
const dotEl = document.getElementById('hx-dot')!
const earlierBtn = document.getElementById('hx-earlier') as HTMLButtonElement
const historyBtn = document.getElementById('hx-history') as HTMLButtonElement
const connectionPanel = document.getElementById('hx-connection')!
const connectionMessage = document.getElementById('hx-connection-message')!
const clientError = document.getElementById('hx-client-error')!
const runtimeAttach = document.getElementById('hx-runtime-attach') as HTMLButtonElement
const runtimeStart = document.getElementById('hx-runtime-start') as HTMLButtonElement
const runtimeReconnect = document.getElementById('hx-runtime-reconnect') as HTMLButtonElement
const disconnect = document.getElementById('hx-disconnect') as HTMLButtonElement
runtimeAttach.onclick = () => vscode.postMessage({ t: 'runtime', action: 'attach' })
runtimeStart.onclick = () => vscode.postMessage({ t: 'runtime', action: 'start' })
runtimeReconnect.onclick = () => vscode.postMessage({ t: 'runtime', action: 'reconnect' })
disconnect.onclick = () => vscode.postMessage({ t: 'runtime', action: 'disconnect' })

let state: UiState = { streaming: false, mode: 'ask', conversationId: null, runtime: 'stopped', pendingInputs: [] }
let pendingSubmission: { requestId: string; revision: number; attachmentIds?: string[] } | undefined
let draftRevision = 0
let submissionSequence = 0
type Upload = { id: string; file: File; assetId?: string; error?: string; busy: boolean; thumbnail?: string }
const uploads = new Map<string, Upload>()
const drafts = new Map<string, { text: string; uploads: Map<string, Upload> }>()
const uploadBar = document.getElementById('hx-uploads')!
const pick = document.createElement('input'); pick.type = 'file'; pick.multiple = true; pick.hidden = true; input.before(pick)
const pickButton = document.getElementById('hx-attach') as HTMLButtonElement
pickButton.onclick = () => pick.click()
pick.onchange = () => { addUploads(Array.from(pick.files || [])); pick.value = '' }
input.addEventListener('paste', event => {
  const files = Array.from(event.clipboardData?.items || []).filter(item => item.type.startsWith('image/')).map(item => item.getAsFile()).filter((file): file is File => !!file)
  if (files.length) { event.preventDefault(); addUploads(files) }
})
function readyAttachments() { return [...uploads.values()].flatMap(item => item.assetId ? [item.assetId] : []) }
function blockedUploads() { return [...uploads.values()].some(item => !item.assetId) }
function addUploads(files: File[]) {
  if (state.runtime !== 'running') return
  for (const file of files) {
    if (uploads.size >= 8) { submitError.textContent = '最多选择 8 个附件'; submitError.hidden = false; break }
    const item: Upload = { id: crypto.randomUUID(), file, busy: true }
    uploads.set(item.id, item); void upload(item)
  }
  renderUploads()
}
async function upload(item: Upload) {
  item.busy = true; item.error = undefined; renderUploads()
  try {
    if (item.file.size > 16 * 1024 * 1024) throw Error('文件超过 16 MiB')
    const data = await new Promise<string>((resolve, reject) => {
      const reader = new FileReader(); reader.onerror = () => reject(Error('无法读取文件'))
      reader.onload = () => resolve(String(reader.result)); reader.readAsDataURL(item.file)
    })
    if (!uploads.has(item.id)) return
    if (/^image\/(png|jpeg|webp|gif)$/.test(item.file.type)) item.thumbnail = data
    vscode.postMessage({ t: 'upload', uploadId: item.id, name: item.file.name, mime: item.file.type, data: data.split(',')[1] })
  } catch (error) { item.busy = false; item.error = String(error); renderUploads() }
}
function renderUploads() {
  uploadBar.replaceChildren()
  for (const item of uploads.values()) {
    const row = document.createElement('div'); row.className = 'hx-upload'
    if (item.thumbnail) { const image = document.createElement('img'); image.src = item.thumbnail; image.alt = item.file.name; row.append(image) }
    const label = document.createElement('button'); label.className = 'hx-btn'; label.textContent = item.file.name + (item.busy ? ' · 上传中' : item.error ? ' · 失败' : '')
    label.title = item.error || item.file.name
    label.onclick = () => { if (item.assetId) vscode.postMessage({ t: 'preview', artifactId: item.assetId }) }
    row.append(label)
    if (item.error) { const retry = document.createElement('button'); retry.textContent = '重试'; retry.onclick = () => void upload(item); row.append(retry) }
    const remove = document.createElement('button'); remove.textContent = '×'; remove.setAttribute('aria-label', '移除附件')
    remove.onclick = () => { uploads.delete(item.id); renderUploads() }; row.append(remove); uploadBar.append(row)
  }
  refreshComposer()
}
document.getElementById('hx-queue-shortcut')!.textContent = /Mac/i.test(navigator.platform) ? '⌘+Enter' : 'Ctrl+Enter'

// ─── Rendering helpers ───────────────────────────────────────────────────────

function esc(s: string): string {
  return s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;')
}

function el(html: string): HTMLElement {
  const t = document.createElement('template')
  t.innerHTML = html.trim()
  return t.content.firstElementChild as HTMLElement
}

function nearBottom(): boolean {
  return transcript.scrollHeight - transcript.scrollTop - transcript.clientHeight < 60
}
function scrollDown(force = false): void {
  if (replaying) return
  if (force || stick) transcript.scrollTop = transcript.scrollHeight
}
let stick = true
let replaying = false
transcript.addEventListener('scroll', () => { stick = nearBottom() })

// ─── Transcript state machine ────────────────────────────────────────────────

let curAssistant: HTMLElement | null = null
let curAssistantText = ''
let curThinking: HTMLElement | null = null
let curThinkingText = ''
const toolRows = new ToolRows(transcript, (runId, seq) => vscode.postMessage({ t: 'toolDetails', runId, seq }))
const cards = new Map<string, HTMLElement>()

function flushAssistant(): void {
  if (curAssistant) {
    for (const code of Array.from(curAssistant.querySelectorAll('code.language-html'))) {
      const button = document.createElement('button'); button.className = 'hx-btn'; button.textContent = '预览页面'
      button.onclick = () => vscode.postMessage({ t: 'preview', html: code.textContent || '' }); code.parentElement?.after(button)
    }
    for (const match of curAssistantText.matchAll(/\[(?:附件 |artifact:)([A-Za-z0-9_-]+)\]/g)) {
      const button = document.createElement('button'); button.className = 'hx-btn'; button.textContent = '查看附件'
      button.onclick = () => vscode.postMessage({ t: 'preview', artifactId: match[1] }); curAssistant.append(button)
    }
  }
  curAssistant = null; curAssistantText = ''
}
function flushThinking(): void { curThinking = null; curThinkingText = '' }

function assistantEl(): HTMLElement {
  if (!curAssistant) {
    curAssistant = el('<div class="hx-msg hx-assistant"></div>')
    transcript.appendChild(curAssistant)
  }
  return curAssistant
}

function sysLine(text: string, cls = ''): void {
  transcript.appendChild(el(`<div class="hx-sys ${cls}">${esc(text)}</div>`))
  scrollDown()
}

function toolLabel(name: string, argsJson: unknown): string {
  try {
    const a = JSON.parse(typeof argsJson === 'string' ? argsJson : '{}') as Record<string, unknown>
    if (typeof a.command === 'string') return a.command.slice(0, 80)
    if (typeof a.path === 'string') return String(a.path)
    if (typeof a.regex === 'string') return String(a.regex)
    if (typeof a.pattern === 'string') return String(a.pattern)
  } catch { /* not json */ }
  return ''
}

function apply(env: Envelope): void {
  const d = env.data ?? {}
  switch (env.type) {
    case 'user_message': {
      flushAssistant(); flushThinking()
      const text = String(d.text ?? ''), message = el(`<div class="hx-msg hx-user">${md(text)}</div>`)
      for (const match of text.matchAll(/\[附件 ([A-Za-z0-9_-]+)\]/g)) {
        const button = document.createElement('button'); button.className = 'hx-btn'; button.textContent = '查看附件'
        button.onclick = () => vscode.postMessage({ t: 'preview', artifactId: match[1] }); message.append(button)
      }
      transcript.appendChild(message)
      break
    }
    case 'text_delta': {
      curAssistantText += String(d.text ?? d.delta ?? '')
      assistantEl().innerHTML = md(curAssistantText)
      break
    }
    case 'thinking_start': case 'thinking_delta': {
      if (!curThinking) {
        curThinking = el(`<details class="hx-thinking"><summary>思考中…</summary><div class="hx-thinking-body"></div></details>`)
        transcript.appendChild(curThinking)
      }
      curThinkingText += String(d.text ?? d.delta ?? '')
      curThinking.querySelector('.hx-thinking-body')!.textContent = curThinkingText
      break
    }
    case 'thinking_end': {
      if (curThinking) curThinking.querySelector('summary')!.textContent = '思考过程'
      flushThinking()
      break
    }
    case 'tool_use_queued': case 'tool_use_start': case 'tool_use_end': case 'tool_use_status': case 'tool_snapshot': {
      flushAssistant(); flushThinking()
      if (env.type === 'tool_use_end') settleToolApproval(String(d.id ?? d.callId ?? ''), '')
      toolRows.apply({ ...env, data: d, runId: env.runId || state.runId || undefined })
      break
    }
    case 'subagent_event': {
      const type = String(d.childEventType || '')
      if (type === 'tool_use_end') {
        settleToolApproval(String(d.id ?? d.callId ?? ''), String(d.subagentThreadId ?? ''))
      }
      if (type.startsWith('tool_use_')) toolRows.apply({ ...env, data: d, type, runId: env.runId || state.runId || undefined })
      break
    }
    case 'message_end': flushAssistant(); break
    case 'approval_required': {
      flushAssistant()
      const callId = String(d.callId ?? '')
      const tid = env.conversationId ?? ''
      const name = String(d.toolName ?? '')
      const isFile = ['file_write', 'file_edit', 'multi_edit'].includes(name)
      const label = toolLabel(name, d.arguments)
      const card = el(`<div class="hx-card" data-call="${esc(callId)}">
        <div class="hx-card-title">审批请求 · <code>${esc(name)}</code></div>
        ${label ? `<div class="hx-card-body"><code>${esc(label)}</code></div>` : ''}
        ${isFile ? '<div class="hx-card-hint">已在编辑器打开 diff，可在此或编辑器通知中决策</div>' : ''}
        <div class="hx-card-actions">
          <button class="hx-btn hx-primary" data-act="allow">允许</button>
          <button class="hx-btn" data-act="deny">拒绝</button>
          <button class="hx-btn hx-quiet" data-act="always">本次会话总是允许</button>
        </div></div>`)
      card.querySelectorAll('button').forEach(b => b.addEventListener('click', () => {
        const act = (b as HTMLElement).dataset.act
        vscode.postMessage({ t: 'approval', conversationId: tid, callId,
          decision: act === 'deny' ? 'deny' : 'allow', whitelist: act === 'always' })
        setCardPending(callId, true)
      }))
      transcript.appendChild(card)
      card.dataset.sourceCall = String(d.sourceCallId ?? '')
      card.dataset.childThread = String(d.subagentThreadId ?? '')
      cards.set(callId, card)
      break
    }
    case 'approval_resolved': {
      settleCard(String(d.callId ?? ''), d.decision === 'deny' ? '已拒绝' : '已允许')
      break
    }
    case 'user_question': {
      flushAssistant()
      const callId = String(d.callId ?? '')
      const tid = env.conversationId ?? String(d.conversationId ?? '')
      const options = Array.isArray(d.options) ? d.options.map(String) : []
      const card = el(`<div class="hx-card" data-call="${esc(callId)}">
        <div class="hx-card-title">提问</div>
        <div class="hx-card-body">${md(String(d.question ?? ''))}</div>
        <div class="hx-card-actions hx-wrap"></div></div>`)
      const actions = card.querySelector('.hx-card-actions')!
      const answer = (text: string) => {
        vscode.postMessage({ t: 'question', conversationId: tid, callId, answer: text })
        setCardPending(callId, true)
      }
      for (const opt of options) {
        const b = el(`<button class="hx-btn">${esc(opt)}</button>`)
        b.addEventListener('click', () => answer(opt))
        actions.appendChild(b)
      }
      if (d.allowFreeText !== false) {
        const row = el(`<div class="hx-free"><input class="hx-free-input" placeholder="自由回答…"/><button class="hx-btn hx-primary">回复</button></div>`)
        const inp = row.querySelector('input')!
        row.querySelector('button')!.addEventListener('click', () => { if (inp.value.trim()) answer(inp.value.trim()) })
        inp.addEventListener('keydown', e => {
          if (e.key === 'Enter' && !e.isComposing && e.keyCode !== 229 && inp.value.trim()) answer(inp.value.trim())
        })
        card.appendChild(row)
      }
      transcript.appendChild(card)
      cards.set(callId, card)
      break
    }
    case 'question_answered': {
      settleCard(String(d.callId ?? ''), '已回答')
      break
    }
    case 'plan_review': {
      flushAssistant()
      const callId = String(d.callId ?? '')
      const tid = env.conversationId ?? String(d.conversationId ?? '')
      const card = el(`<div class="hx-card" data-call="${esc(callId)}">
        <div class="hx-card-title">计划评审</div>
        <div class="hx-card-body hx-plan">${md(String(d.plan ?? ''))}</div>
        <textarea class="hx-feedback" placeholder="反馈（可选，Reject 时随反馈退回）…" rows="2"></textarea>
        <div class="hx-card-actions">
          <button class="hx-btn hx-primary" data-act="approve">批准执行</button>
          <button class="hx-btn" data-act="reject">退回修改</button>
        </div></div>`)
      card.querySelectorAll('.hx-card-actions button').forEach(b => b.addEventListener('click', () => {
        const decision = (b as HTMLElement).dataset.act as 'approve' | 'reject'
        const feedback = (card.querySelector('.hx-feedback') as HTMLTextAreaElement).value.trim() || undefined
        vscode.postMessage({ t: 'plan', conversationId: tid, callId, decision, feedback })
        setCardPending(callId, true)
      }))
      transcript.appendChild(card)
      cards.set(callId, card)
      break
    }
    case 'plan_review_resolved': {
      settleCard(String(d.callId ?? ''), d.decision === 'reject' ? '已退回' : '已批准')
      break
    }
    case 'interaction_expired':
      settleCard(String(d.callId ?? ''), '本次请求已结束')
      break
    case 'interaction_failed':
      setCardPending(String(d.callId ?? ''), false)
      break
    case 'interactions_superseded':
      for (const id of cards.keys()) settleCard(id, '已由新的指令撤回')
      break
    case 'usage': {
      const total = d.totalTokens ?? d.total_tokens
      if (total !== undefined) usageEl.textContent = `${total} tokens`
      break
    }
    case 'error':
      flushAssistant(); flushThinking()
      sysLine(`⚠ ${String(d.message ?? d.error ?? '未知错误')}`, 'hx-error')
      break
    case 'cancelled':
      for (const id of cards.keys()) settleCard(id, '本次请求已结束')
      flushAssistant(); flushThinking()
      sysLine('已停止')
      break
    case 'goal_state_changed':
      if (['PAUSED', 'READY_FOR_REVIEW', 'BLOCKED', 'BUDGET_LIMITED', 'INTERRUPTED'].includes(String(d.status))) {
        for (const id of cards.keys()) settleCard(id, '本次请求已结束')
        flushAssistant(); flushThinking()
        sysLine(`Goal ${d.status}：${String(d.reason || '')}`, 'hx-warn')
      }
      break
    case 'done':
      for (const id of cards.keys()) settleCard(id, '本次请求已结束')
      flushAssistant(); flushThinking()
      if (d.status === 'INCOMPLETE') {
        const result = d.result as { detail?: string; reason?: string } | undefined
        sysLine(`本轮未完成：${result?.detail || result?.reason || '请补充信息或调整执行限额后继续。'}`, 'hx-warn')
      }
      break
    case 'run_input_applied':
      flushAssistant(); flushThinking()
      break
    case 'notice': case 'warning': case 'provider_switched': case 'goal_continue': {
      if (d.kind === 'projection_restored') break
      const text = String(d.message ?? d.text ?? d.kind ?? env.type)
      sysLine(text, env.type === 'warning' ? 'hx-warn' : '')
      break
    }
    // message_start/message_end/snapshot/command_result/hook_notify/run_started — no visual element.
  }
  scrollDown()
}

function settleToolApproval(sourceCallId: string, childThread: string): void {
  if (!sourceCallId) return
  for (const [id, card] of cards) {
    if (card.dataset.sourceCall === sourceCallId && (card.dataset.childThread || '') === childThread) {
      settleCard(id, '本次请求已结束')
    }
  }
}

function setCardPending(callId: string, pending: boolean): void {
  const card = cards.get(callId)
  if (!card) return
  card.querySelectorAll('button,input,textarea').forEach(x => ((x as HTMLButtonElement).disabled = pending))
  card.setAttribute('aria-busy', String(pending))
}

function settleCard(callId: string, note: string): void {
  const card = cards.get(callId)
  if (!card) return
  card.querySelectorAll('button,input,textarea').forEach(x => ((x as HTMLButtonElement).disabled = true))
  card.classList.add('hx-settled')
  const actions = card.querySelector('.hx-card-actions')
  if (actions) actions.innerHTML = `<span class="hx-settled-note">${esc(note)}</span>`
  cards.delete(callId)
}

// ─── State / replay ──────────────────────────────────────────────────────────

function applyState(s: UiState): void {
  const connected = s.runtime === 'running'
  const errorMessage = s.clientError || ''
  if (clientError.textContent !== errorMessage) clientError.textContent = errorMessage
  clientError.hidden = !connected || !s.clientError
  connectionPanel.hidden = connected
  const runtimeMessage = s.runtime === 'starting' ? '正在连接当前项目的 Runtime…'
    : s.runtimeError || '选择 Runtime 连接后开始对话。切换项目时，会先确认是否切换已连接的 Runtime。'
  if (connectionMessage.textContent !== runtimeMessage) connectionMessage.textContent = runtimeMessage
  runtimeAttach.disabled = runtimeStart.disabled = runtimeReconnect.disabled = s.runtime === 'starting'
  runtimeReconnect.hidden = s.runtime !== 'error'
  disconnect.hidden = s.runtime === 'stopped'
  disconnect.title = s.attached ? '断开连接（Runtime 继续由宿主管理）' : '停止独立 Runtime'
  if (state.conversationId !== s.conversationId) {
    drafts.set(state.conversationId || '', { text: input.value, uploads: new Map(uploads) })
    const draft = drafts.get(s.conversationId || '')
    input.value = draft?.text || ''; uploads.clear()
    draft?.uploads.forEach((item, key) => uploads.set(key, item))
    pendingSubmission = undefined; draftRevision++; submitError.hidden = true; autosize(); renderUploads()
  }
  if (state.assetScope && s.assetScope && state.assetScope !== s.assetScope) { uploads.clear(); renderUploads() }
  if (state.streaming !== s.streaming) closeSendOptions()
  state = s
  modeSel.value = s.mode
  earlierBtn.hidden = !s.hasEarlier
  earlierBtn.disabled = Boolean(s.earlierLoading || s.navigating)
  earlierBtn.textContent = s.earlierLoading ? '正在加载…' : '加载更早消息'
  historyBtn.disabled = !connected || Boolean(s.navigating)
  document.getElementById('hx-external')!.hidden = !s.externalBusy
  modeSel.disabled = Boolean(s.navigating)
  renderModels()
  refreshComposer()
  renderRunInputs(s.pendingInputs || [])
  dotEl.className = `hx-dot hx-dot-${s.runtime}`
  dotEl.title = `${s.runtimeLabel || 'Runtime'} · ${s.runtime}`
  document.body.classList.toggle('hx-yolo', s.mode === 'yolo')
}

let modelOptionsKey = ''
function renderModels(): void {
  const models = state.models || []
  const selected = state.selectedModel || ''
  const missing = Boolean(selected && !models.some(model => model.ref === selected))
  const defaultLabel = state.modelsLoading && !models.length ? '正在加载模型…' : '默认模型'
  const optionsKey = JSON.stringify([models, missing ? selected : '', defaultLabel])
  if (modelOptionsKey !== optionsKey) {
    modelOptionsKey = optionsKey
    modelSel.replaceChildren(new Option(defaultLabel, ''))
    const groups = new Map<string, HTMLOptGroupElement>()
    for (const model of models) {
      let group = groups.get(model.provider)
      if (!group) {
        group = document.createElement('optgroup'); group.label = model.providerLabel
        groups.set(model.provider, group); modelSel.append(group)
      }
      const option = new Option(`${model.label} · ${model.providerLabel}`, model.ref)
      option.title = `${model.provider} / ${model.model}`
      group.append(option)
    }
    if (missing) {
      const option = new Option(`${selected}（未在列表中）`, selected)
      option.disabled = true; modelSel.append(option)
    }
  }
  modelSel.value = selected
  modelSel.title = state.modelLocked ? '当前任务结束后可切换模型' : selected || '使用配置中的默认模型'
  modelRetry.disabled = state.runtime !== 'running' || Boolean(state.modelsLoading)
  modelStatus.hidden = state.runtime !== 'running' || Boolean(state.modelsLoading) || !state.modelsError && models.length > 0 && !missing
  modelMessage.textContent = state.modelsError ? `模型列表加载失败：${state.modelsError}`
    : missing ? '已选模型不在当前配置中，请重新选择。' : '尚未配置模型，请在 Harnex 配置中添加 Provider 后刷新。'
}

function renderRunInputs(items: RunInputState[]): void {
  runInputsEl.innerHTML = ''
  for (const item of items.filter(item => ['QUEUED', 'APPLYING', 'HELD', 'REJECTED'].includes(item.status)
    || (item.status === 'CANCELLED' && item.terminalReason === 'source_run_terminal'))) {
    const status = item.status === 'HELD' ? '已暂存，不会自动执行'
      : item.status === 'REJECTED' ? '未能处理这条消息'
        : item.status === 'CANCELLED' ? '任务已结束，这条补充未生效'
          : item.status === 'APPLYING' ? '正在处理'
            : item.mode === 'QUEUE' ? '等待当前任务完成' : '已收到，等待加入当前任务'
    const row = el(`<div class="hx-run-input"><strong>${item.mode === 'QUEUE' ? '完成后执行' : '补充'}</strong><span>${esc(item.text)}</span>
      <em>${status}</em></div>`)
    row.title = item.text
    if (item.status === 'QUEUED' || item.status === 'HELD') {
      const close = el('<button title="撤回这条待处理消息" aria-label="撤回这条待处理消息">×</button>')
      close.addEventListener('click', () => vscode.postMessage({ t: 'withdrawInput', inputId: item.inputId }))
      row.appendChild(close)
    }
    runInputsEl.appendChild(row)
  }
}

function resetTranscript(retainDetails = false): void {
  transcript.innerHTML = ''
  toolRows.reset(retainDetails); cards.clear()
  flushAssistant(); flushThinking()
  usageEl.textContent = ''
}

window.addEventListener('message', (e: MessageEvent) => {
  const m = e.data as { t: string; env?: Envelope; envs?: Envelope[]; state?: UiState; requestId?: string; accepted?: boolean; uploadId?: string; asset?: { artifactId: string }; error?: string; runId?: string; seq?: number; detail?: RunToolDetail }
  switch (m.t) {
    case 'uploadResult': {
      const item = uploads.get(m.uploadId || '')
      if (item) { item.busy = false; item.error = m.error; item.assetId = m.asset?.artifactId; renderUploads() }
      break
    }
    case 'env': if (m.env) apply(m.env); break
    case 'toolDetailsResult': if (m.runId && m.seq) toolRows.receive(m.runId, m.seq, m.detail, m.error); break
    case 'state': if (m.state) applyState(m.state); break
    case 'submission':
      if (!pendingSubmission || pendingSubmission.requestId !== m.requestId) break
      if (m.accepted && pendingSubmission.revision === draftRevision) input.value = ''
      if (m.accepted) for (const [id, item] of uploads) if (item.assetId && pendingSubmission.attachmentIds?.includes(item.assetId)) uploads.delete(id)
      if (!m.accepted) {
        submitError.textContent = '消息未发送，草稿已保留。请稍后重试。'
        submitError.hidden = false
      }
      pendingSubmission = undefined
      renderUploads()
      autosize()
      refreshComposer()
      break
    case 'replay': {
      const same = state.conversationId === m.state?.conversationId
      const expanded = same ? toolRows.expanded() : new Set<string>()
      const top = transcript.scrollTop, height = transcript.scrollHeight
      const loadingEarlier = Boolean(state.earlierLoading)
      const wasSticky = stick
      replaying = true
      resetTranscript(same)
      for (const env of m.envs ?? []) apply(env)
      if (m.state) applyState(m.state)
      toolRows.restoreExpanded(expanded)
      replaying = false; stick = same ? wasSticky : true
      if (same && (loadingEarlier || !wasSticky)) transcript.scrollTop = top + (loadingEarlier ? transcript.scrollHeight - height : 0)
      else scrollDown(true)
      break
    }
  }
})

// ─── Composer ────────────────────────────────────────────────────────────────

function submit(followUp = false): void {
  if (state.runtime !== 'running') return
  const text = input.value.trim()
  if (state.navigating || (!text && !readyAttachments().length) || blockedUploads() || pendingSubmission || (followUp && !state.streaming)) return
  const requestId = `composer-${Date.now()}-${++submissionSequence}`
  const attachmentIds = readyAttachments()
  pendingSubmission = { requestId, revision: draftRevision, attachmentIds }
  submitError.hidden = true
  closeSendOptions()
  refreshComposer()
  vscode.postMessage({ t: followUp ? 'followUp' : state.streaming ? 'steer' : 'send', text, attachmentIds, requestId })
}

function showingStop(): boolean { return state.streaming && !input.value.trim() && !uploads.size && !pendingSubmission }
function refreshComposer(): void {
  const stop = showingStop()
  sendBtn.textContent = pendingSubmission ? '…' : stop ? '■' : '↑'
  sendBtn.title = pendingSubmission ? '正在发送…' : stop ? '停止当前任务（Esc）' : state.streaming ? '补充到当前任务（Enter）' : '发送'
  sendBtn.setAttribute('aria-label', sendBtn.title)
  sendBtn.setAttribute('aria-busy', String(Boolean(pendingSubmission)))
  sendBtn.classList.toggle('hx-stop', stop)
  sendBtn.disabled = state.runtime !== 'running' || Boolean(state.navigating) || !stop && (Boolean(pendingSubmission) || blockedUploads() || !input.value.trim() && !readyAttachments().length)
  pickButton.disabled = state.runtime !== 'running'
  modelSel.disabled = state.runtime !== 'running' || Boolean(state.navigating || state.modelLocked || pendingSubmission || state.modelsLoading && !state.models?.length)
  followBtn.disabled = Boolean(pendingSubmission) || blockedUploads() || !input.value.trim() && !readyAttachments().length
  moreWrap.hidden = !state.streaming
  runBar.hidden = !state.streaming
  stopLink.hidden = stop
  input.placeholder = state.streaming ? '继续输入以补充或调整当前任务…' : '向 Harnex 提问…'
}
function closeSendOptions(restoreFocus = false): void {
  sendOptions.hidden = true
  moreBtn.setAttribute('aria-expanded', 'false')
  if (restoreFocus) moreBtn.focus()
}
moreBtn.addEventListener('click', () => {
  sendOptions.hidden = !sendOptions.hidden
  moreBtn.setAttribute('aria-expanded', String(!sendOptions.hidden))
  if (!sendOptions.hidden && !followBtn.disabled) followBtn.focus()
})
moreWrap.addEventListener('keydown', e => {
  if (e.key === 'Escape') { e.preventDefault(); e.stopPropagation(); closeSendOptions(true) }
})
moreWrap.addEventListener('focusout', e => { if (!moreWrap.contains(e.relatedTarget as Node)) closeSendOptions() })
document.addEventListener('pointerdown', e => { if (!moreWrap.contains(e.target as Node)) closeSendOptions() })
sendBtn.addEventListener('click', () => showingStop() ? vscode.postMessage({ t: 'cancel' }) : submit())
followBtn.addEventListener('click', () => { submit(true); input.focus() })
stopLink.addEventListener('click', () => vscode.postMessage({ t: 'cancel' }))
input.addEventListener('keydown', e => {
  if (e.isComposing || e.keyCode === 229) return
  if (e.key === 'Escape') {
    e.preventDefault(); e.stopPropagation()
    if (!sendOptions.hidden) closeSendOptions()
    else if (state.streaming) vscode.postMessage({ t: 'cancel' })
  } else if (e.key === 'Enter' && !e.shiftKey && !e.altKey) {
    e.preventDefault()
    if (!e.repeat) submit(state.streaming && (e.ctrlKey || e.metaKey))
  }
})
function autosize(): void {
  input.style.height = 'auto'
  input.style.height = `${Math.min(input.scrollHeight, 200)}px`
}
input.addEventListener('input', () => { draftRevision++; autosize(); refreshComposer() })

modeSel.addEventListener('change', () => vscode.postMessage({ t: 'mode', mode: modeSel.value }))
historyBtn.addEventListener('click', () => vscode.postMessage({ t: 'history' }))
document.getElementById('hx-refresh-conversation')!.addEventListener('click', () => vscode.postMessage({ t: 'refreshConversation' }))
earlierBtn.addEventListener('click', () => vscode.postMessage({ t: 'earlier' }))
modelSel.addEventListener('change', () => vscode.postMessage({ t: 'model', ref: modelSel.value }))
modelRetry.addEventListener('click', () => vscode.postMessage({ t: 'refreshModels' }))
modelSel.addEventListener('focus', () => vscode.postMessage({ t: 'refreshModels' }))
document.getElementById('hx-new')!.addEventListener('click', () => {
  uploads.clear(); renderUploads()
  draftRevision++; pendingSubmission = undefined; input.value = ''; submitError.hidden = true
  closeSendOptions(); autosize(); refreshComposer()
  vscode.postMessage({ t: 'new' })
})

refreshComposer()
vscode.postMessage({ t: 'ready' })
