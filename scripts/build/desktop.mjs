#!/usr/bin/env node
// Harnex desktop-shell build orchestrator (see docs/04_部署方案.md).
//
// Pipeline (Windows-first; runs on macOS/Linux too):
//   1. build backend jar + frontend/dist (unless --skip-build)
//   2. build + verify one canonical Runtime Bundle (jar + jlink JRE + hashes)
//   3. stage that exact bundle for Tauri/NSIS
//   4. sync version into src-tauri/tauri.conf.json
//   5. ensure icons (auto `tauri icon` from src-tauri/icons/source.png when present)
//   6. `tauri build` (or `tauri dev` with --dev)
//
// Usage:
//   node scripts/build/desktop.mjs                 # release build → src-tauri/target/release/bundle/
//   node scripts/build/desktop.mjs --debug         # debug build (faster, larger)
//   node scripts/build/desktop.mjs --verbose       # include bundler diagnostics (automatic on Linux)
//   node scripts/build/desktop.mjs --dev           # `tauri dev` with its own Runtime Domain
//   node scripts/build/desktop.mjs --check         # only verify the toolchain, build nothing
//   node scripts/build/desktop.mjs --skip-build    # don't (re)build jar/frontend, just stage + bundle
//   node scripts/build/desktop.mjs --skip-jre      # don't jlink (reuse an existing resources/jre)
//   node scripts/build/desktop.mjs --updater       # signed updater artifacts (requires signing/update env)
//   node scripts/build/desktop.mjs --macos-release # Developer ID + notarization/staple verification
//   node scripts/build/desktop.mjs --target darwin-arm64|darwin-x64  # must match native runner
//   node scripts/build/desktop.mjs --jre-modules a,b,c   # override the jlink --add-modules set
//
// This script is OPT-IN: the default `npm run build` never invokes it, so a machine
// without Rust/Tauri can still build & publish the npm package.

import { spawnSync } from "node:child_process";
import { existsSync, rmSync, mkdirSync, readdirSync, statSync, readFileSync, writeFileSync, cpSync } from "node:fs";
import { join, dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), "../..");
const SRC_TAURI = join(ROOT, "src-tauri");
const RES_DIR = join(SRC_TAURI, "resources");
const JRE_DIR = join(RES_DIR, "jre");
const RES_UI = join(RES_DIR, "ui");
const isWin = process.platform === "win32";
const isMac = process.platform === "darwin";

// ── args ──────────────────────────────────────────────
const args = process.argv.slice(2);
const has = (f) => args.includes(f);
const valOf = (f) => { const i = args.indexOf(f); return i >= 0 && i + 1 < args.length ? args[i + 1] : null; };
const opts = {
  check:      has("--check"),
  dev:        has("--dev"),
  debug:      has("--debug"),
  verbose:    has("--verbose") || process.platform === "linux",
  skipBuild:  has("--skip-build"),
  skipJre:    has("--skip-jre"),
  updater:    has("--updater"),
  macosRelease: has("--macos-release"),
  target:     valOf("--target"),
  jreModules: valOf("--jre-modules"),
  help:       has("--help") || has("-h"),
};

// ── pretty ────────────────────────────────────────────
const C = { dim:"\x1b[2m", cyan:"\x1b[36m", green:"\x1b[32m", red:"\x1b[31m", yellow:"\x1b[33m", bold:"\x1b[1m", reset:"\x1b[0m" };
const log  = (m) => console.log(`${C.cyan}▶${C.reset} ${m}`);
const ok   = (m) => console.log(`${C.green}✔${C.reset} ${m}`);
const warn = (m) => console.log(`${C.yellow}⚠${C.reset} ${m}`);
const die  = (m) => { console.error(`${C.red}✘${C.reset} ${m}`); process.exit(1); };

// Avoid shell:true: recent Node versions correctly warn that concatenating arguments into a
// shell command is an injection boundary. Native executables are spawned directly. Windows
// batch shims (npm/npx/mvnw) are invoked through an explicit cmd.exe argv boundary.
function invocation(cmd, cmdArgs) {
  if (!isWin) return { cmd, args: cmdArgs };
  const resolved = resolveWindowsCommand(cmd);
  if (!/\.(?:cmd|bat)$/i.test(resolved)) return { cmd: resolved, args: cmdArgs };
  return {
    cmd: process.env.ComSpec || "C:\\Windows\\System32\\cmd.exe",
    args: ["/d", "/c", "call", resolved, ...cmdArgs],
  };
}

