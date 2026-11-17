#!/usr/bin/env node
// Build/verify the canonical Harnex Runtime Bundle shared by VS Code companion,
// Tauri/NSIS and the native CLI. The bundle is immutable application code only;
// Runtime Domain state is always written elsewhere by the owning host.

import { spawnSync } from 'node:child_process'
import { createHash } from 'node:crypto'
import {
  chmodSync, copyFileSync, cpSync, existsSync, mkdirSync, readFileSync, readdirSync, rmSync, statSync, writeFileSync,
} from 'node:fs'
import { delimiter, dirname, join, relative, resolve, sep } from 'node:path'
import { fileURLToPath } from 'node:url'
import { stageRemoteTools } from './remote-tools.mjs'
import { prepareWindowsJava } from './windows-java.mjs'
import { copyJreImage } from './jre-files.mjs'
import { prepareJreStartup } from './jre-startup.mjs'

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '../..')
const JRE_ENTITLEMENTS = join(ROOT, 'src-tauri', 'JreEntitlements.plist')
const argv = process.argv.slice(2)
const has = flag => argv.includes(flag)
const value = (flag, fallback) => {
  const index = argv.indexOf(flag)
  return index >= 0 && argv[index + 1] ? argv[index + 1] : fallback
}
const pkg = JSON.parse(readFileSync(join(ROOT, 'package.json'), 'utf8'))
const target = value('--target', platformTarget())
const output = resolve(value('--output', join(ROOT, 'dist', 'runtime-bundles', `${pkg.version}-${target}`)))
const verifyOnly = value('--verify', '')
const modules = value('--jre-modules', '').split(',').map(item => item.trim()).filter(Boolean)

const MODULES = [
  'java.base', 'java.compiler', 'java.desktop', 'java.instrument', 'java.logging', 'java.management',
  'java.naming', 'java.net.http', 'java.prefs', 'java.rmi', 'java.scripting', 'java.security.jgss',
  'java.security.sasl', 'java.sql', 'java.sql.rowset', 'java.transaction.xa', 'java.xml',
  'java.xml.crypto', 'jdk.crypto.cryptoki', 'jdk.crypto.ec', 'jdk.management', 'jdk.net',
  'jdk.unsupported', 'jdk.zipfs',
]

if (has('--help') || has('-h')) {
  console.log(`Usage:
  node scripts/build/runtime-bundle.mjs [--target win32-x64] [--jar file] [--jre-dir dir]
      [--output dir] [--jre-modules java.base,...] [--with-native-cli|--native-cli file]
      [--stage-tauri] [--stage-companion] [--rg-bin /path/to/rg] [--skip-startup-cache]
      [--macos-sign-identity "Developer ID Application: ..."|--macos-pre-signed]
  node scripts/build/runtime-bundle.mjs --verify <bundle-dir> [--target win32-x64]`)
  process.exit(0)
}

if (verifyOnly) {
  verifyBundle(resolve(verifyOnly), target)
  process.exit(0)
}

const hostTarget = platformTarget()
const rgName = target.startsWith('win32-') ? 'rg.exe' : 'rg'
const rgOverride = value('--rg-bin', process.env.HARNEX_RG_PATH || '')
if (target !== hostTarget && !rgOverride) {
  fail(`cross-target build ${hostTarget} -> ${target} requires --rg-bin for the target platform`)
}
const rgBinary = rgOverride ? resolve(rgOverride) : (process.env.PATH || '').split(delimiter)
  .filter(Boolean).map(directory => resolve(directory, rgName)).find(file => existsSync(file) && statSync(file).isFile())
if (!rgBinary || !existsSync(rgBinary)) fail('ripgrep not found; install rg or pass --rg-bin /path/to/rg')
if (target === hostTarget) {
  const probe = spawnSync(rgBinary, ['--version'], { encoding: 'utf8' })
  if (probe.status !== 0 || !String(probe.stdout).startsWith('ripgrep ')) fail(`invalid ripgrep executable: ${rgBinary}`)
}
if (target !== hostTarget && !value('--jre-dir', '')) {
  fail(`cross-target build ${hostTarget} -> ${target} requires an explicit --jre-dir`)
}
if (target !== hostTarget && has('--with-native-cli') && !value('--native-cli', '')) {
  fail(`cross-target build ${hostTarget} -> ${target} requires an explicit --native-cli`)
}

