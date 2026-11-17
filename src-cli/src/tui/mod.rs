use self::i18n::tf;
mod api;
mod app;
mod editor;
pub(crate) mod i18n;
mod input;
mod local;
mod mouse;
pub use local::{Clipboard, SystemClipboard};
mod render;
mod scroll;
mod shortcuts;
pub(crate) mod theme;
mod ui;

use std::io::{self, Stdout, Write};
use std::panic;
use std::time::Duration;

use crossterm::cursor::Show;
use crossterm::event::{
    DisableBracketedPaste, DisableFocusChange, EnableBracketedPaste, EnableFocusChange, Event,
    KeyCode, KeyEvent, KeyEventKind, KeyboardEnhancementFlags, PopKeyboardEnhancementFlags,
    PushKeyboardEnhancementFlags,
};
use crossterm::execute;
use crossterm::terminal::{
    disable_raw_mode, enable_raw_mode, BeginSynchronizedUpdate, DisableLineWrap, EnableLineWrap,
    EndSynchronizedUpdate, EnterAlternateScreen, LeaveAlternateScreen,
};
use ratatui::backend::CrosstermBackend;
use ratatui::Terminal;
use tokio::sync::mpsc;
use tokio::task::JoinHandle;

use self::api::{
    ApiClient, ApiError, ChangesView, CommandView, DiffView, FileSearchHit, GoalView,
    InteractionView, ModelOption, PresentationBundle, RestorePlanView, RunInputView, RunView,
    SkillView, SnapshotView, StreamClose, StreamEvent, ThreadSummary, ThreadView, TodoView,
    ToolsetOption, WorkspaceGitView,
};
use self::app::{App, Effect, PanelKind};
use self::render::StableRenderer;
use self::ui::Theme;
use crate::Session;

