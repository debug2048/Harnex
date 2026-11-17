import * as vscode from 'vscode'
import { RuntimeState } from '../runtime/RuntimeManager'
import { PermissionMode } from '../util/config'

/** Status bar item (docs/03 §3.32.3): runtime state + current permission mode. */
export class StatusBar implements vscode.Disposable {
  private readonly item: vscode.StatusBarItem
  private state: RuntimeState = 'stopped'
  private error = ''
  private mode: PermissionMode

  constructor(defaultMode: PermissionMode) {
    this.mode = defaultMode
    this.item = vscode.window.createStatusBarItem(vscode.StatusBarAlignment.Right, 90)
    this.item.name = 'Harnex'
    this.item.command = 'harnex.setPermissionMode'
    this.render()
    this.item.show()
  }

  setState(s: RuntimeState, error = ''): void {
    this.state = s
    this.error = error
    this.render()
  }

  setMode(m: PermissionMode): void {
    this.mode = m
    this.render()
  }

  get currentMode(): PermissionMode { return this.mode }

  private render(): void {
    const icon = { stopped: '$(circle-slash)', starting: '$(sync~spin)', running: '$(check)', error: '$(error)' }[this.state]
    const connected = this.state === 'running'
    this.item.command = connected ? 'harnex.setPermissionMode' : this.state === 'stopped' ? 'harnex.connectRuntime' : 'harnex.openChat'
    this.item.text = `${icon} Harnex · ${connected ? this.mode : this.state === 'starting' ? '连接中' : this.state === 'error' ? '连接失败' : '未连接'}`
    this.item.tooltip = connected ? `Harnex · 权限模式: ${this.mode}（点击修改）`
      : this.state === 'error' ? `${this.error || 'Runtime 连接失败'}（点击查看）` : 'Harnex · 点击查看 Runtime 连接'
    this.item.backgroundColor = this.state === 'error' ? new vscode.ThemeColor('statusBarItem.errorBackground')
      : connected && this.mode === 'yolo' ? new vscode.ThemeColor('statusBarItem.warningBackground') : undefined
  }

  dispose(): void {
    this.item.dispose()
  }
}
