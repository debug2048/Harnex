import { isTauriHost, nativeFileSave } from '../api/client'
import { t } from '../i18n'

interface FileWriter { write(data: Blob): Promise<void>; close(): Promise<void>; abort(): Promise<void> }
interface FileHandle { createWritable(): Promise<FileWriter> }
type PickerWindow = Window & {showSaveFilePicker?: (options:{suggestedName:string}) => Promise<FileHandle>}
export interface DownloadDestination {
  write(data: Blob, signal: AbortSignal): Promise<void>
  cancel(): Promise<void>
}
function check(signal: AbortSignal) {
  if (signal.aborted) throw new DOMException('Download cancelled', 'AbortError')
}

/** Must be called directly by the click handler, before fetching any file bytes. */
export async function chooseDownloadDestination(name: string): Promise<DownloadDestination | null> {
  if (isTauriHost()) {
    const id = await nativeFileSave.pick(name)
    if (!id) return null
    return {
      async write(data, signal) {
        const bytes = new Uint8Array(await data.arrayBuffer()); check(signal)
        await nativeFileSave.write(id, bytes)
      },
      cancel: () => nativeFileSave.cancel(id),
    }
  }
  const picker = (window as PickerWindow).showSaveFilePicker
  if (picker) {
    let handle: FileHandle
    try { handle = await picker.call(window, {suggestedName:name}) }
    catch (error) { if (error instanceof DOMException && error.name === 'AbortError') return null; throw error }
    let writer: FileWriter | null = null
    return {
      async write(data, signal) {
        check(signal); writer = await handle.createWritable()
        try { check(signal); await writer.write(data); check(signal); await writer.close(); writer = null }
        catch (error) { await writer?.abort().catch(() => {}); writer = null; throw error }
      },
      async cancel() { await writer?.abort().catch(() => {}); writer = null },
    }
  }
  // Legacy browsers control the save dialog themselves. Explain that boundary and
  // let the user cancel instead of silently choosing the default Downloads folder.
  if (!window.confirm(t('files.saveLocationFallback'))) return null
  return {
    async write(data, signal) {
      check(signal)
      const url = URL.createObjectURL(data), link = document.createElement('a')
      link.href = url; link.download = name
      document.body.append(link); link.click(); link.remove()
      setTimeout(() => URL.revokeObjectURL(url), 1000)
    },
    async cancel() {},
  }
}
