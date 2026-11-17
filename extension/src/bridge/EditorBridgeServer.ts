import * as vscode from 'vscode'
import { randomBytes, randomUUID } from 'node:crypto'
import * as fs from 'node:fs'
import * as http from 'node:http'
import * as path from 'node:path'
import { AddressInfo } from 'node:net'
import { log } from '../util/log'

export interface EditorBridgeConnection {
  name: string
  url: string
  token: string
}

type JsonRpcRequest = { jsonrpc?: string; id?: string | number; method?: string; params?: any }

const MAX_BODY = 1024 * 1024
const MAX_SELECTION = 8 * 1024
const MAX_DIFF = 64 * 1024

/**
 * Window-local MCP server backed by VS Code APIs. It binds only loopback, uses a per-process
 * bearer-equivalent token and exposes no workspace write/terminal tool: Runtime remains the sole
 * writer and all edits continue through task permissions, sandbox, approval and snapshot.
 */
export class EditorBridgeServer implements vscode.Disposable {
  private readonly token = randomBytes(32).toString('base64url')
  private readonly sessions = new Map<string, http.ServerResponse>()
  private server: http.Server | undefined
  private connection_: EditorBridgeConnection | undefined
  private runtimeScope: () => string | undefined = () => undefined

  setRuntimeScope(scope: () => string | undefined): void { this.runtimeScope = scope }

  get connection(): EditorBridgeConnection {
    if (!this.connection_) throw new Error('Editor Bridge has not started')
    return this.connection_
  }

  async start(): Promise<EditorBridgeConnection> {
    if (this.connection_) return this.connection_
    this.server = http.createServer((req, res) => { void this.route(req, res) })
    await new Promise<void>((resolve, reject) => {
      this.server!.once('error', reject)
      this.server!.listen(0, '127.0.0.1', () => resolve())
    })
    const port = (this.server.address() as AddressInfo).port
    this.connection_ = {
      // The process-local bridge must never collide with a user-configured MCP server name.
      name: `vscode-${vscode.env.sessionId.replace(/[^A-Za-z0-9_-]/g, '-').slice(0, 32)}-${randomUUID()}`,
      url: `http://127.0.0.1:${port}/mcp`,
      token: this.token,
    }
    log(`Editor Bridge listening at http://127.0.0.1:${port}/mcp`)
    return this.connection_
  }

  dispose(): void {
    for (const response of this.sessions.values()) response.end()
    this.sessions.clear()
    this.server?.close()
    this.server = undefined
    this.connection_ = undefined
  }

  private async route(req: http.IncomingMessage, res: http.ServerResponse): Promise<void> {
    try {
      if (req.socket.remoteAddress && !isLoopback(req.socket.remoteAddress)) return send(res, 403, 'loopback only')
      if (req.headers['x-harnex-bridge-token'] !== this.token) return send(res, 401, 'unauthorized')
      const url = new URL(req.url || '/', 'http://127.0.0.1')
      if (req.method === 'GET' && url.pathname === '/mcp') return this.openSse(res)
      if (req.method === 'POST' && url.pathname === '/mcp/messages') {
        const sessionId = url.searchParams.get('sessionId') || ''
        const sse = this.sessions.get(sessionId)
        if (!sse) return send(res, 404, 'unknown MCP session')
        const request = JSON.parse(await readBody(req)) as JsonRpcRequest
        send(res, 202, '')
        const response = await this.handle(request)
        if (response) writeSse(sse, 'message', JSON.stringify(response))
        return
      }
      send(res, 404, 'not found')
    } catch (error) {
      log(`Editor Bridge request failed: ${error instanceof Error ? error.message : error}`)
      if (!res.headersSent) send(res, 400, 'bad request')
    }
  }

  private openSse(res: http.ServerResponse): void {
    const sessionId = randomUUID()
    res.writeHead(200, {
      'content-type': 'text/event-stream',
      'cache-control': 'no-cache, no-transform',
      connection: 'keep-alive',
      'x-content-type-options': 'nosniff',
    })
    this.sessions.set(sessionId, res)
    writeSse(res, 'endpoint', `/mcp/messages?sessionId=${encodeURIComponent(sessionId)}`)
    const keepAlive = setInterval(() => res.write(': keep-alive\n\n'), 15_000)
    res.on('close', () => {
      clearInterval(keepAlive)
      this.sessions.delete(sessionId)
    })
  }

