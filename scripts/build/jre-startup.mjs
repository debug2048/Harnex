import { spawnSync } from 'node:child_process'
import { join } from 'node:path'

/** Build the JRE's relocatable base class archive before signing and hashing it. */
export function prepareJreStartup(jre, target, javaExecutable = process.platform === 'win32' ? 'bin/java.exe' : 'bin/java') {
  // A cross-target JRE must arrive with its own archive; it cannot run on this builder.
  if (target !== `${process.platform}-${process.arch}`) return false
  const java = join(jre, javaExecutable)
  const env = { ...process.env }
  for (const key of Object.keys(env)) {
    if (/^(JAVA_TOOL_OPTIONS|JDK_JAVA_OPTIONS|_JAVA_OPTIONS|CLASSPATH)$/i.test(key)) delete env[key]
  }
  for (const args of [['-Xshare:dump'], ['-Xshare:on', '-version']]) {
    const result = spawnSync(java, args, { env, encoding: 'utf8', windowsHide: true, timeout: 120_000 })
    if (result.status !== 0) {
      throw new Error(`Cannot prepare the bundled JRE class archive: ${result.error || result.stderr || result.stdout}`)
    }
  }
  return true
}
