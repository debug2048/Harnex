// Stable control plane: one shared personal Runtime and at most one active project Runtime.
//
// Invariant: at most one workspace Runtime JVM is alive. A switch is serialized and always
// executes DRAIN -> CLOSE -> OPEN -> READY. The browser sees only "closing current project" /
// "opening target project"; the phase names remain available in diagnostics and lifecycle SSE.

import { spawn, spawnSync } from "node:child_process";
import {
  createReadStream, existsSync, mkdirSync, readFileSync, readdirSync,
  realpathSync, statSync, unlinkSync,
} from "node:fs";
import { createServer, request as httpRequest } from "node:http";
import { homedir, platform } from "node:os";
import { dirname, extname, join, resolve, sep } from "node:path";
import { createHash, randomBytes, randomUUID, timingSafeEqual } from "node:crypto";
import { workspaceIdentity } from './workspace-identity.mjs';
import { connect as netConnect } from "node:net";
import { fileURLToPath } from "node:url";
import { appendConfigFile } from "./config-inject.mjs";
import { ensureLoopbackNoProxy } from "./no-proxy.mjs";
import { remoteCommand, openRemoteBridge, remoteInteraction } from "./remote-runtime.mjs";
import { PersonalRuntime } from './personal-runtime.mjs';
import { appendBoundedLog, createBoundedLogStream } from './bounded-log.mjs';
import { createRuntimePortPool, probeRuntimeReady } from './runtime-startup.mjs';
import { DEFAULT_WEB_PORT, resolveWebPort } from './ports.mjs';
import { filesystemRoots } from './filesystem-roots.mjs';
import { serveWorkspaceControl } from './workspace-control.mjs';
import {
  atomicWriteJsonSync, claimDomainOwnerSync, cleanupStaleDomainSync, isDomainOwnerAlive, isPidAlive,
  publishDomainManifestSync, readJsonSync, releaseDomainOwnerSync, resolveRuntimeDomain,
  unpublishDomainManifestSync, updateJsonSync, validateDomainOwner,
} from "./runtime-domain.mjs";

const HERE = dirname(fileURLToPath(import.meta.url));
const REPO_ROOT = resolve(HERE, "../..");
const IS_WIN = platform() === "win32";
const USER_HOME = resolve(
  (process.env.HARNEX_USER_DATA_DIR || "").trim() || join(homedir(), ".harnex"),
);
const DOMAIN = resolveRuntimeDomain("web-default", "web");
const CONTROL_HOME = DOMAIN.dir;
const REGISTRY_FILE = DOMAIN.catalogFile;
const DOMAIN_STATE_FILE = DOMAIN.stateFile;
const LOG_DIR = DOMAIN.logDir;
const SUPERVISOR_LOG_FILE = join(LOG_DIR, "supervisor.log");
const runtimePorts = createRuntimePortPool();
const HEALTH_TIMEOUT_MS = Math.max(10_000, Number(process.env.HARNEX_SUPERVISOR_HEALTH_TIMEOUT_MS) || 120_000);
const DRAIN_TIMEOUT_MS = Math.max(1_000, Number(process.env.HARNEX_SUPERVISOR_DRAIN_TIMEOUT_MS) || 10_000);
const CLOSE_TIMEOUT_MS = Math.max(1_000, Number(process.env.HARNEX_SUPERVISOR_CLOSE_TIMEOUT_MS) || 8_000);
const FORCE_KILL_TIMEOUT_MS = Math.max(1_000,
  Number(process.env.HARNEX_SUPERVISOR_FORCE_KILL_TIMEOUT_MS) || 5_000);
const JAVA_XMS = (process.env.HARNEX_SUPERVISOR_JAVA_XMS || "256m").trim();
const JAVA_XMX = (process.env.HARNEX_SUPERVISOR_JAVA_XMX || "512m").trim();
const JAVA_TIERED_STOP_LEVEL = (process.env.HARNEX_GUI_TIERED_STOP_LEVEL || "1").trim();
const BIND_HOST = (process.env.HARNEX_SUPERVISOR_HOST || '127.0.0.1').trim();
const FIXED_WORKSPACE = process.env.HARNEX_SUPERVISOR_WORKSPACE?.trim()
  ? workspaceIdentity(resolve(process.env.HARNEX_SUPERVISOR_WORKSPACE.trim())) : null;
const WEB_ORIGIN = configuredWebOrigin(process.env.HARNEX_WEB_ORIGIN);

function configuredWebOrigin(value) {
  if (!value?.trim()) return null;
  const url = new URL(value.trim());
  if (!['http:', 'https:'].includes(url.protocol) || url.username || url.password
      || url.pathname !== '/' || url.search || url.hash) throw new Error('HARNEX_WEB_ORIGIN must be an HTTP(S) origin');
  return url;
}

let extraArgs = [];
let extraEnv = { HARNEX_AUTH_ENABLED: "false" };
let state = null;
let runtimeChild = null;
let remoteRuntime = null;
let pendingRemote = null;
let runtimeAccessToken = null;
let runtimeLog = null;
let runtimeControlToken = null;
let personalControlToken = null;
let transition = null;
let workspaceControl = null;
let shuttingDown = false;
const CONTROL_TOKEN = randomBytes(32).toString("base64url");
const OWNER_INSTANCE_ID = randomUUID();
const lifecycleClients = new Set();
const personal = new PersonalRuntime({
  userDataDir: USER_HOME, ownerKind: 'web', reservePort: runtimePorts.reserve, configRevision,
  timeoutMs: HEALTH_TIMEOUT_MS,
  onChange: value => {
    const envelope = JSON.stringify({ type: 'personal_lifecycle', data: value, ts: Date.now() });
    for (const response of [...lifecycleClients]) {
      try { response.write(`event: personal_lifecycle\ndata: ${envelope}\n\n`); }
      catch { lifecycleClients.delete(response); }
    }
  },
  launch: async ({ paths, port, instanceId, log }) => {
    const jar = findJar();
    if (!jar) throw new Error('Backend JAR not found; build the Runtime before opening personal space');
    personalControlToken = randomBytes(32).toString('base64url');
    const env = { ...process.env, ...extraEnv, HARNEX_RUNTIME_PROFILE: 'PERSONAL', HARNEX_AUTH_ENABLED: 'true',
      HARNEX_SCOPE_ID: paths.scopeId, HARNEX_INSTANCE_ID: instanceId,
      HARNEX_USER_DATA_DIR: paths.userDataDir, HARNEX_DATA_DIR: paths.dataDir, HARNEX_DATADIR: paths.dataDir,
      HARNEX_RUNTIME_DOMAIN_ID: paths.id, HARNEX_RUNTIME_DOMAIN_DIR: paths.dir, HARNEX_RUNTIME_OWNER_KIND: 'web',
      HARNEX_INTERNAL_SUPERVISOR_TOKEN: personalControlToken,
    };
    delete env.HARNEX_WORKSPACE;
    for (const name of ['agent-config.yml', 'application.yml']) {
      const file = join(USER_HOME, name);
      if (existsSync(file)) appendConfigFile(env, file);
    }
    const startup = /^[1-4]$/.test(JAVA_TIERED_STOP_LEVEL) ? [`-XX:TieredStopAtLevel=${JAVA_TIERED_STOP_LEVEL}`] : [];
    const child = spawn(findJava(), [`-Xms${JAVA_XMS}`, `-Xmx${JAVA_XMX}`, ...startup, '-jar', jar, ...extraArgs,
      `--server.port=${port}`, '--server.address=127.0.0.1', '--harnex.runtime-profile=PERSONAL',
      `--harnex.user-data-dir=${paths.userDataDir}`, `--harnex.data-dir=${paths.dataDir}`,
      `--harnex.runtime-domain-id=${paths.id}`, `--harnex.runtime-domain-dir=${paths.dir}`,
      '--harnex.runtime-owner-kind=web', '--harnex.auth.enabled=true',
      '--harnex.cors.allowed-origins[0]=http://tauri.localhost', '--harnex.cors.allowed-origins[1]=tauri://localhost',
      '--harnex.cors.allowed-headers[0]=*', '--harnex.cors.allow-credentials=false',
      ...['GET', 'POST', 'PUT', 'PATCH', 'DELETE', 'OPTIONS'].map((method, index) => `--harnex.cors.allowed-methods[${index}]=${method}`)],
    { cwd: paths.dir, env, stdio: ['ignore', 'pipe', 'pipe'], detached: !IS_WIN, windowsHide: true });
    child.stdout.pipe(log, { end: false });
    child.stderr.pipe(log, { end: false });
    return child;
  },
  stop: async (child, session) => {
    if (session) await requestRuntime(new URL(session.endpoint).port, `/api/v1/admin/drain?timeoutMs=${DRAIN_TIMEOUT_MS}`,
      'POST', DRAIN_TIMEOUT_MS + 1000, { 'X-Harnex-Supervisor-Token': personalControlToken || '', ...session.identityHeaders });
    treeKill(child.pid, IS_WIN);
    if (!await waitForPidExit(child.pid, CLOSE_TIMEOUT_MS)) {
      treeKill(child.pid, true);
      await waitForPidExit(child.pid, FORCE_KILL_TIMEOUT_MS);
    }
  },
});

