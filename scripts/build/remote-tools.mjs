import { createHash, randomUUID } from 'node:crypto'
import { spawnSync } from 'node:child_process'
import { chmodSync, copyFileSync, existsSync, mkdirSync, readFileSync, renameSync, rmSync, writeFileSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { tarExecutable } from '../../bin/lib/archive-tools.mjs'

const root = resolve(dirname(fileURLToPath(import.meta.url)), '../..')
const manifest = JSON.parse(readFileSync(join(root, 'bin/assets/remote-tools.json'), 'utf8'))
const hash = bytes => createHash('sha256').update(bytes).digest('hex')
const verified = (path, expected) => existsSync(path) && hash(readFileSync(path)) === expected

// Ship both supported SSH architectures with every host bundle. Native-only installs use
// the same pinned manifest to obtain a missing component automatically on first open.
export async function stageRemoteTools(output) {
  const cache = join(root, 'dist/build-tools/remote-tools')
  mkdirSync(cache, {recursive:true})
  for (const [target, artifact] of Object.entries(manifest.targets)) {
    const archive = join(cache, `${manifest.version}-${target}.tar.gz`)
    if (!verified(archive, artifact.archiveSha256)) {
      const response = await fetch(artifact.url, {signal:AbortSignal.timeout(120_000)})
      if (!response.ok) throw new Error(`Remote search tool download failed: HTTP ${response.status}`)
      const bytes = Buffer.from(await response.arrayBuffer())
      if (bytes.length > 32 * 1024 * 1024 || hash(bytes) !== artifact.archiveSha256) throw new Error(`Remote search tool checksum mismatch: ${target}`)
      const temporary = `${archive}.${randomUUID()}.tmp`
      try {
        writeFileSync(temporary, bytes, {flag:'wx'})
        renameSync(temporary, archive)
      } finally { rmSync(temporary, {force:true}) }
    }
    const extracted = spawnSync(tarExecutable(), ['-xzOf', archive, artifact.entry], {maxBuffer:16 * 1024 * 1024, windowsHide:true})
    if (extracted.status !== 0 || hash(extracted.stdout) !== artifact.binarySha256) throw new Error(`Remote search tool extraction failed: ${target}`)
    const destination = join(output, 'remote-tools', target)
    mkdirSync(destination, {recursive:true})
    writeFileSync(join(destination, 'rg'), extracted.stdout)
    chmodSync(join(destination, 'rg'), 0o755)
    copyFileSync(join(root, 'docs/licenses/ripgrep-MIT.txt'), join(destination, 'LICENSE.txt'))
  }
}
