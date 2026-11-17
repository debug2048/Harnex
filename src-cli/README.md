# Harnex native CLI

SSH 工作区与按需命令隔离见 [当前方案](../docs/06_用户手册.md#ssh-workspaces)。使用 `harnex tui --remote devbox --workspace /srv/project`；项目运行数据默认使用项目的 `.harnex/data`，显式 dataDir 优先。

普通会话使用 `harnex tui --conversation` 或 `harnex cli --conversation -p "问题"`，保存在本机 `<用户目录>/personal`，不要求项目。独立 TUI 按 F6 在个人会话与项目 Agent 间切换，草稿、选中记录和模型选择按空间保留；已有 Runtime 在切换时保持运行。退出借用的个人 Runtime 不会关闭 GUI 的进程。SSH 模式下，F6 切到个人会话使用本机个人空间。

显式 Domain manifest / IDE 集成终端遵守固定连接规则，不能用会话标志越过项目身份；VS Code 扩展继续只提供 Agent。

每次启动 TUI 默认进入新会话，不自动打开历史记录。按 `Ctrl+P` 选择 `threads` 手动打开历史会话；`Ctrl+N` 新建会话。F6 返回已经打开的空间时仍恢复该空间的会话和草稿。

主页突出 `Ctrl+P` 命令菜单与 `F1` 帮助，明确菜单包含历史会话、模型与推理强度、设置；固定页脚保留 `F1` 快捷键，F1 在草稿和弹窗中均可用。在 `Ctrl+P → 设置 → 换行与发送快捷键`（也可搜索 `input-keys`）选择两组输入按键：默认 `Shift+Enter` 换行、`Enter` 发送；另一组 `Enter` 换行、`Ctrl+Enter` 发送。选择立即生效并保存至用户目录 `tui-input-keys`，所有空间共用，重启后恢复。草稿、提问回答和反馈输入使用同一组；菜单和审批操作仍用 Enter 确认，粘贴始终只写入草稿。页脚和 F1 帮助随设置更新。`Ctrl+X → Q` 随时退出并保留草稿；`Ctrl+X → C` 清空且不停止任务，`Ctrl+Z` 撤销。`Ctrl+U/K/W` 用于删至行首／行尾／前词，原工具集和技能入口移至 `Ctrl+X → U/K`。上下键先移动多行草稿，在首尾回填历史；`Ctrl+R` 搜索并回填，另按当前组合的发送键。草稿和输入历史按空间持久化到用户目录 `tui/workspaces`。

`F3` / `Ctrl+X → R` / `Ctrl+P → effort` 选择当前模型支持的推理强度，`auto` 跟随 Provider 默认。显式强度绑定具体 Provider/模型，Agent 模式遵守工具调用限制；切换模型后不支持的档位恢复自动。选择作用于下次新任务，随空间保存，运行快照刷新不会覆盖下次选择。

`F2` 选择模型，`Shift+F2` 切换最近模型，模型列表内 `Ctrl+F` 收藏；`Shift+Tab` 切换项目审批模式。`Ctrl+X → Y` 复制最后一条回答的完整文本，命令面板 `copy-turn` 复制本轮已接收工具和正文，`export` 分页导出当前分支的会话消息至用户目录 `exports`。`Ctrl+X → V` 全文预览长草稿，`Ctrl+X → E` 使用 `VISUAL` / `EDITOR`（例如 `code --wait`；Windows 默认记事本），保存关闭后回填草稿。

`Ctrl+X → Q` 表示先按 Ctrl+X，松开后再按 Q；前缀等待期间页脚显示提示，Esc 取消。它是前缀键序列，也可从命令菜单搜索 `quit` 退出。

`Ctrl+P` 首页按范围组织为新建会话、历史会话、当前会话、当前任务、设置和帮助。模型、权限、工具集、恢复点、目标等属于当前会话；变更、任务输入、批准和停止属于当前任务；语言、换行与发送按键、显示选项属于设置。未满足条件的操作显示原因，不能静默执行。输入关键字可跨分类搜索，`Esc` 逐级返回并保留选择，`Ctrl+P` 随时回命令首页。斜杠菜单将 `/skill`、`/plan`、`/goal` 放在行首，选定后回输入框补充参数。

界面使用随终端宽度展开的原有布局：正文左对齐，底部全宽输入框默认 3 行，随输入内容增高；模型与权限显示在顶部。输入框边框明确标注“下次任务模型 / 推理强度”；运行中的任务继续显示自己的模型。右侧栏按任务状态、本次任务模型、本次用量分组，数值右对齐，长模型名可折行；空闲时显示下次任务配置，未返回用量时显示说明而非成排占位数字。侧栏宽度和详情随窗口空间调整，较矮窗口优先保留状态及核心用量。侧栏默认在 112 列以上显示，窗口缩小时收起，也可手动切换。用户消息使用独立底色和左侧标记，工具使用淡色短分隔线、状态与统一缩进分组；命令／路径和返回内容分别保留，长输出自动换行并可滚动查看。模型文本输出时标记“回复中”，进入工具执行后移除该标记；任务状态跟随实际执行事件更新，真正尚未执行的任务才显示“排队中”。`Ctrl+O` 仅展开或收起思考过程。深浅主题分别定义完整的前景与背景，使用蓝紫色表示焦点，状态色只标记完成、警告和失败；选中行使用低饱和底色，批准面板的长命令可按 PgUp/PgDn 查看，操作键保持可见。

通过 `Ctrl+P → 设置 → 主题`（或搜索 `theme`）切换 `auto`、`dark`、`light`、`ansi`，选择即时生效并保存到用户数据目录的 `tui-theme`。启动参数 `--theme` 优先于已保存偏好；`auto` 优先读取 `COLORFGBG`，Windows 上也读取启动前的控制台背景，无法识别时使用完整深色主题。`--color never` / `NO_COLOR` 保留终端默认前景与背景，使用反显标记选择，不应用主题颜色。

界面支持简体中文与英文，首次启动跟随系统语言。按 `Ctrl+P → language` 选择中文、English 或跟随系统，选择保存到用户数据目录的 `tui-language` 文件。也可临时指定 `harnex tui --lang zh-CN`、`--lang en` 或 `--lang auto`。优先级为 `--lang`、`HARNEX_LANG`、已保存偏好、系统语言；显式 `auto` 跟随系统。系统检测优先读取 `LC_ALL` / `LC_MESSAGES` / `LANG`，Windows 无这些变量时读取用户界面语言，macOS 读取 AppleLocale。语言切换仅影响界面文案，不翻译用户输入、模型回复、工具输出或自定义名称。`harnex tui --help` 可在非交互终端查看对应语言的帮助。

Cross-platform launcher distributed from the canonical Runtime Bundle for Windows,
Linux, macOS Apple Silicon and macOS Intel. It does not
contain an AgentLoop and does not require Node or a machine-wide JDK: Owner commands
spawn the bundle's private JRE, while Client commands use Local API v1 after exact
Runtime Domain manifest validation.

The native surface includes `version`, `doctor`, `serve`, `stop`,
`runtime status|stop|restart`, `app`, headless `cli -p`, and the Phase-5
`tui` client. `harnex tui` owns a workspace Runtime Domain when launched from a
normal terminal and exact-attaches as a Client when an IDE injects a trusted
domain manifest. It never contains an AgentLoop.

Transcript frames refresh on a fixed cadence during busy event streams. Use
PgUp/PgDn to read output, Ctrl+Home for its beginning, and Ctrl+End
to follow the latest output. Reading stays anchored across new events and final
snapshots, and full tool output already received is preserved. The context rail
prioritizes current activity, cumulative input/output tokens, cache reads and hit
rate, with a distinction between partial and complete provider usage reports.

Empty composers show a muted italic hint, prefixed with “Hint” / “提示”. In terminals
that support bracketed paste, including Windows Terminal, pasted text goes directly
into the draft: CRLF is normalized to newlines, and pasted newlines never send a
message. Use the selected send key separately to submit the complete draft.
Standalone Windows consoles that omit paste markers are also handled: a queued
multiline text batch is inserted into the draft before any newline is interpreted
as Enter, including a trailing newline beyond the console reader's chunk boundary.
The mouse wheel scrolls TUI history, including reopened conversations. Drag with
the left button to select visible text; right-click or Ctrl+C copies the selection.
With nothing selected, right-click pastes into the draft without sending it.
Selection holds the displayed text still while output arrives; Esc clears the
selection. PgUp/PgDn and Ctrl+Home/End remain available. Exiting restores the
terminal's original input modes.

On Windows, owned Runtime and local SSH process trees are bound to the host with
Job Objects. Closing the terminal or terminating the CLI also reaps these trees;
attached Runtime processes remain under their original host's ownership.

The current TUI provides an adaptive Ratatui/Crossterm transcript and composer,
incremental SSE recovery, threads, models/toolsets, permission modes, `@file`,
Stop, Steer/Follow-up, input withdrawal, interactions, Changes/Diff, Snapshot,
Goal/Todo/Usage/Context and Skill workflows. Fullscreen/inline renderers and
auto/dark/light/ansi/no-color themes share the same state model. On Windows,
`auto` serializes the same rich Ratatui frame as contiguous styled rows, avoiding
terminal-specific CJK horizontal positioning without changing the UI; other
platforms use cell diffs by default. `--render-strategy stable|diff` selects the
transport explicitly. Java `CliRunner` has been removed; `harnex cli` without a prompt is an alias of `tui`,
while `cli -p` remains the headless API client. Missing native components produce an installation hint.
`harnex serve` defaults to `http://127.0.0.1:18081`, matching the Web and Docker entrypoints.
Override it with `--server.port`, `--port`, `HARNEX_PORT`, or `SERVER_PORT` (in that order).
Desktop, IDE and standalone CLI/TUI Runtime ports remain automatically allocated.
See the [installation guide](../docs/en/installation.md#platforms) and release notes for platform support and verification status.

```text
harnex tui
harnex tui --conversation
harnex tui --workspace D:\code\my-project
harnex tui --renderer inline --theme ansi
harnex tui --render-strategy stable
harnex --domain-manifest <path> tui
harnex cli -p "summarise this workspace"
harnex cli --conversation -p "Explain this idea"
```

On macOS, use the matching darwin-arm64 or darwin-x64 bundle. Ctrl means Control; function keys may need Fn. Both input presets require the terminal to distinguish the selected modified-Enter key; an external editor (`Ctrl+X → E`) also supports multiline drafts. Clipboard actions use pbcopy/pbpaste. `Ctrl+P` exposes help, model, effort and settings without function keys.

On macOS, `harnex app` opens the containing `.app`, `harnex path install` creates the
per-user `~/.local/bin/harnex` link, and `Ctrl+X → I` is the Terminal.app-safe alternative
to `Option/Alt+Enter` for Steer/Follow-up switching. Runtime processes are isolated in a
POSIX process group so JVM/MCP/tool descendants are reaped together.

`path install` and `path uninstall` only manage a symbolic link whose resolved target is
this exact Harnex executable; an existing regular file, broken link or link to another
binary is never removed. Provider references named `HARNEX_KEYCHAIN_PROVIDER_*` are owned
by Harnex.app's signed desktop Runtime. A standalone native CLI Runtime fails early when
such a reference is present but unavailable; use `harnex app`/exact attach or provide an
explicit credential to the CLI environment.
