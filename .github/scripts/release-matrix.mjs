import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { appendFileSync, readFileSync } from 'node:fs';
import { resolve } from 'node:path';

const tag = process.env.RELEASE_TAG;
const selection = process.env.RELEASE_PLATFORMS;
assert.match(tag || '', /^v\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?$/);
const source = resolve('source');
const version = JSON.parse(readFileSync(resolve(source, 'package.json'), 'utf8')).version;
assert.equal(tag, `v${version}`, 'The release tag must match the version in the frozen source.');
const probe = spawnSync('git', ['rev-parse', 'HEAD'], { cwd: source, encoding: 'utf8' });
assert.equal(probe.status, 0, probe.stderr);
const commit = probe.stdout.trim();
assert.match(commit, /^[0-9a-f]{40}$/);
const targets = [
  { target: 'win32-x64', runner: 'windows-2022', group: 'windows' },
  { target: 'darwin-arm64', runner: 'macos-15', group: 'macos' },
  { target: 'darwin-x64', runner: 'macos-15-intel', group: 'macos' },
  { target: 'linux-x64', runner: 'ubuntu-22.04', group: 'linux' },
  { target: 'linux-arm64', runner: 'ubuntu-22.04-arm', group: 'linux' },
];
assert.ok(['all', 'unix', 'windows', 'macos', 'linux'].includes(selection), 'Unknown platform selection.');
const include = targets.filter(item => selection === 'all'
  || (selection === 'unix' ? item.group !== 'windows' : item.group === selection))
  .map(({ target, runner }) => ({ target, runner }));
assert.ok(include.length);
const matrix = JSON.stringify({ include });
assert.ok(process.env.GITHUB_OUTPUT, 'GitHub Actions output path is required.');
appendFileSync(process.env.GITHUB_OUTPUT, `version=${version}\ncommit=${commit}\nmatrix=${matrix}\n`);
console.log(JSON.stringify({ tag, version, commit, targets: include }, null, 2));
