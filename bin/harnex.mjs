#!/usr/bin/env node
// Harnex Node launcher.
//
// Subcommands:
//   harnex            (no args)  → interactive launcher (pick Web UI or TUI)
//   harnex launch [--webui|--tui]→ same launcher; flags skip the picker
//   harnex tui  [..]             → start the native Ratatui client
//   harnex cli -p "..."          → headless one-shot Local API client
//   harnex serve [..]            → start the HTTP API + web UI on :18081
//   harnex shortcut              → install desktop / start-menu icon
//   harnex stop                  → kill a running instance (uses PID file)
//   harnex status                → check if a server is running, where, since when
//   harnex version               → print versions
//
// Pass any extra args verbatim to the JVM, e.g.
//   harnex cli -p "summarise README.md"
//   harnex serve --server.port=9090
//
// Process management:
// - On SIGINT / SIGTERM / SIGHUP, the launcher forwards the signal to the JVM child and
//   exits when the child exits. On Windows, where signal forwarding is unreliable, we
//   fall back to `taskkill /T /F /PID` for tree-kill.
// - `harnex serve` owns the `standalone-web` Runtime Domain so `harnex stop` can
//   find and kill that process tree without touching another host's Runtime.

import { spawn, spawnSync } from "node:child_process";
import {
  closeSync, existsSync, mkdirSync, openSync, readFileSync, writeFileSync, unlinkSync,
  readdirSync, statSync,
} from "node:fs";
import { homedir, platform } from "node:os";
import { dirname, join, resolve } from "node:path";
import { createRequire } from "node:module";
import { createHash, randomUUID } from "node:crypto";
import { fileURLToPath } from "node:url";
import { connect as netConnect, createServer as createNetServer } from "node:net";
import { cmdLaunch, cmdShortcut, cmdUninstallShortcut, resolveServerPort } from "./lib/launcher.mjs";
import { DEFAULT_WEB_PORT } from './lib/ports.mjs';
import { appendConfigFile } from "./lib/config-inject.mjs";
import { ensureLoopbackNoProxy } from "./lib/no-proxy.mjs";
import { nativeConnectionCli } from "./lib/remote-runtime.mjs";
import { projectDataDirectory } from "./lib/project-data.mjs";
import { workspaceIdentity } from "./lib/workspace-identity.mjs";
import { tarExecutable } from "./lib/archive-tools.mjs";
import {
  attachTrustedRuntime,
  atomicWriteJsonSync, claimDomainOwnerSync, cleanupStaleDomainSync, ensurePrivateDirectory, isPidAlive, publishDomainManifestSync,
  readJsonSync, releaseDomainOwnerSync, resolveRuntimeDomain, unpublishDomainManifestSync, validateDomainOwner,
} from "./lib/runtime-domain.mjs";
import { attachedStatus, cancelAttachedRun, openAttachedApp, runAttachedOneShot } from "./lib/attached-cli.mjs";

const __filename = fileURLToPath(import.meta.url);
const __dirname  = dirname(__filename);
const REPO_ROOT  = resolve(__dirname, "..");
const STATE_DIR  = resolve((process.env.HARNEX_USER_DATA_DIR || "").trim() || join(homedir(), ".harnex"));
const parsedCommandLine = parseGlobalOptions(process.argv.slice(2));
const DOMAIN_MANIFEST = parsedCommandLine.domainManifest
  || (process.env.HARNEX_DOMAIN_MANIFEST || "").trim() || null;
const ENTRY_COMMAND = parsedCommandLine.args[0] || "launch";
const CLI_OWNER_COMMAND = ENTRY_COMMAND === "cli" || ENTRY_COMMAND === "tui";
// Exact attached Clients must not create or harden an unrelated Runtime Domain directory.
// Owning commands are the only paths that dereference DIRECT_DOMAIN.
const DIRECT_DOMAIN = DOMAIN_MANIFEST || ENTRY_COMMAND === 'remote' ? null : resolveRuntimeDomain(
  CLI_OWNER_COMMAND ? `cli-${process.pid}` : "standalone-web",
  CLI_OWNER_COMMAND ? "cli" : "standalone",
  { create: false },
);
const OWNER_INSTANCE_ID = randomUUID();
const IS_WIN     = platform() === "win32";
const require = createRequire(import.meta.url);
ensureLoopbackNoProxy();

function parseGlobalOptions(input) {
  const args = [];
  let domainManifest = null;
  for (let index = 0; index < input.length; index++) {
    const arg = input[index];
    if (arg === "--domain-manifest") {
      if (!input[index + 1] || input[index + 1].startsWith("--")) {
        die("--domain-manifest requires a file path", 2);
      }
      domainManifest = resolve(input[++index]);
    } else if (arg.startsWith("--domain-manifest=")) {
      const value = arg.slice("--domain-manifest=".length);
      if (!value.trim()) die("--domain-manifest requires a file path", 2);
      domainManifest = resolve(value);
    } else {
      args.push(arg);
    }
  }
  return { args, domainManifest };
}

function withAttachedRuntime(action) {
  if (!DOMAIN_MANIFEST) throw new Error("no Runtime Domain manifest was supplied");
  return attachTrustedRuntime(DOMAIN_MANIFEST)
    .then(action)
    .catch(error => die(`attach refused: ${error instanceof Error ? error.message : error}`));
}

function ensureStateDir() {
  ensurePrivateDirectory(DIRECT_DOMAIN.dir);
}

function findJar() {
  const dir = join(REPO_ROOT, "backend", "target");
  if (!existsSync(dir)) return null;
  const candidates = readdirSync(dir)
    .filter(n => n.startsWith("harnex-") && n.endsWith(".jar") && !n.endsWith("-sources.jar") && !n.endsWith("-javadoc.jar"))
    .map(n => join(dir, n));
  if (candidates.length === 0) return null;
  // Prefer the highest semver-looking version embedded in the filename rather than mtime,
  // which can pick the wrong jar after a `touch` or a partial rebuild that left an old
  // SNAPSHOT newer than the release artifact. Falls back to mtime when versions tie.
  const VERSION_RE = /harnex-(\d+(?:\.\d+)*(?:[-+][\w.]+)?)\.jar$/;
  function vkey(p) {
    const m = VERSION_RE.exec(p);
    if (!m) return [];
    return m[1].split(/[-+.]/).map(s => /^\d+$/.test(s) ? Number(s) : s);
  }
  function cmpVer(a, b) {
    for (let i = 0; i < Math.max(a.length, b.length); i++) {
      const x = a[i] ?? 0, y = b[i] ?? 0;
      if (typeof x === typeof y) {
        if (x < y) return -1;
        if (x > y) return 1;
      } else {
        // Numeric segments outrank pre-release tags (1.0 > 1.0-SNAPSHOT).
        return typeof x === "number" ? 1 : -1;
      }
    }
    return 0;
  }
  candidates.sort((a, b) => {
    const c = cmpVer(vkey(b), vkey(a));
    if (c !== 0) return c;
    return statSync(b).mtimeMs - statSync(a).mtimeMs;
  });
  return candidates[0] || null;
}

function findJava() {
  // Honour JAVA_HOME first, then fall through to whatever is on PATH.
  if (process.env.JAVA_HOME) {
    const exe = IS_WIN ? "java.exe" : "java";
    const cand = join(process.env.JAVA_HOME, "bin", exe);
    if (existsSync(cand)) return cand;
  }
  return "java";
}

/**
 * Verify the resolved java binary actually runs and report a major version. Returns
 * { ok, major, raw } so callers can fail-fast with an actionable hint instead of letting
 * the JVM spawn die deep inside class loading. Harnex requires JDK 17+.
 */
function probeJava(java) {
  try {
    const r = spawnSync(java, ["-version"], { stdio: "pipe", timeout: 5000 });
    if (r.status !== 0 && !(r.stderr && r.stderr.length)) {
      return { ok: false, major: 0, raw: "" };
    }
    const raw = ((r.stderr || r.stdout || "")).toString();
    // Examples: "openjdk version \"17.0.10\" 2024-01-16" or "java version \"1.8.0_321\""
    const m = raw.match(/version "(\d+)(?:\.(\d+))?[^"]*"/);
    if (!m) return { ok: true, major: 0, raw };
    let major = Number(m[1]);
    if (major === 1 && m[2]) major = Number(m[2]); // 1.8 → 8
    return { ok: true, major, raw };
  } catch {
    return { ok: false, major: 0, raw: "" };
  }
}

/**
 * Probe a TCP port for availability on 127.0.0.1. Returns true if free, false if in use.
 * Uses a 500 ms connect attempt — fast enough to not annoy users on cold starts.
 */
async function probePortFree(port) {
  return await new Promise((resolve) => {
    const sock = netConnect({ host: "127.0.0.1", port });
    let done = false;
    const finish = (free) => { if (done) return; done = true; try { sock.destroy(); } catch {} resolve(free); };
    sock.setTimeout(500);
    sock.once("connect",  () => finish(false));      // someone is listening
    sock.once("error",    () => finish(true));        // ECONNREFUSED → port free
    sock.once("timeout",  () => finish(true));
  });
}

/**
 * Pre-flight checks before launching the JVM. Surfaces actionable Chinese + English errors
 * for the most common first-run footguns (missing/old Java, busy port, missing data dir
 * permissions). Throwing exits with code 1 so the launcher visibly stops instead of letting
 * the user stare at a confusing JVM stack trace.
 *
 * @param {{requirePort?: number}} opts
 */
