import { randomUUID } from 'node:crypto'
import { spawn } from 'node:child_process'

const WAITING = new Set(['PAUSED', 'BLOCKED', 'BUDGET_LIMITED', 'READY_FOR_REVIEW', 'INTERRUPTED'])
const TERMINAL = new Set(['COMPLETED', 'INCOMPLETE', 'FAILED', 'CANCELLED', 'EXPIRED'])

/** Thin Local API client used only after runtime-domain.mjs has completed exact attach validation. */
export async function runAttachedOneShot(session, args) {
  const activeRun = takeOption(args, '--run')
  const steer = takeOption(args, '--steer')
  const followUp = takeOption(args, '--follow-up')
  if (activeRun || steer || followUp) {
    if (!activeRun || (!steer && !followUp) || (steer && followUp)) {
      throw new Error('active Run input requires --run ID and exactly one of --steer/--follow-up')
    }
    if (!/^[A-Za-z0-9._:-]{1,160}$/.test(activeRun)) throw new Error('--run requires a valid Run id')
    const run = followUp ? await json(session, `/api/v1/runs/${encodeURIComponent(activeRun)}`) : undefined
    const result = await json(session, followUp
      ? `/api/v1/conversations/${encodeURIComponent(run.conversationId)}/submissions`
      : `/api/v1/runs/${encodeURIComponent(activeRun)}/inputs`, {
        method: 'POST', body: followUp
          ? { input: followUp, predecessorTurnId: run.turnId, execution: run.executionSpec, channel: 'cli', clientRequestId: `cli-queue-${randomUUID()}` }
          : { mode: 'STEER', text: steer, channel: 'cli', clientRequestId: `cli-input-${randomUUID()}` },
      })
    process.stdout.write(`${result.submissionId || result.input?.inputId || 'input'}: ${result.state || result.input?.status || 'accepted'}\n`)
    return 0
  }
  const options = parseOneShot(args)
  if (args.includes('--conversation') && session.manifest.runtimeProfile !== 'PERSONAL') {
    throw new Error('--conversation requires a PERSONAL Runtime; this terminal is attached to its project Runtime')
  }
  const created = await json(session, '/api/v1/runs', {
    method: 'POST',
    body: {
      input: options.prompt,
      execution: session.manifest.runtimeProfile === 'PERSONAL'
        ? { kind: 'CONVERSATION', action: 'AUTO', text: { model: options.model, provider: options.provider } }
        : {
        kind: 'AGENT', text: { model: options.model, provider: options.provider },
        limits: { maxWorkIterations: options.maxWorkIterations }, toolsets: options.toolsets,
        permissionMode: options.permissionMode, interactionTarget: null, planReview: 'AUTOMATIC',
      },
      channel: 'cli',
      stream: false,
      clientRequestId: `cli-${randomUUID()}`,
    },
  })
  if (!created.runId || !created.conversationId) throw new Error('Runtime did not return runId/conversationId')

  let interrupted = false
  const onSignal = () => {
    if (interrupted) return
    interrupted = true
    process.stderr.write('\nCancelling current Run…\n')
    void cancelAttachedRun(session, created.runId).catch(() => {})
  }
  process.once('SIGINT', onSignal)
  process.once('SIGTERM', onSignal)
  try {
    const status = await followRun(session, created.runId, options.verbose)
    return status === 'COMPLETED' ? 0 : status === 'CANCELLED' ? 130 : 1
  } finally {
    process.off('SIGINT', onSignal)
    process.off('SIGTERM', onSignal)
  }
}

export async function cancelAttachedRun(session, runId) {
  if (!runId || !/^[A-Za-z0-9._:-]{1,160}$/.test(runId)) throw new Error('--run requires a valid Run id')
  const run = await json(session, `/api/v1/runs/${encodeURIComponent(runId)}`)
  return json(session, `/api/v1/runs/${encodeURIComponent(runId)}/cancel`, { method: 'POST',
    body: run.goalId ? { revision: run.revision, clientRequestId: `cli-cancel-${randomUUID()}` } : {} })
}

export async function attachedStatus(session) {
  const [workspace, protocol] = await Promise.all([
    json(session, '/api/v1/scope'),
    json(session, '/api/v1/protocol'),
  ])
  return {
    domain: session.manifest.runtimeDomainId,
    ownerKind: session.manifest.ownerKind,
    runtimePid: session.manifest.runtimePid,
    endpoint: session.endpoint,
    scopeId: workspace.scopeId,
    runtimeProfile: workspace.runtimeProfile,
    root: workspace.projectRoot || null,
    instanceId: workspace.instanceId,
    protocol: protocol.version,
  }
}

export function openAttachedApp(session) {
  const url = session.endpoint
  const command = process.platform === 'win32' ? 'cmd.exe' : process.platform === 'darwin' ? 'open' : 'xdg-open'
  const args = process.platform === 'win32' ? ['/d', '/s', '/c', 'start', '', url] : [url]
  const child = spawn(command, args, { detached: true, stdio: 'ignore', windowsHide: true })
  child.unref()
}