type PanicHook = Box<dyn Fn(&panic::PanicHookInfo<'_>) + Send + Sync + 'static>;
struct RestorePanicHook(Option<std::sync::Arc<PanicHook>>);
impl Drop for RestorePanicHook {
    fn drop(&mut self) {
        if std::thread::panicking() {
            return;
        }
        drop(panic::take_hook());
        if let Some(previous) = self.0.take() {
            if let Ok(hook) = std::sync::Arc::try_unwrap(previous) {
                panic::set_hook(hook);
            }
        }
    }
}

#[derive(Clone)]
pub struct Options {
    pub clipboard: std::sync::Arc<dyn Clipboard>,
    pub fullscreen: bool,
    pub theme: String,
    pub no_color: bool,
    pub stable_render: bool,
    pub background_owned_run: bool,
}

#[derive(Clone, Default)]
pub struct ViewState {
    pub theme_name: String,
    pub thread_id: Option<String>,
    pub draft: String,
    pub provider: Option<String>,
    pub model: Option<String>,
    pub reasoning_effort: String,
    pub permission: String,
    pub toolsets: Vec<String>,
    pub owned_run_live: bool,
}
pub struct Exit {
    pub switch_scope: bool,
    pub view: ViewState,
}

fn initial_thread(resume: Option<&ViewState>) -> Option<String> {
    resume.and_then(|view| view.thread_id.clone())
}

pub fn print_help() {
    println!(
        "{}",
        i18n::text(
            "Terminal UI | starts a new conversation; choose history from Ctrl+P > threads."
        )
    );
    println!("harnex tui [--workspace PATH] [--conversation] [--lang auto|zh-CN|en]");
    println!("{}", i18n::text("Language: Ctrl+P > language; --lang overrides HARNEX_LANG, saved preference and system language."));
    println!("--renderer fullscreen|inline  --render-strategy auto|stable|diff");
    println!("--theme auto|dark|light|ansi  --color auto|always|never");
    println!(
        "F1: {} | Ctrl+X → Q: {}",
        i18n::text("Keyboard help"),
        i18n::text("Exit terminal (keep draft)")
    );
}

pub fn run(
    session: Session,
    owns_runtime: bool,
    options: Options,
    can_switch_scope: bool,
    resume: Option<ViewState>,
    notice: Option<String>,
) -> Result<Exit, String> {
    let runtime = tokio::runtime::Builder::new_multi_thread()
        .worker_threads(2)
        .enable_all()
        .thread_name("harnex-tui")
        .build()
        .map_err(|error| error.to_string())?;
    runtime.block_on(run_async(
        session,
        owns_runtime,
        options,
        can_switch_scope,
        resume,
        notice,
    ))
}

async fn run_async(
    session: Session,
    owns_runtime: bool,
    options: Options,
    can_switch_scope: bool,
    resume: Option<ViewState>,
    notice: Option<String>,
) -> Result<Exit, String> {
    let api = ApiClient::new(&session)?;
    let mut workspace = api.workspace().await.map_err(api_string)?;
    if let Some(remote) = &session.manifest.remote {
        workspace.name = format!(
            "SSH {} | {}",
            remote["host"].as_str().unwrap_or("remote"),
            workspace.name
        );
    }
    let threads = api.threads().await.map_err(api_string)?;
    // Only an explicit in-process F6 return restores a conversation. Merely opening
    // a workspace must never submit the user's next message to an old thread.
    let selected = initial_thread(resume.as_ref());
    let (thread, runs, interactions) = if let Some(thread_id) = selected.as_deref() {
        let (thread, runs, interactions) = tokio::join!(
            api.thread(thread_id),
            api.runs(thread_id),
            api.interactions(thread_id)
        );
        (
            Some(thread.map_err(api_string)?),
            runs.map_err(api_string)?,
            interactions.unwrap_or_default(),
        )
    } else {
        (None, Vec::new(), Vec::new())
    };
    let run = select_run(runs);
    let mut app = App::new(workspace, threads, thread, run, !owns_runtime);
    app.input_keys = shortcuts::InputKeys::load();
    app.agent_available = api.agent_available().await.map_err(api_string)?;
    app.can_switch_scope = can_switch_scope;
    app.background_active = options.background_owned_run;
    let memory_path = local::memory_path(
        &session.manifest.runtime_profile,
        &session.manifest.scope_id,
        session
            .manifest
            .remote
            .as_ref()
            .and_then(|remote| remote["host"].as_str())
            .unwrap_or(""),
    );
    let mut saved_memory = match local::load(&memory_path) {
        Ok(memory) => memory,
        Err(error) => {
            app.push_notice(tf!(
                "Could not read saved draft: {error}",
                "无法读取已保存草稿：{error}"
            ));
            local::Memory::default()
        }
    };
    app.restore_memory(saved_memory.clone(), resume.is_none());
    if let Some(view) = resume {
        app.composer.text = view.draft;
        app.composer.cursor = app.composer.text.len();
        app.selected_provider = view.provider;
        app.selected_model = view.model;
        app.selected_effort = if view.reasoning_effort.is_empty() {
            "auto".into()
        } else {
            view.reasoning_effort
        };
        if !view.permission.is_empty() {
            app.permission_mode = view.permission;
        }
        app.selected_toolsets = view.toolsets;
    }
    if let Some(notice) = notice {
        app.push_notice(notice);
    }
    app.set_interactions(interactions);

    app.theme_name = options.theme.clone();
    let terminal_light = theme::terminal_is_light();
    let mut guard = TerminalGuard::enter(
        options.fullscreen,
        options.stable_render,
        app.agent_available,
    )?;
    if let Ok(size) = guard.terminal.size() {
        app.sync_rail_width(size.width);
    }
    let previous_hook = std::sync::Arc::new(panic::take_hook());
    let delegate_hook = previous_hook.clone();
    let panic_fullscreen = options.fullscreen;
    let panic_synchronized = !options.stable_render;
    let panic_keyboard_enhanced = guard.keyboard_enhanced;
    let panic_focus_enabled = guard.focus_enabled;
    panic::set_hook(Box::new(move |info| {
        TerminalGuard::restore(
            panic_fullscreen,
            panic_synchronized,
            panic_keyboard_enhanced,
            panic_focus_enabled,
        );
        delegate_hook(info);
    }));
    let _restore_hook = RestorePanicHook(Some(previous_hook));

    let (backend_tx, mut backend_rx) = mpsc::unbounded_channel::<BackendEvent>();
    app.models_loading = true;
    let catalog_api = api.clone();
    let catalog_sender = backend_tx.clone();
    tokio::spawn(async move {
        let _ = catalog_sender.send(BackendEvent::Models(catalog_api.models().await));
    });
    let (stream_tx, mut stream_rx) = mpsc::channel::<StreamEvent>(512);
    let mut stream_task: Option<JoinHandle<()>> = None;
    if let Some(run) = app.run.as_ref().filter(|run| run.is_live()).cloned() {
        stream_task = Some(spawn_stream(
            api.clone(),
            run,
            stream_tx.clone(),
            backend_tx.clone(),
        ));
    }

    let mut terminal_events = input::TerminalInput::new().map_err(|error| error.to_string())?;
    let mut dirty = true;
    let mut focused = true;
    let mut git_refresh = tokio::time::interval(Duration::from_secs(5));
    git_refresh.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Skip);
    let mut git_task: Option<JoinHandle<()>> = None;
    let mut frames = frame_interval(options.stable_render);
    let mut memory_tick = tokio::time::interval(Duration::from_secs(2));
    memory_tick.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Skip);

    while !app.should_quit {
        tokio::select! {
            _ = memory_tick.tick() => {
                let memory = app.memory();
                if memory != saved_memory {
                    match local::save(&memory_path, &memory) {
                        Ok(()) => saved_memory = memory,
                        Err(error) => { app.push_notice(tf!("Draft could not be saved: {error}", "草稿暂时无法保存：{error}")); dirty = true; }
                    }
                }
            }
            _ = git_refresh.tick(), if app.agent_available && focused => {
                if git_task.is_none() {
                    let api = api.clone();
                    let sender = backend_tx.clone();
                    git_task = Some(tokio::spawn(async move {
                        let _ = sender.send(BackendEvent::WorkspaceGit(api.workspace_git().await));
                    }));
                }
            }
            _ = frames.tick() => {
                if app.expire_notice() { dirty = true; }
                if dirty {
                    if let Ok(size) = guard.terminal.size() {
                        app.sync_rail_width(size.width);
                    }
                    guard.draw(&app, Theme::resolve(&app.theme_name, options.no_color, terminal_light))?;
                    dirty = false;
                }
            }
            Some(event) = stream_rx.recv() => {
                project_stream_event(&mut app, event, &api, &backend_tx);
                // Root completion is reconciled by the stream task's captured presentation.
                dirty = true;
            }
            Some(event) = backend_rx.recv() => {
                match event {
                    BackendEvent::WorkspaceGit(result) => {
                        git_task = None;
                        let next = result.unwrap_or_else(|_| WorkspaceGitView::unavailable());
                        if app.workspace_git != next {
                            app.workspace_git = next;
                            dirty = true;
                        }
                        continue;
                    }
                    BackendEvent::RunCreated { request_id, result } => {
                        if !app.finish_create(&request_id, result.is_ok()) { continue; }
                        match result {
                        Ok(run) => {
                            app.thread_id = Some(run.thread_id.clone());
                            app.run = Some(run.clone());
                            if let Some(task) = stream_task.take() { task.abort(); }
                            stream_task = Some(spawn_stream(api.clone(), run, stream_tx.clone(), backend_tx.clone()));
                            if let Some(thread_id) = app.thread_id.clone() {
                                spawn_interactions(api.clone(), thread_id, backend_tx.clone());
                            }
                        }
                        Err(error) => app.run_rejected(error.message),
                        }
                    },
                    BackendEvent::Presentation { bundle, source_run_id, applied } => {
                        let selected = app.run.as_ref().is_some_and(|run| run.run_id == bundle.snapshot.run.run_id || source_run_id.as_deref() == Some(run.run_id.as_str()));
                        if selected {
                            // The stream producer waits for this snapshot's acknowledgement.
                            // Drain its already queued suffix before advancing the cursor,
                            // retaining full results that a compact snapshot may shorten.
                            while let Ok(event) = stream_rx.try_recv() {
                                project_stream_event(&mut app, event, &api, &backend_tx);
                            }
                            if !bundle.snapshot.run.is_live() { git_refresh.reset_immediately(); }
                            app.hydrate_presentation(bundle);
                        }
                        let _ = applied.send(selected);
                    }
                    BackendEvent::ThreadBundle(result) => match result {
                        Ok((thread, runs, interactions)) => {
                            let selecting = app.expects_panel(PanelKind::Thread);
                            if !selecting && app.thread_id.as_deref() != Some(thread.id.as_str()) { continue; }
                            if selecting { app.close_navigation(); }
                            app.set_thread(thread);
                            app.run = select_run(runs);
                            app.set_interactions(interactions);
                            if let Some(task) = stream_task.take() { task.abort(); }
                            if let Some(run) = app.run.as_ref().filter(|run| run.is_live()).cloned() {
                                stream_task = Some(spawn_stream(api.clone(), run, stream_tx.clone(), backend_tx.clone()));
                            }
                        }
                        Err(error) => app.panel_error(PanelKind::Thread, error.message),
                    },
                    BackendEvent::Threads(result) => match result {
                        Ok(threads) => app.threads = threads,
                        Err(error) => app.push_notice(error.message),
                    },
                    BackendEvent::Interactions(result) => match result {
                        Ok(interactions) => app.set_interactions(interactions),
                        Err(error) => app.push_notice(error.message),
                    },
                    BackendEvent::InteractionResolved(result) => match result {
                        Ok(thread_id) => {
                            app.push_notice("Interaction resolved");
                            spawn_interactions(api.clone(), thread_id, backend_tx.clone());
                        }
                        Err(error) => app.push_notice(error.message),
                    },
                    BackendEvent::Changes(result) => match result {
                        Ok(changes) => app.set_changes(changes),
                        Err(error) => app.panel_error(PanelKind::Changes, error.message),
                    },
                    BackendEvent::Diff(result) => match result {
                        Ok(diff) => app.set_diff(diff),
                        Err(error) => app.push_notice(error.message),
                    },
                    BackendEvent::FileSearch { query, result } => match result {
                        Ok(files) => app.set_file_results(&query, files),
                        Err(error) => app.push_notice(error.message),
                    },
                    BackendEvent::Models(result) => match result {
                        Ok(models) => app.set_models(models),
                        Err(error) => {
                            app.models_loading = false;
                            app.models_ready = false;
                            app.push_notice(error.message);
                        }
                    },
                    BackendEvent::RunInputs(result) => match result {
                        Ok(inputs) => app.set_run_inputs(inputs),
                        Err(error) => app.panel_error(PanelKind::Inputs, error.message),
                    },
                    BackendEvent::InputSubmitted { request_id, result } => app.finish_run_input(&request_id, result),
                    BackendEvent::InputWithdrawn { run_id, result } => match result {
                        Ok(_) => {
                            app.push_notice("Run input withdrawn");
                            spawn_run_inputs(api.clone(), run_id, backend_tx.clone());
                        }
                        Err(error) => app.push_notice(error.message),
                    },
                    BackendEvent::Snapshots(result) => match result {
                        Ok(snapshots) => app.set_snapshots(snapshots),
                        Err(error) => app.panel_error(PanelKind::Snapshots, error.message),
                    },
                    BackendEvent::RestorePlan { snapshot, result } => match result {
                        Ok(plan) => app.set_restore_plan(snapshot, plan),
                        Err(error) => app.panel_error(PanelKind::Restore, error.message),
                    },
                    BackendEvent::SnapshotRestored { thread_id, result } => match result {
                        Ok(_) => {
                            app.push_notice("Snapshot restored");
                            spawn_thread_bundle(api.clone(), thread_id, backend_tx.clone());
                        }
                        Err(error) => app.panel_error(PanelKind::Thread, error.message),
                    },
                    BackendEvent::SnapshotBranched(result) => match result {
                        Ok(thread_id) if app.expects_panel(PanelKind::Thread) => spawn_thread_bundle(api.clone(), thread_id, backend_tx.clone()),
                        Ok(_) => {},
                        Err(error) => app.panel_error(PanelKind::Thread, error.message),
                    },
                    BackendEvent::Goals(result) => match result {
                        Ok((goals, goal_run)) => {
                            if let Some(run) = goal_run.filter(|run| app.thread_id.as_deref() == Some(run.thread_id.as_str())) {
                                app.run = Some(run);
                            }
                            app.set_goals(goals);
                        }
                        Err(error) => app.panel_error(PanelKind::Goal, error.message),
                    },
                    BackendEvent::GoalControlled { thread_id, result } => match result {
                        Ok(run) => {
                            app.run = Some(run);
                            spawn_goals(api.clone(), thread_id, backend_tx.clone());
                        }
                        Err(error) => app.push_notice(error.message),
                    },
                    BackendEvent::Todos(result) => match result {
                        Ok(todos) => app.set_todos(todos),
                        Err(error) => app.panel_error(PanelKind::Todos, error.message),
                    },
                    BackendEvent::Skills(result) => match result {
                        Ok(skills) => app.set_skills(skills),
                        Err(error) => app.push_notice(error.message),
                    },
                    BackendEvent::Toolsets(result) => match result {
                        Ok(toolsets) => app.toolsets = toolsets,
                        Err(error) => app.push_notice(error.message),
                    },
                    BackendEvent::Commands(result) => match result {
                        Ok(commands) => app.commands = commands,
                        Err(error) => app.push_notice(error.message),
                    },
                    BackendEvent::Notice(message) => app.push_notice(message),
                    BackendEvent::Exported(result) => match result {
                        Ok(path) => app.open_text(i18n::text("Conversation exported").into(), path, false),
                        Err(error) => app.push_notice(error),
                    },
                }
                dirty = true;
            }
            terminal_event = terminal_events.next() => {
                let Some(terminal_event) = terminal_event else {
                    app.should_quit = true;
                    continue;
                };
                match terminal_event.map_err(|error| error.to_string())? {
                    Event::Key(key) if should_handle_key_event(&key) => {
                        let effects = app.handle_key(key);
                        for effect in effects {
                            if handle_effect(effect, &mut app, api.clone(), backend_tx.clone(), options.clipboard.as_ref()) {
                                break;
                            }
                        }
                        dirty = true;
                    }
                    Event::Paste(text) => {
                        app.mouse.selection = None;
                        for effect in app.paste(&text) {
                            if handle_effect(effect, &mut app, api.clone(), backend_tx.clone(), options.clipboard.as_ref()) { break; }
                        }
                        dirty = true;
                    }
                    Event::Resize(width, _) => {
                        app.mouse.selection = None;
                        app.sync_rail_width(width);
                        dirty = true;
                    }
                    Event::Mouse(event) => {
                        let (changed, effects) = app.handle_mouse(event);
                        dirty |= changed;
                        for effect in effects {
                            if handle_effect(effect, &mut app, api.clone(), backend_tx.clone(), options.clipboard.as_ref()) { break; }
                        }
                    }
                    Event::FocusGained => {
                        focused = true;
                        git_refresh.reset_immediately();
                    }
                    Event::FocusLost => focused = false,
                    _ => {}
                }
            }
        }
        if app.edit_requested {
            app.edit_requested = false;
            app.close_navigation();
            let draft = app.composer.text.clone();
            drop(terminal_events);
            guard.restore_now();
            let edited = tokio::task::spawn_blocking(move || {
                input::preserve_console_mode(|| local::edit_draft(&draft))
            })
            .await;
            guard = TerminalGuard::enter(
                options.fullscreen,
                options.stable_render,
                app.agent_available,
            )?;
            terminal_events = input::TerminalInput::new().map_err(|error| error.to_string())?;
            match edited {
                Ok(Ok(text)) => {
                    app.composer.replace(text);
                    app.input_history.detach();
                    app.push_notice(tf!(
                        "Editor changes restored to draft | {} sends",
                        "编辑内容已回填草稿 | 另按 {} 发送",
                        app.input_keys.send_key()
                    ));
                }
                Ok(Err(error)) => app.push_notice(tf!(
                    "Editor failed; draft kept: {error}",
                    "编辑器失败，原草稿已保留：{error}"
                )),
                Err(error) => app.push_notice(error.to_string()),
            }
            dirty = true;
        }
    }

    if let Some(task) = stream_task.take() {
        task.abort();
    }
    if let Some(task) = git_task.take() {
        task.abort();
    }
    drop(terminal_events);
    guard.restore_now();
    local::save(&memory_path, &app.memory())
        .map_err(|error| tf!("Could not save draft: {error}", "无法保存草稿：{error}"))?;
    Ok(Exit {
        switch_scope: app.switch_scope,
        view: ViewState {
            owned_run_live: owns_runtime && app.live_run_id().is_some(),
            theme_name: app.theme_name,
            thread_id: app.thread_id,
            draft: app.composer.text,
            provider: app.selected_provider,
            model: app.selected_model,
            reasoning_effort: app.selected_effort,
            permission: app.permission_mode,
            toolsets: app.selected_toolsets,
        },
    })
}

