import * as vscode from 'vscode'
import * as fs from 'node:fs'
import * as path from 'node:path'
import { createHash } from 'node:crypto'

export interface AttachBinding {
  file: string
  ownerInstanceId: string
  instanceId: string
  root: string
  clientId: string
  invalid?: boolean
}

const domains = require('../../../bin/lib/runtime-domain.mjs') as {
  processStartedAtSync(pid: number): string | null
  atomicWriteJsonSync(file: string, value: unknown, options: { backup: boolean }): void
}

/** Same-window continuity only: a fresh window must explicitly choose its connection. */
export class AttachBindingStore {
  private constructor(private readonly file: string) {}

  static async create(ctx: vscode.ExtensionContext): Promise<AttachBindingStore | undefined> {
    try {
      // VS Code routes extension callback URIs to the originating native window. Unlike
      // env.sessionId, that route survives Open Folder and Reload Window. Fail closed on
      // hosts that do not expose such a route; never fall back to a global last connection.
      const callback = await vscode.env.asExternalUri(vscode.Uri.parse(`${vscode.env.uriScheme}://${ctx.extension.id}/runtime-window`))
      const windowId = new URLSearchParams(callback.query).get('windowId')
      const parentStart = domains.processStartedAtSync(process.ppid)
      if (callback.scheme !== vscode.env.uriScheme || !windowId || !/^\d+$/.test(windowId) || !parentStart) return undefined
      const key = createHash('sha256').update(`${process.ppid}:${parentStart}:${callback.toString()}`).digest('hex')
      return new AttachBindingStore(path.join(ctx.globalStorageUri.fsPath, 'runtime-windows', `${key}.json`))
    } catch { return undefined }
  }

  read(): AttachBinding | undefined {
    try {
      if (fs.statSync(this.file).size > 65536) return undefined
      const value = JSON.parse(fs.readFileSync(this.file, 'utf8')) as AttachBinding
      if (!value.file || !path.isAbsolute(value.file) || !value.ownerInstanceId || !value.instanceId
          || !value.root || !/^vscode-[A-Za-z0-9-]{1,80}$/.test(value.clientId)) return undefined
      return value
    } catch { return undefined }
  }

  write(binding: AttachBinding): void {
    fs.mkdirSync(path.dirname(this.file), { recursive: true, mode: 0o700 })
    domains.atomicWriteJsonSync(this.file, binding, { backup: false })
  }

  clear(): void { try { fs.unlinkSync(this.file) } catch {} }
}
