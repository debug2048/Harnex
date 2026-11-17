import type { RunToolDetail } from '../../../packages/protocol/workbench'

interface ToolEnvelope { type: string; runId?: string; seq?: number; data: Record<string, unknown> }
interface ToolRow {
  key: string; runId: string; seq: number; id: string; child: string; name: string; state: string
  arguments?: string; content?: string; summary?: string; purpose?: string; durationMs?: number; resultPending: boolean
  row: HTMLDetailsElement; detail?: RunToolDetail; error?: string; refreshedAfterCompletion?: boolean
}
const text = (value: unknown): string | undefined => value == null ? undefined
  : typeof value === 'string' ? value : JSON.stringify(value, null, 2)
function formatted(value: string | undefined): string {
  if (!value) return ''
  try { return JSON.stringify(JSON.parse(value), null, 2) } catch { return value }
}

/** Tool bodies load independently of the assistant answer, with stable occurrence identity across replay. */
export class ToolRows {
  private rows = new Map<string, ToolRow>()
  private pending = new Map<string, string>()
  private loading = new Set<string>()
  private details = new Map<string, RunToolDetail>()
  constructor(private readonly transcript: HTMLElement, private readonly request: (runId: string, seq: number) => void) {}

  expanded(): Set<string> { return new Set([...this.rows.values()].filter(item => item.row.open).map(item => item.key)) }
  restoreExpanded(keys: Set<string>) { for (const key of keys) { const item = this.rows.get(key); if (item) item.row.open = true } }
  reset(retainDetails: boolean) {
    this.rows.clear(); this.pending.clear()
    if (!retainDetails) { this.details.clear(); this.loading.clear() }
  }

  apply(env: ToolEnvelope): void {
    const d = env.data, id = String(d.id ?? d.callId ?? ''), runId = env.runId || ''
    if (!id) return
    const child = String(d.subagentConversationId ?? d.subagentThreadId ?? '')
    const pendingKey = JSON.stringify([runId, child, id])
    const snapshot = env.type === 'tool_snapshot'
    let key = `${runId}:${env.seq || 0}`
    if (!snapshot && env.type !== 'tool_use_queued') {
      const existing = this.pending.get(pendingKey)
      if (existing) key = existing
    }
    let item = this.rows.get(key)
    if (!item) {
      const row = document.createElement('details'); row.className = 'hx-tool'; row.dataset.toolKey = key
      row.innerHTML = `<summary><span class="hx-tool-chevron">›</span><span class="hx-tool-status"></span><span class="hx-tool-name"></span><span class="hx-tool-label"></span></summary>
        <div class="hx-tool-body"><div class="hx-tool-purpose-section" hidden><div class="hx-tool-section">操作目的</div><p class="hx-tool-purpose"></p></div>
        <div class="hx-tool-section">调用参数</div><pre class="hx-tool-args"></pre>
        <div class="hx-tool-section">执行结果</div><pre class="hx-tool-out"></pre>
        <div class="hx-tool-detail-status"></div><button class="hx-btn hx-quiet hx-tool-retry" hidden>重新加载详情</button></div>`
      item = { key, id, runId, seq: env.seq || 0, child, name: 'tool', state: 'RUNNING', resultPending: true, row }
      this.rows.set(key, item); this.transcript.append(row)
      const current = item
      const label = row.querySelector<HTMLElement>('.hx-tool-label')!
      label.addEventListener('mouseenter', () => {
        const purpose = current.detail?.purpose || current.purpose || ''
        label.title = purpose && label.scrollWidth > label.clientWidth ? purpose : ''
      })
      label.addEventListener('mouseleave', () => { label.title = '' })
      row.addEventListener('toggle', () => { if (row.open) this.load(current) })
      row.querySelector('button')!.addEventListener('click', () => this.load(current, true))
    }
    const wasPending = item.resultPending
    item.name = String(d.name ?? d.toolName ?? item.name)
    if (d.arguments !== undefined) item.arguments = text(d.arguments)
    if (d.content !== undefined) item.content = text(d.content)
    if (d.summary || d.activitySummary) item.summary = String(d.summary || d.activitySummary)
    if (typeof d.purpose === 'string' && d.purpose) item.purpose = d.purpose
    if (typeof d.durationMs === 'number') item.durationMs = d.durationMs
    item.state = snapshot ? String(d.state || 'RUNNING') : env.type === 'tool_use_queued' ? 'QUEUED'
      : env.type === 'tool_use_start' ? 'RUNNING' : env.type === 'tool_use_end' ? d.error ? 'FAILED' : 'COMPLETED' : item.state
    item.resultPending = snapshot ? typeof d.resultPending === 'boolean' ? d.resultPending : ['QUEUED', 'RUNNING'].includes(item.state)
      : env.type !== 'tool_use_end'
    if (item.resultPending) this.pending.set(pendingKey, key)
    else if (this.pending.get(pendingKey) === key) this.pending.delete(pendingKey)
    const cached = this.details.get(key)
    item.detail = cached && (!cached.resultPending || item.resultPending) ? cached : undefined
    this.render(item)
    if (item.row.open && (wasPending !== item.resultPending || !item.detail)) this.load(item)
  }