async function preflight(opts = {}) {
  const java = findJava();
  const probe = probeJava(java);
  if (!probe.ok) {
    die(
      "Java not found on PATH (or JAVA_HOME points at a non-executable binary).\n" +
      "       未找到 Java：请安装 JDK 17 或更高版本，或设置 JAVA_HOME。\n" +
      "       Install JDK 17+ from https://adoptium.net/ and re-run."
    );
  }
  if (probe.major > 0 && probe.major < 17) {
    die(
      `Java ${probe.major} detected — Harnex requires JDK 17+.\n` +
      `       检测到 Java ${probe.major}，需 JDK 17 及以上。请升级后重试。\n` +
      `       (binary: ${java})`
    );
  }
  if (opts.requirePort) {
    const free = await probePortFree(opts.requirePort);
    if (!free) {
      die(
        `Port ${opts.requirePort} is already in use.\n` +
        `       端口 ${opts.requirePort} 已被占用。\n` +
        `       Run \`harnex status\` to check, \`harnex stop\` to kill the previous instance,\n` +
        `       or pass --server.port=NNNN to choose a different port.`
      );
    }
  }
  // Data directory writable? Best-effort touch; we don't fail hard if it doesn't exist yet
  // because Spring Boot will create it on first write. We only warn on permission denial.
  try {
    ensureStateDir();
    const probeFile = join(STATE_DIR, ".perm-probe");
    writeFileSync(probeFile, String(Date.now()), "utf8");
    try { unlinkSync(probeFile); } catch { /* best-effort */ }
  } catch (e) {
    process.stderr.write(`\u001b[33mwarning: cannot write to ${STATE_DIR}: ${e.message}\u001b[0m\n`);
  }
}

function die(msg, code = 1) {
  process.stderr.write(`\u001b[31mharnex: ${msg}\u001b[0m\n`);
  process.exit(code);
}

function info(msg) {
  process.stdout.write(`\u001b[2m${msg}\u001b[0m\n`);
}

function spawnJvm(extraArgs, { writePid = false, port = null, exitProcess = true, stdio = "inherit" } = {}) {
  // Honour HARNEX_JAR FIRST so an operator can run from a directory without a built
  // backend/target/ tree (the documented escape hatch in README). Falling through to
  // findJar() previously die()'d before we ever read the env var, defeating the override.
  let jarPath = process.env.HARNEX_JAR;
  if (jarPath) {
    if (!existsSync(jarPath)) {
      die(`HARNEX_JAR is set to "${jarPath}" but that file does not exist.`);
    }
  } else {
    const jar = findJar();
    if (!jar) {
      die(
        "could not locate the backend jar under backend/target/.\n" +
        "       Build it first:  cd backend && mvn clean -DskipTests package\n" +
        "       Or set HARNEX_JAR=/path/to/harnex-x.y.z.jar"
      );
    }
    jarPath = jar;
  }
  const java = findJava();
  const args = ["-jar", jarPath, ...extraArgs,
    `--harnex.runtime-domain-id=${DIRECT_DOMAIN.id}`,
    `--harnex.runtime-domain-dir=${DIRECT_DOMAIN.dir}`,
    `--harnex.runtime-owner-kind=${DIRECT_DOMAIN.ownerKind}`,
  ];
  if (CLI_OWNER_COMMAND && !extraArgs.some(arg => arg.startsWith('--server.address'))) args.push('--server.address=127.0.0.1');
  // Auto-pick user-level config at ~/.harnex/agent-config.yml (and/or application.yml).
  // Appended (not replaced) into SPRING_CONFIG_ADDITIONAL_LOCATION so jar-internal defaults
  // still load. Most settings may be written as a diff; a declared harnex.providers Map is
  // intentionally owned in full by this higher-priority document. Skipped silently when the
  // file is absent or when the operator already set the env var themselves.
  const env = { ...process.env };
  const { root: workspaceRoot, id: runtimeWorkspaceId } = workspaceIdentity(process.cwd());
  const instanceId = randomUUID();
  env.HARNEX_WORKSPACE = workspaceRoot;
  env.HARNEX_SCOPE_ID = runtimeWorkspaceId;
  env.HARNEX_INSTANCE_ID = instanceId;
  env.HARNEX_USER_DATA_DIR = STATE_DIR;
  if (!env.HARNEX_DATA_DIR && !extraArgs.some(arg => arg.startsWith('--harnex.data-dir'))) {
    args.push(`--harnex.data-dir=${projectDataDirectory(workspaceRoot, STATE_DIR)}`);
  }
  env.HARNEX_RUNTIME_DOMAIN_ID = DIRECT_DOMAIN.id;
  env.HARNEX_RUNTIME_DOMAIN_DIR = DIRECT_DOMAIN.dir;
  env.HARNEX_RUNTIME_OWNER_KIND = DIRECT_DOMAIN.ownerKind;
  // Append the SPECIFIC file URL, not the directory: Spring resolves a directory location
  // (trailing /) by scanning for application.* only, so a co-located agent-config.yml would be
  // silently ignored. Each user-level override is imported by its exact path instead.
  for (const name of ["agent-config.yml", "application.yml"]) {
    const f = join(STATE_DIR, name);
    if (!existsSync(f)) continue;
    if (appendConfigFile(env, f)) {
      process.stdout.write(`\u001b[2m[harnex] loading user config from ${f}\u001b[0m\n`);
    }
  }
  claimDomainOwnerSync(DIRECT_DOMAIN, OWNER_INSTANCE_ID);
  unpublishDomainManifestSync(DIRECT_DOMAIN);
  let child;
  try {
    child = spawn(java, args, {
      stdio,
      env,
      // A POSIX process group gives stop/exit a reliable Runtime tree boundary. Signal
      // forwarding below targets that group; Windows retains taskkill /T semantics.
      detached: !IS_WIN,
    });
  } catch (error) {
    writeDirectDomainState("FAILED", { runtimePid: null });
    releaseDomainOwnerSync(DIRECT_DOMAIN, OWNER_INSTANCE_ID);
    throw error;
  }
  writeDirectDomainState("OPENING", {
    runtimePid: child.pid || null,
    canonicalRoot: workspaceRoot,
    workspaceId: runtimeWorkspaceId,
    instanceId,
    endpoint: port ? `http://127.0.0.1:${port}` : null,
  });

  const ready = writePid && port
    ? publishWhenReady(child, port, workspaceRoot, runtimeWorkspaceId, instanceId)
    : Promise.resolve();

  // Forward signals to the JVM child. On Windows, signals are flaky, so we taskkill the tree.
  const forward = (sig) => {
    if (!child || child.killed || !child.pid) return;
    unpublishDomainManifestSync(DIRECT_DOMAIN);
    writeDirectDomainState("DRAINING", { runtimePid: child.pid || null });
    if (IS_WIN) {
      try {
        spawnSync("taskkill", ["/PID", String(child.pid), "/T", "/F"], { stdio: "ignore" });
      } catch { /* ignore */ }
    } else {
      try { process.kill(-child.pid, sig); }
      catch { try { child.kill(sig); } catch { /* ignore */ } }
    }
  };
  ["SIGINT", "SIGTERM", "SIGHUP"].forEach(sig => process.on(sig, () => forward(sig)));

  child.on("exit", (code, signal) => {
    unpublishDomainManifestSync(DIRECT_DOMAIN);
    writeDirectDomainState("CLOSED", { runtimePid: null, instanceId: null, endpoint: null });
    releaseDomainOwnerSync(DIRECT_DOMAIN, OWNER_INSTANCE_ID);
    if (exitProcess) process.exit(typeof code === "number" ? code : (signal ? 128 : 1));
  });
  child.on("error", () => {
    writeDirectDomainState("FAILED", { runtimePid: child.pid || null });
    releaseDomainOwnerSync(DIRECT_DOMAIN, OWNER_INSTANCE_ID);
  });
  return { child, ready, stop: () => forward("SIGTERM") };
}

function writeDirectDomainState(lifecycle, detail = {}) {
  try {
    atomicWriteJsonSync(DIRECT_DOMAIN.stateFile, {
      schemaVersion: 1,
      runtimeDomainId: DIRECT_DOMAIN.id,
      ownerKind: DIRECT_DOMAIN.ownerKind,
      lifecycle,
      updatedAt: new Date().toISOString(),
      ...detail,
    });
  } catch (error) {
    process.stderr.write(`harnex: cannot persist Runtime Domain state (${error.message})\n`);
  }
}

async function publishWhenReady(child, port, workspaceRoot, runtimeWorkspaceId, instanceId) {
  const endpoint = `http://127.0.0.1:${port}`;
  const deadline = Date.now() + 120_000;
  let spawnError;
  const recordSpawnError = error => { spawnError = error; };
  child.once("error", recordSpawnError);
  try {
    while (Date.now() < deadline && child.exitCode === null && !spawnError) {
      try {
        const response = await fetch(`${endpoint}/api/v1/protocol`, { signal: AbortSignal.timeout(1000) });
        const identityMatches = response.headers.get("x-harnex-scope-id") === runtimeWorkspaceId
          && response.headers.get("x-harnex-instance-id") === instanceId;
        if (identityMatches && (response.ok || response.status === 401)) {
          const hash = createHash("sha256");
          for (const name of ["agent-config.yml", "application.yml"]) {
            const file = join(STATE_DIR, name);
            if (existsSync(file)) hash.update(name).update(readFileSync(file));
          }
          publishDomainManifestSync(DIRECT_DOMAIN, {
            schemaVersion: 3, runtimeProfile: 'PROJECT', runtimeDomainId: DIRECT_DOMAIN.id, ownerKind: DIRECT_DOMAIN.ownerKind,
            ownerPid: process.pid, ownerInstanceId: OWNER_INSTANCE_ID, runtimePid: child.pid,
            canonicalRoot: workspaceRoot, scopeId: runtimeWorkspaceId, instanceId, endpoint,
            lifecycle: "READY", protocolVersion: "1.0", bootTokenFile: DIRECT_DOMAIN.bootTokenFile,
            configRevision: hash.digest("hex"), publishedAt: new Date().toISOString(),
          });
          writeDirectDomainState("READY", {
            runtimePid: child.pid, canonicalRoot: workspaceRoot, workspaceId: runtimeWorkspaceId,
            instanceId, endpoint,
          });
          return;
        }
      } catch {}
      await new Promise(done => setTimeout(done, 400));
    }
  } finally {
    child.removeListener("error", recordSpawnError);
  }
  if (spawnError) throw spawnError;
  throw new Error(`Runtime did not become ready at ${endpoint}`);
}