const out = (s) => process.stdout.write(`${s}\n`);
const err = (s) => process.stderr.write(`harnex supervisor: ${s}\n`);

function ensureControlHome() {
  for (const dir of [CONTROL_HOME, LOG_DIR]) {
    if (!existsSync(dir)) mkdirSync(dir, { recursive: true });
  }
}

function configRevision() {
  const hash = createHash("sha256");
  for (const name of ["agent-config.yml", "application.yml"]) {
    const file = join(USER_HOME, name);
    if (existsSync(file)) hash.update(name).update(readFileSync(file));
  }
  return hash.digest("hex");
}

function lifecycleDiagnostic(phase, detail = {}) {
  const record = { ts: new Date().toISOString(), phase, ...detail };
  try {
    ensureControlHome();
    appendBoundedLog(SUPERVISOR_LOG_FILE, `${JSON.stringify(record)}\n`);
  } catch (e) {
    err(`cannot write lifecycle diagnostic (${e.message})`);
  }
  out(`[workspace] ${phase}${Object.keys(detail).length ? ` ${JSON.stringify(detail)}` : ""}`);
}

function emptyState() {
  return {
    schemaVersion: 1,
    activeWorkspaceId: null,
    lastWorkspaceId: null,
    lifecycle: "CLOSED",
    failureMessage: null,
    instanceId: null,
    port: DEFAULT_WEB_PORT,
    workspaces: [],
  };
}

function loadState() {
  ensureControlHome();
  const domainState = readJsonSync(DOMAIN_STATE_FILE, {});
  state = {
    ...emptyState(), ...domainState,
    activeWorkspaceId: null, lifecycle: "CLOSED", instanceId: null, runtimePort: null, failureMessage:null,
    workspaces: loadCatalogWorkspaces(),
  };
  pendingRemote = state.workspaces.find(item => item.remoteProfile && item.id === domainState.pendingRemoteId) || null;
  if (pendingRemote) {
    state.activeWorkspaceId = pendingRemote.id;
    state.lastWorkspaceId = pendingRemote.id;
    // Keep the remote ownership marker, but do not report an opening failure
    // before trying to reconnect. The old instance has not been verified yet.
    state.lifecycle = "CLOSED";
    state.instanceId = null;
  }
  if (state.lastWorkspaceId && !state.workspaces.some((item) => item.id === state.lastWorkspaceId)) {
    state.lastWorkspaceId = null;
  }
}

function saveState() {
  ensureControlHome();
  const stable = {
    schemaVersion: 1,
    runtimeDomainId: DOMAIN.id,
    ownerKind: DOMAIN.ownerKind,
    activeWorkspaceId: state.activeWorkspaceId,
    lastWorkspaceId: state.lastWorkspaceId,
    pendingRemoteId: pendingRemote?.id || null,
    port: state.port,
    runtimePort: state.runtimePort || null,
    instanceId: state.instanceId,
    lifecycle: state.lifecycle,
    updatedAt: new Date().toISOString(),
  };
  atomicWriteJsonSync(DOMAIN_STATE_FILE, stable);
}

function normalizeWorkspace(item) {
  if (item.remoteProfile) return { ...item, root: item.root, dataDir: null };
  const root = resolve(item.root);
  return { ...item, root, dataDir: resolve(root, item.dataDir || ".harnex/data") };
}

function loadCatalogWorkspaces() {
  const catalog = readJsonSync(REGISTRY_FILE, { schemaVersion: 1, workspaces: [] });
  return Array.isArray(catalog.workspaces) ? catalog.workspaces.map(normalizeWorkspace)
    .filter(item => !FIXED_WORKSPACE || item.id === FIXED_WORKSPACE.id) : [];
}

function upsertCatalogWorkspace(item) {
  updateJsonSync(REGISTRY_FILE, { schemaVersion: 1, workspaces: [] }, current => {
    const workspaces = Array.isArray(current.workspaces) ? current.workspaces : [];
    const persisted = (({ id, name, root, dataDir, createdAt, lastOpened, remoteProfile, remoteHost, remoteKeepRunning, remoteRetainedAt, remoteService, runtimeScopeId }) => (
      { id, name, root, dataDir, createdAt, lastOpened, ...(remoteProfile ? {remoteProfile, remoteHost,
        remoteKeepRunning: remoteKeepRunning === true, remoteRetainedAt: remoteRetainedAt || null,
        ...(remoteService ? {remoteService,runtimeScopeId} : {})} : {}) }))(item);
    const index = workspaces.findIndex(candidate => candidate.id === item.id);
    if (index >= 0) workspaces[index] = persisted;
    else workspaces.push(persisted);
    return { schemaVersion: 1, revision: Number(current.revision || 0) + 1, workspaces };
  });
  state.workspaces = loadCatalogWorkspaces();
  return getWorkspace(item.id);
}

function getWorkspace(id) {
  return state.workspaces.find((item) => item.id === id) || null;
}

function activeWorkspace() {
  return state.activeWorkspaceId ? getWorkspace(state.activeWorkspaceId) : null;
}

function publicWorkspace(item) {
  return {
    id: item.id,
    name: item.name,
    root: item.root,
    ...(item.remoteProfile ? { remoteProfile: item.remoteProfile, remoteHost: item.remoteHost,
      remoteKeepRunning: item.remoteKeepRunning === true, remoteRetainedAt: item.remoteRetainedAt || null,
      ...(item.remoteService ? {remoteService:item.remoteService,runtimeScopeId:item.runtimeScopeId} : {}),
      ...(item.id === state.activeWorkspaceId && remoteRuntime ? {remoteRuntime:remoteRuntimeView(remoteRuntime.descriptor)} : {}) } : {}),
    createdAt: item.createdAt,
    lastOpened: item.lastOpened,
    state: item.id === state.activeWorkspaceId ? state.lifecycle : "CLOSED",
    isActive: item.id === state.activeWorkspaceId,
    instanceId: item.id === state.activeWorkspaceId ? state.instanceId : null,
  };
}

function remoteRuntimeView(descriptor) {
  return {ownership:descriptor.ownership || 'managed',runtimeVersion:descriptor.runtimeVersion || 'unknown',
    runtimeSha256:descriptor.runtimeSha256 || '',targetVersion:descriptor.targetVersion || '',targetSha256:descriptor.targetSha256 || '',
    updateAvailable:descriptor.updateAvailable === true,canUpgrade:descriptor.canUpgrade === true};
}

function registerWorkspace({ root, name, dataDir }) {
  if (!root || typeof root !== "string") throw new Error("root is required");
  const requested = resolve(root);
  if (!existsSync(requested) || !statSync(requested).isDirectory()) {
    throw new Error(`workspace directory does not exist: ${requested}`);
  }
  const { root: absolute, id } = workspaceIdentity(requested);
  if (FIXED_WORKSPACE && id !== FIXED_WORKSPACE.id)
    throw new SupervisorError(403, 'fixed_workspace', 'This host uses a fixed workspace');
  let item = getWorkspace(id);
  if (item) return item;
  item = {
    id,
    name: name || absolute.split(sep).pop() || absolute,
    root: absolute,
    // Project archives stay on the project disk; user-level configuration and personal space are separate.
    dataDir: resolve(absolute, dataDir || ".harnex/data"),
    createdAt: new Date().toISOString(),
    lastOpened: null,
  };
  state.workspaces.push(item);
  return upsertCatalogWorkspace(item);
}

function removeWorkspace(id) {
  if (state.activeWorkspaceId === id) throw new Error("close the current workspace before removing it");
  let removed = false;
  const catalog = updateJsonSync(REGISTRY_FILE, { schemaVersion: 1, workspaces: [] }, current => {
    const workspaces = Array.isArray(current.workspaces) ? current.workspaces : [];
    const next = workspaces.filter(item => item.id !== id);
    removed = next.length !== workspaces.length;
    return { schemaVersion: 1, revision: Number(current.revision || 0) + 1, workspaces: next };
  });
  state.workspaces = catalog.workspaces.map(normalizeWorkspace);
  if (!removed) return false;
  if (state.lastWorkspaceId === id) state.lastWorkspaceId = null;
  saveState();
  return true;
}

function emitLifecycle(phase, detail = {}) {
  if (phase === "FAILED") state.failureMessage = detail.message || state.failureMessage || null;
  else if (phase === "READY" || phase === "CLOSED") state.failureMessage = null;
  const changed = state.lifecycle !== phase || Object.keys(detail).length > 0;
  state.lifecycle = phase;
  const event = JSON.stringify({
    type: "workspace_lifecycle",
    data: {
      phase,
      workspaceId: state.activeWorkspaceId,
      instanceId: state.instanceId,
      ...detail,
    },
    ts: Date.now(),
  });
  for (const response of [...lifecycleClients]) {
    try { response.write(`event: workspace_lifecycle\ndata: ${event}\n\n`); }
    catch { lifecycleClients.delete(response); }
  }
  if (changed) lifecycleDiagnostic(phase, detail);
  saveState();
}

