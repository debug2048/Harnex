#!/usr/bin/env node
// Produce a target-specific, network-independent distribution set. VS Code is
// split into a small UI extension and a platform Runtime companion; the same
// immutable Runtime Bundle is also included as a portable native CLI layout.

import { spawnSync } from 'node:child_process'
import { createHash } from 'node:crypto'
import {
  chmodSync, copyFileSync, cpSync, existsSync, mkdirSync, readFileSync, readdirSync, renameSync, rmSync, statSync, writeFileSync,
} from 'node:fs'
import { basename, dirname, join, relative, resolve, sep } from 'node:path'
import { fileURLToPath } from 'node:url'

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '../..')
const target = option('--target') || platformTarget()
const rootPackage = json(join(ROOT, 'package.json'))
const runtime = resolve(option('--runtime') || join(ROOT, 'dist', 'runtime-bundles', `${rootPackage.version}-${target}`))
const output = resolve(option('--output') || join(ROOT, 'dist', 'offline', `${rootPackage.version}-${target}`))
const vsce = join(ROOT, 'extension', 'node_modules', '@vscode', 'vsce', 'vsce')

if (!existsSync(vsce)) fail('VS Code dependencies are missing; run npm ci in extension/')
assertOutputScope(output)
if (!existsSync(join(runtime, 'runtime-bundle.json'))) fail(`Runtime Bundle is missing: ${runtime}`)
run(process.execPath, [join(ROOT, 'scripts', 'build', 'runtime-bundle.mjs'), '--verify', runtime, '--target', target])

// Stage the exact verified bytes for the companion. There is no second JRE or jar build.
const companionRuntime = join(ROOT, 'extension-runtime', 'runtime')
rmSync(companionRuntime, { recursive: true, force: true })
cpSync(runtime, companionRuntime, { recursive: true })
copyFileSync(join(ROOT, 'LICENSE'), join(ROOT, 'extension-runtime', 'LICENSE'))
copyFileSync(join(ROOT, 'LICENSE'), join(ROOT, 'extension', 'LICENSE'))
run(process.execPath, [join(ROOT, 'extension', 'esbuild.mjs')], join(ROOT, 'extension'))

const staging = `${output}.staging-${process.pid}`
rmSync(staging, { recursive: true, force: true })
mkdirSync(join(staging, 'vscode'), { recursive: true })
mkdirSync(join(staging, 'portable'), { recursive: true })

const uiPackage = json(join(ROOT, 'extension', 'package.json'))
const companionPackage = json(join(ROOT, 'extension-runtime', 'package.json'))
const uiVsix = join(staging, 'vscode', `${uiPackage.name}-${target}-${uiPackage.version}.vsix`)
const runtimeVsix = join(staging, 'vscode', `${companionPackage.name}-${target}-${companionPackage.version}.vsix`)
run(process.execPath, [vsce, 'package', '--target', target, '--allow-missing-repository', '--out', uiVsix], join(ROOT, 'extension'))
run(process.execPath, [vsce, 'package', '--target', target, '--allow-missing-repository', '--out', runtimeVsix], join(ROOT, 'extension-runtime'))
cpSync(runtime, join(staging, 'portable', 'runtime'), { recursive: true })

const installers = newestDesktopArtifacts()
if (installers.length) {
  mkdirSync(join(staging, 'desktop'), { recursive: true })
  for (const installer of installers) copyFileSync(installer, join(staging, 'desktop', basename(installer)))
}

if (target.startsWith('win32-')) {
  writeFileSync(join(staging, 'install-vscode.ps1'), vscodeInstallerPowerShell(runtimeVsix, uiVsix), 'utf8')
} else {
  const installerScript = join(staging, 'install-vscode.sh')
  writeFileSync(installerScript, vscodeInstallerPosix(runtimeVsix, uiVsix), { encoding: 'utf8', mode: 0o755 })
  chmodSync(installerScript, 0o755)
  for (const file of [join(staging, 'portable', 'runtime', 'bin', 'harnex'),
    join(staging, 'portable', 'runtime', 'jre', 'bin', 'java')]) {
    if (existsSync(file)) chmodSync(file, statSync(file).mode | 0o500)
  }
}
writeFileSync(join(staging, 'README.md'), readme(installers.length > 0), 'utf8')
const sums = files(staging)
  .map(file => `${sha(readFileSync(file))}  ${relative(staging, file).split(sep).join('/')}`)
  .join('\n')
writeFileSync(join(staging, 'SHA256SUMS'), `${sums}\n`, 'utf8')
rmSync(output, { recursive: true, force: true })
mkdirSync(dirname(output), { recursive: true })
try { rename(staging, output) } catch (error) { fail(`cannot publish offline kit: ${error.message}`) }
console.log(`Offline kit ready: ${output} (${size(output).toFixed(1)} MiB)`)

function vscodeInstallerPowerShell(runtimeVsix, uiVsix) {
  const runtimeName = relative(join(staging, 'vscode'), runtimeVsix).replaceAll("'", "''")
  const uiName = relative(join(staging, 'vscode'), uiVsix).replaceAll("'", "''")
  return `$ErrorActionPreference = 'Stop'\n`+
    `$code = Get-Command code -ErrorAction Stop\n`+
    `$root = Split-Path -Parent $MyInvocation.MyCommand.Path\n`+
    `& $code.Source --install-extension (Join-Path $root 'vscode/${runtimeName}') --force\n`+
    `if ($LASTEXITCODE -ne 0) { throw 'Runtime companion installation failed' }\n`+
    `& $code.Source --install-extension (Join-Path $root 'vscode/${uiName}') --force\n`+
    `if ($LASTEXITCODE -ne 0) { throw 'Harnex extension installation failed' }\n`+
    `Write-Host 'Harnex VS Code extensions installed. Reload VS Code.'\n`
}

