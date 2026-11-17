/**
 * Interleave semantic activities with already ordered transcript entries.
 *
 * Entries without a sequence keep their original position. This matters for the optimistic
 * user message and pre-existing thread history, which precede the current Run. Activities
 * are inserted immediately before the first later sequenced entry; anything not yet followed
 * by a committed entry remains at the live edge of the timeline.
 */
export type ActivityTimelineEntry<TItem, TActivity> =
  | { kind: 'item'; key: string; item: TItem }
  | { kind: 'activity'; key: string; activity: TActivity }

export function interleaveActivities<
  TItem extends { key: string; seq?: number },
  TActivity extends { id: string; seq: number },
>(items: TItem[], activities: TActivity[]): ActivityTimelineEntry<TItem, TActivity>[] {
  const pending = activities
    .map((activity, index) => ({ activity, index }))
    .sort((left, right) => left.activity.seq - right.activity.seq || left.index - right.index)
  const result: ActivityTimelineEntry<TItem, TActivity>[] = []
  let activityIndex = 0

  for (const item of items) {
    const itemSeq = Number(item.seq)
    if (Number.isFinite(itemSeq) && itemSeq > 0) {
      while (activityIndex < pending.length && pending[activityIndex].activity.seq <= itemSeq) {
        const activity = pending[activityIndex++].activity
        result.push({ kind: 'activity', key: `activity:${activity.id}`, activity })
      }
    }
    result.push({ kind: 'item', key: `item:${item.key}`, item })
  }

  while (activityIndex < pending.length) {
    const activity = pending[activityIndex++].activity
    result.push({ kind: 'activity', key: `activity:${activity.id}`, activity })
  }
  return result
}
