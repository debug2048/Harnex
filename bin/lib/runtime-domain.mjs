import {
  chmodSync, closeSync, copyFileSync, existsSync, mkdirSync, openSync, readFileSync, realpathSync,
  renameSync, statSync, unlinkSync, writeFileSync,
} from "node:fs";
import { homedir } from "node:os";
import { dirname, isAbsolute, join, relative, resolve } from "node:path";
import { createHash, randomUUID } from "node:crypto";
import { spawnSync } from "node:child_process";

const DOMAIN_ID = /^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$/;
const sleepCell = new Int32Array(new SharedArrayBuffer(4));

function sleepSync(ms) {
  Atomics.wait(sleepCell, 0, 0, ms);
}

export function resolveRuntimeDomain(defaultId, ownerKind, { create = true } = {}) {
  const userDataDir = resolve((process.env.HARNEX_USER_DATA_DIR || "").trim() || join(homedir(), ".harnex"));
  const id = (process.env.HARNEX_RUNTIME_DOMAIN_ID || "").trim() || defaultId;
  if (!DOMAIN_ID.test(id)) throw new Error(`invalid runtime domain id: ${id}`);
  const configuredDir = (process.env.HARNEX_RUNTIME_DOMAIN_DIR || "").trim();
  const dir = resolve(configuredDir || join(userDataDir, "runtime-domains", id));
  let sameAsUserRoot = dir === userDataDir;
  if (!sameAsUserRoot && existsSync(dir) && existsSync(userDataDir)) {
    try { sameAsUserRoot = realpathSync.native(dir) === realpathSync.native(userDataDir); } catch {}
  }
  if (sameAsUserRoot) throw new Error("runtime domain directory must not equal user data directory");
  const paths = {
    id, ownerKind, userDataDir, dir,
    stateFile: join(dir, "state.json"),
    manifestFile: join(dir, "domain.json"),
    bootTokenFile: join(dir, "boot-token"),
    ownerFile: join(dir, "owner.json"),
    logDir: join(dir, "logs"),
    catalogFile: join(userDataDir, "catalog", "workspaces.json"),
  };
  if (create) ensurePrivateDirectory(dir);
  return paths;
}

/** Shared by hosts: one persistent personal data owner, independently of each project's domain. */
export function personalRuntimeDomain(userDataDir, ownerKind) {
  const userRoot = resolve(userDataDir);
  let ancestor = userRoot;
  while (!existsSync(ancestor) && dirname(ancestor) !== ancestor) ancestor = dirname(ancestor);
  const canonical = resolve(realpathSync.native(ancestor), relative(ancestor, userRoot));
  const scopeId = createHash('sha256').update(`personal:${canonical}`).digest('hex').slice(0, 12);
  const dir = join(canonical, 'runtime-domains', 'personal');
  return Object.freeze({ id: 'personal', ownerKind, userDataDir: canonical, dir, scopeId,
    dataDir: join(canonical, 'personal'), stateFile: join(dir, 'state.json'),
    manifestFile: join(dir, 'domain.json'), ownerFile: join(dir, 'owner.json'),
    bootTokenFile: join(dir, 'boot-token'), logDir: join(dir, 'logs') });
}

export function ensurePrivateDirectory(dir) {
  const created = !existsSync(dir);
  mkdirSync(dir, { recursive: true, mode: 0o700 });
  try { chmodSync(dir, 0o700); } catch {}
  if (created && process.platform === "win32" && process.env.USERNAME) {
    try {
      spawnSync("icacls.exe", [dir, "/inheritance:r", "/grant:r", `${process.env.USERNAME}:(OI)(CI)F`],
        { stdio: "ignore", windowsHide: true, timeout: 5000 });
    } catch {}
  }
}

export function readJsonSync(file, fallback) {
  if (!existsSync(file)) return structuredClone(fallback);
  try { return JSON.parse(readFileSync(file, "utf8")); }
  catch (error) { throw new Error(`invalid JSON state ${file}: ${error.message}`); }
}