function resolveWindowsCommand(cmd) {
  if (/[\\/]/.test(cmd) || /\.[A-Za-z0-9]+$/.test(cmd)) return cmd;
  const found = spawnSync("where.exe", [cmd], { encoding: "utf8", windowsHide: true });
  if (found.status !== 0) return cmd;
  const candidates = String(found.stdout).split(/\r?\n/).map(value => value.trim()).filter(Boolean);
  return candidates.find(value => /\.(?:exe|com|cmd|bat)$/i.test(value)) || candidates[0] || cmd;
}

function run(cmd, cmdArgs, cwd = ROOT, env = process.env) {
  console.log(`${C.dim}  $ ${cmd} ${cmdArgs.join(" ")}  (cwd=${cwd})${C.reset}`);
  const executable = invocation(cmd, cmdArgs);
  const r = spawnSync(executable.cmd, executable.args, { cwd, stdio: "inherit", shell: false, env });
  if (r.status !== 0) die(`命令失败 (exit=${r.status}): ${cmd} ${cmdArgs.join(" ")}`);
}

/** Run a command quietly and return {ok, out}. Never throws. */
function probe(cmd, cmdArgs) {
  try {
    const executable = invocation(cmd, cmdArgs);
    const r = spawnSync(executable.cmd, executable.args, { encoding: "utf8", shell: false });
    return { ok: r.status === 0, out: ((r.stdout || "") + (r.stderr || "")).trim() };
  } catch { return { ok: false, out: "" }; }
}

if (opts.help) {
  console.log(readFileSync(fileURLToPath(import.meta.url), "utf8").split("\n").filter(l => l.startsWith("//")).map(l => l.slice(3)).join("\n"));
  process.exit(0);
}

// ── JDK tooling (jlink / jdeps) resolution ────────────
function jdkTool(name) {
  const exe = isWin ? `${name}.exe` : name;
  if (process.env.JAVA_HOME) {
    const cand = join(process.env.JAVA_HOME, "bin", exe);
    if (existsSync(cand)) return cand;
  }
  // fall through to PATH
  return probe(name, ["--version"]).ok ? name : null;
}

/** Resolve how to invoke the Tauri CLI: prefer cargo-tauri, then npx @tauri-apps/cli. */
function resolveTauriRunner() {
  if (probe("cargo", ["tauri", "--version"]).ok) return { cmd: "cargo", pre: ["tauri"] };
  if (probe("npx", ["--yes", "@tauri-apps/cli", "--version"]).ok) return { cmd: "npx", pre: ["--yes", "@tauri-apps/cli"] };
  return null;
}

// ── toolchain check ───────────────────────────────────
function checkToolchain() {
  const rows = [];
  const cargo = probe("cargo", ["--version"]);
  rows.push(["cargo / Rust", cargo.ok, cargo.ok ? cargo.out.split("\n")[0] : "缺失 — 安装 https://rustup.rs"]);
  const tauri = resolveTauriRunner();
  rows.push(["tauri CLI v2", !!tauri, tauri ? `${tauri.cmd} ${tauri.pre.join(" ")}` : "缺失 — cargo install tauri-cli --version ^2  或  npm i -g @tauri-apps/cli@^2"]);
  const jlink = jdkTool("jlink");
  rows.push(["jlink (JDK 17+)", !!jlink, jlink || "缺失 — 安装含 jlink 的 JDK17+（Adoptium）；或加 --skip-jre 复用已有 JRE"]);
  const jdeps = jdkTool("jdeps");
  rows.push(["jdeps (可选)", !!jdeps, jdeps || "缺失 — 将使用内置模块清单"]);
  const maven = probe("mvn", ["--version"]);
  rows.push(["Maven", maven.ok, maven.ok ? maven.out.split("\n")[0] : "缺失 — 安装 Maven 3.9+"]);
  let macToolsOk = true;
  let notarytoolOk = true;
  if (isMac) {
    const xcode = probe("xcode-select", ["-p"]);
    const codesign = probe("xcrun", ["--find", "codesign"]);
    const notarytool = probe("xcrun", ["--find", "notarytool"]);
    macToolsOk = xcode.ok && codesign.ok;
    notarytoolOk = notarytool.ok;
    rows.push(["Xcode CLT", xcode.ok, xcode.ok ? xcode.out.split("\n")[0] : "缺失 — xcode-select --install"]);
    rows.push(["codesign", codesign.ok, codesign.ok ? codesign.out : "缺失 — 安装 Xcode Command Line Tools"]);
    rows.push(["notarytool", notarytool.ok, notarytool.ok ? notarytool.out : "缺失 — 安装当前 Xcode"]);
  }

  console.log("");
  for (const [name, good, detail] of rows) {
    const mark = good ? `${C.green}✔${C.reset}` : `${C.red}✘${C.reset}`;
    console.log(`  ${mark} ${C.bold}${name.padEnd(16)}${C.reset} ${C.dim}${detail}${C.reset}`);
  }
  console.log("");
  return { cargoOk: cargo.ok, tauri, jlink, jdeps, mavenOk: maven.ok, macToolsOk, notarytoolOk };
}