function findJar() {
  if (process.env.HARNEX_JAR && existsSync(process.env.HARNEX_JAR)) return resolve(process.env.HARNEX_JAR);
  const dir = join(REPO_ROOT, "backend", "target");
  if (!existsSync(dir)) return null;
  return readdirSync(dir)
    .filter((name) => name.startsWith("harnex-") && name.endsWith(".jar")
      && !name.endsWith("-sources.jar") && !name.endsWith("-javadoc.jar"))
    .map((name) => join(dir, name))
    .sort((a, b) => statSync(b).mtimeMs - statSync(a).mtimeMs)[0] || null;
}

function findJava() {
  const name = IS_WIN ? "java.exe" : "java";
  if (process.env.JAVA_HOME) {
    const candidate = join(process.env.JAVA_HOME, "bin", name);
    if (existsSync(candidate)) return candidate;
  }
  return name;
}

function treeKill(pid, force) {
  if (!pid) return;
  if (IS_WIN) {
    const args = ["/PID", String(pid), "/T"];
    if (force) args.push("/F");
    try { spawnSync("taskkill", args, { stdio: "ignore", windowsHide: true }); } catch {}
  } else {
    try { process.kill(-pid, force ? "SIGKILL" : "SIGTERM"); }
    catch { try { process.kill(pid, force ? "SIGKILL" : "SIGTERM"); } catch {} }
  }
}

function portFree(port) {
  return new Promise((done) => {
    const socket = netConnect({ host: "127.0.0.1", port });
    let settled = false;
    const finish = (free) => {
      if (settled) return;
      settled = true;
      try { socket.destroy(); } catch {}
      done(free);
    };
    socket.setTimeout(300);
    socket.once("connect", () => finish(false));
    socket.once("error", () => finish(true));
    socket.once("timeout", () => finish(true));
  });
}

function requestRuntime(port, path, method = "GET", timeout = 1500, headers = {}) {
  return new Promise((done) => {
    const request = httpRequest(
      { host: "127.0.0.1", port, path, method, timeout, headers },
      (response) => { response.resume(); done(response.statusCode || 0); },
    );
    request.on("error", () => done(0));
    request.on("timeout", () => { request.destroy(); done(0); });
    request.end();
  });
}

function requestRuntimeJson(port, path, timeout = 1500, method = "GET") {
  return new Promise((done) => {
    const request = httpRequest({
      host: "127.0.0.1",
      port,
      path,
      method,
      timeout,
      headers: { "X-Harnex-Supervisor-Token": runtimeControlToken || "",
        ...(runtimeAccessToken ? { Authorization: `Bearer ${runtimeAccessToken}` } : {}) },
    }, (response) => {
      const chunks = [];
      response.on("data", (chunk) => chunks.push(chunk));
      response.on("end", () => {
        try {
          done({ status: response.statusCode || 0, body: JSON.parse(Buffer.concat(chunks).toString("utf8")) });
        } catch {
          done({ status: response.statusCode || 0, body: null });
        }
      });
    });
    request.on("error", () => done({ status: 0, body: null }));
    request.on("timeout", () => { request.destroy(); done({ status: 0, body: null }); });
    request.end();
  });
}

async function waitReady(port, child, expected) {
  const deadline = Date.now() + HEALTH_TIMEOUT_MS;
  while (Date.now() < deadline) {
    if (shuttingDown || !child.pid || child.exitCode !== null || child.signalCode !== null || runtimeChild !== child) return false;
    if (await probeRuntimeReady(port, expected)) {
      return child.exitCode === null && child.signalCode === null && runtimeChild === child;
    }
    await new Promise((done) => setTimeout(done, 400));
  }
  return false;
}

async function waitForPidExit(pid, timeoutMs) {
  const deadline = Date.now() + timeoutMs;
  while (isPidAlive(pid) && Date.now() < deadline) {
    await new Promise((done) => setTimeout(done, 100));
  }
  return !isPidAlive(pid);
}

/**
 * Sequentially touch the fat JAR immediately before launch. On Windows the 90 MiB Runtime
 * artifact is frequently evicted or rescanned between short-lived workspace JVMs; letting
 * Spring Boot fault those pages in through many nested-JAR reads makes a cold switch highly
 * variable. A bounded streaming pass gives the OS/AV one sequential read first, without
 * retaining the artifact in the Supervisor heap. Disable only for diagnostics with
 * HARNEX_SUPERVISOR_PREFETCH_JAR=0.
 */
async function prefetchRuntimeArtifact(jar) {
  if (String(process.env.HARNEX_SUPERVISOR_PREFETCH_JAR || "1").trim() === "0") {
    return { bytes: 0, durationMs: 0, disabled: true };
  }
  const startedAt = Date.now();
  let bytes = 0;
  try {
    await new Promise((done, fail) => {
      const stream = createReadStream(jar, { highWaterMark: 8 * 1024 * 1024 });
      stream.on("data", (chunk) => { bytes += chunk.length; });
      stream.once("end", done);
      stream.once("error", fail);
    });
    return { bytes, durationMs: Date.now() - startedAt, disabled: false };
  } catch (error) {
    // The spawn remains authoritative. A failed prefetch must not turn a readable JAR
    // into a failed workspace open merely because an advisory optimization failed.
    return { bytes, durationMs: Date.now() - startedAt, disabled: false, error: error.message };
  }
}