async function followRun(session, runId, verbose) {
  let afterSeq = 0
  let streamEpoch
  let attempts = 0
  let inText = false
  while (attempts++ < 20) {
    const query = new URLSearchParams({ afterSeq: String(afterSeq) })
    if (streamEpoch) query.set('streamEpoch', String(streamEpoch))
    const response = await request(session,
      `/api/v1/runs/${encodeURIComponent(runId)}/events?${query}`, { timeoutMs: 35 * 60_000 })
    if (response.status === 409) {
      const snapshot = await json(session, `/api/v1/runs/${encodeURIComponent(runId)}/presentation`)
      streamEpoch = snapshot.cursor.streamEpoch
      afterSeq = snapshot.cursor.projectionSeq
      process.stderr.write('[harnex] Restored Run snapshot after interrupted replay.\n')
      const recoveredText = snapshot.live.text || snapshot.history.answer?.contentParts?.filter(part => part.type === 'TEXT').map(part => part.text || '').join('')
      if (recoveredText) process.stdout.write(`\n[Recovered response]\n${recoveredText}\n`)
      renderArtifacts(snapshot)
      if (TERMINAL.has(snapshot.run.status) || WAITING.has(snapshot.run.status) || !snapshot.cursor.replayAvailable) return snapshot.run.status
      continue
    }
    if (response.status === 410 || response.status === 404) {
      const projection = await json(session, `/api/v1/runs/${encodeURIComponent(runId)}`)
      return projection.status || 'FAILED'
    }
    await assertOk(response)
    let resetRequired = false
    for await (const event of sse(response)) {
      if (event.type === 'notice' && event.data?.kind === 'buffer_miss') { resetRequired = true; break }
      if (Number.isFinite(event.streamEpoch) && streamEpoch != null && Number(event.streamEpoch) !== streamEpoch) { resetRequired = true; break }
      if (Number.isFinite(event.seq) && Number(event.seq) <= afterSeq) continue
      if (Number.isFinite(event.seq)) afterSeq = Math.max(afterSeq, Number(event.seq))
      if (Number.isFinite(event.streamEpoch)) streamEpoch = Number(event.streamEpoch)
      inText = render(event, { verbose, inText })
      if (event.type === 'done' || event.type === 'goal_state_changed' && WAITING.has(event.data?.status)) break
    }
    if (resetRequired) {
      const snapshot = await json(session, `/api/v1/runs/${encodeURIComponent(runId)}/presentation`)
      afterSeq = snapshot.cursor.projectionSeq
      streamEpoch = snapshot.cursor.streamEpoch
      const text = snapshot.live.text || snapshot.history.answer?.contentParts?.filter(part => part.type === 'TEXT').map(part => part.text || '').join('')
      if (text) process.stdout.write(`\n[Recovered response]\n${text}\n`)
      renderArtifacts(snapshot)
      if (!snapshot.cursor.replayAvailable || TERMINAL.has(snapshot.run.status) || WAITING.has(snapshot.run.status)) return snapshot.run.status
      continue
    }
    const projection = await json(session, `/api/v1/runs/${encodeURIComponent(runId)}`)
    if (TERMINAL.has(projection.status) || WAITING.has(projection.status)) {
      if (projection.status === 'INCOMPLETE' || WAITING.has(projection.status)) {
        process.stderr.write(`[harnex] ${projection.status}: ${projection.result?.detail || projection.goalExecution?.stopReason || 'execution needs attention'}\n`)
      }
      if (inText) process.stdout.write('\n')
      if (TERMINAL.has(projection.status)) {
        try { renderArtifacts(await json(session, `/api/v1/runs/${encodeURIComponent(runId)}/presentation`)) } catch { /* Completed text remains usable if optional artifact lookup is unavailable. */ }
      }
      return projection.status
    }
    await new Promise(resolve => setTimeout(resolve, Math.min(250 * attempts, 2000)))
  }
  throw new Error('Run subscription could not be resumed after repeated disconnects')
}

