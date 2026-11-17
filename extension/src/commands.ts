import * as vscode from 'vscode'
import { ChatController } from './chat/ChatController'
import { HarnexClient } from './client/HarnexClient'
import { InteractionRouter } from './interactions/InteractionRouter'
import { RuntimeManager } from './runtime/RuntimeManager'
import { ChatViewProvider } from './ui/ChatViewProvider'
import { StatusBar } from './ui/StatusBar'
import { PermissionMode } from './util/config'
import { getLog, log } from './util/log'

interface Deps {
  runtime: RuntimeManager
  client: HarnexClient
  chat: ChatViewProvider
  controller: ChatController
  statusBar: StatusBar
  router: InteractionRouter
}

export function registerCommands(ctx: vscode.ExtensionContext, d: Deps): void {
  const reg = (id: string, fn: (...a: unknown[]) => unknown) =>
    ctx.subscriptions.push(vscode.commands.registerCommand(id, fn))

  const runtimeCommand = (action: () => Promise<unknown>) => async () => {
    try { await action() }
    catch (error) {
      log(`Runtime command failed: ${error instanceof Error ? error.message : error}`)
      if (error instanceof Error && ['RuntimeConnectionCancelled', 'RuntimeSwitchDeclined', 'AttachmentChangedError'].includes(error.name)) return
      // Consume the rejection so VS Code does not create another, non-dismissable-by-code toast.
      // RuntimeManager owns the latest connection error and clears it on retry/success.
      if (d.runtime.currentState === 'error') await d.chat.reveal(true).catch(failure => log(String(failure)))
    }
  }

  const prompt = async (text: string) => {
    await d.chat.reveal()
    try { await d.controller.send(text) }
    catch (error) { d.controller.reportClientError(error) }
  }

  reg('harnex.startRuntime', runtimeCommand(() => d.runtime.startManaged()))
  reg('harnex.attachRuntime', runtimeCommand(() => d.runtime.connectExisting()))
  reg('harnex.disconnectRuntime', runtimeCommand(() => d.runtime.disconnect()))
  reg('harnex.connectRuntime', async () => {
    const action = await vscode.window.showQuickPick([
      { label: '连接已有 Runtime', description: '连接当前项目；更换项目时先确认宿主切换', command: 'harnex.attachRuntime' },
      { label: '启动独立 Runtime', description: '使用已安装的 Runtime 插件', command: 'harnex.startRuntime' },
    ], { title: 'Harnex · 连接 Runtime' })
    if (action) await vscode.commands.executeCommand(action.command)
  })

  reg('harnex.openChat', (preserveFocus?: unknown) => d.chat.reveal(preserveFocus === true))
  reg('harnex.restoreSidebar', () => d.chat.restoreSidebar())
  reg('harnex.showHistory', async () => {
    await d.chat.reveal()
    try { await d.controller.showHistory() }
    catch (error) { d.controller.reportClientError(error) }
  })
  reg('harnex.newThread', async () => { await d.chat.reveal(); void d.controller.newThread() })
  reg('harnex.showLogs', () => getLog().show())
  reg('harnex.copyCliAttachCommand', async () => {
    try {
      await d.runtime.ensure()
      const manifest = d.runtime.connectionManifestFile.replaceAll('"', '\\"')
      const command = `harnex --domain-manifest "${manifest}" cli -p "..."`
      await vscode.env.clipboard.writeText(command)
      void vscode.window.showInformationMessage('Harnex CLI attach command copied')
    } catch (err) {
      d.controller.reportClientError(err)
      await d.chat.reveal(true)
    }
  })
  reg('harnex.restartBackend', runtimeCommand(async () => {
    await vscode.window.withProgress(
      { location: vscode.ProgressLocation.Notification, title: d.runtime.isAttached ? 'Harnex: reconnecting runtime…' : 'Harnex: restarting runtime…' },
      () => d.runtime.restart())
  }))

  reg('harnex.showDiagnostics', async () => {
    try {
      const diag = await d.client.diagnostics()
      const out = getLog()
      out.appendLine('── /api/v1/diagnostics ──')
      out.appendLine(JSON.stringify(diag, null, 2))
      out.show()
    } catch (err) {
      d.controller.reportClientError(err)
      await d.chat.reveal(true)
    }
  })

  reg('harnex.setPermissionMode', async () => {
    const picks: { label: string; mode: PermissionMode; description: string }[] = [
      { label: '$(shield) Ask', mode: 'ask', description: '文件修改与危险命令全部审批（默认）' },
      { label: '$(edit) Accept Edits', mode: 'accept-edits', description: '文件编辑自动放行，命令仍审批' },
      { label: '$(zap) Full Access (yolo)', mode: 'yolo', description: '全自动 — 谨慎使用' },
    ]
    const pick = await vscode.window.showQuickPick(picks, { placeHolder: '选择 Harnex 权限模式（对当前会话生效）' })
    if (!pick) return
    await d.controller.setMode(pick.mode)
  })

  reg('harnex.showPendingInteractions', () => d.router.surfacePending())

  reg('harnex.askSelection', async () => {
    const sel = grabSelection()
    if (!sel) return
    const q = await vscode.window.showInputBox({ prompt: 'Harnex: 想问什么？', placeHolder: '关于选中代码的问题…' })
    if (!q) return
    await prompt(`${q}\n\n${sel}`)
  })

  reg('harnex.explainSelection', async () => {
    const sel = grabSelection()
    if (!sel) return
    await prompt(`请解释下面这段代码的作用、关键逻辑和潜在问题：\n\n${sel}`)
  })

  reg('harnex.fixDiagnostic', async () => {
    const editor = vscode.window.activeTextEditor
    const sel = grabSelection()
    if (!editor || !sel) return
    const diags = vscode.languages.getDiagnostics(editor.document.uri)
      .filter(x => x.range.intersection(editor.selection) || editor.selection.isEmpty)
      .slice(0, 20)
      .map(x => `- L${x.range.start.line + 1}: [${vscode.DiagnosticSeverity[x.severity]}] ${x.message}`)
      .join('\n')
    await prompt(`请修复下面代码中的问题。${diags ? `编辑器诊断信息：\n${diags}\n` : ''}\n${sel}`)
  })
}

function grabSelection(): string | undefined {
  const editor = vscode.window.activeTextEditor
  if (!editor || editor.selection.isEmpty) {
    void vscode.window.showInformationMessage('Harnex: 请先选中一段代码')
    return undefined
  }
  const doc = editor.document
  const rel = vscode.workspace.asRelativePath(doc.uri)
  const start = editor.selection.start.line + 1
  const end = editor.selection.end.line + 1
  let text = doc.getText(editor.selection)
  if (text.length > 8192) text = `${text.slice(0, 8192)}\n…(truncated)`
  return `文件: ${rel} (第 ${start}-${end} 行)\n\`\`\`${doc.languageId}\n${text}\n\`\`\``
}
