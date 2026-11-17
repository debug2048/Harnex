#!/usr/bin/env node
// Postinstall: validate the user's environment and tell them what to do next.
// Intentionally non-fatal — even if Java is missing we want `npm install` to succeed,
// because users may install via CI then provision Java separately.
//
// We also try to drop a desktop shortcut automatically when the install looks like a
// human running `npm i -g harnex` on their own workstation. The auto-install is
// guarded so it never trips up CI / Docker / unattended provisioning:
//   - skipped unless this is a GLOBAL install (npm sets npm_config_global=true)
//   - skipped on CI (CI / GITHUB_ACTIONS / GITLAB_CI / JENKINS_HOME / BUILDKITE)
//   - skipped when running as root on Linux (heuristic: probably a system / container install)
//   - skipped when HARNEX_NO_SHORTCUT=1 (explicit opt-out)
//   - skipped on Linux without DISPLAY/WAYLAND_DISPLAY (headless server)
//   - failures are swallowed and reported as a hint, never as a non-zero exit.

import { spawnSync } from "node:child_process";
import { existsSync, readdirSync } from "node:fs";
import { join } from "node:path";
import { fileURLToPath } from "node:url";
import { dirname, resolve } from "node:path";
import { platform } from "node:os";
import { DEFAULT_WEB_PORT } from './lib/ports.mjs';

const __dirname = dirname(fileURLToPath(import.meta.url));
const ROOT = resolve(__dirname, "..");

const dim   = (s) => `\u001b[2m${s}\u001b[0m`;
const cyan  = (s) => `\u001b[36m${s}\u001b[0m`;
const yel   = (s) => `\u001b[33m${s}\u001b[0m`;
const grn   = (s) => `\u001b[32m${s}\u001b[0m`;
const bold  = (s) => `\u001b[1m${s}\u001b[0m`;

const javaCheck = spawnSync("java", ["-version"], { stdio: "pipe" });
const hasJava = javaCheck.status === 0;
const javaVersion = (javaCheck.stderr || "").toString().split("\n")[0];

const tessCheck = spawnSync("tesseract", ["--version"], { stdio: "pipe" });
const hasTess = tessCheck.status === 0;
const tessVersion = ((tessCheck.stdout || tessCheck.stderr || "").toString().split("\n")[0] || "").trim();

const jarDir = join(ROOT, "backend", "target");
const hasJar = existsSync(jarDir) && readdirSync(jarDir).some(n => n.startsWith("harnex-") && n.endsWith(".jar"));

console.log("");
console.log(bold(cyan("  Harnex")) + dim(" installed."));
console.log("");
if (hasJava) console.log("  " + grn("✓") + "  java       " + dim(javaVersion));
else         console.log("  " + yel("!") + "  java       " + dim("not found on PATH — install JDK 17+ to run"));

if (hasJar)  console.log("  " + grn("✓") + "  jar        " + dim("backend/target jar present"));
else         console.log("  " + yel("!") + "  jar        " + dim("not built — run: ") + cyan("cd backend && mvn clean -DskipTests package"));

if (hasTess) console.log("  " + grn("✓") + "  tesseract  " + dim(tessVersion + "  (image-upload OCR fallback ready)"));
else         console.log("  " + yel("!") + "  tesseract  " + dim("optional — only needed for image OCR fallback (HARNEX_OCR_FALLBACK=true). Install:") +
                              "\n               " + dim("  macOS:  ") + cyan("brew install tesseract tesseract-lang") +
                              "\n               " + dim("  Ubuntu: ") + cyan("sudo apt install -y tesseract-ocr tesseract-ocr-chi-sim") +
                              "\n               " + dim("  Win:    ") + cyan("choco install tesseract") + dim("  (or set HARNEX_OCR_FALLBACK=false)"));

console.log("");
console.log("  " + bold("Get started"));
console.log("    " + cyan("harnex")          + dim("           # interactive launcher (pick Web UI or Terminal)"));
console.log("    " + cyan("harnex shortcut") + dim("  # install desktop / start-menu icon"));
console.log("    " + cyan("harnex serve")    + dim(`     # web UI + API on :${DEFAULT_WEB_PORT}`));
console.log("    " + cyan("harnex tui")      + dim("       # native terminal UI"));
console.log("    " + cyan("harnex cli -p \"...\"") + dim(" # headless execution"));
console.log("    " + cyan("harnex stop")     + dim("      # stop the server"));
console.log("    " + cyan("harnex --help")   + dim("    # all commands"));
console.log("");

// ─── auto-install desktop shortcut (best-effort, skippable) ───────────────────
await maybeAutoInstallShortcut();

async function maybeAutoInstallShortcut() {
  // Decide whether to attempt the shortcut.
  const skipReason = shouldSkipShortcut();
  if (skipReason) {
    console.log("  " + dim(`(skipping desktop shortcut: ${skipReason} — run `) +
                cyan("harnex shortcut") + dim(" to create it manually)"));
    console.log("");
    return;
  }

  try {
    // Lazy import so a broken launcher.mjs can't ever fail `npm install`.
    const { cmdShortcut } = await import("./lib/launcher.mjs");
    console.log("  " + dim("Creating desktop shortcut…  ") + dim("(set HARNEX_NO_SHORTCUT=1 to skip next time)"));
    cmdShortcut([]);
  } catch (e) {
    // Don't fail the install — just hint at the manual command.
    console.log("  " + yel("!") + "  " + dim("desktop shortcut auto-install skipped: " + (e?.message || e)));
    console.log("  " + dim("   run ") + cyan("harnex shortcut") + dim(" later to create it"));
    console.log("");
  }
}

function shouldSkipShortcut() {
  if (process.env.HARNEX_NO_SHORTCUT === "1" || process.env.HARNEX_NO_SHORTCUT === "true") {
    return "HARNEX_NO_SHORTCUT set";
  }
  // npm exposes `npm_config_global=true` only for `-g` installs. Local installs (project deps)
  // should never drop a desktop icon.
  if (process.env.npm_config_global !== "true") {
    return "not a global install";
  }
  if (process.env.CI || process.env.GITHUB_ACTIONS || process.env.GITLAB_CI ||
      process.env.JENKINS_HOME || process.env.BUILDKITE) {
    return "CI environment";
  }
  // Heuristic: in containers / system images you often npm-install as root. Don't write into /root/Desktop.
  if (platform() !== "win32" && typeof process.getuid === "function" && process.getuid() === 0) {
    return "running as root";
  }
  // Headless Linux (server / SSH session) — no desktop to put an icon on.
  if (platform() === "linux" && !process.env.DISPLAY && !process.env.WAYLAND_DISPLAY) {
    return "headless Linux (no DISPLAY)";
  }
  return null;
}
