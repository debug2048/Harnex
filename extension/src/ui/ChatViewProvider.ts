import * as vscode from 'vscode'
import { ChatController } from '../chat/ChatController'

export const CHAT_VIEW_ID = 'harnex.chat'
const CHAT_CONTAINER_ID = 'workbench.view.extension.harnex'

/**
 * Sidebar chat container (docs/03 §3.32.3): hosts the extension's own lightweight webview UI
 * (src/webview/main.ts → media/chat.js, styled purely with --vscode-* theme variables).
 * All state lives in ChatController on the host; the webview is a renderer and survives
 * reloads via envelope replay.
 */
export class ChatViewProvider implements vscode.WebviewViewProvider {
  private view: vscode.WebviewView | undefined

  constructor(
    private readonly ctx: vscode.ExtensionContext,
    private readonly controller: ChatController,
  ) {}

  resolveWebviewView(view: vscode.WebviewView): void {
    this.view = view
    const mediaRoot = vscode.Uri.joinPath(this.ctx.extensionUri, 'media')
    view.webview.options = { enableScripts: true, localResourceRoots: [mediaRoot] }
    this.ctx.subscriptions.push(this.controller.attach(view.webview))
    view.webview.html = this.buildHtml(view.webview, mediaRoot)
  }

  get isVisible(): boolean {
    return this.view?.visible === true
  }

  async reveal(preserveFocus = false): Promise<void> {
    await vscode.commands.executeCommand(`${CHAT_VIEW_ID}.focus`, { preserveFocus })
    for (let i = 0; i < 20 && !this.view; i++) await new Promise(r => setTimeout(r, 100))
  }

  /** Explicit repair only: ordinary Open Chat respects the user's chosen view position. */
  async restoreSidebar(): Promise<void> {
    const resetView = `${CHAT_VIEW_ID}.resetViewLocation`
    const resetContainer = `${CHAT_CONTAINER_ID}.resetViewContainerLocation`
    const available = new Set(await vscode.commands.getCommands(true))
    if (!available.has(resetView) || !available.has(resetContainer)) {
      throw new Error('当前 VS Code 不支持定向恢复侧栏，请在 Harnex 视图及其容器标题的右键菜单中选择“重置位置”。')
    }
    // VS Code stores the view's container and the container's sidebar location separately.
    await vscode.commands.executeCommand(resetView)
    await vscode.commands.executeCommand(resetContainer)
    await this.reveal()
  }

  private buildHtml(webview: vscode.Webview, mediaRoot: vscode.Uri): string {
    const js = webview.asWebviewUri(vscode.Uri.joinPath(mediaRoot, 'chat.js'))
    const css = webview.asWebviewUri(vscode.Uri.joinPath(mediaRoot, 'chat.css'))
    const nonce = Math.random().toString(36).slice(2) + Math.random().toString(36).slice(2)
    const csp = [
      "default-src 'none'",
      `style-src ${webview.cspSource}`,
      `script-src 'nonce-${nonce}'`,
      `font-src ${webview.cspSource}`,
      `img-src https: data: ${webview.cspSource}`,
    ].join('; ')
    return `<!doctype html>
<html lang="zh-CN">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <meta http-equiv="Content-Security-Policy" content="${csp}">
  <link rel="stylesheet" href="${css}">
</head>
<body>
  <div id="app"></div>
  <script nonce="${nonce}" src="${js}"></script>
</body>
</html>`
  }
}
