/** Measure on hover so resized panes use their current rendered width. */
export function updateOverflowTitle(event: MouseEvent, text?: string) {
  const element = event.currentTarget as HTMLElement | null
  if (element) element.title = text && element.scrollWidth > element.clientWidth ? text : ''
}
