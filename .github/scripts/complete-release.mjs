import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { spawnSync } from 'node:child_process';
import { appendFileSync, copyFileSync, existsSync, mkdirSync, readFileSync, readdirSync, statSync, writeFileSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { preparePublicRelease, publicReleaseNotes } from '../../scripts/build/public-release.mjs';
import { TARGETS } from '../../scripts/build/release-assets.mjs';

const repository = process.env.GITHUB_REPOSITORY;
const tag = process.env.RELEASE_TAG;
const buildRunIds = process.env.BUILD_RUN_IDS;
assert.match(repository || '', /^[A-Za-z0-9_.-]+\/[A-Za-z0-9_.-]+$/);
assert.match(tag || '', /^v\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?$/);
assert.match(buildRunIds || '', /^\d+(?:,\d+)*$/);
const source = resolve('source');
const version = JSON.parse(readFileSync(join(source, 'package.json'), 'utf8')).version;
assert.equal(tag, `v${version}`);
const work = resolve('release-work');
const existing = join(work, 'existing');
const downloads = join(work, 'builds');
const combined = join(work, 'combined');
const publishDirectory = join(work, 'public');
for (const directory of [existing, downloads, combined]) mkdirSync(directory, { recursive: true });

function execute(command, args, cwd = process.cwd()) {
  const result = spawnSync(command, args, { cwd, encoding: 'utf8', timeout: 1800000, maxBuffer: 32 * 1024 * 1024 });
  assert.equal(result.status, 0, `${command} ${args[0]} failed: ${result.error || result.stderr || result.stdout}`);
  return result.stdout.trim();
}
const gh = args => execute('gh', args);
const api = path => JSON.parse(gh(['api', path]));
const sha = path => createHash('sha256').update(readFileSync(path)).digest('hex');
const commit = execute('git', ['rev-parse', 'HEAD'], source);
assert.match(commit, /^[0-9a-f]{40}$/);
assert.equal(api(`repos/${repository}/commits/${tag}`).sha, commit);


const builds = [...new Set(buildRunIds.split(','))].map(id => {
  const run = api(`repos/${repository}/actions/runs/${id}`);
  assert.equal(run.status, 'completed');
  assert.ok(['success', 'failure'].includes(run.conclusion));
  assert.equal(run.event, 'workflow_dispatch');
  assert.equal(run.head_repository.full_name, repository);
  assert.equal(run.path, '.github/workflows/native-release.yml');
  return { id, run, jobs: api(`repos/${repository}/actions/runs/${id}/jobs?per_page=100`).jobs,
    artifacts: api(`repos/${repository}/actions/runs/${id}/artifacts?per_page=100`).artifacts };
});
let release = api(`repos/${repository}/releases/tags/${tag}`);
assert.equal(release.immutable, false, 'Immutable releases cannot accept additional platform assets.');
assert.equal(release.target_commitish, commit);
gh(['release', 'download', tag, '--repo', repository, '--dir', existing]);
const selected = [];
const inventory = new Set();
const requiredSteps = ['Stage versioned release assets', 'Upload verified platform assets'];

for (const target of TARGETS) {
  const manifestName = `manifest-${target}.json`;
  let directory = existing;
  let origin = 'existing-release';
  let verificationUrl;
  if (!existsSync(join(existing, manifestName))) {
    const artifactName = `release-${version}-${target}`;
    const build = [...builds].reverse().find(item =>
      item.jobs.some(job => job.name === `Build and verify ${target}` && job.conclusion === 'success')
      && item.artifacts.some(artifact => artifact.name === artifactName && !artifact.expired));
    assert.ok(build, `Missing verified workflow artifact: ${artifactName}`);
    const job = build.jobs.find(item => item.name === `Build and verify ${target}`);
    assert.equal(job?.conclusion, 'success', `Platform job did not pass: ${target}`);
    assert.ok(['Verify Runtime bundle integrity', 'Verify installed Runtime and data retention']
      .some(name => job.steps.some(step => step.name === name && step.conclusion === 'success')),
      `${target}: Runtime verification must pass`);
    const steps = [...requiredSteps,
      ...(target.startsWith('linux-') ? ['Verify embedded AppImage Runtime'] : [])];
    for (const name of steps) assert.equal(job.steps.find(step => step.name === name)?.conclusion, 'success', `${target}: ${name}`);
    directory = join(downloads, target);
    mkdirSync(directory, { recursive: true });
    console.log(`Downloading verified build artifact: ${artifactName}`);
    gh(['run', 'download', build.id, '--repo', repository, '--name', artifactName, '--dir', directory]);
    origin = 'verified-build';
    verificationUrl = job.html_url;
  }
  const manifest = JSON.parse(readFileSync(join(directory, manifestName), 'utf8'));
  assert.equal(manifest.target, target);
  assert.equal(manifest.version, version);
  assert.equal(manifest.commit, commit);
  const names = [...manifest.files.map(file => file.name), manifestName];
  if (origin === 'verified-build') assert.deepEqual(readdirSync(directory).sort(), [...names].sort());
  for (const name of names) {
    assert.match(name, /^[A-Za-z0-9][A-Za-z0-9._-]*$/);
    assert.ok(!inventory.has(name), `Duplicate release asset: ${name}`);
    inventory.add(name);
    const path = join(directory, name);
    const file = manifest.files.find(item => item.name === name);
    if (file) {
      assert.equal(statSync(path).size, file.bytes, name);
      assert.equal(sha(path), file.sha256, name);
    }
    copyFileSync(path, join(combined, name));
  }
  selected.push({ target, origin, signing: manifest.signing, verificationUrl });
}
const publication = preparePublicRelease(combined, version, commit, publishDirectory);
const verification = { targets: publication.targets, assets: publication.assets - 1, version, commit };
const publicInventory = new Set(publication.downloads.flatMap(item => [item.desktop.name, item.offline.name]));
assert.equal(verification.targets, 5);
writeFileSync(join(work, 'verification.json'), JSON.stringify({ status: 'verified-before-upload', ...verification, selected,
  buildRuns: builds.map(build => build.run.html_url) }, null, 2) + '\n');
console.log(JSON.stringify({ ...verification, selected }, null, 2));

for (const remote of release.assets) {
  assert.ok(inventory.has(remote.name) || remote.name === 'SHA256SUMS', `Unexpected existing release asset: ${remote.name}`);
  if (remote.name !== 'SHA256SUMS') {
    assert.equal(remote.digest, `sha256:${sha(join(combined, remote.name))}`, `Published asset differs: ${remote.name}`);
  }
}
for (const name of [...publicInventory].sort()) {
  if (release.assets.some(asset => asset.name === name)) continue;
  console.log(`Uploading new platform asset: ${name}`);
  gh(['release', 'upload', tag, join(publishDirectory, name), '--repo', repository]);
}
release = api(`repos/${repository}/releases/${release.id}`);
for (const name of publicInventory) {
  const remote = release.assets.find(asset => asset.name === name);
  assert.ok(remote, name);
  assert.equal(remote.state, 'uploaded', name);
  assert.equal(remote.size, statSync(join(combined, name)).size, name);
  assert.equal(remote.digest, `sha256:${sha(join(combined, name))}`, name);
}
gh(['release', 'upload', tag, join(publishDirectory, 'SHA256SUMS'), '--repo', repository, '--clobber']);
for (const remote of release.assets) {
  if (remote.name !== 'SHA256SUMS' && !publicInventory.has(remote.name)) {
    gh(['api', '--method', 'DELETE', `repos/${repository}/releases/assets/${remote.id}`]);
  }
}
const notes = publicReleaseNotes(version, repository, publication.downloads);
const notesFile = join(work, 'release-notes.md');
writeFileSync(notesFile, notes);
gh(['release', 'edit', tag, '--repo', repository, '--title', `Harnex ${version}`, '--notes-file', notesFile]);
release = api(`repos/${repository}/releases/${release.id}`);
assert.equal(release.name, `Harnex ${version}`);
assert.equal(release.body, notes);
assert.equal(release.assets.length, publicInventory.size + 1);
const sums = release.assets.find(asset => asset.name === 'SHA256SUMS');
assert.equal(sums.digest, `sha256:${sha(join(publishDirectory, 'SHA256SUMS'))}`);
const result = { status: 'complete-release-verified', ...verification, assetCount: release.assets.length,
  url: release.html_url, selected, buildRuns: builds.map(build => build.run.html_url) };
writeFileSync(join(work, 'verification.json'), JSON.stringify(result, null, 2) + '\n');
if (process.env.GITHUB_STEP_SUMMARY) appendFileSync(process.env.GITHUB_STEP_SUMMARY,
  `Completed [${tag}](${release.html_url}): ${verification.targets} platforms, ${release.assets.length} verified assets, source \`${commit}\`.\n`);
console.log(JSON.stringify(result, null, 2));
