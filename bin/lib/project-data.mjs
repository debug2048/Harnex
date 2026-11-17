import { existsSync, readFileSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { workspaceIdentity } from './workspace-identity.mjs';

/** The launcher and backup command resolve the same project owner and explicit overrides. */
export function projectDataDirectory(root, userDataDir, configured = process.env.HARNEX_DATA_DIR) {
  const { root: project, id } = workspaceIdentity(root);
  if (typeof configured === 'string' && configured.trim()) return resolve(project, configured.trim());
  const file = join(userDataDir, 'catalog', 'workspaces.json');
  const catalog = existsSync(file) ? JSON.parse(readFileSync(file, 'utf8')) : { workspaces: [] };
  if (!Array.isArray(catalog.workspaces)) throw Error('Workspace catalog is invalid; refusing to invent a data directory');
  const recorded = catalog.workspaces.find(item => item.id === id && !item.remoteProfile)?.dataDir;
  return resolve(project, recorded || '.harnex/data');
}
