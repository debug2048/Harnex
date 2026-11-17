# 开发脚本

优先使用根目录 `package.json` 中的 npm 命令；脚本移动后这些命令保持不变。

| 目录 | 用途 | 常用命令 |
|---|---|---|
| `scripts/build/` | npm、Runtime Bundle、原生 CLI、桌面和离线包构建，以及发布元数据 | `npm run build:dist`、`npm run build:native-cli` |
| `scripts/build/brand.mjs` | 从主 SVG 同步 Logo、favicon、前端、VS Code 与桌面图标 | `npm run brand:generate` |
| `scripts/build/release-assets.mjs` | 整理五个平台的 Release 附件并验证完整性 | `npm run build:release-assets` |
| `scripts/dev/` | 开发服务器及平台快捷入口 | `npm run dev`、`npm run dev:supervisor`、`npm run dev:stop` |
| `scripts/deploy/` | Docker 服务部署和升级；随容器离线包交付 | `bash install.sh`、`bash upgrade.sh` |

`bin/harnex.mjs` 和 `bin/postinstall.mjs` 是 npm 安装后的入口；它们使用 `bin/lib/` 中的运行模块及
`bin/assets/` 中的资源。npm 发布仅包含这些运行文件，不包含本目录中的构建、开发和测试脚本。

交互终端统一使用 `harnex tui`，`harnex cli` 不带提示词时是它的别名。
脚本和 CI 使用 `harnex cli -p "任务"`；原生包和 npm 启动器都通过 Runtime API 提交任务。
Java `--cli` 已删除，旧参数会在启动 Runtime 前返回迁移提示及退出码 2。缺少原生 TUI 时明确报错。

Windows、Linux、macOS 优先使用上表中的相同 npm 命令；实际逻辑都在 `.mjs` 中。
需要直接运行快捷脚本时，使用对应入口（以下路径相对仓库根目录）：

| 操作 | Windows | Linux / macOS |
|---|---|---|
| 构建当前平台发行包 | `scripts\build\build-dist.cmd` | `sh scripts/build/build-dist.sh` |
| 启动开发服务 | `scripts\dev\start-dev.cmd` | `sh scripts/dev/start-dev.sh` |
| 启动 Supervisor | `scripts\dev\start-supervisor.cmd` | `sh scripts/dev/start-supervisor.sh` |
| 停止开发服务 | `scripts\dev\stop-dev.cmd` | `sh scripts/dev/stop-dev.sh` |

快捷脚本根据自身位置定位仓库，可从其他工作目录调用；参数和退出码交给同一套 Node 实现处理。
`npm run dev` 的 Vite 页面与 `npm run dev:supervisor` 的浏览器入口统一默认使用 `18081`，开发后端 API 使用 `8088`。前者可通过 `--frontend-port`，后者可通过 `--supervisor-port` 覆盖；并行运行时指定不同的 Web 端口。
Supervisor 启动脚本会先停止同一运行目录（`--runtime-domain-dir`，默认 `~/.harnex/runtime-domains/web-dev`）
中仍在运行的旧实例，再启动新实例；即使旧实例尚未监听端口或本次更换端口也会处理。
失效的进程记录会自动清理，PID 被系统复用时不会终止新进程。其他程序占用端口时仍需手动释放，
或显式传入 `--force-kill-ports`。子进程在就绪前退出时，脚本立即报错并返回非零退出码。
Windows 启动/构建脚本保留双击后的暂停；设置 `CI` 或 `HARNEX_NO_PAUSE=1` 可关闭暂停。
构建脚本也继续识别 `HARNEX_BUILD_NO_PAUSE`。POSIX 入口不暂停，使用 `exec` 转交信号。
构建目标是当前主机平台；macOS、Windows、Linux 的原生发行包分别在对应平台构建。

五个平台的发行包分别在相应原生环境构建，见[跨平台发布说明](../docs/04_部署方案.md#54-五个平台的-github-release)。Linux 离线包收集 DEB、RPM 与 AppImage，发布前会检查所有格式及源提交、文件校验和。

构建输出继续放在各自的 `target/`、`dist/`；临时验证日志统一放 `.artifacts/logs/`，避免堆积在项目根目录。
这些产物不纳入 Git，也不纳入 npm 发布包。

Docker 镜像离线交付使用 `scripts/build/build-docker-offline.sh`，输出到 `dist/docker-offline/`；
桌面/VSIX/原生 Runtime 离线包继续使用 `offline-kit.mjs`。两种交付流程独立，详见 [容器部署说明](deploy/README.md)。
独立测试用例和验收脚本保留在内部仓，不随公开源码导出。构建与发行完整性校验入口保持可用。
