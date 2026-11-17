import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

const backendTarget = process.env.VITE_BACKEND_TARGET
  || process.env.E2E_BACKEND_URL
  || 'http://localhost:8088'

export default defineConfig({
  plugins: [vue()],
  server: {
    port: 18081,
    strictPort: true,
    open: process.env.VITE_OPEN_BROWSER !== '0',
    proxy: {
      // Preserve the browser's Host (including custom ports) for same-origin checks upstream.
      '/api':     { target: backendTarget, changeOrigin: false },
      '/actuator':{ target: backendTarget, changeOrigin: false }
    }
  },
  build: { outDir: 'dist', sourcemap: false, target: 'es2020' }
})
