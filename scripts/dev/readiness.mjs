import { setTimeout as sleep } from 'node:timers/promises'

export async function waitFor(url, label, { child, timeoutMs = 90_000 } = {}) {
  const controller = new AbortController()
  const { signal } = controller
  let lastErr
  const onExit = (code, sig) => controller.abort(new Error(`${label} 在就绪前已退出 (code=${code} signal=${sig})`))
  const onError = error => controller.abort(new Error(`${label} 启动失败：${error.message}`, { cause: error }))
  const timeout = setTimeout(() => controller.abort(new Error(
    `${label} 在 ${url} 未在 ${timeoutMs}ms 内就绪：${lastErr?.message || '请求未完成'}`)), timeoutMs)
  child?.once('exit', onExit)
  child?.once('error', onError)
  if (child && (child.exitCode != null || child.signalCode != null)) onExit(child.exitCode, child.signalCode)
  try {
    while (true) {
      signal.throwIfAborted()
      try {
        const response = await fetch(url, { signal })
        await response.body?.cancel()
        signal.throwIfAborted()
        // Auth-protected health endpoints can return 401/403 once the service is ready.
        if (response.status < 500) return
        lastErr = new Error(`HTTP ${response.status}`)
      } catch (error) {
        signal.throwIfAborted()
        lastErr = error
      }
      await sleep(800, undefined, { signal })
    }
  } catch (error) {
    throw signal.aborted ? signal.reason : error
  } finally {
    clearTimeout(timeout)
    child?.removeListener('exit', onExit)
    child?.removeListener('error', onError)
  }
}
