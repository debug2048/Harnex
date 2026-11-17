import { spawn, spawnSync } from 'node:child_process';
import { readFileSync, statSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createRequire } from 'node:module';
import { createInterface } from 'node:readline';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
const require = createRequire(import.meta.url);
const services = new Map();
const capabilities = new Map();

function componentError(code, message) {
  return Object.assign(new Error(message), {code, status:503});
}

export function selectConnectionCli(candidates, probe = spawnSync) {
  let found = false;
  for (const path of [...new Set(candidates.filter(Boolean))]) {
    let stat; try { stat = statSync(path); } catch { continue; }
    if (!stat.isFile()) continue;
    found = true;
    const stamp = `${stat.size}:${stat.mtimeMs}:${stat.ctimeMs}`;
    let cached = capabilities.get(path);
    if (!cached || cached.stamp !== stamp) {
      const result = probe(path, ['remote', 'capabilities'], {encoding:'utf8', windowsHide:true, timeout:5000, maxBuffer:32_768});
      let reply; try { reply = JSON.parse(result.stdout); } catch {}
      cached = {stamp, supported:result.status === 0 && reply?.protocolVersion === 1
        && ['ssh.interaction','ssh.workspace-owners','ssh.runtime-reuse','ssh.services','ssh.upgrade','ssh.cache-gc'].every(capability => reply?.capabilities?.includes(capability))};
      capabilities.set(path, cached);
    }
    if (cached.supported) return path;
  }
  throw found
    ? componentError('ssh_component_outdated', 'SSH connection component needs an update. Rebuild or update Harnex and retry.')
    : componentError('ssh_component_missing', 'SSH connection component is unavailable. Rebuild or reinstall Harnex and retry.');
}

function connectionService({ jar, userDataDir } = {}) {
  const key = userDataDir || '';
  if (services.has(key)) return services.get(key);
  const pending = new Map();
  let sequence = 0;
  const ready = new Promise((resolveService, reject) => {
    const binary = nativeConnectionCli();
    const child = spawn(binary, ['remote', 'service'], { windowsHide:true, stdio:['pipe','pipe','pipe'],
      env:{...process.env, HARNEX_DOMAIN_MANIFEST:undefined, ...(jar ? {HARNEX_JAR:jar} : {}), ...(userDataDir ? {HARNEX_USER_DATA_DIR:userDataDir} : {})} });
    let errors = '';
    const lines = createInterface({input:child.stdout});
    const fail = error => { if (services.get(key) === ready) services.delete(key); reject(error); for (const item of pending.values()) { clearTimeout(item.timer); item.reject(error); } pending.clear(); };
    const timer = setTimeout(() => { child.kill(); fail(new Error('SSH connection service did not start')); }, 10_000);
    child.stdin.on('error', fail);
    child.stderr.on('data', bytes => { errors = (errors + bytes).slice(-2000); });
    child.once('error', error => { clearTimeout(timer); fail(error); });
    child.once('exit', () => { clearTimeout(timer); lines.close(); fail(new Error(errors.trim() || 'SSH connection service stopped')); });
    // This private stream contains the askpass broker capability. It is never logged or exposed through HTTP.
    lines.on('line', line => {
      let reply; try { reply = JSON.parse(line); } catch { child.kill(); fail(new Error('Invalid SSH service response')); return; }
      if (reply.ready) {
        clearTimeout(timer);
        if (reply.protocolVersion !== 1) { child.kill(); fail(componentError('ssh_component_outdated', 'SSH connection component needs an update. Rebuild or update Harnex and retry.')); return; }
        resolveService({binary, environment:reply.environment, request(action, args, runtimeJar) {
          const id = String(++sequence);
          return new Promise((resolveReply, rejectReply) => {
            const timer = setTimeout(() => { pending.delete(id); rejectReply(new Error('SSH service request timed out')); }, 15_000);
            pending.set(id, {resolve:resolveReply, reject:rejectReply, timer});
            child.stdin.write(`${JSON.stringify({id,action,args,jar:runtimeJar})}\n`);
          });
        }});
      } else {
        const item = pending.get(reply.id); if (!item) return;
        pending.delete(reply.id); clearTimeout(item.timer);
        if (reply.error) item.reject(new Error(reply.error)); else item.resolve(reply.result);
      }
    });
    // The connection service follows the owner lifetime and must not keep a stopped owner alive.
    child.unref(); child.stdin.unref?.(); child.stdout.unref?.(); child.stderr.unref?.();
  });
  services.set(key, ready);
  ready.catch(() => { if (services.get(key) === ready) services.delete(key); });
  return ready;
}

export async function remoteInteraction(action, args, options = {}) {
  return (await connectionService(options)).request(action, args, options.jar);
}

export function nativeConnectionCli() {
  if (process.env.HARNEX_NATIVE_CLI) return selectConnectionCli([process.env.HARNEX_NATIVE_CLI]);
  const binary = process.platform === 'win32' ? 'harnex.exe' : 'harnex';
  const target = `${process.platform}-${process.arch}`;
  const version = JSON.parse(readFileSync(join(root, 'package.json'), 'utf8')).version;
  let installed;
  try { installed = join(dirname(require.resolve(`@harnex/cli-${target}/package.json`)), 'bin', binary); } catch {}
  const candidates = [process.env.HARNEX_NATIVE_CLI, installed,
    process.env.HARNEX_RUNTIME_BUNDLE && join(process.env.HARNEX_RUNTIME_BUNDLE, 'bin', binary),
    join(root, 'dist', 'native', target, binary),
    join(root, 'dist', 'runtime-bundles', `${version}-${target}`, 'bin', binary),
    join(root, 'src-cli', 'target', 'release', binary), join(root, 'src-cli', 'target', 'debug', binary)];
  return selectConnectionCli(candidates);
}

