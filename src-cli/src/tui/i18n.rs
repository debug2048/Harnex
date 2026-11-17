//! Terminal chrome only: never translate user messages, model output, paths or protocol IDs.
use std::path::PathBuf;
#[cfg(not(test))]
use std::sync::atomic::{AtomicBool, Ordering};

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Language {
    Auto,
    English,
    Chinese,
}

impl Language {
    pub fn parse(value: &str) -> Result<Self, String> {
        match value.trim().to_ascii_lowercase().as_str() {
            "auto" => Ok(Self::Auto),
            "en" | "en-us" | "en-gb" => Ok(Self::English),
            "zh" | "zh-cn" | "zh-hans" | "zh-tw" | "zh-hant" => Ok(Self::Chinese),
            _ => {
                Err("--lang / HARNEX_LANG: use auto, zh-CN or en / 请使用 auto、zh-CN 或 en".into())
            }
        }
    }

    fn value(self) -> &'static str {
        match self {
            Self::Auto => "auto",
            Self::English => "en",
            Self::Chinese => "zh-CN",
        }
    }
}

#[cfg(not(test))]
static CHINESE: AtomicBool = AtomicBool::new(false);
// Unit tests can render both languages concurrently without changing other tests.
#[cfg(test)]
thread_local! { static CHINESE: std::cell::Cell<bool> = const { std::cell::Cell::new(false) }; }

pub fn is_chinese() -> bool {
    #[cfg(not(test))]
    {
        CHINESE.load(Ordering::Relaxed)
    }
    #[cfg(test)]
    {
        CHINESE.get()
    }
}

pub fn set(language: Language) {
    let chinese = match language {
        Language::Chinese => true,
        Language::English => false,
        Language::Auto => system_is_chinese(),
    };
    #[cfg(not(test))]
    CHINESE.store(chinese, Ordering::Relaxed);
    #[cfg(test)]
    CHINESE.set(chinese);
}

fn preference_path() -> PathBuf {
    crate::non_blank_env("HARNEX_USER_DATA_DIR")
        .map(PathBuf::from)
        .unwrap_or_else(|| crate::home_dir().join(".harnex"))
        .join("tui-language")
}

fn resolve_language(
    explicit: Option<&str>,
    environment: Option<&str>,
    saved: Option<&str>,
) -> Result<Language, String> {
    if let Some(value) = explicit.or(environment) {
        return Language::parse(value);
    }
    // A corrupt preference must not prevent the terminal from opening.
    Ok(saved
        .and_then(|value| Language::parse(value).ok())
        .unwrap_or(Language::Auto))
}

pub fn initialize(explicit: Option<&str>) -> Result<(), String> {
    let environment = crate::non_blank_env("HARNEX_LANG");
    let saved = std::fs::read_to_string(preference_path()).ok();
    set(resolve_language(
        explicit,
        environment.as_deref(),
        saved.as_deref(),
    )?);
    Ok(())
}

pub fn save(language: Language) -> Result<(), String> {
    let path = preference_path();
    std::fs::create_dir_all(path.parent().unwrap()).map_err(|error| error.to_string())?;
    std::fs::write(path, language.value()).map_err(|error| error.to_string())
}

fn locale_is_chinese(value: &str) -> bool {
    value
        .split(['_', '-', '.', '@'])
        .next()
        .is_some_and(|language| language.eq_ignore_ascii_case("zh"))
}

fn system_is_chinese() -> bool {
    for key in ["LC_ALL", "LC_MESSAGES", "LANG"] {
        if let Some(value) = crate::non_blank_env(key) {
            return locale_is_chinese(&value);
        }
    }
    #[cfg(windows)]
    {
        // Windows terminals commonly have no LANG; use the user's Windows UI language.
        unsafe { windows_sys::Win32::Globalization::GetUserDefaultUILanguage() & 0x3ff == 0x04 }
    }
    #[cfg(target_os = "macos")]
    {
        std::process::Command::new("/usr/bin/defaults")
            .args(["read", "-g", "AppleLocale"])
            .output()
            .ok()
            .filter(|output| output.status.success())
            .is_some_and(|output| locale_is_chinese(String::from_utf8_lossy(&output.stdout).trim()))
    }
    #[cfg(not(any(windows, target_os = "macos")))]
    {
        false
    }
}

// Both format strings are checked by Rust, including named placeholders.
macro_rules! tf {
    ($en:literal, $zh:literal $(, $args:expr)* $(,)?) => {
        if $crate::tui::i18n::is_chinese() {
            format!($zh $(, $args)*)
        } else {
            format!($en $(, $args)*)
        }
    };
}
pub(crate) use tf;

