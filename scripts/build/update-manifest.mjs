#!/usr/bin/env node
import { existsSync, readFileSync, readdirSync, statSync, writeFileSync } from 'node:fs'
import { basename, join, resolve } from 'node:path'

const directory = resolve(option('--artifacts') || '')
const output = resolve(option('--output') || join(directory, 'latest.json'))
const version = option('--version')
const baseUrl = String(option('--base-url') || '').replace(/\/$/, '')
if (!directory || !existsSync(directory)) fail('--artifacts must name an existing directory')
if (!/^\d+\.\d+\.\d+(?:[-+][0-9A-Za-z.-]+)?$/.test(version || '')) fail('--version must be SemVer')
if (!baseUrl.startsWith('https://')) fail('--base-url must use HTTPS')

const platforms = {}
for (const archive of walk(directory).filter(file => file.endsWith('.app.tar.gz'))) {
  const name = basename(archive)
  const target = name.includes('darwin-arm64') ? 'darwin-aarch64'
    : name.includes('darwin-x64') ? 'darwin-x86_64' : ''
  if (!target) continue
  const signatureFile = `${archive}.sig`
  if (!existsSync(signatureFile)) fail(`missing updater signature: ${signatureFile}`)
  platforms[target] = {
    signature: readFileSync(signatureFile, 'utf8').trim(),
    url: `${baseUrl}/${encodeURIComponent(name)}`,
  }
}
if (!platforms['darwin-aarch64'] || !platforms['darwin-x86_64']) {
  fail('both darwin-aarch64 and darwin-x86_64 updater artifacts are required')
}
const manifest = {
  version,
  notes: option('--notes') || `Harnex ${version}`,
  pub_date: new Date().toISOString(),
  platforms,
}
writeFileSync(output, `${JSON.stringify(manifest, null, 2)}\n`, 'utf8')
console.log(`Updater manifest ready: ${output}`)

function walk(root) {
  const files = []
  for (const entry of readdirSync(root, { withFileTypes: true })) {
    const file = join(root, entry.name)
    if (entry.isDirectory()) files.push(...walk(file))
    else if (entry.isFile() && statSync(file).size > 0) files.push(file)
  }
  return files
}
function option(name) {
  const index = process.argv.indexOf(name)
  return index >= 0 ? process.argv[index + 1] : undefined
}
function fail(message) { console.error(`Update manifest error: ${message}`); process.exit(1) }
