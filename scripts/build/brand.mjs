#!/usr/bin/env node
// All production marks derive from one monochrome vector. No font/icon service at runtime.
import { copyFileSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
const brand = 'docs/assets/brand';
const ink = '#222321';
const paper = '#f5f4ef';
const source = readFileSync(resolve(root, brand, 'symbol.svg'), 'utf8');
const geometry = source.match(/<g\b[\s\S]*<\/g>/)?.[0];
if (!geometry) throw new Error('symbol.svg must contain the master geometry in a <g> element');
const shape = (color = ink) => geometry.replaceAll(ink, color);
const svg = (body, width = 256, height = width, viewBox = `0 0 ${width} ${height}`) =>
  `<svg xmlns="http://www.w3.org/2000/svg" width="${width}" height="${height}" viewBox="${viewBox}" fill="none" role="img" aria-label="Harnex">\n${body}\n</svg>\n`;
function save(path, contents) {
  const target = resolve(root, path);
  mkdirSync(dirname(target), { recursive: true });
  writeFileSync(target, contents);
}
function copy(from, to) {
  const target = resolve(root, to);
  mkdirSync(dirname(target), { recursive: true });
  copyFileSync(resolve(root, from), target);
}

const tile = () => `<rect width="256" height="256" rx="56" fill="${ink}"/>\n<g transform="translate(8 8) scale(.9375)">${shape(paper)}</g>`;
const app = svg(tile());
save(`${brand}/app-icon.svg`, app);
save('bin/assets/harnex.svg', app);
save('frontend/src/assets/harnex-icon.svg', app);
// The UI uses the SVG alpha as a mask and inherits the selected theme's foreground.
save('frontend/src/assets/harnex-symbol.svg', svg(shape(), 256, 256, '16 16 224 224'));
save('extension/media/icon.svg', svg(shape('currentColor'), 24, 24, '16 16 224 224'));

// Browser tabs have their own theme, independent of the application's selected theme.
// SVG gets a high-contrast transparent silhouette; PNG/ICO get a neutral backing.
save('frontend/public/favicon.svg', svg(
  `<style>:root{color:${ink}}@media(prefers-color-scheme:dark){:root{color:${paper}}}</style>\n${shape('currentColor')}`,
  32, 32, '16 16 224 224'));
const faviconRaster = svg(`<rect width="256" height="256" rx="56" fill="${ink}"/>\n${shape(paper)}`);
save(`${brand}/favicon-tile.svg`, faviconRaster);

for (const [theme, color] of [['light', ink], ['dark', paper]]) {
  save(`${brand}/logo-${theme}.svg`, svg(
    `<g transform="translate(0 4) scale(.4)">${shape(color)}</g>\n`
    + `<text x="120" y="80" fill="${color}" font-family="Inter, Segoe UI, Helvetica, Arial, sans-serif" font-size="72" font-weight="600" letter-spacing="-3">Harnex</text>`,
    380, 112));
}

const mini = (x, y, size, color = ink) => `<svg x="${x}" y="${y}" width="${size}" height="${size}" viewBox="16 16 224 224">${shape(color)}</svg>`;
const font = 'Inter, Segoe UI, Helvetica, Arial, sans-serif';
const text = (x, y, value, size = 14, color = '#73746f', extra = '') =>
  `<text x="${x}" y="${y}" fill="${color}" font-family="${font}" font-size="${size}" ${extra}>${value}</text>`;
let preview = `<rect width="1280" height="960" fill="${paper}"/>
${text(64, 59, 'HARNEX  /  RELAY', 13, '#73746f', 'letter-spacing="3"')}
${text(64, 104, 'One mark. Every workspace.', 29, ink, 'font-weight="500" letter-spacing="-.7"')}
<rect x="64" y="146" width="1152" height="338" rx="28" fill="#eae9e3"/>
<g transform="translate(119 193) scale(.95)">${shape()}</g>
${text(416, 327, 'Harnex', 96, ink, 'font-weight="600" letter-spacing="-5"')}
${text(422, 373, 'THINK  /  CONNECT  /  ACT', 13, '#73746f', 'letter-spacing="3"')}
<rect x="958" y="212" width="208" height="208" rx="45" fill="${ink}"/>
<g transform="translate(976 230) scale(.67)">${shape(paper)}</g>
${text(64, 534, 'A connected loop, built for intelligence in action.', 17, ink)}
${text(64, 563, 'A single silhouette. Quiet color. Clear at 16 pixels.', 14)}
<path d="M64 601H1216" stroke="#d5d6ce"/>
${text(64, 642, 'ACTUAL SIZES', 11, '#73746f', 'letter-spacing="2"')}
${text(652, 642, 'BROWSER TABS', 11, '#73746f', 'letter-spacing="2"')}`;
for (const [i, size] of [64, 48, 32, 24, 16].entries()) {
  const x = 64 + i * 105;
  preview += mini(x, 665 + (64 - size) / 2, size);
  preview += text(x, 756, String(size), 12);
}
for (const [i, [background, foreground]] of [[paper, ink], [ink, paper]].entries()) {
  const y = 670 + i * 52;
  preview += `<rect x="652" y="${y}" width="263" height="40" rx="11" fill="${background}" stroke="#d5d6ce"/>`;
  preview += mini(666, y + 12, 16, foreground) + text(694, y + 25, 'Harnex', 12, foreground);
}
preview += `${text(960, 642, 'APP ICON', 11, '#73746f', 'letter-spacing="2"')}
<g transform="translate(964 665) scale(.38)">${tile()}</g>
${text(64, 820, 'ADAPTS TO YOUR THEME', 11, '#73746f', 'letter-spacing="2"')}`;
const themes = [['#f3f6fa', '#152944'], ['#232731', '#eceff4'], ['#faf3f7', '#493d49'], ['#f7f2e9', '#453b30'], ['#fff3f0', '#4b3633'], ['#ffffff', '#393936']];
for (const [i, [background, foreground]] of themes.entries()) {
  const x = 64 + i * 194;
  preview += `<rect x="${x}" y="842" width="180" height="64" rx="12" fill="${background}"/>`;
  preview += mini(x + 16, 858, 32, foreground) + text(x + 60, 881, 'Harnex', 16, foreground, 'font-weight="600"');
}
save(`${brand}/preview.svg`, svg(preview, 1280, 960));

if (!process.argv.includes('--svg-only')) {
  function tauriIcon(input, output, sizes = []) {
    const args = ['exec', '--yes', '--package', '@tauri-apps/cli@2.11.4', '--', 'tauri', 'icon', input, '--output', output];
    for (const size of sizes) args.push('--png', String(size));
    const npmCli = process.env.npm_execpath;
    const command = npmCli ? process.execPath : process.platform === 'win32' ? 'cmd.exe' : 'npm';
    const commandArgs = npmCli ? [npmCli, ...args] : process.platform === 'win32'
      ? ['/d', '/s', '/c', `npm ${args.join(' ')}`] : args;
    const result = spawnSync(command, commandArgs, { cwd: root, stdio: 'inherit', windowsHide: true });
    if (result.status !== 0) throw new Error(`Tauri icon conversion failed: ${input}`);
  }
  tauriIcon(`${brand}/app-icon.svg`, '.artifacts/brand/app', [1024, 256, 180]);
  copy('.artifacts/brand/app/1024x1024.png', 'src-tauri/icons/source.png');
  copy('.artifacts/brand/app/256x256.png', 'extension/media/logo.png');
  copy('.artifacts/brand/app/256x256.png', 'extension-runtime/media/logo.png');
  copy('.artifacts/brand/app/180x180.png', 'frontend/public/apple-touch-icon.png');
  tauriIcon(`${brand}/app-icon.svg`, 'src-tauri/icons');
  copy('src-tauri/icons/icon.ico', 'bin/assets/harnex.ico');
  tauriIcon(`${brand}/favicon-tile.svg`, '.artifacts/brand/favicon', [16, 32, 48]);
  copy('.artifacts/brand/favicon/32x32.png', 'frontend/public/favicon-32x32.png');
  const sizes = [16, 32, 48];
  const images = sizes.map(size => readFileSync(resolve(root, `.artifacts/brand/favicon/${size}x${size}.png`)));
  const header = Buffer.alloc(6 + sizes.length * 16);
  header.writeUInt16LE(1, 2);
  header.writeUInt16LE(sizes.length, 4);
  let offset = header.length;
  sizes.forEach((size, index) => {
    const entry = 6 + index * 16;
    header[entry] = size;
    header[entry + 1] = size;
    header.writeUInt16LE(1, entry + 4);
    header.writeUInt16LE(32, entry + 6);
    header.writeUInt32LE(images[index].length, entry + 8);
    header.writeUInt32LE(offset, entry + 12);
    offset += images[index].length;
  });
  save('frontend/public/favicon.ico', Buffer.concat([header, ...images]));
}

// Version every favicon format by its bytes so existing browser tabs receive the new mark.
for (const entry of ['frontend/index.html', 'frontend/ide.html']) {
  const html = readFileSync(resolve(root, entry), 'utf8');
  const versioned = html.replace(
    /href="\/(favicon\.svg|favicon\.ico|favicon-32x32\.png|apple-touch-icon\.png)(?:\?[^"]*)?"/g,
    (_, file) => {
      const revision = createHash('sha256').update(readFileSync(resolve(root, 'frontend/public', file))).digest('hex').slice(0, 12);
      return `href="/${file}?v=${revision}"`;
    },
  ).replace(/(<meta name="theme-color" content=")[^"]*("\s*\/?>)/, `$1${ink}$2`);
  if (versioned !== html) save(entry, versioned);
}
console.log('Harnex Relay assets and favicon cache versions regenerated.');
