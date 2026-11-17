#!/usr/bin/env node
// Rebuild the current platform's desktop, CLI/TUI and VS Code distribution together.
import { spawnSync } from 'node:child_process'
import { existsSync, readFileSync, readdirSync, statSync } from 'node:fs'
import { delimiter, dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '../..')
const args = process.argv.slice(2)
const options = { check: false, skipDesktop: false, rg: process.env.HARNEX_RG_PATH || '' }
for (let i = 0; i < args.length; i++) {
  switch (args[i]) {
    case '--help': case '-h':
      console.log(`Usage: npm run build:dist -- [options]
  --check         Check build prerequisites without compiling
  --skip-desktop  Build Runtime, CLI/TUI and VS Code packages without a desktop installer
  --rg-bin PATH   Use an explicit target-native ripgrep executable

Default: rebuild backend + frontend + native CLI + Runtime + desktop + VS Code offline kit.
Output: dist/offline/<version>-<platform>-<arch>/
Requires Node 20+, JDK 17+ (with jlink), Maven and Rust/Cargo.
Missing ripgrep is built with Cargo; frontend/extension dependencies are installed with npm ci.
Builds the current host platform only. Existing application data is not cleaned.`)
      process.exit(0)
    case '--check': options.check = true; break
    case '--skip-desktop': options.skipDesktop = true; break
    case '--rg-bin':
      if (!args[i + 1] || args[i + 1].startsWith('--')) fail('--rg-bin requires an executable path')
      options.rg = resolve(args[++i]); break
    default: fail(`unknown option: ${args[i]} (use --help)`)
  }
}

const windows = process.platform === 'win32'
const target = `${process.platform}-${process.arch}`
if (!/^(win32|darwin|linux)-(x64|arm64)$/.test(target)) fail(`unsupported build platform: ${target}`)
const version = JSON.parse(readFileSync(join(ROOT, 'package.json'), 'utf8')).version
const runtime = join(ROOT, 'dist', 'runtime-bundles', `${version}-${target}`)
const output = join(ROOT, 'dist', 'offline', `${version}-${target}`)
const env = { ...process.env }