export function atomicWriteJsonSync(file, value, { backup = true } = {}) {
  mkdirSync(dirname(file), { recursive: true });
  const nonce = `${process.pid}.${randomUUID()}`;
  const tmp = `${file}.${nonce}.tmp`;
  const backupTmp = `${file}.bak.${nonce}.tmp`;
  try {
    if (backup && existsSync(file)) {
      copyFileSync(file, backupTmp);
      replaceSync(backupTmp, `${file}.bak`);
    }
    writeFileSync(tmp, `${JSON.stringify(value, null, 2)}\n`, { encoding: "utf8", flag: "wx", mode: 0o600 });
    replaceSync(tmp, file);
    try { chmodSync(file, 0o600); } catch {}
  } finally {
    for (const candidate of [tmp, backupTmp]) {
      try { if (existsSync(candidate)) unlinkSync(candidate); } catch {}
    }
  }
}

function replaceSync(source, target) {
  // Node maps this to an atomic same-volume rename (MoveFileExW with replacement on Windows).
  // Never unlink the destination first: if replacement is unavailable, retaining the old
  // complete file is safer than exposing a missing/partial state file.
  renameSync(source, target);
}

export function withStateLockSync(target, operation, { timeoutMs = 5000 } = {}) {
  const lockFile = `${target}.lock`;
  mkdirSync(dirname(lockFile), { recursive: true });
  const deadline = Date.now() + timeoutMs;
  while (true) {
    let fd;
    try {
      fd = openSync(lockFile, "wx", 0o600);
    } catch (error) {
      if (error.code !== "EEXIST") throw error;
      let lease;
      try { lease = readJsonSync(lockFile, {}); }
      catch {
        const ageMs = lockAgeMs(lockFile);
        if (ageMs >= 2000) {
          try { unlinkSync(lockFile); } catch {}
          continue;
        }
        if (Date.now() >= deadline) throw new Error(`timed out reading shared state lock ${lockFile}`);
        sleepSync(10);
        continue;
      }
      const ageMs = Number.isFinite(Date.parse(lease.acquiredAt || ""))
        ? Date.now() - Date.parse(lease.acquiredAt)
        : lockAgeMs(lockFile);
      if ((!lease.pid || !isPidAlive(lease.pid)) && ageMs >= 2000) {
        try { unlinkSync(lockFile); } catch {}
        continue;
      }
      if (Date.now() >= deadline) {
        throw new Error(`timed out waiting for shared state lock ${lockFile} (owner pid=${lease.pid || "unknown"})`);
      }
      sleepSync(25);
      continue;
    }
    try {
      writeFileSync(fd, JSON.stringify({ pid: process.pid, acquiredAt: new Date().toISOString() }), "utf8");
      return operation();
    } finally {
      try { closeSync(fd); } catch {}
      try { unlinkSync(lockFile); } catch {}
    }
  }
}

function lockAgeMs(lockFile) {
  try { return Date.now() - statSync(lockFile).mtimeMs; }
  catch { return 0; }
}

export function updateJsonSync(file, fallback, update) {
  return withStateLockSync(file, () => {
    const next = update(readJsonSync(file, fallback));
    atomicWriteJsonSync(file, next);
    return next;
  });
}

export function isPidAlive(pid) {
  if (!Number.isInteger(Number(pid)) || Number(pid) <= 0) return false;
  try { process.kill(Number(pid), 0); return true; } catch { return false; }
}

let ownProcessStartedAt;
export function processStartedAtSync(pid) {
  if (pid === process.pid && ownProcessStartedAt) return ownProcessStartedAt;
  const options = { encoding: "utf8", windowsHide: true, timeout: 5000 };
  const result = process.platform === "win32"
    ? spawnSync("powershell.exe", ["-NoProfile", "-NonInteractive", "-Command",
      `(Get-Process -Id ${pid} -ErrorAction Stop).StartTime.ToUniversalTime().ToString('o')`], options)
    : spawnSync("ps", ["-p", String(pid), "-o", "lstart="],
      { ...options, env: { ...process.env, LC_ALL: "C" } });
  const started = result.status === 0 ? Date.parse(result.stdout.trim()) : NaN;
  if (!Number.isFinite(started)) return null;
  const value = new Date(started).toISOString();
  if (pid === process.pid) ownProcessStartedAt = value;
  return value;
}

