// Cross-platform launcher + desktop-shortcut installer for harnex.
//
// Exports:
//   cmdLaunch(rest)       — interactive picker: WebUI or TUI, then exec the choice.
//   cmdShortcut(rest)     — install desktop shortcut for the current OS.
//   cmdUninstallShortcut(rest)
//                          — remove the shortcut(s) created by cmdShortcut.
//
// Design notes:
//  - We deliberately do NOT pull in any extra npm deps. Everything is plain Node + OS shell.
//  - Windows: a .lnk is generated via PowerShell COM (WScript.Shell). Works on every PS-equipped Win 10/11.
//  - macOS:   a .command launcher is dropped under ~/Applications.
//  - Linux:   a freedesktop .desktop file is dropped under ~/Desktop only (no app-list/menu entry).
//  - The shortcut runs `harnex launch`, which then prompts the user for WebUI vs TUI.
//  - `harnex launch --webui` / `--tui` skip the picker (useful when scripted or chained from another shortcut).
//  - Per user request: shortcuts are placed on the Desktop ONLY (never the Start Menu / app launcher),
//    and `harnex shortcut --uninstall` (or `harnex uninstall-shortcut`) removes them.

import { spawn, spawnSync } from "node:child_process";
import { existsSync, mkdirSync, writeFileSync, chmodSync, copyFileSync, unlinkSync } from "node:fs";
import { homedir, platform } from "node:os";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { createInterface } from "node:readline";
import { request as httpRequest } from "node:http";
import { resolveWebPort } from './ports.mjs';

const __dirname = dirname(fileURLToPath(import.meta.url));
const REPO_ROOT = resolve(__dirname, "../..");
const ASSETS_DIR = join(__dirname, "..", "assets");
const ICON_SVG = join(ASSETS_DIR, "harnex.svg");
const IS_WIN = platform() === "win32";
const IS_MAC = platform() === "darwin";
const IS_LINUX = !IS_WIN && !IS_MAC;

const C = {
  dim:   (s) => `\u001b[2m${s}\u001b[0m`,
  cyan:  (s) => `\u001b[36m${s}\u001b[0m`,
  green: (s) => `\u001b[32m${s}\u001b[0m`,
  red:   (s) => `\u001b[31m${s}\u001b[0m`,
  bold:  (s) => `\u001b[1m${s}\u001b[0m`,
  yellow:(s) => `\u001b[33m${s}\u001b[0m`,
};

function info(msg) { process.stdout.write(C.dim(msg) + "\n"); }
function ok(msg)   { process.stdout.write(C.green("✓ ") + msg + "\n"); }
function warn(msg) { process.stdout.write(C.yellow("! ") + msg + "\n"); }
function die(msg, code = 1) { process.stderr.write(C.red("harnex: ") + msg + "\n"); process.exit(code); }

// ─────────────────────────────────────────────────────────────────────────
// Port resolution (single source of truth — used by launcher AND harnex.mjs)
// ─────────────────────────────────────────────────────────────────────────

/**
 * Resolve the standalone `serve` entrypoint. Precedence (highest first):
 *   1. {@code --server.port=NNNN} on the command line (Spring Boot's own arg)
 *   2. {@code HARNEX_PORT} env var (operator override)
 *   3. {@code SERVER_PORT} env var (Spring Boot's standard env binding)
 *   4. Shared public Web default (18081).
 * The internal backend default in application.yml does not change this public entrypoint;
 * the owner passes the resolved port explicitly to the JVM in source and packaged installs.
 *
 * @param {string[]} rest extra args destined for the JVM (parsed read-only)
 * @returns {number} a TCP port in [1, 65535]
 */
export function resolveServerPort(rest = [], env = process.env) {
  return resolveWebPort(rest, env);
}

// ─────────────────────────────────────────────────────────────────────────
// `harnex launch`
// ─────────────────────────────────────────────────────────────────────────

/**
 * Locate the `harnex` shim that npm installed on the user's PATH.
 *  - When running from `node bin/harnex.mjs launch`, we just re-exec the same script.
 *  - When the binary `harnex` is shimmed by npm (global install), we still go through
 *    `process.execPath` + the launcher to avoid PATH ambiguity.
 */
function selfArgs(extra) {
  // Always re-enter via Node + the launcher script — bullet-proof on Win / *nix alike.
  return [resolve(__dirname, "..", "harnex.mjs"), ...extra];
}

/** Spawn a fresh Node process running `harnex <subcmd> <args>` and inherit its exit code. */
function execSelf(subcmd, args = [], { detach = false } = {}) {
  const node = process.execPath;
  const argv = selfArgs([subcmd, ...args]);
  if (detach) {
    const child = spawn(node, argv, { stdio: "ignore", detached: true, env: process.env });
    child.unref();
    return 0;
  }
  const r = spawnSync(node, argv, { stdio: "inherit", env: process.env });
  process.exit(r.status ?? 0);
}

