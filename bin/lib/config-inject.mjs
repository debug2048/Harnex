// Shared Spring config-location injection helper for the Node launchers
// (bin/harnex.mjs, scripts/dev/dev.mjs, bin/lib/supervisor.mjs).
//
// Why this exists:
//   1) #1 — every launcher that starts the backend must inject the user-level
//      ~/.harnex/agent-config.yml the SAME way, so `harnex serve`, `harnex supervisor`
//      and `dev` behave identically. Centralising it here removes the per-launcher drift.
//   2) #2 — build the `file:` URL with pathToFileURL() instead of `"file:"+p.replace(\\→/)`.
//      The hand-rolled form silently truncates at the first `#`, mis-encodes spaces / `%`
//      and breaks on non-ASCII paths. pathToFileURL() percent-encodes correctly on every OS.
//   3) #3 — record every CONCRETE config file we inject in HARNEX_CONFIG_FILES (newline
//      separated, OS paths) so the backend's PathProtection write-protects exactly the
//      control-plane files that were actually loaded — instead of blanket-protecting any
//      agent-config.yml found anywhere under the workspace (which false-positives on a
//      user's own repo).
import { pathToFileURL } from "node:url";
import { readFileSync } from "node:fs";

const FILE_SEP = "\n";

// Spring splits SPRING_CONFIG_ADDITIONAL_LOCATION on ',' and ';'. pathToFileURL() leaves any
// literal ',' / ';' that occur INSIDE the OS path unescaped, so a path like
// C:\a,b\agent-config.yml (or a Windows 8.3 name containing ';') would be mis-split into two
// bogus locations and silently dropped. Percent-encode just those two delimiters; everything
// else pathToFileURL already encoded correctly.
function encodeSpringLocation(url) {
  return url.replace(/,/g, "%2C").replace(/;/g, "%3B");
}

function appendLocation(env, url) {
  const prev = env.SPRING_CONFIG_ADDITIONAL_LOCATION || "";
  const parts = prev ? prev.split(",") : [];
  if (!parts.includes(url)) {
    parts.push(url);
    env.SPRING_CONFIG_ADDITIONAL_LOCATION = parts.join(",");
    return true;
  }
  return false;
}

function recordConfigFile(env, file) {
  const prev = env.HARNEX_CONFIG_FILES || "";
  const parts = prev ? prev.split(FILE_SEP) : [];
  if (!parts.includes(file)) {
    parts.push(file);
    env.HARNEX_CONFIG_FILES = parts.join(FILE_SEP);
  }
}

/** Fail early instead of silently resolving desktop-only Keychain references to an empty API key. */
export function requireResolvableKeychainReferences(env, file, platform = process.platform) {
  if (platform !== "darwin") return;
  const body = readFileSync(file, "utf8");
  const references = new Set();
  for (const line of body.split(/\r?\n/)) {
    if (line.trimStart().startsWith("#")) continue;
    for (const match of line.matchAll(/\$\{(HARNEX_KEYCHAIN_PROVIDER_[A-Z0-9_]+)(?::[^}]*)?\}/g)) {
      references.add(match[1]);
    }
  }
  const missing = [...references].filter(name => !env[name]);
  if (missing.length) {
    throw new Error(
      `${file} contains macOS Keychain Provider references that this non-desktop launcher cannot resolve `
      + `(${missing.join(", ")}). Open the workspace through Harnex.app/attach to its Runtime, or replace `
      + `those references with credentials supplied explicitly to this launcher.`
    );
  }
}

/**
 * Append a SPECIFIC config FILE to SPRING_CONFIG_ADDITIONAL_LOCATION (so Spring imports it by
 * exact path — a directory location would only scan for application.* and silently ignore a
 * co-located agent-config.yml) and record it in HARNEX_CONFIG_FILES for the backend guard.
 *
 * @param {Record<string,string>} env  the child env being assembled (mutated in place)
 * @param {string} file                absolute OS path to the config file
 * @returns {boolean} true if it was newly added (false if already present)
 */
export function appendConfigFile(env, file) {
  requireResolvableKeychainReferences(env, file);
  const url = encodeSpringLocation(pathToFileURL(file).href);
  const added = appendLocation(env, url);
  recordConfigFile(env, file);
  return added;
}

/**
 * Append a config DIRECTORY to SPRING_CONFIG_ADDITIONAL_LOCATION (trailing slash → Spring
 * scans it for application.*). The directory itself is NOT recorded in HARNEX_CONFIG_FILES;
 * a co-located agent-config.yml must be added separately via appendConfigFile().
 *
 * @param {Record<string,string>} env  the child env being assembled (mutated in place)
 * @param {string} dir                 absolute OS path to the config directory
 * @returns {boolean} true if it was newly added (false if already present)
 */
export function appendConfigDir(env, dir) {
  let url = encodeSpringLocation(pathToFileURL(dir).href);
  if (!url.endsWith("/")) {
    url += "/";
  }
  return appendLocation(env, url);
}
