import { createHash } from 'node:crypto';
import { realpathSync } from 'node:fs';
import { resolve } from 'node:path';

/** Same canonical-path UTF-8/SHA-256 contract as Java Path.toRealPath and Rust canonicalize. */
export function workspaceIdentity(directory) {
  // On Windows the JS realpath implementation preserves drive spelling and DOS 8.3 aliases.
  // The native implementation expands them, as do the Runtime and native hosts.
  const root = realpathSync.native(resolve(directory));
  return { root, id: createHash('sha256').update(root, 'utf8').digest('hex').slice(0, 12) };
}
