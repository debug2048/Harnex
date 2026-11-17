# Harnex 桌面外壳（Tauri）

SSH 工作区与按需命令隔离见 [当前方案](../docs/06_用户手册.md#ssh-workspaces)。使用 `harnex tui --remote devbox --workspace /srv/project`；默认沿用远端工具链，不额外隔离普通命令。项目状态默认位于项目 `.harnex/data`，已登记的显式 dataDir 优先。

一个承载完整 Vue SPA 与本地 Workspace Runtime 的轻量原生宿主。SPA 来自安装包内可信资源，
Runtime 自动选择空闲 loopback 端口并只提供 API。分发与桌面方案见 [docs/04_部署方案.md](../docs/04_部署方案.md) 与 [docs/06_用户手册.md](../docs/06_用户手册.md)。

- **复用完整 Vue 前端**（由 Tauri 资源协议托管），切换 JVM 时页面和工作台状态不卸载；
- **内置 jlink 裁剪 JRE + jar**，用户机器**无需预装 JDK / Node**；
- Windows 使用系统 WebView2，macOS 使用系统 WKWebView，薄壳**不打包 Chromium**；
- 诊断浏览器页面与 Tauri SPA 可以作为同一桌面 Runtime Domain 的两个 Client。
- 任意时刻只监督一个 Workspace JVM；切换严格执行 `DRAIN → CLOSE → OPEN → READY`。
- Web 与 Tauri 使用同一个 Sidebar WorkspaceSwitcher；Rust 只提供目录选择、registry 和生命周期命令，
  托盘/壳菜单不再提供第二套工作区入口。

## 桌面交互

源码按职责组织：`main.rs` 装配插件、命令与生命周期回调；`workspaces.rs` 协调工作区切换，`runtime.rs` 管理本地和个人 Runtime，`state.rs` 保存共享运行状态。`catalog.rs` 负责工作区目录事务，`host.rs` 负责宿主身份和运行域持久化，`bundle.rs` 校验发行资源，`credentials.rs` 适配密钥存储，`remote.rs` 适配远程连接命令，`desktop.rs` 负责窗口、托盘与更新。跨宿主能力继续复用 `host-runtime`。

- 有历史项目时启动自动打开最近使用项目。
- 首次无历史项目时显示空工作台，不自动弹目录选择，也不创建 fallback workspace；点击唯一
  Workspace 入口后才打开原生目录选择，取消保持空状态。
- 关闭主窗口隐藏到托盘，Runtime 继续运行；只有托盘“退出 Harnex”才 drain 并结束进程树。
- 托盘只承担窗口显隐、更新检查与退出；工作区始终从应用内唯一入口打开。Windows/Linux 默认
  `Ctrl+Shift+H` 全局唤起；macOS 为避免抢占 Finder `⇧⌘H` 默认不注册，可用
  `HARNEX_GLOBAL_SHORTCUT` 显式配置。
- 首次显示默认最大化；之后恢复窗口位置/尺寸/显示器。原生标题栏跟随 light/dark mode。
- Web/npm 与桌面壳共享 `~/.harnex/catalog/workspaces.json` Catalog 契约；active/last、owner、
  token、port、log 与 manifest 只写入 Tauri 自己的 `~/.harnex/runtime-domains/tauri-main/`。
- Tauri 与 Web Supervisor 对齐 JVM heap、分层编译、JAR 顺序预热与 READY/DRAIN 时限；两者都会关闭旧 JVM 后启动新 JVM。
- 独立模式下，Tauri 的项目执行状态保存在共享项目数据根下的 `runtime-stores/tauri`，VS Code 使用自己的执行目录，各自保留独占锁。两端共享历史、附件和工具详情，同一会话通过跨进程锁互斥。attach 模式则共用 Tauri 的唯一活动项目：VS Code 更换目录须确认后才请求宿主空闲切换；Tauri 主动切换或退出会使 VS Code 连接失效并显示提醒。

## 一键构建（推荐）

构建编排自动完成「构建 jar+前端 → native CLI + jlink JRE/类共享缓存 → macOS 内嵌代码预签名 →
Runtime 哈希 → 暂存 resources → Tauri bundle」：

安装后的 JRE 模块保持未压缩，由安装包负责传输压缩；同平台构建时预生成并验证可迁移的 CDS
基础类缓存，减少每次启动 JVM 的类加载开销。桌面和原生 CLI 将已完成 Bundle 校验的 JAR 哈希
传给后端，避免首个协议请求重复读取整个 JAR；直接运行 JAR 和 SSH 远端启动仍自行计算哈希。

```bash
npm run build:desktop          # Windows: NSIS；macOS: 当前 CPU 的 .app + .dmg
npm run build:desktop:updater  # 生成 minisign 签名 updater artifact
npm run build:desktop:macos:release # Developer ID + notarize/staple + updater
npm run build:desktop:debug    # debug 构建，跑得快、体积大
node scripts/build/desktop.mjs --check   # 仅检查工具链（cargo / tauri-cli / jlink），不构建
```

产物在 `src-tauri/target/release/bundle/`。

### 签名与自动更新

Rust 宿主使用 Tauri v2 updater：启动后后台检查，菜单栏也可手动“检查更新”。下载内容必须通过
minisign；macOS 外层仍必须通过 Developer ID 与 Apple notarization。正式发布设置：

```bash
export HARNEX_UPDATE_ENDPOINT='https://updates.example.com/harnex/latest.json'
export HARNEX_UPDATE_PUBKEY='<minisign public key>'
export TAURI_SIGNING_PRIVATE_KEY='<private key or path>'
npm run build:desktop:macos:release
```

endpoint 和公钥编入 release，并作为不可被运行时环境覆盖的 updater 信任配置；debug 构建仍允许用
环境变量测试 staging channel。私钥只进入构建环境。
缺少 HTTPS endpoint 或公钥时 updater 完全关闭，不提供 unsigned fallback。
公共 Tauri 配置仍保留 `plugins.updater: { "pubkey": "", "endpoints": [] }`，以满足插件初始化的
反序列化要求；实际检查更新时才使用编译期通道信息覆盖 updater builder。不能删除该配置块或设为 null。

## 本地开发

开发模式与 release 使用同样的独立 Runtime Domain，不提供任意 URL attach：

```bash
npm run dev:desktop
```

## 前置工具链

| 工具 | 用途 | 安装 |
|------|------|------|
| Rust + cargo | 编译薄壳 | https://rustup.rs |
| Tauri CLI v2 | `tauri build/dev` | `cargo install tauri-cli --version "^2"` 或 `npm i -g @tauri-apps/cli@^2` |
| WebView2 / WKWebView | 系统渲染引擎 | Windows 10/11 或 macOS 13+ 系统自带 |
| JDK 17+（含 `jlink`/`jdeps`） | 裁剪内置 JRE | https://adoptium.net |
| Xcode Command Line Tools | macOS 编译、codesign、notarytool | `xcode-select --install` |
| Developer ID + App Store Connect API Key | 正式 Mac 签名/公证 | 仅 release 构建需要 |

`node scripts/build/desktop.mjs --check` 会逐项体检并给出缺失项的安装提示。

## 图标

`tauri build` 需要 `icons/`（含 `icon.ico` 等）。把一张 ≥1024×1024 的 PNG 放到 `icons/source.png`，构建脚本会自动调用 `tauri icon` 生成整套图标；缺失时会给出明确提示。详见 [icons/README.md](icons/README.md)。
