import * as vscode from 'vscode'

let channel: vscode.OutputChannel | undefined

export function getLog(): vscode.OutputChannel {
  if (!channel) channel = vscode.window.createOutputChannel('Harnex')
  return channel
}

export function log(msg: string): void {
  getLog().appendLine(`[${new Date().toISOString()}] ${msg}`)
}