  receive(runId: string, seq: number, detail?: RunToolDetail, error?: string): void {
    const key = `${runId}:${seq}`, item = this.rows.get(key)
    this.loading.delete(key)
    if (!item) return
    if (detail && (detail.runId !== runId || detail.seq !== seq || detail.id !== item.id
      || (detail.subagentConversationId || '') !== item.child)) error = '工具详情与当前调用不匹配'
    if (!error && detail) {
      // An in-flight read must not roll a just-completed invocation back to "waiting".
      if (!detail.resultPending || item.resultPending) {
        item.detail = detail; this.details.set(key, detail)
        if (this.details.size > 200) this.details.delete(this.details.keys().next().value!)
      }
    }
    const staleResult = !error && detail?.resultPending && !item.resultPending
    item.error = staleResult && item.refreshedAfterCompletion ? '执行结果仍未保存，请稍后重新加载。' : error
    this.render(item)
    if (staleResult && !item.refreshedAfterCompletion && item.row.open) {
      item.refreshedAfterCompletion = true
      this.load(item, true)
    }
  }

  private load(item: ToolRow, force = false) {
    if (!item.runId || item.seq < 1 || this.loading.has(item.key) || !force && item.detail) return
    this.loading.add(item.key); item.error = undefined; this.render(item)
    this.request(item.runId, item.seq)
  }
  private render(item: ToolRow) {
    const running = ['RUNNING', 'QUEUED'].includes(item.state), failed = ['FAILED', 'INTERRUPTED'].includes(item.state)
    const status = item.row.querySelector('.hx-tool-status')!
    status.textContent = running ? '◐' : failed ? '✕' : '✓'
    status.className = `hx-tool-status ${running ? 'hx-spin' : failed ? 'hx-err' : 'hx-ok'}`
    item.row.querySelector('.hx-tool-name')!.textContent = item.name
    const args = item.detail?.arguments ?? item.arguments, output = item.detail?.content ?? item.content
    let label = item.summary || ''
    try {
      const fields = JSON.parse(args || '{}')
      label = fields.command || fields.path || fields.pattern || fields.regex || label
    } catch { /* arguments can be a partial display snapshot */ }
    const purpose = item.detail?.purpose || item.purpose || ''
    label = purpose || label
    const duration = item.detail?.durationMs ?? item.durationMs
    const labelElement = item.row.querySelector<HTMLElement>('.hx-tool-label')!
    labelElement.textContent = `${item.child ? '子任务 · ' : ''}${label}${duration == null ? '' : ` · ${(duration / 1000).toFixed(1)}s`}`
    labelElement.title = ''
    item.row.querySelector<HTMLElement>('.hx-tool-purpose-section')!.hidden = !purpose
    item.row.querySelector('.hx-tool-purpose')!.textContent = purpose
    item.row.querySelector('.hx-tool-args')!.textContent = formatted(args) || '暂无调用参数'
    item.row.querySelector('.hx-tool-out')!.textContent = output !== undefined ? output
      : item.state === 'QUEUED' ? '正在排队，尚未产生输出。'
        : running ? '工具正在执行，结果返回后会自动显示。'
          : item.state === 'INTERRUPTED' ? '执行已中断。' : '暂无返回内容。'
    const loading = this.loading.has(item.key)
    item.row.querySelector('.hx-tool-detail-status')!.textContent = item.error ? `完整详情暂不可用：${item.error}` : loading ? '正在读取完整详情…' : ''
    const retry = item.row.querySelector('button') as HTMLButtonElement
    retry.hidden = !item.error; retry.disabled = loading
  }
}