function cmdCli(rest) {
  const oneShot = rest.some(arg => arg === '-p' || arg === '--prompt' || arg.startsWith('-p=') || arg.startsWith('--prompt='));
  const prompt = optionValue(rest, '-p') ?? optionValue(rest, '--prompt');
  if (oneShot && (prompt == null || prompt === '')) die('-p/--prompt requires a value', 2);
  const runInput = optionValue(rest, "--run") || optionValue(rest, "--steer") || optionValue(rest, "--follow-up");
  if (!oneShot && !runInput) return cmdTui(rest);
  if (rest.some(arg => arg === '--remote' || arg.startsWith('--remote='))) return cmdRemoteNative('cli', rest);
  if (DOMAIN_MANIFEST) {
    void withAttachedRuntime(async session => {
      process.exitCode = await runAttachedOneShot(session, rest);
    });
    return;
  }
  if (optionValue(rest, "--run") || optionValue(rest, "--steer") || optionValue(rest, "--follow-up")) {
    die("active Run input requires an exact --domain-manifest");
  }
  void runStandaloneCli(rest).catch(error => die(error instanceof Error ? error.message : String(error)));
}

function nativeRuntimeTarget() {
  const os = { win32: "win32", darwin: "darwin", linux: "linux" }[process.platform];
  const arch = { x64: "x64", arm64: "arm64", arm: "armhf" }[process.arch];
  return os && arch ? `${os}-${arch}` : null;
}

function findNativeCli() {
  const executable = IS_WIN ? "harnex.exe" : "harnex";
  const target = nativeRuntimeTarget();
  const packageVersion = JSON.parse(readFileSync(join(REPO_ROOT, "package.json"), "utf8")).version;
  const configured = (process.env.HARNEX_NATIVE_CLI || "").trim();
  const bundle = (process.env.HARNEX_RUNTIME_BUNDLE || "").trim();
  const candidates = [
    configured || null,
    bundle ? join(resolve(bundle), "bin", executable) : null,
    optionalNativeCli(target),
    target ? join(REPO_ROOT, "dist", "native", target, executable) : null,
    target ? join(REPO_ROOT, "dist", "runtime-bundles", `${packageVersion}-${target}`, "bin", executable) : null,
  ].filter(Boolean);
  return candidates.find(candidate => existsSync(candidate) && statSync(candidate).isFile()) || null;
}

function optionalNativeCli(target) {
  const names = {
    "darwin-arm64": "@harnex/cli-darwin-arm64",
    "darwin-x64": "@harnex/cli-darwin-x64",
    "win32-x64": "@harnex/cli-win32-x64",
    "linux-x64": "@harnex/cli-linux-x64",
    "linux-arm64": "@harnex/cli-linux-arm64",
  };
  const name = names[target];
  if (!name) return null;
  try { return join(dirname(require.resolve(`${name}/package.json`)), "bin", IS_WIN ? "harnex.exe" : "harnex"); }
  catch { return null; }
}

function hasAdjacentRuntimeBundle(native) {
  return existsSync(join(dirname(dirname(native)), "runtime-bundle.json"));
}

function cmdTui(rest) {
  if (rest.some(arg => arg === '--remote' || arg.startsWith('--remote='))) return cmdRemoteNative('tui', rest);
  const native = findNativeCli();
  if (!native) {
    die("native TUI is unavailable; install the platform's native CLI/Runtime Bundle, run npm run build:native-cli in a source checkout, or set HARNEX_NATIVE_CLI. Headless execution remains available with harnex cli -p <prompt>.");
  }
  if (!DOMAIN_MANIFEST && !hasAdjacentRuntimeBundle(native)) {
    void runSystemRuntimeTui(native, rest).catch(error => die(error instanceof Error ? error.message : String(error)));
    return;
  }
  const args = [];
  if (DOMAIN_MANIFEST) args.push("--domain-manifest", DOMAIN_MANIFEST);
  args.push("tui", ...rest);
  const child = spawn(native, args, { stdio: "inherit", cwd: process.cwd(), env: process.env });
  child.on("error", error => die(`native TUI launch failed: ${error.message}`));
  child.on("exit", (code, signal) => process.exit(typeof code === "number" ? code : (signal ? 128 : 1)));
}

function cmdRemoteNative(command, rest) {
  if (DOMAIN_MANIFEST && command !== 'remote') die('an attached client cannot switch to another Runtime');
  let binary;
  try { binary = nativeConnectionCli(); } catch (error) { die(error.message); }
  const child = spawn(binary, [command, ...rest], { windowsHide: true, stdio:'inherit',
    env: { ...process.env, HARNEX_JAR: process.env.HARNEX_JAR || findJar(), HARNEX_USER_DATA_DIR: STATE_DIR } });
  child.on('error', error => die(error.message));
  child.on('exit', code => { process.exitCode = code ?? 1; });
}

async function runSystemRuntimeTui(native, rest) {
  await preflight();
  const port = await freeLoopbackPort();
  ensureStateDir();
  const logDir = join(DIRECT_DOMAIN.dir, "logs");
  mkdirSync(logDir, { recursive: true });
  const stdout = openSync(join(logDir, "runtime-out.log"), "a");
  const stderr = openSync(join(logDir, "runtime-err.log"), "a");
  let owned;
  try {
    owned = spawnJvm([
      `--server.port=${port}`, "--server.address=127.0.0.1",
      "--spring.main.web-application-type=servlet",
    ], { writePid: true, port, exitProcess: false, stdio: ["ignore", stdout, stderr] });
    await owned.ready;
  } finally {
    try { closeSync(stdout); } catch {}
    try { closeSync(stderr); } catch {}
  }
  const child = spawn(native, ["--domain-manifest", DIRECT_DOMAIN.manifestFile, "tui", ...rest], {
    stdio: "inherit", cwd: process.cwd(), env: process.env,
  });
  const result = await new Promise((resolvePromise, rejectPromise) => {
    child.once("error", rejectPromise);
    child.once("exit", (code, signal) => resolvePromise({ code, signal }));
  });
  const runtimeExited = new Promise(resolvePromise => owned.child.once("exit", resolvePromise));
  owned.stop();
  await Promise.race([runtimeExited, new Promise(resolvePromise => setTimeout(resolvePromise, 6000))]);
  process.exit(typeof result.code === "number" ? result.code : (result.signal ? 128 : 1));
}

async function runStandaloneCli(rest) {
  await preflight();
  const port = await freeLoopbackPort();
  ensureStateDir();
  const logDir = join(DIRECT_DOMAIN.dir, "logs");
  mkdirSync(logDir, { recursive: true });
  const stdout = openSync(join(logDir, "runtime-out.log"), "a");
  let stderr;
  let owned;
  try {
    stderr = openSync(join(logDir, "runtime-err.log"), "a");
    owned = spawnJvm([
      `--server.port=${port}`, "--server.address=127.0.0.1",
    ], { writePid: true, port, exitProcess: false, stdio: ["ignore", stdout, stderr] });
  } finally {
    closeSync(stdout);
    if (stderr !== undefined) closeSync(stderr);
  }
  const exited = new Promise(resolve => owned.child.once("exit", resolve));
  try {
    await owned.ready;
    const session = await attachTrustedRuntime(DIRECT_DOMAIN.manifestFile);
    process.exitCode = await runAttachedOneShot(session, rest);
  } finally {
    owned.stop();
    await Promise.race([exited, new Promise(resolve => setTimeout(resolve, 10_000))]);
  }
}

function freeLoopbackPort() {
  return new Promise((resolvePort, reject) => {
    const server = createNetServer();
    server.once("error", reject);
    server.listen(0, "127.0.0.1", () => {
      const address = server.address();
      const port = typeof address === "object" && address ? address.port : 0;
      server.close(error => error ? reject(error) : resolvePort(port));
    });
  });
}
function cmdServe(rest) {
  if (DOMAIN_MANIFEST) {
    die("attached Client cannot serve/restart a Runtime; leave the IDE terminal or unset HARNEX_DOMAIN_MANIFEST");
  }
  // The standalone public entrypoint defaults to 18081; explicit CLI/env settings win.
  const port = resolveServerPort(rest);
  preflight({ requirePort: port }).then(() => {
    const owned = spawnJvm(rest, { writePid: true, port });
    void owned.ready.catch(error => {
      process.stderr.write(`harnex: ${error instanceof Error ? error.message : error}\n`);
      owned.stop();
    });
  }).catch(e => die(String(e)));
}

const AGENTS_TEMPLATE = `# AGENTS.md

> Workspace context auto-loaded by Harnex when the agent starts in this directory.
> Keep this short — it is injected into every system prompt (hard cap 16 KB).

## Project

Describe what this project is, the tech stack, and how to run it.

## Conventions

- Prefer X over Y
- Always run \`<test-cmd>\` before declaring done
- Never touch \`/legacy/**\` without explicit approval

## Tools

List any project-specific scripts the agent should know about, e.g.

- \`make verify\` — lint + tests
- \`scripts/deploy.sh\` — deploy to staging (requires confirmation)

## Notes for the agent

Anything else the agent should remember while working here.
`;

/** Placeholder appended to a seeded AGENTS.md so a fresh project still prompts the user
 *  to record the project-specific facts the global template cannot know. Kept short so it
 *  doesn't dominate the token budget if the operator forgets to edit it. */
