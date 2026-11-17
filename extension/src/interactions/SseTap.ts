import type { StreamEventEnvelope } from '../../../packages/protocol'

/**
 * Incremental SSE frame parser. FetchProxy feeds it the raw bytes it is already relaying to
 * the webview; we surface each parsed `data:` JSON as a Harnex stream-event envelope
 * (docs/03 §3.14: {type, data, runId?, conversationId?, seq?, streamEpoch?, ts?}) so host-side consumers
 * (InteractionRouter, StatusBar) can react without opening a second connection.
 */
export type StreamEnvelope = StreamEventEnvelope & { type: string }

export class SseTap {
  private buf = ''

  constructor(private readonly onEnvelope: (env: StreamEnvelope) => void) {}

  feed(text: string): void {
    this.buf += text
    // Frames are separated by a blank line; tolerate \r\n line endings.
    let idx: number
    while ((idx = this.buf.search(/\r?\n\r?\n/)) >= 0) {
      const frame = this.buf.slice(0, idx)
      this.buf = this.buf.slice(idx).replace(/^\r?\n\r?\n/, '')
      this.emit(frame)
    }
  }

  private emit(frame: string): void {
    const dataLines: string[] = []
    for (const line of frame.split(/\r?\n/)) {
      if (line.startsWith('data:')) dataLines.push(line.slice(5).replace(/^ /, ''))
      // event:/id:/retry:/comments — the envelope JSON already carries `type`, ignore.
    }
    if (dataLines.length === 0) return
    let env: StreamEnvelope
    try { env = JSON.parse(dataLines.join('\n')) as StreamEnvelope }
    catch { return /* keep-alive or non-JSON data — ignore */ }
    // Deliberately invoke outside the JSON catch: consumers use a thrown ProjectionReset
    // to terminate this response and rebuild from durable resources.
    if (env && typeof env.type === 'string') this.onEnvelope(env)
  }
}