fn handle_effect(
    effect: Effect,
    app: &mut App,
    api: ApiClient,
    sender: mpsc::UnboundedSender<BackendEvent>,
    clipboard: &dyn Clipboard,
) -> bool {
    match effect {
        Effect::SetInputKeys(mode) => {
            app.input_keys = mode;
            app.push_notice(match mode.save() {
                Ok(()) => tf!(
                    "Input keys saved: {}",
                    "换行与发送快捷键已保存：{}",
                    i18n::text(mode.label())
                ),
                Err(error) => tf!(
                    "Input keys changed; could not save preference: {error}",
                    "快捷键已切换，但无法保存偏好：{error}"
                ),
            });
        }
        Effect::PasteClipboard => match clipboard.paste() {
            Ok(text) => {
                for effect in app.paste(&text) {
                    if handle_effect(effect, app, api.clone(), sender.clone(), clipboard) {
                        return true;
                    }
                }
            }
            Err(error) => app.push_notice(tf!(
                "Clipboard paste failed: {error}",
                "读取剪贴板失败：{error}"
            )),
        },
        Effect::EditDraft => app.edit_requested = true,
        Effect::Copy { text, label } => {
            // Keep copy and a subsequent right-click paste in input order.
            let chars = text.chars().count();
            let message = match clipboard.copy(&text) {
                Ok(()) => tf!(
                    "Copied {} ({} characters)",
                    "已复制{}（{} 字符）",
                    i18n::text(label),
                    chars
                ),
                Err(error) => tf!(
                    "Clipboard copy failed: {error}",
                    "复制到剪贴板失败：{error}"
                ),
            };
            app.push_notice(message);
        }
        Effect::ExportConversation { thread_id, title } => {
            app.push_notice(tf!("Exporting conversation...", "正在导出会话消息..."));
            tokio::spawn(async move {
                let result = match api.export_conversation(&thread_id, &title).await {
                    Ok(text) => tokio::task::spawn_blocking(move || {
                        local::export(&text)
                            .map(|path| path.display().to_string())
                            .map_err(|error| error.to_string())
                    })
                    .await
                    .map_err(|error| error.to_string())
                    .and_then(|result| result),
                    Err(error) => Err(error.message),
                };
                let _ = sender.send(BackendEvent::Exported(result));
            });
        }
        Effect::SetTheme(name) => {
            app.theme_name = name.clone();
            app.push_notice(match theme::save(&name) {
                Ok(()) => i18n::text("Theme preference saved").to_string(),
                Err(error) => tf!(
                    "Theme changed; could not save preference: {error}",
                    "主题已切换，但无法保存偏好：{error}"
                ),
            });
        }
        Effect::SetLanguage(language) => {
            i18n::set(language);
            app.push_notice(match i18n::save(language) {
                Ok(()) => i18n::text("Language preference saved").to_string(),
                Err(error) => i18n::tf!(
                    "Language changed; could not save preference: {error}",
                    "语言已切换，但无法保存偏好：{error}"
                ),
            });
        }
        Effect::Preview(source) => {
            tokio::spawn(async move {
                let message = match api.preview_url(&source).await {
                    Ok(url) => match harnex_host_runtime::preview::open_preview_url(&url) {
                        Ok(()) => {
                            "Preview opened in browser (available for 30 minutes)".to_string()
                        }
                        Err(error) => tf!(
                            "Browser could not open: {error}\n{url}",
                            "无法打开浏览器：{error}\n{url}"
                        ),
                    },
                    Err(error) => error.message,
                };
                let _ = sender.send(BackendEvent::Notice(message));
            });
        }
        Effect::CreateRun {
            request_id,
            thread_id,
            input,
            permission_mode,
            provider,
            model,
            reasoning_effort,
            toolsets,
        } => {
            tokio::spawn(async move {
                let result = api
                    .create_run(
                        thread_id.as_deref(),
                        &input,
                        &permission_mode,
                        provider.as_deref(),
                        model.as_deref(),
                        &reasoning_effort,
                        &toolsets,
                    )
                    .await;
                let _ = sender.send(BackendEvent::RunCreated { request_id, result });
            });
        }
        Effect::EnqueueInput {
            request_id,
            run_id,
            input,
            mode,
        } => {
            tokio::spawn(async move {
                let result = api.enqueue_input(&run_id, &mode, &input).await.map(|_| ());
                let _ = sender.send(BackendEvent::InputSubmitted { request_id, result });
            });
        }
        Effect::CancelRun(run_id) => {
            tokio::spawn(async move {
                let message = match api.cancel_run(&run_id).await {
                    Ok(_) => "Cancellation requested".to_string(),
                    Err(error) => error.message,
                };
                let _ = sender.send(BackendEvent::Notice(message));
            });
        }
        Effect::LoadThread(thread_id) => spawn_thread_bundle(api, thread_id, sender),
        Effect::LoadThreads => {
            tokio::spawn(async move {
                let _ = sender.send(BackendEvent::Threads(api.threads().await));
            });
        }
        Effect::ResolveInteraction { interaction, body } => {
            tokio::spawn(async move {
                let thread_id = interaction.thread_id.clone();
                let result = api
                    .resolve_interaction(&interaction, body)
                    .await
                    .map(|_| thread_id);
                let _ = sender.send(BackendEvent::InteractionResolved(result));
            });
        }
        Effect::LoadChanges(run_id) => {
            tokio::spawn(async move {
                let _ = sender.send(BackendEvent::Changes(api.changes(&run_id).await));
            });
        }
        Effect::LoadDiff { run_id, path } => {
            tokio::spawn(async move {
                let _ = sender.send(BackendEvent::Diff(api.diff(&run_id, &path).await));
            });
        }
        Effect::SearchFiles(query) => {
            tokio::spawn(async move {
                let result = api.search_files(&query).await.map(|page| page.entries);
                let _ = sender.send(BackendEvent::FileSearch { query, result });
            });
        }
        Effect::LoadModels => {
            tokio::spawn(async move {
                let _ = sender.send(BackendEvent::Models(api.models().await));
            });
        }
        Effect::LoadRunInputs(run_id) => spawn_run_inputs(api, run_id, sender),
        Effect::WithdrawInput { run_id, input_id } => {
            tokio::spawn(async move {
                let result = api.withdraw_input(&run_id, &input_id).await;
                let _ = sender.send(BackendEvent::InputWithdrawn { run_id, result });
            });
        }
        Effect::LoadSnapshots(thread_id) => {
            tokio::spawn(async move {
                let _ = sender.send(BackendEvent::Snapshots(api.snapshots(&thread_id).await));
            });
        }
        Effect::LoadRestorePlan {
            thread_id,
            snapshot,
            mode,
        } => {
            tokio::spawn(async move {
                let result = api.restore_plan(&thread_id, snapshot.seq, &mode).await;
                let _ = sender.send(BackendEvent::RestorePlan { snapshot, result });
            });
        }
        Effect::RestoreSnapshot {
            thread_id,
            seq,
            mode,
            history_revision,
            command_id,
        } => {
            tokio::spawn(async move {
                let result = api
                    .restore_snapshot(&thread_id, seq, &mode, history_revision, &command_id)
                    .await;
                let _ = sender.send(BackendEvent::SnapshotRestored { thread_id, result });
            });
        }
        Effect::BranchSnapshot { thread_id, seq } => {
            tokio::spawn(async move {
                let result = api
                    .branch_snapshot(&thread_id, seq)
                    .await
                    .and_then(|value| {
                        value
                            .get("conversationId")
                            .and_then(serde_json::Value::as_str)
                            .map(ToString::to_string)
                            .ok_or_else(|| ApiError {
                                message: "branch response has no conversationId".to_string(),
                            })
                    });
                let _ = sender.send(BackendEvent::SnapshotBranched(result));
            });
        }
        Effect::LoadGoal(thread_id) => spawn_goals(api, thread_id, sender),
        Effect::GoalControl { run, action } => {
            let thread_id = run.thread_id.clone();
            tokio::spawn(async move {
                let result = api.goal_control(&run, &action).await;
                let _ = sender.send(BackendEvent::GoalControlled { thread_id, result });
            });
        }
        Effect::GoalReview {
            run,
            decision,
            feedback,
        } => {
            let thread_id = run.thread_id.clone();
            tokio::spawn(async move {
                let result = api.review_goal(&run, &decision, feedback.as_deref()).await;
                let _ = sender.send(BackendEvent::GoalControlled { thread_id, result });
            });
        }
        Effect::LoadTodos(thread_id) => {
            tokio::spawn(async move {
                let _ = sender.send(BackendEvent::Todos(api.todos(&thread_id).await));
            });
        }
        Effect::LoadSkills => {
            tokio::spawn(async move {
                let _ = sender.send(BackendEvent::Skills(api.skills().await));
            });
        }
        Effect::LoadToolsets => {
            tokio::spawn(async move {
                let _ = sender.send(BackendEvent::Toolsets(api.toolsets().await));
            });
        }
        Effect::LoadCommands => {
            tokio::spawn(async move {
                let _ = sender.send(BackendEvent::Commands(api.commands().await));
            });
        }
        Effect::SetPermissionMode { thread_id, mode } => {
            tokio::spawn(async move {
                let message = match api.set_permission_mode(&thread_id, &mode).await {
                    Ok(_) => tf!("Permission mode: {}", "权限模式：{}", i18n::text(&mode)),
                    Err(error) => error.message,
                };
                let _ = sender.send(BackendEvent::Notice(message));
            });
        }
        Effect::Redraw => {
            if let Err(error) = TerminalGuard::clear() {
                app.push_notice(error);
            }
        }
        Effect::Quit => {
            app.should_quit = true;
            return true;
        }
    }
    false
}