/** A live PID can belong to a different process after the original owner exits. */
export function isDomainOwnerAlive(owner, { requireIdentity = false } = {}) {
  const pid = Number(owner?.ownerPid);
  if (!isPidAlive(pid)) return false;
  const actualStart = processStartedAtSync(pid);
  if (!actualStart) {
    if (!isPidAlive(pid)) return false;
    if (requireIdentity) throw new Error(`cannot verify runtime domain owner process ${pid}; refusing to stop it`);
    return true; // An unavailable process query must never allow stealing a live lease.
  }
  if (owner.ownerProcessStartedAt) return actualStart === owner.ownerProcessStartedAt;
  // Older leases only recorded when ownership was acquired. A newer process cannot own them.
  const leaseStart = Date.parse(owner.startedAt || "");
  if (Number.isFinite(leaseStart)) return Date.parse(actualStart) <= leaseStart;
  if (requireIdentity) throw new Error(`cannot verify runtime domain owner lease for pid ${pid}`);
  return true;
}

export function claimDomainOwnerSync(paths, ownerInstanceId) {
  const payload = {
    schemaVersion: 1, runtimeDomainId: paths.id, ownerKind: paths.ownerKind,
    ownerPid: process.pid, ownerInstanceId, startedAt: new Date().toISOString(),
    ownerProcessStartedAt: processStartedAtSync(process.pid),
  };
  try {
    writeFileSync(paths.ownerFile, `${JSON.stringify(payload, null, 2)}\n`, { flag: "wx", mode: 0o600 });
    return payload;
  } catch (error) {
    if (error.code !== "EEXIST") throw error;
    let owner;
    try { owner = readJsonSync(paths.ownerFile, {}); }
    catch {
      const ageMs = Date.now() - statSync(paths.ownerFile).mtimeMs;
      if (ageMs >= 2000) {
        unlinkSync(paths.ownerFile);
        cleanupStaleDomainSync(paths);
        return claimDomainOwnerSync(paths, ownerInstanceId);
      }
      throw new Error(`runtime domain ${paths.id} owner lease is still being initialised; retry`);
    }
    try { validateDomainOwner(owner, paths); }
    catch (validationError) {
      const ageMs = lockAgeMs(paths.ownerFile);
      if (ageMs >= 2000) {
        unlinkSync(paths.ownerFile);
        cleanupStaleDomainSync(paths);
        return claimDomainOwnerSync(paths, ownerInstanceId);
      }
      throw new Error(`runtime domain ${paths.id} owner lease is still being initialised; retry (${validationError.message})`);
    }
    const ageMs = Number.isFinite(Date.parse(owner.startedAt || ""))
      ? Date.now() - Date.parse(owner.startedAt)
      : lockAgeMs(paths.ownerFile);
    if (!isDomainOwnerAlive(owner) && ageMs >= 2000) {
      cleanupStaleDomainSync(paths);
      unlinkSync(paths.ownerFile);
      writeFileSync(paths.ownerFile, `${JSON.stringify(payload, null, 2)}\n`, { flag: "wx", mode: 0o600 });
      return payload;
    }
    throw new Error(`runtime domain ${paths.id} is owned by pid ${owner.ownerPid || "unknown"}; stop it or retry`);
  }
}