  private async handle(request: JsonRpcRequest): Promise<Record<string, unknown> | undefined> {
    const id = request.id
    if (id === undefined) return undefined // initialized notification
    try {
      switch (request.method) {
        case 'initialize':
          return rpc(id, {
            protocolVersion: '2024-11-05',
            capabilities: { tools: { listChanged: false } },
            serverInfo: { name: 'Harnex VS Code Editor Bridge', version: '1.0.0' },
          })
        case 'tools/list': return rpc(id, { tools: tools() })
        case 'tools/call': {
          const name = String(request.params?.name || '')
          const args = isRecord(request.params?.arguments) ? request.params.arguments : {}
          try {
            const scope = this.runtimeScope()
            if (!scope) throw new Error('Editor connection is unavailable or its workspace changed')
            const result = await this.callTool(name, args)
            if (this.runtimeScope() !== scope) throw new Error('Editor workspace changed during the tool call')
            return rpc(id, { content: [{ type: 'text', text: JSON.stringify(result) }], isError: false })
          } catch (error) {
            return rpc(id, {
              content: [{ type: 'text', text: error instanceof Error ? error.message : String(error) }],
              isError: true,
            })
          }
        }
        default: return rpcError(id, -32601, `Method not found: ${request.method}`)
      }
    } catch (error) {
      return rpcError(id, -32603, error instanceof Error ? error.message : String(error))
    }
  }

  private async callTool(name: string, args: Record<string, unknown>): Promise<unknown> {
    switch (name) {
      case 'editor_get_selection': return getSelection()
      case 'editor_get_diagnostics': return getDiagnostics(args)
      case 'editor_get_open_files': return getOpenFiles()
      case 'editor_get_git_diff': return getGitDiff(args)
      case 'editor_open_file': return openFile(args)
      default: throw new Error(`Unknown Editor Bridge tool: ${name}`)
    }
  }
}

function tools(): Array<Record<string, unknown>> {
  const readOnly = { readOnlyHint: true, destructiveHint: false }
  return [
    tool('editor_get_selection', 'Return the active workspace editor selection (text capped at 8 KiB).', {}, readOnly),
    tool('editor_get_diagnostics', 'Return up to 50 VS Code diagnostics for a workspace file or the workspace.', {
      path: { type: 'string', description: 'Optional workspace-relative or absolute file path.' },
    }, readOnly),
    tool('editor_get_open_files', 'Return currently open workspace text documents and dirty state.', {}, readOnly),
    tool('editor_get_git_diff', 'Return the current workspace Git diff, capped at 64 KiB.', {
      staged: { type: 'boolean', description: 'When true, return the staged/index diff.' },
    }, readOnly),
    tool('editor_open_file', 'Open a workspace file in VS Code and optionally reveal a line range.', {
      path: { type: 'string' },
      startLine: { type: 'integer', minimum: 1 },
      endLine: { type: 'integer', minimum: 1 },
    }, { readOnlyHint: true, destructiveHint: false }),
  ]
}

function tool(name: string, description: string, properties: Record<string, unknown>,
  annotations: Record<string, boolean>): Record<string, unknown> {
  const required = name === 'editor_open_file' ? ['path'] : []
  return { name, description, inputSchema: { type: 'object', properties, required, additionalProperties: false }, annotations }
}

function getSelection(): unknown {
  const editor = vscode.window.activeTextEditor
  if (!editor || editor.document.uri.scheme !== 'file') return { active: false }
  const file = trustedWorkspaceFile(editor.document.uri.fsPath)
  const selection = editor.selection
  const selectionText = editor.document.getText(selection)
  return {
    active: true,
    path: relativeWorkspacePath(file),
    languageId: editor.document.languageId,
    dirty: editor.document.isDirty,
    range: rangeView(selection),
    text: truncate(selectionText, MAX_SELECTION),
    truncated: selectionText.length > MAX_SELECTION,
  }
}

function getDiagnostics(args: Record<string, unknown>): unknown {
  const requested = typeof args.path === 'string' && args.path.trim()
    ? vscode.Uri.file(resolveWorkspaceFile(args.path)) : undefined
  const rows = requested ? [[requested, vscode.languages.getDiagnostics(requested)] as const]
    : vscode.languages.getDiagnostics()
  const diagnostics: unknown[] = []
  for (const [uri, items] of rows) {
    if (uri.scheme !== 'file' || !isWorkspaceFile(uri.fsPath)) continue
    for (const item of items) {
      diagnostics.push({
        path: relativeWorkspacePath(uri.fsPath),
        severity: vscode.DiagnosticSeverity[item.severity].toLowerCase(),
        message: truncate(item.message, 1000),
        source: item.source || '',
        code: typeof item.code === 'object' ? String(item.code.value) : String(item.code ?? ''),
        range: rangeView(item.range),
      })
      if (diagnostics.length >= 50) return { diagnostics, truncated: true }
    }
  }
  return { diagnostics, truncated: false }
}

function getOpenFiles(): unknown {
  const files = vscode.workspace.textDocuments
    .filter(document => document.uri.scheme === 'file' && isWorkspaceFile(document.uri.fsPath))
    .map(document => ({
      path: relativeWorkspacePath(document.uri.fsPath),
      languageId: document.languageId,
      dirty: document.isDirty,
    }))
  return { files }
}

