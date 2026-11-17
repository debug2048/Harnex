import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// Legacy Vue IDE build with a postMessage transport bootstrap and relative asset URLs.
// The shipped VS Code extension uses extension/src/webview/main.ts instead
// (docs/03 §3.32.3); extension/esbuild.mjs does not consume this output.
export default defineConfig({
  plugins: [vue()],
  base: './',
  build: {
    outDir: 'dist-ide',
    sourcemap: false,
    target: 'es2020',
    rollupOptions: { input: 'ide.html' },
  },
})