// ── artifact helpers ──────────────────────────────────
function findJar() {
  const dir = join(ROOT, "backend", "target");
  if (!existsSync(dir)) return null;
  const jars = readdirSync(dir).filter(f => /^harnex-.*\.jar$/.test(f) && !f.endsWith("-sources.jar") && !f.endsWith("-javadoc.jar"));
  if (!jars.length) return null;
  jars.sort((a, b) => statSync(join(dir, b)).mtimeMs - statSync(join(dir, a)).mtimeMs);
  return join(dir, jars[0]);
}

function ensureArtifacts() {
  if (opts.skipBuild) { warn("跳过 jar/前端构建 (--skip-build)"); return; }
  log("构建后端 (mvn clean -DskipTests package)");
  const useWrapper = existsSync(join(ROOT, "backend", isWin ? "mvnw.cmd" : "mvnw"));
  run(useWrapper ? (isWin ? "mvnw.cmd" : "./mvnw") : "mvn", ["-B", "clean", "-DskipTests", "package"], join(ROOT, "backend"));
  const jar = findJar();
  if (!jar) die("后端构建后仍未找到 backend/target/harnex-*.jar");
  const dist = join(ROOT, "frontend", "dist", "index.html");
  log("构建前端 (vite build)");
  const fe = join(ROOT, "frontend");
  if (!existsSync(join(fe, "node_modules"))) run("npm", ["ci"], fe);
  run("npm", ["run", "build"], fe);
  if (!existsSync(dist)) die("前端构建后仍未找到 frontend/dist/index.html");
}

function dirSizeMb(dir) {
  let total = 0;
  const walk = (d) => {
    for (const name of readdirSync(d)) {
      const p = join(d, name);
      const st = statSync(p);
      if (st.isDirectory()) walk(p); else total += st.size;
    }
  };
  try { walk(dir); } catch { /* best-effort */ }
  return total / 1024 / 1024;
}

function runtimeTarget() {
  const platform = { win32: "win32", linux: "linux", darwin: "darwin" }[process.platform];
  const arch = { x64: "x64", arm64: "arm64", arm: "armhf" }[process.arch];
  if (!platform || !arch) die(`不支持的 Runtime 平台：${process.platform}-${process.arch}`);
  return `${platform}-${arch}`;
}

function selectedRuntimeTarget() {
  const host = runtimeTarget();
  const selected = opts.target || host;
  if (!/^(?:win32|linux|darwin)-(?:x64|arm64|armhf)$/.test(selected)) {
    die(`不支持的桌面目标：${selected}`);
  }
  // The desktop contains a jlink image. Cross-architecture JRE construction is deliberately
  // not guessed; release CI uses a native runner for each macOS architecture.
  if (selected !== host) {
    die(`桌面构建必须在目标架构原生执行：当前 ${host}，请求 ${selected}`);
  }
  return selected;
}

function buildRuntimeBundle(jar) {
  const pkg = JSON.parse(readFileSync(join(ROOT, "package.json"), "utf8"));
  const target = selectedRuntimeTarget();
  const output = join(ROOT, "dist", "runtime-bundles", `${pkg.version}-${target}`);
  const bundleArgs = [join(ROOT, "scripts", "build", "runtime-bundle.mjs"), "--jar", jar,
    "--target", target, "--output", output, "--with-native-cli", "--stage-tauri"];
  if (opts.skipJre) {
    if (!existsSync(join(JRE_DIR, "bin"))) die("--skip-jre 但 resources/jre 不存在");
    bundleArgs.push("--jre-dir", JRE_DIR);
  }
  if (opts.jreModules) bundleArgs.push("--jre-modules", opts.jreModules);
  log("构建并校验 canonical Runtime Bundle");
  run(process.execPath, bundleArgs);
}

