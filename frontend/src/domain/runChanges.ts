import type { ChangesView } from '../api/contracts'

export interface RunChangeSummary {
  count: number
  added: number
  removed: number
  paths: string[]
}

const EMPTY_SUMMARY: RunChangeSummary = { count: 0, added: 0, removed: 0, paths: [] }

/** Task-local change facts shared by the transcript notice and the Changes undo action. */
export function summarizeRunChanges(view?: ChangesView | null): RunChangeSummary {
  if (!view?.thisRun?.length) return EMPTY_SUMMARY
  const entries = view.thisRun.filter(entry => Boolean(entry?.path))
  return {
    count: entries.length,
    added: entries.reduce((total, entry) => total + Number(entry.added || 0), 0),
    removed: entries.reduce((total, entry) => total + Number(entry.removed || 0), 0),
    paths: entries.map(entry => entry.path),
  }
}