fn spawn_thread_bundle(
    api: ApiClient,
    thread_id: String,
    sender: mpsc::UnboundedSender<BackendEvent>,
) {
    tokio::spawn(async move {
        let (thread, runs, interactions) = tokio::join!(
            api.thread(&thread_id),
            api.runs(&thread_id),
            api.interactions(&thread_id)
        );
        let result = match (thread, runs, interactions) {
            (Ok(thread), Ok(runs), Ok(interactions)) => Ok((thread, runs, interactions)),
            (Err(error), _, _) | (_, Err(error), _) | (_, _, Err(error)) => Err(error),
        };
        let _ = sender.send(BackendEvent::ThreadBundle(result));
    });
}

fn spawn_interactions(
    api: ApiClient,
    thread_id: String,
    sender: mpsc::UnboundedSender<BackendEvent>,
) {
    tokio::spawn(async move {
        let _ = sender.send(BackendEvent::Interactions(
            api.interactions(&thread_id).await,
        ));
    });
}

fn spawn_run_inputs(api: ApiClient, run_id: String, sender: mpsc::UnboundedSender<BackendEvent>) {
    tokio::spawn(async move {
        let _ = sender.send(BackendEvent::RunInputs(api.run_inputs(&run_id).await));
    });
}

fn spawn_goals(api: ApiClient, thread_id: String, sender: mpsc::UnboundedSender<BackendEvent>) {
    tokio::spawn(async move {
        let result = match api.goals(&thread_id).await {
            Ok(goals) => {
                let run_id = goals
                    .iter()
                    .find_map(|goal| goal.active_run_id.clone())
                    .or_else(|| goals.iter().find_map(|goal| goal.run_ids.last().cloned()));
                let run = if let Some(run_id) = run_id {
                    match api.run(&run_id).await {
                        Ok(run) => Some(run),
                        Err(error) => {
                            let _ = sender.send(BackendEvent::Notice(error.message));
                            None
                        }
                    }
                } else {
                    None
                };
                Ok((goals, run))
            }
            Err(error) => Err(error),
        };
        let _ = sender.send(BackendEvent::Goals(result));
    });
}

