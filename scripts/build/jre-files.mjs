import { chmodSync, constants, copyFileSync, mkdirSync, readdirSync, realpathSync, statSync } from 'node:fs';
import { basename, dirname, isAbsolute, join, relative, resolve, sep } from 'node:path';

/** Materialize every JRE link while its target is available; the installed image owns its bytes. */
export function copyJreImage(source, destination) {
  const sourceRoot = realpathSync.native(source);
  const outputRoot = resolve(realpathSync.native(dirname(destination)), basename(destination));
  const withinSource = relative(sourceRoot, outputRoot);
  if (!isAbsolute(withinSource) && !withinSource.split(sep).includes('..')) {
    throw new Error('JRE destination must be outside the source image');
  }
  const ancestors = new Set();
  function copy(input, output) {
    const real = realpathSync.native(input);
    const info = statSync(real);
    if (info.isDirectory()) {
      if (ancestors.has(real)) throw new Error(`JRE contains a symbolic link cycle: ${input}`);
      ancestors.add(real);
      mkdirSync(output);
      try {
        for (const name of readdirSync(real)) copy(join(real, name), join(output, name));
      } finally { ancestors.delete(real); }
      chmodSync(output, info.mode & 0o777);
    } else if (info.isFile()) {
      // Avoid recursive fs.cpSync: Node 22.17+ can preserve links despite dereference:true.
      // https://github.com/nodejs/node/issues/59168
      copyFileSync(real, output, constants.COPYFILE_EXCL);
      chmodSync(output, info.mode & 0o777);
    } else {
      throw new Error(`JRE contains an unsupported filesystem entry: ${input}`);
    }
  }
  copy(sourceRoot, outputRoot);
}
