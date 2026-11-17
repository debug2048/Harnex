import { existsSync } from 'node:fs';
import { createBoundedLogStream } from './bounded-log.mjs';
import { randomUUID } from 'node:crypto';
import { finished } from 'node:stream/promises';
import {
  attachTrustedRuntime, claimDomainOwnerSync, ensurePrivateDirectory, isDomainOwnerAlive, isPidAlive,
  personalRuntimeDomain, publishDomainManifestSync, readJsonSync, releaseDomainOwnerSync,
  unpublishDomainManifestSync,
} from './runtime-domain.mjs';

const delay = ms => new Promise(resolve => setTimeout(resolve, ms));
// Boot-token sessions have a 2h idle limit and a 24h absolute lifetime. Renew on
// demand before either can expire, including the first request after host sleep.
const SESSION_REFRESH_MS = 60 * 60_000;
const sameRuntime = (left, right) => left && right && left.endpoint === right.endpoint
  && left.manifest.scopeId === right.manifest.scopeId && left.manifest.instanceId === right.manifest.instanceId;
const sessionChanged = () => Object.assign(new Error('Personal Runtime changed while renewing authentication'),
  { status: 409, code: 'runtime_instance_changed' });

/** One owned or attached personal process; selecting a project never changes this connection. */
export class PersonalRuntime {
  constructor({ userDataDir, ownerKind, launch, reservePort, stop, configRevision, timeoutMs = 120_000,
    onChange = () => {}, attach = attachTrustedRuntime, now = Date.now }) {
    this.paths = personalRuntimeDomain(userDataDir, ownerKind);
    Object.assign(this, { launch, reservePort, stop, configRevision, timeoutMs, onChange, attach, now });
    this.ownerInstanceId = randomUUID();
    this.lifecycle = 'CLOSED';
    this.session = null;
    this.sessionIssuedAt = 0;
    this.sessionRefresh = null;
    this.child = null;
    this.opening = null;
    this.failureMessage = null;
  }

  view() {
    return { scopeId: this.paths.scopeId, runtimeProfile: 'PERSONAL', name: 'Personal',
      instanceId: this.session?.manifest.instanceId || null, state: this.lifecycle,
      failureMessage: this.failureMessage, owned: Boolean(this.child),
      endpoint: this.session?.endpoint || null };
  }

  change(state, failureMessage = null) {
    this.lifecycle = state;
    this.failureMessage = failureMessage;
    this.onChange(this.view());
  }

  setSession(session) {
    this.session = session;
    this.sessionIssuedAt = this.now();
  }

  /** Renew only the captured Runtime; never launch or adopt a replacement JVM. */
  async requestSession(expected = this.session, { refresh = false } = {}) {
    const current = this.session;
    if (this.lifecycle !== 'READY' || !sameRuntime(expected, current)) throw sessionChanged();
    if (this.sessionRefresh?.session === current) return this.sessionRefresh.promise;
    // A concurrent request may already have replaced the rejected credential.
    if (current !== expected || !refresh && this.now() - this.sessionIssuedAt < SESSION_REFRESH_MS) return current;
    const promise = (async () => {
      const renewed = await this.attach(this.paths.manifestFile);
      if (this.lifecycle !== 'READY' || this.session !== current || !sameRuntime(current, renewed)) throw sessionChanged();
      this.setSession(renewed);
      return renewed;
    })();
    const operation = { session: current, promise };
    this.sessionRefresh = operation;
    try { return await promise; }
    finally { if (this.sessionRefresh === operation) this.sessionRefresh = null; }
  }

  async open() {
    if (this.opening) return this.opening;
    this.opening = this.openInner().catch(error => {
      this.session = null;
      this.change('FAILED', error.message);
      throw error;
    }).finally(() => { this.opening = null; });
    return this.opening;
  }

