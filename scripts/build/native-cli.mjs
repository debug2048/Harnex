#!/usr/bin/env node
import { copyFileSync, existsSync, mkdirSync, readFileSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { spawnSync } from 'node:child_process'

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '../..')
const target = option('--target') || platformTarget()
const hostTarget = platformTarget()
const executable = target.startsWith('win32-') ? 'harnex.exe' : 'harnex'
const rustTarget = rustTargetTriple(target)
const buildArgs = ['build', '--release', '--locked', '--manifest-path', join(ROOT, 'src-cli', 'Cargo.toml')]
if (target !== hostTarget) buildArgs.push('--target', rustTarget)
const result = spawnSync('cargo', buildArgs, { cwd: ROOT, stdio: 'inherit' })
if (result.status !== 0) fail(`cargo build failed with exit ${result.status}`)
const built = target === hostTarget
  ? join(ROOT, 'src-cli', 'target', 'release', executable)
  : join(ROOT, 'src-cli', 'target', rustTarget, 'release', executable)
if (!existsSync(built)) fail(`native CLI output missing: ${built}`)
const output = join(ROOT, 'dist', 'native', target, executable)
mkdirSync(dirname(output), { recursive: true })
copyFileSync(built, output)
if (target.startsWith('darwin-')) signDarwinBinary(output)
if (target === hostTarget) {
  const probe = spawnSync(output, ['version'], { encoding: 'utf8' })
  if (probe.status !== 0 || !String(probe.stdout).includes('native')) fail('native CLI version probe failed')
  const capabilities = spawnSync(output, ['remote', 'capabilities'], { encoding:'utf8', windowsHide:true })
  let protocol; try { protocol = JSON.parse(capabilities.stdout) } catch {}
  if (capabilities.status !== 0 || protocol?.protocolVersion !== 1 || !protocol?.capabilities?.includes('ssh.interaction')) fail('native CLI SSH protocol probe failed')
}
stageOptionalPackage(target, output)
console.log(`Native CLI ready: ${output}`)

function platformTarget() {
  const platform = { win32: 'win32', linux: 'linux', darwin: 'darwin' }[process.platform]
  const arch = { x64: 'x64', arm64: 'arm64', arm: 'armhf' }[process.arch]
  if (!platform || !arch) fail(`unsupported platform: ${process.platform}-${process.arch}`)
  return `${platform}-${arch}`
}
function rustTargetTriple(target) {
  const triples = {
    'win32-x64': 'x86_64-pc-windows-msvc',
    'win32-arm64': 'aarch64-pc-windows-msvc',
    'linux-x64': 'x86_64-unknown-linux-gnu',
    'linux-arm64': 'aarch64-unknown-linux-gnu',
    'darwin-x64': 'x86_64-apple-darwin',
    'darwin-arm64': 'aarch64-apple-darwin',
  }
  const triple = triples[target]
  if (!triple) fail(`unsupported native CLI target: ${target}`)
  return triple
}
function signDarwinBinary(file) {
  if (process.platform !== 'darwin') fail('Darwin native CLI signing must run on macOS')
  const identity = process.env.APPLE_SIGNING_IDENTITY || '-'
  const args = identity === '-'
    ? ['--force', '--sign', '-', file]
    : ['--force', '--options', 'runtime', '--timestamp', '--sign', identity, file]
  const signed = spawnSync('/usr/bin/codesign', args, { stdio: 'inherit' })
  if (signed.status !== 0) fail(`codesign failed for ${file}`)
}
function stageOptionalPackage(target, binary) {
  const names = {
    'darwin-arm64': 'cli-darwin-arm64',
    'darwin-x64': 'cli-darwin-x64',
    'win32-x64': 'cli-win32-x64',
    'linux-x64': 'cli-linux-x64',
    'linux-arm64': 'cli-linux-arm64',
  }
  const name = names[target]
  if (!name) return
  const packageRoot = join(ROOT, 'packages', name)
  const metadata = JSON.parse(readFileSync(join(packageRoot, 'package.json'), 'utf8'))
  const rootVersion = JSON.parse(readFileSync(join(ROOT, 'package.json'), 'utf8')).version
  if (metadata.version !== rootVersion) fail(`${metadata.name} version ${metadata.version} != ${rootVersion}`)
  const destination = join(packageRoot, 'bin', target.startsWith('win32-') ? 'harnex.exe' : 'harnex')
  mkdirSync(dirname(destination), { recursive: true })
  copyFileSync(binary, destination)
  copyFileSync(join(ROOT, 'LICENSE'), join(packageRoot, 'LICENSE'))
  console.log(`Staged optional npm CLI package: ${metadata.name}`)
}
function option(name) {
  const index = process.argv.indexOf(name)
  return index >= 0 && process.argv[index + 1] ? process.argv[index + 1] : undefined
}
function fail(message) { console.error(`Native CLI error: ${message}`); process.exit(1) }