const jar = resolve(value('--jar', findJar() || ''))
if (!jar || !existsSync(jar)) fail('backend jar not found; build backend or pass --jar')
let jre = value('--jre-dir', '')
const temporaryJre = !jre
if (jre) {
  jre = resolve(jre)
  if (!existsSync(join(jre, 'bin'))) fail(`invalid --jre-dir: ${jre}`)
} else {
  jre = join(dirname(output), `.jre-build-${process.pid}`)
  buildJre(jre)
}

assertOutputScope(output)
resetOutputDirectory(output)
cpSync(jar, join(output, 'harnex.jar'))
// jlink uses relative symlinks to share legal notices across modules on macOS/Linux.
// Materialize their contents while the source image still exists, before signing/hashing.
// The final Runtime Bundle remains link-free for every consumer's strict verifier.
copyJreImage(jre, join(output, 'jre'))
copyFileSync(join(ROOT, 'LICENSE'), join(output, 'LICENSE'))
mkdirSync(join(output, 'bin'), { recursive: true })
copyFileSync(rgBinary, join(output, 'bin', rgName))
if (!target.startsWith('win32-')) chmodSync(join(output, 'bin', rgName), 0o755)
copyFileSync(join(ROOT, 'docs', 'licenses', 'ripgrep-MIT.txt'), join(output, 'bin', 'ripgrep-LICENSE.txt'))
await stageRemoteTools(output)
if (has('--with-native-cli') || value('--native-cli', '')) {
  const executable = target.startsWith('win32-') ? 'harnex.exe' : 'harnex'
  const nativeCli = resolve(value('--native-cli', join(ROOT, 'dist', 'native', target, executable)))
  if (!existsSync(nativeCli)) fail(`native CLI not found: ${nativeCli}; run node scripts/build/native-cli.mjs`)
  mkdirSync(join(output, 'bin'), { recursive: true })
  cpSync(nativeCli, join(output, 'bin', executable))
}
if (temporaryJre) removeTree(jre)

if (target.startsWith('darwin-')) ensureDarwinEntrypointsExecutable(output)
const javaExecutable = target.startsWith('win32-')
  ? prepareWindowsJava(join(output, 'jre')) : 'jre/bin/java'
if (!has('--skip-startup-cache')) {
  prepareJreStartup(join(output, 'jre'), target, javaExecutable.slice('jre/'.length))
}

// macOS notarization treats the bundled JRE, its dylibs and the native CLI as nested code.
// Sign those bytes BEFORE creating the immutable Runtime manifest. Signing them after hashing
// would make every consumer reject the otherwise-valid notarized application at launch.
let codeSigning
if (target.startsWith('darwin-')) {
  if (has('--macos-pre-signed')) {
    codeSigning = { platform: 'macos', mode: 'pre-signed', signedBeforeHash: true }
  } else {
    if (process.platform !== 'darwin') {
      fail('Darwin Runtime Bundles must be signed on macOS; pass --macos-pre-signed only for an already-signed input set')
    }
    const identity = value('--macos-sign-identity', process.env.APPLE_SIGNING_IDENTITY || '-')
    signMacRuntimeCode(output, identity)
    codeSigning = {
      platform: 'macos', mode: identity === '-' ? 'adhoc' : 'developer-id', signedBeforeHash: true,
    }
  }
}

const files = fileHashes(output)
const bundleDigest = digest(JSON.stringify(files))
const manifest = {
  schemaVersion: 1,
  runtimeVersion: pkg.version,
  protocolVersion: '1.0',
  target,
  javaVersion: 17,
  entrypoint: 'harnex.jar',
  javaExecutable,
  bundleDigest,
  files,
}
if (codeSigning) manifest.codeSigning = codeSigning
writeFileSync(join(output, 'runtime-bundle.json'), `${JSON.stringify(manifest, null, 2)}\n`)
writeFileSync(join(output, 'SHA256SUMS'), Object.entries(files)
  .map(([name, hash]) => `${hash}  ${name}`).join('\n') + '\n')