async function openRuntime(workspace) {
  const startedAt = Date.now();
  const jar = findJar();
  if (!jar && !workspace.remoteService) throw new Error("backend jar not found; run `cd backend && mvn clean -DskipTests package`");
  const portLease = await runtimePorts.reserve();
  const runtimePort = portLease.port;
  let startedChild;
  try {
    if (workspace.remoteProfile) {
      pendingRemote = workspace;
      emitLifecycle("OPENING", { workspaceId: workspace.id });
      const remote = await openRemoteBridge(workspace, { jar, userDataDir: USER_HOME, owner: DOMAIN.id, port: runtimePort });
      remoteRuntime = remote;
      runtimeChild = remote.child;
      startedChild = remote.child;
      remote.child.once('exit', portLease.release);
      runtimeAccessToken = remote.descriptor.token;
      runtimeControlToken = remote.descriptor.supervisorToken;
      state.instanceId = remote.descriptor.instanceId;
      state.runtimePort = runtimePort;
      state.lastWorkspaceId = workspace.id;
      workspace.lastOpened = new Date().toISOString();
      upsertCatalogWorkspace(workspace);
      const credentialFile = join(DOMAIN.dir, 'remote-session.json');
      atomicWriteJsonSync(credentialFile, {token:runtimeAccessToken});
      publishDomainManifestSync(DOMAIN, {
        schemaVersion:3, runtimeProfile:'PROJECT', authType:'bearer-json', runtimeDomainId:DOMAIN.id, ownerKind:DOMAIN.ownerKind,
        ownerPid:process.pid, runtimePid:remote.child.pid, ownerInstanceId:OWNER_INSTANCE_ID,
        canonicalRoot:workspace.root, scopeId:remote.descriptor.workspaceId, instanceId:state.instanceId,
        endpoint:`http://127.0.0.1:${state.port}`, lifecycle:'READY', protocolVersion:'1.0',
        bootTokenFile:credentialFile, configRevision:'remote', publishedAt:new Date().toISOString(),
        remote:{host:workspace.remoteHost || workspace.remoteProfile, root:workspace.root,
          runtimePid:remote.descriptor.pid, connectionId:workspace.remoteProfile},
      });
      remote.child.once('exit', () => {
        if (remoteRuntime === remote && !remote.closing) {
          runtimeChild = null;
          emitLifecycle("FAILED", { workspaceId: workspace.id, message: "SSH connection ended; remote task state is unconfirmed. Reopen this workspace to reconnect." });
        }
      });
      emitLifecycle("READY", { workspaceId: workspace.id, instanceId: state.instanceId });
      upsertCatalogWorkspace({...workspace, remoteRetainedAt:null});
      saveState();
      return;
    }
    const instanceId = randomUUID();
    const internalToken = randomBytes(32).toString("base64url");
    runtimeControlToken = internalToken;
    state.instanceId = instanceId;
    emitLifecycle("OPENING", { workspaceId: workspace.id, instanceId });
    mkdirSync(workspace.dataDir, { recursive: true });
    const logPath = join(LOG_DIR, `${workspace.id}.log`);
    const logStream = createBoundedLogStream(logPath);
    runtimeLog = logStream;
    logStream.write(`\n---- ${new Date().toISOString()} OPEN ${workspace.name} instance=${instanceId} ----\n`);
    const prefetch = await prefetchRuntimeArtifact(jar);
    logStream.write(`---- ARTIFACT_PREFETCH bytes=${prefetch.bytes} durationMs=${prefetch.durationMs}`
      + `${prefetch.disabled ? " disabled=true" : ""}${prefetch.error ? ` error=${prefetch.error}` : ""} ----\n`);
    const env = {
      ...process.env,
      ...extraEnv,
      HARNEX_WORKSPACE: workspace.root,
      HARNEX_SCOPE_ID: workspace.id,
      HARNEX_RUNTIME_PROFILE: 'PROJECT',
      HARNEX_INSTANCE_ID: instanceId,
      HARNEX_DATA_DIR: workspace.dataDir,
      HARNEX_DATADIR: workspace.dataDir,
      HARNEX_USER_DATA_DIR: USER_HOME,
      HARNEX_RUNTIME_DOMAIN_ID: DOMAIN.id,
      HARNEX_RUNTIME_DOMAIN_DIR: DOMAIN.dir,
      HARNEX_RUNTIME_OWNER_KIND: DOMAIN.ownerKind,
      HARNEX_INTERNAL_SUPERVISOR_TOKEN: internalToken,
    };
    for (const name of ["agent-config.yml", "application.yml"]) {
      const file = join(USER_HOME, name);
      if (existsSync(file)) appendConfigFile(env, file);
    }
    const startupJvmArgs = /^[1-4]$/.test(JAVA_TIERED_STOP_LEVEL)
      ? [`-XX:TieredStopAtLevel=${JAVA_TIERED_STOP_LEVEL}`]
      : [];
    runtimeChild = spawn(findJava(), [
      `-Xms${JAVA_XMS}`, `-Xmx${JAVA_XMX}`, ...startupJvmArgs, "-jar", jar,
      `--server.port=${runtimePort}`,
      '--server.address=127.0.0.1',
      `--harnex.data-dir=${workspace.dataDir}`,
      `--harnex.user-data-dir=${USER_HOME}`,
      `--harnex.runtime-domain-id=${DOMAIN.id}`,
      `--harnex.runtime-domain-dir=${DOMAIN.dir}`,
      `--harnex.runtime-owner-kind=${DOMAIN.ownerKind}`,
      ...extraArgs,
      '--harnex.runtime-profile=PROJECT',
    ], {
      cwd: workspace.root,
      env,
      stdio: ["ignore", "pipe", "pipe"],
      // POSIX process-group ownership lets closeWorkspace terminate JVM, MCP and tool
      // descendants as one Runtime Domain. Windows uses taskkill /T.
      detached: !IS_WIN,
      windowsHide: true,
    });
    runtimeChild.stdout.pipe(logStream, { end: false });
    runtimeChild.stderr.pipe(logStream, { end: false });
    const child = runtimeChild;
    startedChild = child;
    child.once('error', error => {
      logStream.write(`Runtime spawn failed: ${error.message}\n`);
      if (!child.pid) { logStream.end(); if (runtimeLog === logStream) runtimeLog = null; }
    });
    child.once('exit', portLease.release);
    child.once("exit", (code, signal) => {
      logStream.write(`\n---- exit code=${code} signal=${signal} ----\n`);
      logStream.end();
      if (runtimeLog === logStream) runtimeLog = null;
      if (runtimeChild === child) {
        runtimeChild = null;
        runtimeControlToken = null;
        unpublishDomainManifestSync(DOMAIN);
        if (state.lifecycle === "READY") emitLifecycle("FAILED", { reason: "runtime_exited", code, signal });
      }
    });
    if (!(await waitReady(runtimePort, child, { scopeId: workspace.id, instanceId,
      runtimeProfile: 'PROJECT', runtimeDomainId: DOMAIN.id, projectRoot: workspace.root }))) {
      const exitedEarly = child.exitCode !== null || child.signalCode !== null || runtimeChild !== child;
      if (!exitedEarly) {
        treeKill(child.pid, true);
        const exited = await waitForPidExit(child.pid, FORCE_KILL_TIMEOUT_MS);
        if (!exited) {
          throw new Error(`workspace runtime did not become ready and process tree ${child.pid} did not exit`);
        }
      }
      if (runtimeChild === child) runtimeChild = null;
      if (!runtimeChild) runtimeControlToken = null;
      throw new Error(exitedEarly
        ? `workspace runtime exited before readiness (code=${child.exitCode} signal=${child.signalCode})`
        : `workspace runtime did not become ready within ${HEALTH_TIMEOUT_MS}ms`);
    }
    workspace.lastOpened = new Date().toISOString();
    state.lastWorkspaceId = workspace.id;
    state.runtimePort = runtimePort;
    const startupDurationMs = Date.now() - startedAt;
    logStream.write(`---- READY durationMs=${startupDurationMs} ----\n`);
    upsertCatalogWorkspace(workspace);
    publishDomainManifestSync(DOMAIN, {
      schemaVersion: 3,
      runtimeProfile: 'PROJECT',
      runtimeDomainId: DOMAIN.id,
      ownerKind: DOMAIN.ownerKind,
      ownerPid: process.pid,
      ownerInstanceId: OWNER_INSTANCE_ID,
      runtimePid: child.pid,
      canonicalRoot: realpathSync.native(workspace.root),
      scopeId: workspace.id,
      instanceId,
      endpoint: `http://127.0.0.1:${state.port}`,
      lifecycle: "READY",
      protocolVersion: "1.0",
      bootTokenFile: DOMAIN.bootTokenFile,
      configRevision: configRevision(),
      publishedAt: new Date().toISOString(),
    });
    emitLifecycle("READY", { workspaceId: workspace.id, instanceId, durationMs: startupDurationMs });
    saveState();
  } finally {
    if (!startedChild || !isPidAlive(startedChild.pid)) portLease.release();
  }
}

async function closeRuntime(reason = "switch", stopRemote = false, detachOnly = false) {
  const startedAt = Date.now();
  if (remoteRuntime?.descriptor?.ownership === 'attached' && stopRemote)
    throw new SupervisorError(409,'external_runtime','This service is managed on the server; disconnect this client instead');
  unpublishDomainManifestSync(DOMAIN);
  if (remoteRuntime) {
    const project = activeWorkspace();
    const attached = remoteRuntime.descriptor.ownership === 'attached';
    const retain = attached || detachOnly || project?.remoteKeepRunning === true && !stopRemote;
    emitLifecycle("DRAINING", { reason });
    try {
      if (retain) await remoteRuntime.disconnect(); else await remoteRuntime.close();
      if (project) upsertCatalogWorkspace({...project, remoteRetainedAt: retain && !attached ? new Date().toISOString() : null});
    }
    catch (error) { emitLifecycle("FAILED", { message: error.message }); throw error; }
    remoteRuntime = null;
    pendingRemote = null;
    runtimeChild = null;
    runtimeAccessToken = null;
    runtimeControlToken = null;
    state.runtimePort = null;
    state.instanceId = null;
    emitLifecycle("CLOSED", { reason });
    return;
  }
  if (pendingRemote) {
    if (stopRemote) throw new Error("remote state is unconfirmed; reconnect to that workspace before stopping it");
    // A failed restore has no local connection to release. Keep the catalog entry
    // reconnectable without making the unreachable host block another project.
    upsertCatalogWorkspace({...pendingRemote, remoteRetainedAt: pendingRemote.remoteService ? null : new Date().toISOString()});
    pendingRemote = null;
    runtimeAccessToken = null;
  }
  const child = runtimeChild;
  const port = state.runtimePort;
  if (!child) {
    state.runtimePort = null;
    state.instanceId = null;
    runtimeControlToken = null;
    emitLifecycle("CLOSED", { reason, durationMs: 0 });
    return;
  }
  emitLifecycle("DRAINING", { reason });
  const drainStartedAt = Date.now();
  let drainStatus = 0;
  if (port) {
    drainStatus = await requestRuntime(port, `/api/v1/admin/drain?timeoutMs=${DRAIN_TIMEOUT_MS}`, "POST",
      DRAIN_TIMEOUT_MS + 1000, { "X-Harnex-Supervisor-Token": runtimeControlToken || "" });
  }
  const drainDurationMs = Date.now() - drainStartedAt;
  emitLifecycle("CLOSING", { reason, drainDurationMs, drainStatus });
  const pid = child.pid;
  const terminationStartedAt = Date.now();
  // taskkill without /F has no useful graceful-shutdown semantics for a hidden JVM on
  // Windows. DRAIN above is the grace barrier, so terminate the tree immediately there.
  // POSIX still gets SIGTERM first, then a bounded SIGKILL fallback.
  let forced = IS_WIN;
  treeKill(pid, forced);
  let exited = await waitForPidExit(pid, CLOSE_TIMEOUT_MS);
  if (!exited) {
    forced = true;
    treeKill(pid, true);
    exited = await waitForPidExit(pid, FORCE_KILL_TIMEOUT_MS);
  }
  if (!exited) {
    emitLifecycle("FAILED", { reason: "close_failed", pid, drainDurationMs });
    throw new Error(`workspace runtime process tree ${pid} did not exit`);
  }
  const terminationDurationMs = Date.now() - terminationStartedAt;
  runtimeChild = null;
  runtimeControlToken = null;
  state.runtimePort = null;
  state.instanceId = null;
  emitLifecycle("CLOSED", {
    reason,
    durationMs: Date.now() - startedAt,
    drainDurationMs,
    terminationDurationMs,
    forced,
  });
}