try {
  console.log(`Harnex ${version} | ${target} | ${options.check ? 'CHECK' : 'REBUILD DIST'}`)
  if (Number(process.versions.node.split('.')[0]) < 20) fail('Node 20+ is required by the VS Code packaging tools')
  const npm = findCommand('npm')
  const maven = existsSync(join(ROOT, 'backend', windows ? 'mvnw.cmd' : 'mvnw'))
    ? join(ROOT, 'backend', windows ? 'mvnw.cmd' : 'mvnw') : findCommand('mvn')
  const java = jdkTool('java')
  const jlink = jdkTool('jlink')
  const cargo = findCommand('cargo')
  for (const [name, command, flags] of [
    ['npm', npm, ['--version']], ['Maven', maven, ['--version']],
    ['Java', java, ['--version']], ['jlink', jlink, ['--version']], ['Rust/Cargo', cargo, ['--version']],
  ]) {
    if (!command) fail(`${name} is missing; install it and reopen the terminal`)
    const result = invoke(command, flags, join(ROOT, 'backend'), true)
    if (result.status !== 0) fail(`${name} is unavailable: ${result.error?.message || result.stderr || command}`)
    console.log(`  OK ${name}: ${String(result.stdout || result.stderr).trim().split(/\r?\n/)[0]}`)
  }
  const jlinkVersion = invoke(jlink, ['--version'], ROOT, true).stdout.trim()
  if (Number(jlinkVersion.match(/\d+/)?.[0] || 0) < 17) fail('JDK 17+ with jlink is required')

  const rgName = windows ? 'rg.exe' : 'rg'
  const rgCache = join(ROOT, 'dist', 'build-tools', 'ripgrep', target)
  const cachedRg = join(rgCache, 'bin', rgName)
  // Distribution-provided Linux rg often links to a host PCRE2 package. Compile the default
  // Rust engine for offline kits unless the builder explicitly supplies a portable executable.
  const systemRg = process.platform === 'linux' ? undefined : findCommand('rg')
  let rg = options.rg ? resolve(options.rg) : existsSync(cachedRg) ? cachedRg : systemRg
  if (rg) {
    const probe = invoke(rg, ['--version'], ROOT, true)
    if (probe.status !== 0 || !String(probe.stdout).startsWith('ripgrep ')) fail(`invalid ripgrep executable: ${rg}`)
    // A Homebrew-linked binary would work on the build host but break on an offline Mac.
    if (process.platform === 'darwin') {
      const deps = invoke('/usr/bin/otool', ['-L', rg], ROOT, true)
      const external = String(deps.stdout).split('\n').slice(1).map(line => line.trim()).filter(Boolean)
        .filter(line => !line.startsWith('/usr/lib/') && !line.startsWith('/System/Library/'))
      if (deps.status !== 0 || external.length) {
        if (options.rg) fail('selected rg depends on non-system libraries; supply a self-contained executable')
        rg = undefined
      }
    }
  }
  console.log(rg ? `  OK ripgrep: ${rg}` : '  ripgrep: will compile version 15.1.0 into dist/build-tools/ripgrep')
  if (!options.skipDesktop) {
    // Reuse the desktop toolchain check, including Tauri and native platform prerequisites.
    run('Check desktop toolchain', process.execPath, [join(ROOT, 'scripts', 'build', 'desktop.mjs'), '--check'])
  }
  if (options.check) {
    console.log(`Build prerequisites checked. Output will be: ${output}`)
    process.exit(0)
  }
  if (!rg) {
    run('Build bundled ripgrep', cargo,
      ['install', 'ripgrep', '--version', '15.1.0', '--locked', '--no-default-features', '--root', rgCache])
    rg = cachedRg
  }
  env.HARNEX_RG_PATH = rg

  run('Install frontend dependencies', npm, ['ci', '--no-audit', '--no-fund'], join(ROOT, 'frontend'))
  run('Install VS Code dependencies', npm, ['ci', '--no-audit', '--no-fund'], join(ROOT, 'extension'))
  run('Rebuild backend', maven, ['-B', '-ntp', 'clean', 'package', '-DskipTests'],
    join(ROOT, 'backend'))
  run('Build frontend', npm, ['run', 'build'], join(ROOT, 'frontend'))

  if (options.skipDesktop) {
    run('Build native CLI/TUI', process.execPath, [join(ROOT, 'scripts', 'build', 'native-cli.mjs'), '--target', target])
    run('Build Runtime Bundle', process.execPath,
      [join(ROOT, 'scripts', 'build', 'runtime-bundle.mjs'), '--target', target, '--with-native-cli'])
  } else {
    // desktop.mjs builds native CLI + Runtime once, then embeds that same Runtime in the installer.
    run('Build desktop and Runtime Bundle', process.execPath, [join(ROOT, 'scripts', 'build', 'desktop.mjs'), '--skip-build'])
  }
  run('Package VS Code and portable distribution', process.execPath,
    [join(ROOT, 'scripts', 'build', 'offline-kit.mjs'), '--runtime', runtime, '--target', target])
  run('Verify packaged Runtime', process.execPath,
    [join(ROOT, 'scripts', 'build', 'runtime-bundle.mjs'), '--verify', join(output, 'portable', 'runtime'), '--target', target])
  for (const file of ['SHA256SUMS', `portable/runtime/bin/${rgName}`]) {
    if (!existsSync(join(output, file))) fail(`distribution artifact missing: ${file}`)
  }
  const vsix = readdirSync(join(output, 'vscode')).filter(name => name.endsWith('.vsix'))
  if (vsix.length !== 2) fail('expected both the VS Code UI and Runtime VSIX packages')
  if (!options.skipDesktop) {
    const desktop = join(output, 'desktop')
    if (!existsSync(desktop) || !readdirSync(desktop).length) fail('new desktop installer was not included in the offline kit')
    if (process.platform === 'linux') {
      const names = readdirSync(desktop)
      for (const extension of ['.deb', '.rpm', '.AppImage']) {
        if (!names.some(name => name.endsWith(extension))) fail(`Linux desktop artifact missing: ${extension}`)
      }
    }
  }
  console.log(`\nBuild complete: ${output}`)
  console.log('  vscode/             VS Code UI + Runtime VSIX packages')
  console.log('  portable/runtime/   CLI/TUI + JRE + backend + ripgrep')
  if (!options.skipDesktop) console.log('  desktop/            Desktop installer (Windows/macOS/Linux)')
  console.log('  SHA256SUMS          Artifact checksums')
} catch (error) {
  console.error(`\nBuild failed: ${error.message}`)
  process.exitCode = 1
}

function findCommand(name) {
  for (const directory of (process.env.PATH || '').split(delimiter).filter(Boolean)) {
    for (const suffix of windows ? ['.exe', '.cmd', '.bat', ''] : ['']) {
      const file = resolve(directory.replace(/^"|"$/g, ''), name + suffix)
      if (existsSync(file) && statSync(file).isFile()) return file
    }
  }
  return undefined
}

function jdkTool(name) {
  const file = process.env.JAVA_HOME && join(process.env.JAVA_HOME, 'bin', windows ? `${name}.exe` : name)
  return file && existsSync(file) ? file : findCommand(name)
}

function invoke(command, commandArgs, cwd, quiet = false) {
  // PowerShell literal arguments preserve spaces/metacharacters in Windows .cmd paths.
  // Native executables and Node scripts never pass through a shell.
  let executable = command
  let argv = commandArgs
  if (windows && /\.(cmd|bat)$/i.test(command)) {
    const quote = value => `'${String(value).replaceAll("'", "''")}'`
    const script = `$ErrorActionPreference = 'Stop'; & ${[command, ...commandArgs].map(quote).join(' ')}; exit $LASTEXITCODE`
    executable = 'powershell.exe'
    argv = ['-NoLogo', '-NoProfile', '-NonInteractive', '-EncodedCommand', Buffer.from(script, 'utf16le').toString('base64')]
  }
  return spawnSync(executable, argv, { cwd, env, windowsHide: true, shell: false,
    ...(quiet ? { encoding: 'utf8', timeout: 30_000 } : { stdio: 'inherit' }) })
}

function run(label, command, commandArgs, cwd = ROOT) {
  console.log(`\n> ${label}`)
  const result = invoke(command, commandArgs, cwd)
  if (result.status !== 0) throw new Error(`${label} (exit ${result.status ?? result.signal ?? 'unknown'}): ${result.error?.message || 'see output above'}`)
}

function fail(message) {
  console.error(`Build error: ${message}`)
  process.exit(1)
}
