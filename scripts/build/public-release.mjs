#!/usr/bin/env node
import { spawnSync } from 'node:child_process';
import { copyFileSync, existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { join, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { TARGETS, verifyRelease } from './release-assets.mjs';

export const PUBLIC_DESKTOP_FORMATS = { win32: '.exe', darwin: '.dmg', linux: '.AppImage' };

export function publicDownloads(manifests) {
  return TARGETS.map(target => {
    const matches=manifests.filter(m=>m.target===target);
    if(matches.length!==1)throw Error(`Expected one manifest for ${target}`);
    const manifest=matches[0];
    const desktop=manifest.files.filter(f=>f.kind==='desktop'&&f.name.endsWith(PUBLIC_DESKTOP_FORMATS[target.split('-')[0]]));
    const offline=manifest.files.filter(f=>f.kind==='offline');
    if(desktop.length!==1||offline.length!==1)throw Error(`Missing primary installer or offline kit: ${target}`);
    return {target,signing:manifest.signing,desktop:desktop[0],offline:offline[0]};
  });
}

export function preparePublicRelease(directory, version, commit, output) {
  directory=resolve(directory);output=resolve(output);
  if(existsSync(output))throw Error(`Public release output already exists: ${output}`);
  const inside=relative(directory,output);
  if(!inside||(!inside.startsWith('..')&&!/^[A-Za-z]:/.test(inside)))throw Error('Public output must be outside the full build inventory');
  const verified=verifyRelease(directory,version,commit);
  const downloads=publicDownloads(TARGETS.map(target=>JSON.parse(readFileSync(join(directory,`manifest-${target}.json`)))));
  const files=downloads.flatMap(item=>[item.desktop,item.offline]);
  if(new Set(files.map(f=>f.name)).size!==10)throw Error('Public download names must be unique');
  mkdirSync(output,{recursive:true});
  for(const file of files)copyFileSync(join(directory,file.name),join(output,file.name));
  writeFileSync(join(output,'SHA256SUMS'),files.map(f=>`${f.sha256}  ${f.name}`).sort().join('\n')+'\n');
  return {targets:verified.targets,assets:11,version,commit,downloads};
}

export function publicReleaseNotes(version, repository, downloads) {
  if(!/^\d+\.\d+\.\d+(?:-[A-Za-z0-9.-]+)?$/.test(version)||!/^[-\w.]+\/[-\w.]+$/.test(repository))throw Error('Invalid release identity');
  const labels={'win32-x64':['Windows','x64'],'darwin-arm64':['macOS','Apple Silicon（M 系列）'],
    'darwin-x64':['macOS','Intel'],'linux-x64':['Linux','x64'],'linux-arm64':['Linux','ARM64']};
  const link=file=>`[${file.name}](https://github.com/${repository}/releases/download/v${version}/${file.name})（${(file.bytes/1024/1024).toFixed(1)} MiB）`;
  const rows=downloads.map(item=>`| ${labels[item.target][0]} | ${labels[item.target][1]} | ${link(item.desktop)} | ${link(item.offline)} |`).join('\n');
  const windows=downloads.find(d=>d.target==='win32-x64');
  const mac=downloads.filter(d=>d.target.startsWith('darwin-'));
  const signing=[];
  if(windows?.signing==='unsigned')signing.push('Windows 安装器未进行发布者代码签名，首次运行可能出现系统提示。');
  if(mac.some(d=>d.signing==='adhoc'))signing.push('macOS 包使用 ad-hoc 签名，未进行 Apple Developer ID 签名或公证。');
  return `# Harnex ${version}

桌面使用选择安装包；需要 CLI/TUI、VS Code 或完整便携运行环境时选择同平台的离线包。离线包已经包含桌面安装程序，无需重复下载。

## 下载选择

| 系统 | 架构 | 桌面安装包 | 完整离线包 |
| --- | --- | --- | --- |
${rows}

Linux 单独提供 AppImage；DEB 和 RPM 安装包位于对应离线包的 \`desktop/\` 目录。GitHub 自动生成的 **Source code (zip / tar.gz)** 是源码，需要自行构建。

## 离线归档内容

Windows 使用 ZIP，macOS/Linux 使用 TAR.GZ。解压后进入 \`${version}-<平台>/\`：

\`\`\`text
desktop/            本平台桌面安装程序；Linux 包含 DEB、RPM、AppImage
portable/runtime/   便携 Runtime，包含 Java、后端、原生 CLI/TUI 和 ripgrep
vscode/             Runtime 与 UI 两份 VSIX
install-vscode.ps1  Windows 扩展安装脚本
install-vscode.sh   macOS/Linux 扩展安装脚本
README.md           包内使用说明
SHA256SUMS          归档内逐文件校验清单
\`\`\`

每个平台只包含对应的扩展安装脚本。保留 \`portable/runtime/\` 的完整目录，不要只复制其中一个可执行文件。

## 使用方法

- **桌面**：直接安装下载的 EXE/DMG，或使用离线包 \`desktop/\` 中的安装程序。Linux AppImage 需要执行权限；DEB/RPM 使用系统包管理器安装。
- **VS Code**：在“Extensions: Install from VSIX”中先安装 \`vscode/\` 里的 Runtime，再安装 UI，随后重新加载窗口；也可使用包内安装脚本。UI 与 Runtime 各自使用独立版本号。
- **CLI/TUI**：在解压目录运行下面的命令，并把项目路径替换成自己的目录：

Windows PowerShell：

\`\`\`powershell
.\\portable\\runtime\\bin\\harnex.exe tui --workspace "C:\\path\\to\\project"
\`\`\`

macOS / Linux：

\`\`\`bash
./portable/runtime/bin/harnex tui --workspace "/path/to/project"
\`\`\`

首次启动后，在“设置 → 模型”中配置自己的服务地址、模型 ID 和凭据。离线包包含程序依赖，不包含模型服务或 API 调用额度；项目的编译器、Git 等工具仍由项目环境提供。

## 系统要求与校验

- Windows 10 1903+/11 x64，需要 WebView2；安装目录避免 emoji 等补充平面字符。
- macOS 13+，按 Apple Silicon 或 Intel 选择对应包。
- Linux 需要兼容 glibc、WebKitGTK 4.1 等系统组件，构建基线为 Ubuntu 22.04。
- ${signing.join(' ') || '签名与系统信任状态以下载文件的系统验证结果为准。'}

本页 \`SHA256SUMS\` 覆盖上表的 10 个下载文件。Windows 使用 \`Get-FileHash -Algorithm SHA256 文件名\`，Linux 使用 \`sha256sum 文件名\`，macOS 使用 \`shasum -a 256 文件名\`，与清单中对应的一行比较。离线包内的同名清单用于校验解压后的文件；完整下载或解压后可使用 \`sha256sum -c SHA256SUMS\`（macOS 为 \`shasum -a 256 -c SHA256SUMS\`）。
`;
}

if(process.argv[1]&&resolve(process.argv[1])===fileURLToPath(import.meta.url)) {
  try {
    const args=process.argv.slice(2),value=key=>{const i=args.indexOf(key);return i<0?undefined:args[i+1];};
    const directory=value('--from'),output=value('--output');
    if(!directory||!output)throw Error('Usage: node scripts/build/public-release.mjs --from FULL_INVENTORY --output NEW_PUBLIC_DIRECTORY [--notes FILE]');
    const root=resolve(fileURLToPath(new URL('../..',import.meta.url)));
    const version=JSON.parse(readFileSync(join(root,'package.json'))).version;
    const git=spawnSync('git',['rev-parse','HEAD'],{cwd:root,encoding:'utf8',windowsHide:true});
    const commit=process.env.GITHUB_SHA||git.stdout?.trim();
    if(!/^[a-f0-9]{40}$/.test(commit||''))throw Error('Cannot resolve source commit');
    const result=preparePublicRelease(directory,version,commit,output);
    if(value('--notes'))writeFileSync(value('--notes'),publicReleaseNotes(version,process.env.GITHUB_REPOSITORY||'debug2048/Harnex',result.downloads));
    console.log(JSON.stringify(result,null,2));
  } catch(error) { console.error(error.message);process.exitCode=1; }
}
