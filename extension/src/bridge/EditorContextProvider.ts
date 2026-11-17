import * as vscode from 'vscode'
import * as path from 'node:path'

const MAX_CONTEXT_BYTES = 1024
const MAX_AGE_MS = 5 * 60_000

interface Snapshot {
  value: string
  updatedAt: number
}

/** Compact passive context cache; diagnostics/open files/diff remain on-demand MCP tools. */
export class EditorContextProvider implements vscode.Disposable {
  private current: Snapshot | undefined
  private selectionTimer: NodeJS.Timeout | undefined
  private readonly subscriptions: vscode.Disposable[]

  constructor() {
    this.subscriptions = [
      vscode.workspace.onDidChangeWorkspaceFolders(() => this.capture()),
      vscode.window.onDidChangeActiveTextEditor(() => this.capture()),
      vscode.window.onDidChangeTextEditorSelection(() => {
        if (this.selectionTimer) clearTimeout(this.selectionTimer)
        this.selectionTimer = setTimeout(() => this.capture(), 500)
      }),
    ]
    this.capture()
  }

  snapshot(): string | undefined {
    if (!this.current || Date.now() - this.current.updatedAt > MAX_AGE_MS) return undefined
    return this.current.value
  }

  dispose(): void {
    if (this.selectionTimer) clearTimeout(this.selectionTimer)
    this.subscriptions.forEach(subscription => subscription.dispose())
  }

  private capture(): void {
    const editor = vscode.window.activeTextEditor
    if (!editor || editor.document.uri.scheme !== 'file') { this.current = undefined; return }
    // The Runtime has exactly one canonical root (the first local workspace folder). Do not
    // pass context from a second multi-root folder into a Runtime that cannot safely access it.
    const folder = vscode.workspace.workspaceFolders?.[0]
    if (!folder || folder.uri.scheme !== 'file') { this.current = undefined; return }
    const relative = path.relative(folder.uri.fsPath, editor.document.uri.fsPath).replaceAll(path.sep, '/')
    if (!relative || relative.startsWith('..') || path.isAbsolute(relative)) { this.current = undefined; return }
    const selection = editor.selection
    const lines = [
      `activeFile: ${relative}`,
      `languageId: ${editor.document.languageId}`,
      `dirty: ${editor.document.isDirty}`,
      `selection: ${selection.start.line + 1}:${selection.start.character + 1}`
        + `-${selection.end.line + 1}:${selection.end.character + 1}`,
    ]
    if (!selection.isEmpty) lines.push('selectionText:', editor.document.getText(selection))
    this.current = { value: truncateUtf8(lines.join('\n'), MAX_CONTEXT_BYTES), updatedAt: Date.now() }
  }
}

function truncateUtf8(value: string, maxBytes: number): string {
  let bytes = 0
  let result = ''
  for (const character of value) {
    const size = Buffer.byteLength(character, 'utf8')
    if (bytes + size > maxBytes) break
    result += character
    bytes += size
  }
  return result
}