fn spawn_stream(
    api: ApiClient,
    run: RunView,
    stream_sender: mpsc::Sender<StreamEvent>,
    backend_sender: mpsc::UnboundedSender<BackendEvent>,
) -> JoinHandle<()> {
    tokio::spawn(async move {
        let mut run_id = run.run_id;
        let mut source_run_id = None;
        let mut after_seq = 0;
        let mut stream_epoch = None;
        let mut capture = true;
        let mut failures = 0_u32;
        loop {
            if capture {
                match api.presentation(&run_id).await {
                    Ok(bundle) => {
                        after_seq = bundle.snapshot.cursor.projection_seq;
                        stream_epoch = bundle.snapshot.cursor.stream_epoch;
                        let subscribe = bundle.snapshot.cursor.replay_available
                            && matches!(
                                bundle.snapshot.run.status.as_str(),
                                "CREATED"
                                    | "QUEUED"
                                    | "RUNNING"
                                    | "PAUSE_REQUESTED"
                                    | "WAITING_RETRY"
                            );
                        let captured_run = bundle.snapshot.run.clone();
                        let (applied, acknowledgement) = tokio::sync::oneshot::channel();
                        if backend_sender
                            .send(BackendEvent::Presentation {
                                bundle,
                                source_run_id: source_run_id.take(),
                                applied,
                            })
                            .is_err()
                        {
                            return;
                        }
                        if acknowledgement.await != Ok(true) {
                            return;
                        }
                        if !subscribe {
                            if captured_run.status != "COMPLETED" {
                                return;
                            }
                            let mut next_run = None;
                            for _ in 0..20 {
                                match api.next_submission(&captured_run).await {
                                    Ok(Some(next)) => {
                                        if let Some(id) = next["runId"].as_str() {
                                            next_run = Some(id.to_string());
                                            break;
                                        }
                                        if !matches!(
                                            next["state"].as_str(),
                                            Some("QUEUED" | "STARTING")
                                        ) {
                                            return;
                                        }
                                    }
                                    _ => return,
                                }
                                tokio::time::sleep(Duration::from_millis(400)).await;
                            }
                            if let Some(next) = next_run {
                                source_run_id = Some(run_id);
                                run_id = next;
                                capture = true;
                                continue;
                            }
                            return;
                        }
                        capture = false;
                    }
                    Err(error) => {
                        let _ = backend_sender.send(BackendEvent::Notice(error.message));
                        failures += 1;
                        if failures >= 12 {
                            return;
                        }
                        tokio::time::sleep(Duration::from_millis(500)).await;
                        continue;
                    }
                }
            }
            match api
                .stream_events(&run_id, after_seq, stream_epoch, stream_sender.clone())
                .await
            {
                Ok(StreamClose::Refresh | StreamClose::ReplayGone | StreamClose::RunNotFound) => {
                    capture = true;
                    failures = 0;
                }
                Ok(StreamClose::Eof {
                    after_seq: seq,
                    stream_epoch: epoch,
                }) => {
                    after_seq = seq;
                    stream_epoch = epoch;
                    capture = true;
                    tokio::time::sleep(Duration::from_millis(250)).await;
                }
                Err(error) => {
                    failures += 1;
                    if failures >= 5 {
                        let _ = backend_sender.send(BackendEvent::Notice(tf!(
                            "Run stream disconnected: {}",
                            "任务事件连接已断开：{}",
                            error.message
                        )));
                    }
                    if failures >= 12 {
                        return;
                    }
                    tokio::time::sleep(Duration::from_millis(
                        (250_u64 * 2_u64.pow(failures.min(4))).min(2_000),
                    ))
                    .await;
                }
            }
        }
    })
}

