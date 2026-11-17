import { createServer } from 'node:net';
import { realpathSync } from 'node:fs';

/** Check bindability, rather than treating a refused/timed-out connection as a free port. */
export function runtimePortAvailable(port) {
  return new Promise((resolve, reject) => {
    const server = createServer(socket => socket.destroy());
    server.once('error', error => {
      if (error.code === 'EADDRINUSE' || error.code === 'EACCES') resolve(false);
      else reject(error);
    });
    server.listen({ host: '127.0.0.1', port, exclusive: true }, () => {
      server.close(error => error ? reject(error) : resolve(true));
    });
  });
}

/** A host keeps each reservation until the owning child exits, including its startup interval. */
export function createRuntimePortPool({ min = 8100, max = 8199, available = runtimePortAvailable } = {}) {
  const reserved = new Set();
  return {
    async reserve() {
      for (let port = min; port <= max; port++) {
        if (reserved.has(port)) continue;
        // Claim before the asynchronous check so concurrent launches cannot choose the same candidate.
        reserved.add(port);
        let held = true;
        const release = () => { if (held) { held = false; reserved.delete(port); } };
        try {
          if (await available(port)) return { port, release };
        } catch (error) { release(); throw error; }
        release();
      }
      throw new Error('no runtime port is available');
    },
  };
}

function sameRoot(actual, expected) {
  try {
    const left = realpathSync(actual), right = realpathSync(expected);
    return process.platform === 'win32' ? left.toLowerCase() === right.toLowerCase() : left === right;
  } catch { return false; }
}

/** Health alone cannot identify a child: another Runtime may already answer at the selected port. */
export async function probeRuntimeReady(port, expected) {
  const endpoint = `http://127.0.0.1:${port}`;
  const headers = { 'X-Harnex-Scope-Id': expected.scopeId, 'X-Harnex-Instance-Id': expected.instanceId };
  const identityMatches = response => response.headers.get('X-Harnex-Scope-Id') === expected.scopeId
    && response.headers.get('X-Harnex-Instance-Id') === expected.instanceId;
  try {
    const response = await fetch(`${endpoint}/api/v1/scope`, { headers, signal: AbortSignal.timeout(1500) });
    if (!response.ok || !identityMatches(response)) { await response.body?.cancel(); return false; }
    const scope = await response.json();
    if (scope.scopeId !== expected.scopeId || scope.instanceId !== expected.instanceId
      || scope.runtimeProfile !== expected.runtimeProfile || scope.runtimeDomainId !== expected.runtimeDomainId
      || (expected.runtimeProfile === 'PERSONAL' ? scope.projectRoot != null : !sameRoot(scope.projectRoot, expected.projectRoot))) return false;
    const health = await fetch(`${endpoint}/actuator/health/readiness`, { headers, signal: AbortSignal.timeout(1500) });
    if (health.status !== 200 || !identityMatches(health)) { await health.body?.cancel(); return false; }
    return (await health.json()).status === 'UP';
  } catch { return false; }
}