class SupervisorError extends Error {
  constructor(status, code, message, details = {}) {
    super(message);
    this.status = status;
    this.code = code;
    this.details = details;
  }
}

function beginTransition(kind, targetId, work) {
  if (shuttingDown) throw new SupervisorError(409, 'host_closing', 'Runtime host is shutting down');
  if (transition) {
    if (kind === "switch" && transition.kind === kind && transition.targetId === targetId) {
      return transition.promise;
    }
    throw new SupervisorError(409, "workspace_transition_in_progress",
      "another workspace lifecycle transition is already in progress", {
        phase: state.lifecycle,
        targetWorkspaceId: transition.targetId,
      });
  }
  const current = { kind, targetId, promise: null };
  current.promise = Promise.resolve().then(work).catch(error => {
    // A failed close/reconnect must not leave the shell displaying an endless
    // transition. Retain ownership, but expose FAILED so recovery stays available.
    if (["DRAINING", "CLOSING", "OPENING"].includes(state.lifecycle)) {
      emitLifecycle("FAILED", {workspaceId:state.activeWorkspaceId, targetWorkspaceId:targetId, message:error.message});
    }
    throw error;
  }).finally(() => {
    if (transition === current) transition = null;
  });
  transition = current;
  return current.promise;
}

function assertExpectedInstance(expectedInstanceId) {
  if (expectedInstanceId && expectedInstanceId !== state.instanceId) {
    throw new SupervisorError(409, "runtime_instance_changed",
      "the active workspace instance changed; refresh and retry", {
        workspaceId: state.activeWorkspaceId,
        instanceId: state.instanceId,
      });
  }
}

async function assertCanStopRuntime(stopActiveRun, stopRemote = false) {
  if (remoteRuntime?.descriptor?.ownership === 'attached' && !stopRemote) return;
  if (remoteRuntime && activeWorkspace()?.remoteKeepRunning === true && !stopRemote) return;
  if (!runtimeChild || !state.runtimePort || stopActiveRun) return;
  const status = await requestRuntimeJson(state.runtimePort, "/api/v1/admin/status");
  if (status.status !== 200) {
    throw new SupervisorError(503, "runtime_status_unavailable",
      "cannot verify whether the current Runtime has active work");
  }
  const activeRuns = Number(status.body?.activeRuns || 0);
  if (activeRuns > 0) {
    throw new SupervisorError(409, "active_run",
      "the current workspace has an active task; confirm stopping it before switching", { activeRuns });
  }
}

function switchWorkspace(id, options = {}) {
  if (options.idleOnly && transition) throw new SupervisorError(409, 'workspace_transition_in_progress', '宿主正在切换项目，请稍后重试');
  return beginTransition("switch", id, async () => {
    const target = getWorkspace(id);
    if (!target) throw new SupervisorError(404, "workspace_not_found", "workspace not found");
    assertExpectedInstance(options.expectedInstanceId);
    if (options.idleOnly && (remoteRuntime || target.remoteProfile || state.lifecycle !== 'READY' || !runtimeChild)) {
      throw new SupervisorError(409, 'local_runtime_required', '宿主必须处于本地项目就绪状态');
    }
    if (options.upgradeRemote) {
      if (!remoteRuntime || state.activeWorkspaceId !== id || state.lifecycle !== 'READY')
        throw new SupervisorError(409,'remote_workspace_required','Reconnect to the managed remote project before updating');
      if (remoteRuntime.descriptor.ownership === 'attached') throw new SupervisorError(409,'external_runtime','Update this service on its server');
      try { await remoteRuntime.upgrade(); }
      catch (error) {
        if (/UPGRADE_BUSY|UPGRADE_UNSUPPORTED/.test(error.message)) throw new SupervisorError(409,'remote_upgrade_unavailable',error.message);
        emitLifecycle('FAILED',{workspaceId:id,message:error.message}); throw error;
      }
      await remoteRuntime.disconnect(); remoteRuntime=null; runtimeChild=null;
      try { await openRuntime(target); return publicWorkspace(getWorkspace(id)); }
      catch(error) { emitLifecycle('FAILED',{workspaceId:id,message:error.message}); throw error; }
    }
    if (options.restartIfCurrent && target.remoteService) throw new SupervisorError(409,'external_runtime','This service is managed on the server; reconnect to it instead of restarting it');
    if (target.remoteProfile && state.activeWorkspaceId === id && state.lifecycle !== "READY" && !options.restartIfCurrent) {
      await remoteRuntime?.disconnect();
      remoteRuntime = null;
      runtimeChild = null;
      try { await openRuntime(target); }
      catch (error) {
        state.instanceId = null;
        emitLifecycle("FAILED", {workspaceId:id, message:error.message});
        throw error;
      }
      return publicWorkspace(target);
    }
    if (!options.restartIfCurrent && state.activeWorkspaceId === id
        && state.lifecycle === "READY" && runtimeChild) {
      return publicWorkspace(target);
    }
    const stopRemote = options.restartIfCurrent === true && state.activeWorkspaceId === id;
    const attachingCurrent = Boolean(target.remoteService && remoteRuntime && target.remoteProfile === activeWorkspace()?.remoteProfile
      && target.runtimeScopeId === remoteRuntime.descriptor.workspaceId && target.remoteService.domainId === remoteRuntime.descriptor.domainId);
    if (options.idleOnly) {
      const stopped = await requestRuntimeJson(state.runtimePort, '/api/v1/admin/upgrade-stop-idle', 5000, 'POST');
      if (stopped.status !== 200) throw new SupervisorError(stopped.status === 409 ? 409 : 503, 'project_switch_unavailable',
        stopped.status === 409 ? '当前 Runtime 有任务或会话操作尚未结束，请结束后在 VS Code 重试连接。' : '无法确认 Runtime 可安全切换，请检查宿主状态');
    } else if (!attachingCurrent) await assertCanStopRuntime(options.stopActiveRun === true, stopRemote);
    const previousId = state.activeWorkspaceId;
    if (runtimeChild || remoteRuntime || pendingRemote) await closeRuntime("workspace_switch", stopRemote, attachingCurrent);
    state.activeWorkspaceId = id;
    state.instanceId = null;
    emitLifecycle("CLOSED", { previousWorkspaceId: previousId, targetWorkspaceId: id });
    try {
      await openRuntime(target);
      return publicWorkspace(target);
    } catch (e) {
      if (target.remoteProfile) {
        state.activeWorkspaceId = id;
        saveState();
        emitLifecycle("FAILED", { workspaceId: id, message: e.message });
        throw e;
      }
      if (runtimeChild) {
        try { await closeRuntime("open_failed"); } catch {}
      }
      state.activeWorkspaceId = null;
      state.instanceId = null;
      state.runtimePort = null;
      if (!runtimeChild) runtimeControlToken = null;
      saveState();
      emitLifecycle("FAILED", { targetWorkspaceId: id, message: e.message });
      throw e;
    }
  });
}

function closeWorkspace(options = {}) {
  return beginTransition("close", state.activeWorkspaceId, async () => {
    assertExpectedInstance(options.expectedInstanceId);
    await assertCanStopRuntime(options.stopActiveRun === true, options.stopRemote === true);
    await closeRuntime("user_close", options.stopRemote === true);
    state.activeWorkspaceId = null;
    saveState();
  });
}

/** Open a native directory chooser on the local machine. Cancellation is not an error. */
function pickDirectory() {
  let result;
  if (IS_WIN) {
    const script = [
      "Add-Type -AssemblyName System.Windows.Forms",
      "$dialog = New-Object System.Windows.Forms.FolderBrowserDialog",
      "$dialog.Description = '选择 Harnex 工作区 / Select Harnex workspace'",
      "$dialog.ShowNewFolderButton = $true",
      "if ($dialog.ShowDialog() -eq [System.Windows.Forms.DialogResult]::OK) {",
      "  [Console]::OutputEncoding = [System.Text.Encoding]::UTF8",
      "  Write-Output $dialog.SelectedPath",
      "}",
    ].join("; ");
    result = spawnSync("powershell.exe", ["-NoProfile", "-STA", "-Command", script], {
      encoding: "utf8", windowsHide: true,
    });
  } else if (platform() === "darwin") {
    result = spawnSync("osascript", ["-e",
      'POSIX path of (choose folder with prompt "Select Harnex workspace")'], { encoding: "utf8" });
  } else {
    result = spawnSync("zenity", ["--file-selection", "--directory", "--title=Select Harnex workspace"],
      { encoding: "utf8" });
    if (result.error?.code === "ENOENT") {
      result = spawnSync("kdialog", ["--getexistingdirectory", homedir()], { encoding: "utf8" });
    }
  }
  if (result?.status !== 0) return null;
  const selected = String(result?.stdout || "").trim();
  if (!selected) return null;
  const absolute = resolve(selected);
  return existsSync(absolute) && statSync(absolute).isDirectory() ? absolute : null;
}