pub fn text(english: &str) -> &str {
    if !is_chinese() {
        return english;
    }
    match english {
        "F1 shortcuts" => "F1 快捷键",
        "Enter send | Shift+Enter newline" => "Enter 发送 | Shift+Enter 换行",
        "Enter update | Shift+Enter newline | Esc stop" => "Enter 补充 | Shift+Enter 换行 | Esc 停止",
        "Enter | Shift+Enter newline" => "Enter | Shift+Enter 换行",
        "Shift+Enter newline" => "Shift+Enter 换行",
        "Newline and send keys" => "换行与发送快捷键",
        "Shift+Enter newline | Enter send" => "Shift+Enter 换行 | Enter 发送",
        "Enter newline | Ctrl+Enter send" => "Enter 换行 | Ctrl+Enter 发送",
        "Ctrl+Enter send | Enter newline" => "Ctrl+Enter 发送 | Enter 换行",
        "Ctrl+Enter update | Enter newline | Esc stop" => "Ctrl+Enter 补充 | Enter 换行 | Esc 停止",
        "Ctrl+Enter send" => "Ctrl+Enter 发送",
        "Enter submit | Shift+Enter newline | Esc back" => "Enter 提交 | Shift+Enter 换行 | Esc 返回",
        "Ctrl+Enter submit | Enter newline | Esc back" => "Ctrl+Enter 提交 | Enter 换行 | Esc 返回",
        "Enter (menus)" => "Enter（菜单）",
        "confirm a menu choice or approval" => "确认菜单选项或批准操作",
        "choose newline and send keys" => "选择换行和发送的按键组合",
        "Text input only; Enter still confirms menus" => "仅影响文本输入；菜单仍使用 Enter 确认",
        "Modified Enter needs terminal support; Ctrl+X → E opens an editor" => "修饰 Enter 需要终端支持；Ctrl+X → E 可打开编辑器",
        "insert a newline" => "插入换行，不发送",
        "| Ctrl+X → E editor " => "| Ctrl+X → E 外部编辑 ",
        "F1: view all keyboard shortcuts" => "F1: 查看全部快捷键",
        "F2 model | Shift+Tab approval mode" => "F2 切换模型 | Shift+Tab 审批模式",
        "F2 model | F6 project Agent" => "F2 切换模型 | F6 项目 Agent",
        "Ctrl+P > threads: history | Ctrl+N new" => "Ctrl+P > threads 历史会话 | Ctrl+N 新建会话",
        "Up/Down input history | Ctrl+R search" => "上下键回填历史输入 | Ctrl+R 搜索",
        "Ctrl+X → C clear draft | Ctrl+X → Q exit" => "Ctrl+X → C 清空草稿 | Ctrl+X → Q 退出",
        "ctrl+p commands" => "ctrl+p 命令",
        "ctrl+x: q exit | c clear | y copy | e editor | v preview" => "ctrl+x: q 退出 | c 清空 | y 复制 | e 编辑 | v 预览",
        "Draft restored | F1 shortcuts" => "已恢复未发送草稿 | F1 查看快捷键",
        "Draft cleared | Ctrl+Z undo | Ctrl+X → Q exit" => "草稿已清空 | Ctrl+Z 撤销 | Ctrl+X → Q 退出",
        "Draft is not empty | Ctrl+X → Q exit | Ctrl+X → C clear" => "草稿非空 | Ctrl+X → Q 退出 | Ctrl+X → C 清空",
        "Unknown Ctrl+X chord | F1 shortcuts" => "未知 Ctrl+X 组合键 | F1 查看快捷键",
        "History restored to draft | Enter sends" => "历史输入已回填草稿 | 另按 Enter 发送",
        "Input history" => "输入历史",
        "Draft preview" => "草稿全文预览",
        "Exit terminal (keep draft)" => "退出终端并保留草稿",
        "Clear draft (undo available)" => "清空草稿（可撤销）",
        "Search input history" => "搜索历史输入",
        "Preview full draft" => "预览完整草稿",
        "Edit draft in external editor" => "用外部编辑器编辑草稿",
        "Copy last answer" => "复制最后一条回答",
        "Copy received turn output (including tools)" => "复制本轮已接收输出（含工具）",
        "Export conversation messages to Markdown" => "导出会话消息为 Markdown",
        "[favorite] " => "[收藏] ",
        "[recent] " => "[最近] ",
        "Ctrl+F favorite | Shift+F2 recent models" => "Ctrl+F 收藏模型 | Shift+F2 切换最近模型",
        "Enter restores the draft; it does not send" => "Enter 只回填草稿，不会发送",
        "No answer to copy yet" => "还没有可复制的回答",
        "No output to copy yet" => "还没有可复制的输出",
        "Answer" => "回答",
        "Received turn output" => "本轮已接收输出",
        "Text" => "文本",
        "Conversation exported" => "会话已导出",
        "Exit this terminal?" => "退出当前终端？",
        "Your draft is kept. Exiting stops runtimes started by this terminal and their tasks. Shared runtimes stay open." => "未发送草稿会保留。本窗口启动的服务及任务将停止；借用的服务保持运行。",
        "PgUp/PgDn scroll | c copy | e edit | Esc back" => "PgUp/PgDn 翻页 | c 复制 | e 编辑 | Esc 返回",
        "PgUp/PgDn scroll | c copy | Esc back" => "PgUp/PgDn 翻页 | c 复制 | Esc 返回",
        "shortcuts from any screen" => "随时查看快捷键，不清空草稿",
        "exit terminal and keep draft" => "随时退出终端，保留未发送草稿",
        "clear draft without stopping task" => "清空草稿，不停止正在运行的任务",
        "undo input edit or clear" => "撤销输入编辑或清空操作",
        "redo input edit" => "重做输入编辑",
        "start / end of input line" => "移动到当前输入行的开头／结尾",
        "delete to line start / end" => "删除至当前行的开头／结尾",
        "delete previous word" => "删除光标前一个词",
        "restore deleted text" => "恢复最近删除的文字",
        "move by word" => "按词移动光标",
        "move in draft; recall history at edges" => "先在草稿内移动，到首尾后回填历史",
        "search input history; restore to draft" => "搜索历史输入，选中后只回填草稿",
        "preview the entire draft" => "完整预览长草稿",
        "edit draft in external editor" => "用外部编辑器修改草稿",
        "copy the last answer" => "复制最后一条完整回答",
        "copy received output including tools" => "复制本轮已接收输出，包括工具内容",
        "export conversation messages as Markdown" => "导出会话消息为 Markdown 文件",
        "cancel task / clear idle draft" => "有任务时取消任务；空闲时清空草稿",
        "delete character / exit empty draft" => "删除光标后字符；空草稿时退出",
        "choose model without clearing draft" => "选择模型，保留当前草稿",
        "cycle recent models" => "循环切换最近使用的模型",
        "toggle model favorite" => "在模型列表中收藏／取消收藏",
        "permission mode (Agent)" => "切换审批模式（项目 Agent）",
        "PgUp/PgDn details | Esc back" => "PgUp/PgDn 查看详情 | Esc 返回",
        "Conversation context" => "会话上下文",
        "Search commands" => "输入关键字搜索",
        "a allow once" => "a 允许一次",
        "w allow this tool" => "w 允许此工具",
        "d deny" => "d 拒绝",
        "  Thinking collapsed | Ctrl+O details" => "  思考已收起 | Ctrl+O 详情",
        "Theme" => "主题",
        "Dark" => "深色",
        "Light" => "浅色",
        "Follow terminal background" => "跟随终端背景",
        "ANSI compatibility" => "ANSI 兼容",
        "Theme preference saved" => "主题偏好已保存",
        "Start a task in this workspace" => "在此工作区开始一项任务",
        "Continue the conversation" => "继续对话",
        "New conversation" => "新建会话",
        "Esc back | Ctrl+P command home" => "Esc 返回 | Ctrl+P 命令首页",
        "No conversations yet. Esc returns to your draft." => "还没有历史会话，按 Esc 返回输入框。",
        "Explore, then review a plan before execution" => "先探索并审核计划，再执行",
        "Work toward a goal with a budget" => "在预算内持续完成目标",
        "Current conversation" => "当前会话",
        "Current task" => "当前任务",
        "Interface settings" => "界面设置",
        "Settings" => "设置",
        "Ctrl+P command menu | F1 shortcuts" => "Ctrl+P 命令菜单 | F1 快捷键帮助",
        "History | Models & effort | Settings" => "历史会话 | 模型与推理强度 | 设置",
        "F2 model | F3 effort | Ctrl+N new" => "F2 模型 | F3 推理强度 | Ctrl+N 新建会话",
        "Shift+Tab approval mode" => "Shift+Tab 审批模式",
        "Ctrl+X → Q exit | press in sequence" => "Ctrl+X → Q 退出 | 分两步按下",
        "Release Ctrl+X, then: Q exit | M model | R effort | Esc cancel" => "松开 Ctrl+X，再按：Q 退出 | M 模型 | R 强度 | Esc 取消",
        "Ctrl+X → Q: press Ctrl+X, release, then Q" => "Ctrl+X → Q：先按 Ctrl+X，松开后再按 Q",
        "Ctrl+P opens all commands, including history and settings" => "Ctrl+P 打开命令菜单，包含历史会话、设置等入口",
        "Mac: Ctrl means Control; function keys may need Fn" => "Mac：Ctrl 指 Control 键；功能键可能需要配合 Fn",
        "Shift+Enter needs terminal support; Ctrl+X → E opens an editor" => "Shift+Enter 需要终端支持；Ctrl+X → E 可打开编辑器",
        "Reasoning effort" => "推理强度",
        "Auto (provider default)" => "自动（跟随 Provider 默认配置）",
        "reasoning effort for the next task" => "选择下次任务的推理强度",
        "choose from the selected model's supported levels" => "选择当前模型支持的强度档位",
        "theme, language and display settings" => "主题、语言与显示设置",
        " (unsupported level reset to auto)" => "（不支持的强度已恢复自动）",
        "Model capabilities changed; effort reset to auto" => "模型能力已变化；推理强度已恢复自动",
        "Wait for model capabilities, or select auto effort with F3" => "请等待模型能力加载，或按 F3 选择自动强度",
        "Ctrl+F favorite | F3 effort | Shift+F2 recent" => "Ctrl+F 收藏 | F3 强度 | Shift+F2 最近模型",
        "Loading model capabilities..." => "正在加载模型能力...",
        "Capabilities unavailable; reopen F3 to retry" => "模型能力暂不可用；重新按 F3 重试",
        "Choose a model with F2 first" => "请先按 F2 选择模型",
        "This model has no selectable reasoning levels" => "当前模型不提供可选的推理强度档位",
        "Conversation model" => "会话模型",
        "Permission mode" => "权限模式",
        "Insert a skill reference" => "插入技能引用",
        "Stop current task" => "停止当前任务",
        "Ask before tool actions" => "执行工具前询问",
        "Allow file edits" => "允许文件编辑",
        "Full access" => "完全访问",
        "Wait for the message to be accepted" => "请等待消息提交成功",
        "Stop the current task before switching conversations" => "请先停止当前任务，再切换会话",
        "Send a message or select a conversation first" => "请先发送消息或选择历史会话",
        "No task in this conversation yet" => "当前会话尚无任务",
        "No task is running" => "当前没有运行中的任务",
        "Write a draft before queuing it" => "请先在输入框填写待发送内容",
        "Loading" => "正在加载",
        "Loading..." => "正在加载...",
        "Esc back" => "Esc 返回",
        " Esc back | Ctrl+P command home " => " Esc 返回上一级 | Ctrl+P 命令首页 ",
        "Browse by scope, or type to search all commands" => "按范围浏览，或输入关键字搜索全部命令",
        "Choose a command, then add its arguments" => "选择命令，再在输入框补充参数",
        " (unavailable)" => "（暂不可用）",
        " (current)" => "（当前）",
        "Up/Down select | Enter confirm | Esc back" => "Up/Down 选择 | Enter 确认 | Esc 返回",
        "ctrl+x: d changes | m model | i queue draft" => "ctrl+x: d 变更 | m 模型 | i 排队草稿",
        "enter update | esc stop" => "enter 更新 | esc 停止",
        "enter send" => "enter 发送",
        "ctrl+p | ? help" => "ctrl+p | ? 帮助",
        "Ctrl+P > threads: history" => "Ctrl+P > threads：历史会话",
        "Ctrl+N: new conversation" => "Ctrl+N：新建会话",
        "READY_FOR_REVIEW" => "等待审核",
        "BUDGET_LIMITED" => "预算受限",
        "INTERRUPTED" => "已中断",
        "pre_tool" => "工具执行前",
        "run_start" => "任务开始前",
        "EXPIRED" => "已过期",
        "Presentation omitted conversation" => "运行结果缺少会话信息",
        "Ctrl+P > threads: history | Ctrl+N: new conversation" => "Ctrl+P > threads：历史会话 | Ctrl+N：新建会话",
        "verified" => "已验证",
        "unverified" => "未验证",
        "checkpoint" => "检查点",
        "manual" => "手动",
        "ADDED" => "新增",
        "MODIFIED" => "修改",
        "DELETED" => "删除",
        "RENAMED" => "重命名",
        "BLOCKED" => "受阻",
        "REVIEW_REQUIRED" => "需要审核",
        "AWAITING_REVIEW" => "等待审核",
        "BUDGET_EXHAUSTED" => "预算已用尽",
        "Up/Down scroll | PgUp/PgDn page | Esc back" => "Up/Down 滚动 | PgUp/PgDn 翻页 | Esc 返回",
        "interactive tui requires a TTY; use `harnex cli -p <prompt>` for headless execution" => "交互式 TUI 需要终端（TTY）；无界面执行请使用 `harnex cli -p <prompt>`",
        "--renderer must be fullscreen or inline" => "--renderer 必须为 fullscreen 或 inline",
        "--theme must be auto, dark, light or ansi" => "--theme 必须为 auto、dark、light 或 ansi",
        "--color must be auto, always or never" => "--color 必须为 auto、always 或 never",
        "--render-strategy must be auto, stable or diff" => "--render-strategy 必须为 auto、stable 或 diff",
        "Invalid preview URL" => "无效的预览地址",
        "New thread" => "新会话",
        "Working..." => "处理中...",
        "Conversation" => "普通会话",
        "Project Agent" => "项目 Agent",
        "Personal conversations" => "个人会话",
        "Ready for a conversation" => "可以开始对话",
        "Connected to your workspace" => "已连接到工作区",
        "Ready in your workspace" => "工作区已就绪",
        "Describe a task below | / for commands" => "在下方描述任务 | / 查看命令",
        "Ask a question, write, or describe an image" => "提问、写作，或描述一张图片",
        "Thinking collapsed | Ctrl+O to show" => "思考过程已折叠 | Ctrl+O 展开",
        "Thinking" => "思考过程",
        " input " => " 输入 ",
        "  replying..." => "  回复中...",
        "Tool" => "工具",
        "Command" => "命令",
        "Path" => "路径",
        "Pattern" => "匹配",
        "Input" => "输入",
        "Output" => "输出",
        "Hint: ask a question..." => "提示: 输入问题...",
        "Hint: add to the current task..." => "提示: 补充当前任务...",
        "Hint: describe a task..." => "提示: 输入任务...",
        "Task" => "任务",
        "CURRENT TASK" => "当前任务",
        "THIS TASK MODEL" => "本次任务模型",
        "NEXT TASK CONFIG" => "下次任务配置",
        "Next task model" => "下次任务模型",
        "Next model" => "下次模型",
        "effort" => "推理强度",
        "Provider" => "服务商",
        "RUN USAGE" => "本次用量",
        "Awaiting model" => "等待模型信息",
        "Usage appears after a task" => "发送消息后显示用量",
        "No usage reported" => "未返回用量数据",
        "F2 model | F3 effort" => "F2 模型 | F3 推理强度",
        " update " => " 补充任务 ",
        "TOKENS" => "TOKEN 用量",
        "MODEL" => "当前模型",
        "Cache read" => "缓存读取",
        "Hit rate" => "命中率",
        "Cache write" => "缓存写入",
        "Usage reported" => "已完整上报",
        "Reported usage so far" => "已上报用量",
        "Awaiting usage" => "等待用量回报",
        "Needs you" => "待你处理",
        "Queued msgs" => "排队消息",
        "Replying" => "回复中",
        "Reading history | PgUp/PgDn | Ctrl+End latest" => "回看记录 | PgUp/PgDn | Ctrl+End 最新",
        "RUN" => "运行",
        "THREAD" => "会话",
        "WAITING" => "等待处理",
        "GOAL" => "目标",
        "USAGE" => "用量",
        "status" => "状态",
        "model" => "模型",
        "mode" => "模式",
        "tokens" => "令牌数",
        "cached" => "缓存",
        "interactions" => "待处理交互",
        "pending input" => "待处理输入",
        " sending... " => " 正在发送... ",
        " update current task " => " 更新当前任务 ",
        " message " => " 消息 ",
        "Ask a question, or describe an image..." => "提问，或描述一张图片...",
        "Add details or adjust the current task..." => "补充细节或调整当前任务...",
        "Tell Harnex what to build..." => "告诉 Harnex 你想完成什么...",
        "d changes | m model | i run after completion" => "d 变更 | m 模型 | i 完成后执行",
        "enter update | alt+enter queue | esc stop" => "enter 更新 | alt+enter 排队 | esc 停止",
        "conversation" => "普通会话",
        "agent" => "项目 Agent",
        "ctrl+p commands | ? help" => "ctrl+p 命令 | ? 帮助",
        "ctrl+p commands | shift+tab mode | ? help" => "ctrl+p 命令 | shift+tab 模式 | ? 帮助",
        "Enable full access?" => "启用完全访问权限？",
        "Harnex may run tools without asking. Runtime denylist and path safety still apply." => "Harnex 可以直接运行工具。运行时的禁用列表和路径安全限制仍然有效。",
        "Enter/y enable | Esc/n back" => "Enter/y 启用 | Esc/n 返回",
        "A run is still active" => "仍有任务正在运行",
        "Exit stops this terminal's owned runtimes, including background tasks. Shared runtimes stay open." => "退出将停止此终端启动的运行时及其后台任务，共享运行时会继续运行。",
        "c cancel + exit | e/Enter exit | Esc back" => "c 取消并退出 | e/Enter 退出 | Esc 返回",
        "Keyboard" => "快捷键",
        "send / confirm" => "发送 / 确认",
        "open a project file in browser" => "在浏览器打开项目文件",
        "open a conversation attachment in browser" => "在浏览器打开会话附件",
        "stop active run" => "停止当前任务",
        "exit when composer is empty" => "输入框为空时退出",
        "command palette" => "命令面板",
        "model picker | Ctrl+X → M also works" => "选择模型，也可按 Ctrl+X → M",
        "switch Conversation / Agent (standalone terminal)" => "切换普通会话 / 项目 Agent（独立终端）",
        "run input mailbox" => "查看任务输入",
        "goal status and controls" => "目标状态与控制",
        "skill picker" => "选择技能",
        "toolset picker" => "选择工具集",
        "send once after completion | Ctrl+X → I fallback" => "完成后执行，也可按 Ctrl+X → I",
        "new thread" => "新建会话",
        "toggle Thinking details" => "展开 / 折叠思考过程",
        "this run's changes" => "本次任务的变更",
        "permission mode" => "权限模式",
        "scroll transcript" => "滚动会话记录",
        "Mouse wheel" => "鼠标滚轮",
        "Left drag" => "左键拖选",
        "Right-click / Ctrl+C" => "右键 / Ctrl+C",
        "Right-click" => "右键",
        "select visible text" => "选择屏幕内的文字",
        "copy selection" => "复制选中文字",
        "selection" => "选中文字",
        "paste clipboard into draft when nothing is selected" => "未选中文字时，粘贴到草稿",
        "first / latest output" => "最早 / 最新输出",
        "close overlay first; otherwise stop task and keep draft" => "关闭弹窗；无弹窗时停止任务并保留草稿",
        "this help" => "显示帮助",
        "Esc closes help" => "Esc 关闭帮助",
        "Commands" => "命令",
        "Threads" => "历史会话",
        "Reference a file" => "引用文件",
        "Choose model" => "选择模型",
        "Reference a skill" => "引用技能",
        "Choose toolset" => "选择工具集",
        "Slash commands" => "斜杠命令",
        "No matches" => "没有匹配项",
        "Approval required" => "需要批准",
        "Plan review" => "审核计划",
        "Question" => "问题",
        "tool" => "工具",
        "a allow once | d deny" => "a 允许一次 | d 拒绝",
        "a allow once | w allow this tool | d deny" => "a 允许一次 | w 允许此工具 | d 拒绝",
        "a/Enter approve | r reject with feedback" => "a/Enter 批准 | r 拒绝并反馈",
        "Enter submit | Esc back" => "Enter 提交 | Esc 返回",
        "  truncated" => "  已截断",
        "Binary or unavailable diff" => "二进制文件或差异不可用",
        "This run's changes" => "本次任务的变更",
        "No files changed by this run" => "本次任务没有修改文件",
        "Up/Down select | Enter diff | Esc back" => "Up/Down 选择 | Enter 查看差异 | Esc 返回",
        "Run inputs" => "任务输入",
        "Updates to this task and messages to run after completion" => "当前任务的补充输入，以及完成后执行的消息",
        "After task" => "完成后执行",
        "Update" => "补充输入",
        "Waiting" => "等待中",
        "Processing" => "处理中",
        "Delivered" => "已送达",
        "On hold" => "已暂存",
        "Not applied" => "未应用",
        "Could not deliver" => "发送失败",
        "Replaced" => "已替换",
        "Pending" => "待处理",
        "No inputs for this run" => "此任务没有补充输入",
        "Up/Down select | d withdraw queued/held | Esc back" => "Up/Down 选择 | d 撤回排队或暂存输入 | Esc 返回",
        "Restore points" => "还原点",
        "Restore is refused while a Run is active; branch remains safe." => "任务运行时无法还原，但可以创建分支。",
        "No restore points" => "没有还原点",
        "c conversation | r conversation + files | b branch | Esc back" => "c 还原会话 | r 还原会话与文件 | b 创建分支 | Esc 返回",
        "Confirm restore" => "确认还原",
        "snapshot" => "快照",
        "coverage" => "覆盖范围",
        "files" => "文件",
        "conflicts" => "冲突",
        "conflict" => "冲突",
        "Enter restore | Esc back" => "Enter 还原 | Esc 返回",
        "Goal" => "目标",
        "runs" => "运行次数",
        "turn budget" => "轮次预算",
        "token budget" => "令牌预算",
        "cost budget" => "费用预算",
        "feedback > " => "反馈 > ",
        "p pause | r resume | c cancel | a confirm review | f feedback | Esc back" => "p 暂停 | r 继续 | c 取消 | a 确认审核 | f 反馈 | Esc 返回",
        "Todo progress" => "待办进度",
        "No todos" => "没有待办事项",
        "Context" => "上下文",
        "workspace" => "工作区",
        "root" => "根目录",
        "thread" => "会话",
        "toolsets" => "工具集",
        "default" => "默认",
        "auto" => "自动",
        "runtime" => "运行时",
        "attached client" => "已连接的客户端",
        "owned domain" => "本终端启动",
        "prompt" => "输入令牌",
        "completion" => "输出令牌",
        "reasoning" => "推理令牌",
        "total" => "总计",
        "complete" => "完整统计",
        "yes" => "是",
        "partial" => "部分",
        "READY" => "就绪",
        "CREATED" => "已创建",
        "QUEUED" => "排队中",
        "STARTING" => "启动中",
        "RUNNING" => "执行中",
        "WAITING_RETRY" => "等待重试",
        "WAITING_APPROVAL" => "等待批准",
        "WAITING_INPUT" => "等待输入",
        "WAITING_REVIEW" => "等待审核",
        "PAUSE_REQUESTED" => "正在暂停",
        "PAUSED" => "已暂停",
        "COMPLETED" => "已完成",
        "CANCELLED" => "已取消",
        "FAILED" => "失败",
        "INCOMPLETE" => "未完成",
        "ask" => "询问",
        "accept-edits" => "允许编辑",
        "yolo" => "完全访问",
        "conversation_only" => "仅会话",
        "conversation_and_files" => "会话与文件",
        "full" => "完整",
        "none" => "无",
        "added" => "新增",
        "modified" => "修改",
        "deleted" => "删除",
        "renamed" => "重命名",
        "unavailable" => "不可用",
        "Switch thread" => "选择历史会话",
        "Pending approvals and questions" => "待处理的批准与问题",
        "Choose provider and model" => "选择服务商和模型",
        "Send draft after the current task completes" => "当前任务完成后发送草稿",
        "Pending messages" => "待处理消息",
        "Restore points and branches" => "还原点与分支",
        "Goal status and controls" => "目标状态与控制",
        "Workspace and run context" => "工作区与任务上下文",
        "Choose toolset preset" => "选择工具集预设",
        "Toggle Thinking details" => "展开 / 折叠思考过程",
        "Toggle wide-screen context rail" => "显示 / 隐藏侧栏",
        "Restore responsive context rail" => "按终端宽度自动显示侧栏",
        "Cycle permission mode" => "切换权限模式",
        "Keyboard help" => "快捷键帮助",
        "Language / 语言" => "语言 / Language",
        "System default / 跟随系统" => "跟随系统 / System default",
        "Language preference saved" => "语言偏好已保存",
        "Context rail hidden | use rail-auto to restore responsive mode" => "侧栏已隐藏 | 使用 rail-auto 恢复自动布局",
        "Context rail follows terminal width" => "侧栏将按终端宽度自动显示",
        "Request rejected" => "请求被拒绝",
        "Waiting for you" => "等待你的操作",
        "A tool needs approval" => "有工具需要批准",
        "A plan is ready for review" => "有计划等待审核",
        "Harnex has a question" => "Harnex 有问题需要你回答",
        "Notice" => "提示",
        "Warning" => "警告",
        "Runtime warning" => "运行时警告",
        "Run failed" => "任务失败",
        "Error" => "错误",
        "Provide the required input or adjust the step limit to continue." => "请补充所需输入或调整步数限制后继续。",
        "Run incomplete" => "任务未完成",
        "Agent controls require a project space" => "Agent 操作需要在项目空间中进行",
        "Unknown Ctrl+X chord | D changes | M model | I run after completion" => "未知 Ctrl+X 组合键 | D 变更 | M 模型 | I 完成后执行",
        "This terminal is attached to a fixed Runtime; open standalone `harnex tui --conversation` for personal conversations" => "此终端已绑定运行时；请另开终端运行 `harnex tui --conversation` 进入个人会话",
        "Wait for the submitted message to be accepted before switching" => "请等待消息提交成功后再切换",
        "Cancelling current run..." => "正在取消当前任务...",
        "Input cleared | Ctrl+D exits" => "输入已清空 | Ctrl+D 退出",
        "Stop the active run before starting a new thread" => "请先停止当前任务，再新建会话",
        "Thinking expanded" => "思考过程已展开",
        "Thinking collapsed" => "思考过程已折叠",
        "No thread selected" => "尚未选择会话",
        "Stopping current task | draft kept" => "正在停止当前任务 | 草稿已保留",
        "No active task | Enter sends a new message" => "当前没有运行中的任务 | Enter 发送新消息",
        "Usage: /preview relative/path.html or /preview artifact:asset_id" => "用法：/preview 相对路径.html 或 /preview artifact:附件ID",
        "Preparing browser preview..." => "正在准备浏览器预览...",
        "Sending message..." => "正在发送消息...",
        "Queued | will run after this task completes" => "已排队 | 当前任务完成后执行",
        "Received | waiting to join the current task" => "已收到 | 等待加入当前任务",
        "Loading this run's changes..." => "正在加载本次任务的变更...",
        "No run selected" => "尚未选择任务",
        "Full access enabled | Runtime denylist still applies" => "已启用完全访问 | 运行时禁用列表仍然有效",
        "No pending interactions" => "没有待处理的交互",
        "Only queued or held inputs can be withdrawn" => "只能撤回排队或暂存的输入",
        "No Goal for this thread | type /goal <objective> to create one" => "此会话没有目标 | 输入 /goal <目标描述> 创建",
        "You" => "你",
        "Tool failed" => "工具执行失败",
        "Tool result" => "工具结果",
        "Interaction resolved" => "交互已处理",
        "Run input withdrawn" => "任务输入已撤回",
        "Snapshot restored" => "快照已还原",
        "Preview opened in browser (available for 30 minutes)" => "已在浏览器打开预览（有效期 30 分钟）",
        "Cancellation requested" => "已请求取消",
        "branch response has no conversationId" => "分支响应缺少 conversationId",
        "Runtime identity changed while TUI was attached" => "TUI 连接期间运行时身份发生变化",
        "Opening personal conversations..." => "正在打开个人会话...",
        "Opening project Agent..." => "正在打开项目 Agent...",
        "Terminal UI | starts a new conversation; choose history from Ctrl+P > threads." => "终端界面 | 默认进入新会话；Ctrl+P > threads 选择历史会话。",
        "Language: Ctrl+P > language; --lang overrides HARNEX_LANG, saved preference and system language." => "语言：Ctrl+P > language；优先级为 --lang、HARNEX_LANG、已保存偏好、系统语言。",
        "Reference a skill: pre-inject its body, then your input. No args > list skills." => "引用技能：先载入技能内容，再处理输入；无参数时列出技能。",
        "Enter plan mode: explore read-only, then submit a plan for your approval before executing." => "进入规划模式：先只读探索，提交计划经你批准后执行。",
        "Set an autonomous goal; the agent keeps working (with budget guard rails) until it is met." => "设置自主目标；Agent 在预算限制内持续执行，直到达成目标。",
        "<name> [input]" => "<名称> [输入]",
        "[task] | cancel" => "[任务] | cancel",
        "<objective> [+budget] | clear | pause | resume" => "<目标> [+预算] | clear | pause | resume",
        _ => english,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn locale_precedence_and_system_tags() {
        assert_eq!(
            resolve_language(Some("en"), Some("zh"), Some("zh-CN")).unwrap(),
            Language::English
        );
        assert_eq!(
            resolve_language(None, Some("zh"), Some("en")).unwrap(),
            Language::Chinese
        );
        assert_eq!(
            resolve_language(None, None, Some("zh-CN\n")).unwrap(),
            Language::Chinese
        );
        assert_eq!(
            resolve_language(Some("auto"), Some("en"), Some("zh-CN")).unwrap(),
            Language::Auto
        );
        assert_eq!(
            resolve_language(None, None, Some("invalid")).unwrap(),
            Language::Auto
        );
        assert!(resolve_language(Some("invalid"), None, None).is_err());
        for value in ["zh_CN.UTF-8", "zh-Hans-CN", "zh_TW", "ZH"] {
            assert!(locale_is_chinese(value));
        }
        for value in ["en_US.UTF-8", "C.UTF-8", "POSIX", "de_DE"] {
            assert!(!locale_is_chinese(value));
        }
    }
}
