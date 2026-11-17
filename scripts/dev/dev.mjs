#!/usr/bin/env node
/**
 * Harnex 一键启动器（生产 / 真实开发用，**不**带任何桩）。
 *
 * 与 stubs/launch.mjs 的区别：
 *   - 不拉 LLM/MCP 桩，不写 spring profile=stubs
 *   - 使用项目 .harnex/data 数据目录，或 --data-dir <path> 显式指定
 *   - Web Runtime Domain（默认 ~/.harnex/runtime-domains/web-dev）
 *   - 由 --config <dir> 注入 SPRING_CONFIG_ADDITIONAL_LOCATION 加载你的私有 yml
 *
 * 模式：
 *   node scripts/dev/dev.mjs                  默认 = 后端(jar) + 前端 Vite Dev(HMR)
 *   node scripts/dev/dev.mjs --supervisor     单 active workspace Supervisor + 前端 dist
 *   node scripts/dev/dev.mjs --stop           停止之前由本脚本启动的所有进程
 *
 *   单进程场景请直接用：
 *     node bin/harnex.mjs serve   仅后端（jar 内置 SPA）
 *     node bin/harnex.mjs tui     Native TUI
 *     node bin/harnex.mjs stop    停后端
 *
 * 常用选项：
 *   --config <dir>           指向你的私有配置目录（含 agent-config.yml / application.yml）
 *   --data-dir <dir>         覆盖 harnex.data-dir（也可以写在你 --config 的 yml 里）
 *   --backend-port <n>       后端端口（默认 8088；Supervisor 模式下无效）
 *   --frontend-port <n>      Vite 端口（默认 18081；仅默认模式）
 *   --supervisor-port <n>    Supervisor 端口（默认 18081）
 *   --runtime-domain-dir <dir> Web Runtime Domain 目录
 *   --no-rebuild             跳过源码 mtime 检查，不自动 mvn / vite build
 *   --no-browser             不自动打开浏览器
 *   --force-kill-ports       端口被外部进程占用时强杀（默认 fail-safe，拒绝杀外部 PID）
 *
 * 环境变量直接 export 即可（OPENAI_API_KEY 之类），子进程会继承。
 */

import { spawn, spawnSync } from 'node:child_process'
import {
  existsSync, mkdirSync, readFileSync, writeFileSync, unlinkSync, copyFileSync, renameSync,
  statSync, readdirSync
} from 'node:fs'
import { basename, dirname, resolve, join } from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'
import { homedir, platform } from 'node:os'
import { appendConfigFile, appendConfigDir } from '../../bin/lib/config-inject.mjs'
import { ensureLoopbackNoProxy } from '../../bin/lib/no-proxy.mjs'
import { DEFAULT_WEB_PORT } from '../../bin/lib/ports.mjs'
import { waitFor } from './readiness.mjs'

const __dirname = dirname(fileURLToPath(import.meta.url))
const PROJECT_ROOT = resolve(__dirname, '../..')
const BIN_DIR = join(PROJECT_ROOT, 'bin')
const STATE_DIR = join(homedir(), '.harnex')
const PID_FILE = join(STATE_DIR, 'dev.pids')
const IS_WIN = platform() === 'win32'
ensureLoopbackNoProxy()

// ─── 参数解析（不引第三方库）────────────────────────────────────────────────
const argv = process.argv.slice(2)
const flag = (name) => argv.includes(name)
const valOf = (name, def = null) => {
  const i = argv.indexOf(name)
  return i >= 0 && i + 1 < argv.length ? argv[i + 1] : def
}

const isStop = flag('--stop') || flag('stop')
const wantsHelp = flag('-h') || flag('--help')
const supervisorMode = flag('--supervisor')
const noRebuild = flag('--no-rebuild')
const noBrowser = flag('--no-browser')
const forceKillPorts = flag('--force-kill-ports')

const configDir = valOf('--config')
const dataDirOpt = valOf('--data-dir')
const backendPort = Number(valOf('--backend-port', '8088'))
const frontendPort = Number(valOf('--frontend-port', String(DEFAULT_WEB_PORT)))
const supervisorPort = Number(valOf('--supervisor-port', String(DEFAULT_WEB_PORT)))
const supervisorHome = valOf('--runtime-domain-dir') || join(STATE_DIR, 'runtime-domains', 'web-dev')
const supervisorDomainId = 'web-dev'

const children = []
const pids = []