/**
 * Read-only directory projection for the browser-hosted picker. Browsers cannot return a
 * server-side absolute path from the File System Access API, so WebUI navigation stays in
 * the Harnex page and asks its loopback Supervisor for directory names only.
 */
async function browseDirectory(requestedPath) {
  const requested = typeof requestedPath === "string" && requestedPath.trim()
    ? resolve(requestedPath.trim())
    : resolve(homedir());
  let current;
  try {
    current = realpathSync.native(requested);
    if (!statSync(current).isDirectory()) {
      throw new SupervisorError(400, "invalid_directory", "the selected path is not a directory");
    }
  } catch (error) {
    if (error instanceof SupervisorError) throw error;
    throw new SupervisorError(400, "invalid_directory", `directory is unavailable: ${requested}`);
  }

  let entries;
  try {
    entries = readdirSync(current, { withFileTypes: true });
  } catch {
    throw new SupervisorError(403, "directory_unreadable", `directory cannot be read: ${current}`);
  }
  const directories = entries.flatMap(entry => {
    const path = join(current, entry.name);
    try {
      if (!entry.isDirectory() && !(entry.isSymbolicLink() && statSync(path).isDirectory())) return [];
      return [{ name: entry.name, path, hidden: entry.name.startsWith(".") }];
    } catch {
      return [];
    }
  }).sort((left, right) => left.name.localeCompare(right.name, undefined, {
    numeric: true, sensitivity: "base",
  }));
  const parent = dirname(current);
  return {
    path: current,
    parentPath: parent === current ? null : parent,
    directories,
    locations: [
      { kind: "home", name: "Home", path: realpathSync.native(homedir()) },
      ...(await filesystemRoots()).map(path => ({ kind: "root", name: path, path })),
    ].filter((location, index, all) =>
      all.findIndex(candidate => candidate.path === location.path) === index),
  };
}

const MIME = {
  ".html": "text/html; charset=utf-8", ".js": "application/javascript; charset=utf-8",
  ".css": "text/css; charset=utf-8", ".json": "application/json; charset=utf-8",
  ".svg": "image/svg+xml", ".png": "image/png", ".ico": "image/x-icon", ".woff2": "font/woff2",
};

function serveStatic(req, res) {
  const dist = join(REPO_ROOT, "frontend", "dist");
  if (!existsSync(dist)) return json(res, 503, { error: "frontend_not_built" });
  const pathname = (req.url || "/").split("?")[0];
  const relative = pathname === "/" || !extname(pathname) ? "index.html" : pathname.replace(/^\/+/, "");
  const file = resolve(dist, relative);
  if (!file.startsWith(resolve(dist))) return json(res, 403, { error: "forbidden" });
  const target = existsSync(file) && statSync(file).isFile() ? file : join(dist, "index.html");
  res.writeHead(200, {
    "Content-Type": MIME[extname(target).toLowerCase()] || "application/octet-stream",
    "Cache-Control": target.endsWith("index.html") ? "no-cache" : "public, max-age=3600",
  });
  createReadStream(target).pipe(res);
}

async function proxy(req, res) {
  const path = (req.url || "/").split("?")[0];
  if (req.headers.origin === 'null' && !path.startsWith('/api/v1/preview-content/')) {
    return jsonError(res, 403, 'invalid_runtime_origin', 'Preview documents cannot call the Runtime API');
  }
  if (path.startsWith("/api/v1/admin/")) {
    return jsonError(res, 404, "not_found", "resource not found");
  }
  if (!authorizeRuntimeMutation(req, res)) return;
  const workspace = activeWorkspace();
  // Embedded preview requests cannot attach client headers. Their grant path pins the same Runtime identity;
  // the Runtime validates the unguessable, expiring resource grant before serving any bytes.
  const previewTarget = /^\/api\/v1\/preview-content\/([A-Za-z0-9_-]+)\/([A-Za-z0-9_-]+)\/[A-Za-z0-9_-]{43}\//.exec(path);
  const clientInstance = previewTarget?.[2] || req.headers["x-harnex-instance-id"];
  const clientScope = previewTarget?.[1] || req.headers['x-harnex-scope-id'];
  const personalSelected = clientScope === personal.paths.scopeId || (!clientScope && !workspace);
  let personalSession = personalSelected ? personal.session : null;
  // Capture all routing and response identity before opening the upstream connection.
  // Project switches can happen while a body or SSE response is still arriving.
  const target = personalSelected && personal.lifecycle === 'READY' && personal.session
    ? { scopeId: personal.paths.scopeId, instanceId: personal.session.manifest.instanceId,
        port: new URL(personal.session.endpoint).port, token: personal.session.token }
    : !personalSelected && workspace && state.lifecycle === 'READY' && state.runtimePort
      ? { scopeId: workspace.runtimeScopeId || workspace.id, instanceId: state.instanceId, port: state.runtimePort, token: runtimeAccessToken }
      : null;
  if (!target) return jsonError(res, 503, 'scope_not_ready', 'The requested space is unavailable');
  if ((clientInstance && clientInstance !== target.instanceId) || (clientScope && clientScope !== target.scopeId)) {
    return json(res, 409, {
      error: { code: 'runtime_instance_changed', message: 'The requested Runtime instance changed; reconnect to the same space' },
      scopeId: target.scopeId, instanceId: target.instanceId,
    });
  }
  const failAuthentication = error => {
    if (res.destroyed) return;
    err(`personal Runtime authentication renewal failed: ${error.message}`);
    jsonError(res, error.status || 502, error.code || 'runtime_auth_refresh_failed', error.message);
  };
  if (personalSession) {
    try { personalSession = await personal.requestSession(personalSession); target.token = personalSession.token; }
    catch (error) { return failAuthentication(error); }
    if (req.aborted || res.destroyed) return;
  }
  const blocked = new Set(["connection", "keep-alive", "proxy-authenticate", "proxy-authorization",
    "te", "trailers", "transfer-encoding", "upgrade", "host", "origin", "referer",
    "x-harnex-supervisor-token"]);
  const headers = {};
  for (const [key, value] of Object.entries(req.headers)) if (!blocked.has(key.toLowerCase())) headers[key] = value;
  headers.host = `127.0.0.1:${target.port}`;
  headers['x-harnex-scope-id'] = target.scopeId;
  headers['x-harnex-instance-id'] = target.instanceId;
  let upstream;
  const send = (retried = false) => {
    if (target.token) headers.authorization = `Bearer ${target.token}`;
    upstream = httpRequest({
      host: "127.0.0.1", port: target.port, path: req.url, method: req.method, headers,
    }, (upstreamResponse) => {
      // Recover revoked/expired credentials for safe reads (catalog, history, SSE).
      // Never replay uploads or writes whose incoming body has already been consumed.
      if (personalSession && upstreamResponse.statusCode === 401 && !retried
          && ['GET', 'HEAD'].includes(req.method) && !req.headers['transfer-encoding']
          && Number(req.headers['content-length'] || 0) === 0) {
        upstreamResponse.resume();
        void personal.requestSession(personalSession, { refresh: true }).then(session => {
          if (req.aborted || res.destroyed) return;
          personalSession = session; target.token = session.token; send(true);
        }).catch(failAuthentication);
        return;
      }
      const responseHeaders = {};
      for (const [key, value] of Object.entries(upstreamResponse.headers)) {
        if (!blocked.has(key.toLowerCase())) responseHeaders[key] = value;
      }
      responseHeaders['x-harnex-scope-id'] = target.scopeId;
      responseHeaders['x-harnex-instance-id'] = target.instanceId;
      res.writeHead(upstreamResponse.statusCode || 502, responseHeaders);
      upstreamResponse.pipe(res);
    });
    upstream.on("error", (e) => {
      if (res.destroyed) return;
      if (!res.headersSent) json(res, 502, { error: { code: "runtime_unreachable", message: e.message } });
      else res.destroy();
    });
    if (retried) upstream.end();
    else req.pipe(upstream);
  };
  req.on("aborted", () => upstream?.destroy());
  res.on('close', () => upstream?.destroy());
  send();
}

function json(res, status, body) {
  res.setHeader("Cache-Control", "no-store");
  res.writeHead(status, { "Content-Type": "application/json; charset=utf-8" });
  res.end(JSON.stringify(body));
}

function jsonError(res, status, code, message, details = {}) {
  return json(res, status, { error: { code, message, details } });
}

function safeTokenEquals(supplied) {
  if (typeof supplied !== "string" || !supplied) return false;
  const left = Buffer.from(CONTROL_TOKEN);
  const right = Buffer.from(supplied);
  return left.length === right.length && timingSafeEqual(left, right);
}

function trustedRequestOrigin(req) {
  try {
    const host = new URL(`http://${req.headers.host || ""}`);
    if (WEB_ORIGIN && host.host === WEB_ORIGIN.host) return WEB_ORIGIN;
    return ["127.0.0.1", "localhost", "[::1]"].includes(host.hostname) ? host : null;
  } catch {
    return null;
  }
}

