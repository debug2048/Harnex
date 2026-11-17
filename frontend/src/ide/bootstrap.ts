// Legacy Vue IDE bootstrap; the shipped extension UI is described in docs/03 §3.32.3. It
// installs the postMessage transport BEFORE the app code loads, then pulls in the regular
// main.ts. main.ts branches on __HARNEX_IDE__ for router history mode, the auth guard and
// the host→webview UI command hooks — everything else is byte-identical to the web build.

import { installIdeTransport } from './transport'

declare function acquireVsCodeApi(): { postMessage(msg: unknown): void; getState(): unknown; setState(s: unknown): void }

const vsapi = acquireVsCodeApi()
;(globalThis as unknown as Record<string, unknown>).__HARNEX_IDE__ = true
;(globalThis as unknown as Record<string, unknown>).__harnexVsApi = vsapi

installIdeTransport(vsapi)

// Dynamic import — a static one would hoist main.ts's evaluation above the flag/transport setup.
import('../main')