const AGENTS_PROJECT_PLACEHOLDER = `

## 项目栈 / Tech stack

<!-- e.g. Node 20 + Vue 3 + Vite; Java 17 + Spring Boot 3 -->

## 构建命令 / Build & test

<!-- e.g.
- \`npm ci && npm test\`
- \`mvn clean -DskipTests package\`
-->

## 测试约定 / Test conventions

<!-- e.g. baseline test count, slow tests to exclude -->

## 目录结构 / Layout

<!-- e.g. \`src/\` 源码，\`docs/\` 文档，\`scripts/\` 运维脚本 -->
`;

/** First-time-setup template for {@code ~/.harnex/USER.md}. Mirrors the cross-project
 *  preferences a developer typically wants every agent session to honour. The whole file is
 *  hard-capped at 3500 chars when injected, so keep entries terse. */
const GLOBAL_USER_TEMPLATE = `# USER.md (global)

> Auto-loaded into the system prompt as the cross-project user profile.
> Override per-project by creating \`<dataDir>/memory/USER.md\` in that project.
> Hard cap when injected: 3500 chars.

## Language / Tone

- 回复始终使用中文。
- Concise, technical; avoid filler and apologies.

## Coding preferences

- Prefer the smallest change that solves the problem; reject over-engineering.
- Never add comments / docstrings to code you did not change.

## Safety

- Never push, force-push, or amend published commits without explicit confirmation.
- Never \`rm -rf\` outside the current workspace.
`;

/** Default global \`~/.harnex/AGENTS.md\` written on first \`harnex init --global\`. Holds the
 *  user's PORTABLE workspace conventions (style, safety, default approval mode) so every
 *  fresh project's seeded AGENTS.md inherits them without re-typing. */
const GLOBAL_AGENTS_TEMPLATE = `# AGENTS.md (global)

> Auto-seeded into a project's \`AGENTS.md\` by \`harnex init\` when no project file exists.
> Keep this short — it is injected into every system prompt (hard cap 16 KB).
> Project-specific facts (stack, build, tests) belong in the project's \`AGENTS.md\`, not here.

## Style

- 回复始终使用中文。
- Prefer brief, technical answers.

## Safety defaults

- Destructive shell operations require explicit confirmation.
- Never bypass test suites with \`--no-verify\` / equivalent.

## Default approval mode

- \`ask\` for any \`bash\` / \`file_write\` / \`file_edit\` call until whitelisted.
`;

/** Header inserted at the top of a seeded project AGENTS.md so the operator can see at a glance
 *  where the content came from and that subsequent global edits are NOT auto-synced. */
function seededHeader(srcPath) {
  const stamp = new Date().toISOString().slice(0, 10);
  return `<!-- seeded from ${srcPath} @ ${stamp}; not auto-synced. Edit freely. -->\n\n`;
}

function cmdInit(rest) {
  // The interactive wizard writes deployment configuration; the default command seeds AGENTS.md.
  if (rest.includes("--wizard") || rest.includes("-w") || rest.includes("--interactive")) {
    return cmdInitWizard(rest);
  }
  // `--global` seeds the user-level ~/.harnex/{AGENTS,USER}.md templates (one-time per machine)
  // so subsequent `harnex init` runs in any project pick them up as the seed. Idempotent: never
  // overwrites an existing global file unless --force is passed.
  if (rest.includes("--global") || rest.includes("-g")) {
    return cmdInitGlobal(rest);
  }
  const target = join(process.cwd(), "AGENTS.md");
  const force = rest.includes("--force") || rest.includes("-f");
  if (existsSync(target) && !force) {
    die(`AGENTS.md already exists at ${target}. Pass --force to overwrite.`);
  }
  // Cascade for the SEED (mirrors WorkspaceContext / CoreMemory cascade for the RUNTIME read):
  // prefer the user's portable global template at ~/.harnex/AGENTS.md so cross-project style /
  // safety defaults follow them into every new repo. Falls back to the built-in skeleton when
  // the user has never run `harnex init --global`. In both cases the project-specific placeholder
  // is appended so the operator is prompted to fill in stack / build / tests / layout.
  const globalAgents = join(homedir(), ".harnex", "AGENTS.md");
  let body;
  let source;
  if (existsSync(globalAgents)) {
    body = seededHeader(globalAgents) + readFileSync(globalAgents, "utf8") + AGENTS_PROJECT_PLACEHOLDER;
    source = `~/.harnex/AGENTS.md (global seed)`;
  } else {
    body = AGENTS_TEMPLATE;
    source = "built-in template";
  }
  writeFileSync(target, body, "utf8");
  info(`Wrote ${target}  (source: ${source})`);
  // Hint the operator about the second half of the memory model when they have not yet adopted
  // the global layer. Silent on machines that already have it set up.
  const globalUser = join(homedir(), ".harnex", "USER.md");
  if (!existsSync(globalAgents) || !existsSync(globalUser)) {
    info("Tip: run `harnex init --global` to seed cross-project ~/.harnex/{AGENTS,USER}.md (one-time per machine).");
  }
  info("Restart 'harnex serve' / 'harnex cli' to pick it up, or POST /api/v1/workspace/reload.");
  info("Tip: run `harnex init --wizard` for an interactive .env generator.");
}

// ---------------------------------------------------------------------------
// `harnex init --global` — seed the user-level memory templates
//
// Writes ~/.harnex/AGENTS.md and ~/.harnex/USER.md from built-in templates if absent.
// Idempotent: a missing file is created, an existing file is preserved unless --force.
// This is the "set my preferences once" step that mirrors Claude Code's
// `~/.claude/CLAUDE.md` and Codex's `~/.codex/AGENTS.md`. After running this, every
// `harnex init` in a new project inherits the global AGENTS.md as a seed, and the running
// agent picks up ~/.harnex/USER.md automatically via the CoreMemory cascade.
// ---------------------------------------------------------------------------
function cmdInitGlobal(rest) {
  const force = rest.includes("--force") || rest.includes("-f");
  const dir = join(homedir(), ".harnex");
  if (!existsSync(dir)) mkdirSync(dir, { recursive: true });
  const files = [
    { path: join(dir, "AGENTS.md"), body: GLOBAL_AGENTS_TEMPLATE, label: "global AGENTS.md" },
    { path: join(dir, "USER.md"),   body: GLOBAL_USER_TEMPLATE,   label: "global USER.md"   },
  ];
  let wrote = 0;
  for (const f of files) {
    if (existsSync(f.path) && !force) {
      info(`Skip: ${f.path} already exists (pass --force to overwrite).`);
      continue;
    }
    writeFileSync(f.path, f.body, "utf8");
    info(`Wrote ${f.path}  (${f.label})`);
    wrote++;
  }
  if (wrote === 0) {
    info("No files written. Edit the existing ones directly, or pass --force to overwrite.");
  } else {
    info("Edit ~/.harnex/USER.md to record your standing preferences (language, style, safety).");
    info("Edit ~/.harnex/AGENTS.md to record cross-project workspace conventions.");
    info("Run `harnex init` inside a project to seed its AGENTS.md from the global one.");
  }
}

// ---------------------------------------------------------------------------
// `harnex init --wizard` — interactive .env generator (C3 in 01 §九)
// ---------------------------------------------------------------------------
async function cmdInitWizard(rest) {
  const force = rest.includes("--force") || rest.includes("-f");
  const target = join(process.cwd(), ".env");
  if (existsSync(target) && !force) {
    die(`${target} already exists. Pass --force to overwrite.`);
  }
  const readline = await import("node:readline/promises");
  const rl = readline.createInterface({ input: process.stdin, output: process.stdout });
  const ask = async (q, def) => {
    const prompt = def ? `${q} [${def}]: ` : `${q}: `;
    const ans = (await rl.question(prompt)).trim();
    return ans || def || "";
  };
  process.stdout.write(
    "\nHarnex — interactive setup wizard\n" +
    "—".repeat(48) + "\n" +
    "Answer 5 questions to generate a tailored `.env`. Press Enter to accept the default.\n\n");
  const team = await ask("1) Team / deployment name", "ops-team");
  const im = (await ask("2) Primary IM platform (none / feishu / dingtalk / wecom / webhook)", "none"))
    .toLowerCase();
  const provider = (await ask("3) Primary LLM provider key env-var", "OPENAI_API_KEY")).toUpperCase();
  const providerKey = await ask(`   Paste the value for ${provider} (left empty if you'll set it later)`, "");
  const bindAddress = await ask("4) Host bind address (keep loopback; use a reverse proxy for remote access)",
    "127.0.0.1");
  const sandbox = (await ask("5) Command environment (host / docker)", "host")).toLowerCase();
  if (!['host', 'docker'].includes(sandbox)) die('command environment must be host or docker');
  await rl.close();

  const lines = [
    `# Harnex .env — generated by 'harnex init --wizard' for team: ${team}`,
    `# Generated at: ${new Date().toISOString()}`,
    "",
    "# ---------- LLM provider ----------",
    `${provider}=${providerKey}`,
    "OPENAI_BASE_URL=https://api.openai.com/v1",
    "OPENAI_DEFAULT_MODEL=gpt-4o-mini",
    "",
    "# ---------- Local GUI access boundary ----------",
    "# The GUI is accountless. Put authenticated HTTPS/SSO in a reverse proxy for remote access.",
    "HARNEX_AUTH_ENABLED=false",
    `HARNEX_BIND_ADDRESS=${bindAddress}`,
    "",
    "# ---------- Sandbox ----------",
    `HARNEX_EXECUTION_ENVIRONMENT=${sandbox}`,
  ];
  if (im && im !== "none") {
    lines.push("",  "# ---------- IM gateway ----------");
    if (im === "feishu") {
      lines.push("HARNEX_FEISHU_SECRET=", "HARNEX_FEISHU_VTOKEN=");
    } else if (im === "dingtalk") {
      lines.push("HARNEX_DINGTALK_SECRET=");
    } else if (im === "wecom") {
      lines.push("HARNEX_WECOM_TOKEN=");
    } else if (im === "webhook") {
      lines.push("HARNEX_GATEWAY_SECRET=");
    }
    lines.push("# Add your senders to `harnex.gateway.allowed-senders` in agent-config.yml.");
  }
  lines.push("",
    "# ---------- Logging ----------",
    "HARNEX_LOG_DIR=/app/data/logs",
    "HARNEX_LOG_RETENTION_DAYS=14",
    "");
  writeFileSync(target, lines.join("\n"), "utf8");
  info(`Wrote ${target}`);
  info("Next:");
  info("  1) cd " + process.cwd());
  info("  2) docker compose up -d --build");
  info("  3) harnex doctor          # red/green deployment health check");
  if (im && im !== "none") {
    info(`  4) Edit agent-config.yml → harnex.gateway.allowed-senders to whitelist ${im} senders.`);
  }
}