export async function remoteCommand(args, { jar, userDataDir, timeoutMs = 360_000, input } = {}) {
  const service = await connectionService({jar, userDataDir});
  const lifecycle = ['describe','close','close-bound','upgrade-bound'].includes(args[0]);
  const attempt = lifecycle ? await service.request('begin', ['close-bound','upgrade-bound'].includes(args[0])
    ? {profile:JSON.parse(input).profile} : {profileId:args[1]}) : null;
  try { return await new Promise((resolveResult, reject) => {
    const child = spawn(service.binary, ['remote', ...args], {
      windowsHide: true, stdio: [input === undefined ? 'ignore' : 'pipe', 'pipe', 'pipe'],
      env: { ...process.env, ...service.environment, ...(attempt ? {HARNEX_SSH_ATTEMPT:attempt.id} : {}), HARNEX_DOMAIN_MANIFEST:undefined, ...(jar ? { HARNEX_JAR: jar } : {}), ...(userDataDir ? { HARNEX_USER_DATA_DIR: userDataDir } : {}) },
    });
    let output = '', errors = '';
    child.stdin?.on('error', () => {});
    if (input !== undefined) child.stdin.end(input);
    const timer = setTimeout(() => { child.kill(); reject(new Error('SSH control operation timed out; remote state must be checked before switching')); }, timeoutMs);
    child.stdout.on('data', bytes => { output += bytes; if (output.length > 2_000_000) { child.kill(); reject(new Error('SSH response exceeded its limit')); } });
    child.stderr.on('data', bytes => { errors = (errors + bytes).slice(-4000); });
    child.once('error', error => { clearTimeout(timer); reject(error); });
    child.once('close', code => {
      clearTimeout(timer);
      if (code !== 0) { reject(new Error(errors.trim() || `SSH operation exited ${code}`)); return; }
      try { resolveResult(JSON.parse(output.trim())); } catch { reject(new Error('Invalid SSH control response')); }
    });
  }); } finally { if (attempt) await service.request('finish',{id:attempt.id}).catch(()=>{}); }
}

export async function openRemoteBridge(workspace, { jar, userDataDir, owner, port }) {
  const service = await connectionService({jar, userDataDir});
  const attempt = await service.request('begin',{profileId:workspace.remoteProfile});
  const scopeId = workspace.runtimeScopeId || workspace.id;
  const child = spawn(service.binary, ['remote', 'bridge', workspace.remoteProfile, '--workspace', workspace.root,
    '--workspace-id', scopeId, '--owner', owner, '--port', String(port),
    ...(workspace.remoteService ? ['--service-json', JSON.stringify(workspace.remoteService)] : [])], {
    windowsHide: true, stdio: ['pipe', 'pipe', 'pipe'], env: { ...process.env, ...service.environment, HARNEX_SSH_ATTEMPT:attempt.id, HARNEX_DOMAIN_MANIFEST:undefined, HARNEX_JAR: jar, HARNEX_USER_DATA_DIR: userDataDir },
  });
  let errors = '';
  child.stdin.on('error', () => {});
  child.stderr.on('data', bytes => { errors = (errors + bytes).slice(-4000); });
  const lines = createInterface({ input: child.stdout });
  const ready = await new Promise((resolveReady, reject) => {
    const timer = setTimeout(() => { void service.request('cancel',{id:attempt.id}).catch(()=>{}); child.stdin.end(); reject(new Error('Remote connection timed out; reconnect to confirm the remote state')); }, 330_000);
    child.once('error', error => { clearTimeout(timer); reject(error); });
    child.once('exit', code => { clearTimeout(timer); reject(new Error(errors.trim() || `Remote bridge exited ${code}`)); });
    lines.once('line', line => {
      clearTimeout(timer);
      try {
        const value = JSON.parse(line);
        if (!value.ready || value.remote?.workspaceId !== scopeId || value.endpoint !== `http://127.0.0.1:${port}`
            || typeof value.remote?.token !== 'string' || !value.remote?.instanceId
            || value.control?.profile?.id !== workspace.remoteProfile
            || (workspace.remoteService ? value.remote?.ownership !== 'attached' : value.remote?.ownership === 'attached')
            || value.control?.descriptor?.instanceId !== value.remote.instanceId) throw new Error('Remote identity mismatch');
        resolveReady(value);
      } catch (error) { child.stdin.end(); reject(error); }
    });
  }).finally(()=>service.request('finish',{id:attempt.id}).catch(()=>{}));
  // Credentials remain in this host process. Never pipe the private bridge response into logs or browser JSON.
  const record = ready.remote;
  let closing = false;
  return {
    get closing() { return closing; },
    child, descriptor: record,
    async upgrade() {
      if (record.ownership === 'attached') throw new Error('UPGRADE_UNSUPPORTED: update this service on its server');
      await remoteCommand(['upgrade-bound'], {jar,userDataDir,input:JSON.stringify(ready.control)});
    },
    async close() {
      await remoteCommand(['close-bound'], { jar, userDataDir, input:JSON.stringify(ready.control) });
      closing = true;
      child.stdin.end();
      lines.close();
      await waitBridgeExit(child);
    },
    async disconnect() { closing = true; child.stdin.end(); lines.close(); await waitBridgeExit(child); },
  };
}

function waitBridgeExit(child) {
  if (child.exitCode !== null || child.signalCode !== null) return Promise.resolve();
  return new Promise((done, fail) => {
    const timer = setTimeout(() => fail(new Error('local SSH bridge did not release its connection lease')), 8000);
    child.once('exit', () => { clearTimeout(timer); done(); });
  });
}
