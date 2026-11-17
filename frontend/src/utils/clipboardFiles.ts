type ClipboardItemLike = Pick<DataTransferItem, 'kind' | 'type' | 'getAsFile'>

const IMAGE_EXTENSIONS: Record<string, string> = {
  'image/png': 'png',
  'image/jpeg': 'jpg',
  'image/gif': 'gif',
  'image/webp': 'webp',
  'image/bmp': 'bmp',
  'image/tiff': 'tiff',
  'image/svg+xml': 'svg',
  'image/heic': 'heic',
  'image/heif': 'heif',
}

function screenshotStem(now: number) {
  return `screenshot-${new Date(now).toISOString().replace(/\D/g, '').slice(0, 14)}`
}

/** Convert clipboard image items into ordinary Files consumed by the existing upload pipeline. */
export function imageFilesFromClipboard(
    clipboard: { items: ArrayLike<ClipboardItemLike> } | null,
    now = Date.now()): File[] {
  if (!clipboard?.items) return []
  const images: File[] = []
  for (const item of Array.from(clipboard.items)) {
    if (item.kind !== 'file' || !item.type.toLowerCase().startsWith('image/')) continue
    const source = item.getAsFile()
    if (!source) continue
    const mime = (source.type || item.type).toLowerCase()
    const extension = IMAGE_EXTENSIONS[mime] || 'png'
    const suffix = images.length ? `-${images.length + 1}` : ''
    images.push(new File([source], `${screenshotStem(now)}${suffix}.${extension}`, {
      type: mime,
      lastModified: now,
    }))
  }
  return images
}
