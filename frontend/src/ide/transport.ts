// Legacy Vue IDE transport; the shipped extension uses ChatController (docs/03 §3.32.3).
//
// Replaces window.fetch for backend paths (/api, /actuator) with a postMessage bridge to
// the extension host. The host attaches the Bearer session token (which never enters the
// webview) and streams the response body back in base64 chunks; we surface it as a real
// Response backed by a ReadableStream, so authFetch, @microsoft/fetch-event-source and the
// raw fetch() call sites in main.ts / App.vue all work unchanged.
//
// Experimental message protocol retained by this legacy build:
//   webview → host: { __harnex, kind:'http', id, url, method, headers, body?, bodyB64? }
//                   { __harnex, kind:'abort', id }
//   host → webview: { __harnex, kind:'head', id, status, statusText, headers:[k,v][] }
//                   { __harnex, kind:'chunk', id, b64 }
//                   { __harnex, kind:'end', id }
//                   { __harnex, kind:'fail', id, message }

interface VsCodeApi {
  postMessage(msg: unknown): void
}

type HostMsg =
  | { __harnex: true; kind: 'head'; id: number; status: number; statusText: string; headers: [string, string][] }
  | { __harnex: true; kind: 'chunk'; id: number; b64: string }
  | { __harnex: true; kind: 'end'; id: number }
  | { __harnex: true; kind: 'fail'; id: number; message: string }

interface PendingReq {
  resolve: (r: Response) => void
  reject: (e: Error) => void
  controller: ReadableStreamDefaultController<Uint8Array> | null
  headReceived: boolean
}

function b64ToBytes(b64: string): Uint8Array {
  const bin = atob(b64)
  const out = new Uint8Array(bin.length)
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i)
  return out
}

function bytesToB64(bytes: Uint8Array): string {
  let bin = ''
  const CHUNK = 0x8000
  for (let i = 0; i < bytes.length; i += CHUNK) {
    bin += String.fromCharCode(...bytes.subarray(i, i + CHUNK))
  }
  return btoa(bin)
}

function headersToRecord(h: HeadersInit | undefined): Record<string, string> {
  const out: Record<string, string> = {}
  if (!h) return out
  if (h instanceof Headers) {
    h.forEach((v, k) => { out[k] = v })
  } else if (Array.isArray(h)) {
    for (const [k, v] of h) out[k] = v
  } else {
    Object.assign(out, h)
  }
  return out
}

export function installIdeTransport(vsapi: VsCodeApi): void {
  const realFetch = window.fetch.bind(window)
  const pending = new Map<number, PendingReq>()
  let nextId = 1

  window.addEventListener('message', (e: MessageEvent) => {
    const m = e.data as HostMsg
    if (!m || (m as { __harnex?: boolean }).__harnex !== true) return
    const p = pending.get(m.id)
    if (!p) return
    switch (m.kind) {
      case 'head': {
        p.headReceived = true
        const headers = new Headers(m.headers)
        // Statuses that forbid a body must construct Response with null body.
        const bodyless = m.status === 204 || m.status === 205 || m.status === 304
        if (bodyless) {
          pending.delete(m.id)
          p.resolve(new Response(null, { status: m.status, statusText: m.statusText, headers }))
          return
        }
        const stream = new ReadableStream<Uint8Array>({
          start(controller) { p.controller = controller },
          cancel() {
            pending.delete(m.id)
            vsapi.postMessage({ __harnex: true, kind: 'abort', id: m.id })
          },
        })
        p.resolve(new Response(stream, { status: m.status, statusText: m.statusText, headers }))
        break
      }
      case 'chunk':
        try { p.controller?.enqueue(b64ToBytes(m.b64)) } catch { /* stream already closed/cancelled */ }
        break
      case 'end':
        pending.delete(m.id)
        try { p.controller?.close() } catch { /* already closed */ }
        break
      case 'fail': {
        pending.delete(m.id)
        const err = new TypeError(m.message || 'harnex ide transport failure')
        if (!p.headReceived) p.reject(err)
        else try { p.controller?.error(err) } catch { /* already errored */ }
        break
      }
    }
  })

  window.fetch = function ideFetch(input: RequestInfo | URL, init?: RequestInit): Promise<Response> {
    const url = typeof input === 'string' ? input : input instanceof URL ? input.pathname + input.search : null
    if (url === null || !(url.startsWith('/api') || url.startsWith('/actuator'))) {
      return realFetch(input as RequestInfo, init)
    }
    const id = nextId++
    const method = (init?.method || 'GET').toUpperCase()
    const headers = headersToRecord(init?.headers)

    let body: string | undefined
    let bodyB64: string | undefined
    const raw = init?.body
    if (raw != null) {
      if (typeof raw === 'string') body = raw
      else if (raw instanceof URLSearchParams) {
        body = raw.toString()
        if (!headers['content-type'] && !headers['Content-Type']) {
          headers['content-type'] = 'application/x-www-form-urlencoded;charset=UTF-8'
        }
      } else if (raw instanceof ArrayBuffer) bodyB64 = bytesToB64(new Uint8Array(raw))
      else if (ArrayBuffer.isView(raw)) bodyB64 = bytesToB64(new Uint8Array(raw.buffer, raw.byteOffset, raw.byteLength))
      else {
        // FormData / Blob / ReadableStream are unsupported by this legacy transport.
        return Promise.reject(new TypeError('harnex ide transport: unsupported request body type'))
      }
    }

    return new Promise<Response>((resolve, reject) => {
      const p: PendingReq = { resolve, reject, controller: null, headReceived: false }
      pending.set(id, p)

      const signal = init?.signal
      if (signal) {
        if (signal.aborted) {
          pending.delete(id)
          reject(new DOMException('The operation was aborted.', 'AbortError'))
          return
        }
        signal.addEventListener('abort', () => {
          if (!pending.has(id) && p.controller == null) return
          pending.delete(id)
          vsapi.postMessage({ __harnex: true, kind: 'abort', id })
          const err = new DOMException('The operation was aborted.', 'AbortError')
          if (!p.headReceived) reject(err)
          else try { p.controller?.error(err) } catch { /* already closed */ }
        }, { once: true })
      }

      vsapi.postMessage({ __harnex: true, kind: 'http', id, url, method, headers, body, bodyB64 })
    })
  }
}
