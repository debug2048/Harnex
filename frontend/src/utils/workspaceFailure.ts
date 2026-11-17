import { t } from '../i18n'

/** Keep host diagnostics intact, but lead with a useful explanation and recovery action. */
export function workspaceFailure(message: string, remote = false, code = '', personal = false) {
  if (!code && /^[\u3400-\u9fff]/.test(message)) return {summary:message, details:message}
  const text = message.toLowerCase()
  const fallback = personal ? 'workspace.personalOpenFailureHint' : 'workspace.openFailureHint'
  let key = fallback
  if (/datadir.*(already locked|in use)|data_dir_locked/.test(text)) key = personal ? 'workspace.personalDataBusy' : 'workspace.dataBusy'
  else if (personal && /manifest\/owner identity|personal runtime.*(identity|lease)/.test(text)) key = 'workspace.personalIdentityFailed'
  else if (text.includes('upgrade_busy')) key = 'remote.error.upgradeBusy'
  else if (text.includes('upgrade_unsupported')) key = 'remote.error.upgradeUnsupported'
  else if (/existing service.*(unavailable|rejected)|service identity.*changed/.test(text)) key = 'remote.error.existingService'
  else if (/remote runtime (exited|startup failed|startup timed out)/i.test(message)) key = 'remote.error.runtimeStart'
  else if (/close the (active|current).*before removing/.test(text)) key = 'remote.closeBeforeRemove'
  else if (text.includes('ssh_component_outdated') || text.includes('component needs an update')) key = 'remote.componentOutdated'
  else if (text.includes('connection was removed') || text.includes('missing connection')) key = 'remote.missingConnection'
  else if (text.includes('host identification has changed') || text.includes('host key verification failed')) key = 'remote.error.hostKey'
  else if (remote && (text.includes('authentication') || text.includes('permission denied'))) key = 'remote.error.authentication'
  else if (/not a directory|can't cd|cannot cd|directory does not exist/.test(text)) key = remote ? 'remote.error.directory' : 'workspace.directoryUnavailable'
  else if (/timed out|refused|no route|resolve hostname|unreachable|connection ended|closed socket/.test(text)) key = remote ? 'remote.error.network' : fallback
  else if (['connection','hostKey','keyPermissions','authentication','network','directory','environment','cancelled'].includes(code)) key = `remote.error.${code}`
  else if (remote && /cancelled|canceled/.test(text)) key = 'remote.error.cancelled'
  else if (remote) key = 'remote.error.open'
  return {summary:t(key), details:message}
}