const C = {
  dim:    (s) => `\u001b[2m${s}\u001b[0m`,
  cyan:   (s) => `\u001b[36m${s}\u001b[0m`,
  green:  (s) => `\u001b[32m${s}\u001b[0m`,
  red:    (s) => `\u001b[31m${s}\u001b[0m`,
  yellow: (s) => `\u001b[33m${s}\u001b[0m`,
  bold:   (s) => `\u001b[1m${s}\u001b[0m`,
}
const log = (msg) => process.stdout.write(`${C.dim('[harnex-dev]')} ${msg}\n`)
const ok = (msg) => log(C.green('✓ ') + msg)
const warn = (msg) => log(C.yellow('! ') + msg)
const die = (msg, code = 1) => { process.stderr.write(C.red('[harnex-dev] ') + msg + '\n'); process.exit(code) }

function ensureStateDir() {
  if (!existsSync(STATE_DIR)) mkdirSync(STATE_DIR, { recursive: true })
}

// ─── HELP ─────────────────────────────────────────────────────────────────
if (wantsHelp) {
  process.stdout.write(`
Harnex 一键启动器（scripts/dev/dev.mjs）

用法：
  node scripts/dev/dev.mjs                       后端(jar) + 前端 Vite Dev (HMR)
  node scripts/dev/dev.mjs --supervisor          单 active workspace Supervisor + 前端 dist
  node scripts/dev/dev.mjs --stop                停止本脚本启动的所有进程

  单进程场景请直接用：
    node bin/harnex.mjs serve  仅后端（jar 内置 SPA）
    node bin/harnex.mjs tui    Native TUI
    node bin/harnex.mjs stop   停后端

选项：
  --config <dir>           外部配置目录 (SPRING_CONFIG_ADDITIONAL_LOCATION)
  --data-dir <dir>         覆盖 harnex.data-dir
  --backend-port <n>       后端端口（默认 8088）
  --frontend-port <n>      Vite 端口（默认 ${DEFAULT_WEB_PORT}）
  --supervisor-port <n>    Supervisor 端口（默认 ${DEFAULT_WEB_PORT}）
  --runtime-domain-dir <dir> Web Runtime Domain 目录
  --no-rebuild             不自动 mvn / vite build
  --no-browser             不自动打开浏览器
  --force-kill-ports       端口被外部进程占用时强杀
  -h, --help               显示本帮助

示例：
  node scripts/dev/dev.mjs --config ./my-conf --data-dir ./my-data
  node scripts/dev/dev.mjs --supervisor --config ./my-conf
`)
  process.exit(0)
}

// ─── STOP 模式 ─────────────────────────────────────────────────────────────
if (isStop) {
  if (!existsSync(PID_FILE)) {
    log('no dev.pids file — nothing to stop')
    process.exit(0)
  }
  const list = readFileSync(PID_FILE, 'utf8').split(/\r?\n/).map(Number).filter(n => n > 0)
  log(`stopping ${list.length} process(es)…`)
  for (const pid of list) killPid(pid, 'stop')
  try { unlinkSync(PID_FILE) } catch {}
  ok('done')
  process.exit(0)
}

// ─── 公用工具 ──────────────────────────────────────────────────────────────
function savePids() {
  ensureStateDir()
  writeFileSync(PID_FILE, pids.join('\n') + '\n', 'utf8')
}

function recordChild(child, label) {
  children.push(child)
  if (child.pid) { pids.push(child.pid); savePids() }
  child.on('exit', (code, sig) => log(`${label} exited (code=${code} signal=${sig})`))
}

function spawnChild(label, command, args, options = {}) {
  // Windows: .cmd/.bat 不能直接 spawn，得通过 cmd.exe
  const prepared = IS_WIN && /\.(cmd|bat)$/i.test(command)
    ? { command: 'cmd.exe', args: ['/d', '/s', '/c', command, ...args] }
    : { command, args }
  const child = spawn(prepared.command, prepared.args, {
    cwd: options.cwd || PROJECT_ROOT,
    windowsHide: true,
    stdio: ['ignore', 'pipe', 'pipe'],
    ...options,
  })
  child.stdout?.on('data', d => {
    const s = d.toString().trimEnd()
    if (s) process.stdout.write(`${C.cyan(`[${label}]`)} ${s}\n`)
  })
  child.stderr?.on('data', d => {
    const s = d.toString().trimEnd()
    if (s) process.stdout.write(`${C.yellow(`[${label}]`)} ${s}\n`)
  })
  recordChild(child, label)
  return child
}

