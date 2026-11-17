import type { QuestionOption } from './chat'
import { isRecord } from '../utils/unknown'

function plain(value: string): string {
  return value.replace(/\*\*(.*?)\*\*/g, '$1').replace(/`([^`]+)`/g, '$1').trim()
}

/** Keep the original answer value while presenting both new and persisted string options consistently. */
export function normalizeQuestionOptions(callId: string, raw: unknown, details?: unknown): QuestionOption[] {
  if (!Array.isArray(raw)) return []
  return raw.flatMap((item, index) => {
    const detail = Array.isArray(details) && isRecord(details[index]) ? details[index] : isRecord(item) ? item : null
    const value = typeof item === 'string' ? item.trim() : detail && typeof detail.label === 'string'
      ? [detail.label.trim(), typeof detail.description === 'string' ? detail.description.trim() : ''].filter(Boolean).join(' — ') : ''
    if (!value) return []
    let label = detail && typeof detail.label === 'string' ? detail.label : value
    let description = detail && typeof detail.description === 'string' ? detail.description : ''
    if (!detail) {
      const separator = /\r?\n|\s+[—–]\s+|：|:\s+/.exec(label)
      if (separator) {
        description = label.slice(separator.index + separator[0].length)
        label = label.slice(0, separator.index)
      }
    }
    label = plain(label).replace(/^(?:[A-Za-z]|\d{1,2})[.)、]\s+/, '')
    const suffix = /\s*[(（](?:推荐|建议|recommended)[)）]\s*$/i
    const recommended = detail?.recommended === true || suffix.test(label)
    label = label.replace(suffix, '').trim()
    if (!label) return []
    return [{ key: `${callId}:${index}`, label, description: plain(description).replace(/\s+/g, ' '), recommended, value, index }]
  })
}