function cmdStop(rest = []) {
  if (DOMAIN_MANIFEST) {
    const runId = optionValue(rest, "--run");
    if (!runId) die("attached stop cancels a Run only; pass --run <runId>");
    void withAttachedRuntime(async session => {
      const result = await cancelAttachedRun(session, runId);
      info(`run ${runId}: ${result.cancelled ? "cancelled" : result.status || "not running"}`);
    });
    return;
  }
  if (!existsSync(DIRECT_DOMAIN.ownerFile)) {
    info("runtime domain " + DIRECT_DOMAIN.id + " is not running.");
    return;
  }
  let owner;
  try { owner = validateDomainOwner(readJsonSync(DIRECT_DOMAIN.ownerFile, {}), DIRECT_DOMAIN); }
  catch (error) { die(`refusing to stop an invalid Runtime owner lease: ${error.message}`); }
  const pid = Number(owner.ownerPid);
  info(`stopping harnex (pid ${pid})…`);
  // Verify the PID actually points at a live process before we do anything destructive.
  let alive = isPidAlive(pid);
  if (!alive) {
    if (Date.now() - Date.parse(owner.startedAt) < 2000) {
      info("runtime owner lease is still initializing; retry.");
      return;
    }
    info("process is not running; cleaning stale domain state.");
    try { unlinkSync(DIRECT_DOMAIN.ownerFile); } catch { /* best-effort */ }
    cleanupStaleDomainSync(DIRECT_DOMAIN);
    return;
  }
  try {
    if (IS_WIN) {
      const r = spawnSync("taskkill", ["/PID", String(pid), "/T", "/F"], { stdio: "inherit" });
      if (r.status !== 0) die("taskkill failed (process may already be dead)");
    } else {
      process.kill(pid, "SIGTERM");
      // Give it 5 seconds; if still alive escalate. Block synchronously — this is a CLI and
      // we want the script to exit only after we know the process is gone (or escalated).
      const start = Date.now();
      while (Date.now() - start < 5000) {
        try { process.kill(pid, 0); } catch { alive = false; break; }
        spawnSync("sleep", ["0.1"]);
      }
      try { process.kill(pid, 0); process.kill(pid, "SIGKILL"); } catch { /* gone */ }
    }
  } catch (e) {
    die("could not signal pid " + pid + ": " + e.message);
  }
  // Only remove the PID file once we have evidence the process is no longer running.
  // (For Windows, taskkill /F is synchronous; for POSIX we waited above.) This avoids a
  // window where a stale PID file says "down" but the JVM is still draining.
  const stillUp = isPidAlive(pid);
  if (!stillUp) {
    try { unlinkSync(DIRECT_DOMAIN.ownerFile); } catch { /* best-effort */ }
    cleanupStaleDomainSync(DIRECT_DOMAIN);
  } else {
    info("warning: pid " + pid + " still alive after kill attempt; owner file left in place.");
  }
}

function cmdStatus() {
  if (DOMAIN_MANIFEST) {
    void withAttachedRuntime(async session => {
      const status = await attachedStatus(session);
      info(`attached  domain=${status.domain}  owner=${status.ownerKind}  runtimePid=${status.runtimePid}`);
      info(`scope=${status.scopeId} (${status.runtimeProfile})  protocol=${status.protocol}  endpoint=${status.endpoint}`);
      if (status.root) info(`root=${status.root}`);
    });
    return;
  }
  if (!existsSync(DIRECT_DOMAIN.ownerFile)) { info("not running."); return; }
  let owner;
  try { owner = validateDomainOwner(readJsonSync(DIRECT_DOMAIN.ownerFile, {}), DIRECT_DOMAIN); }
  catch (error) { die(`invalid Runtime owner lease: ${error.message}`); }
  const pid = Number(owner.ownerPid);
  if (!isPidAlive(pid)) {
    if (Date.now() - Date.parse(owner.startedAt) < 2000) {
      info("runtime owner lease is still initializing; retry.");
      return;
    }
    info(`stale runtime owner (${pid}); cleaning.`);
    try { unlinkSync(DIRECT_DOMAIN.ownerFile); } catch {}
    cleanupStaleDomainSync(DIRECT_DOMAIN);
    return;
  }
  info(`running   domain=${DIRECT_DOMAIN.id}   pid=${pid}   started=${owner.startedAt || "unknown"}`);
}

function cmdApp(rest) {
  if (DOMAIN_MANIFEST) {
    void withAttachedRuntime(async session => {
      openAttachedApp(session);
      info(`opened attached Runtime app at ${session.endpoint}`);
    });
    return;
  }
  cmdLaunch(["--webui", ...rest]);
}

function cmdRuntime(rest) {
  const [operation = "status", ...runtimeArgs] = rest;
  if (operation === "status") { cmdStatus(); return; }
  if (DOMAIN_MANIFEST) {
    die(`attached Client cannot perform Runtime lifecycle operation '${operation}'`);
  }
  if (operation === "stop") { cmdStop(runtimeArgs); return; }
  if (operation === "restart") {
    cmdStop();
    cmdServe(runtimeArgs);
    return;
  }
  die("runtime command must be status, stop or restart", 2);
}

function optionValue(args, name) {
  const index = args.indexOf(name);
  if (index >= 0) return args[index + 1];
  const prefixed = args.find(arg => arg.startsWith(`${name}=`));
  return prefixed ? prefixed.slice(name.length + 1) : null;
}

function cmdDoctorRoute(rest) {
  if (!DOMAIN_MANIFEST) { void cmdDoctor(rest); return; }
  void withAttachedRuntime(async session => {
    const status = await attachedStatus(session);
    process.stdout.write(
      `\nharnex doctor — attached Runtime Domain\n${"-".repeat(72)}\n`
      + `PASS  manifest       ${DOMAIN_MANIFEST}\n`
      + `PASS  domain         ${status.domain} (${status.ownerKind})\n`
      + `PASS  protocol       ${status.protocol}\n`
      + `PASS  workspace      ${status.scopeId}  ${status.root || 'Personal'}\n`
      + `PASS  endpoint       ${status.endpoint}\n\n`);
  });
}

function cmdVersion() {
  const pkg = JSON.parse(readFileSync(join(REPO_ROOT, "package.json"), "utf8"));
  info(`harnex ${pkg.version}`);
  const jar = findJar();
  info(`jar:    ${jar ?? "(not built)"}`);
  const java = findJava();
  const r = spawnSync(java, ["-version"], { stdio: "pipe" });
  const v = (r.stderr || "").toString().split("\n")[0];
  info(`java:   ${v || "(not found)"}`);
}

async function cmdSupervisorRoute(rest) {
  try {
    // supervisor.mjs owns a Runtime Domain at module initialisation time. Keep it out of
    // exact Client processes (and every unrelated command) until the user actually asks
    // for the standalone Web supervisor.
    const { cmdSupervisor, cmdSupervisorStop, cmdSupervisorStatus } = await import("./lib/supervisor.mjs");
    const [sub = "start", ...subRest] = rest;
    if (sub === "stop") await cmdSupervisorStop();
    else if (sub === "status") cmdSupervisorStatus();
    else await cmdSupervisor([sub, ...subRest].filter(arg => arg !== "start"));
  } catch (error) {
    die(`supervisor failed: ${error instanceof Error ? error.message : error}`);
  }
}