/** Open a URL in the user's default browser. Best-effort — never throws. */
function openBrowser(url) {
  try {
    if (IS_WIN)        spawn("cmd",  ["/c", "start", "", url], { detached: true, stdio: "ignore" }).unref();
    else if (IS_MAC)   spawn("open", [url],                      { detached: true, stdio: "ignore" }).unref();
    else               spawn("xdg-open", [url],                  { detached: true, stdio: "ignore" }).unref();
  } catch { /* best-effort */ }
}

/**
 * Poll {@code GET <url>} until it returns 200 (or any 2xx/3xx) or {@code timeoutMs} elapses,
 * then open the URL in the default browser. Replaces the previous fixed setTimeout(1500), which
 * was unreliable on cold-JVM first launch.
 */
function openWhenReady(url, healthUrl, timeoutMs = 60000) {
  const start = Date.now();
  let opened = false;
  const tryOnce = () => {
    if (opened) return;
    if (Date.now() - start > timeoutMs) {
      // Give up waiting; open anyway so the user sees the browser error and can retry.
      opened = true;
      openBrowser(url);
      return;
    }
    const req = httpRequest(healthUrl, { method: "GET", timeout: 800 }, (res) => {
      // Drain the body so the socket can be released.
      res.resume();
      if (res.statusCode && res.statusCode < 400) {
        opened = true;
        openBrowser(url);
      } else {
        setTimeout(tryOnce, 500).unref();
      }
    });
    req.on("error",   () => setTimeout(tryOnce, 500).unref());
    req.on("timeout", () => { req.destroy(); setTimeout(tryOnce, 500).unref(); });
    req.end();
  };
  tryOnce();
}

async function pickMode(port) {
  process.stdout.write("\n" + C.bold(C.cyan("  Harnex")) + "\n");
  process.stdout.write(C.dim("  Choose how you want to interact:") + "\n\n");
  process.stdout.write("    " + C.bold("[1]") + "  Web UI    " + C.dim(`→ open browser at http://localhost:${port}`) + "\n");
  process.stdout.write("    " + C.bold("[2]") + "  Terminal  " + C.dim("→ Claude-Code-style TUI in this shell") + "\n");
  process.stdout.write("    " + C.bold("[q]") + "  Quit\n\n");
  const rl = createInterface({ input: process.stdin, output: process.stdout });
  // Default readline behaviour on SIGINT is to do nothing for `question()` callbacks; emit a clean exit.
  rl.on("SIGINT", () => { rl.close(); process.stdout.write("\n"); info("cancelled."); process.exit(130); });
  const ask = () => new Promise(res => rl.question(C.cyan("  > "), a => res(a.trim().toLowerCase())));
  while (true) {
    const a = await ask();
    if (a === "1" || a === "w" || a === "web" || a === "webui") { rl.close(); return "webui"; }
    if (a === "2" || a === "t" || a === "tui" || a === "cli")   { rl.close(); return "tui"; }
    if (a === "q" || a === "quit" || a === "exit")              { rl.close(); return "quit"; }
    process.stdout.write(C.yellow("  please type 1, 2, or q\n"));
  }
}

export async function cmdLaunch(rest = []) {
  // Allow scripted bypass: `harnex launch --webui` / `--tui`.
  let mode = null;
  if (rest.includes("--webui") || rest.includes("--web")) mode = "webui";
  else if (rest.includes("--tui") || rest.includes("--cli")) mode = "tui";

  // Non-TTY (double-clicked .lnk on Windows can still allocate a console; .desktop usually does too)
  // — if for some reason we have no TTY *and* no flag, default to WebUI which is safer.
  if (!mode && !process.stdin.isTTY) {
    info("no TTY detected; defaulting to Web UI. Pass --tui to force terminal mode.");
    mode = "webui";
  }

  // The standalone Web UI is always the Supervisor control plane. Runtime ports are
  // private implementation details and must never become browser bookmarks.
  const port = resolveWebPort(rest, process.env, ['HARNEX_SUPERVISOR_PORT']);

  if (!mode) mode = await pickMode(port);
  if (mode === "quit") { info("bye."); process.exit(0); }

  // Strip our own flags before forwarding the rest to the JVM.
  const passthrough = rest.filter(a => !["--webui","--web","--tui","--cli"].includes(a));

  if (mode === "webui") {
    // Poll the stable control plane before opening the browser. It may intentionally
    // have no active Runtime on a first launch.
    const base = `http://localhost:${port}`;
    openWhenReady(base + "/", base + "/supervisor/api/status");
    const supervisorArgs = [
      `--port=${port}`,
      ...passthrough
        .filter(arg => !/^--(?:server\.)?port=/.test(String(arg)))
        .map(arg => `--child-arg=${arg}`),
    ];
    execSelf("supervisor", supervisorArgs);
  } else {
    execSelf("tui", passthrough);
  }
}

