import { stat as filesystemStat } from 'node:fs/promises';

/** Discover currently readable roots without starting a shell or caching failed drive discovery. */
export async function filesystemRoots({ platform = process.platform, stat = filesystemStat, timeoutMs = 1200 } = {}) {
  const candidates = platform === 'win32'
    ? Array.from({ length: 26 }, (_, index) => `${String.fromCharCode(65 + index)}:\\`)
    : ['/'];
  const roots = await Promise.all(candidates.map(async root => {
    let timer;
    try {
      const entry = await Promise.race([
        Promise.resolve().then(() => stat(root)),
        new Promise(resolve => { timer = setTimeout(() => resolve(null), timeoutMs); }),
      ]);
      return entry?.isDirectory() ? root : null;
    } catch { return null; } finally { clearTimeout(timer); }
  }));
  return roots.filter(Boolean);
}