function killPid(pid, why) {
  try {
    if (IS_WIN) spawnSync('taskkill', ['/PID', String(pid), '/T', '/F'], { windowsHide: true, stdio: 'ignore' })
    else process.kill(pid, 'SIGTERM')
    log(`killed PID ${pid} (${why})`)
  } catch { /* already gone */ }
}

const sleep = (ms) => new Promise(r => setTimeout(r, ms))

function stopManagedSupervisor() {
  if (!existsSync(join(supervisorHome, 'owner.json'))) return
  log(`检查运行目录 ${supervisorHome} 的旧 Supervisor；验证进程身份后停止并重新启动…`)
  const r = spawnSync(process.execPath, ['bin/harnex.mjs', 'supervisor', 'stop'], {
    cwd: PROJECT_ROOT,
    env: { ...process.env, HARNEX_RUNTIME_DOMAIN_ID: supervisorDomainId,
      HARNEX_RUNTIME_DOMAIN_DIR: supervisorHome },
    encoding: 'utf8',
    windowsHide: true,
    stdio: 'pipe',
    timeout: 20_000,
  })
  const out = `${r.stdout || ''}${r.stderr || ''}`.trim()
  if (out) log(out)
  if (r.status !== 0) {
    throw new Error(`停止现有 Supervisor 失败 (${r.error?.message || `exit ${r.status ?? 'unknown'}`})`)
  }
}

async function listPidsOnPort(port) {
  const out = new Set()
  try {
    const { execSync } = await import('node:child_process')
    if (IS_WIN) {
      const raw = execSync(`netstat -ano | findstr "LISTENING" | findstr ":${port} "`,
        { encoding: 'utf8', windowsHide: true, timeout: 5000 }).trim()
      for (const line of raw.split(/\r?\n/)) {
        const parts = line.trim().split(/\s+/)
        const pid = Number(parts[parts.length - 1])
        if (pid > 0 && pid !== process.pid) out.add(pid)
      }
    } else {
      const raw = execSync(`lsof -ti :${port}`, { encoding: 'utf8', timeout: 5000 }).trim()
      for (const pid of raw.split(/\s+/).map(Number)) if (pid > 0 && pid !== process.pid) out.add(pid)
    }
  } catch { /* port likely free */ }
  return out
}

function readPreviousPids() {
  if (!existsSync(PID_FILE)) return new Set()
  try { return new Set(readFileSync(PID_FILE, 'utf8').split(/\r?\n/).map(Number).filter(n => n > 0)) }
  catch { return new Set() }
}

/** Fail-safe 端口清场：默认只杀我们自己上次的 PID；外部进程要求 --force-kill-ports。 */
async function freePort(port, label) {
  let busy = await listPidsOnPort(port)
  if (busy.size === 0) return

  const prev = readPreviousPids()
  const ours = [...busy].filter(p => prev.has(p))
  const foreign = [...busy].filter(p => !prev.has(p))
  for (const pid of ours) killPid(pid, `${label}:${port} 上次残留`)
  busy = await listPidsOnPort(port)
  if (foreign.length > 0) {
    if (forceKillPorts) for (const pid of foreign) killPid(pid, `${label}:${port} --force-kill-ports`)
    else die(`端口 ${port} (${label}) 被外部 PID ${foreign.join(', ')} 占用。\n  释放后重试，或加 --force-kill-ports 强杀。`)
  }
  if (busy.size > 0) await sleep(800)
}

function findJar(dir = join(PROJECT_ROOT, 'backend', 'target')) {
  if (!existsSync(dir)) return null
  const cands = readdirSync(dir).filter(n =>
    n.startsWith('harnex-') && n.endsWith('.jar')
      && !n.endsWith('-sources.jar') && !n.endsWith('-javadoc.jar'))
  if (cands.length === 0) return null
  cands.sort((a, b) => statSync(join(dir, b)).mtimeMs - statSync(join(dir, a)).mtimeMs)
  return join(dir, cands[0])
}

function jarIsStale(jarPath) {
  if (!jarPath || !existsSync(jarPath)) return true
  const jarMtime = statSync(jarPath).mtimeMs
  const roots = [
    join(PROJECT_ROOT, 'backend', 'src', 'main'),
    join(PROJECT_ROOT, 'backend', 'pom.xml'),
  ]
  const walk = (p) => {
    let st; try { st = statSync(p) } catch { return false }
    if (!st.isDirectory()) return st.mtimeMs > jarMtime
    for (const e of readdirSync(p, { withFileTypes: true })) {
      if (e.name.startsWith('.') || e.name === 'target' || e.name === 'node_modules') continue
      if (walk(join(p, e.name))) return true
    }
    return false
  }
  return roots.some(walk)
}