// ─────────────────────────────────────────────────────────────────────────
// `harnex shortcut`  — install desktop / Start-menu icon
// ─────────────────────────────────────────────────────────────────────────

/** Resolve the user-visible `harnex` command for the shortcut target. */
function resolveHarnexTarget() {
  // Prefer the npm-installed shim (so `harnex` works after upgrades), otherwise fall back to absolute script path.
  const which = spawnSync(IS_WIN ? "where" : "which", ["harnex"], { encoding: "utf8" });
  if (which.status === 0) {
    // pick the first hit
    const first = (which.stdout || "").split(/\r?\n/).map(s => s.trim()).find(Boolean);
    if (first && existsSync(first)) return first;
  }
  // Fallback: invoke our launcher directly via node.
  return null;
}

/** Ensure a directory exists; return path. */
function ensureDir(p) { if (!existsSync(p)) mkdirSync(p, { recursive: true }); return p; }

// ─── Windows ─────────────────────────────────────────────────────────────

function installShortcutWindows({ desktop = true, mode = "launch" } = {}) {
  // Resolve target. If `harnex` is on PATH we point to it directly; else use node + harnex.mjs.
  const harnexBin = resolveHarnexTarget();
  let targetPath, args;
  if (harnexBin && /\.cmd$|\.exe$|\.bat$/i.test(harnexBin)) {
    targetPath = harnexBin;
    args = mode === "launch" ? "launch" : mode;
  } else {
    targetPath = process.execPath; // node.exe
    args = `"${join(__dirname, "..", "harnex.mjs")}" ${mode === "launch" ? "launch" : mode}`;
  }

  // Ship the same multi-resolution ICO as the desktop app.
  const iconIco = join(ASSETS_DIR, "harnex.ico");
  const iconClause = existsSync(iconIco) ? `$s.IconLocation = '${iconIco.replace(/'/g, "''")}'` : "";

  const desktopDir = join(homedir(), "Desktop");
  const lnkName    = "Harnex.lnk";

  const targets = [];
  if (desktop) { ensureDir(desktopDir); targets.push(join(desktopDir, lnkName)); }

  for (const lnk of targets) {
    const ps = `
$ws = New-Object -ComObject WScript.Shell
$s  = $ws.CreateShortcut('${lnk.replace(/'/g, "''")}')
$s.TargetPath  = '${targetPath.replace(/'/g, "''")}'
$s.Arguments   = '${String(args).replace(/'/g, "''")}'
$s.WorkingDirectory = '${homedir().replace(/'/g, "''")}'
$s.WindowStyle = 1
$s.Description = 'Harnex — pick Web UI or Terminal on launch'
${iconClause}
$s.Save()
`.trim();
    const r = spawnSync("powershell.exe", ["-NoProfile", "-NonInteractive", "-Command", ps], { stdio: "pipe" });
    if (r.status !== 0) {
      warn(`failed to create ${lnk}: ${(r.stderr || "").toString().trim() || "powershell exit " + r.status}`);
    } else {
      ok(`created ${lnk}`);
    }
  }
}

function uninstallShortcutWindows() {
  const lnkName = "Harnex.lnk";
  const candidates = [
    join(homedir(), "Desktop", lnkName),
    // Belt-and-braces: previous versions also dropped a Start Menu entry; clean it up if found.
    join(homedir(), "AppData", "Roaming", "Microsoft", "Windows", "Start Menu", "Programs", lnkName),
  ];
  for (const f of candidates) removeIfExists(f);
}

// ─── macOS ───────────────────────────────────────────────────────────────

function installShortcutMac({ mode = "launch" } = {}) {
  // Drop a .command file (double-clickable, opens Terminal) under ~/Applications.
  const appsDir = join(homedir(), "Applications");
  ensureDir(appsDir);
  const file = join(appsDir, "Harnex.command");
  const harnexBin = resolveHarnexTarget();
  const invocation = harnexBin
    ? `${shQuote(harnexBin)} ${shQuote(mode)}`
    : `${shQuote(process.execPath)} ${shQuote(join(__dirname, "..", "harnex.mjs"))} ${shQuote(mode)}`;
  const body =
`#!/usr/bin/env bash
# Harnex launcher — generated by 'harnex shortcut'
exec ${invocation}
`;
  writeFileSync(file, body, { mode: 0o755 });
  try { chmodSync(file, 0o755); } catch {}
  ok(`created ${file}`);
  info("Tip: drag this file to your Dock for one-click launch.");
}

function shQuote(value) {
  return `'${String(value).replaceAll("'", "'\\''")}'`;
}

