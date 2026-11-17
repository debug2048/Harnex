pub use super::editor::Composer;
use super::editor::InputHistory;
use super::i18n::tf;
use super::scroll::TranscriptScroll;
use super::shortcuts::InputKeys;
use std::cell::Cell;
use std::time::{Duration, Instant};

use crossterm::event::{KeyCode, KeyEvent, KeyModifiers, MouseEvent};
use serde_json::{json, Value};
use unicode_segmentation::UnicodeSegmentation;

use super::api::{
    ApiError, ChangesView, CommandView, DiffView, FileSearchHit, GoalView, InteractionView,
    MessageView, ModelOption, RestorePlanView, RunInputView, RunView, SkillView, SnapshotView,
    StreamEvent, ThreadSummary, ThreadView, TodoView, ToolsetOption, UsageView, WorkspaceView,
};

const MAX_TIMELINE_ITEMS: usize = 1_000;
pub const RAIL_MIN_WIDTH: u16 = 112;

#[derive(Clone, Debug, PartialEq, Eq)]
pub enum TimelineKind {
    User,
    Assistant,
    Thinking,
    Tool,
    Notice,
    Warning,
    Error,
}

#[derive(Clone, Debug)]
pub struct TimelineItem {
    pub kind: TimelineKind,
    pub title: String,
    pub content: String,
    pub purpose: Option<String>,
    pub tool_input: Option<(String, String)>,
    pub queued: bool,
    pub sequence: u64,
    pub content_complete: bool,
    pub call_id: Option<String>,
    pub running: bool,
    pub failed: bool,
}

