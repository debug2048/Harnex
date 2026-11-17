/** Stable server codes drive recovery actions; provider prose is never parsed for intent. */
export function imageFailureKey(code: string) {
  if (code === 'media_tool_call_not_emitted') return 'image.toolCallMissing'
  if (code === 'image_rate_limited') return 'image.rateLimited'
  if (code === 'image_access_denied' || code === 'image_credentials_unavailable') return 'image.accessDenied'
  if (code === 'image_model_unavailable' || code === 'image_model_not_found') return 'image.modelUnavailable'
  if (/^image_(model_not_configured|service_|connection_|configuration_|profile_)/.test(code)) return 'image.configurationRequired'
  return 'image.failed'
}

export const isImageFailure = (code?: string | null) => /^(image_|media_)/.test(code || '')