fn select_run(mut runs: Vec<RunView>) -> Option<RunView> {
    if let Some(index) = runs.iter().position(RunView::is_live) {
        return Some(runs.remove(index));
    }
    runs.into_iter().next()
}

enum BackendEvent {
    Exported(Result<String, String>),
    WorkspaceGit(Result<WorkspaceGitView, ApiError>),
    InputSubmitted {
        request_id: String,
        result: Result<(), ApiError>,
    },
    RunCreated {
        request_id: String,
        result: Result<RunView, ApiError>,
    },
    Presentation {
        bundle: PresentationBundle,
        source_run_id: Option<String>,
        applied: tokio::sync::oneshot::Sender<bool>,
    },
    ThreadBundle(Result<(ThreadView, Vec<RunView>, Vec<InteractionView>), ApiError>),
    Threads(Result<Vec<ThreadSummary>, ApiError>),
    Interactions(Result<Vec<InteractionView>, ApiError>),
    InteractionResolved(Result<String, ApiError>),
    Changes(Result<ChangesView, ApiError>),
    Diff(Result<DiffView, ApiError>),
    FileSearch {
        query: String,
        result: Result<Vec<FileSearchHit>, ApiError>,
    },
    Models(Result<Vec<ModelOption>, ApiError>),
    RunInputs(Result<Vec<RunInputView>, ApiError>),
    InputWithdrawn {
        run_id: String,
        result: Result<serde_json::Value, ApiError>,
    },
    Snapshots(Result<Vec<SnapshotView>, ApiError>),
    RestorePlan {
        snapshot: SnapshotView,
        result: Result<RestorePlanView, ApiError>,
    },
    SnapshotRestored {
        thread_id: String,
        result: Result<serde_json::Value, ApiError>,
    },
    SnapshotBranched(Result<String, ApiError>),
    Goals(Result<(Vec<GoalView>, Option<RunView>), ApiError>),
    GoalControlled {
        thread_id: String,
        result: Result<RunView, ApiError>,
    },
    Todos(Result<Vec<TodoView>, ApiError>),
    Skills(Result<Vec<SkillView>, ApiError>),
    Toolsets(Result<Vec<ToolsetOption>, ApiError>),
    Commands(Result<Vec<CommandView>, ApiError>),
    Notice(String),
}

