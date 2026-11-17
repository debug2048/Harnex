import { join } from 'node:path';
import { spawnSync } from 'node:child_process';
import { closeSync, openSync, readSync } from 'node:fs';

/** Windows needs libarchive's drive-path and ZIP support, regardless of the calling shell's PATH. */
export function tarExecutable() {
  if (process.platform !== 'win32') return 'tar';
  return join(process.env.SystemRoot || process.env.SYSTEMROOT || process.env.WINDIR || 'C:\\Windows', 'System32', 'tar.exe');
}

/** Keep Unicode paths out of older Windows tar's ANSI argument parsing. */
export function extractArchive(archive, destination, options = {}) {
  const input = openSync(archive, 'r');
  try {
    // GNU tar cannot autodetect compressed input from stdin. ZIP remains native
    // libarchive territory on Windows; gzip needs an explicit switch on POSIX.
    const signature = Buffer.alloc(2);
    readSync(input, signature, 0, 2, 0);
    const flags = signature[0] === 0x1f && signature[1] === 0x8b ? '-xzf' : '-xf';
    return spawnSync(tarExecutable(), [flags, '-'], {
      encoding: 'utf8', windowsHide: true, timeout: 120_000, ...options,
      cwd: destination, stdio: [input, 'pipe', 'pipe'],
    });
  } finally {
    closeSync(input);
  }
}