function authorizeControlMutation(req, res) {
  const requestHost = trustedRequestOrigin(req);
  if (!requestHost) {
    jsonError(res, 403, "invalid_control_origin", "Supervisor control API requires a loopback or configured Web origin");
    return false;
  }
  const origin = req.headers.origin;
  if (origin) {
    try {
      if (new URL(origin).origin !== requestHost.origin) {
        jsonError(res, 403, "invalid_control_origin", "cross-origin Supervisor mutation rejected");
        return false;
      }
    } catch {
      jsonError(res, 403, "invalid_control_origin", "invalid Origin header");
      return false;
    }
  }
  if (!safeTokenEquals(req.headers["x-harnex-control-token"])) {
    jsonError(res, 403, "invalid_control_token", "Supervisor control token is missing or invalid");
    return false;
  }
  return true;
}

function authorizeRuntimeMutation(req, res) {
  if (["GET", "HEAD", "OPTIONS"].includes(req.method || "GET")) return true;
  const requestHost = trustedRequestOrigin(req);
  if (!requestHost) {
    jsonError(res, 403, "invalid_runtime_origin", "Runtime mutation requires a loopback or configured Web origin");
    return false;
  }
  const fetchSite = String(req.headers["sec-fetch-site"] || "").toLowerCase();
  if (fetchSite && !["same-origin", "none"].includes(fetchSite)) {
    jsonError(res, 403, "invalid_runtime_origin", "cross-site Runtime mutation rejected");
    return false;
  }
  const origin = req.headers.origin;
  if (origin) {
    try {
      if (new URL(origin).origin !== requestHost.origin) {
        jsonError(res, 403, "invalid_runtime_origin", "cross-origin Runtime mutation rejected");
        return false;
      }
    } catch {
      jsonError(res, 403, "invalid_runtime_origin", "invalid Origin header");
      return false;
    }
  }
  return true;
}

function respondError(res, error) {
  const status = Number(error?.status) || 500;
  return jsonError(res, status, error?.code || "internal_error",
    error?.message || "Supervisor operation failed", error?.details || {});
}

function body(req, max = 1 << 20) {
  return new Promise((done, fail) => {
    const chunks = [];
    let size = 0;
    req.on("data", (chunk) => {
      size += chunk.length;
      if (size > max) { fail(new Error("body too large")); req.destroy(); }
      else chunks.push(chunk);
    });
    req.on("end", () => {
      try { done(chunks.length ? JSON.parse(Buffer.concat(chunks).toString("utf8")) : {}); }
      catch (e) { fail(e); }
    });
    req.on("error", fail);
  });
}

async function handleControl(req, res, path) {
  if (FIXED_WORKSPACE && (path.startsWith('/supervisor/api/connections')
      || ['/supervisor/api/remote-workspaces', '/supervisor/api/directories', '/supervisor/api/pick-directory'].includes(path)
      || path.startsWith('/supervisor/api/workspaces') && req.method !== 'GET'))
    return jsonError(res, 403, 'fixed_workspace', 'This host uses a fixed workspace');
  if (path === "/supervisor/api/connections" && req.method === "GET") {
    try { return json(res, 200, await remoteCommand(['list'], { userDataDir: USER_HOME })); }
    catch (error) { return respondError(res, error); }
  }
  if (path.startsWith("/supervisor/api/connections") || path === "/supervisor/api/remote-workspaces") {
    if (!authorizeControlMutation(req, res)) return;
    try {
      const options = { userDataDir: USER_HOME };
      if (path === "/supervisor/api/connections/interaction" && req.method === "POST") {
        const request = await body(req, 65_536);
        if (request.args?.operation === 'prepare') options.jar = findJar();
        return json(res, 200, await remoteInteraction(request.action, request.args || {}, options));
      }
      if (path === "/supervisor/api/connections" && req.method === "PUT") {
        const profile = await body(req);
        return json(res, 200, await remoteCommand(['save', '--profile-json', JSON.stringify(profile)], options));
      }
      if (path === "/supervisor/api/connections/inspect" && req.method === "POST") {
        const request = await body(req);
        if (!['probe', 'directories'].includes(request.operation)) throw new Error('invalid connection inspection');
        return json(res, 200, await remoteCommand([request.operation === 'probe' ? 'test' : 'directories', request.id,
          ...(request.root ? ['--workspace', request.root] : [])], options));
      }
      if (path === "/supervisor/api/remote-workspaces" && req.method === "POST") {
        const request = await body(req);
        const described = request.preparationId ? await remoteInteraction('prepared', {id:request.preparationId,profileId:request.profileId,root:request.root}, options)
          : await remoteCommand(['describe', request.profileId, '--workspace', request.root], {...options,jar:findJar()});
        const profiles = await remoteCommand(['list'], options);
        const profile = profiles.find(item => item.id === request.profileId);
        if (!profile) throw new Error('remote connection was removed');
        const item = { id:described.catalogId || described.workspaceId, root:described.root, name:described.root.split('/').pop() || described.root,
          remoteProfile:request.profileId, remoteHost:profile.port ? `${profile.host}:${profile.port}` : profile.host,
          ...(described.service ? {remoteService:described.service,runtimeScopeId:described.workspaceId} : {}),
          dataDir:null, createdAt:new Date().toISOString(), lastOpened:null };
        const existing = getWorkspace(item.id);
        const registered = existing ? {...existing, root:item.root, remoteProfile:item.remoteProfile, remoteHost:item.remoteHost,
          ...(item.remoteService ? {remoteService:item.remoteService,runtimeScopeId:item.runtimeScopeId} : {})} : item;
        return json(res, 201, publicWorkspace(upsertCatalogWorkspace(registered)));
      }
      const connection = path.match(/^\/supervisor\/api\/connections\/([^/]+)$/);
      if (connection && req.method === "DELETE") {
        const id = decodeURIComponent(connection[1]);
        if (activeWorkspace()?.remoteProfile === id) throw new Error('close the active remote workspace before removing its connection');
        return json(res, 200, await remoteCommand(['remove', id], options));
      }
      return jsonError(res, 404, "not_found", "unknown connection operation");
    } catch (error) { return respondError(res, error); }
  }
  if (path === "/supervisor/api/status" && req.method === "GET") {
    return json(res, 200, {
      runtimeDomainId: DOMAIN.id,
      lifecycle: state.lifecycle, activeWorkspaceId: state.activeWorkspaceId,
      instanceId: state.instanceId,
      transition: transition ? { kind: transition.kind, targetWorkspaceId: transition.targetId } : null,
      capabilities: ["workspace.lifecycle", "scope.instance-guard", "scope.personal",
        ...(FIXED_WORKSPACE ? [] : ["workspaces.registry", "filesystem.directory-browser"])],
      controlToken: CONTROL_TOKEN,
    });
  }
  if (path === "/supervisor/api/workspaces" && req.method === "GET") {
    state.workspaces = loadCatalogWorkspaces();
    return json(res, 200, {
      workspaces: state.workspaces.map(publicWorkspace),
      fixedWorkspaceId: FIXED_WORKSPACE?.id || null,
      activeWorkspaceId: state.activeWorkspaceId,
      lastWorkspaceId: state.lastWorkspaceId,
      lifecycle: state.lifecycle,
      failureMessage: state.failureMessage,
      instanceId: state.instanceId,
      personal: personal.view(),
    });
  }
  if (path === '/supervisor/api/personal' && req.method === 'POST') {
    if (!authorizeControlMutation(req, res)) return;
    try {
      const request = await body(req);
      if (request.restartIfCurrent) {
        if (personal.session && !personal.child) throw new SupervisorError(409, 'runtime_not_owned',
          'This personal Runtime belongs to another host; restart it from its owning host');
        await personal.close();
      }
      return json(res, 200, await personal.open());
    }
    catch (error) { return respondError(res, error); }
  }
  if (path === "/supervisor/api/pick-directory" && req.method === "POST") {
    if (!authorizeControlMutation(req, res)) return;
    try { return json(res, 200, { path: pickDirectory() }); }
    catch (e) { return respondError(res, e); }
  }
  if (path === "/supervisor/api/directories" && req.method === "POST") {
    if (!authorizeControlMutation(req, res)) return;
    try {
      const request = await body(req);
      return json(res, 200, await browseDirectory(request.path));
    } catch (e) { return respondError(res, e); }
  }
  if (path === "/supervisor/api/workspaces" && req.method === "POST") {
    if (!authorizeControlMutation(req, res)) return;
    try { return json(res, 201, publicWorkspace(registerWorkspace(await body(req)))); }
    catch (e) {
      if (!(e instanceof SupervisorError)) {
        e.status = 400;
        e.code = "invalid_workspace";
      }
      return respondError(res, e);
    }
  }
  const match = path.match(/^\/supervisor\/api\/workspaces\/([^/]+)$/);
  if (match && req.method === "PATCH") {
    if (!authorizeControlMutation(req, res)) return;
    try {
      const request = await body(req);
      if (transition) throw new SupervisorError(409, "workspace_transition", "workspace is changing; retry after it is ready");
      assertExpectedInstance(request.expectedInstanceId);
      const project = getWorkspace(match[1]);
      if (!project?.remoteProfile || project.id !== state.activeWorkspaceId || state.lifecycle !== "READY")
        throw new SupervisorError(409, "remote_workspace_required", "connect to the SSH workspace before changing its background behavior");
      if (project.remoteService) throw new SupervisorError(409,'external_runtime','An existing service always stays on its server when this client disconnects');
      if (typeof request.remoteKeepRunning !== 'boolean') throw new SupervisorError(400, "invalid_setting", "remoteKeepRunning must be boolean");
      return json(res, 200, publicWorkspace(upsertCatalogWorkspace({...project, remoteKeepRunning:request.remoteKeepRunning})));
    } catch (error) { return respondError(res, error); }
  }
  if (match && req.method === "DELETE") {
    if (!authorizeControlMutation(req, res)) return;
    try {
      if (!removeWorkspace(match[1])) {
        return jsonError(res, 404, "workspace_not_found", "workspace not found");
      }
      return json(res, 200, { ok: true });
    } catch (e) {
      if (!(e instanceof SupervisorError)) {
        e.status = 409;
        e.code = "workspace_active";
      }
      return respondError(res, e);
    }
  }
  if (path === "/supervisor/api/switch" && req.method === "POST") {
    if (!authorizeControlMutation(req, res)) return;
    try {
      const request = await body(req);
      return json(res, 200, await switchWorkspace(request.id, {
        expectedInstanceId: request.expectedInstanceId,
        stopActiveRun: request.stopActiveRun === true,
        restartIfCurrent: request.restartIfCurrent === true,
        upgradeRemote: request.upgradeRemote === true,
      }));
    }
    catch (e) { return respondError(res, e); }
  }
  if (path === "/supervisor/api/close" && req.method === "POST") {
    if (!authorizeControlMutation(req, res)) return;
    try {
      const request = await body(req);
      await closeWorkspace({
        expectedInstanceId: request.expectedInstanceId,
        stopActiveRun: request.stopActiveRun === true,
        stopRemote: request.stopRemote === true,
      });
      return json(res, 200, { lifecycle: state.lifecycle });
    }
    catch (e) { return respondError(res, e); }
  }
  if (path === "/supervisor/api/active" && req.method === "GET") {
    const current = activeWorkspace();
    return json(res, 200, current ? publicWorkspace(current) : { id: null, state: state.lifecycle });
  }
  if (path === "/supervisor/api/events" && req.method === "GET") {
    res.writeHead(200, {
      "Content-Type": "text/event-stream", "Cache-Control": "no-cache", Connection: "keep-alive",
    });
    lifecycleClients.add(res);
    emitLifecycle(state.lifecycle);
    req.on("close", () => lifecycleClients.delete(res));
    return;
  }
  return jsonError(res, 404, "not_found", "resource not found");
}

