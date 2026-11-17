import * as vscode from 'vscode'

export type PermissionMode = 'ask' | 'accept-edits' | 'yolo'

export function cfg() {
  const c = vscode.workspace.getConfiguration('harnex')
  return {
    runtimeMode: c.get<'auto' | 'managed' | 'existing'>('backend.mode', 'auto'),
    manifestPath: expand(c.get<string>('backend.manifestPath', '')).trim(),
    javaPath: expand(c.get<string>('backend.javaPath', '')),
    jarPath: expand(c.get<string>('backend.jarPath', '')),
    jvmArgs: c.get<string[]>('backend.jvmArgs', ['-Xmx1g']).map(expand),
    dataDir: expand(c.get<string>('backend.dataDir', '')),
    defaultPermissionMode: c.get<PermissionMode>('permission.defaultMode', 'ask'),
  }
}

/**
 * VS Code only substitutes ${workspaceFolder} in task/launch configs, not in extension
 * settings — do it ourselves so .vscode/settings.json can stay machine-independent.
 */
function expand(v: string): string {
  const ws = vscode.workspace.workspaceFolders?.[0]?.uri.fsPath ?? ''
  return v.replace(/\$\{workspaceFolder\}/g, ws)
}

/**
 * Proxy passthrough (docs/03 §3.32.8): forward VS Code's http.proxy ONLY when the user set it
 * explicitly — an unset value must not masquerade as a hard override of agent-config.yml.
 */
export function buildProxyArgs(): string[] {
  const http = vscode.workspace.getConfiguration('http')
  const inspected = http.inspect<string>('proxy')
  const userValue = inspected?.globalValue ?? inspected?.workspaceValue
  if (userValue === undefined) return []
  if (userValue === '') return ['--harnex.http.proxy=off']
  const args = [`--harnex.http.proxy=${userValue}`]
  const support = http.get<string>('proxySupport')
  if (support) args.push(`--harnex.http.proxy-support=${support}`)
  return args
}