function renderArtifacts(snapshot) {
  const ids = new Set((snapshot.history?.answer?.contentParts || []).filter(part => part.type === 'ARTIFACT_REF' && part.artifactId).map(part => part.artifactId))
  for (const id of ids) process.stdout.write(`[Artifact: ${id}]\n`)
}
function render(event, state) {
  const data = event.data || {}
  switch (event.type) {
    case 'thinking_delta':
      if (state.verbose && data.text) process.stderr.write(String(data.text))
      return state.inText
    case 'text_delta':
      if (data.text) process.stdout.write(String(data.text))
      return true
    case 'message_end':
      if (state.inText) process.stdout.write('\n')
      return false
    case 'tool_use_start':
      if (state.inText) process.stdout.write('\n')
      process.stderr.write(`  ⏵ ${data.name || 'tool'}${summarize(data.arguments)}\n`)
      return false
    case 'tool_use_end':
      process.stderr.write(`  ${data.error ? '✗' : '✓'} ${data.name || 'tool'}\n`)
      return state.inText
    case 'command_result':
      if (data.message) process.stdout.write(`${data.message}\n`)
      return state.inText
    case 'notice':
      if (data.kind === 'buffer_miss') process.stderr.write('[harnex] Event buffer trimmed; durable state retained.\n')
      return state.inText
    case 'error':
      process.stderr.write(`harnex: ${data.message || data.reason || 'Run failed'}\n`)
      return state.inText
    case 'approval_required':
    case 'user_question':
    case 'plan_review':
      // One-shot requests do not bind a reply handler and must not suspend. Seeing this is a server contract violation.
      throw new Error(`attached one-shot unexpectedly requested interaction: ${event.type}`)
    default:
      if (state.verbose && !['message_start', 'run_started', 'done'].includes(event.type)) {
        process.stderr.write(`[${event.type}] ${JSON.stringify(data)}\n`)
      }
      return state.inText
  }
}

async function* sse(response) {
  if (!response.body) return
  const decoder = new TextDecoder()
  let buffer = ''
  for await (const chunk of response.body) {
    buffer += decoder.decode(chunk, { stream: true })
    let match
    while ((match = /\r?\n\r?\n/.exec(buffer))) {
      const frame = buffer.slice(0, match.index)
      buffer = buffer.slice(match.index + match[0].length)
      const data = frame.split(/\r?\n/).filter(line => line.startsWith('data:'))
        .map(line => line.slice(5).replace(/^ /, '')).join('\n')
      if (!data) continue
      try {
        const parsed = JSON.parse(data)
        if (parsed && typeof parsed.type === 'string') yield parsed
      } catch { /* SSE comment/keepalive */ }
    }
  }
}

async function json(session, pathname, options = {}) {
  const response = await request(session, pathname, options)
  await assertOk(response)
  return response.json()
}

async function request(session, pathname, { method = 'GET', body, timeoutMs = 15_000 } = {}) {
  const headers = {
    ...session.identityHeaders,
    Authorization: `Bearer ${session.token}`,
    Accept: 'application/json, text/event-stream',
  }
  if (body !== undefined) headers['content-type'] = 'application/json'
  const response = await fetch(`${session.endpoint}${pathname}`, {
    method, headers, body: body === undefined ? undefined : JSON.stringify(body),
    signal: AbortSignal.timeout(timeoutMs),
  })
  const workspace = response.headers.get('x-harnex-scope-id')
  const instance = response.headers.get('x-harnex-instance-id')
  if (workspace !== session.manifest.scopeId || instance !== session.manifest.instanceId) {
    throw new Error('Runtime identity changed while attached; refusing the response')
  }
  return response
}

async function assertOk(response) {
  if (response.ok) return
  const text = await response.text().catch(() => '')
  let message = text
  try {
    const parsed = JSON.parse(text)
    message = parsed.error?.message || parsed.message || text
  } catch {}
  throw new Error(`Runtime HTTP ${response.status}${message ? `: ${message}` : ''}`)
}

function parseOneShot(args) {
  const take = (...names) => names.map(name => takeOption(args, name)).find(value => value !== undefined)
  const prompt = take('-p', '--prompt')
  if (!prompt) throw new Error('attached CLI currently requires -p/--prompt (interactive TUI is a later command)')
  if (take('--mode') !== undefined) throw new Error('--mode has been removed; use --max-work-iterations or /goal')
  const rawLimit = take('--max-work-iterations')
  const maxWorkIterations = rawLimit === undefined ? undefined : Number(rawLimit)
  if (maxWorkIterations !== undefined && (!Number.isSafeInteger(maxWorkIterations) || maxWorkIterations < 1)) {
    throw new Error('--max-work-iterations must be a positive integer')
  }
  const permissionMode = take('--permission-mode') || 'ask'
  if (!['ask', 'accept-edits', 'yolo'].includes(permissionMode)) {
    throw new Error('--permission-mode must be ask, accept-edits or yolo')
  }
  return {
    prompt,
    maxWorkIterations,
    permissionMode,
    model: take('--model'),
    provider: take('--provider'),
    toolsets: take('--toolsets')?.split(',').map(item => item.trim()).filter(Boolean),
    verbose: args.includes('--verbose'),
  }
}

function takeOption(args, name) {
  for (let index = 0; index < args.length; index++) {
    if (args[index] === name) return args[index + 1]
    if (args[index].startsWith(`${name}=`)) return args[index].slice(name.length + 1)
  }
  return undefined
}

function summarize(value) {
  if (value == null) return ''
  const raw = typeof value === 'string' ? value : JSON.stringify(value)
  const compact = raw.replace(/\s+/g, ' ').slice(0, 120)
  return compact ? `  ${compact}${raw.length > 120 ? '…' : ''}` : ''
}
