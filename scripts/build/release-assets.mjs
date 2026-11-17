#!/usr/bin/env node
import { createHash } from 'node:crypto';
import { spawnSync } from 'node:child_process';
import { copyFileSync, existsSync, mkdirSync, readFileSync, readdirSync, statSync, writeFileSync } from 'node:fs';
import { basename, dirname, join, relative, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { tarExecutable } from '../../bin/lib/archive-tools.mjs';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
export const TARGETS = ['win32-x64', 'darwin-arm64', 'darwin-x64', 'linux-x64', 'linux-arm64'];
export const DESKTOP_FORMATS = { win32: ['.exe'], darwin: ['.dmg'], linux: ['.deb', '.rpm', '.AppImage'] };
const sha = bytes => createHash('sha256').update(bytes).digest('hex');
const json = file => JSON.parse(readFileSync(file, 'utf8'));

function run(command, args, cwd = ROOT) {
  const quote = value => "'" + String(value).replaceAll("'", "''") + "'";
  const result = process.platform === 'win32' && command === 'npm'
    ? spawnSync('powershell.exe', ['-NoProfile', '-NonInteractive', '-Command',
      `& npm.cmd ${args.map(quote).join(' ')}; exit $LASTEXITCODE`], { cwd, stdio: 'inherit', windowsHide: true })
    : spawnSync(command, args, { cwd, stdio: 'inherit', windowsHide: true });
  if (result.status !== 0) throw new Error(`${command} failed with exit ${result.status}`);
}

function archive(directory, destination, windows) {
  run(tarExecutable(), [windows ? '-acf' : '-czf', destination, '-C', dirname(directory), basename(directory)]);
}

function stage(target, version, commit, requestedOutput) {
  if (!TARGETS.includes(target)) throw new Error(`Unsupported release target: ${target}`);
  if (target !== `${process.platform}-${process.arch}`) throw new Error('Release packaging must run on the native target');
  const output = resolve(requestedOutput || join(ROOT, 'dist', 'release', version, target));
  const scope = relative(join(ROOT, 'dist', 'release'), output);
  if (!scope || scope.startsWith('..') || resolve(ROOT, 'dist', 'release', scope) !== output) throw new Error('Output must be under dist/release/');
  if (existsSync(output) && readdirSync(output).length) throw new Error(`Output is not empty: ${output}; use a fresh --output directory`);
  const offline = join(ROOT, 'dist', 'offline', `${version}-${target}`);
  const runtime = join(offline, 'portable', 'runtime');
  run(process.execPath, [join(ROOT, 'scripts/build/runtime-bundle.mjs'), '--verify', runtime, '--target', target]);
  mkdirSync(output, { recursive: true });
  const entries = [];
  function add(file, kind, name = basename(file)) {
    const destination = join(output, name);
    if (resolve(file) !== destination) copyFileSync(file, destination);
    entries.push({ name, kind, bytes: statSync(destination).size, sha256: sha(readFileSync(destination)) });
  }
  const formats = DESKTOP_FORMATS[target.split('-')[0]];
  const desktop = readdirSync(join(offline, 'desktop'));
  for (const extension of formats) {
    const matches = desktop.filter(name => name.endsWith(extension));
    if (matches.length !== 1) throw new Error(`Expected exactly one ${extension} installer for ${target}`);
    add(join(offline, 'desktop', matches[0]), 'desktop', `Harnex_${version}_${target}${extension}`);
  }
  const vsix = readdirSync(join(offline, 'vscode')).filter(name => name.endsWith('.vsix'));
  if (vsix.length !== 2) throw new Error('Expected both UI and Runtime VSIX packages');
  for (const file of vsix) add(join(offline, 'vscode', file), 'vscode');
  const offlineArchive = join(output, `Harnex_${version}_${target}_offline.${target.startsWith('win32-') ? 'zip' : 'tar.gz'}`);
  archive(offline, offlineArchive, target.startsWith('win32-'));
  add(offlineArchive, 'offline');

  const nativePackage = join(ROOT, 'packages', `cli-${target}`);
  const nativeName = `harnex-cli-${target}-${version}.tgz`;
  run('npm', ['pack', '--ignore-scripts', '--pack-destination', output], nativePackage);
  add(join(output, nativeName), 'npm-native');

  if (target === 'linux-x64') {
    run('npm', ['pack', '--ignore-scripts', '--pack-destination', output]);
    add(join(output, `harnex-${version}.tgz`), 'npm');
    add(join(runtime, 'harnex.jar'), 'backend', `harnex-${version}.jar`);
    const webArchive = join(output, `Harnex_${version}_webui.tar.gz`);
    archive(join(ROOT, 'frontend/dist'), webArchive, false);
    add(webArchive, 'webui');
  }
  const runtimeManifest = json(join(runtime, 'runtime-bundle.json'));
  writeFileSync(join(output, `manifest-${target}.json`), JSON.stringify({
    schemaVersion: 1, version, target, commit,
    signing: target.startsWith('darwin-') ? runtimeManifest.codeSigning?.mode || 'unknown' : 'unsigned',
    files: entries,
  }, null, 2) + '\n');
  console.log(`Release assets staged: ${output}`);
}

// Validate the complete download before turning a draft into a public release.
export function verifyRelease(directory, version, commit) {
  const names = new Set();
  const checksums = [];
  for (const target of TARGETS) {
    const manifestName = `manifest-${target}.json`;
    const manifest = json(join(directory, manifestName));
    if (manifest.schemaVersion !== 1 || manifest.version !== version || manifest.target !== target || manifest.commit !== commit) {
      throw new Error(`Release source/version mismatch: ${manifestName}`);
    }
    if (!Array.isArray(manifest.files)) throw new Error(`Invalid file inventory: ${manifestName}`);
    for (const [kind, count] of [['offline', 1], ['vscode', 2], ['npm-native', 1]]) {
      if (manifest.files.filter(file => file.kind === kind).length !== count) throw new Error(`Missing ${kind} assets for ${target}`);
    }
    const installers = manifest.files.filter(file => file.kind === 'desktop');
    const formats = DESKTOP_FORMATS[target.split('-')[0]];
    if (installers.length !== formats.length || formats.some(extension => installers.filter(file => file.name.endsWith(extension)).length !== 1)) {
      throw new Error(`Missing desktop formats for ${target}`);
    }
    if (target === 'linux-x64') {
      for (const kind of ['npm', 'backend', 'webui']) {
        if (manifest.files.filter(file => file.kind === kind).length !== 1) throw new Error(`Missing shared ${kind} asset`);
      }
    }
    for (const file of manifest.files) {
      if (!/^[A-Za-z0-9][A-Za-z0-9._-]*$/.test(file.name) || names.has(file.name)) throw new Error(`Unsafe or duplicate asset name: ${file.name}`);
      names.add(file.name);
      const bytes = readFileSync(join(directory, file.name));
      if (bytes.length !== file.bytes || sha(bytes) !== file.sha256) throw new Error(`Asset checksum mismatch: ${file.name}`);
      checksums.push(`${file.sha256}  ${file.name}`);
    }
    names.add(manifestName);
    checksums.push(`${sha(readFileSync(join(directory, manifestName)))}  ${manifestName}`);
  }
  for (const name of readdirSync(directory)) {
    if (!names.has(name) && name !== 'SHA256SUMS') throw new Error(`Unexpected release asset: ${name}`);
  }
  writeFileSync(join(directory, 'SHA256SUMS'), checksums.sort().join('\n') + '\n');
  return { targets: TARGETS.length, assets: names.size, version, commit };
}

function main() {
  const args = process.argv.slice(2);
  const value = name => { const index = args.indexOf(name); return index >= 0 ? args[index + 1] : undefined; };
  const version = json(join(ROOT, 'package.json')).version;
  if (!/^\d+\.\d+\.\d+(?:-[A-Za-z0-9.-]+)?$/.test(version)) throw new Error('Invalid release version');
  const probe = spawnSync('git', ['rev-parse', 'HEAD'], { cwd: ROOT, encoding: 'utf8', windowsHide: true });
  const commit = process.env.GITHUB_SHA || probe.stdout?.trim();
  if (!/^[a-f0-9]{40}$/.test(commit || '')) throw new Error('Cannot resolve the release commit');
  if (value('--verify')) console.log(verifyRelease(resolve(value('--verify')), version, commit));
  else stage(value('--target') || `${process.platform}-${process.arch}`, version, commit, value('--output'));
}
if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  try { main(); } catch (error) { console.error(`Release packaging failed: ${error.message}`); process.exitCode = 1; }
}
