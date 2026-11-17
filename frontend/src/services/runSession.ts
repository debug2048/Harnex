import { RuntimeClient, withReadTimeout, type StreamHandler } from '../api/client'

/** A Run keeps its originating client even when the workbench selects another scope. */
export class RunSession {
  readonly key: string
  constructor(readonly client: RuntimeClient, readonly runId: string) {
    const identity = client.target.identity
    this.key = `${identity?.scopeId || ''}:${identity?.instanceId || ''}:${runId}`
  }
  presentation(signal?: AbortSignal) { return withReadTimeout(request => this.client.api.presentation(this.runId, request), signal) }
  subscribe(afterSeq: number, handler: StreamHandler, signal?: AbortSignal, streamEpoch?: number) {
    return this.client.stream(this.runId, afterSeq, handler, signal, streamEpoch)
  }
  async cancel() {
    const run = await this.client.api.run(this.runId)
    return run.goalId ? this.client.api.cancelGoal(this.runId, run.revision || 0, crypto.randomUUID())
      : this.client.api.cancelRun(this.runId)
  }
}
