import esbuild from 'esbuild'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const root = dirname(fileURLToPath(import.meta.url))
const watch = process.argv.includes('--watch')

// Two bundles: the extension host (node) and the chat webview (browser, docs/03 §3.32.3).
const targets = [
  {
    entryPoints: [join(root, 'src', 'extension.ts')],
    outfile: join(root, 'dist', 'extension.js'),
    external: ['vscode'],
    platform: 'node',
    target: 'node18',
    format: 'cjs',
  },
  {
    entryPoints: [join(root, 'src', 'webview', 'main.ts')],
    outfile: join(root, 'media', 'chat.js'),
    platform: 'browser',
    target: 'es2020',
    format: 'iife',
  },
]

const ctxs = await Promise.all(targets.map(t => esbuild.context({
  bundle: true,
  sourcemap: true,
  logLevel: 'info',
  ...t,
})))

if (watch) {
  await Promise.all(ctxs.map(c => c.watch()))
} else {
  await Promise.all(ctxs.map(c => c.rebuild()))
  await Promise.all(ctxs.map(c => c.dispose()))
}