verifyBundle(output, target)

if (has('--stage-tauri')) stageTauri(output, join(ROOT, 'src-tauri', 'resources'))
if (has('--stage-companion')) {
  const companionRoot = join(ROOT, 'extension-runtime')
  const companion = JSON.parse(readFileSync(join(companionRoot, 'package.json'), 'utf8'))
  if (companion.version !== pkg.version) fail(`companion version ${companion.version} != Runtime ${pkg.version}`)
  stage(output, join(companionRoot, 'runtime'))
  copyFileSync(join(ROOT, 'LICENSE'), join(companionRoot, 'LICENSE'))
}
console.log(`Runtime Bundle ready: ${output} (${dirSize(output).toFixed(1)} MiB, digest=${bundleDigest.slice(0, 16)}…)`)

function buildJre(destination) {
  const jlink = jdkTool('jlink')
  if (!jlink) fail('jlink not found; install JDK 17+ or pass --jre-dir')
  removeTree(destination)
  mkdirSync(dirname(destination), { recursive: true })
  const result = spawnSync(jlink, [
    '--add-modules', (modules.length ? modules : MODULES).join(','),
    '--strip-debug', '--no-man-pages', '--no-header-files',
    // NSIS/DMG/VSIX already compress the distribution. Keeping the installed
    // modules uncompressed avoids inflating class bytes on every JVM startup.
    '--compress=0', '--output', destination,
  ], { stdio: 'inherit' })
  if (result.status !== 0) fail(`jlink failed with exit ${result.status}`)
}

function ensureDarwinEntrypointsExecutable(directory) {
  for (const file of [join(directory, 'jre', 'bin', 'java'), join(directory, 'bin', 'harnex'), join(directory, 'bin', 'rg')]) {
    if (!existsSync(file)) continue
    const mode = statSync(file).mode & 0o777
    chmodSync(file, mode | 0o500)
  }
}

function signMacRuntimeCode(directory, identity) {
  const codesign = '/usr/bin/codesign'
  const fileTool = '/usr/bin/file'
  if (!existsSync(codesign) || !existsSync(fileTool)) {
    fail('macOS Runtime signing requires /usr/bin/codesign and /usr/bin/file (install Xcode Command Line Tools)')
  }
  const macho = walk(directory).filter(file => {
    const probe = spawnSync(fileTool, ['-b', file], { encoding: 'utf8' })
    return probe.status === 0 && /Mach-O/.test(String(probe.stdout))
  })
  if (!macho.length) fail('macOS Runtime Bundle contains no Mach-O code to sign')
  if (identity !== '-' && !existsSync(JRE_ENTITLEMENTS)) {
    fail(`macOS JRE entitlements are missing: ${JRE_ENTITLEMENTS}`)
  }
  // Files are independent nested code objects; signing each before the outer .app lets the
  // application signature seal their final bytes without mutating the Runtime manifest later.
  for (const file of macho) {
    const type = spawnSync(fileTool, ['-b', file], { encoding: 'utf8' })
    const name = relative(directory, file).split(sep).join('/')
    const jreExecutable = name.startsWith('jre/') && /\bexecutable\b/.test(String(type.stdout))
    const args = identity === '-'
      ? ['--force', '--sign', '-', file]
      : ['--force', '--options', 'runtime', '--timestamp', '--generate-entitlement-der',
          ...(jreExecutable ? ['--entitlements', JRE_ENTITLEMENTS] : []), '--sign', identity, file]
    const signed = spawnSync(codesign, args, { stdio: 'inherit' })
    if (signed.status !== 0) fail(`codesign failed for Runtime code: ${file}`)
    const verified = spawnSync(codesign, ['--verify', '--strict', file], { stdio: 'inherit' })
    if (verified.status !== 0) fail(`codesign verification failed for Runtime code: ${file}`)
  }
  console.log(`Signed ${macho.length} macOS Runtime code objects (${identity === '-' ? 'ad-hoc' : 'Developer ID'})`)
}