function distIsStale() {
  const distIndex = join(PROJECT_ROOT, 'frontend', 'dist', 'index.html')
  const distMt = existsSync(distIndex) ? statSync(distIndex).mtimeMs : 0
  let srcMt = 0
  const walk = (dir) => {
    try {
      for (const e of readdirSync(dir, { withFileTypes: true })) {
        const p = join(dir, e.name)
        if (e.isDirectory()) walk(p)
        else { const m = statSync(p).mtimeMs; if (m > srcMt) srcMt = m }
      }
    } catch {}
  }
  walk(join(PROJECT_ROOT, 'frontend', 'src'))
  walk(join(PROJECT_ROOT, 'frontend', 'public'))
  for (const f of ['vite.config.ts', 'index.html', 'package.json', 'package-lock.json', 'tsconfig.json']) {
    const p = join(PROJECT_ROOT, 'frontend', f)
    if (existsSync(p)) { const m = statSync(p).mtimeMs; if (m > srcMt) srcMt = m }
  }
  return srcMt > distMt
}

function runSync(label, command, args, cwd) {
  log(`${label}: ${command} ${args.join(' ')}`)
  const prepared = IS_WIN && /\.(cmd|bat)$/i.test(command)
    ? { command: 'cmd.exe', args: ['/d', '/s', '/c', command, ...args] }
    : { command, args }
  return new Promise((res, rej) => {
    const p = spawn(prepared.command, prepared.args, {
      cwd: cwd || PROJECT_ROOT, stdio: 'inherit', windowsHide: true,
    })
    p.on('exit', c => c === 0 ? res() : rej(new Error(`${label} 失败 (exit ${c})`)))
    p.on('error', rej)
  })
}

async function ensureJar() {
  let jar = findJar()
  if (noRebuild) {
    if (!jar) die('找不到 backend jar，且指定了 --no-rebuild。先运行 npm run build。')
    return jar
  }
  if (jarIsStale(jar)) {
    log('─── 后端源码已更新（或 jar 缺失），重新打包 ───')
    const mvn = IS_WIN ? 'mvn.cmd' : 'mvn'
    // IDE/JDT can rewrite target/classes during Maven's test compilation, even after clean.
    // Compile and package outside that tree, then publish only the completed jar to the standard location.
    await runSync('mvn', mvn, ['-q', '-DskipTests', '-Dharnex.build.directory=../.artifacts/dev-backend',
      '-f', 'backend/pom.xml', 'clean', 'package'])
    const built = findJar(join(PROJECT_ROOT, '.artifacts', 'dev-backend'))
    if (!built) die('打包后仍找不到 jar，请人工检查 .artifacts/dev-backend/')
    const target = join(PROJECT_ROOT, 'backend', 'target')
    mkdirSync(target, { recursive: true })
    jar = join(target, basename(built))
    const staged = jar + '.pending'
    try {
      copyFileSync(built, staged)
      renameSync(staged, jar)
    } finally {
      if (existsSync(staged)) unlinkSync(staged)
    }
  } else {
    ok(`后端 jar 最新：${jar}`)
  }
  return jar
}

async function ensureFrontendDependencies() {
  const frontend = join(PROJECT_ROOT, 'frontend')
  const suffix = IS_WIN ? '.cmd' : ''
  const tools = [join('node_modules', '.bin', `vite${suffix}`),
    join('node_modules', '.bin', `vue-tsc${suffix}`),
    join('node_modules', 'typescript', 'lib', 'typescript.js')]
  if (tools.every(file => existsSync(join(frontend, file)))) return
  log('─── 前端构建依赖缺失，按 package-lock.json 安装（含开发依赖）───')
  await runSync('frontend-npm-ci', IS_WIN ? 'npm.cmd' : 'npm',
    ['ci', '--include=dev', '--include=optional', '--no-audit', '--no-fund'], frontend)
  if (!tools.every(file => existsSync(join(frontend, file)))) {
    throw new Error('前端构建工具未安装完整，请在 frontend 目录执行 npm ci --include=dev --include=optional。')
  }
}