function handle(req, res) {
  const path = (req.url || "/").split("?")[0];
  if (path.startsWith("/supervisor/api/")) {
    return handleControl(req, res, path).catch((e) => respondError(res, e));
  }
  if (path.startsWith("/api/") || path.startsWith("/actuator/") || path.startsWith("/sse/")) {
    return proxy(req, res).catch(e => respondError(res, e));
  }
  return serveStatic(req, res);
}

function readOwner() {
  const owner = readJsonSync(DOMAIN.ownerFile, null);
  return owner ? validateDomainOwner(owner, DOMAIN) : null;
}

function cleanupStoppedOwner(owner) {
  const current = readOwner();
  // A concurrent start may have replaced the lease while we waited for the old process.
  if (current?.ownerPid !== owner?.ownerPid || current?.ownerInstanceId !== owner?.ownerInstanceId) return;
  cleanupStaleDomainSync(DOMAIN);
  if (current) {
    try { unlinkSync(DOMAIN.ownerFile); } catch {}
  }
}

async function shutdown(reason) {
  shuttingDown = true;
  workspaceControl?.close();
  workspaceControl = null;
  try { await transition?.promise; } catch {}
  unpublishDomainManifestSync(DOMAIN);
  try { await closeRuntime(reason); } catch {}
  try { await personal.close(); } catch (error) { err(error.message); }
  releaseDomainOwnerSync(DOMAIN, OWNER_INSTANCE_ID);
}

export async function cmdSupervisor(args = []) {
  loadState();
  if (FIXED_WORKSPACE) registerWorkspace({ root: FIXED_WORKSPACE.root });
  // Saved ports locate a running owner for status/stop; a new start uses current options/defaults.
  state.port = resolveWebPort(args, process.env, ['HARNEX_SUPERVISOR_PORT']);
  for (const arg of args) {
    let match;
    if ((match = /^--child-arg=(.*)$/s.exec(arg))) extraArgs.push(match[1]);
    else if ((match = /^--child-env=([^=]+)=(.*)$/s.exec(arg))) extraEnv[match[1]] = match[2];
    else if (arg === "--require-auth") extraEnv.HARNEX_AUTH_ENABLED = "true";
  }
  claimDomainOwnerSync(DOMAIN, OWNER_INSTANCE_ID);
  unpublishDomainManifestSync(DOMAIN);
  saveState();
  if (!(await portFree(state.port))) {
    releaseDomainOwnerSync(DOMAIN, OWNER_INSTANCE_ID);
    throw new Error(`supervisor port ${state.port} is already in use`);
  }
  const reopenId = state.lastWorkspaceId || [...state.workspaces]
    .sort((left, right) => String(right.lastOpened || right.createdAt || "")
      .localeCompare(String(left.lastOpened || left.createdAt || "")))[0]?.id;
  if (reopenId && state.lastWorkspaceId !== reopenId) {
    state.lastWorkspaceId = reopenId;
    saveState();
  }
  const server = createServer(handle);
  try {
    await new Promise((done, fail) => {
      server.once("error", fail);
      server.listen(state.port, BIND_HOST, done);
    });
  } catch (error) {
    releaseDomainOwnerSync(DOMAIN, OWNER_INSTANCE_ID);
    throw error;
  }
  out(`Harnex Supervisor: http://${BIND_HOST}:${state.port}`);
  try {
    workspaceControl = await serveWorkspaceControl(DOMAIN, OWNER_INSTANCE_ID, async (root, expectedInstanceId) => {
      if (shuttingDown) throw new Error('Runtime host is shutting down');
      const target = registerWorkspace({ root });
      await switchWorkspace(target.id, { expectedInstanceId, idleOnly: true });
      return readJsonSync(DOMAIN.manifestFile, null);
    });
  } catch (error) { err(`workspace following unavailable: ${error.message}`); }
  out(`Runtime domain: ${DOMAIN.id} (${DOMAIN.dir})`);
  out(`Workspace registry: ${REGISTRY_FILE}`);
  // Managed local hosts warm personal space; the fixed Docker host starts only the selected view.
  // open() coalesces callers when the workbench attaches during startup.
  if (!FIXED_WORKSPACE) void personal.open().catch((e) => {
    err(`cannot warm personal runtime (${e.message}); personal space can be retried from the workbench`);
  });
  // Expose the stable shell immediately, then restore the last project Runtime in the
  // background. This matches the desktop splash behavior without weakening READY:
  // Runtime API proxying remains blocked until the fresh JVM passes readiness.
  // SSH restoration is initiated by the workbench so its authentication window
  // is mounted before starting a connection (including password and host trust).
  if (!FIXED_WORKSPACE && reopenId && getWorkspace(reopenId) && !getWorkspace(reopenId).remoteProfile) {
    void switchWorkspace(reopenId).catch((e) => {
      err(`cannot reopen last workspace (${e.message}); opening with no active workspace`);
    });
  }
  for (const signal of ["SIGINT", "SIGTERM", "SIGHUP"]) {
    process.on(signal, async () => { await shutdown(signal); server.close(); process.exit(0); });
  }
}

export async function cmdSupervisorStop() {
  ensureControlHome();
  const owner = readOwner();
  if (!owner || !isDomainOwnerAlive(owner, { requireIdentity: true })) {
    cleanupStoppedOwner(owner);
    out("supervisor is not running");
    return;
  }
  const pid = owner.ownerPid;
  treeKill(pid, true);
  if (!await waitForPidExit(pid, FORCE_KILL_TIMEOUT_MS)) {
    throw new Error(`supervisor (pid ${pid}) did not exit; owner lease retained`);
  }
  cleanupStoppedOwner(owner);
  out(`supervisor (pid ${pid}) stopped`);
}

export function cmdSupervisorStatus() {
  loadState();
  const owner = readOwner();
  out(`Supervisor  ${owner && isDomainOwnerAlive(owner) ? `running (pid ${owner.ownerPid})` : "stopped"}`);
  out(`Lifecycle   ${state.lifecycle}`);
  out(`Workspaces  ${state.workspaces.length}`);
  for (const item of state.workspaces) {
    out(`  ${item.id === state.activeWorkspaceId ? "●" : "○"} ${item.name}  ${item.root}`);
  }
}