function verifyBundle(directory, expectedTarget) {
  const manifestFile = join(directory, 'runtime-bundle.json')
  if (!existsSync(manifestFile)) fail(`runtime-bundle.json missing from ${directory}`)
  const manifest = JSON.parse(readFileSync(manifestFile, 'utf8'))
  if (manifest.schemaVersion !== 1 || manifest.protocolVersion !== '1.0') fail('unsupported Runtime Bundle schema/protocol')
  if (typeof manifest.runtimeVersion !== 'string' || !manifest.runtimeVersion) fail('Runtime version is missing')
  if (expectedTarget && manifest.target !== expectedTarget) fail(`Runtime target ${manifest.target} != ${expectedTarget}`)
  const actual = fileHashes(directory)
  if (JSON.stringify(actual) !== JSON.stringify(manifest.files)) {
    const declared = manifest.files || {}
    const extra = Object.keys(actual).filter(name => !Object.hasOwn(declared, name))
    const missing = Object.keys(declared).filter(name => !Object.hasOwn(actual, name))
    const changed = Object.keys(actual).filter(name => Object.hasOwn(declared, name) && actual[name] !== declared[name])
    const summary = names => names.slice(0, 8).join(', ') || 'none'
    fail(`Runtime Bundle file hashes do not match manifest (extra: ${summary(extra)}; missing: ${summary(missing)}; changed: ${summary(changed)})`)
  }
  if (digest(JSON.stringify(actual)) !== manifest.bundleDigest) fail('Runtime Bundle digest does not match manifest')
  if (!safeBundlePath(manifest.javaExecutable) || !safeBundlePath(manifest.entrypoint)) {
    fail('Runtime Bundle entrypoint path is unsafe')
  }
  const java = join(directory, ...manifest.javaExecutable.split('/'))
  const jar = join(directory, ...manifest.entrypoint.split('/'))
  if (!existsSync(java) || !existsSync(jar)) fail('Runtime Bundle entrypoints are missing')
  const expectedSums = Object.entries(actual).map(([name, hash]) => `${hash}  ${name}`).join('\n') + '\n'
  if (!existsSync(join(directory, 'SHA256SUMS'))) fail('Runtime Bundle SHA256SUMS is missing')
  if (readFileSync(join(directory, 'SHA256SUMS'), 'utf8') !== expectedSums) fail('Runtime Bundle SHA256SUMS is inconsistent')
  console.log(`Verified Runtime Bundle ${manifest.runtimeVersion} ${manifest.target}`)
}

function stage(source, destination) {
  resetOutputDirectory(destination)
  for (const entry of readdirSync(source)) {
    cpSync(join(source, entry), join(destination, entry), { recursive: true })
  }
  console.log(`Staged Runtime Bundle -> ${destination}`)
}

function stageTauri(source, destination) {
  mkdirSync(destination, { recursive: true })
  for (const name of ['harnex.jar', 'jre', 'bin', 'remote-tools', 'LICENSE', 'runtime-bundle.json', 'SHA256SUMS']) {
    const target = join(destination, name)
    removeTree(target)
    cpSync(join(source, name), target, { recursive: true })
  }
  console.log(`Staged Runtime Bundle -> ${destination} (preserved resources/ui)`)
  verifyBundle(destination, target)
}

function fileHashes(directory) {
  const result = {}
  for (const file of walk(directory)) {
    const name = relative(directory, file).split(sep).join('/')
    // Tauri colocates the separately built SPA at resources/ui. Match the Rust host/CLI
    // boundary: only this top-level UI tree is excluded. walk() still rejects links in it.
    if (name === 'runtime-bundle.json' || name === 'SHA256SUMS' || name.startsWith('ui/') || name.startsWith('.jre-build/')) continue
    result[name] = digest(readFileSync(file))
  }
  // Digest order must be byte-stable across Node locales and Rust BTreeMap consumers.
  return Object.fromEntries(Object.entries(result).sort(([a], [b]) => a < b ? -1 : a > b ? 1 : 0))
}