async function ensureDist() {
  if (noRebuild) {
    if (!existsSync(join(PROJECT_ROOT, 'frontend', 'dist', 'index.html')))
      die('frontend/dist 不存在，且指定了 --no-rebuild。先 cd frontend && npm run build。')
    return
  }
  if (distIsStale()) {
    log('─── 前端源码已更新（或 dist 缺失），重新构建 ───')
    await ensureFrontendDependencies()
    const npm = IS_WIN ? 'npm.cmd' : 'npm'
    await runSync('vite-build', npm, ['run', 'build'], join(PROJECT_ROOT, 'frontend'))
  } else {
    ok('frontend/dist 最新')
  }
}

function buildBackendEnv() {
  const env = { ...process.env }
  // 自动加载用户级配置：~/.harnex/{agent-config.yml, application.yml}（如果存在）。
  // 行为与 bin/harnex.mjs / supervisor.mjs 保持一致：追加用户配置，jar 内置默认值仍会加载。
  for (const name of ['agent-config.yml', 'application.yml']) {
    const userYml = join(STATE_DIR, name)
    if (existsSync(userYml)) {
      // 追加“具体文件 URL”而非目录——Spring 对目录 location 只扫描 application.*，
      // 同目录下的 agent-config.yml 不会被加载。追加而非替换，jar 内置默认值仍生效。
      if (appendConfigFile(env, userYml)) {
        ok(`自动加载用户配置：${userYml}`)
      }
    }
  }
  if (configDir) {
    const abs = resolve(configDir)
    if (!existsSync(abs)) die(`--config 目录不存在：${abs}`)
    // 目录 location 让 Spring 扫描 application.*；agent-config.yml 需显式追加具体文件。
    // 追加而不是覆盖——保留 jar 内默认 application.yml + agent-config.yml
    appendConfigDir(env, abs)
    const agentCfg = join(abs, 'agent-config.yml')
    if (existsSync(agentCfg)) appendConfigFile(env, agentCfg)
    ok(`SPRING_CONFIG_ADDITIONAL_LOCATION = ${env.SPRING_CONFIG_ADDITIONAL_LOCATION}`)
  }
  if (dataDirOpt) {
    env.HARNEX_DATA_DIR = resolve(dataDirOpt).replace(/\\/g, '/')
    ok(`HARNEX_DATA_DIR = ${env.HARNEX_DATA_DIR}`)
  }
  return env
}

function openBrowser(url) {
  if (noBrowser) return
  try {
    if (IS_WIN) spawn('cmd.exe', ['/c', 'start', '', url], { detached: true, stdio: 'ignore' }).unref()
    else if (process.platform === 'darwin') spawn('open', [url], { detached: true, stdio: 'ignore' }).unref()
    else spawn('xdg-open', [url], { detached: true, stdio: 'ignore' }).unref()
  } catch { /* headless / no DISPLAY */ }
}

// ─── 信号处理 ──────────────────────────────────────────────────────────────
async function cleanup(exitCode = 0) {
  log('正在关闭所有子进程…')
  for (const ch of children) {
    if (ch.killed || ch.exitCode != null || ch.signalCode != null || !ch.pid) continue
    try {
      if (IS_WIN) spawnSync('taskkill', ['/PID', String(ch.pid), '/T', '/F'], { windowsHide: true, stdio: 'ignore' })
      else ch.kill('SIGTERM')
    } catch {}
  }
  try { unlinkSync(PID_FILE) } catch {}
  await new Promise(r => setTimeout(r, 800))
  process.exit(exitCode)
}
process.on('SIGINT', () => cleanup())
process.on('SIGTERM', () => cleanup())

// ─── 三种启动模式 ──────────────────────────────────────────────────────────

async function startBackend(env) {
  const jar = await ensureJar()
  await freePort(backendPort, 'backend')
  const args = ['-jar', jar, `--server.port=${backendPort}`]
  const javaBin = process.env.JAVA_HOME ? join(process.env.JAVA_HOME, 'bin', IS_WIN ? 'java.exe' : 'java') : 'java'
  const child = spawnChild('backend', javaBin, args, { env })
  log(`等待后端就绪 http://127.0.0.1:${backendPort}/actuator/health …`)
  await waitFor(`http://127.0.0.1:${backendPort}/actuator/health`, 'backend', { child })
  ok(`后端就绪 → http://127.0.0.1:${backendPort}`)
}

