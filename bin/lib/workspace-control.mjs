import { createServer, createConnection } from 'node:net';
import { randomBytes, randomUUID, timingSafeEqual } from 'node:crypto';
import { readFileSync, unlinkSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { atomicWriteJsonSync, isDomainOwnerAlive } from './runtime-domain.mjs';

const LIMIT = 65536;
const descriptorFile = manifest => join(dirname(manifest), 'workspace-control.json');

/** A host-owned channel survives replacement of its project JVM. It never exposes the JVM's supervisor token. */
export async function serveWorkspaceControl(domain, ownerInstanceId, switchProject) {
  const token = randomBytes(32).toString('base64url');
  const sockets = new Set();
  let closing = false;
  const server = createServer(socket => {
    sockets.add(socket);
    socket.on('close', () => sockets.delete(socket));
    socket.on('error', () => {});
    socket.setTimeout(5000, () => socket.destroy());
    let buffer = Buffer.alloc(0), received = false;
    socket.on('data', async chunk => {
      if (received) return;
      buffer = Buffer.concat([buffer, chunk]);
      if (buffer.length > LIMIT) { socket.destroy(); return; }
      const end = buffer.indexOf(10);
      if (end < 0) return;
      received = true;
      socket.setTimeout(180000);
      let id;
      try {
        const request = JSON.parse(buffer.subarray(0, end).toString('utf8'));
        id = request.id;
        const credential = Buffer.from(typeof request.token === 'string' ? request.token : '');
        const expected = Buffer.from(token);
        if (closing || credential.length !== expected.length || !timingSafeEqual(credential, expected)
            || request.ownerInstanceId !== ownerInstanceId) throw new Error('Workspace host connection is no longer valid');
        if (request.action !== 'switch' || typeof request.root !== 'string' || !request.root
            || typeof request.expectedInstanceId !== 'string' || !request.expectedInstanceId) throw new Error('Invalid workspace switch request');
        const result = await switchProject(request.root, request.expectedInstanceId);
        socket.end(JSON.stringify({ id, ok: true, result }) + '\n');
      } catch (error) {
        socket.end(JSON.stringify({ id, ok: false, error: error.message }) + '\n');
      }
    });
  });
  await new Promise((resolve, reject) => { server.once('error', reject); server.listen(0, '127.0.0.1', resolve); });
  const file = descriptorFile(domain.manifestFile);
  try {
    atomicWriteJsonSync(file, { schemaVersion: 1, ownerInstanceId, host: '127.0.0.1', port: server.address().port, token }, { backup: false });
  } catch (error) { server.close(); throw error; }
  return { close() {
    closing = true;
    try { if (JSON.parse(readFileSync(file, 'utf8')).ownerInstanceId === ownerInstanceId) unlinkSync(file); } catch {}
    server.close();
    for (const socket of sockets) socket.destroy();
  } };
}

/** Validate the original owner even while domain.json is unpublished during a switch. */
export function switchHostWorkspace(manifestFile, ownerInstanceId, expectedInstanceId, root, signal) {
  const owner = JSON.parse(readFileSync(join(dirname(manifestFile), 'owner.json'), 'utf8'));
  if (owner.ownerInstanceId !== ownerInstanceId || !isDomainOwnerAlive(owner, { requireIdentity: true })) {
    throw new Error('原 Runtime 宿主已退出或重新启动，请主动重新连接。');
  }
  let descriptor;
  try { descriptor = JSON.parse(readFileSync(descriptorFile(manifestFile), 'utf8')); }
  catch { throw new Error('此 Runtime 宿主不支持跟随 VS Code 切换项目。请使用支持项目切换的 Tauri 或 Web 宿主。'); }
  if (descriptor.schemaVersion !== 1 || descriptor.ownerInstanceId !== ownerInstanceId
      || descriptor.host !== '127.0.0.1' || !Number.isInteger(descriptor.port) || descriptor.port < 1 || descriptor.port > 65535
      || !/^[A-Za-z0-9_-]{32,128}$/.test(descriptor.token)) throw new Error('Runtime 宿主控制信息无效。');
  return new Promise((resolve, reject) => {
    const id = randomUUID();
    const socket = createConnection({ host: descriptor.host, port: descriptor.port });
    let buffer = Buffer.alloc(0), settled = false;
    const finish = (error, value) => {
      if (settled) return;
      settled = true; signal?.removeEventListener('abort', cancel); socket.destroy();
      error ? reject(error) : resolve(value);
    };
    const cancel = () => finish(new Error('Runtime 连接已取消。'));
    signal?.addEventListener('abort', cancel, { once: true });
    socket.setTimeout(180000, () => finish(new Error('等待宿主切换项目超时，请检查宿主状态后重连。')));
    socket.on('error', error => finish(error));
    socket.on('close', () => finish(new Error('Runtime 宿主连接已关闭。')));
    socket.once('connect', () => socket.write(JSON.stringify({ id, token: descriptor.token, ownerInstanceId,
      action: 'switch', expectedInstanceId, root }) + '\n'));
    socket.on('data', chunk => {
      buffer = Buffer.concat([buffer, chunk]);
      if (buffer.length > LIMIT) { finish(new Error('Runtime 宿主响应过大。')); return; }
      const end = buffer.indexOf(10);
      if (end < 0) return;
      try {
        const reply = JSON.parse(buffer.subarray(0, end).toString('utf8'));
        if (reply.id !== id) throw new Error('Runtime 宿主响应不匹配。');
        if (!reply.ok) throw new Error(reply.error || 'Runtime 项目切换失败。');
        finish(undefined, reply.result);
      } catch (error) { finish(error); }
    });
    if (signal?.aborted) cancel();
  });
}