function uninstallShortcutMac() {
  removeIfExists(join(homedir(), "Applications", "Harnex.command"));
}

// ─── Linux / *BSD ────────────────────────────────────────────────────────

function installShortcutLinux({ desktop = true, mode = "launch" } = {}) {
  const harnexBin = resolveHarnexTarget();
  // .desktop spec: Exec must NOT be quoted as a single string if there are flags.
  const execLine = harnexBin
    ? `${harnexBin} ${mode}`
    : `${process.execPath} ${join(__dirname, "..", "harnex.mjs")} ${mode}`;

  // Copy SVG icon into ~/.local/share/icons so the .desktop can reference it stably.
  const iconDir = join(homedir(), ".local", "share", "icons");
  ensureDir(iconDir);
  const iconDst = join(iconDir, "harnex.svg");
  try { copyFileSync(ICON_SVG, iconDst); } catch { /* best-effort */ }

  const body =
`[Desktop Entry]
Type=Application
Name=Harnex
GenericName=Agent Harness
Comment=Pick Web UI or Terminal on launch
Exec=${execLine}
Icon=${existsSync(iconDst) ? iconDst : "utilities-terminal"}
Terminal=true
Categories=Development;Utility;
StartupNotify=false
`;

  // Per user request: write the Desktop entry only — no app-launcher / start-menu integration.
  const targets = [];
  if (desktop) targets.push(join(homedir(), "Desktop", "harnex.desktop"));

  for (const f of targets) {
    ensureDir(dirname(f));
    writeFileSync(f, body, { mode: 0o755 });
    try { chmodSync(f, 0o755); } catch {}
    ok(`created ${f}`);
  }

  // Some DEs (GNOME 42+) also require the Desktop file to be marked "trusted" via gio.
  try {
    spawnSync("gio", ["set", join(homedir(), "Desktop", "harnex.desktop"),
                      "metadata::trusted", "true"], { stdio: "ignore" });
  } catch { /* harmless if gio missing */ }
}

function uninstallShortcutLinux() {
  // Remove both the current location (Desktop only) and any legacy app-list entry from earlier versions.
  removeIfExists(join(homedir(), "Desktop", "harnex.desktop"));
  removeIfExists(join(homedir(), ".local", "share", "applications", "harnex.desktop"));
  removeIfExists(join(homedir(), ".local", "share", "icons", "harnex.svg"));
}

/** Delete a file if present; never throw. Logs a friendly line either way. */
function removeIfExists(p) {
  if (!existsSync(p)) return false;
  try { unlinkSync(p); ok(`removed ${p}`); return true; }
  catch (e) { warn(`could not remove ${p}: ${e.message}`); return false; }
}

// ─── Top-level shortcut command ──────────────────────────────────────────

export function cmdShortcut(rest = []) {
  // `--uninstall` here also flows through to the same code path so users can write either form.
  if (rest.includes("--uninstall") || rest.includes("-u")) {
    return cmdUninstallShortcut(rest);
  }

  const mode = rest.includes("--tui") ? "cli"
            : rest.includes("--webui") ? "launch --webui"
            : "launch";
  const noDesktop = rest.includes("--no-desktop");

  process.stdout.write("\n" + C.bold("Installing Harnex shortcut") + C.dim(`  (mode=${mode})`) + "\n\n");

  try {
    if (IS_WIN)        installShortcutWindows({ desktop: !noDesktop, mode });
    else if (IS_MAC)   installShortcutMac({ mode });
    else               installShortcutLinux({ desktop: !noDesktop, mode });
    process.stdout.write("\n" + C.green("Done.") +
      C.dim(" Look for ‘Harnex’ on your desktop. Run 'harnex shortcut --uninstall' to remove.") + "\n");
  } catch (e) {
    die("shortcut install failed: " + (e?.message || e));
  }
}

export function cmdUninstallShortcut(_rest = []) {
  process.stdout.write("\n" + C.bold("Removing Harnex shortcut") + "\n\n");
  try {
    if (IS_WIN)      uninstallShortcutWindows();
    else if (IS_MAC) uninstallShortcutMac();
    else             uninstallShortcutLinux();
    process.stdout.write("\n" + C.green("Done.") + "\n");
  } catch (e) {
    die("shortcut uninstall failed: " + (e?.message || e));
  }
}

export const SHORTCUT_HELP = `
harnex launch    [--webui|--tui]   Interactive picker; use flags to skip prompt
harnex shortcut  [--webui|--tui]   Install Desktop shortcut
                [--no-desktop]    Skip the Desktop entry (effectively a no-op install)
                [--uninstall|-u]  Remove the shortcut
harnex uninstall-shortcut          Alias for 'harnex shortcut --uninstall'
`.trim();
