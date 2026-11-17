#!/usr/bin/env node
// One-click build & pack/publish script for harnex.
//
// Usage:
//   node scripts/build/build.mjs                     # 默认: backend + frontend + npm pack
//   node scripts/build/build.mjs --skip-backend      # 跳过 mvn package
//   node scripts/build/build.mjs --skip-frontend     # 跳过 vite build
//   node scripts/build/build.mjs --pack              # 显式 npm pack（默认行为）
//   node scripts/build/build.mjs --publish           # 真发布: npm publish --access public
//   node scripts/build/build.mjs --publish --tag beta
//   node scripts/build/build.mjs --dry-run           # npm pack --dry-run 仅预览
//   node scripts/build/build.mjs --version patch     # 先 npm version patch 再发
//   node scripts/build/build.mjs --clean             # 先清理 target/ 和 frontend/dist
//   node scripts/build/build.mjs --target desktop    # 构建 Tauri 桌面安装包（委托 scripts/build/desktop.mjs，不走 npm pack）
//
// --target npm（默认）产出 .tgz；--target desktop 产出 Windows 安装包。
// 也可以 npm run build / npm run release / npm run build:desktop （见 package.json scripts）

import { spawnSync } from 'node:child_process';
import { existsSync, rmSync, readdirSync, statSync, readFileSync } from 'node:fs';
import { join, dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
const isWin = process.platform === 'win32';

// ── 参数解析 ───────────────────────────────────────────
const args = process.argv.slice(2);
const has = (f) => args.includes(f);
const valOf = (f) => {
  const i = args.indexOf(f);
  return i >= 0 && i + 1 < args.length ? args[i + 1] : null;
};

const opts = {
  skipBackend:  has('--skip-backend'),
  skipFrontend: has('--skip-frontend'),
  publish:      has('--publish'),
  dryRun:       has('--dry-run'),
  clean:        has('--clean'),
  versionBump:  valOf('--version'),     // patch | minor | major
  npmTag:       valOf('--tag'),         // e.g. beta, next
  target:       valOf('--target') || 'npm', // npm（默认，.tgz）| desktop（委托 desktop.mjs 出安装包）
};

if (!['npm', 'desktop'].includes(opts.target)) {
  console.error(`未知 --target「${opts.target}」，仅支持 npm | desktop`);
  process.exit(1);
}

// ── 工具函数 ───────────────────────────────────────────
const C = { dim:'\x1b[2m', cyan:'\x1b[36m', green:'\x1b[32m', red:'\x1b[31m', yellow:'\x1b[33m', bold:'\x1b[1m', reset:'\x1b[0m' };
const log  = (msg) => console.log(`${C.cyan}▶${C.reset} ${msg}`);
const ok   = (msg) => console.log(`${C.green}✔${C.reset} ${msg}`);
const warn = (msg) => console.log(`${C.yellow}⚠${C.reset} ${msg}`);
const die  = (msg) => { console.error(`${C.red}✘${C.reset} ${msg}`); process.exit(1); };

function run(cmd, cmdArgs, cwd) {
  const display = `${cmd} ${cmdArgs.join(' ')}`;
  console.log(`${C.dim}  $ ${display}  (cwd=${cwd})${C.reset}`);
  const r = spawnSync(cmd, cmdArgs, { cwd, stdio: 'inherit', shell: isWin });
  if (r.status !== 0) die(`命令失败 (exit=${r.status}): ${display}`);
}

function findJar() {
  const dir = join(ROOT, 'backend', 'target');
  if (!existsSync(dir)) return null;
  const jars = readdirSync(dir).filter(f => /^harnex-.*\.jar$/.test(f) && !f.endsWith('-sources.jar') && !f.endsWith('-javadoc.jar'));
  return jars.length ? join(dir, jars[0]) : null;
}

// ── Banner ────────────────────────────────────────────
const pkg = JSON.parse(readFileSync(join(ROOT, 'package.json'), 'utf8'));
console.log(`${C.bold}┌─ harnex build ───────────────────────────${C.reset}`);
console.log(`${C.bold}│${C.reset}  package : ${pkg.name}@${pkg.version}`);
console.log(`${C.bold}│${C.reset}  root    : ${ROOT}`);
console.log(`${C.bold}│${C.reset}  mode    : ${opts.publish ? 'PUBLISH' : (opts.dryRun ? 'DRY-RUN' : 'PACK')}`);
console.log(`${C.bold}└──────────────────────────────────────────────────${C.reset}\n`);

// ── 步骤 0: 清理 ───────────────────────────────────────
if (opts.clean) {
  log('清理 backend/target 与 frontend/dist');
  for (const p of ['backend/target', 'frontend/dist']) {
    const full = join(ROOT, p);
    if (existsSync(full)) { rmSync(full, { recursive: true, force: true }); ok(`已删除 ${p}`); }
  }
  // 顺便清理旧的 .tgz
  for (const f of readdirSync(ROOT).filter(f => /^harnex-.*\.tgz$/.test(f))) {
    rmSync(join(ROOT, f), { force: true });
    ok(`已删除 ${f}`);
  }
}

// ── 步骤 1: 升版本（可选，必须在 pack/publish 前）─────
if (opts.versionBump) {
  if (!['patch','minor','major'].includes(opts.versionBump)) {
    die(`--version 必须是 patch/minor/major，收到: ${opts.versionBump}`);
  }
  log(`升版本: npm version ${opts.versionBump}`);
  run('npm', ['version', opts.versionBump, '--no-git-tag-version'], ROOT);
  const newPkg = JSON.parse(readFileSync(join(ROOT, 'package.json'), 'utf8'));
  ok(`新版本: ${newPkg.version}`);
}

// ── 步骤 2: 后端 ───────────────────────────────────────
if (opts.skipBackend) {
  warn('跳过后端构建 (--skip-backend)');
} else {
  log('构建后端 (mvn clean package)');
  const mvnArgs = ['-B', 'clean', 'package'];
  mvnArgs.push('-DskipTests');
  // 优先用 mvnw（如果存在），否则 mvn
  const wrapper = isWin ? 'mvnw.cmd' : './mvnw';
  const useWrapper = existsSync(join(ROOT, 'backend', isWin ? 'mvnw.cmd' : 'mvnw'));
  run(useWrapper ? wrapper : 'mvn', mvnArgs, join(ROOT, 'backend'));
  const jar = findJar();
  if (!jar) die('后端构建完成但未找到 backend/target/harnex-*.jar');
  ok(`jar 已生成: ${jar.replace(ROOT + (isWin?'\\':'/'), '')} (${(statSync(jar).size/1024/1024).toFixed(1)} MB)`);
}

// ── 步骤 3: 前端 ───────────────────────────────────────
if (opts.skipFrontend) {
  warn('跳过前端构建 (--skip-frontend)');
} else {
  log('构建前端 (npm ci + vite build)');
  const fe = join(ROOT, 'frontend');
  if (!existsSync(join(fe, 'node_modules'))) {
    run('npm', ['ci'], fe);
  } else {
    console.log(`${C.dim}  (检测到 node_modules，跳过 npm ci；需要重装请加 --clean)${C.reset}`);
  }
  run('npm', ['run', 'build'], fe);
  const distIdx = join(fe, 'dist', 'index.html');
  if (!existsSync(distIdx)) die('前端构建完成但未找到 frontend/dist/index.html');
  ok('前端 dist 已生成');
}

// ── 步骤 3.9: 桌面外壳（--target desktop，与 npm pack 互斥）────────
// `--target desktop`：在已构建 jar+前端的基础上，委托 scripts/build/desktop.mjs 完成
// jlink 内置 JRE → 暂存 → tauri build。这条线与 npm 发布无关（产物是安装包，不是 .tgz），
// 因此构建完直接退出，不再走 npm pack/publish。详见 src-tauri/README.md。
if (opts.target === 'desktop') {
  log('构建桌面外壳 (node scripts/build/desktop.mjs --skip-build)');
  run('node', [join(ROOT, 'scripts', 'build', 'desktop.mjs'), '--skip-build'], ROOT);
  console.log(`\n${C.green}${C.bold}桌面外壳构建完成${C.reset}：产物在 src-tauri/target/.../bundle/`);
  process.exit(0);
}

// ── 步骤 4: 校验 npm 打包内容 ─────────────────────────
log('校验 npm pack 将包含的文件 (npm pack --dry-run --json)');
const dry = spawnSync('npm', ['pack', '--dry-run', '--json'], { cwd: ROOT, shell: isWin, encoding: 'utf8' });
if (dry.status !== 0) { console.error(dry.stderr); die('npm pack --dry-run 失败'); }
let packInfo;
try { packInfo = JSON.parse(dry.stdout)[0]; } catch { die('解析 npm pack --json 输出失败'); }

const files = packInfo.files || [];
const hasJar = files.some(f => /backend\/target\/harnex-.*\.jar$/.test(f.path));
const hasFeDist = files.some(f => f.path.startsWith('frontend/dist/'));
const hasHarnex = files.some(f => f.path === 'bin/harnex.mjs');

console.log(`  文件数: ${files.length}, 解包后大小: ${(packInfo.unpackedSize/1024/1024).toFixed(2)} MB, .tgz: ${(packInfo.size/1024).toFixed(0)} KB`);
if (!hasHarnex) die('打包内容缺少 bin/harnex.mjs');
if (!hasJar)   die('打包内容缺少 backend/target/harnex-*.jar — 请检查 package.json files 字段或先跑后端构建');
if (!hasFeDist) warn('打包内容不含 frontend/dist/ — serve 模式将无 Web UI（cli 模式仍可用）');
ok('打包内容校验通过');

if (opts.dryRun && !opts.publish) {
  console.log(`\n${C.green}${C.bold}DRY-RUN 完成${C.reset}：未实际生成 .tgz 也未发布。`);
  process.exit(0);
}

// ── 步骤 5a: 发布 ─────────────────────────────────────
if (opts.publish) {
  // 登录检查
  const who = spawnSync('npm', ['whoami'], { shell: isWin, encoding: 'utf8' });
  if (who.status !== 0) die('未登录 npm，请先运行: npm login');
  console.log(`  npm user: ${who.stdout.trim()}`);

  // Name-collision sanity check: query the registry for the current package name and refuse
  // to publish if (a) the name resolves to an existing package AND (b) the current npm user
  // is not in the package's `maintainers`. Without this it's far too easy to clobber an
  // unrelated `harnex` someone else may have squatted, OR — worse — to publish a
  // typo-squat under a name we never intended (e.g. `Harnex`). Pure-network check;
  // skipped automatically if the registry is unreachable so an offline build can still publish.
  try {
    const view = spawnSync('npm', ['view', pkg.name, 'maintainers', '--json'],
      { shell: isWin, encoding: 'utf8' });
    if (view.status === 0 && view.stdout && view.stdout.trim() !== '') {
      let maints = [];
      try { maints = JSON.parse(view.stdout); } catch { /* registry returned plain text */ }
      const me = who.stdout.trim();
      const isMine = Array.isArray(maints) &&
        maints.some(m => (typeof m === 'string' && m.startsWith(me + ' ')) ||
                         (m && m.name === me));
      if (!isMine && maints.length > 0) {
        die(`包名 "${pkg.name}" 已被其他用户占用 (维护者: ${
          maints.map(m => typeof m === 'string' ? m.split(' ')[0] : m.name).join(', ')
        })。请改名或换账号 (--tag dev / 修改 package.json name) 再重试。`);
      }
    } else if (view.stderr && /E404/.test(view.stderr)) {
      console.log(`  ${C.green}✓${C.reset}  registry 中尚无同名包 — 首次发布。`);
    }
  } catch (e) {
    console.log(`  ${C.yellow}!${C.reset}  registry 查询失败 (${e.message}) — 跳过名字冲突预检。`);
  }

  const pubArgs = ['publish', '--access', 'public'];
  if (opts.npmTag) pubArgs.push('--tag', opts.npmTag);
  log(`发布到 npm registry (${opts.npmTag || 'latest'} tag)`);
  run('npm', pubArgs, ROOT);
  ok(`已发布 ${pkg.name}@${packInfo.version || pkg.version}`);
  console.log(`\n用户安装命令: ${C.bold}npm install -g ${pkg.name}${opts.npmTag ? '@' + opts.npmTag : ''}${C.reset}`);
  process.exit(0);
}

// ── 步骤 5b: 仅打包 ───────────────────────────────────
log('生成 .tgz (npm pack)');
const pack = spawnSync('npm', ['pack', '--json'], { cwd: ROOT, shell: isWin, encoding: 'utf8' });
if (pack.status !== 0) { console.error(pack.stderr); die('npm pack 失败'); }
let tgz;
try { tgz = JSON.parse(pack.stdout)[0].filename; } catch { tgz = `${pkg.name}-${pkg.version}.tgz`; }
ok(`已生成 ${tgz}`);

console.log(`\n${C.green}${C.bold}构建完成${C.reset}`);
console.log(`本地试装: ${C.bold}npm install -g ./${tgz}${C.reset}`);
console.log(`正式发布: ${C.bold}node scripts/build/build.mjs --publish${C.reset}`);