export function validateDomainOwner(owner, paths) {
  if (owner?.schemaVersion !== 1 || owner.runtimeDomainId !== paths.id
      || owner.ownerKind !== paths.ownerKind || !Number.isInteger(owner.ownerPid) || owner.ownerPid < 1
      || typeof owner.ownerInstanceId !== "string" || !owner.ownerInstanceId) {
    throw new Error("runtime domain owner lease identity is invalid");
  }
  const started = Date.parse(owner.startedAt || "");
  if (!Number.isFinite(started) || started > Date.now() + 5 * 60 * 1000) {
    throw new Error("runtime domain owner lease timestamp is invalid");
  }
  return owner;
}

export function releaseDomainOwnerSync(paths, ownerInstanceId) {
  try {
    const owner = readJsonSync(paths.ownerFile, {});
    if (owner.ownerPid === process.pid && owner.ownerInstanceId === ownerInstanceId) unlinkSync(paths.ownerFile);
  } catch {}
}

export function publishDomainManifestSync(paths, manifest) {
  validateDomainManifest(manifest, paths);
  atomicWriteJsonSync(paths.manifestFile, manifest, { backup: false });
}

export function unpublishDomainManifestSync(paths) {
  try { unlinkSync(paths.manifestFile); } catch {}
}

export function cleanupStaleDomainSync(paths) {
  let manifest = {};
  try { manifest = readJsonSync(paths.manifestFile, {}); } catch {}
  let owner;
  try { owner = readJsonSync(paths.ownerFile, null); } catch {}
  const matchingOwner = owner && owner.ownerPid === manifest.ownerPid
    && owner.ownerInstanceId === manifest.ownerInstanceId;
  if (matchingOwner ? isDomainOwnerAlive(owner) : isPidAlive(manifest.ownerPid)) return false;
  for (const file of [paths.manifestFile, paths.bootTokenFile]) {
    try { unlinkSync(file); } catch {}
  }
  return true;
}

export function validateDomainManifest(manifest, paths) {
  const requiredStrings = ["runtimeDomainId", "ownerKind", "ownerInstanceId", "scopeId", "runtimeProfile",
    "instanceId", "endpoint", "lifecycle", "protocolVersion", "bootTokenFile", "configRevision", "publishedAt"];
  if (manifest?.schemaVersion !== 3) throw new Error("unsupported runtime domain manifest schema");
  if (!['PERSONAL', 'PROJECT'].includes(manifest.runtimeProfile)) throw new Error('invalid Runtime profile');
  if (manifest.runtimeProfile === 'PERSONAL' && (manifest.canonicalRoot != null || manifest.remote)) {
    throw new Error('personal Runtime must not declare a project root or remote workspace');
  }
  if (manifest.runtimeProfile === 'PROJECT' && (typeof manifest.canonicalRoot !== 'string' || !manifest.canonicalRoot)) {
    throw new Error('project Runtime requires canonicalRoot');
  }
  if (manifest.remote && (manifest.remote?.root !== manifest.canonicalRoot
      || !manifest.canonicalRoot?.startsWith('/') || typeof manifest.remote?.host !== 'string' || !manifest.remote.host
      || !Number.isInteger(manifest.remote?.runtimePid) || manifest.remote.runtimePid < 1
      || !manifest.remote?.connectionId || manifest.authType !== 'bearer-json')) {
    throw new Error('remote manifest location or authentication contract is invalid');
  }
  for (const key of requiredStrings) if (typeof manifest[key] !== "string" || !manifest[key]) {
    throw new Error(`runtime domain manifest requires ${key}`);
  }
  if (manifest.runtimeDomainId !== paths.id || manifest.lifecycle !== "READY") {
    throw new Error("runtime domain manifest identity/lifecycle mismatch");
  }
  if (!DOMAIN_ID.test(manifest.runtimeDomainId)
      || !["web", "tauri", "vscode", "cli", "standalone"].includes(manifest.ownerKind)) {
    throw new Error("runtime domain manifest id/owner kind is invalid");
  }
  for (const key of ["ownerPid", "runtimePid"]) if (!Number.isInteger(manifest[key]) || manifest[key] < 1) {
    throw new Error(`runtime domain manifest requires positive ${key}`);
  }
  const endpoint = new URL(manifest.endpoint);
  if (endpoint.protocol !== "http:" || !["127.0.0.1", "[::1]"].includes(endpoint.hostname)) {
    throw new Error("runtime domain endpoint must be loopback HTTP");
  }
  if (endpoint.origin !== manifest.endpoint || !endpoint.port) {
    throw new Error("runtime domain endpoint must be a loopback HTTP origin with an explicit port");
  }
  if ((!manifest.remote && manifest.runtimeProfile === 'PROJECT' && !isAbsolute(manifest.canonicalRoot)) || !isAbsolute(manifest.bootTokenFile)) {
    throw new Error("runtime domain root and boot token paths must be absolute");
  }
  if (!/^\d+\.\d+$/.test(manifest.protocolVersion)) {
    throw new Error("runtime domain protocol version is invalid");
  }
  const published = Date.parse(manifest.publishedAt);
  if (!Number.isFinite(published) || published > Date.now() + 5 * 60 * 1000) {
    throw new Error("runtime domain publication timestamp is invalid");
  }
  const token = resolve(manifest.bootTokenFile);
  const rel = relative(paths.dir, token);
  if (rel.startsWith("..") || isAbsolute(rel)) {
    throw new Error("runtime domain boot token must be inside its domain directory");
  }
  return manifest;
}

