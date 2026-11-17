import { spawnSync } from 'node:child_process';
import { copyFileSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';

// Java 17's Windows launcher decodes arguments through the process ANSI code page.
// Give our launcher copy a UTF-8 process manifest, before Runtime files are hashed.
// https://learn.microsoft.com/en-us/windows/apps/design/globalizing/use-utf8-code-page
export function prepareWindowsJava(jre) {
  if (process.platform !== 'win32') throw new Error('Windows Runtime launchers must be prepared on Windows');
  const executable = join(jre, 'bin/harnex-java.exe');
  copyFileSync(join(jre, 'bin/java.exe'), executable);
  const powershell = join(process.env.SystemRoot || 'C:\\Windows', 'System32/WindowsPowerShell/v1.0/powershell.exe');
  const patched = spawnSync(powershell, ['-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass', '-File',
    fileURLToPath(new URL('./windows-java-utf8.ps1', import.meta.url)), '-Java', executable],
  { encoding: 'utf8', windowsHide: true, timeout: 60_000 });
  if (patched.status !== 0) throw new Error(`Cannot prepare UTF-8 Java launcher: ${patched.error || patched.stderr || patched.stdout}`);
  const probe = spawnSync(executable, ['-XshowSettings:properties', '-version'], { encoding: 'utf8', windowsHide: true, timeout: 30_000 });
  if (probe.status !== 0 || !/sun\.jnu\.encoding\s*=\s*UTF-8/.test(probe.stderr || '')) {
    throw new Error(`The Windows Java launcher requires Windows 10 1903+ and a working JRE: ${probe.error || probe.stderr}`);
  }
  writeFileSync(join(jre, 'HARNEX-LAUNCHER.txt'),
    'Harnex adds bin/harnex-java.exe, a copy of the bundled Java launcher with its Windows process manifest set to UTF-8.\n'
    + 'Its original Authenticode signature is removed by the resource update; it is verified by the Harnex Runtime manifest.\n'
    + 'The vendor-provided bin/java.exe and all JRE libraries remain unchanged.\n');
  return 'jre/bin/harnex-java.exe';
}
