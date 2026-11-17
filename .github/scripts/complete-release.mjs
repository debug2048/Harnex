import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { spawnSync } from 'node:child_process';
import { appendFileSync, copyFileSync, existsSync, mkdirSync, readFileSync, readdirSync, statSync, writeFileSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { pathToFileURL } from 'node:url';

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
const { TARGETS, verifyRelease } = await import(pathToFileURL(join(source, 'scripts/build/release-assets.mjs')).href);

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
const verification = verifyRelease(combined, version, commit);
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
for (const name of [...inventory].sort()) {
  if (release.assets.some(asset => asset.name === name)) continue;
  console.log(`Uploading new platform asset: ${name}`);
  gh(['release', 'upload', tag, join(combined, name), '--repo', repository]);
}
release = api(`repos/${repository}/releases/${release.id}`);
for (const name of inventory) {
  const remote = release.assets.find(asset => asset.name === name);
  assert.ok(remote, name);
  assert.equal(remote.state, 'uploaded', name);
  assert.equal(remote.size, statSync(join(combined, name)).size, name);
  assert.equal(remote.digest, `sha256:${sha(join(combined, name))}`, name);
}
gh(['release', 'upload', tag, join(combined, 'SHA256SUMS'), '--repo', repository, '--clobber']);
const builtTargets = selected.filter(item => item.origin === 'verified-build').map(item => item.target);
const notes = `# Harnex ${version}

Harnex 提供 Web、桌面工作台、CLI / TUI 和 VS Code 多端入口。本次 Release 的五个平台安装包已齐全。

## 下载选择

| 平台 | 架构 | 桌面安装包 | 完整离线包 |
| --- | --- | --- | --- |
| Windows | x64 | \`Harnex_${version}_win32-x64.exe\` | \`Harnex_${version}_win32-x64_offline.zip\` |
| macOS | Apple Silicon | \`Harnex_${version}_darwin-arm64.dmg\` | \`Harnex_${version}_darwin-arm64_offline.tar.gz\` |
| macOS | Intel | \`Harnex_${version}_darwin-x64.dmg\` | \`Harnex_${version}_darwin-x64_offline.tar.gz\` |
| Linux | x64 | \`Harnex_${version}_linux-x64.deb\`、\`.rpm\`、\`.AppImage\` | \`Harnex_${version}_linux-x64_offline.tar.gz\` |
| Linux | ARM64 | \`Harnex_${version}_linux-arm64.deb\`、\`.rpm\`、\`.AppImage\` | \`Harnex_${version}_linux-arm64_offline.tar.gz\` |

离线包包含桌面安装程序、便携 Runtime、内置 Java 与 ripgrep，以及对应平台的 Runtime / UI 两个 VSIX。VS Code 请先安装 Runtime 扩展，再安装 UI 扩展；UI 扩展独立使用自己的版本号。

附件还包含各平台原生 CLI npm 包、主 npm 包、独立后端 JAR 和 Web UI 压缩包。原生 CLI npm 平台包不包含完整 Runtime；需要自包含运行环境时，请选择离线包。使用独立后端 JAR 需要 Java 17+。模型调用需要配置自己的模型服务。

## 验证

- 五个平台的源码提交均为 \`${commit}\`，没有移动发布标签或替换已发布的程序包。
- 五平台附件格式、数量、文件大小和 SHA-256 已统一校验。\`SHA256SUMS\` 覆盖本页全部人工上传附件（校验文件自身除外）。
- 本次构建并通过 Runtime 完整性校验的目标：${builtTargets.join('、') || '沿用同源已发布产物'}。公仓不包含独立测试用例；行为验收结果须另外留证。
- 沿用的附件保持原有字节和校验记录；构建及哈希校验不代替桌面、TUI 或扩展的行为验收。
- VS Code Extension Host 端到端检查，以及 macOS/Linux 桌面交互安装验收，尚未完成。

## 环境与签名

- Windows 桌面面向 Windows 10 1903+/11 x64，需要 WebView2；安装程序未做 Authenticode 签名。
- macOS 最低版本为 13；本次使用 ad-hoc 签名，未做 Apple Developer ID 签名或公证。
- Linux 构建基线为 Ubuntu 22.04，桌面需要 WebKitGTK 4.1 等系统组件；优先使用发行版对应的 DEB/RPM 安装依赖。
- 本次未启用自动更新。

在 Git Bash 或 Unix 终端运行 \`sha256sum -c SHA256SUMS\`（macOS 可使用 \`shasum -a 256 -c SHA256SUMS\`）校验下载文件。

构建记录：${selected.filter(item => item.verificationUrl).map(item => `[${item.target}](${item.verificationUrl})`).join('、')}。使用说明：[中文用户手册](https://github.com/${repository}/blob/${tag}/docs/06_%E7%94%A8%E6%88%B7%E6%89%8B%E5%86%8C.md)、[English user guide](https://github.com/${repository}/blob/${tag}/docs/en/user-guide.md)。
`;
const notesFile = join(work, 'release-notes.md');
writeFileSync(notesFile, notes);
gh(['release', 'edit', tag, '--repo', repository, '--title', `Harnex ${version}`, '--notes-file', notesFile]);
release = api(`repos/${repository}/releases/${release.id}`);
assert.equal(release.name, `Harnex ${version}`);
assert.equal(release.body, notes);
assert.equal(release.assets.length, inventory.size + 1);
const sums = release.assets.find(asset => asset.name === 'SHA256SUMS');
assert.equal(sums.digest, `sha256:${sha(join(combined, 'SHA256SUMS'))}`);
const result = { status: 'complete-release-verified', ...verification, assetCount: release.assets.length,
  url: release.html_url, selected, buildRuns: builds.map(build => build.run.html_url) };
writeFileSync(join(work, 'verification.json'), JSON.stringify(result, null, 2) + '\n');
if (process.env.GITHUB_STEP_SUMMARY) appendFileSync(process.env.GITHUB_STEP_SUMMARY,
  `Completed [${tag}](${release.html_url}): ${verification.targets} platforms, ${release.assets.length} verified assets, source \`${commit}\`.\n`);
console.log(JSON.stringify(result, null, 2));