export function readTrustedManifestSync(file) {
  const absolute = resolve(file);
  const dir = dirname(absolute);
  const manifest = readJsonSync(absolute, {});
  const paths = { id: manifest.runtimeDomainId, ownerKind: manifest.ownerKind, dir };
  validateDomainManifest(manifest, paths);
  const owner = validateDomainOwner(readJsonSync(join(dir, "owner.json"), {}), paths);
  if (owner.ownerPid !== manifest.ownerPid
      || owner.ownerInstanceId !== manifest.ownerInstanceId) {
    throw new Error("runtime domain manifest does not match its active owner lease");
  }
  if (!isDomainOwnerAlive(owner) || !isPidAlive(manifest.runtimePid)) {
    throw new Error("runtime domain manifest refers to a stopped process");
  }
  let domainReal;
  let tokenReal;
  try {
    domainReal = realpathSync.native(dir);
    tokenReal = realpathSync.native(manifest.bootTokenFile);
  } catch (error) {
    throw new Error(`runtime domain manifest path validation failed: ${error.message}`);
  }
  const tokenRelative = relative(domainReal, tokenReal);
  if (tokenRelative.startsWith("..") || isAbsolute(tokenRelative)) {
    throw new Error("runtime domain boot token resolves outside its domain directory");
  }
  return manifest;
}