function vscodeInstallerPosix(runtimeVsix, uiVsix) {
  const runtimeName = relative(join(staging, 'vscode'), runtimeVsix).split(sep).join('/').replaceAll("'", "'\\''")
  const uiName = relative(join(staging, 'vscode'), uiVsix).split(sep).join('/').replaceAll("'", "'\\''")
  return `#!/usr/bin/env sh\nset -eu\n`+
    `command -v code >/dev/null 2>&1 || { echo 'VS Code command "code" is not on PATH' >&2; exit 1; }\n`+
    `ROOT=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)\n`+
    `code --install-extension "$ROOT/vscode/${runtimeName}" --force\n`+
    `code --install-extension "$ROOT/vscode/${uiName}" --force\n`+
    `echo 'Harnex VS Code extensions installed. Reload VS Code.'\n`
}

function readme(installer) {
  const windows = target.startsWith('win32-')
  const mac = target.startsWith('darwin-')
  const vscodeCommand = windows
    ? '`powershell -ExecutionPolicy Bypass -File .\\install-vscode.ps1`'
    : '`./install-vscode.sh`'
  const cli = windows ? '.\\portable\\runtime\\bin\\harnex.exe' : './portable/runtime/bin/harnex'
  return `# Harnex ${rootPackage.version} offline kit (${target})\n\n`+
    `All artifacts are target-specific and covered by \`SHA256SUMS\`. No JDK or Node.js is required on the target machine.\n\n`+
    `## VS Code\n\nRun ${vscodeCommand}. `+
    `The Runtime companion is installed first, then the UI extension. New integrated terminals receive `+
    `the exact Domain manifest and the bundled native \`harnex\` command on PATH.\n\n`+
    `## Portable CLI\n\nRun \`${cli} doctor\` or \`${cli} cli -p "..."\`. `+
    `The adjacent verified Runtime Bundle is used automatically.\n\n`+
    (installer
      ? `## Desktop\n\nDesktop artifacts (${mac ? 'DMG' : windows ? 'NSIS installer' : 'DEB, RPM and AppImage'}) are in \`desktop/\`. `+
        (mac ? 'See the release notes for Developer ID signing and notarization status.\n' : '\n')
      : `## Desktop\n\nNo target desktop artifact was present when this kit was built. Run \`npm run build:desktop\`, then rebuild the kit to include it.\n`)
}

function newestDesktopArtifacts() {
  const formats = target.startsWith('darwin-') ? [['dmg', '.dmg']]
    : target.startsWith('win32-') ? [['nsis', '.exe']]
      : [['deb', '.deb'], ['rpm', '.rpm'], ['appimage', '.AppImage']]
  const runtimeBuiltAt = statSync(join(runtime, 'runtime-bundle.json')).mtimeMs
  return formats.flatMap(([kind, extension]) => {
    const dir = join(ROOT, 'src-tauri', 'target', 'release', 'bundle', kind)
    if (!existsSync(dir)) return []
    const candidate = readdirSync(dir)
      .filter(name => name.endsWith(extension) && name.includes(rootPackage.version))
      .map(name => join(dir, name))
      .sort((a, b) => statSync(b).mtimeMs - statSync(a).mtimeMs)[0]
    if (candidate && statSync(candidate).mtimeMs + 1000 >= runtimeBuiltAt) return [candidate]
    if (candidate) console.warn(`Offline kit: ${kind} installer is older than the Runtime Bundle and was omitted`)
    return []
  })
}
function files(dir) {
  const result = []
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    const file = join(dir, entry.name)
    if (entry.isDirectory()) result.push(...files(file)); else result.push(file)
  }
  return result.sort((a, b) => a < b ? -1 : a > b ? 1 : 0)
}
function size(dir) { return files(dir).reduce((total, file) => total + statSync(file).size, 0) / 1024 / 1024 }
function sha(bytes) { return createHash('sha256').update(bytes).digest('hex') }
function json(file) { return JSON.parse(readFileSync(file, 'utf8')) }
function option(name) { const i = process.argv.indexOf(name); return i >= 0 ? process.argv[i + 1] : undefined }
function assertOutputScope(directory) {
  const allowed = resolve(ROOT, 'dist', 'offline')
  const rel = relative(allowed, directory)
  if (rel === '' || rel === '.' || rel.startsWith('..') || rel.split(/[\\/]/).includes('..')) {
    fail(`output must be a child of ${allowed}`)
  }
}
function rename(from, to) {
  assertOutputScope(from)
  assertOutputScope(to)
  // Windows scanners can briefly retain handles after VSIX creation or removal of the old kit.
  const pause = new Int32Array(new SharedArrayBuffer(4))
  for (let attempt = 0; ; attempt++) {
    try {
      return renameSync(from, to)
    } catch (error) {
      if (process.platform !== 'win32' || !['EPERM', 'EBUSY', 'EACCES'].includes(error.code) || attempt >= 29) throw error
      Atomics.wait(pause, 0, 0, 200)
    }
  }
}
function run(command, args, cwd = ROOT) {
  const result = spawnSync(command, args, { cwd, stdio: 'inherit' })
  if (result.status !== 0) fail(`${command} failed with exit ${result.status}`)
}
function platformTarget() {
  const p = { win32: 'win32', linux: 'linux', darwin: 'darwin' }[process.platform]
  const a = { x64: 'x64', arm64: 'arm64', arm: 'armhf' }[process.arch]
  if (!p || !a) fail(`unsupported platform: ${process.platform}-${process.arch}`)
  return `${p}-${a}`
}
function fail(message) { console.error(`Offline kit error: ${message}`); process.exit(1) }
