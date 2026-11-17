import { appendFileSync, closeSync, existsSync, lstatSync, mkdirSync, openSync, readSync, readdirSync, renameSync, statSync, unlinkSync, writeFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { Writable } from 'node:stream';

const MAX_BYTES = 2 * 1024 * 1024;
const active = new Set();
const managed = /^(?:[a-f0-9]{12,64}|runtime(?:-out|-err)?|supervisor)\.log(?:\.1)?$/;

function regularOrMissing(file) {
  try { if (!lstatSync(file).isFile()) throw new Error('Diagnostic log must be a regular file'); }
  catch (error) { if (error.code !== 'ENOENT') throw error; }
}

function tail(file, size, maxBytes) {
  const bytes = Buffer.alloc(Math.min(size, maxBytes));
  const fd = openSync(file, 'r');
  try { return bytes.subarray(0, readSync(fd, bytes, 0, bytes.length, Math.max(0, size - bytes.length))); }
  finally { closeSync(fd); }
}

/** Keep only recent, inactive host logs; never touch runtime manifests, credentials or workspace data. */
export function pruneHostLogs(directory, { now = Date.now(), maxBytes = 32 * 1024 * 1024, keep = [] } = {}) {
  if (!existsSync(directory) || !lstatSync(directory).isDirectory()) return;
  const pinned = new Set([...active, ...keep.map(file => resolve(file))]);
  const files = readdirSync(directory).filter(name => managed.test(name)).map(name => {
    const file = resolve(directory, name), stat = lstatSync(file);
    return { file, stat };
  }).filter(({ stat }) => stat.isFile()).sort((a, b) => a.stat.mtimeMs - b.stat.mtimeMs);
  let total = files.reduce((sum, { stat }) => sum + stat.size, 0);
  for (const { file, stat } of files) {
    if (pinned.has(file) || pinned.has(file.replace(/\.1$/, ''))) continue;
    if (stat.mtimeMs < now - 7 * 86400_000 || total > maxBytes) {
      unlinkSync(file); total -= stat.size;
    }
  }
}

/** Current file plus one backup. Oversized writes/files retain only their bounded tail. */
export function appendBoundedLog(file, value, { maxBytes = MAX_BYTES } = {}) {
  file = resolve(file);
  if (!Number.isSafeInteger(maxBytes) || maxBytes < 1) throw new Error('Invalid log limit');
  let bytes = Buffer.isBuffer(value) ? value : Buffer.from(value);
  if (bytes.length > maxBytes) bytes = bytes.subarray(bytes.length - maxBytes);
  mkdirSync(dirname(file), { recursive: true });
  regularOrMissing(file);
  const size = existsSync(file) ? statSync(file).size : 0;
  if (size + bytes.length > maxBytes) {
    const backup = `${file}.1`;
    regularOrMissing(backup);
    if (size > maxBytes) {
      writeFileSync(backup, tail(file, size, maxBytes), { mode: 0o600 });
      writeFileSync(file, Buffer.alloc(0));
    } else if (size > 0) {
      if (existsSync(backup)) unlinkSync(backup);
      renameSync(file, backup);
    }
    pruneHostLogs(dirname(file), { keep: [file] });
  }
  appendFileSync(file, bytes, { mode: 0o600 });
}

export function createBoundedLogStream(file, options) {
  file = resolve(file);
  active.add(file);
  try { pruneHostLogs(dirname(file)); } catch { /* Diagnostics never prevent runtime startup. */ }
  const stream = new Writable({
    write(chunk, encoding, callback) {
      try { appendBoundedLog(file, chunk, options); } catch { /* Keep draining the child even if logs are unavailable. */ }
      callback();
    },
  });
  stream.once('close', () => active.delete(file));
  return stream;
}