export async function attachTrustedRuntime(manifestFile, { cwd = process.cwd() } = {}) {
  const manifest = readTrustedManifestSync(manifestFile);
  const identityHeaders = {
    "X-Harnex-Scope-Id": manifest.scopeId,
    "X-Harnex-Instance-Id": manifest.instanceId,
  };
  let remoteToken;
  if (manifest.remote) {
    remoteToken = JSON.parse(readFileSync(manifest.bootTokenFile, 'utf8')).token;
    if (typeof remoteToken !== 'string' || !remoteToken) throw new Error('remote session credential missing');
  }
  const probeHeaders = remoteToken ? {...identityHeaders, Authorization:`Bearer ${remoteToken}`} : identityHeaders;
  let workspaceResponse;
  try {
    workspaceResponse = await fetch(`${manifest.endpoint}/api/v1/scope`, {
      headers: probeHeaders, signal: AbortSignal.timeout(2500),
    });
  } catch (error) {
    throw new Error(`trusted Runtime workspace probe failed: ${error.message}`);
  }
  if (!workspaceResponse.ok) throw new Error(`trusted Runtime workspace probe returned HTTP ${workspaceResponse.status}`);
  assertResponseIdentity(workspaceResponse, manifest);
  const workspace = await workspaceResponse.json();
  if (workspace.scopeId !== manifest.scopeId || workspace.instanceId !== manifest.instanceId
      || workspace.runtimeProfile !== manifest.runtimeProfile || workspace.runtimeDomainId !== manifest.runtimeDomainId) {
    throw new Error('trusted Runtime scope/profile/instance identity mismatch');
  }
  if (manifest.runtimeProfile === 'PERSONAL' && workspace.projectRoot != null) {
    throw new Error('trusted personal Runtime declares a project root');
  }
  if (manifest.runtimeProfile === 'PROJECT') {
  let expectedRoot;
  let actualRoot;
  try {
    expectedRoot = manifest.remote ? manifest.canonicalRoot : realpathSync.native(manifest.canonicalRoot);
    actualRoot = manifest.remote ? workspace.projectRoot : realpathSync.native(workspace.projectRoot);
  } catch (error) {
    throw new Error(`trusted Runtime canonical root check failed: ${error.message}`);
  }
  if (!(manifest.remote ? expectedRoot === actualRoot : samePath(expectedRoot, actualRoot)) || workspace.scopeId !== manifest.scopeId
      || workspace.instanceId !== manifest.instanceId) {
    throw new Error("trusted Runtime workspace/root/instance identity mismatch");
  }
  let clientRoot;
  try { clientRoot = manifest.remote ? expectedRoot : realpathSync.native(cwd); }
  catch (error) { throw new Error(`trusted Runtime client cwd check failed: ${error.message}`); }
  const clientRelative = relative(expectedRoot, clientRoot);
  if (clientRelative.startsWith("..") || isAbsolute(clientRelative)) {
    throw new Error("trusted Runtime client cwd is outside the manifest workspace");
  }
  }
  const protocolResponse = await fetch(`${manifest.endpoint}/api/v1/protocol`, {
    headers: probeHeaders, signal: AbortSignal.timeout(2500),
  });
  if (!protocolResponse.ok) throw new Error(`trusted Runtime protocol probe returned HTTP ${protocolResponse.status}`);
  assertResponseIdentity(protocolResponse, manifest);
  const protocol = await protocolResponse.json();
  if (protocol.version !== manifest.protocolVersion) {
    throw new Error(`trusted Runtime protocol mismatch: manifest=${manifest.protocolVersion} runtime=${protocol.version}`);
  }
  if (remoteToken) return Object.freeze({ attached:true, owner:false, manifest, endpoint:manifest.endpoint,
    token:remoteToken, identityHeaders:Object.freeze(identityHeaders) });
  let candidate;
  try { candidate = readFileSync(manifest.bootTokenFile, "utf8").trim(); }
  catch (error) { throw new Error(`trusted Runtime boot token is unreadable: ${error.message}`); }
  if (!candidate) throw new Error("trusted Runtime boot token is empty");
  const authResponse = await fetch(`${manifest.endpoint}/api/v1/auth/boot-token`, {
    method: "POST",
    headers: { ...identityHeaders, "content-type": "application/json" },
    body: JSON.stringify({ token: candidate }),
    signal: AbortSignal.timeout(2500),
  });
  if (!authResponse.ok) throw new Error(`trusted Runtime boot-token handshake returned HTTP ${authResponse.status}`);
  assertResponseIdentity(authResponse, manifest);
  const session = await authResponse.json();
  if (!session.token) throw new Error("trusted Runtime boot-token handshake returned no session token");
  return Object.freeze({
    attached: true,
    owner: false,
    manifest,
    endpoint: manifest.endpoint,
    token: session.token,
    identityHeaders: Object.freeze(identityHeaders),
  });
}

function assertResponseIdentity(response, manifest) {
  if (response.headers.get("x-harnex-scope-id") !== manifest.scopeId
      || response.headers.get("x-harnex-instance-id") !== manifest.instanceId) {
    throw new Error("trusted Runtime response identity mismatch");
  }
}

function samePath(left, right) {
  const a = resolve(left);
  const b = resolve(right);
  return process.platform === "win32" ? a.toLowerCase() === b.toLowerCase() : a === b;
}
