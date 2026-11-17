import * as vscode from 'vscode'
import * as path from 'node:path'
import { ChatController } from './chat/ChatController'
import { HarnexClient } from './client/HarnexClient'
import { registerCommands } from './commands'
import { DiffReview } from './interactions/DiffReview'
import { InteractionRouter } from './interactions/InteractionRouter'
import { RuntimeManager } from './runtime/RuntimeManager'
import { ChatViewProvider, CHAT_VIEW_ID } from './ui/ChatViewProvider'
import { StatusBar } from './ui/StatusBar'
import { cfg } from './util/config'
import { log } from './util/log'
import { EditorBridgeServer } from './bridge/EditorBridgeServer'
import { EditorContextProvider } from './bridge/EditorContextProvider'

let runtimeForDeactivate: RuntimeManager | undefined

export async function activate(ctx: vscode.ExtensionContext): Promise<Record<string, unknown>> {
  const bridge = new EditorBridgeServer()
  await bridge.start()
  const editorContext = new EditorContextProvider()
  const runtime = new RuntimeManager(ctx, bridge.connection)
  bridge.setRuntimeScope(() => runtime.editorConnectionScope)
  runtimeForDeactivate = runtime
  const updateTerminalEnvironment = () => {
    const environment = ctx.environmentVariableCollection
    environment.delete('HARNEX_DOMAIN_MANIFEST'); environment.delete('PATH')
    if (runtime.currentState !== 'running') return
    environment.replace('HARNEX_DOMAIN_MANIFEST', runtime.connectionManifestFile)
    const cli = runtime.cliBinDir
    if (cli) environment.prepend('PATH', `${cli}${path.delimiter}`)
    environment.description = runtime.isAttached ? 'Harnex: connected to an existing project Runtime' : 'Harnex: this window owns its project Runtime'
  }
  const client = new HarnexClient(runtime)
  const diffReview = new DiffReview(client)
  const statusBar = new StatusBar(cfg().defaultPermissionMode)
  // Two-phase wiring: the router needs chat visibility, the controller needs the router.
  let chat: ChatViewProvider
  const router = new InteractionRouter(client, diffReview, () => chat?.isVisible === true)
  const controller = new ChatController(ctx, runtime, client, editorContext, router, statusBar)
  chat = new ChatViewProvider(ctx, controller)

  ctx.subscriptions.push(
    runtime, bridge, editorContext, diffReview, statusBar, controller,
    vscode.window.registerWebviewViewProvider(CHAT_VIEW_ID, chat, {
      webviewOptions: { retainContextWhenHidden: true },
    }),
    runtime.onDidChangeState(s => statusBar.setState(s, runtime.lastError)),
    runtime.onDidChangeState(updateTerminalEnvironment),
    runtime.onDidChangeState(s => { if (s === 'running') void router.surfacePending().catch(err => log(String(err))) }),
  )

  registerCommands(ctx, { runtime, client, chat, controller, statusBar, router })

  // Resolve the selected connection once, then restore only this client's pending approvals.
  void runtime.initialize()
    .catch(err => {
      if (err instanceof Error && ['RuntimeConnectionCancelled', 'RuntimeSwitchDeclined', 'AttachmentChangedError'].includes(err.name)) return
      log(`runtime startup failed: ${err instanceof Error ? err.message : err}`)
      if (runtime.currentState === 'error') void chat.reveal(true).catch(failure => log(String(failure)))
    })

  // Test handles (extension/test/suite) — not a public API.
  return { runtime, bridge, editorContext, client, chat, controller, router, diffReview }
}

export async function deactivate(): Promise<void> {
  // VS Code accepts an async deactivation hook. Await drain/tree termination so Extension Host
  // shutdown cannot orphan a Runtime after a fire-and-forget Disposable returned.
  await runtimeForDeactivate?.stop()
  runtimeForDeactivate = undefined
}
