use crossterm::event::{KeyCode, KeyEvent, KeyModifiers};

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub enum InputKeys {
    #[default]
    EnterSends,
    CtrlEnterSends,
}

impl InputKeys {
    pub const ALL: [Self; 2] = [Self::EnterSends, Self::CtrlEnterSends];

    pub fn id(self) -> &'static str {
        match self {
            Self::EnterSends => "enter-send",
            Self::CtrlEnterSends => "ctrl-enter-send",
        }
    }

    pub fn parse(value: &str) -> Option<Self> {
        Self::ALL.into_iter().find(|mode| mode.id() == value.trim())
    }

    pub fn label(self) -> &'static str {
        match self {
            Self::EnterSends => "Shift+Enter newline | Enter send",
            Self::CtrlEnterSends => "Enter newline | Ctrl+Enter send",
        }
    }

    pub fn send_key(self) -> &'static str {
        match self {
            Self::EnterSends => "Enter",
            Self::CtrlEnterSends => "Ctrl+Enter",
        }
    }

    pub fn footer_hint(self, live: bool) -> &'static str {
        match (self, live) {
            (Self::EnterSends, false) => "Enter send | Shift+Enter newline",
            (Self::EnterSends, true) => "Enter update | Shift+Enter newline | Esc stop",
            (Self::CtrlEnterSends, false) => "Ctrl+Enter send | Enter newline",
            (Self::CtrlEnterSends, true) => "Ctrl+Enter update | Enter newline | Esc stop",
        }
    }

    pub fn compact_hint(self) -> &'static str {
        match self {
            Self::EnterSends => "Enter | Shift+Enter newline",
            Self::CtrlEnterSends => "Ctrl+Enter send | Enter newline",
        }
    }

    pub fn minimal_hint(self) -> &'static str {
        match self {
            Self::EnterSends => "Shift+Enter newline",
            Self::CtrlEnterSends => "Ctrl+Enter send",
        }
    }

    pub fn submit_hint(self) -> &'static str {
        match self {
            Self::EnterSends => "Enter submit | Shift+Enter newline | Esc back",
            Self::CtrlEnterSends => "Ctrl+Enter submit | Enter newline | Esc back",
        }
    }

    pub fn is_send(self, key: KeyEvent) -> bool {
        key.code == KeyCode::Enter
            && key.modifiers
                == match self {
                    Self::EnterSends => KeyModifiers::NONE,
                    Self::CtrlEnterSends => KeyModifiers::CONTROL,
                }
    }

    pub fn is_newline(self, key: KeyEvent) -> bool {
        key.code == KeyCode::Enter
            && key.modifiers
                == match self {
                    Self::EnterSends => KeyModifiers::SHIFT,
                    Self::CtrlEnterSends => KeyModifiers::NONE,
                }
    }

    pub fn load() -> Self {
        std::fs::read_to_string(super::local::user_directory().join("tui-input-keys"))
            .ok()
            .and_then(|value| Self::parse(&value))
            .unwrap_or_default()
    }

    pub fn save(self) -> std::io::Result<()> {
        let directory = super::local::user_directory();
        std::fs::create_dir_all(&directory)?;
        std::fs::write(directory.join("tui-input-keys"), self.id())
    }
}

pub const ROWS: &[(&str, &str)] = &[
    ("F1", "shortcuts from any screen"),
    ("Ctrl+X → Q", "exit terminal and keep draft"),
    ("Ctrl+X → C", "clear draft without stopping task"),
    ("Ctrl+Z / Ctrl+_", "undo input edit or clear"),
    ("Ctrl+Shift+Z", "redo input edit"),
    ("Ctrl+A / Ctrl+E", "start / end of input line"),
    ("Ctrl+U / Ctrl+K", "delete to line start / end"),
    ("Ctrl+W", "delete previous word"),
    ("Ctrl+Y", "restore deleted text"),
    ("Ctrl+Left / Right", "move by word"),
    ("Up / Down", "move in draft; recall history at edges"),
    ("Ctrl+R", "search input history; restore to draft"),
    ("Ctrl+X → V", "preview the entire draft"),
    ("Ctrl+X → E", "edit draft in external editor"),
    ("Ctrl+X → Y", "copy the last answer"),
    ("Ctrl+P > copy-turn", "copy received output including tools"),
    (
        "Ctrl+P > export",
        "export conversation messages as Markdown",
    ),
    ("Enter (menus)", "confirm a menu choice or approval"),
    ("Ctrl+P > input-keys", "choose newline and send keys"),
    ("Ctrl+C", "cancel task / clear idle draft"),
    ("Ctrl+D", "delete character / exit empty draft"),
    ("Ctrl+P", "command palette"),
    ("F2 / Ctrl+X → M", "choose model without clearing draft"),
    ("F3 / Ctrl+X → R", "reasoning effort for the next task"),
    (
        "Ctrl+P > effort",
        "choose from the selected model's supported levels",
    ),
    ("Ctrl+P > settings", "theme, language and display settings"),
    ("Shift+F2", "cycle recent models"),
    ("Ctrl+F (models)", "toggle model favorite"),
    ("Shift+Tab", "permission mode (Agent)"),
    ("F6", "switch Conversation / Agent (standalone terminal)"),
    ("Ctrl+X → K", "skill picker"),
    ("Ctrl+X → U", "toolset picker"),
    (
        "Alt+Enter",
        "send once after completion | Ctrl+X → I fallback",
    ),
    ("Ctrl+N", "new thread"),
    ("Ctrl+O", "toggle Thinking details"),
    ("Ctrl+X → D", "this run's changes"),
    ("Ctrl+I", "run input mailbox"),
    ("Ctrl+G", "goal status and controls"),
    ("PgUp/PgDn", "scroll transcript"),
    ("Mouse wheel", "scroll transcript"),
    ("Left drag", "select visible text"),
    ("Right-click / Ctrl+C", "copy selection"),
    (
        "Right-click",
        "paste clipboard into draft when nothing is selected",
    ),
    ("Ctrl+Home/End", "first / latest output"),
    (
        "Esc",
        "close overlay first; otherwise stop task and keep draft",
    ),
    ("/preview path", "open a project file in browser"),
    (
        "/preview artifact:id",
        "open a conversation attachment in browser",
    ),
    ("Ctrl+P > language", "Language / 语言"),
    ("? (empty draft)", "this help"),
];