fn project_stream_event(
    app: &mut App,
    event: StreamEvent,
    api: &ApiClient,
    sender: &mpsc::UnboundedSender<BackendEvent>,
) {
    let directive = app.apply_stream(event);
    if directive.refresh_interactions {
        if let Some(thread_id) = app.thread_id.clone() {
            spawn_interactions(api.clone(), thread_id, sender.clone());
        }
    }
    if directive.refresh_inputs {
        if let Some(run_id) = app.run.as_ref().map(|run| run.run_id.clone()) {
            spawn_run_inputs(api.clone(), run_id, sender.clone());
        }
    }
    if directive.refresh_goal {
        if let Some(thread_id) = app.thread_id.clone() {
            spawn_goals(api.clone(), thread_id, sender.clone());
        }
    }
}

fn should_handle_key_event(key: &KeyEvent) -> bool {
    match key.kind {
        KeyEventKind::Press => true,
        KeyEventKind::Repeat => matches!(
            key.code,
            KeyCode::Backspace
                | KeyCode::Delete
                | KeyCode::Left
                | KeyCode::Right
                | KeyCode::Up
                | KeyCode::Down
                | KeyCode::Home
                | KeyCode::End
                | KeyCode::PageUp
                | KeyCode::PageDown
        ),
        KeyEventKind::Release => false,
    }
}

fn frame_interval(stable: bool) -> tokio::time::Interval {
    // Keep a deadline across incoming events. Recreating sleep in select!
    // debounces every frame and can hide tools for an entire busy stream.
    let mut interval = tokio::time::interval(Duration::from_millis(if stable { 33 } else { 16 }));
    interval.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Skip);
    interval
}

fn keyboard_enhancement_requested() -> bool {
    let terminal = ["TERM_PROGRAM", "TERM"]
        .into_iter()
        .filter_map(|name| std::env::var(name).ok())
        .collect::<Vec<_>>()
        .join(" ")
        .to_ascii_lowercase();
    ["wezterm", "kitty", "alacritty", "foot"]
        .iter()
        .any(|name| terminal.contains(name))
}

struct TerminalGuard {
    terminal: Terminal<CrosstermBackend<Stdout>>,
    stable: Option<StableRenderer>,
    restored: bool,
    fullscreen: bool,
    stable_render: bool,
    keyboard_enhanced: bool,
    focus_enabled: bool,
}