// Stage the built SPA for the backend's explicit attach/browser fallback. Normal desktop
// windows load frontend/dist directly from Tauri's trusted asset origin.
function stageUi() {
  const dist = join(ROOT, "frontend", "dist");
  if (!existsSync(join(dist, "index.html"))) {
    die("未找到 frontend/dist/index.html，无法暂存 Web UI（去掉 --skip-build 让脚本构建前端）。");
  }
  if (existsSync(RES_UI)) rmSync(RES_UI, { recursive: true, force: true });
  mkdirSync(RES_DIR, { recursive: true });
  cpSync(dist, RES_UI, { recursive: true });
  ok(`暂存 Web UI → resources/ui (${(dirSizeMb(RES_UI)).toFixed(1)} MB)`);
}

function syncVersion() {
  const pkg = JSON.parse(readFileSync(join(ROOT, "package.json"), "utf8"));
  const confPath = join(SRC_TAURI, "tauri.conf.json");
  const conf = JSON.parse(readFileSync(confPath, "utf8"));
  if (conf.version !== pkg.version) {
    conf.version = pkg.version;
    writeFileSync(confPath, JSON.stringify(conf, null, 2) + "\n", "utf8");
    ok(`tauri.conf.json 版本同步为 ${pkg.version}`);
  }
  const cargoPath = join(SRC_TAURI, "Cargo.toml");
  const cargo = readFileSync(cargoPath, "utf8");
  const syncedCargo = cargo.replace(
    /(\[package\][\s\S]*?\nversion\s*=\s*)"[^"]+"/,
    `$1"${pkg.version}"`,
  );
  if (syncedCargo === cargo && !cargo.includes(`version = "${pkg.version}"`)) {
    die("无法同步 src-tauri/Cargo.toml package.version");
  }
  if (syncedCargo !== cargo) {
    writeFileSync(cargoPath, syncedCargo, "utf8");
    ok(`Cargo.toml 版本同步为 ${pkg.version}`);
  }
}

function ensureIcons(tools) {
  const iconsDir = join(SRC_TAURI, "icons");
  const required = join(iconsDir, isMac ? "icon.icns" : "icon.ico");
  const source = join(iconsDir, "source.png");
  const stale = existsSync(source) && (!existsSync(required) || statSync(source).mtimeMs > statSync(required).mtimeMs);
  if (stale && tools.tauri) {
    log("生成图标 (tauri icon icons/source.png)");
    run(tools.tauri.cmd, [...tools.tauri.pre, "icon", source]);
    return;
  }
  if (existsSync(required)) return;
  warn(`缺少 ${required} —— tauri build 需要目标平台图标。`);
  warn("放一张 ≥1024×1024 的 PNG 到 src-tauri/icons/source.png 后重跑（脚本会自动生成）。");
}

// ── main ──────────────────────────────────────────────
console.log(`${C.bold}┌─ harnex desktop shell (Tauri) ────${C.reset}`);
console.log(`${C.bold}│${C.reset}  mode : ${opts.check ? "CHECK" : opts.dev ? "DEV" : (opts.debug ? "BUILD(debug)" : "BUILD(release)")}`);
console.log(`${C.bold}└──────────────────────────────────────────${C.reset}`);

const tools = checkToolchain();

if (opts.check) {
  const blockers = [!tools.cargoOk && "cargo", !tools.tauri && "tauri CLI", !tools.mavenOk && "Maven",
    !(opts.skipJre || tools.jlink) && "jlink", isMac && !tools.macToolsOk && "Xcode CLI tools",
    opts.macosRelease && !tools.notarytoolOk && "notarytool"].filter(Boolean);
  if (blockers.length) die(`工具链缺失：${blockers.join(", ")}。见上方提示。`);
  ok("工具链就绪，可执行 npm run build:desktop。");
  process.exit(0);
}

