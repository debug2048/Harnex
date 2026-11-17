declare module '*.vue' {
  import type { DefineComponent } from 'vue'
  const c: DefineComponent<{}, {}, any>
  export default c
}

// Lightweight router escape hatch surfaced for non-Vue contexts (e.g. the api/client.ts auth
// 401 handler that has no access to the Vue Router instance via composition). Typed against
// `globalThis` so it works in both browser and (hypothetical) SSR contexts without `any` casts.
declare global {
  // eslint-disable-next-line no-var
}

export {}
