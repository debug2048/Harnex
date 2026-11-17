/** Presentation only: keep the original error in state and diagnostics; never use these matches to retry a call. */
export function providerErrorPresentation(message?: string | null): string | null {
  const detail = message?.trim() || ''
  if (/^Provider returned no answer content\b/i.test(detail)) return 'error.providerEmptyResponse'
  if (/^stream sent \[DONE\] without finish_reason\b/i.test(detail)) return 'error.providerMissingFinish'
  if (/^(?:Upstream connection closed before the response completed|Upstream response stream was interrupted)\b/i.test(detail)
    || /^(?:connection reset(?: by peer)?|broken pipe)$/i.test(detail)) return 'error.providerInterrupted'
  if (/^Generation reached a token limit\b|^(?:stream|oneShot) ended with finish_reason=length\b/i.test(detail)) {
    return 'error.providerOutputLimit'
  }
  if (/^(?:timeout|read timed out|connect timed out|request timed out|request timeout)$/i.test(detail)
    || /^no first content from .+ within \d+ms$/i.test(detail)
    || /^HTTP 504\b/.test(detail)) return 'error.providerTimeout'
  if (/^HTTP (?:502|503)\b/.test(detail)
    || /^Upstream service temporarily unavailable$/i.test(detail)) return 'error.providerUnavailable'
  return null
}