function walk(directory) {
  const output = []
  if (!existsSync(directory)) return output
  for (const entry of readdirSync(directory, { withFileTypes: true })) {
    const file = join(directory, entry.name)
    if (entry.isDirectory()) output.push(...walk(file))
    else if (entry.isFile()) output.push(file)
    else if (entry.isSymbolicLink()) fail(`Runtime Bundle must not contain symbolic links: ${file}`)
    else fail(`Runtime Bundle contains an unsupported entry: ${file}`)
  }
  return output
}

function findJar() {
  const directory = join(ROOT, 'backend', 'target')
  if (!existsSync(directory)) return null
  return readdirSync(directory)
    .filter(name => /^harnex-.*\.jar$/.test(name) && !/-sources|-javadoc/.test(name))
    .map(name => join(directory, name))
    .sort((a, b) => statSync(b).mtimeMs - statSync(a).mtimeMs)[0] || null
}

function jdkTool(name) {
  const executable = process.platform === 'win32' ? `${name}.exe` : name
  const fromHome = process.env.JAVA_HOME && join(process.env.JAVA_HOME, 'bin', executable)
  if (fromHome && existsSync(fromHome)) return fromHome
  const probe = spawnSync(executable, ['--version'], { stdio: 'ignore' })
  return probe.status === 0 ? executable : null
}

function platformTarget() {
  const platform = { win32: 'win32', linux: 'linux', darwin: 'darwin' }[process.platform]
  const arch = { x64: 'x64', arm64: 'arm64', arm: 'armhf' }[process.arch]
  if (!platform || !arch) fail(`unsupported platform: ${process.platform}-${process.arch}`)
  return `${platform}-${arch}`
}

function safeBundlePath(value) {
  return typeof value === 'string' && value.length > 0 && !value.includes('\\') && !value.startsWith('/')
    && !/^[A-Za-z]:/.test(value) && !value.includes('\0')
    && value.split('/').every(part => part && part !== '.' && part !== '..')
}

function assertOutputScope(directory) {
  const allowed = resolve(ROOT, 'dist', 'runtime-bundles')
  const rel = relative(allowed, directory)
  if (rel.startsWith('..') || rel === '' || rel === '.') fail(`output must be a child of ${allowed}`)
}

function digest(value) { return createHash('sha256').update(value).digest('hex') }
function dirSize(directory) { return walk(directory).reduce((sum, file) => sum + statSync(file).size, 0) / 1024 / 1024 }
function removeTree(path) {
  rmSync(path, { recursive: true, force: true, maxRetries: 20, retryDelay: 250 })
}
function resetOutputDirectory(directory) {
  mkdirSync(directory, { recursive: true })
  for (const entry of readdirSync(directory, { withFileTypes: true })) {
    const target = join(directory, entry.name)
    try {
      removeTree(target)
    } catch (error) {
      // Windows refuses to remove a directory that is another terminal's cwd, even
      // after all of its contents are gone. Reconcile its children in place and keep
      // the empty directory; locked files still fail closed below.
      if ((error?.code === 'EPERM' || error?.code === 'EACCES') && entry.isDirectory()) {
        resetOutputDirectory(target)
      } else if (error?.code === 'EPERM' || error?.code === 'EACCES') {
        fail(`cannot replace an in-use Runtime Bundle file: ${target}. Close the running Harnex process or build to a different --output directory`)
      } else {
        throw error
      }
    }
  }
  const remainingFiles = walk(directory)
  if (remainingFiles.length) {
    fail(`cannot clean Runtime Bundle output; locked files remain: ${remainingFiles.slice(0, 5).join(', ')}`)
  }
}
function fail(message) { console.error(`Runtime Bundle error: ${message}`); process.exit(1) }