async function getGitDiff(args: Record<string, unknown>): Promise<unknown> {
  const extension = vscode.extensions.getExtension<any>('vscode.git')
  if (!extension) throw new Error('VS Code Git extension is unavailable')
  const exports = extension.isActive ? extension.exports : await extension.activate()
  const api = exports?.getAPI?.(1)
  const root = workspaceRoot()
  // Only expose repositories fully rooted inside this workspace. A parent monorepo's
  // repository.diff() has no public path filter and could disclose sibling projects.
  // Prefer an explicit "no repository" result over crossing the workspace boundary.
  const repositories = (api?.repositories ?? [])
    .filter((candidate: any) => candidate?.rootUri?.scheme === 'file')
    .map((candidate: any) => ({ candidate, root: real(candidate.rootUri.fsPath) }))
    .filter((entry: { root: string }) => isInside(entry.root, root))
    .sort((a: { root: string }, b: { root: string }) =>
      path.relative(root, a.root).length - path.relative(root, b.root).length)
  const repository = repositories[0]?.candidate
  if (!repository) throw new Error('No Git repository is open for this workspace')
  const diff = String(await repository.diff(args.staged === true))
  return { diff: truncate(diff, MAX_DIFF), truncated: diff.length > MAX_DIFF, staged: args.staged === true }
}

async function openFile(args: Record<string, unknown>): Promise<unknown> {
  if (!vscode.workspace.isTrusted) throw new Error('editor_open_file is disabled in an untrusted workspace')
  if (typeof args.path !== 'string' || !args.path.trim()) throw new Error('path is required')
  const file = resolveWorkspaceFile(args.path)
  const document = await vscode.workspace.openTextDocument(vscode.Uri.file(file))
  const editor = await vscode.window.showTextDocument(document, { preview: true })
  const start = clampLine(args.startLine, document.lineCount)
  const end = clampLine(args.endLine ?? args.startLine, document.lineCount)
  if (start !== undefined) {
    const range = new vscode.Range(start, 0, end ?? start, document.lineAt(end ?? start).text.length)
    editor.selection = new vscode.Selection(range.start, range.end)
    editor.revealRange(range, vscode.TextEditorRevealType.InCenterIfOutsideViewport)
  }
  return { opened: true, path: relativeWorkspacePath(file) }
}

function workspaceRoot(): string {
  const folder = vscode.workspace.workspaceFolders?.[0]
  if (!folder || folder.uri.scheme !== 'file') throw new Error('A local workspace folder is required')
  return real(folder.uri.fsPath)
}

function resolveWorkspaceFile(value: string): string {
  const root = workspaceRoot()
  const candidate = path.isAbsolute(value) ? value : path.resolve(root, value)
  return trustedWorkspaceFile(candidate)
}

function trustedWorkspaceFile(value: string): string {
  const candidate = real(value)
  if (!isInside(candidate, workspaceRoot())) throw new Error('Editor Bridge path is outside the workspace root')
  return candidate
}

function isWorkspaceFile(value: string): boolean {
  try { return isInside(real(value), workspaceRoot()) } catch { return false }
}

function relativeWorkspacePath(value: string): string {
  return path.relative(workspaceRoot(), real(value)).replaceAll(path.sep, '/') || '.'
}

function real(value: string): string {
  const resolved = fs.realpathSync.native(value)
  return process.platform === 'win32' ? resolved.toLowerCase() : resolved
}

function isInside(candidate: string, root: string): boolean {
  const rel = path.relative(root, candidate)
  return rel === '' || (!rel.startsWith('..') && !path.isAbsolute(rel))
}

function rangeView(range: vscode.Range): unknown {
  return {
    start: { line: range.start.line + 1, character: range.start.character + 1 },
    end: { line: range.end.line + 1, character: range.end.character + 1 },
  }
}

function clampLine(value: unknown, lineCount: number): number | undefined {
  if (value === undefined || value === null) return undefined
  const line = Math.trunc(Number(value))
  return Number.isFinite(line) ? Math.max(0, Math.min(lineCount - 1, line - 1)) : undefined
}

function rpc(id: string | number, result: unknown): Record<string, unknown> {
  return { jsonrpc: '2.0', id, result }
}

function rpcError(id: string | number, code: number, message: string): Record<string, unknown> {
  return { jsonrpc: '2.0', id, error: { code, message } }
}

function writeSse(response: http.ServerResponse, event: string, data: string): void {
  response.write(`event: ${event}\ndata: ${data}\n\n`)
}

function send(response: http.ServerResponse, status: number, body: string): void {
  response.writeHead(status, { 'content-type': 'text/plain; charset=utf-8', 'x-content-type-options': 'nosniff' })
  response.end(body)
}

function readBody(request: http.IncomingMessage): Promise<string> {
  return new Promise((resolve, reject) => {
    let size = 0
    const chunks: Buffer[] = []
    request.on('data', chunk => {
      const buffer = Buffer.from(chunk)
      size += buffer.length
      if (size > MAX_BODY) { reject(new Error('MCP request body too large')); request.destroy(); return }
      chunks.push(buffer)
    })
    request.on('end', () => resolve(Buffer.concat(chunks).toString('utf8')))
    request.on('error', reject)
  })
}

function isLoopback(address: string): boolean {
  return address === '127.0.0.1' || address === '::1' || address === '::ffff:127.0.0.1'
}

function truncate(value: string, max: number): string { return value.length > max ? value.slice(0, max) : value }
function isRecord(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === 'object' && !Array.isArray(value)
}