  async openInner() {
    const paths = this.paths;
    if (this.session) {
      const session = await this.attach(paths.manifestFile);
      this.setSession(session);
      this.change('READY');
      return this.view();
    }
    this.change('OPENING');
    ensurePrivateDirectory(paths.dir);
    const deadline = Date.now() + this.timeoutMs;
    // A different host may be booting the shared process. Wait for its verified manifest;
    // never claim ownership from a live host just because its HTTP endpoint is not ready.
    while (true) {
      const owner = readJsonSync(paths.ownerFile, null);
      if (owner && isDomainOwnerAlive(owner)) {
        if (existsSync(paths.manifestFile)) {
          this.setSession(await this.attach(paths.manifestFile));
          if (this.session.manifest.runtimeProfile !== 'PERSONAL'
              || this.session.manifest.scopeId !== paths.scopeId) throw new Error('personal Runtime identity mismatch');
          this.change('READY');
          return this.view();
        }
        if (Date.now() >= deadline) throw new Error('The personal Runtime is still owned by another host but is unavailable');
        await delay(200);
        continue;
      }
      try { claimDomainOwnerSync(paths, this.ownerInstanceId); break; }
      catch (error) {
        if (Date.now() >= deadline) throw error;
        await delay(200);
      }
    }
    const instanceId = randomUUID();
    let startupLog, portLease, startedChild;
    try {
      portLease = await this.reservePort();
      const { port } = portLease;
      ensurePrivateDirectory(paths.logDir);
      const log = createBoundedLogStream(`${paths.logDir}/runtime.log`);
      startupLog = log;
      const child = await this.launch({ paths, port, instanceId, log });
      startedChild = child;
      this.child = child;
      child.once('exit', () => {
        portLease.release();
        log.end();
        if (this.child !== child) return;
        this.child = null;
        this.session = null;
        unpublishDomainManifestSync(paths);
        releaseDomainOwnerSync(paths, this.ownerInstanceId);
        if (this.lifecycle !== 'CLOSING') this.change('FAILED', 'Personal Runtime exited');
      });
      let spawnError;
      child.once('error', error => { spawnError = error; });
      while (Date.now() < deadline) {
        if (spawnError) throw spawnError;
        if (child.exitCode !== null || child.signalCode !== null || this.child !== child) {
          throw new Error('Personal Runtime exited before readiness; see its runtime log');
        }
        try {
          const response = await fetch(`http://127.0.0.1:${port}/api/v1/scope`, { signal: AbortSignal.timeout(1500) });
          if (response.ok) {
            const scope = await response.json();
            if (scope.scopeId !== paths.scopeId || scope.instanceId !== instanceId || scope.runtimeProfile !== 'PERSONAL'
                || scope.runtimeDomainId !== paths.id || scope.projectRoot != null) {
              throw Object.assign(new Error('Personal Runtime readiness identity mismatch'), { fatal: true });
            }
            publishDomainManifestSync(paths, {
              schemaVersion: 3, runtimeProfile: 'PERSONAL', scopeId: paths.scopeId, canonicalRoot: null,
              runtimeDomainId: paths.id, ownerKind: paths.ownerKind, ownerPid: process.pid,
              ownerInstanceId: this.ownerInstanceId, runtimePid: child.pid, instanceId,
              endpoint: `http://127.0.0.1:${port}`, lifecycle: 'READY', protocolVersion: '1.0',
              bootTokenFile: paths.bootTokenFile, configRevision: this.configRevision(), publishedAt: new Date().toISOString(),
            });
            this.setSession(await this.attach(paths.manifestFile));
            this.change('READY');
            return this.view();
          }
        } catch (error) { if (error.fatal) throw error; }
        await delay(200);
      }
      throw new Error('Personal Runtime did not become ready in time');
    } catch (error) {
      if (this.child) await this.close();
      else {
        if (startupLog) {
          const closed = finished(startupLog).catch(() => {});
          startupLog.end();
          await closed;
        }
        releaseDomainOwnerSync(paths, this.ownerInstanceId);
      }
      throw error;
    } finally {
      if (!startedChild || !isPidAlive(startedChild.pid)) portLease?.release();
    }
  }

  async close() {
    // An attached client has no process or lease ownership, even when the shared Runtime is idle.
    if (!this.child) { this.session = null; this.change('CLOSED'); return; }
    this.change('CLOSING');
    const child = this.child;
    await this.stop(child, this.session, this.paths);
    if (isPidAlive(child.pid)) throw new Error('Personal Runtime did not exit; ownership retained');
    this.child = null;
    this.session = null;
    unpublishDomainManifestSync(this.paths);
    releaseDomainOwnerSync(this.paths, this.ownerInstanceId);
    this.change('CLOSED');
  }
}