// ---------------------------------------------------------------------------
// `harnex backup`
//
// Tar+gzip the data directory to a timestamped archive. Uses the system `tar` binary
// when available (POSIX + Git-Bash on Windows ship one); falls back to a node-native
// implementation when the binary is missing so the command still works on a vanilla
// Windows install. Live-running server is fine — the data files are append-mostly +
// snapshot-friendly and the archive accepts a "best-effort point-in-time" view; a
// consistent backup requires pausing writes or using a filesystem snapshot.
//
// Usage:
//   harnex backup                          → ~/.harnex/backups/data-YYYYMMDD-HHMMSS.tar.gz
//   harnex backup --out /path/to/dir       → /path/to/dir/data-YYYYMMDD-HHMMSS.tar.gz
//   harnex backup --out /path/file.tar.gz  → exactly that filename
//   harnex backup --data /alt/data         → archive a non-default data dir
// ---------------------------------------------------------------------------
async function cmdBackup(rest) {
  // Resolve --data first so --out auto-naming knows what we're archiving.
  const dataIdx = rest.indexOf("--data");
  const workspaceIdx = rest.indexOf("--workspace");
  const personal = rest.includes("--personal");
  if (personal && (dataIdx >= 0 || workspaceIdx >= 0)) die('--personal cannot be combined with --data or --workspace');
  if (dataIdx >= 0 && !rest[dataIdx + 1]) die('--data requires a path');
  if (workspaceIdx >= 0 && !rest[workspaceIdx + 1]) die('--workspace requires a path');
  const dataDir = personal ? join(STATE_DIR, 'personal')
    : projectDataDirectory(workspaceIdx >= 0 ? rest[workspaceIdx + 1] : process.cwd(), STATE_DIR,
      dataIdx >= 0 ? rest[dataIdx + 1] : undefined);
  if (!existsSync(dataDir)) die(`data dir not found: ${dataDir}`);

  // Build YYYYMMDD-HHMMSS directly from a Date — slicing an ISO string is brittle
  // (the `.123Z` suffix shifts the cut points and produces names like `data-…85750.`).
  const now = new Date();
  const pad2 = n => String(n).padStart(2, "0");
  const stamp = `${now.getFullYear()}${pad2(now.getMonth() + 1)}${pad2(now.getDate())}`
    + `-${pad2(now.getHours())}${pad2(now.getMinutes())}${pad2(now.getSeconds())}`;
  const fname = `${personal ? 'personal' : 'data'}-${stamp}.tar.gz`;
  let outArg = null;
  const outIdx = rest.indexOf("--out");
  if (outIdx >= 0 && rest[outIdx + 1]) outArg = resolve(rest[outIdx + 1]);

  const defaultDir = join(STATE_DIR, "backups");
  let outPath;
  if (!outArg) {
    if (!existsSync(defaultDir)) mkdirSync(defaultDir, { recursive: true });
    outPath = join(defaultDir, fname);
  } else if (outArg.endsWith(".tar.gz") || outArg.endsWith(".tgz")) {
    outPath = outArg;
    const dir = dirname(outPath);
    if (!existsSync(dir)) mkdirSync(dir, { recursive: true });
  } else {
    if (!existsSync(outArg)) mkdirSync(outArg, { recursive: true });
    outPath = join(outArg, fname);
  }

  // Prefer the system tar — it preserves perms / symlinks / timestamps natively. If that
  // fails (typical: bsdtar on Windows refuses to stat a POSIX-600 file owned by another
  // user) we fall through to the pure-Node fallback, which simply skips unreadable files.
  const tarOk = (() => {
    try {
      const r = spawnSync(tarExecutable(), ["--version"], { stdio: "pipe", timeout: 3000 });
      return r.status === 0;
    } catch { return false; }
  })();

  let tarFailed = false;
  if (tarOk) {
    const dataParent = dirname(dataDir);
    const dataName   = dataDir.split(/[\\/]/).pop();
    info(`packing ${dataDir} → ${outPath} (system tar)…`);
    const r = spawnSync(tarExecutable(), [
        "-czf", outPath,
        "-C", dataParent, dataName
      ], { stdio: "inherit" });
    if (r.status !== 0) {
      info(`note: system tar exited ${r.status} — retrying with the Node fallback (skips unreadable files).`);
      // Remove the half-written archive (if any) before the fallback writes a new one.
      try { unlinkSync(outPath); } catch { /* may not exist */ }
      tarFailed = true;
    }
  }
  if (!tarOk || tarFailed) {
    info(`packing ${dataDir} → ${outPath} (node fallback)…`);
    info("  (install GNU tar for symlink / perm preservation)");
    await nodeTarballFallback(dataDir, outPath);
  }
  const bytes = statSync(outPath).size;
  info(`done. ${outPath} (${(bytes / 1024 / 1024).toFixed(2)} MiB)`);
}

/**
 * Last-resort tarball: walks the data dir and writes a gzipped POSIX-ustar archive.
 * Skips symlinks / device nodes / sockets — file-only walk is enough for the typical
 * `data/` content (jsonl, json, parquet-like blobs, FTS indexes, sandbox workspaces).
 */
async function nodeTarballFallback(srcRoot, outPath) {
  // Lazy ESM import so we don't pay for zlib unless the fallback path actually fires.
  const { createWriteStream } = await import("node:fs");
  const { createGzip } = await import("node:zlib");
  const gzip = createGzip();
  const out  = createWriteStream(outPath);
  gzip.pipe(out);

  function pad(s, n) { return s.length >= n ? s.slice(0, n) : (s + "\0".repeat(n - s.length)); }
  function octal(n, w) { return n.toString(8).padStart(w - 1, "0") + "\0"; }
  function walk(dir, rel) {
    let entries;
    try { entries = readdirSync(dir); } catch { return; /* unreadable dir → skip whole subtree */ }
    for (const name of entries) {
      const full = join(dir, name);
      let st;
      try { st = statSync(full); } catch { continue; /* unreadable entry → skip */ }
      const entryRel = rel ? rel + "/" + name : name;
      if (st.isDirectory()) { walk(full, entryRel); continue; }
      if (!st.isFile()) continue;
      let data;
      try { data = readFileSync(full); } catch { continue; /* permission-denied → skip */ }
      const header = Buffer.alloc(512, 0);
      header.write(pad(entryRel, 100), 0, "utf8");
      header.write(octal(0o644, 8), 100, "ascii");
      header.write(octal(0, 8), 108, "ascii");                 // uid
      header.write(octal(0, 8), 116, "ascii");                 // gid
      header.write(octal(data.length, 12), 124, "ascii");      // size
      header.write(octal(Math.floor(st.mtimeMs / 1000), 12), 136, "ascii"); // mtime
      header.write("        ", 148, "ascii");                  // chksum placeholder
      header.write("0", 156, "ascii");                         // typeflag = regular file
      header.write("ustar\0", 257, "ascii");
      header.write("00", 263, "ascii");
      let sum = 0;
      for (let i = 0; i < 512; i++) sum += header[i];
      header.write(sum.toString(8).padStart(6, "0") + "\0 ", 148, "ascii");
      gzip.write(header);
      gzip.write(data);
      const pad512 = (512 - (data.length % 512)) % 512;
      if (pad512) gzip.write(Buffer.alloc(pad512, 0));
    }
  }
  walk(srcRoot, "");
  // Two zero blocks = end of archive.
  gzip.write(Buffer.alloc(1024, 0));
  // gzip.end() is async — return a Promise that resolves once the underlying file
  // descriptor has flushed, otherwise the caller's statSync(outPath) races the writer.
  return await new Promise((resolveProm, rejectProm) => {
    out.on("finish", resolveProm);
    out.on("error",  rejectProm);
    gzip.on("error", rejectProm);
    gzip.end();
  });
}

// ---------------------------------------------------------------------------
// `harnex upgrade`
//
// Replace the backend jar with a newer one, taking a backup first. Refuses to run
// while a server instance is up unless --force is supplied.
//
// Usage:
//   harnex upgrade --jar /path/to/new.jar
//   harnex upgrade --jar /path/to/new.jar --no-backup    (skip the safety net)
//   harnex upgrade --jar /path/to/new.jar --force        (overwrite even when server is running)
//
// The replacement jar must be supplied locally; this command does not download artifacts.
// ---------------------------------------------------------------------------
function cmdUpgrade(rest) {
  if (DOMAIN_MANIFEST) {
    die("attached Client cannot replace Runtime artifacts; leave the IDE terminal or unset HARNEX_DOMAIN_MANIFEST");
  }
  const jarIdx = rest.indexOf("--jar");
  const newJar = jarIdx >= 0 && rest[jarIdx + 1] ? resolve(rest[jarIdx + 1]) : null;
  if (!newJar) die("usage: harnex upgrade --jar /path/to/new.jar [--no-backup] [--force]");
  if (!existsSync(newJar)) die(`jar not found: ${newJar}`);
  if (!newJar.endsWith(".jar")) die(`not a .jar file: ${newJar}`);

  const skipBackup = rest.includes("--no-backup");
  const force      = rest.includes("--force");

  // Refuse if a server is running unless --force.
  if (existsSync(DIRECT_DOMAIN.ownerFile)) {
    const pid = Number(readJsonSync(DIRECT_DOMAIN.ownerFile, {}).ownerPid);
    const alive = isPidAlive(pid);
    if (alive && !force) {
      die(`server is running (pid ${pid}). Stop it first ('harnex stop') or pass --force.`);
    }
    if (alive && force) {
      info(`warning: server pid ${pid} still alive — replacing the jar mid-flight may corrupt in-progress requests.`);
    }
  }

  const dest = join(REPO_ROOT, "backend", "target");
  if (!existsSync(dest)) mkdirSync(dest, { recursive: true });
  const existing = findJar();
  if (existing && !skipBackup) {
    const stash = join(STATE_DIR, "backups");
    if (!existsSync(stash)) mkdirSync(stash, { recursive: true });
    const now = new Date();
    const pad2 = n => String(n).padStart(2, "0");
    const stamp = `${now.getFullYear()}${pad2(now.getMonth() + 1)}${pad2(now.getDate())}`
      + `-${pad2(now.getHours())}${pad2(now.getMinutes())}${pad2(now.getSeconds())}`;
    const stashed = join(stash, `jar-${stamp}-` + existing.split(/[\\/]/).pop());
    info(`stashing existing jar  ${existing} → ${stashed}`);
    spawnSync(IS_WIN ? "cmd" : "cp", IS_WIN ? ["/c", "copy", "/y", existing, stashed] : ["-f", existing, stashed],
      { stdio: "inherit" });
  } else if (existing && skipBackup) {
    info(`--no-backup: not stashing the existing jar`);
  }

  const target = join(dest, newJar.split(/[\\/]/).pop());
  info(`installing ${newJar} → ${target}`);
  const cp = spawnSync(IS_WIN ? "cmd" : "cp", IS_WIN ? ["/c", "copy", "/y", newJar, target] : ["-f", newJar, target],
    { stdio: "inherit" });
  if (cp.status !== 0) die(`copy failed (${cp.status})`);
  info(`done. Run 'harnex serve' to start the new version.`);
}

// ---------------------------------------------------------------------------
// `harnex doctor`
//
// Prints a green / red table of the most common deployment-blocking misconfigurations:
//
//   * Java 17+ on PATH
//   * Backend jar built (or instructions to build it)
//   * Standalone server port (18081 by default) free or already taken by us
//   * Provider api-keys are ENV references (not hard-coded literals)
//   * At least one provider key resolves to a non-empty value
//   * Bearer auth either disabled OR has tokens
//   * Data directory writable
//
// SRE workflow: the very first command after `docker compose up` / `npm i -g`. Designed to
// answer "is my deployment good to go?" in one screen. Exits non-zero if any check is RED so
// it can be wired into CI / smoke-test scripts.
// ---------------------------------------------------------------------------

