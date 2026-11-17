import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { mkdirSync, mkdtempSync, readdirSync, writeFileSync } from 'node:fs';
import { join, resolve } from 'node:path';

assert.equal(process.platform, 'linux');
const source = process.cwd();
const bundleDirectory = join(source, 'src-tauri/target/release/bundle/appimage');
const images = readdirSync(bundleDirectory).filter(name => name.endsWith('.AppImage'));
assert.equal(images.length, 1);
const artifacts = join(source, '.artifacts/appimage-check');
mkdirSync(artifacts, { recursive: true });
const destination = mkdtempSync(join(artifacts, 'extracted-'));
const extract = spawnSync(join(bundleDirectory, images[0]), ['--appimage-extract'], {
  cwd: destination, encoding: 'utf8', timeout: 300000, maxBuffer: 32 * 1024 * 1024,
});
writeFileSync(join(artifacts, 'extraction.log'), `${extract.stdout || ''}\n${extract.stderr || ''}`);
assert.equal(extract.status, 0, extract.error || extract.stderr);
const runtime = join(destination, 'squashfs-root/usr/lib/Harnex/resources');
for (const args of [
  ['scripts/build/runtime-bundle.mjs', '--verify', runtime, '--target', `linux-${process.arch}`],
]) {
  const result = spawnSync(process.execPath, args, {
    cwd: source, stdio: 'inherit', timeout: 600000,
    env: { ...process.env, LD_LIBRARY_PATH: '' },
  });
  assert.equal(result.status, 0, result.error?.message || 'Extracted AppImage Runtime verification failed');
}
writeFileSync(join(artifacts, 'result.json'), JSON.stringify({ target: `linux-${process.arch}`,
  appimage: images[0], runtimeManifestVerified: true }, null, 2) + '\n');
console.log('AppImage Runtime manifest and bytes verified after extraction.');