impl TimelineItem {
    fn message(kind: TimelineKind, title: impl Into<String>, content: impl Into<String>) -> Self {
        Self {
            kind,
            title: title.into(),
            content: content.into(),
            purpose: None,
            tool_input: None,
            queued: false,
            sequence: 0,
            content_complete: false,
            call_id: None,
            running: false,
            failed: false,
        }
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum CommandGroup {
    Conversation,
    Task,
    Display,
}

impl CommandGroup {
    pub fn title(&self) -> &'static str {
        match self {
            Self::Conversation => "Current conversation",
            Self::Task => "Current task",
            Self::Display => "Settings",
        }
    }
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub enum PanelKind {
    Thread,
    Changes,
    Inputs,
    Snapshots,
    Restore,
    Goal,
    Todos,
}

#[derive(Clone, Copy, Debug)]
pub enum PickerKind {
    Language,
    Themes,
    Commands,
    CommandGroup(CommandGroup),
    Permissions,
    Threads,
    Files,
    Models,
    Efforts,
    InputKeys,
    Skills,
    Toolsets,
    Slash,
    InputHistory,
}

#[derive(Clone, Debug)]
pub struct PickerState {
    pub kind: PickerKind,
    pub query: String,
    pub selected: usize,
}

#[derive(Clone, Debug)]
pub struct InteractionState {
    pub scroll: u16,
    pub interaction: InteractionView,
    pub input: Composer,
    pub rejecting: bool,
}

#[derive(Clone, Debug)]
pub struct ChangesState {
    pub view: ChangesView,
    pub selected: usize,
    pub diff: Option<DiffView>,
    pub scroll: u16,
}

#[derive(Clone, Debug)]
pub struct RunInputsState {
    pub inputs: Vec<RunInputView>,
    pub selected: usize,
}

#[derive(Clone, Debug)]
pub struct SnapshotsState {
    pub snapshots: Vec<SnapshotView>,
    pub selected: usize,
}

#[derive(Clone, Debug)]
pub struct RestoreState {
    pub snapshot: SnapshotView,
    pub plan: RestorePlanView,
}

#[derive(Clone, Debug)]
pub struct GoalState {
    pub goal: GoalView,
    pub feedback: Composer,
    pub reviewing: bool,
}

#[derive(Clone, Debug)]
pub struct TextView {
    pub title: String,
    pub text: String,
    pub scroll: TranscriptScroll,
    pub draft: bool,
}

#[derive(Clone, Debug)]
pub enum Overlay {
    Loading(PanelKind),
    Help,
    Picker(PickerState),
    Interaction(InteractionState),
    Changes(ChangesState),
    RunInputs(RunInputsState),
    Snapshots(SnapshotsState),
    Restore(RestoreState),
    Goal(GoalState),
    Todos(Vec<TodoView>),
    Context,
    ConfirmYolo,
    ConfirmExit,
    Text(TextView),
}

#[derive(Clone, Debug)]
pub enum Effect {
    PasteClipboard,
    Copy {
        text: String,
        label: &'static str,
    },
    ExportConversation {
        thread_id: String,
        title: String,
    },
    EditDraft,
    SetTheme(String),
    SetLanguage(super::i18n::Language),
    SetInputKeys(InputKeys),
    Preview(String),
    CreateRun {
        request_id: String,
        thread_id: Option<String>,
        input: String,
        permission_mode: String,
        provider: Option<String>,
        model: Option<String>,
        reasoning_effort: String,
        toolsets: Vec<String>,
    },
    EnqueueInput {
        request_id: String,
        run_id: String,
        input: String,
        mode: String,
    },
    CancelRun(String),
    LoadThread(String),
    LoadThreads,
    ResolveInteraction {
        interaction: InteractionView,
        body: Value,
    },
    LoadChanges(String),
    LoadDiff {
        run_id: String,
        path: String,
    },
    SearchFiles(String),
    LoadModels,
    LoadRunInputs(String),
    WithdrawInput {
        run_id: String,
        input_id: String,
    },
    LoadSnapshots(String),
    LoadRestorePlan {
        thread_id: String,
        snapshot: SnapshotView,
        mode: String,
    },
    RestoreSnapshot {
        thread_id: String,
        seq: u64,
        mode: String,
        history_revision: Option<u64>,
        command_id: String,
    },
    BranchSnapshot {
        thread_id: String,
        seq: u64,
    },
    LoadGoal(String),
    GoalControl {
        run: RunView,
        action: String,
    },
    GoalReview {
        run: RunView,
        decision: String,
        feedback: Option<String>,
    },
    LoadTodos(String),
    LoadSkills,
    LoadToolsets,
    LoadCommands,
    SetPermissionMode {
        thread_id: String,
        mode: String,
    },
    Redraw,
    Quit,
}

#[derive(Clone, Debug, Default)]
pub struct StreamDirective {
    pub refresh_interactions: bool,
    pub terminal: bool,
    pub refresh_inputs: bool,
    pub refresh_goal: bool,
}

pub struct App {
    pub input_keys: InputKeys,
    pub mouse: super::mouse::MouseState,
    pub theme_name: String,
    pub agent_available: bool,
    pub workspace: WorkspaceView,
    pub workspace_git: super::api::WorkspaceGitView,
    pub threads: Vec<ThreadSummary>,
    pub thread_id: Option<String>,
    pub thread_title: String,
    pub timeline: Vec<TimelineItem>,
    pub composer: Composer,
    pub editor_width: Cell<usize>,
    pub input_history: InputHistory,
    pub recent_models: Vec<String>,
    pub favorite_models: Vec<String>,
    pub edit_requested: bool,
    pub permission_mode: String,
    pending_run_input: Option<(String, String, String)>,
    pending_create: Option<(String, String)>,
    pub run: Option<RunView>,
    pub interactions: Vec<InteractionView>,
    dismissed_interactions: Vec<String>,
    pub file_results: Vec<FileSearchHit>,
    pub models: Vec<ModelOption>,
    pub models_loading: bool,
    pub models_ready: bool,
    pub skills: Vec<SkillView>,
    pub goal: Option<GoalView>,
    pub selected_provider: Option<String>,
    pub selected_model: Option<String>,
    pub selected_effort: String,
    pub toolsets: Vec<ToolsetOption>,
    pub selected_toolsets: Vec<String>,
    pub commands: Vec<CommandView>,
    pub overlay: Option<Overlay>,
    pub navigation: Vec<Overlay>,
    pub help_scroll: TranscriptScroll,
    pub details_open: bool,
    pub rail_auto: bool,
    pub show_thinking: bool,
    pub scroll: TranscriptScroll,
    pub unread: u64,
    stream_run_id: Option<String>,
    stream_epoch: Option<u64>,
    last_applied_stream_seq: u64,
    pub notice: Option<(String, Instant)>,
    pub leader: Option<Instant>,
    pub should_quit: bool,
    pub attached: bool,
    pub can_switch_scope: bool,
    pub switch_scope: bool,
    pub background_active: bool,
}

impl App {
    pub fn memory(&self) -> super::local::Memory {
        super::local::Memory {
            draft: self.composer.text.clone(),
            history: self.input_history.entries.clone(),
            recent_models: self.recent_models.clone(),
            favorite_models: self.favorite_models.clone(),
            model_selection: Some(self.model_selection()),
        }
    }

    pub fn restore_memory(&mut self, memory: super::local::Memory, restore_draft: bool) {
        if restore_draft && self.thread_id.is_none() {
            if let Some(selection) = memory.model_selection {
                self.restore_model_selection(selection);
            }
        }
        if restore_draft && !memory.draft.is_empty() {
            let cursor = memory.draft.len();
            self.composer.load(memory.draft, cursor);
            self.push_notice("Draft restored | F1 shortcuts");
        }
        for entry in memory.history {
            self.input_history.record(&entry);
        }
        self.recent_models = memory.recent_models.into_iter().take(12).collect();
        self.favorite_models = memory.favorite_models.into_iter().take(100).collect();
    }

    fn request_quit(&mut self) -> Vec<Effect> {
        self.leader = None;
        if self.live_run_id().is_some() && !self.attached
            || self.background_active
            || self.submitting_input()
        {
            if !matches!(self.overlay, Some(Overlay::ConfirmExit)) {
                if let Some(overlay) = self.overlay.take() {
                    self.navigation.push(overlay);
                }
            }
            self.overlay = Some(Overlay::ConfirmExit);
            Vec::new()
        } else {
            vec![Effect::Quit]
        }
    }

    fn open_help(&mut self) {
        if matches!(self.overlay, Some(Overlay::Help)) {
            self.back();
        } else {
            if let Some(overlay) = self.overlay.take() {
                self.navigation.push(overlay);
            }
            self.help_scroll.first();
            self.overlay = Some(Overlay::Help);
        }
    }

    pub fn open_text(&mut self, title: String, text: String, draft: bool) {
        if let Some(overlay) = self.overlay.take() {
            self.navigation.push(overlay);
        }
        let scroll = TranscriptScroll::default();
        scroll.first();
        self.overlay = Some(Overlay::Text(TextView {
            title,
            text,
            scroll,
            draft,
        }));
    }

    fn preview_draft(&mut self) {
        self.open_text(
            super::i18n::text("Draft preview").into(),
            self.composer.text.clone(),
            true,
        );
    }

    fn clear_draft(&mut self) {
        self.composer.clear();
        self.input_history.detach();
        self.push_notice("Draft cleared | Ctrl+Z undo | Ctrl+X → Q exit");
    }

    fn choose_model(&mut self, provider: String, model: String, remember: bool) {
        let id = format!("{provider}:{model}");
        self.selected_provider = Some(provider.clone());
        self.selected_model = Some(model.clone());
        let reset_effort = self.reconcile_effort();
        if remember {
            self.recent_models.retain(|entry| entry != &id);
            self.recent_models.insert(0, id);
            self.recent_models.truncate(12);
        }
        self.push_notice(tf!(
            "Next task: {provider}:{model} | effort: {}{}",
            "下次任务：{provider}:{model} | 推理强度：{}{}",
            self.selected_effort,
            if reset_effort {
                super::i18n::text(" (unsupported level reset to auto)")
            } else {
                ""
            }
        ));
    }

    fn model_selection(&self) -> super::local::ModelSelection {
        super::local::ModelSelection {
            provider: self.selected_provider.clone(),
            model: self.selected_model.clone(),
            effort: self.selected_effort.clone(),
        }
    }

    fn restore_model_selection(&mut self, selection: super::local::ModelSelection) {
        self.selected_provider = selection.provider;
        self.selected_model = selection.model;
        self.selected_effort = if selection.effort.is_empty() {
            "auto".into()
        } else {
            selection.effort
        };
    }

    pub fn selected_catalog_model(&self) -> Option<&ModelOption> {
        if self.selected_provider.is_some() || self.selected_model.is_some() {
            self.models.iter().find(|item| {
                self.selected_provider
                    .as_deref()
                    .is_some_and(|provider| item.provider.eq_ignore_ascii_case(provider))
                    && self.selected_model.as_deref() == Some(item.model.as_str())
            })
        } else {
            self.models
                .iter()
                .find(|item| item.default)
                .or_else(|| self.models.first())
        }
    }

    pub fn effort_choices(&self) -> Vec<String> {
        let Some(model) = self.selected_catalog_model() else {
            return Vec::new();
        };
        if self.agent_available {
            if let Some(required) = &model.function_tool_reasoning_effort {
                return model
                    .reasoning_efforts
                    .iter()
                    .filter(|value| *value == required)
                    .cloned()
                    .collect();
            }
        }
        model.reasoning_efforts.clone()
    }

    fn reconcile_effort(&mut self) -> bool {
        if self.models_ready
            && self.selected_effort != "auto"
            && !self.effort_choices().contains(&self.selected_effort)
        {
            self.selected_effort = "auto".into();
            return true;
        }
        if self.models_ready && self.selected_effort != "auto" {
            if let Some(model) = self.selected_catalog_model().cloned() {
                self.selected_provider = Some(model.provider);
                self.selected_model = Some(model.model);
            }
        }
        false
    }

    pub fn set_models(&mut self, models: Vec<ModelOption>) {
        self.models = models;
        self.models_loading = false;
        self.models_ready = true;
        if self.reconcile_effort() {
            self.push_notice("Model capabilities changed; effort reset to auto");
        }
    }

    fn open_efforts(&mut self) -> Vec<Effect> {
        if let Some(overlay) = self.overlay.take() {
            self.navigation.push(overlay);
        }
        self.overlay = Some(Overlay::Picker(PickerState {
            kind: PickerKind::Efforts,
            query: String::new(),
            selected: 0,
        }));
        self.models_loading = true;
        vec![Effect::LoadModels]
    }

    fn cycle_recent_model(&mut self) -> Vec<Effect> {
        if self.recent_models.is_empty() {
            return self.open_models();
        }
        let current = format!(
            "{}:{}",
            self.selected_provider.as_deref().unwrap_or(""),
            self.selected_model.as_deref().unwrap_or("")
        );
        let next = self
            .recent_models
            .iter()
            .position(|entry| entry == &current)
            .map(|at| (at + 1) % self.recent_models.len())
            .unwrap_or(0);
        let id = self.recent_models[next].clone();
        if let Some((provider, model)) = id.split_once(':') {
            self.choose_model(provider.into(), model.into(), false);
        }
        Vec::new()
    }

    fn copy_answer(&mut self) -> Vec<Effect> {
        let text = self
            .timeline
            .iter()
            .rev()
            .find(|item| item.kind == TimelineKind::Assistant && !item.content.is_empty())
            .map(|item| item.content.clone());
        match text {
            Some(text) => vec![Effect::Copy {
                text,
                label: "Answer",
            }],
            None => {
                self.push_notice("No answer to copy yet");
                Vec::new()
            }
        }
    }

    fn copy_turn(&mut self) -> Vec<Effect> {
        let start = self
            .timeline
            .iter()
            .rposition(|item| item.kind == TimelineKind::User)
            .map(|at| at + 1)
            .unwrap_or(0);
        let mut text = String::new();
        for item in self.timeline.iter().skip(start).filter(|item| {
            matches!(
                item.kind,
                TimelineKind::Assistant
                    | TimelineKind::Thinking
                    | TimelineKind::Tool
                    | TimelineKind::Error
            )
        }) {
            text.push_str(&format!("## {}\n\n", item.title));
            if let Some(purpose) = &item.purpose {
                text.push_str(&format!("{purpose}\n\n"));
            }
            if let Some((label, input)) = &item.tool_input {
                text.push_str(&format!("{}: {input}\n\n", super::i18n::text(label)));
            }
            text.push_str(&item.content);
            text.push_str("\n\n");
        }
        if text.is_empty() {
            self.push_notice("No output to copy yet");
            Vec::new()
        } else {
            vec![Effect::Copy {
                text,
                label: "Received turn output",
            }]
        }
    }

    fn open_history(&mut self) -> Vec<Effect> {
        self.overlay = Some(Overlay::Picker(PickerState {
            kind: PickerKind::InputHistory,
            query: String::new(),
            selected: 0,
        }));
        Vec::new()
    }
    pub fn back(&mut self) {
        self.overlay = self.navigation.pop();
    }

    pub fn close_navigation(&mut self) {
        self.overlay = None;
        self.navigation.clear();
    }

    fn child_picker(&mut self, parent: &PickerState, kind: PickerKind) {
        self.navigation.push(Overlay::Picker(parent.clone()));
        self.overlay = Some(Overlay::Picker(PickerState {
            kind,
            query: String::new(),
            selected: 0,
        }));
    }

    pub fn expects_panel(&self, kind: PanelKind) -> bool {
        match &self.overlay {
            Some(Overlay::Loading(expected)) => *expected == kind,
            Some(Overlay::Changes(_)) => kind == PanelKind::Changes,
            Some(Overlay::RunInputs(_)) => kind == PanelKind::Inputs,
            Some(Overlay::Snapshots(_)) => kind == PanelKind::Snapshots,
            Some(Overlay::Restore(_)) => kind == PanelKind::Restore,
            Some(Overlay::Goal(_)) => kind == PanelKind::Goal,
            Some(Overlay::Todos(_)) => kind == PanelKind::Todos,
            _ => false,
        }
    }

    pub fn panel_error(&mut self, kind: PanelKind, message: String) {
        if matches!(&self.overlay, Some(Overlay::Loading(expected)) if *expected == kind) {
            self.back();
        }
        self.push_notice(message);
    }

    pub fn command_reason(&self, id: &str) -> Option<&'static str> {
        if matches!(id, "new" | "threads") {
            if self.submitting_input() {
                return Some("Wait for the message to be accepted");
            }
            if self.live_run_id().is_some() {
                return Some("Stop the current task before switching conversations");
            }
        }
        if matches!(id, "snapshots" | "goal" | "todos") && self.thread_id.is_none() {
            return Some("Send a message or select a conversation first");
        }
        if id == "export" && self.thread_id.is_none() {
            return Some("Send a message or select a conversation first");
        }
        if matches!(id, "task" | "changes" | "inputs") && self.run.is_none() {
            return Some("No task in this conversation yet");
        }
        if id == "interactions" && self.interactions.is_empty() {
            return Some("No pending interactions");
        }
        if matches!(id, "stop" | "input") && self.live_run_id().is_none() {
            return Some("No task is running");
        }
        if id == "input" && self.composer.text.trim().is_empty() {
            return Some("Write a draft before queuing it");
        }
        None
    }

    fn command_entries(&self, group: Option<CommandGroup>, query: &str) -> Vec<(String, String)> {
        use CommandGroup::{Conversation, Display, Task};
        [
            ("new", "New thread", None),
            ("threads", "Switch thread", None),
            ("conversation", "Current conversation", None),
            ("task", "Current task", None),
            ("display", "Settings", None),
            ("help", "Keyboard help", None),
            ("quit", "Exit terminal (keep draft)", None),
            ("clear-input", "Clear draft (undo available)", None),
            ("history", "Search input history", None),
            ("preview-input", "Preview full draft", None),
            ("edit-input", "Edit draft in external editor", None),
            ("copy", "Copy last answer", None),
            ("models", "Conversation model", Some(Conversation)),
            ("effort", "Reasoning effort", Some(Conversation)),
            ("mode", "Permission mode", Some(Conversation)),
            ("toolsets", "Choose toolset preset", Some(Conversation)),
            ("skills", "Insert a skill reference", Some(Conversation)),
            (
                "snapshots",
                "Restore points and branches",
                Some(Conversation),
            ),
            ("goal", "Goal status and controls", Some(Conversation)),
            ("todos", "Todo progress", Some(Conversation)),
            ("context", "Workspace and run context", Some(Conversation)),
            (
                "export",
                "Export conversation messages to Markdown",
                Some(Conversation),
            ),
            ("inputs", "Pending messages", Some(Task)),
            (
                "input",
                "Send draft after the current task completes",
                Some(Task),
            ),
            ("changes", "This run's changes", Some(Task)),
            (
                "interactions",
                "Pending approvals and questions",
                Some(Task),
            ),
            ("stop", "Stop current task", Some(Task)),
            (
                "copy-turn",
                "Copy received turn output (including tools)",
                Some(Task),
            ),
            ("language", "Language / 语言", Some(Display)),
            ("theme", "Theme", Some(Display)),
            ("input-keys", "Newline and send keys", Some(Display)),
            ("details", "Toggle Thinking details", Some(Display)),
            ("rail", "Toggle wide-screen context rail", Some(Display)),
            (
                "rail-auto",
                "Restore responsive context rail",
                Some(Display),
            ),
        ]
        .into_iter()
        .filter(|(_, _, scope)| {
            group.map_or(!query.is_empty() || scope.is_none(), |group| {
                *scope == Some(group)
            })
        })
        .filter(|(id, _, _)| {
            self.agent_available
                || ![
                    "mode",
                    "toolsets",
                    "skills",
                    "snapshots",
                    "goal",
                    "todos",
                    "changes",
                    "interactions",
                ]
                .contains(id)
        })
        .filter(|(id, label, _)| {
            query.is_empty()
                || format!(
                    "{id} {label} {} {}",
                    super::i18n::text(label),
                    if *id == "display" {
                        "settings preferences 设置 界面设置"
                    } else if *id == "effort" {
                        "reasoning thinking 推理 强度"
                    } else if *id == "input-keys" {
                        "keyboard shortcuts newline send 换行 发送 快捷键"
                    } else {
                        ""
                    }
                )
                .to_lowercase()
                .contains(query)
        })
        .map(|(id, label, scope)| {
            let label = super::i18n::text(label);
            let label = if group.is_none() && !query.is_empty() {
                scope
                    .map(|group| format!("{} > {label}", super::i18n::text(group.title())))
                    .unwrap_or_else(|| label.to_string())
            } else {
                label.to_string()
            };
            (id.to_string(), label)
        })
        .collect()
    }

    pub fn new(
        workspace: WorkspaceView,
        threads: Vec<ThreadSummary>,
        thread: Option<ThreadView>,
        run: Option<RunView>,
        attached: bool,
    ) -> Self {
        let mut app = Self {
            theme_name: "auto".into(),
            agent_available: true,
            workspace,
            workspace_git: super::api::WorkspaceGitView::default(),
            threads,
            thread_id: None,
            thread_title: String::new(),
            timeline: Vec::new(),
            composer: Composer::default(),
            editor_width: Cell::new(73),
            input_keys: InputKeys::default(),
            mouse: super::mouse::MouseState::default(),
            input_history: InputHistory::default(),
            recent_models: Vec::new(),
            favorite_models: Vec::new(),
            edit_requested: false,
            permission_mode: "ask".to_string(),
            pending_run_input: None,
            pending_create: None,
            run,
            interactions: Vec::new(),
            dismissed_interactions: Vec::new(),
            file_results: Vec::new(),
            models: Vec::new(),
            models_loading: false,
            models_ready: false,
            skills: Vec::new(),
            goal: None,
            selected_provider: None,
            selected_model: None,
            selected_effort: "auto".into(),
            toolsets: Vec::new(),
            selected_toolsets: Vec::new(),
            commands: Vec::new(),
            overlay: None,
            navigation: Vec::new(),
            help_scroll: TranscriptScroll::default(),
            details_open: false,
            rail_auto: true,
            show_thinking: false,
            scroll: TranscriptScroll::default(),
            unread: 0,
            stream_run_id: None,
            stream_epoch: None,
            last_applied_stream_seq: 0,
            notice: None,
            leader: None,
            should_quit: false,
            attached,
            can_switch_scope: !attached,
            switch_scope: false,
            background_active: false,
        };
        if let Some(thread) = thread {
            app.set_thread(thread);
        }
        app
    }

    pub fn set_thread(&mut self, thread: ThreadView) {
        if self.thread_id.as_deref() != Some(thread.id.as_str()) {
            self.pending_run_input = None;
        }
        self.thread_id = Some(thread.id.clone());
        self.thread_title = thread.title.clone();
        self.permission_mode = if thread.state.permission_mode.is_empty() {
            "ask".to_string()
        } else {
            thread.state.permission_mode.clone()
        };
        self.selected_provider = thread.provider.clone();
        self.selected_model = thread.model.clone();
        self.selected_effort = thread.reasoning_effort.unwrap_or_else(|| "auto".into());
        self.reconcile_effort();
        self.selected_toolsets = thread.toolsets.clone();
        self.timeline.clear();
        let messages = thread.messages;
        let skip = messages.len().saturating_sub(MAX_TIMELINE_ITEMS);
        for message in messages.into_iter().skip(skip) {
            self.append_archived_message(message);
        }
        self.scroll = TranscriptScroll::default();
        self.unread = 0;
    }

    fn append_archived_message(&mut self, message: MessageView) {
        let shown = message.original_input.unwrap_or(message.content);
        if shown.is_empty() && message.reasoning.as_deref().unwrap_or("").is_empty() {
            return;
        }
        match message.role.as_str() {
            "user" => self.timeline.push(TimelineItem::message(
                TimelineKind::User,
                user_title(message.channel.as_deref()),
                shown,
            )),
            "assistant" if message.reasoning_block => self.timeline.push(TimelineItem::message(
                TimelineKind::Thinking,
                "Thinking",
                message.reasoning.unwrap_or(shown),
            )),
            "assistant" => self.timeline.push(TimelineItem::message(
                TimelineKind::Assistant,
                "Harnex",
                shown,
            )),
            "tool" => self.timeline.push(TimelineItem {
                kind: TimelineKind::Tool,
                title: if message.tool_error == Some(true) {
                    "Tool failed".to_string()
                } else {
                    "Tool result".to_string()
                },
                content: shown,
                purpose: None,
                tool_input: None,
                queued: false,
                sequence: 0,
                content_complete: false,
                call_id: None,
                running: false,
                failed: message.tool_error == Some(true),
            }),
            _ => {}
        }
        self.reset_stream_cursor();
        self.prune_timeline();
    }

    pub fn new_thread(&mut self) {
        self.navigation.clear();
        self.pending_create = None;
        self.thread_id = None;
        self.thread_title.clear();
        self.timeline.clear();
        self.run = None;
        self.interactions.clear();
        self.dismissed_interactions.clear();
        self.overlay = None;
        self.permission_mode = "ask".to_string();
        self.pending_run_input = None;
        self.selected_provider = None;
        self.selected_model = None;
        self.selected_effort = "auto".into();
        self.selected_toolsets.clear();
        self.goal = None;
        self.scroll = TranscriptScroll::default();
        self.unread = 0;
        self.reset_stream_cursor();
        self.composer.clear();
    }

    pub fn live_run_id(&self) -> Option<&str> {
        self.run
            .as_ref()
            .filter(|run| run.is_live())
            .map(|run| run.run_id.as_str())
    }

    pub fn thread_label(&self) -> &str {
        if self.thread_title.is_empty() {
            super::i18n::text("New thread")
        } else {
            &self.thread_title
        }
    }

    pub fn run_status(&self) -> &str {
        self.run
            .as_ref()
            .map(|run| {
                if run.is_live()
                    && run.executing
                    && matches!(run.status.as_str(), "CREATED" | "QUEUED" | "STARTING")
                {
                    "RUNNING"
                } else {
                    run.status.as_str()
                }
            })
            .unwrap_or("READY")
    }

    pub fn actual_model(&self) -> String {
        if let Some(run) = self.run.as_ref() {
            return [
                run.actual_model.as_deref(),
                run.requested_model.as_deref(),
                run.execution_spec["text"]["model"].as_str(),
            ]
            .into_iter()
            .flatten()
            .find(|model| !model.trim().is_empty())
            .unwrap_or(super::i18n::text("Awaiting model"))
            .to_string();
        }
        self.selected_model
            .clone()
            .or_else(|| {
                self.thread_id.as_ref().and_then(|id| {
                    self.threads
                        .iter()
                        .find(|thread| &thread.id == id)
                        .and_then(|thread| thread.model.clone())
                })
            })
            .unwrap_or_else(|| super::i18n::text("auto").to_string())
    }

    pub fn usage(&self) -> Option<&UsageView> {
        self.run.as_ref().and_then(|run| run.usage.as_ref())
    }

    pub fn push_notice(&mut self, message: impl Into<String>) {
        self.notice = Some((message.into(), Instant::now()));
    }

    pub fn sync_rail_width(&mut self, width: u16) {
        if self.rail_auto {
            self.details_open = width >= RAIL_MIN_WIDTH;
        }
    }

    fn toggle_rail(&mut self) {
        self.rail_auto = false;
        self.details_open = !self.details_open;
        self.push_notice(if self.details_open {
            tf!(
                "Context rail shown at {RAIL_MIN_WIDTH}+ columns",
                "侧栏已显示（建议至少 {RAIL_MIN_WIDTH} 列）"
            )
        } else {
            "Context rail hidden | use rail-auto to restore responsive mode".to_string()
        });
    }

    fn auto_rail(&mut self) {
        self.rail_auto = true;
        self.push_notice("Context rail follows terminal width");
    }

    pub fn run_rejected(&mut self, message: impl Into<String>) {
        self.timeline.push(TimelineItem::message(
            TimelineKind::Error,
            "Request rejected",
            message.into(),
        ));
    }

    pub fn expire_notice(&mut self) -> bool {
        let mut changed = false;
        if self
            .notice
            .as_ref()
            .is_some_and(|(_, at)| at.elapsed() > Duration::from_secs(5))
        {
            self.notice = None;
            changed = true;
        }
        if self
            .leader
            .is_some_and(|at| at.elapsed() > Duration::from_secs(2))
        {
            self.leader = None;
            changed = true;
        }
        changed
    }

    pub fn set_interactions(&mut self, interactions: Vec<InteractionView>) {
        self.interactions = interactions;
        self.dismissed_interactions
            .retain(|id| self.interactions.iter().any(|item| &item.call_id == id));
        if let Some(Overlay::Interaction(state)) = self.overlay.as_ref() {
            if !self.interactions.iter().any(|item| {
                item.call_id == state.interaction.call_id
                    && item.thread_id == state.interaction.thread_id
            }) {
                self.back();
            }
        }
        if self.overlay.is_none() {
            if let Some(interaction) = self
                .interactions
                .iter()
                .find(|item| !self.dismissed_interactions.contains(&item.call_id))
                .cloned()
            {
                self.overlay = Some(Overlay::Interaction(InteractionState {
                    scroll: 0,
                    interaction,
                    input: Composer::default(),
                    rejecting: false,
                }));
            }
        }
    }

    pub fn apply_stream(&mut self, event: StreamEvent) -> StreamDirective {
        if let Some(run_id) = event.run_id.as_deref() {
            if self.run.as_ref().is_some_and(|run| run.run_id != run_id) {
                return StreamDirective::default();
            }
        }
        if let Some(thread_id) = event.thread_id.as_deref() {
            if self.thread_id.as_deref().is_some_and(|id| id != thread_id) {
                return StreamDirective::default();
            }
        }
        if event.seq > 0 {
            let event_run_id = event
                .run_id
                .clone()
                .or_else(|| self.run.as_ref().map(|run| run.run_id.clone()))
                .unwrap_or_default();
            let same_stream = self.stream_run_id.as_deref() == Some(event_run_id.as_str())
                && self.stream_epoch == event.stream_epoch;
            if same_stream && event.seq <= self.last_applied_stream_seq {
                return StreamDirective::default();
            }
            if !same_stream {
                self.stream_run_id = Some(event_run_id);
                self.stream_epoch = event.stream_epoch;
                self.last_applied_stream_seq = 0;
            }
            self.last_applied_stream_seq = event.seq;
        }
        if let Some(run) = self.run.as_mut() {
            run.last_seq = run.last_seq.max(event.seq);
        }
        let mut directive = StreamDirective::default();
        // Ordinary runs need not emit run_started. Actual model/tool activity
        // also advances a captured admission snapshot out of QUEUED.
        if matches!(
            event.kind.as_str(),
            "message_start"
                | "text_delta"
                | "thinking_start"
                | "thinking_delta"
                | "tool_use_queued"
                | "tool_use_start"
                | "tool_use_end"
        ) {
            if self.run.as_ref().is_some_and(|run| !run.is_live()) {
                return directive;
            }
            self.mark_run_executing();
        }
        match event.kind.as_str() {
            "run_started" => {
                if let Some(run) = self.run.as_mut() {
                    if run.is_live() {
                        run.status = "RUNNING".to_string();
                        run.executing = true;
                    }
                }
            }
            "message_start" => {
                self.finish_model_items();
                if let Some(run) = self.run.as_mut() {
                    run.actual_provider =
                        string_field(&event.data, "provider").or(run.actual_provider.take());
                    run.actual_model =
                        string_field(&event.data, "model").or(run.actual_model.take());
                }
            }
            "text_delta" => {
                let text = event.data.get("text").and_then(Value::as_str).unwrap_or("");
                if text.is_empty() {
                    return directive;
                }
                for item in &mut self.timeline {
                    if item.kind == TimelineKind::Thinking {
                        item.running = false;
                    }
                }
                if let Some(item) = self
                    .timeline
                    .last_mut()
                    .filter(|item| item.kind == TimelineKind::Assistant && item.running)
                {
                    item.content.push_str(text);
                } else {
                    let mut item = TimelineItem::message(TimelineKind::Assistant, "Harnex", text);
                    item.running = true;
                    self.timeline.push(item);
                }
                self.on_new_content();
            }
            "thinking_start" => {
                self.finish_model_items();
                let mut item = TimelineItem::message(TimelineKind::Thinking, "Thinking", "");
                item.running = true;
                self.timeline.push(item);
                self.on_new_content();
            }
            "thinking_delta" => {
                let text = event.data.get("text").and_then(Value::as_str).unwrap_or("");
                if let Some(item) = self
                    .timeline
                    .iter_mut()
                    .rev()
                    .find(|item| item.kind == TimelineKind::Thinking && item.running)
                {
                    item.content.push_str(text);
                } else if !text.is_empty() {
                    self.finish_model_items();
                    let mut item = TimelineItem::message(TimelineKind::Thinking, "Thinking", text);
                    item.running = true;
                    self.timeline.push(item);
                    self.on_new_content();
                }
            }
            "thinking_end" => {
                if let Some(item) = self
                    .timeline
                    .iter_mut()
                    .rev()
                    .find(|item| item.kind == TimelineKind::Thinking && item.running)
                {
                    item.running = false;
                }
            }
            "message_end" => self.finish_model_items(),
            "tool_use_queued" | "tool_use_start" => {
                // AgentLoop consumes provider message_end internally. Tool
                // admission is also a reliable boundary for the preceding reply.
                self.finish_model_items();
                let queued = event.kind == "tool_use_queued";
                let name = event
                    .data
                    .get("name")
                    .or_else(|| event.data.get("toolName"))
                    .and_then(Value::as_str)
                    .unwrap_or("tool");
                let call_id = event
                    .data
                    .get("id")
                    .or_else(|| event.data.get("callId"))
                    .and_then(Value::as_str)
                    .filter(|id| !id.is_empty())
                    .map(ToString::to_string);
                let input = tool_input(&event.data);
                let purpose = string_field(&event.data, "purpose").filter(|value| !value.is_empty());
                if !queued {
                    if let Some(item) = self.timeline.iter_mut().find(|item| {
                        item.kind == TimelineKind::Tool
                            && item.queued
                            && call_id.is_some()
                            && item.call_id == call_id
                    }) {
                        item.queued = false;
                        item.running = true;
                        if input.is_some() {
                            item.tool_input = input;
                        }
                        if purpose.is_some() {
                            item.purpose = purpose;
                        }
                        return directive;
                    }
                }
                self.timeline.push(TimelineItem {
                    kind: TimelineKind::Tool,
                    title: name.to_string(),
                    content: String::new(),
                    purpose,
                    tool_input: input,
                    queued,
                    sequence: event.seq,
                    content_complete: false,
                    call_id,
                    running: !queued,
                    failed: false,
                });
                self.on_new_content();
            }
            "tool_use_end" => {
                self.finish_model_items();
                let call_id = event
                    .data
                    .get("id")
                    .or_else(|| event.data.get("callId"))
                    .and_then(Value::as_str)
                    .filter(|id| !id.is_empty());
                let name = event
                    .data
                    .get("name")
                    .or_else(|| event.data.get("toolName"))
                    .and_then(Value::as_str);
                if let Some(item) = self.timeline.iter_mut().find(|item| {
                    item.kind == TimelineKind::Tool
                        && (item.running || item.queued)
                        && if call_id.is_some() {
                            item.call_id.as_deref() == call_id
                        } else {
                            name.is_none() || Some(item.title.as_str()) == name
                        }
                }) {
                    item.running = false;
                    item.queued = false;
                    item.content_complete =
                        event.data.get("content").and_then(Value::as_str).is_some();
                    item.failed = event
                        .data
                        .get("error")
                        .and_then(Value::as_bool)
                        .unwrap_or(false);
                    let result = event
                        .data
                        .get("content")
                        .or_else(|| event.data.get("summary"))
                        .or_else(|| event.data.get("output"))
                        .and_then(Value::as_str)
                        .unwrap_or("");
                    if !result.is_empty() {
                        item.content = result.to_string();
                    }
                }
            }
            "approval_required" | "plan_review" | "user_question" => {
                directive.refresh_interactions = true;
                self.timeline.push(TimelineItem::message(
                    TimelineKind::Warning,
                    "Waiting for you",
                    match event.kind.as_str() {
                        "approval_required" => "A tool needs approval",
                        "plan_review" => "A plan is ready for review",
                        _ => "Harnex has a question",
                    },
                ));
                self.on_new_content();
            }
            "interactions_superseded" => {
                self.set_interactions(Vec::new());
                directive.refresh_interactions = true;
            }
            kind if kind.starts_with("run_input_") => {
                directive.refresh_inputs = true;
            }
            "goal_continue"
            | "goal_step_started"
            | "goal_step_completed"
            | "goal_review_required" => {
                directive.refresh_goal = true;
            }
            "command_result" | "notice" => {
                let content = event
                    .data
                    .get("message")
                    .and_then(Value::as_str)
                    .unwrap_or("");
                if !content.is_empty() {
                    self.timeline.push(TimelineItem::message(
                        TimelineKind::Notice,
                        "Notice",
                        content,
                    ));
                    self.on_new_content();
                }
            }
            "warning" => {
                let content = event
                    .data
                    .get("message")
                    .and_then(Value::as_str)
                    .unwrap_or("Runtime warning");
                self.timeline.push(TimelineItem::message(
                    TimelineKind::Warning,
                    "Warning",
                    content,
                ));
                self.on_new_content();
            }
            "usage" => {
                if let Some(run) = self.run.as_mut() {
                    if let Ok(usage) = serde_json::from_value::<UsageView>(event.data.clone()) {
                        run.usage
                            .get_or_insert_with(UsageView::default)
                            .accumulate(&usage);
                    }
                    let root_usage = event.data["subagentThreadId"]
                        .as_str()
                        .unwrap_or("")
                        .is_empty()
                        && event.data["subagentConversationId"]
                            .as_str()
                            .unwrap_or("")
                            .is_empty()
                        && matches!(
                            event.data["kind"].as_str(),
                            None | Some("" | "task" | "model")
                        );
                    if root_usage {
                        run.actual_provider =
                            string_field(&event.data, "provider").or(run.actual_provider.clone());
                        run.actual_model =
                            string_field(&event.data, "model").or(run.actual_model.clone());
                    }
                }
            }
            "error" => {
                let content = event
                    .data
                    .get("message")
                    .and_then(Value::as_str)
                    .unwrap_or("Run failed");
                self.timeline
                    .push(TimelineItem::message(TimelineKind::Error, "Error", content));
                // Error frames describe the step; root completion is owned by the final done frame.
                self.on_new_content();
            }
            "goal_state_changed" | "goal_review_invalidated" => {
                directive.refresh_interactions = true;
                directive.refresh_goal = true;
                if let Some(run) = self.run.as_mut() {
                    run.status =
                        string_field(&event.data, "status").unwrap_or_else(|| run.status.clone());
                    run.executing = matches!(run.status.as_str(), "RUNNING" | "PAUSE_REQUESTED");
                }
                if self.run.as_ref().is_some_and(|run| !run.executing) {
                    self.finish_model_items();
                }
                if let Some(reason) = event.data["reason"].as_str() {
                    self.timeline.push(TimelineItem::message(
                        TimelineKind::Warning,
                        "Goal",
                        reason,
                    ));
                }
                self.on_new_content();
            }
            "done" => {
                directive.refresh_interactions = true;
                if event.data["status"].as_str() == Some("INCOMPLETE") {
                    let detail = event.data["result"]["detail"].as_str().unwrap_or(
                        "Provide the required input or adjust the step limit to continue.",
                    );
                    self.timeline.push(TimelineItem::message(
                        TimelineKind::Warning,
                        "Run incomplete",
                        detail,
                    ));
                }
                if let Some(run) = self.run.as_mut() {
                    run.status = string_field(&event.data, "status")
                        .unwrap_or_else(|| "COMPLETED".to_string());
                    run.executing = false;
                    if let Some(usage) = run.usage.as_mut() {
                        usage.complete = event.data["execution"]["usageComplete"]
                            .as_bool()
                            .unwrap_or(false);
                    }
                }
                directive.terminal = true;
                self.finish_running_items();
            }
            "cancelled" => {
                if let Some(run) = self.run.as_mut() {
                    run.status = "CANCELLED".to_string();
                    run.executing = false;
                }
                directive.terminal = true;
                self.finish_running_items();
            }
            _ => {}
        }
        self.prune_timeline();
        directive
    }

    pub fn reset_stream_cursor(&mut self) {
        self.stream_run_id = None;
        self.stream_epoch = None;
        self.last_applied_stream_seq = 0;
    }

    fn prune_timeline(&mut self) {
        if self.timeline.len() > MAX_TIMELINE_ITEMS {
            let drop_count = self.timeline.len() - MAX_TIMELINE_ITEMS;
            self.timeline.drain(..drop_count);
        }
    }

    fn finish_running_items(&mut self) {
        for item in &mut self.timeline {
            item.running = false;
            item.queued = false;
        }
    }

    fn finish_model_items(&mut self) {
        for item in &mut self.timeline {
            if matches!(item.kind, TimelineKind::Assistant | TimelineKind::Thinking) {
                item.running = false;
            }
        }
    }

    fn mark_run_executing(&mut self) {
        if let Some(run) = self.run.as_mut().filter(|run| run.is_live()) {
            if matches!(
                run.status.as_str(),
                "CREATED" | "QUEUED" | "STARTING" | "WAITING_RETRY"
            ) {
                run.status = "RUNNING".into();
            }
            if matches!(run.status.as_str(), "RUNNING" | "PAUSE_REQUESTED") {
                run.executing = true;
            }
        }
    }

    fn on_new_content(&mut self) {
        if self.scroll.is_following() {
            self.unread = 0;
        } else {
            self.unread += 1;
        }
    }

    pub fn scroll_wheel(&mut self, up: bool) -> bool {
        let adjust = |value: &mut u16| {
            *value = if up {
                value.saturating_sub(3)
            } else {
                value.saturating_add(3)
            };
        };
        match self.overlay.as_mut() {
            None => {
                if up {
                    self.scroll.move_up(3);
                } else {
                    self.scroll.move_down(3);
                }
                if self.scroll.is_following() {
                    self.unread = 0;
                }
            }
            Some(Overlay::Help) => {
                if up {
                    self.help_scroll.move_up(3);
                } else {
                    self.help_scroll.move_down(3);
                }
            }
            Some(Overlay::Text(state)) => {
                if up {
                    state.scroll.move_up(3);
                } else {
                    state.scroll.move_down(3);
                }
            }
            Some(Overlay::Changes(state)) => adjust(&mut state.scroll),
            Some(Overlay::Interaction(state)) => adjust(&mut state.scroll),
            _ => return false,
        }
        true
    }

    pub fn handle_mouse(&mut self, event: MouseEvent) -> (bool, Vec<Effect>) {
        use super::mouse::Action;
        match self.mouse.handle(event) {
            Action::None => (false, Vec::new()),
            Action::Redraw => (true, Vec::new()),
            Action::Copy(text) => (
                true,
                vec![Effect::Copy {
                    text,
                    label: "selection",
                }],
            ),
            Action::Paste => (true, vec![Effect::PasteClipboard]),
            Action::Scroll(up) => {
                self.scroll_wheel(up);
                (true, Vec::new())
            }
        }
    }

    pub fn current_activity(&self) -> Option<String> {
        if !self.interactions.is_empty() {
            return Some(super::i18n::text("Waiting for you").into());
        }
        if !self
            .run
            .as_ref()
            .is_some_and(|run| run.is_live() && run.executing)
        {
            return None;
        }
        let tools = self
            .timeline
            .iter()
            .filter(|item| item.kind == TimelineKind::Tool && item.running)
            .collect::<Vec<_>>();
        if let Some(tool) = tools.first() {
            return Some(if tools.len() == 1 {
                format!("{}: {}", super::i18n::text("Tool"), tool.title)
            } else {
                format!(
                    "{}: {} (+{})",
                    super::i18n::text("Tool"),
                    tool.title,
                    tools.len() - 1
                )
            });
        }
        if self
            .timeline
            .iter()
            .any(|item| item.kind == TimelineKind::Assistant && item.running)
        {
            Some(super::i18n::text("Replying").into())
        } else if self
            .timeline
            .iter()
            .any(|item| item.kind == TimelineKind::Thinking && item.running)
        {
            Some(super::i18n::text("Thinking").into())
        } else {
            Some(super::i18n::text("Processing").into())
        }
    }

    pub fn handle_key(&mut self, key: KeyEvent) -> Vec<Effect> {
        if self.mouse.selection.is_some() {
            let text = self.mouse.take_text();
            if key.code == KeyCode::Esc {
                return Vec::new();
            }
            if key.code == KeyCode::Char('c') && key.modifiers.contains(KeyModifiers::CONTROL) {
                return text
                    .map(|text| {
                        vec![Effect::Copy {
                            text,
                            label: "selection",
                        }]
                    })
                    .unwrap_or_default();
            }
        }
        let revision = self.composer.revision;
        let effects = self.handle_key_inner(key);
        if self.composer.revision != revision && !matches!(key.code, KeyCode::Up | KeyCode::Down) {
            self.input_history.detach();
        }
        effects
    }

    fn handle_key_inner(&mut self, key: KeyEvent) -> Vec<Effect> {
        if key.code == KeyCode::F(3) {
            self.leader = None;
            return self.open_efforts();
        }
        if key.code == KeyCode::F(1) {
            self.leader = None;
            self.open_help();
            return Vec::new();
        }
        if key.code == KeyCode::F(2) {
            self.leader = None;
            return if key.modifiers.contains(KeyModifiers::SHIFT) {
                self.cycle_recent_model()
            } else {
                self.open_models()
            };
        }
        if key.modifiers == KeyModifiers::CONTROL && key.code == KeyCode::Char('p') {
            self.leader = None;
            self.navigation.clear();
            self.overlay = Some(Overlay::Picker(PickerState {
                kind: PickerKind::Commands,
                query: String::new(),
                selected: 0,
            }));
            return Vec::new();
        }
        if key.modifiers == KeyModifiers::CONTROL && key.code == KeyCode::Char('x') {
            self.leader = Some(Instant::now());
            return Vec::new();
        }
        if self.leader.take().is_some() {
            return match key.code {
                KeyCode::Char('q' | 'Q') => self.request_quit(),
                KeyCode::Char('c' | 'C') => {
                    self.clear_draft();
                    Vec::new()
                }
                KeyCode::Char('e' | 'E') => vec![Effect::EditDraft],
                KeyCode::Char('v' | 'V') => {
                    self.preview_draft();
                    Vec::new()
                }
                KeyCode::Char('y' | 'Y') => self.copy_answer(),
                KeyCode::Char('m' | 'M') => self.open_models(),
                KeyCode::Char('r' | 'R') => self.open_efforts(),
                KeyCode::Esc => Vec::new(),
                KeyCode::Char('d' | 'D') if self.agent_available => self.open_changes(),
                KeyCode::Char('i' | 'I') => self.queue_follow_up(),
                KeyCode::Char('k' | 'K') if self.agent_available => {
                    self.overlay = Some(Overlay::Picker(PickerState {
                        kind: PickerKind::Skills,
                        query: String::new(),
                        selected: 0,
                    }));
                    vec![Effect::LoadSkills]
                }
                KeyCode::Char('u' | 'U') if self.agent_available => {
                    self.overlay = Some(Overlay::Picker(PickerState {
                        kind: PickerKind::Toolsets,
                        query: String::new(),
                        selected: 0,
                    }));
                    vec![Effect::LoadToolsets]
                }
                _ => {
                    self.push_notice("Unknown Ctrl+X chord | F1 shortcuts");
                    Vec::new()
                }
            };
        }
        if key.modifiers == KeyModifiers::CONTROL && key.code == KeyCode::Char('r') {
            return self.open_history();
        }
        let editor = match self.overlay.as_mut() {
            None => Some(&mut self.composer),
            Some(Overlay::Interaction(state))
                if state.interaction.kind == "question" || state.rejecting =>
            {
                Some(&mut state.input)
            }
            Some(Overlay::Goal(state)) if state.reviewing => Some(&mut state.feedback),
            _ => None,
        };
        let edited = editor.is_some_and(|editor| {
            if self.input_keys.is_newline(key) {
                editor.insert_newline();
                true
            } else {
                editor.edit_key(key)
            }
        });
        if edited {
            return Vec::new();
        }
        if !self.agent_available
            && (key.code == KeyCode::BackTab
                || key.modifiers == KeyModifiers::CONTROL
                    && matches!(key.code, KeyCode::Char('g' | 't')))
        {
            self.leader = None;
            self.push_notice("Agent controls require a project space");
            return Vec::new();
        }
        if self.overlay.is_some() {
            return self.handle_overlay_key(key);
        }
        match (key.code, key.modifiers) {
            (KeyCode::F(6), _) => {
                if !self.can_switch_scope {
                    self.push_notice("This terminal is attached to a fixed Runtime; open standalone `harnex tui --conversation` for personal conversations");
                } else if self.pending_create.is_some() || self.pending_run_input.is_some() {
                    self.push_notice(
                        "Wait for the submitted message to be accepted before switching",
                    );
                } else {
                    self.switch_scope = true;
                    self.should_quit = true;
                }
                Vec::new()
            }
            (KeyCode::Char('c'), KeyModifiers::CONTROL) => {
                if let Some(run_id) = self.live_run_id().map(ToString::to_string) {
                    self.push_notice("Cancelling current run...");
                    vec![Effect::CancelRun(run_id)]
                } else {
                    self.composer.clear();
                    self.push_notice("Input cleared | Ctrl+D exits");
                    Vec::new()
                }
            }
            (KeyCode::Char('d'), KeyModifiers::CONTROL) => {
                if self.composer.text.is_empty() {
                    self.request_quit()
                } else {
                    if self.composer.cursor >= self.composer.text.len() {
                        self.push_notice("Draft is not empty | Ctrl+X → Q exit | Ctrl+X → C clear");
                    } else {
                        self.composer.delete();
                    }
                    Vec::new()
                }
            }
            (KeyCode::Char('p'), KeyModifiers::CONTROL) => {
                self.overlay = Some(Overlay::Picker(PickerState {
                    kind: PickerKind::Commands,
                    query: String::new(),
                    selected: 0,
                }));
                Vec::new()
            }
            (KeyCode::Char('n'), KeyModifiers::CONTROL) => {
                if let Some(reason) = self.command_reason("new") {
                    self.push_notice(reason);
                } else {
                    self.new_thread();
                }
                Vec::new()
            }
            (KeyCode::Char('o'), KeyModifiers::CONTROL) => {
                self.show_thinking = !self.show_thinking;
                self.push_notice(if self.show_thinking {
                    "Thinking expanded"
                } else {
                    "Thinking collapsed"
                });
                Vec::new()
            }
            (KeyCode::Char('m'), KeyModifiers::CONTROL) | (KeyCode::F(2), _) => self.open_models(),
            (KeyCode::Char('l'), KeyModifiers::CONTROL) => vec![Effect::Redraw],
            (KeyCode::Char('i'), KeyModifiers::CONTROL) => self.open_run_inputs(),
            (KeyCode::Char('t'), KeyModifiers::CONTROL) => self
                .thread_id
                .clone()
                .map(|thread_id| {
                    self.overlay = Some(Overlay::Loading(PanelKind::Todos));
                    vec![Effect::LoadTodos(thread_id)]
                })
                .unwrap_or_else(|| {
                    self.push_notice("No thread selected");
                    Vec::new()
                }),
            (KeyCode::Char('g'), KeyModifiers::CONTROL) => self.open_goal(),
            (KeyCode::Up, _) => {
                if !self.composer.vertical(true, self.editor_width.get()) {
                    self.input_history.previous(&mut self.composer);
                }
                Vec::new()
            }
            (KeyCode::Down, _) => {
                if !self.composer.vertical(false, self.editor_width.get()) {
                    self.input_history.next(&mut self.composer);
                }
                Vec::new()
            }
            (KeyCode::BackTab, _) => self.cycle_permission(),
            (KeyCode::Enter, _) if self.input_keys.is_send(key) => self.submit(),
            (KeyCode::Enter, KeyModifiers::CONTROL | KeyModifiers::ALT) => self.queue_follow_up(),
            (KeyCode::Enter, _) => Vec::new(),
            (KeyCode::Backspace, _) => {
                self.composer.backspace();
                Vec::new()
            }
            (KeyCode::Delete, _) => {
                self.composer.delete();
                Vec::new()
            }
            (KeyCode::Left, _) => {
                self.composer.left();
                Vec::new()
            }
            (KeyCode::Right, _) => {
                self.composer.right();
                Vec::new()
            }
            (KeyCode::Home, KeyModifiers::CONTROL) => {
                self.scroll.first();
                Vec::new()
            }
            (KeyCode::End, KeyModifiers::CONTROL) => {
                self.scroll.follow();
                self.unread = 0;
                Vec::new()
            }
            (KeyCode::Home, _) => {
                self.composer.cursor = 0;
                Vec::new()
            }
            (KeyCode::End, _) => {
                self.composer.cursor = self.composer.text.len();
                Vec::new()
            }
            (KeyCode::PageUp, _) => {
                self.scroll.move_up(self.scroll.page_size());
                Vec::new()
            }
            (KeyCode::PageDown, _) => {
                self.scroll.move_down(self.scroll.page_size());
                if self.scroll.is_following() {
                    self.unread = 0;
                }
                Vec::new()
            }
            (KeyCode::Esc, _) => {
                if let Some(run_id) = self.live_run_id().map(ToString::to_string) {
                    self.push_notice("Stopping current task | draft kept");
                    vec![Effect::CancelRun(run_id)]
                } else {
                    Vec::new()
                }
            }
            (KeyCode::Char('?'), _) if self.composer.text.is_empty() => {
                self.help_scroll.first();
                self.overlay = Some(Overlay::Help);
                Vec::new()
            }
            (KeyCode::Char('@'), _) => {
                self.file_results.clear();
                self.overlay = Some(Overlay::Picker(PickerState {
                    kind: PickerKind::Files,
                    query: String::new(),
                    selected: 0,
                }));
                Vec::new()
            }
            (KeyCode::Char('/'), _) if self.composer.text.is_empty() => {
                self.overlay = Some(Overlay::Picker(PickerState {
                    kind: PickerKind::Slash,
                    query: String::new(),
                    selected: 0,
                }));
                vec![Effect::LoadCommands]
            }
            (KeyCode::Char(character), modifiers)
                if !modifiers.contains(KeyModifiers::CONTROL)
                    && !modifiers.contains(KeyModifiers::ALT) =>
            {
                self.composer.insert(character);
                Vec::new()
            }
            _ => Vec::new(),
        }
    }

    pub fn paste(&mut self, text: &str) -> Vec<Effect> {
        let text = text.replace("\r\n", "\n").replace('\r', "\n");
        let text = text
            .chars()
            .filter(|character| !character.is_control() || matches!(character, '\n' | '\t'))
            .collect::<String>();
        match self.overlay.as_mut() {
            None => {
                self.composer.insert_text(&text);
                self.input_history.detach();
            }
            Some(Overlay::Interaction(state)) => state.input.insert_text(&text),
            Some(Overlay::Goal(state)) if state.reviewing => state.feedback.insert_text(&text),
            Some(Overlay::Picker(picker)) => {
                picker
                    .query
                    .push_str(&text.replace(['\r', '\n', '\t'], " "));
                picker.selected = 0;
                if matches!(picker.kind, PickerKind::Files) {
                    return vec![Effect::SearchFiles(picker.query.clone())];
                }
            }
            _ => {}
        }
        Vec::new()
    }

    fn submit(&mut self) -> Vec<Effect> {
        self.submit_with_mode("STEER")
    }

    fn queue_follow_up(&mut self) -> Vec<Effect> {
        if self.live_run_id().is_none() {
            self.push_notice(tf!(
                "No active task | {} sends a new message",
                "当前没有运行中的任务 | {} 发送新消息",
                self.input_keys.send_key()
            ));
            return Vec::new();
        }
        self.submit_with_mode("QUEUE")
    }

    fn submit_with_mode(&mut self, mode: &str) -> Vec<Effect> {
        if self.pending_create.is_some()
            || self.pending_run_input.is_some()
            || self.composer.text.trim().is_empty()
        {
            return Vec::new();
        }
        if self.composer.text.trim() == "/preview" {
            self.push_notice("Usage: /preview relative/path.html or /preview artifact:asset_id");
            return Vec::new();
        }
        if let Some(source) = self.composer.text.trim().strip_prefix("/preview ") {
            let source = source.trim().to_string();
            if !source.is_empty() {
                self.composer.clear();
                self.push_notice("Preparing browser preview...");
                return vec![Effect::Preview(source)];
            }
        }
        if let Some(run_id) = self.live_run_id().map(ToString::to_string) {
            let mode = if self
                .run
                .as_ref()
                .is_some_and(|run| run.execution_spec["kind"].as_str() == Some("CONVERSATION"))
            {
                "QUEUE"
            } else {
                mode
            };
            let input = self.composer.text.clone();
            let request_id = uuid::Uuid::new_v4().to_string();
            self.pending_run_input = Some((request_id.clone(), input.clone(), mode.to_string()));
            self.push_notice("Sending message...");
            return vec![Effect::EnqueueInput {
                request_id,
                run_id,
                input,
                mode: mode.to_string(),
            }];
        }
        let input = self.composer.text.clone();
        let request_id = uuid::Uuid::new_v4().to_string();
        if self.selected_effort != "auto" && (!self.models_ready || self.models_loading) {
            self.push_notice("Wait for model capabilities, or select auto effort with F3");
            return Vec::new();
        }
        self.reconcile_effort();
        self.pending_create = Some((request_id.clone(), input.clone()));
        self.scroll.follow();
        vec![Effect::CreateRun {
            request_id,
            thread_id: self.thread_id.clone(),
            input,
            permission_mode: self.permission_mode.clone(),
            provider: self.selected_provider.clone(),
            model: self.selected_model.clone(),
            reasoning_effort: self.selected_effort.clone(),
            toolsets: self.selected_toolsets.clone(),
        }]
    }

    pub fn submitting_input(&self) -> bool {
        self.pending_run_input.is_some() || self.pending_create.is_some()
    }

    pub fn finish_run_input(&mut self, request_id: &str, result: Result<(), ApiError>) {
        if self
            .pending_run_input
            .as_ref()
            .map(|pending| pending.0.as_str())
            != Some(request_id)
        {
            return;
        }
        let (_, text, mode) = self.pending_run_input.take().unwrap();
        match result {
            Ok(_) => {
                self.input_history.record(&text);
                if self.composer.text == text {
                    self.composer.reset();
                }
                self.push_notice(if mode == "QUEUE" {
                    "Queued | will run after this task completes"
                } else {
                    "Received | waiting to join the current task"
                });
            }
            Err(error) => self.push_notice(tf!(
                "Message not sent | draft kept | {}",
                "消息未发送 | 草稿已保留 | {}",
                error.message
            )),
        }
    }

    pub fn finish_create(&mut self, request_id: &str, accepted: bool) -> bool {
        if self.pending_create.as_ref().map(|item| item.0.as_str()) != Some(request_id) {
            return false;
        }
        let (_, text) = self.pending_create.take().unwrap();
        if accepted {
            self.input_history.record(&text);
        }
        if accepted && self.composer.text == text {
            self.composer.reset();
        }
        true
    }

    pub fn hydrate_presentation(&mut self, bundle: super::api::PresentationBundle) {
        let snapshot = bundle.snapshot;
        let complete_tools = if self
            .run
            .as_ref()
            .is_some_and(|run| run.run_id == snapshot.run.run_id)
        {
            self.timeline
                .iter()
                .filter(|item| item.kind == TimelineKind::Tool && item.content_complete)
                .cloned()
                .collect::<Vec<_>>()
        } else {
            Vec::new()
        };
        let mut thread = bundle.thread;
        let answer_id = snapshot
            .history
            .answer
            .as_ref()
            .map(|answer| &answer.message_id);
        let answer = answer_id
            .and_then(|id| {
                thread
                    .messages
                    .iter()
                    .position(|message| &message.message_id == id)
            })
            .map(|index| thread.messages.remove(index));
        let history = std::mem::take(&mut thread.messages);
        let reading = (self.thread_id.as_deref() == Some(thread.id.as_str()))
            .then(|| (self.scroll.clone(), self.unread, self.model_selection()));
        self.set_thread(thread);
        if let Some((scroll, unread, selection)) = reading {
            self.scroll = scroll;
            self.unread = unread;
            self.restore_model_selection(selection);
        }
        self.run = Some(snapshot.run.clone());
        self.set_interactions(snapshot.interactions);
        if matches!(self.overlay, Some(Overlay::RunInputs(_))) {
            self.set_run_inputs(snapshot.inputs);
        }
        // Captured process rows are history, not new events: do not let them
        // change run status or clear the active reply's streaming marker.
        let mut process = Vec::new();
        for message in snapshot.live.messages {
            let seq = message["seq"].as_u64().unwrap_or(0);
            let anchor = string_field(&message, "afterMessageId");
            for (key, kind, title) in [
                ("thinking", TimelineKind::Thinking, "Thinking"),
                ("text", TimelineKind::Assistant, "Harnex"),
            ] {
                if let Some(content) = message[key].as_str().filter(|content| !content.is_empty()) {
                    process.push((
                        seq,
                        anchor.clone(),
                        TimelineItem::message(kind, title, content),
                    ));
                }
            }
        }
        for tool in snapshot.live.tools {
            if tool["subagentConversationId"]
                .as_str()
                .is_some_and(|id| !id.is_empty())
            {
                continue;
            }
            let state = tool["state"].as_str().unwrap_or("COMPLETED");
            let pending = matches!(state, "RUNNING" | "QUEUED");
            let seq = tool["seq"].as_u64().unwrap_or(0);
            let id = string_field(&tool, "id");
            let complete = complete_tools
                .iter()
                .find(|item| item.sequence == seq && item.call_id == id);
            process.push((
                seq,
                string_field(&tool, "afterMessageId"),
                TimelineItem {
                    kind: TimelineKind::Tool,
                    title: tool["name"].as_str().unwrap_or("tool").into(),
                    purpose: string_field(&tool, "purpose")
                        .or_else(|| complete.and_then(|item| item.purpose.clone())),
                    tool_input: complete
                        .and_then(|item| item.tool_input.clone())
                        .or_else(|| tool_input(&tool)),
                    content: complete
                        .map(|item| item.content.clone())
                        .unwrap_or_else(|| {
                            tool["content"]
                                .as_str()
                                .or_else(|| tool["summary"].as_str())
                                .unwrap_or("")
                                .into()
                        }),
                    call_id: id,
                    sequence: seq,
                    content_complete: complete.is_some(),
                    queued: state == "QUEUED" && snapshot.run.is_live(),
                    running: state == "RUNNING" && snapshot.run.executing,
                    failed: state == "FAILED" || pending && !snapshot.run.is_live(),
                },
            ));
        }
        process.sort_by_key(|(seq, _, _)| *seq);
        for message in history {
            let id = message.message_id.clone();
            self.append_archived_message(message);
            let mut index = 0;
            while index < process.len() {
                if !id.is_empty() && process[index].1.as_deref() == Some(id.as_str()) {
                    self.timeline.push(process.remove(index).2);
                } else {
                    index += 1;
                }
            }
        }
        self.timeline
            .extend(process.into_iter().map(|(_, _, item)| item));
        if let Some(answer) = answer {
            self.append_archived_message(answer);
        }
        for (content, kind, title) in [
            (snapshot.live.thinking, TimelineKind::Thinking, "Thinking"),
            (snapshot.live.text, TimelineKind::Assistant, "Harnex"),
        ] {
            if !content.is_empty() {
                let mut item = TimelineItem::message(kind, title, content);
                item.running = snapshot.run.executing && snapshot.run.is_live();
                self.timeline.push(item);
            }
        }
        self.prune_timeline();
        self.stream_run_id = Some(snapshot.run.run_id);
        self.stream_epoch = snapshot.cursor.stream_epoch;
        self.last_applied_stream_seq = snapshot.cursor.projection_seq;
    }

    fn cycle_permission(&mut self) -> Vec<Effect> {
        let next = match self.permission_mode.as_str() {
            "ask" => "accept-edits",
            "accept-edits" => "yolo",
            _ => "ask",
        };
        if next == "yolo" {
            self.overlay = Some(Overlay::ConfirmYolo);
            return Vec::new();
        }
        self.permission_mode = next.to_string();
        self.push_notice(tf!(
            "Permission mode: {}",
            "权限模式：{}",
            super::i18n::text(next)
        ));
        self.thread_id
            .clone()
            .map(|thread_id| {
                vec![Effect::SetPermissionMode {
                    thread_id,
                    mode: next.to_string(),
                }]
            })
            .unwrap_or_default()
    }

    fn open_changes(&mut self) -> Vec<Effect> {
        if let Some(run_id) = self.run.as_ref().map(|run| run.run_id.clone()) {
            self.overlay = Some(Overlay::Loading(PanelKind::Changes));
            self.push_notice("Loading this run's changes...");
            vec![Effect::LoadChanges(run_id)]
        } else {
            self.push_notice("No run selected");
            Vec::new()
        }
    }

    fn open_models(&mut self) -> Vec<Effect> {
        self.models_loading = true;
        if let Some(overlay) = self.overlay.take() {
            if !matches!(
                overlay,
                Overlay::Picker(PickerState {
                    kind: PickerKind::Models,
                    ..
                })
            ) {
                self.navigation.push(overlay);
            }
        }
        self.overlay = Some(Overlay::Picker(PickerState {
            kind: PickerKind::Models,
            query: String::new(),
            selected: 0,
        }));
        vec![Effect::LoadModels]
    }

    fn handle_overlay_key(&mut self, key: KeyEvent) -> Vec<Effect> {
        let overlay = self.overlay.take().expect("overlay exists");
        if key.code == KeyCode::Esc {
            match overlay {
                Overlay::Changes(mut state) if state.diff.is_some() => {
                    state.diff = None;
                    state.scroll = 0;
                    self.overlay = Some(Overlay::Changes(state));
                }
                Overlay::Goal(mut state) if state.reviewing => {
                    state.reviewing = false;
                    self.overlay = Some(Overlay::Goal(state));
                }
                Overlay::Interaction(mut state) if state.rejecting => {
                    state.rejecting = false;
                    self.overlay = Some(Overlay::Interaction(state));
                }
                Overlay::Interaction(state) => {
                    self.dismissed_interactions.push(state.interaction.call_id);
                    self.back();
                }
                _ => self.back(),
            }
            return Vec::new();
        }
        let previous = overlay.clone();
        let effects = match overlay {
            Overlay::Loading(kind) => {
                self.overlay = Some(Overlay::Loading(kind));
                Vec::new()
            }
            Overlay::Help => {
                match key.code {
                    KeyCode::Up => self.help_scroll.move_up(1),
                    KeyCode::Down => self.help_scroll.move_down(1),
                    KeyCode::PageUp => self.help_scroll.move_up(self.help_scroll.page_size()),
                    KeyCode::PageDown => self.help_scroll.move_down(self.help_scroll.page_size()),
                    KeyCode::Home => self.help_scroll.first(),
                    KeyCode::End => self.help_scroll.follow(),
                    _ => {}
                }
                if !matches!(key.code, KeyCode::Esc | KeyCode::Char('?')) {
                    self.overlay = Some(Overlay::Help);
                }
                Vec::new()
            }
            Overlay::ConfirmYolo => match key.code {
                KeyCode::Char('y') | KeyCode::Enter => {
                    self.permission_mode = "yolo".to_string();
                    self.back();
                    self.push_notice("Full access enabled | Runtime denylist still applies");
                    self.thread_id
                        .clone()
                        .map(|thread_id| {
                            vec![Effect::SetPermissionMode {
                                thread_id,
                                mode: "yolo".to_string(),
                            }]
                        })
                        .unwrap_or_default()
                }
                KeyCode::Esc | KeyCode::Char('n') => {
                    self.back();
                    Vec::new()
                }
                _ => {
                    self.overlay = Some(Overlay::ConfirmYolo);
                    Vec::new()
                }
            },
            Overlay::ConfirmExit => match key.code {
                KeyCode::Char('c') => {
                    let mut effects = self
                        .live_run_id()
                        .map(|run| vec![Effect::CancelRun(run.to_string())])
                        .unwrap_or_default();
                    effects.push(Effect::Quit);
                    effects
                }
                KeyCode::Char('e') | KeyCode::Enter => vec![Effect::Quit],
                KeyCode::Esc | KeyCode::Char('b') => Vec::new(),
                _ => {
                    self.overlay = Some(Overlay::ConfirmExit);
                    Vec::new()
                }
            },
            Overlay::Text(state) => {
                match key.code {
                    KeyCode::Up => state.scroll.move_up(1),
                    KeyCode::Down => state.scroll.move_down(1),
                    KeyCode::PageUp => state.scroll.move_up(state.scroll.page_size()),
                    KeyCode::PageDown => state.scroll.move_down(state.scroll.page_size()),
                    KeyCode::Home => state.scroll.first(),
                    KeyCode::End => state.scroll.follow(),
                    KeyCode::Char('c') => {
                        let effect = Effect::Copy {
                            text: state.text.clone(),
                            label: "Text",
                        };
                        self.overlay = Some(Overlay::Text(state));
                        return vec![effect];
                    }
                    KeyCode::Char('e') if state.draft => {
                        self.back();
                        return vec![Effect::EditDraft];
                    }
                    _ => {}
                }
                self.overlay = Some(Overlay::Text(state));
                Vec::new()
            }
            Overlay::Picker(mut picker) => self.handle_picker_key(&mut picker, key),
            Overlay::Interaction(mut interaction) => {
                self.handle_interaction_key(&mut interaction, key)
            }
            Overlay::Changes(mut changes) => self.handle_changes_key(&mut changes, key),
            Overlay::RunInputs(mut inputs) => self.handle_run_inputs_key(&mut inputs, key),
            Overlay::Snapshots(mut snapshots) => self.handle_snapshots_key(&mut snapshots, key),
            Overlay::Restore(restore) => self.handle_restore_key(restore, key),
            Overlay::Goal(mut goal) => self.handle_goal_key(&mut goal, key),
            Overlay::Todos(todos) => {
                if key.code != KeyCode::Esc {
                    self.overlay = Some(Overlay::Todos(todos));
                }
                Vec::new()
            }
            Overlay::Context => {
                if key.code != KeyCode::Esc {
                    self.overlay = Some(Overlay::Context);
                }
                Vec::new()
            }
        };
        if self.overlay.is_none() {
            if effects.iter().any(|effect| {
                matches!(
                    effect,
                    Effect::LoadDiff { .. }
                        | Effect::WithdrawInput { .. }
                        | Effect::GoalControl { .. }
                        | Effect::GoalReview { .. }
                )
            }) {
                self.overlay = Some(previous);
            } else if effects
                .iter()
                .any(|effect| matches!(effect, Effect::LoadRestorePlan { .. }))
            {
                self.navigation.push(previous);
                self.overlay = Some(Overlay::Loading(PanelKind::Restore));
            } else if effects.iter().any(|effect| {
                matches!(
                    effect,
                    Effect::RestoreSnapshot { .. } | Effect::BranchSnapshot { .. }
                )
            }) {
                self.navigation.push(previous);
                self.overlay = Some(Overlay::Loading(PanelKind::Thread));
            } else {
                self.back();
            }
        }
        effects
    }

    fn handle_picker_key(&mut self, picker: &mut PickerState, key: KeyEvent) -> Vec<Effect> {
        let count = self.picker_entries(picker).len();
        if matches!(picker.kind, PickerKind::Models)
            && key.code == KeyCode::Char('f')
            && key.modifiers == KeyModifiers::CONTROL
        {
            if let Some((id, _)) = self.picker_entries(picker).get(picker.selected).cloned() {
                if self.favorite_models.contains(&id) {
                    self.favorite_models.retain(|item| item != &id);
                } else {
                    self.favorite_models.push(id.clone());
                }
                picker.selected = self
                    .picker_entries(picker)
                    .iter()
                    .position(|(entry, _)| entry == &id)
                    .unwrap_or(0);
            }
            self.overlay = Some(Overlay::Picker(picker.clone()));
            return Vec::new();
        }
        match key.code {
            KeyCode::Esc => Vec::new(),
            KeyCode::Up => {
                picker.selected = picker.selected.saturating_sub(1);
                self.overlay = Some(Overlay::Picker(picker.clone()));
                Vec::new()
            }
            KeyCode::Down => {
                picker.selected = (picker.selected + 1).min(count.saturating_sub(1));
                self.overlay = Some(Overlay::Picker(picker.clone()));
                Vec::new()
            }
            KeyCode::Backspace => {
                picker.query.pop();
                picker.selected = 0;
                self.overlay = Some(Overlay::Picker(picker.clone()));
                if matches!(picker.kind, PickerKind::Files) && !picker.query.is_empty() {
                    vec![Effect::SearchFiles(picker.query.clone())]
                } else {
                    if picker.query.is_empty() {
                        self.file_results.clear();
                    }
                    Vec::new()
                }
            }
            KeyCode::Char(character) if !key.modifiers.contains(KeyModifiers::CONTROL) => {
                picker.query.push(character);
                picker.selected = 0;
                self.overlay = Some(Overlay::Picker(picker.clone()));
                if matches!(picker.kind, PickerKind::Files) {
                    vec![Effect::SearchFiles(picker.query.clone())]
                } else {
                    Vec::new()
                }
            }
            KeyCode::Enter => self.activate_picker(picker),
            _ => {
                self.overlay = Some(Overlay::Picker(picker.clone()));
                Vec::new()
            }
        }
    }

    pub fn picker_entries(&self, picker: &PickerState) -> Vec<(String, String)> {
        let query = picker.query.to_lowercase();
        match picker.kind {
            PickerKind::InputHistory => self
                .input_history
                .entries
                .iter()
                .enumerate()
                .rev()
                .filter(|(_, text)| query.is_empty() || text.to_lowercase().contains(&query))
                .map(|(index, text)| (index.to_string(), text.replace(['\n', '\r'], " ↵ ")))
                .collect(),
            PickerKind::Themes => [
                ("auto", "Follow terminal background"),
                ("dark", "Dark"),
                ("light", "Light"),
                ("ansi", "ANSI compatibility"),
            ]
            .into_iter()
            .filter(|(id, label)| {
                query.is_empty()
                    || format!("{id} {label} {}", super::i18n::text(label))
                        .to_lowercase()
                        .contains(&query)
            })
            .map(|(id, label)| (id.to_string(), super::i18n::text(label).to_string()))
            .collect(),
            PickerKind::Language => [
                ("auto", super::i18n::text("System default / 跟随系统")),
                ("zh-CN", "简体中文"),
                ("en", "English"),
            ]
            .into_iter()
            .filter(|(id, label)| {
                query.is_empty()
                    || format!("{id} {label} {}", super::i18n::text(label))
                        .to_lowercase()
                        .contains(&query)
            })
            .map(|(id, label)| (id.to_string(), label.to_string()))
            .collect(),
            PickerKind::Commands => self.command_entries(None, &query),
            PickerKind::CommandGroup(group) => self.command_entries(Some(group), &query),
            PickerKind::Permissions => [
                ("ask", super::i18n::text("Ask before tool actions")),
                ("accept-edits", super::i18n::text("Allow file edits")),
                ("yolo", super::i18n::text("Full access")),
            ]
            .into_iter()
            .filter(|(id, label)| {
                query.is_empty() || format!("{id} {label}").to_lowercase().contains(&query)
            })
            .map(|(id, label)| (id.to_string(), label.to_string()))
            .collect(),
            PickerKind::Threads => self
                .threads
                .iter()
                .filter(|thread| {
                    query.is_empty()
                        || thread.title.to_lowercase().contains(&query)
                        || thread.id.to_lowercase().contains(&query)
                })
                .map(|thread| (thread.id.clone(), thread.title.clone()))
                .collect(),
            PickerKind::Files => self
                .file_results
                .iter()
                .filter(|file| {
                    query.is_empty()
                        || file.path.to_lowercase().contains(&query)
                        || file.name.to_lowercase().contains(&query)
                })
                .map(|file| {
                    (
                        file.path.clone(),
                        file.snippet.clone().unwrap_or_else(|| file.name.clone()),
                    )
                })
                .collect(),
            PickerKind::InputKeys => InputKeys::ALL
                .into_iter()
                .map(|mode| {
                    (
                        mode.id().to_string(),
                        super::i18n::text(mode.label()).to_string(),
                    )
                })
                .filter(|(id, label)| {
                    query.is_empty() || format!("{id} {label}").to_lowercase().contains(&query)
                })
                .collect(),
            PickerKind::Efforts => {
                let mut efforts = vec![(
                    "auto".to_string(),
                    super::i18n::text("Auto (provider default)").to_string(),
                )];
                if self.models_ready && !self.models_loading {
                    efforts.extend(
                        self.effort_choices()
                            .into_iter()
                            .map(|effort| (effort.clone(), effort)),
                    );
                }
                efforts
                    .into_iter()
                    .filter(|(id, label)| {
                        query.is_empty() || format!("{id} {label}").to_lowercase().contains(&query)
                    })
                    .collect()
            }
            PickerKind::Models => {
                let mut models = self
                    .models
                    .iter()
                    .filter(|model| {
                        query.is_empty()
                            || model.label.to_lowercase().contains(&query)
                            || model.model.to_lowercase().contains(&query)
                            || model.provider_label.to_lowercase().contains(&query)
                    })
                    .map(|model| {
                        let detail = model
                            .description
                            .as_deref()
                            .map(|description| format!(" | {}", truncate(description, 42)))
                            .unwrap_or_default();
                        let id = format!("{}:{}", model.provider, model.model);
                        let badge = if self.favorite_models.contains(&id) {
                            super::i18n::text("[favorite] ")
                        } else if self.recent_models.contains(&id) {
                            super::i18n::text("[recent] ")
                        } else {
                            ""
                        };
                        (
                            id,
                            format!("{badge}{} | {}{detail}", model.label, model.provider_label),
                        )
                    })
                    .collect::<Vec<_>>();
                models.sort_by_key(|(id, _)| {
                    (
                        !self.favorite_models.contains(id),
                        self.recent_models
                            .iter()
                            .position(|entry| entry == id)
                            .unwrap_or(usize::MAX),
                    )
                });
                models
            }
            PickerKind::Skills => self
                .skills
                .iter()
                .filter(|skill| {
                    skill.enabled
                        && skill.effective
                        && (query.is_empty()
                            || skill.name.to_lowercase().contains(&query)
                            || skill.description.to_lowercase().contains(&query))
                })
                .map(|skill| {
                    (
                        skill.name.clone(),
                        format!("{} | {}", skill.description, skill.source),
                    )
                })
                .collect(),
            PickerKind::Toolsets => self
                .toolsets
                .iter()
                .filter(|toolset| query.is_empty() || toolset.name.to_lowercase().contains(&query))
                .map(|toolset| {
                    (
                        toolset.name.clone(),
                        tf!(
                            "{} tools | {}",
                            "{} 个工具 | {}",
                            toolset.tools.len(),
                            toolset
                                .tools
                                .iter()
                                .take(4)
                                .cloned()
                                .collect::<Vec<_>>()
                                .join(", ")
                        ),
                    )
                })
                .collect(),
            PickerKind::Slash => self
                .commands
                .iter()
                .filter(|command| {
                    query.is_empty()
                        || command.name.to_lowercase().contains(&query)
                        || format!("{} {}", command.description, command_description(command))
                            .to_lowercase()
                            .contains(&query)
                })
                .map(|command| {
                    (
                        command.name.clone(),
                        format!(
                            "{}  {}",
                            super::i18n::text(&command.arg_hint),
                            command_description(command)
                        ),
                    )
                })
                .collect(),
        }
    }

    fn activate_picker(&mut self, picker: &PickerState) -> Vec<Effect> {
        let Some((id, _)) = self.picker_entries(picker).get(picker.selected).cloned() else {
            self.overlay = Some(Overlay::Picker(picker.clone()));
            return Vec::new();
        };
        match picker.kind {
            PickerKind::InputKeys => {
                let mode = InputKeys::parse(&id).expect("known input key preset");
                self.input_keys = mode;
                self.back();
                vec![Effect::SetInputKeys(mode)]
            }
            PickerKind::Efforts => {
                if id != "auto" {
                    let Some(model) = self.selected_catalog_model().cloned() else {
                        return Vec::new();
                    };
                    // An explicit level belongs to one exact provider/model capability contract.
                    self.selected_provider = Some(model.provider);
                    self.selected_model = Some(model.model);
                }
                self.selected_effort = id;
                self.close_navigation();
                self.push_notice(tf!(
                    "Effort for next task: {}",
                    "下次任务推理强度：{}",
                    self.selected_effort
                ));
                Vec::new()
            }
            PickerKind::InputHistory => {
                if let Some(text) = id
                    .parse::<usize>()
                    .ok()
                    .and_then(|index| self.input_history.entries.get(index))
                    .cloned()
                {
                    self.composer.replace(text);
                    self.input_history.detach();
                    self.close_navigation();
                    self.push_notice(tf!(
                        "History restored to draft | {} sends",
                        "历史输入已回填草稿 | 另按 {} 发送",
                        self.input_keys.send_key()
                    ));
                }
                Vec::new()
            }
            PickerKind::Themes => {
                self.back();
                vec![Effect::SetTheme(id)]
            }
            PickerKind::Language => {
                self.back();
                vec![Effect::SetLanguage(
                    super::i18n::Language::parse(&id).expect("known language choice"),
                )]
            }
            PickerKind::Permissions => {
                if id == "yolo" {
                    self.navigation.push(Overlay::Picker(picker.clone()));
                    self.overlay = Some(Overlay::ConfirmYolo);
                    Vec::new()
                } else {
                    self.permission_mode = id.clone();
                    self.back();
                    self.push_notice(tf!(
                        "Permission mode: {}",
                        "权限模式：{}",
                        super::i18n::text(&id)
                    ));
                    self.thread_id
                        .clone()
                        .map(|thread_id| {
                            vec![Effect::SetPermissionMode {
                                thread_id,
                                mode: id,
                            }]
                        })
                        .unwrap_or_default()
                }
            }
            PickerKind::Threads => {
                if let Some(reason) = self.command_reason("threads") {
                    self.overlay = Some(Overlay::Picker(picker.clone()));
                    self.push_notice(reason);
                    return Vec::new();
                }
                self.navigation.push(Overlay::Picker(picker.clone()));
                self.overlay = Some(Overlay::Loading(PanelKind::Thread));
                vec![Effect::LoadThread(id)]
            }
            PickerKind::Models => {
                if let Some((provider, model)) = id.split_once(':') {
                    self.choose_model(provider.into(), model.into(), true);
                }
                self.back();
                Vec::new()
            }
            PickerKind::Toolsets => {
                self.selected_toolsets = vec![id.clone()];
                self.push_notice(tf!("Toolset selected: {id}", "已选择工具集：{id}"));
                self.back();
                Vec::new()
            }
            PickerKind::Files | PickerKind::Skills | PickerKind::Slash => {
                let text = match picker.kind {
                    PickerKind::Files => format!("@{id} "),
                    PickerKind::Skills => format!("/skill {id} "),
                    _ => format!("/{id} "),
                };
                for character in text.chars() {
                    self.composer.insert(character);
                }
                self.close_navigation();
                Vec::new()
            }
            PickerKind::Commands | PickerKind::CommandGroup(_) => {
                self.overlay = Some(Overlay::Picker(picker.clone()));
                if let Some(reason) = self.command_reason(&id) {
                    self.push_notice(reason);
                    return Vec::new();
                }
                match id.as_str() {
                    "quit" => self.request_quit(),
                    "clear-input" => {
                        self.close_navigation();
                        self.clear_draft();
                        Vec::new()
                    }
                    "history" => self.open_history(),
                    "preview-input" => {
                        self.close_navigation();
                        self.preview_draft();
                        Vec::new()
                    }
                    "edit-input" => {
                        self.close_navigation();
                        vec![Effect::EditDraft]
                    }
                    "copy" => {
                        self.close_navigation();
                        self.copy_answer()
                    }
                    "copy-turn" => {
                        self.close_navigation();
                        self.copy_turn()
                    }
                    "export" => vec![Effect::ExportConversation {
                        thread_id: self.thread_id.clone().unwrap(),
                        title: self.thread_title.clone(),
                    }],
                    "conversation" | "task" | "display" => {
                        let group = match id.as_str() {
                            "conversation" => CommandGroup::Conversation,
                            "task" => CommandGroup::Task,
                            _ => CommandGroup::Display,
                        };
                        self.child_picker(picker, PickerKind::CommandGroup(group));
                        Vec::new()
                    }
                    "new" => {
                        self.new_thread();
                        Vec::new()
                    }
                    "threads" => {
                        self.child_picker(picker, PickerKind::Threads);
                        vec![Effect::LoadThreads]
                    }
                    "language" => {
                        self.child_picker(picker, PickerKind::Language);
                        Vec::new()
                    }
                    "theme" => {
                        self.child_picker(picker, PickerKind::Themes);
                        Vec::new()
                    }
                    "input-keys" => {
                        self.child_picker(picker, PickerKind::InputKeys);
                        Vec::new()
                    }
                    "models" => {
                        self.models_loading = true;
                        self.child_picker(picker, PickerKind::Models);
                        vec![Effect::LoadModels]
                    }
                    "effort" => self.open_efforts(),
                    "mode" => {
                        self.child_picker(picker, PickerKind::Permissions);
                        Vec::new()
                    }
                    "toolsets" => {
                        self.child_picker(picker, PickerKind::Toolsets);
                        vec![Effect::LoadToolsets]
                    }
                    "skills" => {
                        self.child_picker(picker, PickerKind::Skills);
                        vec![Effect::LoadSkills]
                    }
                    "changes" | "inputs" | "snapshots" | "goal" | "todos" => {
                        self.navigation.push(Overlay::Picker(picker.clone()));
                        match id.as_str() {
                            "changes" => self.open_changes(),
                            "inputs" => self.open_run_inputs(),
                            "goal" => self.open_goal(),
                            "snapshots" => {
                                self.overlay = Some(Overlay::Loading(PanelKind::Snapshots));
                                vec![Effect::LoadSnapshots(self.thread_id.clone().unwrap())]
                            }
                            _ => {
                                self.overlay = Some(Overlay::Loading(PanelKind::Todos));
                                vec![Effect::LoadTodos(self.thread_id.clone().unwrap())]
                            }
                        }
                    }
                    "interactions" => {
                        self.navigation.push(Overlay::Picker(picker.clone()));
                        self.overlay = Some(Overlay::Interaction(InteractionState {
                            scroll: 0,
                            interaction: self.interactions[0].clone(),
                            input: Composer::default(),
                            rejecting: false,
                        }));
                        Vec::new()
                    }
                    "input" => self.queue_follow_up(),
                    "stop" => vec![Effect::CancelRun(self.live_run_id().unwrap().to_string())],
                    "context" => {
                        self.navigation.push(Overlay::Picker(picker.clone()));
                        self.overlay = Some(Overlay::Context);
                        Vec::new()
                    }
                    "details" => {
                        self.show_thinking = !self.show_thinking;
                        Vec::new()
                    }
                    "rail" => {
                        self.toggle_rail();
                        Vec::new()
                    }
                    "rail-auto" => {
                        self.auto_rail();
                        Vec::new()
                    }
                    "help" => {
                        self.navigation.push(Overlay::Picker(picker.clone()));
                        self.help_scroll.first();
                        self.overlay = Some(Overlay::Help);
                        Vec::new()
                    }
                    _ => Vec::new(),
                }
            }
        }
    }

    fn handle_interaction_key(
        &mut self,
        state: &mut InteractionState,
        key: KeyEvent,
    ) -> Vec<Effect> {
        if key.code == KeyCode::Esc {
            return Vec::new();
        }
        let kind = state.interaction.kind.as_str();
        if kind == "approval" {
            if matches!(key.code, KeyCode::PageUp | KeyCode::PageDown) {
                state.scroll = if key.code == KeyCode::PageUp {
                    state.scroll.saturating_sub(8)
                } else {
                    state.scroll.saturating_add(8)
                };
                self.overlay = Some(Overlay::Interaction(state.clone()));
                return Vec::new();
            }
            let body = match key.code {
                KeyCode::Char('a') | KeyCode::Enter => {
                    Some(json!({"kind":"approval","decision":"allow"}))
                }
                KeyCode::Char('w') => {
                    if state
                        .interaction
                        .payload
                        .get("outsideWrite")
                        .and_then(Value::as_bool)
                        .unwrap_or(false)
                    {
                        Some(json!({"kind":"approval","decision":"allow"}))
                    } else {
                        Some(json!({"kind":"approval","decision":"allow","whitelist":true}))
                    }
                }
                KeyCode::Char('d') => Some(json!({"kind":"approval","decision":"deny"})),
                _ => None,
            };
            if let Some(body) = body {
                return vec![Effect::ResolveInteraction {
                    interaction: state.interaction.clone(),
                    body,
                }];
            }
        } else if kind == "plan_review" && !state.rejecting {
            match key.code {
                KeyCode::Char('a') | KeyCode::Enter => {
                    return vec![Effect::ResolveInteraction {
                        interaction: state.interaction.clone(),
                        body: json!({"kind":"plan_review","decision":"approve"}),
                    }];
                }
                KeyCode::Char('r') => state.rejecting = true,
                _ => {}
            }
        } else {
            match key.code {
                KeyCode::Enter if self.input_keys.is_send(key) => {
                    let input = state.input.take();
                    if kind == "question" && input.trim().is_empty() {
                        self.overlay = Some(Overlay::Interaction(state.clone()));
                        return Vec::new();
                    }
                    let body = if kind == "question" {
                        json!({"kind":"question","answer":input})
                    } else {
                        json!({"kind":"plan_review","decision":"reject","feedback":input})
                    };
                    return vec![Effect::ResolveInteraction {
                        interaction: state.interaction.clone(),
                        body,
                    }];
                }
                KeyCode::Backspace => state.input.backspace(),
                KeyCode::Delete => state.input.delete(),
                KeyCode::Left => state.input.left(),
                KeyCode::Right => state.input.right(),
                KeyCode::Char(character) if !key.modifiers.contains(KeyModifiers::CONTROL) => {
                    state.input.insert(character)
                }
                _ => {}
            }
        }
        self.overlay = Some(Overlay::Interaction(state.clone()));
        Vec::new()
    }

    fn handle_changes_key(&mut self, state: &mut ChangesState, key: KeyEvent) -> Vec<Effect> {
        if state.diff.is_some() {
            match key.code {
                KeyCode::Esc => {
                    state.diff = None;
                    state.scroll = 0;
                }
                KeyCode::Up => state.scroll = state.scroll.saturating_sub(1),
                KeyCode::Down => state.scroll = state.scroll.saturating_add(1),
                KeyCode::PageUp => state.scroll = state.scroll.saturating_sub(10),
                KeyCode::PageDown => state.scroll = state.scroll.saturating_add(10),
                KeyCode::Home => state.scroll = 0,
                _ => {}
            }
            self.overlay = Some(Overlay::Changes(state.clone()));
            return Vec::new();
        }
        match key.code {
            KeyCode::Esc => Vec::new(),
            KeyCode::Up => {
                state.selected = state.selected.saturating_sub(1);
                self.overlay = Some(Overlay::Changes(state.clone()));
                Vec::new()
            }
            KeyCode::Down => {
                state.selected =
                    (state.selected + 1).min(state.view.entries.len().saturating_sub(1));
                self.overlay = Some(Overlay::Changes(state.clone()));
                Vec::new()
            }
            KeyCode::Enter => state
                .view
                .entries
                .get(state.selected)
                .filter(|entry| entry.diff_available)
                .map(|entry| {
                    vec![Effect::LoadDiff {
                        run_id: state.view.run_id.clone(),
                        path: entry.path.clone(),
                    }]
                })
                .unwrap_or_else(|| {
                    self.overlay = Some(Overlay::Changes(state.clone()));
                    Vec::new()
                }),
            _ => {
                self.overlay = Some(Overlay::Changes(state.clone()));
                Vec::new()
            }
        }
    }

    pub fn set_changes(&mut self, view: ChangesView) {
        if !self.expects_panel(PanelKind::Changes) {
            return;
        }
        self.overlay = Some(Overlay::Changes(ChangesState {
            view,
            selected: 0,
            diff: None,
            scroll: 0,
        }));
    }

    pub fn set_diff(&mut self, diff: DiffView) {
        if let Some(Overlay::Changes(state)) = self.overlay.as_mut() {
            state.diff = Some(diff);
            state.scroll = 0;
        }
    }

    fn open_run_inputs(&mut self) -> Vec<Effect> {
        let run_id = self
            .live_run_id()
            .or_else(|| self.run.as_ref().map(|run| run.run_id.as_str()))
            .map(ToString::to_string);
        if let Some(run_id) = run_id {
            self.overlay = Some(Overlay::Loading(PanelKind::Inputs));
            vec![Effect::LoadRunInputs(run_id)]
        } else {
            self.push_notice("No run selected");
            Vec::new()
        }
    }

    fn open_goal(&mut self) -> Vec<Effect> {
        if let Some(thread_id) = self.thread_id.clone() {
            self.overlay = Some(Overlay::Loading(PanelKind::Goal));
            vec![Effect::LoadGoal(thread_id)]
        } else {
            self.push_notice("No thread selected");
            Vec::new()
        }
    }

    fn handle_run_inputs_key(&mut self, state: &mut RunInputsState, key: KeyEvent) -> Vec<Effect> {
        match key.code {
            KeyCode::Esc => Vec::new(),
            KeyCode::Up => {
                state.selected = state.selected.saturating_sub(1);
                self.overlay = Some(Overlay::RunInputs(state.clone()));
                Vec::new()
            }
            KeyCode::Down => {
                state.selected = (state.selected + 1).min(state.inputs.len().saturating_sub(1));
                self.overlay = Some(Overlay::RunInputs(state.clone()));
                Vec::new()
            }
            KeyCode::Char('d') => state
                .inputs
                .get(state.selected)
                .filter(|input| input.withdrawable())
                .map(|input| {
                    vec![Effect::WithdrawInput {
                        run_id: input.run_id.clone(),
                        input_id: input.input_id.clone(),
                    }]
                })
                .unwrap_or_else(|| {
                    self.push_notice("Only queued or held inputs can be withdrawn");
                    self.overlay = Some(Overlay::RunInputs(state.clone()));
                    Vec::new()
                }),
            _ => {
                self.overlay = Some(Overlay::RunInputs(state.clone()));
                Vec::new()
            }
        }
    }

    fn handle_snapshots_key(&mut self, state: &mut SnapshotsState, key: KeyEvent) -> Vec<Effect> {
        match key.code {
            KeyCode::Esc => Vec::new(),
            KeyCode::Up => {
                state.selected = state.selected.saturating_sub(1);
                self.overlay = Some(Overlay::Snapshots(state.clone()));
                Vec::new()
            }
            KeyCode::Down => {
                state.selected = (state.selected + 1).min(state.snapshots.len().saturating_sub(1));
                self.overlay = Some(Overlay::Snapshots(state.clone()));
                Vec::new()
            }
            KeyCode::Char('c') | KeyCode::Char('r') => {
                let mode = if key.code == KeyCode::Char('r') {
                    "conversation_and_files"
                } else {
                    "conversation_only"
                };
                match (
                    self.thread_id.clone(),
                    state.snapshots.get(state.selected).cloned(),
                ) {
                    (Some(thread_id), Some(snapshot)) => vec![Effect::LoadRestorePlan {
                        thread_id,
                        snapshot,
                        mode: mode.to_string(),
                    }],
                    _ => Vec::new(),
                }
            }
            KeyCode::Char('b') => {
                match (self.thread_id.clone(), state.snapshots.get(state.selected)) {
                    (Some(thread_id), Some(snapshot)) => vec![Effect::BranchSnapshot {
                        thread_id,
                        seq: snapshot.seq,
                    }],
                    _ => Vec::new(),
                }
            }
            _ => {
                self.overlay = Some(Overlay::Snapshots(state.clone()));
                Vec::new()
            }
        }
    }

    fn handle_restore_key(&mut self, state: RestoreState, key: KeyEvent) -> Vec<Effect> {
        match key.code {
            KeyCode::Enter => self
                .thread_id
                .clone()
                .map(|thread_id| {
                    vec![Effect::RestoreSnapshot {
                        thread_id,
                        seq: state.snapshot.seq,
                        mode: state.plan.mode.clone(),
                        history_revision: state.plan.history_revision,
                        command_id: uuid::Uuid::new_v4().to_string(),
                    }]
                })
                .unwrap_or_default(),
            KeyCode::Esc => Vec::new(),
            _ => {
                self.overlay = Some(Overlay::Restore(state));
                Vec::new()
            }
        }
    }

    fn handle_goal_key(&mut self, state: &mut GoalState, key: KeyEvent) -> Vec<Effect> {
        if state.reviewing {
            match key.code {
                KeyCode::Esc => state.reviewing = false,
                KeyCode::Enter if self.input_keys.is_send(key) => {
                    let feedback = state.feedback.take();
                    if let Some(run) = self.run.clone() {
                        return vec![Effect::GoalReview {
                            run,
                            decision: "feedback".to_string(),
                            feedback: Some(feedback),
                        }];
                    }
                }
                KeyCode::Backspace => state.feedback.backspace(),
                KeyCode::Delete => state.feedback.delete(),
                KeyCode::Left => state.feedback.left(),
                KeyCode::Right => state.feedback.right(),
                KeyCode::Char(character) if !key.modifiers.contains(KeyModifiers::CONTROL) => {
                    state.feedback.insert(character)
                }
                _ => {}
            }
            self.overlay = Some(Overlay::Goal(state.clone()));
            return Vec::new();
        }
        let Some(run) = self.run.clone() else {
            if key.code != KeyCode::Esc {
                self.overlay = Some(Overlay::Goal(state.clone()));
            }
            return Vec::new();
        };
        match key.code {
            KeyCode::Esc => Vec::new(),
            KeyCode::Char('p') => vec![Effect::GoalControl {
                run,
                action: "pause".to_string(),
            }],
            KeyCode::Char('r') => vec![Effect::GoalControl {
                run,
                action: "resume".to_string(),
            }],
            KeyCode::Char('c') => vec![Effect::GoalControl {
                run,
                action: "cancel".to_string(),
            }],
            KeyCode::Char('a') => vec![Effect::GoalReview {
                run,
                decision: "confirm".to_string(),
                feedback: None,
            }],
            KeyCode::Char('f') => {
                state.reviewing = true;
                self.overlay = Some(Overlay::Goal(state.clone()));
                Vec::new()
            }
            _ => {
                self.overlay = Some(Overlay::Goal(state.clone()));
                Vec::new()
            }
        }
    }

    pub fn set_run_inputs(&mut self, inputs: Vec<RunInputView>) {
        if !self.expects_panel(PanelKind::Inputs) {
            return;
        }
        self.overlay = Some(Overlay::RunInputs(RunInputsState {
            inputs,
            selected: 0,
        }));
    }

    pub fn set_snapshots(&mut self, snapshots: Vec<SnapshotView>) {
        if !self.expects_panel(PanelKind::Snapshots) {
            return;
        }
        self.overlay = Some(Overlay::Snapshots(SnapshotsState {
            snapshots,
            selected: 0,
        }));
    }

    pub fn set_restore_plan(&mut self, snapshot: SnapshotView, plan: RestorePlanView) {
        if !self.expects_panel(PanelKind::Restore) {
            return;
        }
        self.overlay = Some(Overlay::Restore(RestoreState { snapshot, plan }));
    }

    pub fn set_goals(&mut self, goals: Vec<GoalView>) {
        self.goal = goals
            .iter()
            .find(|goal| !matches!(goal.status.as_str(), "COMPLETED" | "CANCELLED" | "FAILED"))
            .cloned()
            .or_else(|| goals.into_iter().next());
        if !self.expects_panel(PanelKind::Goal) {
            return;
        }
        if let Some(goal) = self.goal.clone() {
            self.overlay = Some(Overlay::Goal(GoalState {
                goal,
                feedback: Composer::default(),
                reviewing: false,
            }));
        } else {
            self.back();
            self.push_notice("No Goal for this thread | type /goal <objective> to create one");
        }
    }

    pub fn set_todos(&mut self, todos: Vec<TodoView>) {
        if !self.expects_panel(PanelKind::Todos) {
            return;
        }
        self.overlay = Some(Overlay::Todos(todos));
    }

    pub fn set_skills(&mut self, skills: Vec<SkillView>) {
        self.skills = skills;
    }

    pub fn set_file_results(&mut self, query: &str, results: Vec<FileSearchHit>) {
        if let Some(Overlay::Picker(picker)) = self.overlay.as_mut() {
            if matches!(picker.kind, PickerKind::Files) && picker.query == query {
                self.file_results = results;
                picker.selected = 0;
            }
        }
    }
}

fn command_description(command: &CommandView) -> &str {
    super::i18n::text(match command.name.as_str() {
        "skill" => "Insert a skill reference",
        "plan" => "Explore, then review a plan before execution",
        "goal" => "Work toward a goal with a budget",
        _ => &command.description,
    })
}

fn tool_input(data: &Value) -> Option<(String, String)> {
    let input = data.get("input").or_else(|| data.get("arguments"))?;
    let decoded = input
        .as_str()
        .and_then(|value| serde_json::from_str::<Value>(value).ok());
    let input = decoded.as_ref().unwrap_or(input);
    if let Value::Object(map) = input {
        for (key, label) in [
            ("command", "Command"),
            ("path", "Path"),
            ("pattern", "Pattern"),
            ("url", "URL"),
            ("task", "Task"),
        ] {
            if let Some(value) = map
                .get(key)
                .and_then(Value::as_str)
                .filter(|value| !value.is_empty())
            {
                return Some((label.into(), value.into()));
            }
        }
        if map.is_empty() {
            return None;
        }
    }
    let value = match input {
        Value::Null => return None,
        Value::String(value) => value.clone(),
        value => serde_json::to_string_pretty(value).unwrap_or_default(),
    };
    (!value.is_empty()).then_some(("Input".into(), value))
}

fn truncate(value: &str, max: usize) -> String {
    let mut graphemes = value.graphemes(true);
    let shortened = graphemes.by_ref().take(max).collect::<String>();
    if graphemes.next().is_some() {
        format!("{shortened}...")
    } else {
        shortened
    }
}

fn string_field(value: &Value, key: &str) -> Option<String> {
    value
        .get(key)
        .and_then(Value::as_str)
        .map(ToString::to_string)
}

fn user_title(channel: Option<&str>) -> String {
    match channel.unwrap_or("") {
        "" | "tui" | "webui" | "vscode" | "cli" => "You".to_string(),
        channel => format!("You | {channel}"),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn choose(app: &mut App, id: &str) -> Vec<Effect> {
        let Some(Overlay::Picker(mut picker)) = app.overlay.clone() else {
            panic!("expected a picker")
        };
        picker.selected = app
            .picker_entries(&picker)
            .iter()
            .position(|(key, _)| key == id)
            .expect("visible command");
        app.overlay = Some(Overlay::Picker(picker));
        app.handle_key(KeyEvent::new(KeyCode::Enter, KeyModifiers::NONE))
    }

    fn command_home(app: &mut App) {
        app.handle_key(KeyEvent::new(KeyCode::Char('p'), KeyModifiers::CONTROL));
    }

    #[test]
    fn theme_picker_returns_to_its_parent_without_sending_the_draft() {
        let mut app = app();
        app.composer.text = "keep this draft".into();
        command_home(&mut app);
        choose(&mut app, "display");
        choose(&mut app, "theme");
        let effects = choose(&mut app, "light");
        assert!(matches!(effects.as_slice(), [Effect::SetTheme(name)] if name == "light"));
        assert!(matches!(
            app.overlay,
            Some(Overlay::Picker(PickerState {
                kind: PickerKind::CommandGroup(CommandGroup::Display),
                ..
            }))
        ));
        assert_eq!(app.composer.text, "keep this draft");
    }

    #[test]
    fn palette_scopes_commands_and_explains_unavailable_actions() {
        let mut app = app();
        command_home(&mut app);
        let Some(Overlay::Picker(root)) = app.overlay.clone() else {
            panic!()
        };
        assert_eq!(
            app.picker_entries(&root)
                .iter()
                .map(|(id, _)| id.as_str())
                .collect::<Vec<_>>(),
            [
                "new",
                "threads",
                "conversation",
                "task",
                "display",
                "help",
                "quit",
                "clear-input",
                "history",
                "preview-input",
                "edit-input",
                "copy"
            ]
        );
        assert!(choose(&mut app, "task").is_empty());
        assert!(app.notice.as_ref().unwrap().0.contains("No task"));
        assert!(matches!(
            app.overlay,
            Some(Overlay::Picker(PickerState {
                kind: PickerKind::Commands,
                ..
            }))
        ));
        choose(&mut app, "conversation");
        let Some(Overlay::Picker(conversation)) = app.overlay.clone() else {
            panic!()
        };
        let entries = app.picker_entries(&conversation);
        assert!(entries.iter().any(|(id, _)| id == "models"));
        assert!(entries.iter().any(|(id, _)| id == "snapshots"));
        assert!(!entries
            .iter()
            .any(|(id, _)| matches!(id.as_str(), "changes" | "language" | "stop")));
        assert!(choose(&mut app, "snapshots").is_empty());
        assert!(app.notice.as_ref().unwrap().0.contains("Send a message"));
    }

    #[test]
    fn escape_returns_one_level_and_preserves_the_draft_and_selection() {
        let mut app = app();
        app.composer.text = "unsent draft".into();
        command_home(&mut app);
        choose(&mut app, "conversation");
        choose(&mut app, "mode");
        choose(&mut app, "yolo");
        assert!(matches!(app.overlay, Some(Overlay::ConfirmYolo)));
        for expected in ["permissions", "conversation", "home", "closed"] {
            assert!(app
                .handle_key(KeyEvent::new(KeyCode::Esc, KeyModifiers::NONE))
                .is_empty());
            match expected {
                "permissions" => assert!(matches!(
                    app.overlay,
                    Some(Overlay::Picker(PickerState {
                        kind: PickerKind::Permissions,
                        selected: 2,
                        ..
                    }))
                )),
                "conversation" => assert!(matches!(
                    app.overlay,
                    Some(Overlay::Picker(PickerState {
                        kind: PickerKind::CommandGroup(CommandGroup::Conversation),
                        selected: 2,
                        ..
                    }))
                )),
                "home" => assert!(matches!(
                    app.overlay,
                    Some(Overlay::Picker(PickerState {
                        kind: PickerKind::Commands,
                        selected: 2,
                        ..
                    }))
                )),
                _ => assert!(app.overlay.is_none()),
            }
        }
        assert_eq!(app.permission_mode, "ask");
        assert_eq!(app.composer.text, "unsent draft");
    }

    #[test]
    fn search_crosses_groups_and_back_restores_the_search() {
        let mut app = app();
        command_home(&mut app);
        for ch in "language".chars() {
            app.handle_key(KeyEvent::new(KeyCode::Char(ch), KeyModifiers::NONE));
        }
        choose(&mut app, "language");
        app.handle_key(KeyEvent::new(KeyCode::Esc, KeyModifiers::NONE));
        let Some(Overlay::Picker(picker)) = &app.overlay else {
            panic!()
        };
        assert_eq!(picker.query, "language");
        assert!(app.picker_entries(picker)[0].1.contains("Settings"));
    }

    #[test]
    fn leaving_a_loading_panel_ignores_its_late_result() {
        let mut app = app();
        app.thread_id = Some("thread".into());
        app.run = Some(RunView {
            run_id: "run".into(),
            status: "COMPLETED".into(),
            ..Default::default()
        });
        command_home(&mut app);
        choose(&mut app, "task");
        assert!(matches!(
            choose(&mut app, "changes").as_slice(),
            [Effect::LoadChanges(_)]
        ));
        assert!(matches!(
            app.overlay,
            Some(Overlay::Loading(PanelKind::Changes))
        ));
        app.handle_key(KeyEvent::new(KeyCode::Esc, KeyModifiers::NONE));
        app.set_changes(ChangesView::default());
        app.set_goals(vec![GoalView::default()]);
        assert!(matches!(
            app.overlay,
            Some(Overlay::Picker(PickerState {
                kind: PickerKind::CommandGroup(CommandGroup::Task),
                ..
            }))
        ));
    }

    #[test]
    fn diff_returns_to_changes_then_to_task_commands() {
        let mut app = app();
        app.run = Some(RunView {
            run_id: "run".into(),
            status: "COMPLETED".into(),
            ..Default::default()
        });
        command_home(&mut app);
        choose(&mut app, "task");
        choose(&mut app, "changes");
        app.set_changes(ChangesView {
            run_id: "run".into(),
            entries: vec![super::super::api::ChangeEntry {
                path: "file.txt".into(),
                diff_available: true,
                ..Default::default()
            }],
            ..Default::default()
        });
        let effects = app.handle_key(KeyEvent::new(KeyCode::Enter, KeyModifiers::NONE));
        assert!(matches!(effects.as_slice(), [Effect::LoadDiff { .. }]));
        app.set_diff(DiffView::default());
        app.handle_key(KeyEvent::new(KeyCode::Esc, KeyModifiers::NONE));
        assert!(matches!(
            app.overlay,
            Some(Overlay::Changes(ChangesState { diff: None, .. }))
        ));
        app.handle_key(KeyEvent::new(KeyCode::Esc, KeyModifiers::NONE));
        assert!(matches!(
            app.overlay,
            Some(Overlay::Picker(PickerState {
                kind: PickerKind::CommandGroup(CommandGroup::Task),
                ..
            }))
        ));
    }

    #[test]
    fn dismissed_approval_does_not_reopen_on_poll() {
        let mut app = app();
        let pending = InteractionView {
            call_id: "approval-1".into(),
            kind: "approval".into(),
            ..Default::default()
        };
        app.set_interactions(vec![pending.clone()]);
        app.handle_key(KeyEvent::new(KeyCode::Esc, KeyModifiers::NONE));
        app.set_interactions(vec![pending]);
        assert!(app.overlay.is_none());
        assert_eq!(
            app.interactions.len(),
            1,
            "dismissal does not approve or deny the tool"
        );
    }

    #[test]
    fn chinese_commands_search_and_language_picker_keep_protocol_and_content_intact() {
        super::super::i18n::set(super::super::i18n::Language::Chinese);
        let mut app = App::new(WorkspaceView::default(), Vec::new(), None, None, false);
        app.composer.text = "Keep this English input".into();
        let picker = PickerState {
            kind: PickerKind::Commands,
            query: "历史".into(),
            selected: 0,
        };
        assert_eq!(
            app.picker_entries(&picker),
            vec![
                ("threads".into(), "选择历史会话".into()),
                ("history".into(), "搜索历史输入".into())
            ]
        );
        assert!(matches!(
            app.activate_picker(&picker).as_slice(),
            [Effect::LoadThreads]
        ));
        let language = PickerState {
            kind: PickerKind::Language,
            query: "en".into(),
            selected: 0,
        };
        assert!(matches!(
            app.activate_picker(&language).as_slice(),
            [Effect::SetLanguage(super::super::i18n::Language::English)]
        ));
        assert_eq!(app.permission_mode, "ask");
        assert_eq!(app.composer.text, "Keep this English input");
        super::super::i18n::set(super::super::i18n::Language::English);
        assert_eq!(app.thread_label(), "New thread");
        app.thread_title = "New thread".into();
        super::super::i18n::set(super::super::i18n::Language::Chinese);
        assert_eq!(
            app.thread_label(),
            "New thread",
            "user-defined titles must not be translated"
        );
        super::super::i18n::set(super::super::i18n::Language::English);
    }
    use crossterm::event::{KeyCode, KeyEvent, KeyModifiers};

    fn app() -> App {
        App::new(
            WorkspaceView {
                name: "Harnex".to_string(),
                root: "D:/code/Harnex".to_string(),
                ..WorkspaceView::default()
            },
            Vec::new(),
            None,
            None,
            false,
        )
    }

    #[test]
    fn composer_edits_cjk_by_grapheme() {
        let mut composer = Composer::default();
        for character in "你好a".chars() {
            composer.insert(character);
        }
        composer.left();
        composer.backspace();
        assert_eq!(composer.text, "你a");
        assert_eq!(composer.cursor, "你".len());
    }

    fn effort_models() -> Vec<ModelOption> {
        vec![
            ModelOption {
                provider: "provider".into(),
                model: "reasoner".into(),
                default: true,
                reasoning_efforts: vec!["low".into(), "medium".into(), "high".into()],
                default_reasoning_effort: "medium".into(),
                ..Default::default()
            },
            ModelOption {
                provider: "provider".into(),
                model: "tool-limited".into(),
                reasoning_efforts: vec!["none".into(), "high".into()],
                function_tool_reasoning_effort: Some("none".into()),
                ..Default::default()
            },
            ModelOption {
                provider: "provider".into(),
                model: "plain".into(),
                ..Default::default()
            },
        ]
    }

    #[test]
    fn effort_pins_default_model_preserves_draft_and_is_sent_with_the_next_task() {
        let mut app = app();
        app.composer.replace("unchanged 中文 draft".into());
        let effects = app.handle_key(KeyEvent::new(KeyCode::F(3), KeyModifiers::NONE));
        assert!(matches!(effects.as_slice(), [Effect::LoadModels]));
        app.set_models(effort_models());
        assert!(choose(&mut app, "high").is_empty());
        assert_eq!(app.selected_provider.as_deref(), Some("provider"));
        assert_eq!(app.selected_model.as_deref(), Some("reasoner"));
        assert_eq!(app.composer.text, "unchanged 中文 draft");
        assert!(
            matches!(app.handle_key(KeyEvent::new(KeyCode::Enter, KeyModifiers::NONE)).as_slice(), [Effect::CreateRun { model: Some(model), reasoning_effort, .. }] if model == "reasoner" && reasoning_effort == "high")
        );
        let mut reopened = super::tests::app();
        reopened.restore_memory(app.memory(), true);
        reopened.set_models(effort_models());
        assert_eq!(reopened.selected_effort, "high");
        assert_eq!(reopened.selected_model, app.selected_model);
        assert_eq!(reopened.composer.text, app.composer.text);
    }

    #[test]
    fn effort_respects_tool_constraints_and_resets_after_switching_models() {
        let mut app = app();
        app.set_models(effort_models());
        app.choose_model("provider".into(), "reasoner".into(), true);
        app.selected_effort = "high".into();
        app.choose_model("provider".into(), "tool-limited".into(), true);
        assert_eq!(app.selected_effort, "auto");
        assert_eq!(app.effort_choices(), ["none"]);
        app.agent_available = false;
        assert_eq!(app.effort_choices(), ["none", "high"]);
        app.selected_effort = "high".into();
        app.choose_model("provider".into(), "plain".into(), true);
        assert!(app.effort_choices().is_empty());
        assert_eq!(app.selected_effort, "auto");
        app.composer.replace("preserved".into());
        command_home(&mut app);
        let Some(Overlay::Picker(mut picker)) = app.overlay.clone() else {
            panic!()
        };
        picker.query = "effort".into();
        assert!(app
            .picker_entries(&picker)
            .iter()
            .any(|(id, _)| id == "effort"));
        picker.query = "settings".into();
        assert!(app
            .picker_entries(&picker)
            .iter()
            .any(|(id, _)| id == "display"));
    }

    #[test]
    fn leader_cancel_and_effort_shortcut_do_not_edit_or_submit_a_draft() {
        let mut app = app();
        app.composer.replace("keep me".into());
        for second in [KeyCode::Esc, KeyCode::Char('r')] {
            app.handle_key(KeyEvent::new(KeyCode::Char('x'), KeyModifiers::CONTROL));
            let effects = app.handle_key(KeyEvent::new(second, KeyModifiers::NONE));
            assert!(!effects
                .iter()
                .any(|effect| matches!(effect, Effect::CreateRun { .. } | Effect::CancelRun(_))));
            assert_eq!(app.composer.text, "keep me");
        }
        assert!(matches!(
            app.overlay,
            Some(Overlay::Picker(PickerState {
                kind: PickerKind::Efforts,
                ..
            }))
        ));
    }

    #[test]
    fn text_deltas_coalesce_into_one_assistant_item() {
        let mut app = app();
        for text in ["hello", " world"] {
            app.apply_stream(StreamEvent {
                kind: "text_delta".to_string(),
                data: json!({"text": text}),
                ..StreamEvent::default()
            });
        }
        assert_eq!(app.timeline.len(), 1);
        assert_eq!(app.timeline[0].content, "hello world");
    }

    #[test]
    fn ordinary_model_events_advance_queued_runs_without_run_started() {
        for kind in [
            "message_start",
            "text_delta",
            "thinking_delta",
            "tool_use_start",
        ] {
            let mut app = app();
            app.run = Some(RunView {
                status: "QUEUED".into(),
                ..Default::default()
            });
            assert_eq!(app.run_status(), "QUEUED");
            app.apply_stream(StreamEvent {
                kind: "notice".into(),
                data: json!({"message":"accepted"}),
                ..Default::default()
            });
            assert_eq!(
                app.run_status(),
                "QUEUED",
                "admission alone is not execution"
            );
            app.apply_stream(StreamEvent {
                kind: kind.into(),
                data: json!({"text":"answer", "id":"call", "name":"shell"}),
                ..Default::default()
            });
            assert_eq!(app.run_status(), "RUNNING", "{kind}");
            assert!(app.run.as_ref().unwrap().executing);
            app.apply_stream(StreamEvent {
                kind: "done".into(),
                data: json!({"status":"COMPLETED"}),
                ..Default::default()
            });
            assert!(!app.run.as_ref().unwrap().executing);
            app.apply_stream(StreamEvent {
                kind: kind.into(),
                data: json!({"text":"late"}),
                ..Default::default()
            });
            assert_eq!(app.run_status(), "COMPLETED");
            assert!(!app.timeline.iter().any(|item| item.running));
        }
    }

    #[test]
    fn usage_accumulates_once_and_child_calls_do_not_change_the_selected_model() {
        let mut app = app();
        app.run = Some(RunView {
            status: "RUNNING".into(),
            ..Default::default()
        });
        let event = StreamEvent {
            kind: "usage".into(),
            seq: 1,
            data: json!({"prompt":1000,"completion":200,"cachedInput":300,"model":"root-model","complete":true}),
            ..Default::default()
        };
        app.apply_stream(event.clone());
        app.apply_stream(event);
        app.apply_stream(StreamEvent { kind:"usage".into(), seq:2, data:json!({"prompt":2000,"completion":300,"cachedInput":700,"model":"child-model","subagentThreadId":"child"}), ..Default::default() });
        let usage = app.usage().unwrap();
        assert_eq!(
            (
                usage.prompt,
                usage.completion,
                usage.total,
                usage.cached_input
            ),
            (3000, 500, 3500, 1000)
        );
        assert!((usage.cache_hit_rate().unwrap() - 100.0 / 3.0).abs() < 0.001);
        assert!(!usage.complete);
        assert_eq!(app.actual_model(), "root-model");
        app.apply_stream(StreamEvent {
            kind: "done".into(),
            seq: 3,
            data: json!({"status":"COMPLETED","execution":{"usageComplete":true}}),
            ..Default::default()
        });
        assert!(app.usage().unwrap().complete);
    }

    #[test]
    fn compact_presentation_keeps_the_received_full_tool_output_and_scroll_position() {
        use super::super::api::{PresentationBundle, PresentationView};
        let mut app = app();
        app.thread_id = Some("thread".into());
        app.run = Some(RunView {
            run_id: "run".into(),
            status: "RUNNING".into(),
            ..Default::default()
        });
        app.apply_stream(StreamEvent {
            kind: "tool_use_start".into(),
            seq: 1,
            data: json!({"id":"tool","name":"shell","arguments":"{\"command\":\"run test\"}"}),
            ..Default::default()
        });
        let full = format!("{}END_OF_FULL_OUTPUT", "output\n".repeat(5000));
        app.apply_stream(StreamEvent {
            kind: "tool_use_end".into(),
            seq: 2,
            data: json!({"id":"tool","name":"shell","content":full}),
            ..Default::default()
        });
        app.scroll.position(5000, 20, 80);
        app.scroll.move_up(100);
        let top = app.scroll.position(5000, 20, 80);
        let snapshot: PresentationView = serde_json::from_value(json!({"run":{"runId":"run","status":"COMPLETED","terminal":true},"live":{"tools":[{"seq":1,"id":"tool","name":"shell","state":"COMPLETED","content":"short snapshot"}]}})).unwrap();
        let thread = ThreadView {
            id: app.thread_id.clone().unwrap_or_default(),
            ..Default::default()
        };
        app.hydrate_presentation(PresentationBundle { snapshot, thread });
        assert_eq!(app.timeline[0].content, full);
        assert!(app.timeline[0].content_complete);
        assert_eq!(app.scroll.position(5000, 20, 80), top);
    }

    #[test]
    fn tool_boundaries_finish_replies_and_match_parallel_results_by_wire_id() {
        let mut app = app();
        app.run = Some(RunView {
            status: "QUEUED".into(),
            ..Default::default()
        });
        let emit = |app: &mut App, kind: &str, data: Value| {
            app.apply_stream(StreamEvent {
                kind: kind.into(),
                data,
                ..Default::default()
            });
        };
        emit(
            &mut app,
            "text_delta",
            json!({"text":"I will inspect two files."}),
        );
        assert!(app.timeline[0].running);
        emit(
            &mut app,
            "tool_use_queued",
            json!({"id":"a", "name":"file_read", "purpose":"Check test configuration", "arguments":"{\"path\":\"one.rs\"}"}),
        );
        assert!(
            !app.timeline[0].running,
            "AgentLoop does not forward message_end before tools"
        );
        emit(
            &mut app,
            "tool_use_start",
            json!({"id":"a", "name":"file_read"}),
        );
        assert_eq!(
            app.timeline.len(),
            2,
            "queued call becomes running in place"
        );
        emit(
            &mut app,
            "tool_use_start",
            json!({"id":"b", "name":"file_read", "arguments":"{\"path\":\"two.rs\"}"}),
        );
        emit(
            &mut app,
            "tool_use_end",
            json!({"id":"b", "name":"file_read", "content":"second output", "error":false}),
        );
        emit(
            &mut app,
            "tool_use_end",
            json!({"id":"a", "name":"file_read", "content":"first output", "error":false}),
        );
        assert_eq!(
            app.timeline[1].tool_input,
            Some(("Path".into(), "one.rs".into()))
        );
        assert_eq!(app.timeline[1].content, "first output");
        assert_eq!(app.timeline[1].purpose.as_deref(), Some("Check test configuration"));
        assert_eq!(
            app.timeline[2].tool_input,
            Some(("Path".into(), "two.rs".into()))
        );
        assert_eq!(app.timeline[2].content, "second output");
        emit(
            &mut app,
            "message_start",
            json!({"provider":"provider", "model":"model"}),
        );
        emit(
            &mut app,
            "text_delta",
            json!({"text":"Both files checked."}),
        );
        emit(&mut app, "thinking_end", json!({}));
        assert!(
            app.timeline.last().unwrap().running,
            "thinking_end must not close a reply"
        );
        emit(&mut app, "message_end", json!({}));
        assert!(!app.timeline.iter().any(|item| item.running || item.queued));
        assert_eq!(
            app.run.as_ref().unwrap().actual_model.as_deref(),
            Some("model")
        );
    }

    #[test]
    fn presentation_restores_tools_before_the_answer_without_fabricating_activity() {
        use super::super::api::{PresentationBundle, PresentationView};
        for (status, executing) in [("QUEUED", false), ("RUNNING", true), ("COMPLETED", false)] {
            let mut app = app();
            let snapshot: PresentationView = serde_json::from_value(json!({
                "run":{"status":status,"executing":executing},
                "history":{"answer":{"messageId":"answer"}},
                "live":{
                    "text":if executing { "live reply" } else { "" },
                    "messages":[{"seq":2,"text":"Inspecting the file.","afterMessageId":"user"}],
                    "tools":[{"seq":3,"id":"tool","name":"file_read","state":"COMPLETED","purpose":"Check the entry point","arguments":"{\"path\":\"main.rs\"}","content":"file result","afterMessageId":"user"}]
                }
            })).unwrap();
            app.hydrate_presentation(PresentationBundle {
                snapshot,
                thread: ThreadView {
                    messages: vec![
                        MessageView {
                            message_id: "user".into(),
                            role: "user".into(),
                            content: "Check this file".into(),
                            ..Default::default()
                        },
                        MessageView {
                            message_id: "answer".into(),
                            role: "assistant".into(),
                            content: if executing {
                                String::new()
                            } else {
                                "Saved answer".into()
                            },
                            ..Default::default()
                        },
                    ],
                    ..Default::default()
                },
            });
            assert_eq!(app.run_status(), status);
            assert_eq!(app.timeline[1].content, "Inspecting the file.");
            assert_eq!(
                app.timeline[2].tool_input,
                Some(("Path".into(), "main.rs".into()))
            );
            assert_eq!(app.timeline[2].content, "file result");
            assert_eq!(app.timeline[2].purpose.as_deref(), Some("Check the entry point"));
            assert!(!app.timeline[1].running && !app.timeline[2].running);
            assert_eq!(
                app.timeline.last().unwrap().content,
                if executing {
                    "live reply"
                } else {
                    "Saved answer"
                }
            );
            assert_eq!(app.timeline.last().unwrap().running, executing);
        }
    }

    #[test]
    fn preview_commands_do_not_create_or_steer_model_runs() {
        let mut app = app();
        app.composer.text = "/preview reports/my page.html".to_string();
        assert!(
            matches!(app.submit().as_slice(), [Effect::Preview(path)] if path == "reports/my page.html")
        );
        assert!(app.composer.text.is_empty());
        app.composer.text = "/preview artifact:artifact_picture".to_string();
        assert!(
            matches!(app.submit().as_slice(), [Effect::Preview(path)] if path == "artifact:artifact_picture")
        );
        assert!(app.pending_create.is_none());
    }

    #[test]
    fn duplicate_stream_sequence_is_projected_once() {
        let mut app = app();
        app.run = Some(RunView {
            run_id: "run-1".to_string(),
            thread_id: "thread-1".to_string(),
            status: "RUNNING".to_string(),
            ..RunView::default()
        });
        let event = StreamEvent {
            kind: "text_delta".to_string(),
            run_id: Some("run-1".to_string()),
            thread_id: Some("thread-1".to_string()),
            seq: 7,
            stream_epoch: Some(3),
            data: json!({"text":"once"}),
            ..StreamEvent::default()
        };
        app.apply_stream(event.clone());
        app.apply_stream(event);
        assert_eq!(app.timeline.len(), 1);
        assert_eq!(app.timeline[0].content, "once");
    }

    #[test]
    fn multiline_paste_only_changes_the_draft_until_enter() {
        for running in [false, true] {
            let mut app = app();
            if running {
                app.run = Some(RunView {
                    run_id: "run-1".into(),
                    status: "RUNNING".into(),
                    ..RunView::default()
                });
            }
            assert!(app.paste("/goal 中文😀\r\n@file\r\x10last\n").is_empty());
            let expected = "/goal 中文😀\n@file\nlast\n";
            assert_eq!(app.composer.text, expected);
            assert!(app.overlay.is_none());
            assert!(!app.submitting_input());
            assert!(app.timeline.is_empty());
            let effects = app.handle_key(KeyEvent::new(KeyCode::Enter, KeyModifiers::NONE));
            if running {
                assert!(
                    matches!(effects.as_slice(), [Effect::EnqueueInput { input, .. }] if input == expected)
                );
            } else {
                assert!(
                    matches!(effects.as_slice(), [Effect::CreateRun { input, .. }] if input == expected)
                );
            }
            assert!(app
                .handle_key(KeyEvent::new(KeyCode::Enter, KeyModifiers::NONE))
                .is_empty());
        }
    }

    #[test]
    fn only_shift_enter_adds_newlines_in_text_fields() {
        let mut app = app();
        let newline = KeyEvent::new(KeyCode::Enter, KeyModifiers::SHIFT);
        let old_binding = KeyEvent::new(KeyCode::Char('j'), KeyModifiers::CONTROL);
        app.paste("first");
        assert!(app.handle_key(old_binding).is_empty());
        assert_eq!(app.composer.text, "first");
        assert!(app.handle_key(newline).is_empty());
        assert_eq!(app.composer.text, "first\n");
        assert!(!app.submitting_input());
        app.overlay = Some(Overlay::Interaction(InteractionState {
            scroll: 0,
            interaction: InteractionView {
                kind: "question".into(),
                ..Default::default()
            },
            input: Composer::default(),
            rejecting: false,
        }));
        app.paste("answer");
        assert!(app.handle_key(old_binding).is_empty());
        assert!(app.handle_key(newline).is_empty());
        let Some(Overlay::Interaction(state)) = &app.overlay else {
            panic!()
        };
        assert_eq!(state.input.text, "answer\n");
        assert!(
            matches!(app.handle_key(KeyEvent::new(KeyCode::Enter, KeyModifiers::NONE)).as_slice(), [Effect::ResolveInteraction { body, .. }] if body["answer"] == "answer\n")
        );
    }

    #[test]
    fn configurable_input_keys_keep_multiline_drafts_and_live_submission_semantics() {
        for (input_keys, newline, send) in [
            (
                InputKeys::EnterSends,
                KeyModifiers::SHIFT,
                KeyModifiers::NONE,
            ),
            (
                InputKeys::CtrlEnterSends,
                KeyModifiers::NONE,
                KeyModifiers::CONTROL,
            ),
        ] {
            for running in [false, true] {
                let mut app = app();
                app.input_keys = input_keys;
                if running {
                    app.run = Some(RunView {
                        run_id: "run".into(),
                        status: "RUNNING".into(),
                        ..Default::default()
                    });
                }
                app.paste("first");
                assert!(app
                    .handle_key(KeyEvent::new(KeyCode::Enter, newline))
                    .is_empty());
                assert!(app.paste("last\n").is_empty());
                assert_eq!(app.composer.text, "first\nlast\n");
                assert!(!app.submitting_input());
                let effects = app.handle_key(KeyEvent::new(KeyCode::Enter, send));
                if running {
                    assert!(
                        matches!(effects.as_slice(), [Effect::EnqueueInput { input, mode, .. }] if input == "first\nlast\n" && mode == "STEER")
                    );
                } else {
                    assert!(
                        matches!(effects.as_slice(), [Effect::CreateRun { input, .. }] if input == "first\nlast\n")
                    );
                }
            }
        }
    }

    #[test]
    fn ctrl_enter_preset_applies_to_questions_and_feedback_but_keeps_menu_enter() {
        let mut app = app();
        command_home(&mut app);
        choose(&mut app, "display");
        choose(&mut app, "input-keys");
        let effects = choose(&mut app, "ctrl-enter-send");
        assert!(matches!(
            effects.as_slice(),
            [Effect::SetInputKeys(InputKeys::CtrlEnterSends)]
        ));
        assert_eq!(app.input_keys, InputKeys::CtrlEnterSends);
        // The same plain Enter can select the other preset in a menu.
        choose(&mut app, "input-keys");
        assert!(matches!(
            choose(&mut app, "enter-send").as_slice(),
            [Effect::SetInputKeys(InputKeys::EnterSends)]
        ));
        app.input_keys = InputKeys::CtrlEnterSends;
        app.close_navigation();
        for (kind, rejecting, field) in [
            ("question", false, "answer"),
            ("plan_review", true, "feedback"),
        ] {
            app.overlay = Some(Overlay::Interaction(InteractionState {
                scroll: 0,
                interaction: InteractionView {
                    kind: kind.into(),
                    ..Default::default()
                },
                input: Composer::default(),
                rejecting,
            }));
            app.paste("answer");
            assert!(app
                .handle_key(KeyEvent::new(KeyCode::Enter, KeyModifiers::NONE))
                .is_empty());
            assert!(app
                .handle_key(KeyEvent::new(KeyCode::Enter, KeyModifiers::SHIFT))
                .is_empty());
            assert!(
                matches!(app.handle_key(KeyEvent::new(KeyCode::Enter, KeyModifiers::CONTROL)).as_slice(), [Effect::ResolveInteraction { body, .. }] if body[field] == "answer\n")
            );
        }
        app.run = Some(RunView {
            run_id: "run".into(),
            ..Default::default()
        });
        app.overlay = Some(Overlay::Goal(GoalState {
            goal: GoalView::default(),
            feedback: Composer::default(),
            reviewing: true,
        }));
        app.paste("review");
        assert!(app
            .handle_key(KeyEvent::new(KeyCode::Enter, KeyModifiers::NONE))
            .is_empty());
        assert!(
            matches!(app.handle_key(KeyEvent::new(KeyCode::Enter, KeyModifiers::CONTROL)).as_slice(), [Effect::GoalReview { feedback: Some(text), .. }] if text == "review\n")
        );
    }

    #[test]
    fn global_help_clear_undo_and_exit_keep_a_large_draft() {
        let mut app = app();
        let draft = "中文😀 line\n".repeat(4000);
        app.paste(&draft);
        app.handle_key(KeyEvent::new(KeyCode::F(1), KeyModifiers::NONE));
        assert!(matches!(app.overlay, Some(Overlay::Help)));
        assert_eq!(app.composer.text, draft);
        app.handle_key(KeyEvent::new(KeyCode::Char('x'), KeyModifiers::CONTROL));
        let effects = app.handle_key(KeyEvent::new(KeyCode::Char('q'), KeyModifiers::NONE));
        assert!(matches!(effects.as_slice(), [Effect::Quit]));
        assert_eq!(app.memory().draft, draft);
        app.handle_key(KeyEvent::new(KeyCode::F(1), KeyModifiers::NONE));
        app.run = Some(RunView {
            run_id: "run".into(),
            status: "RUNNING".into(),
            ..Default::default()
        });
        app.handle_key(KeyEvent::new(KeyCode::Char('x'), KeyModifiers::CONTROL));
        assert!(app
            .handle_key(KeyEvent::new(KeyCode::Char('c'), KeyModifiers::NONE))
            .is_empty());
        assert!(app.composer.text.is_empty());
        assert!(app.live_run_id().is_some());
        app.handle_key(KeyEvent::new(KeyCode::Char('z'), KeyModifiers::CONTROL));
        assert_eq!(app.composer.text, draft);
        app.handle_key(KeyEvent::new(KeyCode::Char('x'), KeyModifiers::CONTROL));
        assert!(app
            .handle_key(KeyEvent::new(KeyCode::Char('q'), KeyModifiers::NONE))
            .is_empty());
        assert!(matches!(app.overlay, Some(Overlay::ConfirmExit)));
    }

    #[test]
    fn accepted_input_history_search_restores_without_submitting() {
        let mut app = app();
        app.paste("prior 中文\nrequest");
        let request = match &app.submit()[0] {
            Effect::CreateRun { request_id, .. } => request_id.clone(),
            _ => panic!(),
        };
        app.finish_create(&request, false);
        assert!(app.input_history.entries.is_empty());
        let request = match &app.submit()[0] {
            Effect::CreateRun { request_id, .. } => request_id.clone(),
            _ => panic!(),
        };
        app.finish_create(&request, true);
        let saved = app.memory();
        assert_eq!(saved.history, ["prior 中文\nrequest"]);
        app.paste("new unsent draft");
        app.handle_key(KeyEvent::new(KeyCode::Char('r'), KeyModifiers::CONTROL));
        app.paste("中文");
        let effects = app.handle_key(KeyEvent::new(KeyCode::Enter, KeyModifiers::NONE));
        assert!(effects.is_empty());
        assert!(!app.submitting_input());
        assert_eq!(app.composer.text, "prior 中文\nrequest");
        app.handle_key(KeyEvent::new(KeyCode::Char('z'), KeyModifiers::CONTROL));
        assert_eq!(app.composer.text, "new unsent draft");
    }

    #[test]
    fn copy_uses_entire_answer_and_models_keep_draft_and_favorites() {
        let mut app = app();
        let answer = "full answer 中文\n".repeat(4000);
        app.timeline.push(TimelineItem::message(
            TimelineKind::Assistant,
            "Harnex",
            &answer,
        ));
        app.scroll.first();
        app.handle_key(KeyEvent::new(KeyCode::Char('x'), KeyModifiers::CONTROL));
        assert!(
            matches!(app.handle_key(KeyEvent::new(KeyCode::Char('y'), KeyModifiers::NONE)).as_slice(), [Effect::Copy { text, .. }] if text == &answer)
        );
        app.paste("preserve this draft");
        app.models = ["one", "two"]
            .into_iter()
            .map(|model| ModelOption {
                provider: "provider".into(),
                model: model.into(),
                label: model.into(),
                ..Default::default()
            })
            .collect();
        app.handle_key(KeyEvent::new(KeyCode::F(2), KeyModifiers::NONE));
        app.handle_key(KeyEvent::new(KeyCode::Char('f'), KeyModifiers::CONTROL));
        app.handle_key(KeyEvent::new(KeyCode::Enter, KeyModifiers::NONE));
        assert_eq!(app.favorite_models, ["provider:one"]);
        app.choose_model("provider".into(), "two".into(), true);
        app.handle_key(KeyEvent::new(KeyCode::F(2), KeyModifiers::SHIFT));
        assert_eq!(app.selected_model.as_deref(), Some("one"));
        assert_eq!(app.composer.text, "preserve this draft");
        let memory = app.memory();
        let mut reopened = super::App::new(WorkspaceView::default(), vec![], None, None, false);
        reopened.restore_memory(memory, true);
        assert_eq!(reopened.composer.text, app.composer.text);
        assert_eq!(reopened.favorite_models, app.favorite_models);
    }

    #[test]
    fn new_run_draft_clears_only_after_its_own_admission() {
        let mut app = app();
        app.composer.text = "keep this draft".to_string();
        let request_id = match &app.submit()[0] {
            Effect::CreateRun { request_id, .. } => request_id.clone(),
            _ => panic!(),
        };
        assert_eq!(app.composer.text, "keep this draft");
        assert!(app.timeline.is_empty());
        assert!(app.submit().is_empty());
        assert!(!app.finish_create("other-request", true));
        assert!(app.finish_create(&request_id, false));
        assert_eq!(app.composer.text, "keep this draft");
        let request_id = match &app.submit()[0] {
            Effect::CreateRun { request_id, .. } => request_id.clone(),
            _ => panic!(),
        };
        app.composer.text = "newer draft".to_string();
        assert!(app.finish_create(&request_id, true));
        assert_eq!(app.composer.text, "newer draft");
    }

    #[test]
    fn presentation_replaces_live_text_and_rejects_earlier_frames() {
        let mut app = app();
        let sample: Value =
            serde_json::from_str(include_str!("../../../packages/protocol/fixtures.json")).unwrap();
        let run: RunView = serde_json::from_value(sample["run_text_running"].clone()).unwrap();
        let snapshot = super::super::api::PresentationView {
            run: run.clone(),
            cursor: super::super::api::PresentationCursor {
                projection_seq: 20,
                stream_epoch: Some(7),
                replay_available: true,
            },
            live: super::super::api::LiveView {
                text: "snapshot".to_string(),
                ..Default::default()
            },
            ..Default::default()
        };
        app.hydrate_presentation(super::super::api::PresentationBundle {
            snapshot,
            thread: ThreadView {
                id: run.thread_id.clone(),
                ..Default::default()
            },
        });
        for (seq, text) in [(19, "duplicate"), (21, " suffix")] {
            app.apply_stream(StreamEvent {
                kind: "text_delta".to_string(),
                run_id: Some(run.run_id.clone()),
                thread_id: Some(run.thread_id.clone()),
                seq,
                stream_epoch: Some(7),
                data: json!({"text": text}),
                ..Default::default()
            });
        }
        assert_eq!(app.timeline.last().unwrap().content, "snapshot suffix");
    }

    #[test]
    fn live_run_submission_becomes_steering() {
        let mut app = app();
        app.run = Some(RunView {
            run_id: "run-1".to_string(),
            status: "RUNNING".to_string(),
            ..RunView::default()
        });
        app.composer.text = "focus tests".to_string();
        app.composer.cursor = app.composer.text.len();
        let effects = app.submit();
        assert!(
            matches!(effects.as_slice(), [Effect::EnqueueInput { run_id, .. }] if run_id == "run-1")
        );
    }

    #[test]
    fn follow_up_is_a_single_submission_and_default_remains_update() {
        let mut app = app();
        app.run = Some(RunView {
            run_id: "run-1".to_string(),
            status: "RUNNING".to_string(),
            ..RunView::default()
        });
        app.composer.text = "run tests next".to_string();
        app.composer.cursor = app.composer.text.len();
        let effects = app.handle_key(KeyEvent::new(KeyCode::Enter, KeyModifiers::ALT));
        assert!(
            matches!(effects.as_slice(), [Effect::EnqueueInput { mode, .. }] if mode == "QUEUE")
        );
        assert_eq!(app.composer.text, "run tests next");
        assert!(app.submit().is_empty());
        let request_id = match &effects[0] {
            Effect::EnqueueInput { request_id, .. } => request_id.clone(),
            _ => unreachable!(),
        };
        app.finish_run_input(&request_id, Ok(()));
        assert!(app.composer.text.is_empty());
        app.composer.text = "adjust current task".to_string();
        assert!(
            matches!(app.submit().as_slice(), [Effect::EnqueueInput { mode, .. }] if mode == "STEER")
        );
    }

    #[test]
    fn leader_input_chord_is_option_key_fallback() {
        let mut app = app();
        app.run = Some(RunView {
            run_id: "run-1".to_string(),
            status: "RUNNING".to_string(),
            ..RunView::default()
        });
        app.composer.text = "run later".to_string();
        app.handle_key(KeyEvent::new(KeyCode::Char('x'), KeyModifiers::CONTROL));
        let effects = app.handle_key(KeyEvent::new(KeyCode::Char('i'), KeyModifiers::NONE));
        assert!(
            matches!(effects.as_slice(), [Effect::EnqueueInput { mode, .. }] if mode == "QUEUE")
        );
    }

    #[test]
    fn submitted_updates_preserve_new_drafts_and_failed_messages() {
        let mut app = app();
        app.run = Some(RunView {
            run_id: "run-1".to_string(),
            status: "RUNNING".to_string(),
            ..RunView::default()
        });
        app.composer.text = "first".to_string();
        let effects = app.submit();
        let request_id = match &effects[0] {
            Effect::EnqueueInput { request_id, .. } => request_id.clone(),
            _ => unreachable!(),
        };
        app.composer.text = "new draft".to_string();
        app.finish_run_input(&request_id, Ok(()));
        assert_eq!(app.composer.text, "new draft");
        let effects = app.submit();
        let request_id = match &effects[0] {
            Effect::EnqueueInput { request_id, .. } => request_id.clone(),
            _ => unreachable!(),
        };
        app.finish_run_input(
            &request_id,
            Err(ApiError {
                message: "run ended".to_string(),
            }),
        );
        assert_eq!(app.composer.text, "new draft");
        assert!(!app.submitting_input());
    }

    #[test]
    fn escape_closes_overlay_before_stopping_and_keeps_the_draft() {
        let mut app = app();
        app.run = Some(RunView {
            run_id: "run-1".to_string(),
            status: "RUNNING".to_string(),
            ..RunView::default()
        });
        app.composer.text = "keep this".to_string();
        app.overlay = Some(Overlay::Help);
        assert!(app
            .handle_key(KeyEvent::new(KeyCode::Esc, KeyModifiers::NONE))
            .is_empty());
        assert!(app.overlay.is_none());
        assert!(
            matches!(app.handle_key(KeyEvent::new(KeyCode::Esc, KeyModifiers::NONE)).as_slice(), [Effect::CancelRun(id)] if id == "run-1")
        );
        assert_eq!(app.composer.text, "keep this");
    }

    #[test]
    fn queue_shortcut_does_not_start_an_idle_task_and_shift_enter_adds_a_line() {
        let mut app = app();
        app.composer.text = "draft".to_string();
        app.composer.cursor = 5;
        assert!(app
            .handle_key(KeyEvent::new(KeyCode::Enter, KeyModifiers::CONTROL))
            .is_empty());
        assert_eq!(app.composer.text, "draft");
        assert!(app
            .handle_key(KeyEvent::new(KeyCode::Enter, KeyModifiers::SHIFT))
            .is_empty());
        assert_eq!(app.composer.text, "draft\n");
    }

    #[test]
    fn approval_overlay_emits_runtime_resolution() {
        let mut app = app();
        app.overlay = Some(Overlay::Interaction(InteractionState {
            scroll: 0,
            interaction: InteractionView {
                kind: "approval".to_string(),
                thread_id: "thread-1".to_string(),
                call_id: "call-1".to_string(),
                ..InteractionView::default()
            },
            input: Composer::default(),
            rejecting: false,
        }));
        let effects = app.handle_key(KeyEvent::new(KeyCode::Char('a'), KeyModifiers::NONE));
        assert!(
            matches!(effects.as_slice(), [Effect::ResolveInteraction { body, .. }]
            if body["decision"] == "allow")
        );

        app.overlay = Some(Overlay::Interaction(InteractionState {
            scroll: 0,
            interaction: InteractionView {
                kind: "approval".to_string(),
                thread_id: "thread-1".to_string(),
                call_id: "outside-1".to_string(),
                payload: json!({"outsideWrite": true}),
                ..InteractionView::default()
            },
            input: Composer::default(),
            rejecting: false,
        }));
        let outside = app.handle_key(KeyEvent::new(KeyCode::Char('w'), KeyModifiers::NONE));
        assert!(
            matches!(outside.as_slice(), [Effect::ResolveInteraction { body, .. }]
            if body["decision"] == "allow" && body.get("whitelist").is_none())
        );
    }

    #[test]
    fn personal_scope_hides_agent_actions_and_keeps_slash_input_literal() {
        let mut app = app();
        app.agent_available = false;
        for code in ['g', 'k', 'u', 't'] {
            assert!(app
                .handle_key(KeyEvent::new(KeyCode::Char(code), KeyModifiers::CONTROL))
                .is_empty());
            assert!(app.overlay.is_none());
        }
        let permission = app.permission_mode.clone();
        app.handle_key(KeyEvent::new(KeyCode::BackTab, KeyModifiers::SHIFT));
        assert_eq!(app.permission_mode, permission);
        app.composer.text = "/goal literal conversation text".into();
        let effects = app.handle_key(KeyEvent::new(KeyCode::Enter, KeyModifiers::NONE));
        assert!(
            matches!(effects.as_slice(), [Effect::CreateRun { input, .. }] if input == "/goal literal conversation text")
        );
    }

    #[test]
    fn space_switch_keeps_drafts_and_does_not_cancel_a_live_run() {
        let mut app = app();
        app.composer.text = "personal draft".into();
        app.run = Some(RunView {
            run_id: "live-run".into(),
            status: "RUNNING".into(),
            ..Default::default()
        });
        let effects = app.handle_key(KeyEvent::new(KeyCode::F(6), KeyModifiers::NONE));
        assert!(effects.is_empty());
        assert!(app.should_quit && app.switch_scope);
        assert_eq!(app.composer.text, "personal draft");
    }

    #[test]
    fn scope_switch_waits_for_admission_and_is_disabled_for_exact_attach() {
        let mut app = app();
        app.composer.text = "submit first".into();
        app.submit();
        assert!(app.pending_create.is_some());
        app.handle_key(KeyEvent::new(KeyCode::F(6), KeyModifiers::NONE));
        assert!(!app.switch_scope && !app.should_quit);
        app.pending_create = None;
        app.can_switch_scope = false;
        app.handle_key(KeyEvent::new(KeyCode::F(6), KeyModifiers::NONE));
        assert!(!app.switch_scope && !app.should_quit);
    }

    #[test]
    fn quitting_with_a_background_owned_run_requires_confirmation() {
        let mut app = app();
        app.attached = true;
        app.background_active = true;
        let effects = app.handle_key(KeyEvent::new(KeyCode::Char('d'), KeyModifiers::CONTROL));
        assert!(effects.is_empty());
        assert!(matches!(app.overlay, Some(Overlay::ConfirmExit)));
    }

    #[test]
    fn at_key_opens_file_reference_picker() {
        let mut app = app();
        app.handle_key(KeyEvent::new(KeyCode::Char('@'), KeyModifiers::NONE));
        assert!(matches!(
            app.overlay,
            Some(Overlay::Picker(PickerState {
                kind: PickerKind::Files,
                ..
            }))
        ));
    }

    #[test]
    fn model_picker_has_direct_function_and_leader_shortcuts() {
        let cases = [
            vec![KeyEvent::new(KeyCode::Char('m'), KeyModifiers::CONTROL)],
            vec![KeyEvent::new(KeyCode::F(2), KeyModifiers::NONE)],
            vec![
                KeyEvent::new(KeyCode::Char('x'), KeyModifiers::CONTROL),
                KeyEvent::new(KeyCode::Char('m'), KeyModifiers::NONE),
            ],
        ];
        for keys in cases {
            let mut app = app();
            let mut effects = Vec::new();
            for key in keys {
                effects = app.handle_key(key);
            }
            assert!(matches!(effects.as_slice(), [Effect::LoadModels]));
            assert!(matches!(
                app.overlay,
                Some(Overlay::Picker(PickerState {
                    kind: PickerKind::Models,
                    ..
                }))
            ));
        }
    }

    #[test]
    fn context_rail_tracks_resize_until_user_overrides_it() {
        let mut app = app();
        app.sync_rail_width(RAIL_MIN_WIDTH + 40);
        assert!(
            app.details_open,
            "wide terminals use the original context rail layout"
        );
        app.sync_rail_width(RAIL_MIN_WIDTH - 1);
        assert!(!app.details_open);
        app.sync_rail_width(RAIL_MIN_WIDTH);
        assert!(app.details_open);

        app.toggle_rail();
        assert!(!app.details_open);
        app.sync_rail_width(RAIL_MIN_WIDTH + 40);
        assert!(!app.details_open);

        app.auto_rail();
        app.sync_rail_width(RAIL_MIN_WIDTH + 40);
        assert!(app.details_open);
    }

    #[test]
    fn queued_run_input_can_be_withdrawn_from_overlay() {
        let mut app = app();
        app.overlay = Some(Overlay::RunInputs(RunInputsState {
            inputs: vec![RunInputView {
                input_id: "input-1".to_string(),
                run_id: "run-1".to_string(),
                status: "QUEUED".to_string(),
                ..RunInputView::default()
            }],
            selected: 0,
        }));
        let effects = app.handle_key(KeyEvent::new(KeyCode::Char('d'), KeyModifiers::NONE));
        assert!(
            matches!(effects.as_slice(), [Effect::WithdrawInput { input_id, .. }] if input_id == "input-1")
        );
    }

    #[test]
    fn snapshot_restore_requires_plan_before_apply() {
        let mut app = app();
        app.thread_id = Some("thread-1".to_string());
        app.overlay = Some(Overlay::Snapshots(SnapshotsState {
            snapshots: vec![SnapshotView {
                seq: 7,
                label: "pre-tool".to_string(),
                ..SnapshotView::default()
            }],
            selected: 0,
        }));
        let effects = app.handle_key(KeyEvent::new(KeyCode::Char('r'), KeyModifiers::NONE));
        assert!(
            matches!(effects.as_slice(), [Effect::LoadRestorePlan { snapshot, mode, .. }]
            if snapshot.seq == 7 && mode == "conversation_and_files")
        );
    }

    #[test]
    fn timeline_is_bounded_for_long_threads() {
        let mut app = app();
        for index in 0..1_200 {
            app.timeline.push(TimelineItem::message(
                TimelineKind::Notice,
                "event",
                index.to_string(),
            ));
        }
        app.prune_timeline();
        assert_eq!(app.timeline.len(), MAX_TIMELINE_ITEMS);
        assert_eq!(app.timeline[0].content, "200");
    }

    #[test]
    fn slash_picker_uses_runtime_command_catalog() {
        let mut app = app();
        app.commands = vec![CommandView {
            name: "goal".to_string(),
            description: "Create a Goal".to_string(),
            arg_hint: "<objective>".to_string(),
        }];
        let effects = app.handle_key(KeyEvent::new(KeyCode::Char('/'), KeyModifiers::NONE));
        assert!(matches!(effects.as_slice(), [Effect::LoadCommands]));
        let effects = app.handle_key(KeyEvent::new(KeyCode::Enter, KeyModifiers::NONE));
        assert!(effects.is_empty());
        assert_eq!(app.composer.text, "/goal ");
    }

    #[test]
    fn selected_toolset_is_sent_with_new_run() {
        let mut app = app();
        app.selected_toolsets = vec!["act".to_string()];
        app.composer.text = "implement".to_string();
        app.composer.cursor = app.composer.text.len();
        let effects = app.submit();
        assert!(
            matches!(effects.as_slice(), [Effect::CreateRun { toolsets, .. }]
            if toolsets == &["act".to_string()])
        );
    }
    #[test]
    fn incomplete_done_is_not_projected_as_success() {
        let mut state = app();
        state.run = Some(RunView {
            run_id: "run-incomplete".into(),
            status: "RUNNING".into(),
            ..RunView::default()
        });
        let result = state.apply_stream(StreamEvent {
            kind: "done".into(),
            run_id: Some("run-incomplete".into()),
            seq: 1,
            data: json!({"status":"INCOMPLETE", "result":{"detail":"Target required"}}),
            ..StreamEvent::default()
        });
        assert!(result.terminal);
        assert!(!state.run.as_ref().unwrap().is_live());
        assert_eq!(state.run_status(), "INCOMPLETE");
        assert!(state
            .timeline
            .iter()
            .any(|item| item.content.contains("Target required")));
    }

    #[test]
    fn goal_blocker_refreshes_controls_without_completing_the_goal() {
        let mut state = app();
        state.run = Some(RunView {
            run_id: "run-goal".into(),
            status: "RUNNING".into(),
            ..RunView::default()
        });
        let result = state.apply_stream(StreamEvent {
            kind: "goal_state_changed".into(),
            run_id: Some("run-goal".into()),
            seq: 1,
            data: json!({"status":"BLOCKED", "reason":"Target required"}),
            ..StreamEvent::default()
        });
        assert!(!result.terminal);
        assert!(result.refresh_goal);
        assert!(result.refresh_interactions);
        assert_eq!(state.run_status(), "BLOCKED");
    }

    #[test]
    fn steering_withdraws_visible_interactions() {
        let mut state = app();
        let interaction = InteractionView {
            call_id: "approval-old".into(),
            kind: "approval".into(),
            ..InteractionView::default()
        };
        state.set_interactions(vec![interaction]);
        let result = state.apply_stream(StreamEvent {
            kind: "interactions_superseded".into(),
            seq: 1,
            data: json!({"reason":"user_steering"}),
            ..StreamEvent::default()
        });
        assert!(result.refresh_interactions);
        assert!(state.interactions.is_empty());
    }
}