async function startFrontendDev() {
  await ensureFrontendDependencies()
  await freePort(frontendPort, 'vite')
  const npm = IS_WIN ? 'npm.cmd' : 'npm'
  const env = { ...process.env,
    VITE_BACKEND_TARGET: `http://127.0.0.1:${backendPort}`,
    VITE_OPEN_BROWSER: noBrowser ? '0' : '1',
  }
  const child = spawnChild('vite', npm, ['run', 'dev', '--', `--port=${frontendPort}`],
    { cwd: join(PROJECT_ROOT, 'frontend'), env })
  log(`等待 Vite Dev 就绪 http://127.0.0.1:${frontendPort} …`)
  await waitFor(`http://127.0.0.1:${frontendPort}`, 'vite', { child })
  ok(`前端就绪 → http://127.0.0.1:${frontendPort}`)
}

async function startSupervisor(env) {
  await ensureJar()
  await ensureDist()
  stopManagedSupervisor()
  await freePort(supervisorPort, 'supervisor')
  const supervisorModuleUrl = pathToFileURL(join(BIN_DIR, 'lib', 'supervisor.mjs')).href
  const supervisorArgs = [`--port=${supervisorPort}`]
  // 用户配置透传给唯一的 active workspace JVM。
  const childEnvKv = []
  for (const k of ['SPRING_CONFIG_ADDITIONAL_LOCATION', 'HARNEX_DATA_DIR']) {
    if (env[k] && env[k] !== process.env[k]) childEnvKv.push(`--child-env=${k}=${env[k]}`)
  }
  supervisorArgs.push(...childEnvKv)
  const wrapper = `
    process.env.HARNEX_RUNTIME_DOMAIN_ID = ${JSON.stringify(supervisorDomainId)};
    process.env.HARNEX_RUNTIME_DOMAIN_DIR = ${JSON.stringify(supervisorHome.replace(/\\/g, '/'))};
    import(${JSON.stringify(supervisorModuleUrl)})
      .then(m => m.cmdSupervisor(${JSON.stringify(supervisorArgs)}))
      .catch(e => { console.error('supervisor failed:', e); process.exit(1); });
  `.trim()
  const child = spawnChild('supervisor', process.execPath, ['--input-type=module', '-e', wrapper], {
    env: { ...env, HARNEX_RUNTIME_DOMAIN_ID: supervisorDomainId,
      HARNEX_RUNTIME_DOMAIN_DIR: supervisorHome },
  })
  log(`等待 Supervisor 就绪 http://127.0.0.1:${supervisorPort}/supervisor/api/status …`)
  await waitFor(`http://127.0.0.1:${supervisorPort}/supervisor/api/status`, 'supervisor', { child })
  ok(`Supervisor 就绪 → http://127.0.0.1:${supervisorPort}/`)
}

// ─── 主入口 ────────────────────────────────────────────────────────────────
async function main() {
  ensureStateDir()
  pids.push(process.pid); savePids()  // 把 launcher 自身记下，--stop 也能干掉残余 child

  log(`─── Harnex 一键启动（${supervisorMode ? 'supervisor' : 'dev'}）───`)
  const env = buildBackendEnv()

  if (supervisorMode) {
    await startSupervisor(env)
    openBrowser(`http://127.0.0.1:${supervisorPort}/`)
    banner([
      `Web UI       → http://127.0.0.1:${supervisorPort}/`,
      `Supervisor   → ${supervisorHome}`,
      `配置目录     → ${configDir ? resolve(configDir) : '（未指定 --config，仅用 jar 内置默认）'}`,
      `停止         → Ctrl+C  或  node scripts/dev/dev.mjs --stop`,
    ])
  } else {
    // 默认 dev：后端 + Vite HMR
    await startBackend(env)
    await startFrontendDev()
    openBrowser(`http://127.0.0.1:${frontendPort}`)
    banner([
      `前端（HMR）  → http://127.0.0.1:${frontendPort}`,
      `后端 API     → http://127.0.0.1:${backendPort}`,
      `配置目录     → ${configDir ? resolve(configDir) : '（未指定 --config，仅用 jar 内置默认）'}`,
      `数据目录     → ${dataDirOpt ? resolve(dataDirOpt) : '（默认 ./data）'}`,
      `停止         → Ctrl+C  或  node scripts/dev/dev.mjs --stop`,
    ])
  }
}

function banner(lines) {
  log('')
  log('═══════════════════════════════════════════════════')
  for (const l of lines) log(`  ${l}`)
  log('═══════════════════════════════════════════════════')
}

main().catch(e => {
  process.stderr.write(C.red(`[harnex-dev] 启动失败：${e.stack || e.message || e}\n`))
  cleanup(1)
})