const C_GREEN = "\u001b[32m";
const C_RED   = "\u001b[31m";
const C_YEL   = "\u001b[33m";
const C_RESET = "\u001b[0m";

function pickAgentConfig() {
  // Search order must match spawnJvm()'s SPRING_CONFIG_ADDITIONAL_LOCATION priority,
  // otherwise `harnex doctor` may report "not found" while the running JVM has actually
  // loaded a user-level override (false-negative). Priority — highest wins:
  //   1) ~/.harnex/agent-config.yml        (user-global override, auto-picked by spawnJvm)
  //   2) <repo>/backend/src/main/resources (dev mode running from a checkout)
  //   3) /app/agent-config.yml             (docker convention)
  const candidates = [
    join(STATE_DIR, "agent-config.yml"),
    join(REPO_ROOT, "backend", "src", "main", "resources", "agent-config.yml"),
    join("/app", "agent-config.yml"),
  ];
  for (const p of candidates) {
    try {
      if (existsSync(p)) return { path: p, text: readFileSync(p, "utf8") };
    } catch { /* skip */ }
  }
  return null;
}

/**
 * Walk the agent-config.yml text and collect provider api-key declarations. Pure regex —
 * we deliberately avoid pulling in a YAML parser dep so `harnex doctor` stays single-file.
 * Returns [{ provider, key, bare }] where `bare` means "looks like a hard-coded literal".
 */
