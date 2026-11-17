// Optional, pinned OS isolation adapter. Command environment is applied inside the boundary, not to this helper.
import { readFileSync } from 'node:fs';
import { pathToFileURL } from 'node:url';
import { spawn } from 'node:child_process';
import { randomUUID } from 'node:crypto';

let manager;
try {
  const [major, minor] = process.versions.node.split('.').map(Number);
  if (major < 20 || major === 20 && minor < 11) throw new Error('Node 20.11+ is required for native isolation');
  const { SandboxManager } = await import(pathToFileURL(process.argv[2]).href);
  manager = SandboxManager;
  const invocation = JSON.parse(readFileSync(process.argv[3], 'utf8'));
  if (!manager.isSupportedPlatform()) throw new Error('sandbox platform is unavailable');
  await manager.initialize(invocation.policy, undefined, false);
  const dependencies = manager.checkDependencies();
  if (dependencies.errors.length || dependencies.warnings.length || !manager.isSandboxingEnabled()) {
    throw new Error(`sandbox dependencies are incomplete: ${[...dependencies.errors, ...dependencies.warnings].join('; ')}`);
  }
  const quote = value => `'${String(value).replaceAll("'", "'\\''")}'`;
  const environment = Object.entries(invocation.env || {}).map(([key, value]) => `${key}=${value}`);
  const command = ['/usr/bin/env', ...environment, ...invocation.argv].map(quote).join(' ');
  const wrapped = await manager.wrapWithSandbox(command, '/bin/sh', undefined, undefined,
    {commandId:randomUUID(),commandText:invocation.argv.join(' ')});
  if (!wrapped || wrapped === command) throw new Error('sandbox runner returned an unconfined command');
  const child = spawn('/bin/sh', ['-c', wrapped], {stdio:'inherit'});
  process.on('SIGTERM', () => child.kill('SIGTERM'));
  process.on('SIGINT', () => child.kill('SIGINT'));
  const outcome = await new Promise((done, fail) => {
    child.once('error', fail);
    child.once('close', (code, signal) => done({code,signal}));
  });
  process.exitCode = outcome.signal ? 1 : outcome.code ?? 1;
} catch (failure) {
  console.error(`SANDBOX_UNAVAILABLE: ${failure.message}`);
  process.exitCode = 1;
} finally {
  if (manager) {
    try { manager.cleanupAfterCommand(); await manager.reset(); }
    catch { console.error('Sandbox cleanup was incomplete; the command outcome is preserved.'); }
  }
}
process.exit(process.exitCode ?? 1);