if (!tools.cargoOk) die("缺少 cargo/Rust（https://rustup.rs）。仅检查工具链用 --check。");
if (!tools.tauri)   die("缺少 Tauri CLI v2（cargo install tauri-cli --version ^2 或 npm i -g @tauri-apps/cli@^2）。");
if (!tools.mavenOk && !opts.skipBuild) die("缺少 Maven 3.9+（桌面 Runtime 需要构建后端 jar）。");
if (isMac && !tools.macToolsOk) die("缺少 Xcode Command Line Tools/codesign。");
selectedRuntimeTarget();
if (opts.macosRelease && !isMac) die("--macos-release 只能在 macOS 上执行");
if (opts.macosRelease) {
  if (!tools.notarytoolOk) die("--macos-release 需要 Xcode notarytool");
  if (!process.env.APPLE_SIGNING_IDENTITY || process.env.APPLE_SIGNING_IDENTITY === "-") {
    die("--macos-release 需要 APPLE_SIGNING_IDENTITY=Developer ID Application: ...");
  }
  const appStoreApi = process.env.APPLE_API_ISSUER && process.env.APPLE_API_KEY
    && (process.env.APPLE_API_KEY_PATH || process.env.API_PRIVATE_KEYS_DIR);
  const appleId = process.env.APPLE_ID && process.env.APPLE_PASSWORD && process.env.APPLE_TEAM_ID;
  if (!appStoreApi && !appleId) {
    die("--macos-release 需要 App Store Connect API 凭据，或 APPLE_ID/APPLE_PASSWORD/APPLE_TEAM_ID");
  }
}
if (opts.updater) {
  for (const key of ["HARNEX_UPDATE_ENDPOINT", "HARNEX_UPDATE_PUBKEY", "TAURI_SIGNING_PRIVATE_KEY"]) {
    if (!process.env[key]) die(`--updater 需要环境变量 ${key}`);
  }
  if (!/^https:\/\//i.test(process.env.HARNEX_UPDATE_ENDPOINT)) {
    die("HARNEX_UPDATE_ENDPOINT 必须是 HTTPS URL");
  }
}

syncVersion();

// BUILD / DEV share the same verified Runtime resource preparation. This makes a clean
// checkout usable on macOS instead of relying on ignored resources left by a prior release.
ensureArtifacts();
const jar = findJar();
if (!jar) die("找不到 backend jar（请去掉 --skip-build 让脚本构建，或先 npm run build）。");
log(`构建 ${selectedRuntimeTarget()} native CLI launcher`);
run(process.execPath, [join(ROOT, "scripts", "build", "native-cli.mjs"), "--target", selectedRuntimeTarget()]);
buildRuntimeBundle(jar);
stageUi();
ensureIcons(tools);

if (opts.dev) {
  log("tauri dev（owned Runtime Domain）");
  run(tools.tauri.cmd, [...tools.tauri.pre, "dev"], ROOT, process.env);
  process.exit(0);
}

log(`tauri build${opts.debug ? " --debug" : ""}${opts.updater ? " (signed updater)" : ""}`);
run(tools.tauri.cmd, [
  ...tools.tauri.pre,
  "build",
  ...(opts.verbose ? ["--verbose"] : []),
  ...(opts.debug ? ["--debug"] : []),
  ...(opts.updater ? ["--config", join(SRC_TAURI, "tauri.updater.conf.json")] : []),
], ROOT);

const bundleDir = join(SRC_TAURI, "target", opts.debug ? "debug" : "release", "bundle");
if (isMac) verifyMacBundles(bundleDir, opts.macosRelease);
ok("桌面安装包构建完成。");
console.log(`产物目录: ${C.bold}${bundleDir}${C.reset}`);

function verifyMacBundles(bundleDir, release) {
  const macosDir = join(bundleDir, "macos");
  const dmgDir = join(bundleDir, "dmg");
  const apps = existsSync(macosDir) ? readdirSync(macosDir).filter(name => name.endsWith(".app")) : [];
  const dmgs = existsSync(dmgDir) ? readdirSync(dmgDir).filter(name => name.endsWith(".dmg")) : [];
  if (!apps.length || !dmgs.length) die("macOS 构建未同时生成 .app 与 .dmg");
  for (const name of apps) {
    const app = join(macosDir, name);
    run("codesign", ["--verify", "--deep", "--strict", "--verbose=2", app]);
    if (release) {
      run("xcrun", ["stapler", "validate", app]);
      run("spctl", ["--assess", "--type", "execute", "--verbose=2", app]);
    }
  }
  if (release) {
    for (const name of dmgs) {
      const dmg = join(dmgDir, name);
      run("xcrun", ["stapler", "validate", dmg]);
      run("spctl", ["--assess", "--type", "open", "--context", "context:primary-signature", "--verbose=2", dmg]);
    }
  }
}