function scanProviderKeys(yamlText) {
  if (!yamlText) return [];
  const out = [];
  let curName = null;
  for (const rawLine of yamlText.split(/\r?\n/)) {
    const line = rawLine.replace(/#.*$/, "").trimEnd();
    let m;
    if ((m = /^\s*-\s*name:\s*(.+?)\s*$/.exec(line))) {
      curName = m[1].replace(/^["']|["']$/g, "");
      continue;
    }
    if ((m = /^\s*api-key:\s*(.+?)\s*$/.exec(line))) {
      const raw = m[1].replace(/^["']|["']$/g, "");
      const trimmed = raw.trim();
      const bare = trimmed.length > 0 && !trimmed.startsWith("${") && !trimmed.startsWith("$");
      out.push({ provider: curName ?? "(unknown)", key: trimmed, bare });
    }
  }
  return out;
}

/** Resolve a `${VAR:default}` / `$VAR` reference against process.env. */
function resolveEnvRef(raw) {
  if (raw == null) return "";
  const s = String(raw).trim();
  if (s.startsWith("${") && s.endsWith("}")) {
    const inside = s.slice(2, -1);
    const idx = inside.indexOf(":");
    const name = idx >= 0 ? inside.slice(0, idx) : inside;
    const def  = idx >= 0 ? inside.slice(idx + 1) : "";
    return process.env[name] ?? def;
  }
  if (s.startsWith("$")) return process.env[s.slice(1)] ?? s;
  return s;
}

/**
 * Scan agent-config.yml for the VSCode-style proxy settings introduced in the
 * "代理与出网安全" series. Returns:
 *   { http: { proxy, support, bypass[] }, providers: [{ provider, proxy }] }
 * Same single-file regex constraint as scanProviderKeys() — keep `harnex doctor` zero-dep.
 */
function scanProxyConfig(yamlText) {
  const out = { http: { proxy: null, support: null, bypass: [] }, providers: [] };
  if (!yamlText) return out;
  let curName = null;
  let curIndent = -1;
  let inHttpBlock = false;
  let inHttpBypass = false;
  const lines = yamlText.split(/\r?\n/);
  for (const rawLine of lines) {
    const line = rawLine.replace(/#.*$/, "").replace(/\s+$/, "");
    if (line.trim().length === 0) continue;
    const indent = line.length - line.replace(/^\s+/, "").length;
    let m;
    // Track http: block under top-level harnex (we don't parse the prefix; spring removes it).
    if (/^\s*http:\s*$/.test(line)) { inHttpBlock = true; inHttpBypass = false; curIndent = indent; continue; }
    if (inHttpBlock && indent <= curIndent && !/^\s*$/.test(line)) {
      // dedented out of http: block (next top-level key)
      inHttpBlock = false; inHttpBypass = false;
    }
    if (inHttpBlock) {
      if ((m = /^\s*proxy:\s*(.+?)\s*$/.exec(line))) {
        out.http.proxy = m[1].replace(/^["']|["']$/g, "");
        inHttpBypass = false;
        continue;
      }
      if ((m = /^\s*proxy-support:\s*(.+?)\s*$/.exec(line)) ||
          (m = /^\s*proxySupport:\s*(.+?)\s*$/.exec(line))) {
        out.http.support = m[1].replace(/^["']|["']$/g, "");
        inHttpBypass = false;
        continue;
      }
      if (/^\s*(proxy-bypass|proxyBypass):\s*$/.test(line)) {
        inHttpBypass = true;
        continue;
      }
      if (inHttpBypass) {
        if ((m = /^\s*-\s*(.+?)\s*$/.exec(line))) {
          out.http.bypass.push(m[1].replace(/^["']|["']$/g, ""));
          continue;
        } else if (/^\s*\S/.test(line)) {
          inHttpBypass = false;
        }
      }
    }
    // Per-provider proxy: under each `- name: foo` block, capture `proxy: ...`.
    if ((m = /^\s*-\s*name:\s*(.+?)\s*$/.exec(line))) {
      curName = m[1].replace(/^["']|["']$/g, "");
      continue;
    }
    if (curName && (m = /^\s*proxy:\s*(.+?)\s*$/.exec(line))) {
      out.providers.push({ provider: curName, proxy: m[1].replace(/^["']|["']$/g, "") });
    }
  }
  return out;
}

/** Sanitise a URL string for display: scrub `user:pass@` to `***:***@`. */
function sanitiseProxyUrl(s) {
  if (!s) return s;
  return String(s).replace(/^([a-zA-Z][\w+.\-]*:\/\/)[^/@:?#"']+:[^/@?#"']+@/, "$1***:***@");
}

function row(status, name, detail) {
  const colour = status === "OK" ? C_GREEN : status === "WARN" ? C_YEL : C_RED;
  const tag    = status === "OK" ? "  OK  " : status === "WARN" ? " WARN " : " FAIL ";
  return `${colour}[${tag}]${C_RESET} ${name.padEnd(28)} ${detail}`;
}

async function cmdDoctor(rest) {
  const findings = [];   // { status: "OK"|"WARN"|"FAIL", name, detail }

  // 1. Java
  const java = findJava();
  const probe = probeJava(java);
  if (!probe.ok) {
    findings.push({ status: "FAIL", name: "Java runtime",
      detail: `not found on PATH (set JAVA_HOME). Harnex needs JDK 17+.` });
  } else if (probe.major > 0 && probe.major < 17) {
    findings.push({ status: "FAIL", name: "Java runtime",
      detail: `Java ${probe.major} detected — JDK 17+ required (${java})` });
  } else {
    const ver = probe.major > 0 ? `Java ${probe.major}` : "Java (version unknown)";
    findings.push({ status: "OK", name: "Java runtime", detail: `${ver} (${java})` });
  }

  // 2. Backend jar
  const jar = findJar();
  if (!jar) {
    findings.push({ status: "FAIL", name: "Backend jar",
      detail: `not built. Run: cd backend && mvn clean -DskipTests package` });
  } else {
    findings.push({ status: "OK", name: "Backend jar", detail: jar });
  }

  // 3. Standalone server port (respect explicit CLI/env overrides)
  const port = resolveServerPort(rest ?? []);
  const portFree = await probePortFree(port);
  if (portFree) {
    findings.push({ status: "OK", name: `Port ${port}`, detail: "free" });
  } else {
    // Distinguish "we own it" from "someone else owns it" — own-PID is OK, foreign is WARN.
    let owner = "unknown";
    try {
      if (existsSync(DIRECT_DOMAIN.ownerFile)) {
        const pid = Number(readJsonSync(DIRECT_DOMAIN.ownerFile, {}).ownerPid);
        if (isPidAlive(pid)) owner = `harnex domain ${DIRECT_DOMAIN.id} (pid ${pid})`;
      }
    } catch { /* ignore */ }
    findings.push({ status: owner === "unknown" ? "WARN" : "OK",
      name: `Port ${port}`, detail: `in use by ${owner}` });
  }

  // 4. Provider keys: bare (A3 violation) and resolution
  const cfg = pickAgentConfig();
  if (!cfg) {
    findings.push({ status: "WARN", name: "agent-config.yml",
      detail: "not found in CWD or repo — using built-in defaults at runtime" });
  } else {
    findings.push({ status: "OK", name: "agent-config.yml", detail: cfg.path });
    const keys = scanProviderKeys(cfg.text);
    if (keys.length === 0) {
      findings.push({ status: "WARN", name: "Providers configured",
        detail: "no `providers[].api-key` entries detected (regex scan)" });
    }
    let bareCount = 0;
    let resolvedCount = 0;
    for (const k of keys) {
      if (k.bare) {
        bareCount++;
        findings.push({ status: "FAIL", name: `Provider '${k.provider}' key`,
          detail: "hard-coded literal in agent-config.yml — switch to ${ENV_VAR} (A3)" });
      } else {
        const val = resolveEnvRef(k.key);
        if (val && val.trim().length > 0) {
          resolvedCount++;
          findings.push({ status: "OK", name: `Provider '${k.provider}' key`,
            detail: `${k.key}  →  resolved (${val.length} chars)` });
        } else {
          findings.push({ status: "WARN", name: `Provider '${k.provider}' key`,
            detail: `${k.key}  →  empty (env var not set)` });
        }
      }
    }
    if (keys.length > 0 && resolvedCount === 0 && bareCount === 0) {
      findings.push({ status: "FAIL", name: "Any provider usable",
        detail: "every provider api-key resolved to empty — agent will fail on first chat" });
    }

    // 4b. Egress proxy: surface harnex.http.proxy + per-provider overrides so operators
    // can confirm what the backend will dial out through. Values are sanitised
    // (user:pass@ scrubbed) before being printed.
    const proxyCfg = scanProxyConfig(cfg.text);
    const overrideArg = (rest ?? []).find(a => a.startsWith("--harnex.http.proxy="));
    if (overrideArg !== undefined) {
      // The CLI override takes the highest Spring property-source precedence — flag it loudly
      // so the operator never has to guess why a YAML proxy "isn't doing anything".
      const v = overrideArg.slice("--harnex.http.proxy=".length);
      findings.push({ status: "WARN", name: "http.proxy (CLI override)",
        detail: `--harnex.http.proxy=${sanitiseProxyUrl(v) || "(empty → DIRECT)"} — overrides YAML for every provider that doesn't set its own` });
    } else if (proxyCfg.http.proxy) {
      const resolved = resolveEnvRef(proxyCfg.http.proxy);
      const support = proxyCfg.http.support || "fallback";
      const detail = `${sanitiseProxyUrl(resolved) || "(empty → DIRECT)"} support=${support}` +
        (proxyCfg.http.bypass.length ? ` bypass=${proxyCfg.http.bypass.join(",")}` : "");
      findings.push({ status: "OK", name: "http.proxy (global)", detail });
    } else {
      // YAML didn't set harnex.http.proxy: report whatever POSIX env var the backend will
      // pick up at runtime (HTTPS_PROXY > HTTP_PROXY > ALL_PROXY). Matches the env-fallback
      // logic in ProviderRouter.buildProvider. When NO_PROXY is set, note it too.
      const envOrder = ["HTTPS_PROXY", "https_proxy", "HTTP_PROXY", "http_proxy", "ALL_PROXY", "all_proxy"];
      const envName = envOrder.find(n => process.env[n] && process.env[n].trim() !== "");
      const noProxy = process.env.NO_PROXY || process.env.no_proxy;
      if (envName) {
        findings.push({ status: "OK", name: "http.proxy (global)",
          detail: `${sanitiseProxyUrl(process.env[envName])} (from ${envName} env)` +
            (noProxy ? `  NO_PROXY=${noProxy}` : "") });
      } else {
        findings.push({ status: "OK", name: "http.proxy (global)",
          detail: "unset → providers connect directly" +
            (noProxy ? ` (NO_PROXY=${noProxy} present but no proxy env set)` : "") });
      }
    }
    for (const pp of proxyCfg.providers) {
      const v = resolveEnvRef(pp.proxy);
      const isOff = !v || /^(off|false|none|direct)$/i.test(v);
      const detail = isOff
        ? "off — forced DIRECT regardless of global"
        : sanitiseProxyUrl(v);
      findings.push({ status: "OK", name: `provider '${pp.provider}' proxy`, detail });
    }
  }

  // 5. Auth
  const authEnabled = (process.env.HARNEX_AUTH_ENABLED || "false").toLowerCase() === "true";
  const authTokens  = (process.env.HARNEX_AUTH_TOKENS  || "").trim();
  if (!authEnabled) {
    findings.push({ status: "WARN", name: "Bearer auth",
      detail: "disabled by default for the loopback, single-OS-user product" });
  } else if (authTokens.length === 0) {
    findings.push({ status: "WARN", name: "Bearer auth",
      detail: "no HARNEX_AUTH_TOKENS set — a random token will be bootstrapped on first start" });
  } else {
    findings.push({ status: "OK", name: "Bearer auth",
      detail: `${authTokens.split(",").length} token(s) configured` });
  }

  // 6. Data dir writable
  try {
    const dataRoot = process.env.HARNEX_DATA_DIR || join(REPO_ROOT, "data");
    if (!existsSync(dataRoot)) mkdirSync(dataRoot, { recursive: true });
    const probeFile = join(dataRoot, ".doctor-probe");
    writeFileSync(probeFile, String(Date.now()), "utf8");
    try { unlinkSync(probeFile); } catch { /* best-effort */ }
    findings.push({ status: "OK", name: "Data dir writable", detail: dataRoot });
  } catch (e) {
    findings.push({ status: "FAIL", name: "Data dir writable", detail: e.message });
  }

  // ---- Render table ----
  const lines = ["", "harnex doctor — Harnex deployment health check", "".padEnd(72, "-")];
  for (const f of findings) lines.push(row(f.status, f.name, f.detail));
  const fails = findings.filter(f => f.status === "FAIL").length;
  const warns = findings.filter(f => f.status === "WARN").length;
  lines.push("".padEnd(72, "-"));
  if (fails > 0) {
    lines.push(`${C_RED}${fails} blocking issue(s)${C_RESET}, ${warns} warning(s). Fix the FAILs and re-run.`);
  } else if (warns > 0) {
    lines.push(`${C_YEL}0 blocking issues, ${warns} warning(s)${C_RESET}. Safe to start, but review the WARNs.`);
  } else {
    lines.push(`${C_GREEN}All green — safe to \`harnex serve\`.${C_RESET}`);
  }
  lines.push("");
  process.stdout.write(lines.join("\n"));
  process.exit(fails > 0 ? 1 : 0);
}


function help() {
  process.stdout.write(`
\u001b[1mHarnex\u001b[0m — agent harness with built-in TUI + web UI

\u001b[1mUsage\u001b[0m
  harnex                       Interactive launcher (pick Web UI or Terminal)
  harnex launch [--webui|--tui]   Same launcher; flags skip the picker
  harnex app                   Open the app (or the exact attached Runtime)
  harnex tui [--conversation]   Native terminal; F6 switches Conversation / Agent
  harnex cli   -p "..."        One-shot; exact attach when a Domain manifest is present
  harnex cli   [args]          Alias of native TUI when no prompt is supplied
  harnex serve [args]          Start HTTP API + web UI on :${DEFAULT_WEB_PORT} (writes PID file)
  harnex runtime status|stop|restart [serve args]
  harnex supervisor [opts]     Single-workspace Web control plane on :${DEFAULT_WEB_PORT}
                              Subcommands: stop | status. Flag: --port=NNNN
  harnex shortcut [opts]       Install desktop shortcut (use --uninstall to remove)
  harnex uninstall-shortcut    Remove the desktop shortcut
  harnex stop                  Stop standalone serve; attached: requires --run <runId>
  harnex status                Show whether a server is running
  harnex init  [--force]       Scaffold AGENTS.md in CWD (seeded from ~/.harnex/AGENTS.md when present)
  harnex init  --global        Seed ~/.harnex/{AGENTS,USER}.md from built-in templates (one-time per machine)
  harnex init  --wizard        Interactive .env generator (5 questions, then docker compose up)
  harnex doctor                Deployment health check (Java / port / providers / auth)
  harnex backup [--out X]      Back up current project data; --workspace PATH / --data PATH override it
  harnex backup --personal     Back up personal conversations
  harnex upgrade --jar X       Replace the backend jar (auto-stashes the previous one)
  harnex version               Print versions

\u001b[1mExamples\u001b[0m
  harnex                                 # interactive picker (Web UI or TUI)
  harnex launch --webui                  # skip picker, start Web UI directly
  harnex shortcut                        # install desktop shortcut
  harnex cli -p "summarise README.md"
  harnex cli --conversation -p "Explain this idea"
  harnex tui --workspace /path/to/project
  harnex serve --server.port=9090
  harnex --domain-manifest X cli -p "explain this workspace"

\u001b[1mEnvironment\u001b[0m
  JAVA_HOME                   override Java binary
  HARNEX_JAR                   override path to the backend jar
  HARNEX_EXECUTION_ENVIRONMENT host | docker (SSH uses remote Runtime connections)
  HARNEX_USER_DATA_DIR         user-level config/state root (default ~/.harnex)
  HARNEX_DOMAIN_MANIFEST       exact IDE Runtime Domain manifest (injected by VS Code)

\u001b[1mDocs\u001b[0m  see docs/06_用户手册.md
`);
}

const [cmd = "launch", ...rest] = parsedCommandLine.args;
switch (cmd) {
  case "launch":        DOMAIN_MANIFEST ? cmdApp(rest) : cmdLaunch(rest); break;
  case "app":           cmdApp(rest); break;
  case "shortcut":
  case "install-shortcut":
                        cmdShortcut(rest); break;
  case "uninstall-shortcut":
  case "shortcut-uninstall":
                        cmdUninstallShortcut(rest); break;
  case "cli":           cmdCli(rest); break;
  case "tui":           cmdTui(rest); break;
  case "remote":        cmdRemoteNative('remote', rest); break;
  case "serve":
  case "start":         cmdServe(rest); break;
  case "runtime":       cmdRuntime(rest); break;
  case "supervisor": {
    if (DOMAIN_MANIFEST) {
      die("attached Client cannot own a Web supervisor; leave the IDE terminal or unset HARNEX_DOMAIN_MANIFEST");
    }
    void cmdSupervisorRoute(rest);
    break;
  }
  case "stop":          cmdStop(rest); break;
  case "status":        cmdStatus(); break;
  case "init":          cmdInit(rest); break;
  case "doctor":        cmdDoctorRoute(rest); break;
  case "backup":        cmdBackup(rest); break;
  case "upgrade":       cmdUpgrade(rest); break;
  case "version":
  case "-v":
  case "--version":     cmdVersion(); break;
  case "help":
  case "-h":
  case "--help":        help(); break;
  default:
    // Reject unknown commands explicitly — the previous pass-through happily forwarded
    // arbitrary tokens like `harnex rm` to the JVM, which was both confusing and risky.
    process.stderr.write(`\u001b[31mharnex: unknown command '${cmd}'. Try 'harnex help'.\u001b[0m\n`);
    process.exit(2);
}