impl TerminalGuard {
    fn enter(fullscreen: bool, stable_render: bool, agent_available: bool) -> Result<Self, String> {
        enable_raw_mode().map_err(|error| error.to_string())?;
        let mut stdout = io::stdout();
        let entered = if fullscreen {
            execute!(
                stdout,
                EnterAlternateScreen,
                DisableLineWrap,
                EnableBracketedPaste
            )
        } else {
            execute!(stdout, DisableLineWrap, EnableBracketedPaste)
        };
        if let Err(error) = entered {
            let _ = disable_raw_mode();
            return Err(error.to_string());
        }
        let focus_enabled = agent_available && execute!(stdout, EnableFocusChange).is_ok();
        // TerminalInput owns mouse capture and restores the original input mode.
        // Only negotiate Kitty keyboard enhancements with terminals known to
        // support them. Sending the sequence through ConPTY/legacy PowerShell
        // can duplicate IME commits on some hosts.
        let keyboard_enhanced = keyboard_enhancement_requested()
            && execute!(
                stdout,
                PushKeyboardEnhancementFlags(KeyboardEnhancementFlags::DISAMBIGUATE_ESCAPE_CODES)
            )
            .is_ok();
        let backend = CrosstermBackend::new(stdout);
        let terminal = match Terminal::new(backend) {
            Ok(terminal) => terminal,
            Err(error) => {
                Self::restore(fullscreen, !stable_render, keyboard_enhanced, focus_enabled);
                return Err(error.to_string());
            }
        };
        let stable = if stable_render {
            let size = match terminal.size() {
                Ok(size) => size,
                Err(error) => {
                    Self::restore(fullscreen, false, keyboard_enhanced, focus_enabled);
                    return Err(error.to_string());
                }
            };
            Some(StableRenderer::new(size.width, size.height))
        } else {
            None
        };
        let mut guard = Self {
            terminal,
            stable,
            restored: false,
            fullscreen,
            stable_render,
            keyboard_enhanced,
            focus_enabled,
        };
        guard
            .terminal
            .hide_cursor()
            .map_err(|error| error.to_string())?;
        guard.terminal.clear().map_err(|error| error.to_string())?;
        Ok(guard)
    }

    fn clear() -> Result<(), String> {
        execute!(
            io::stdout(),
            crossterm::terminal::Clear(crossterm::terminal::ClearType::All)
        )
        .map_err(|error| error.to_string())
    }

    fn draw(&mut self, app: &App, theme: Theme) -> Result<(), String> {
        if let Some(stable) = self.stable.as_mut() {
            let size = self.terminal.size().map_err(|error| error.to_string())?;
            let frame = stable
                .render(app, theme, size.width, size.height)
                .map_err(|error| error.to_string())?;
            let mut stdout = io::stdout();
            stdout
                .write_all(&frame)
                .map_err(|error| error.to_string())?;
            stdout.flush().map_err(|error| error.to_string())?;
            return Ok(());
        }
        execute!(io::stdout(), BeginSynchronizedUpdate).map_err(|error| error.to_string())?;
        let drawn = self
            .terminal
            .draw(|frame| ui::draw(frame, app, theme))
            .map_err(|error| error.to_string());
        let ended =
            execute!(io::stdout(), EndSynchronizedUpdate).map_err(|error| error.to_string());
        drawn?;
        ended
    }

    fn restore(fullscreen: bool, synchronized: bool, keyboard_enhanced: bool, focus_enabled: bool) {
        input::restore_console_mode();
        let _ = disable_raw_mode();
        let _ = execute!(
            io::stdout(),
            crossterm::style::ResetColor,
            crossterm::style::SetAttribute(crossterm::style::Attribute::Reset)
        );
        if focus_enabled {
            let _ = execute!(io::stdout(), DisableFocusChange);
        }
        if keyboard_enhanced {
            let _ = execute!(io::stdout(), PopKeyboardEnhancementFlags);
        }
        if fullscreen {
            if synchronized {
                let _ = execute!(io::stdout(), EndSynchronizedUpdate);
            }
            let _ = execute!(
                io::stdout(),
                Show,
                DisableBracketedPaste,
                EnableLineWrap,
                LeaveAlternateScreen
            );
        } else {
            if synchronized {
                let _ = execute!(io::stdout(), EndSynchronizedUpdate);
            }
            let _ = execute!(io::stdout(), Show, DisableBracketedPaste, EnableLineWrap);
        }
    }

    fn restore_now(&mut self) {
        if self.restored {
            return;
        }
        let _ = self.terminal.show_cursor();
        Self::restore(
            self.fullscreen,
            !self.stable_render,
            self.keyboard_enhanced,
            self.focus_enabled,
        );
        self.restored = true;
    }
}

impl Drop for TerminalGuard {
    fn drop(&mut self) {
        self.restore_now();
    }
}

fn api_string(error: ApiError) -> String {
    i18n::text(&error.message).to_string()
}

#[cfg(test)]
mod tests {
    use super::*;
    use crossterm::event::KeyModifiers;

    #[test]
    fn startup_is_new_but_scope_switch_restores_the_explicit_selection() {
        let history = vec![ThreadSummary {
            id: "old-thread".into(),
            title: "Old task".into(),
            ..Default::default()
        }];
        assert_eq!(initial_thread(None), None);
        let mut app = App::new(api::WorkspaceView::default(), history, None, None, false);
        app.composer.text = "A different task".into();
        let effects = app.handle_key(KeyEvent::new(KeyCode::Enter, KeyModifiers::NONE));
        assert!(matches!(
            effects.as_slice(),
            [Effect::CreateRun {
                thread_id: None,
                ..
            }]
        ));
        assert_eq!(
            app.threads[0].id, "old-thread",
            "history remains available for manual selection"
        );
        let resume = ViewState {
            thread_id: Some("chosen-thread".into()),
            draft: "draft".into(),
            ..Default::default()
        };
        assert_eq!(
            initial_thread(Some(&resume)).as_deref(),
            Some("chosen-thread")
        );
        assert_eq!(initial_thread(Some(&ViewState::default())), None);
    }

    #[test]
    fn repeated_text_is_not_inserted_twice_but_editing_can_repeat() {
        let repeated_text = KeyEvent::new_with_kind(
            KeyCode::Char('你'),
            KeyModifiers::NONE,
            KeyEventKind::Repeat,
        );
        let repeated_backspace =
            KeyEvent::new_with_kind(KeyCode::Backspace, KeyModifiers::NONE, KeyEventKind::Repeat);
        assert!(!should_handle_key_event(&repeated_text));
        assert!(should_handle_key_event(&repeated_backspace));
    }
}
