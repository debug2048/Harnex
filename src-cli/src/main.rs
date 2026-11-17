use harnex_host_runtime::session_auth::SessionCredential;
use reqwest::blocking::{Client, Response};
use reqwest::header::{HeaderMap, HeaderValue, ACCEPT, AUTHORIZATION, CONTENT_TYPE};
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use sha2::{Digest, Sha256};
use std::collections::BTreeMap;
use std::env;
use std::fs::{self, OpenOptions};
use std::io::{BufRead, BufReader, Write};
use std::net::TcpListener;
use std::path::{Component, Path, PathBuf};
use std::process::{Child, Command, Stdio};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use std::thread;
use std::time::{Duration, Instant};
use url::Url;
use uuid::Uuid;

mod remote_commands;
mod tui;

const PROTOCOL_VERSION: &str = "1.0";
const DEFAULT_WEB_PORT: u16 = 18081;

fn main() {
    if harnex_host_runtime::interaction::handle_askpass() {
        return;
    }
    let result = run();
    harnex_host_runtime::interaction::shutdown();
    if let Err(error) = result {
        eprintln!("harnex: {error}");
        std::process::exit(1);
    }
}

fn run() -> Result<(), String> {
    let (args, explicit_manifest) = global_options(env::args().skip(1).collect())?;
    let manifest =
        explicit_manifest.or_else(|| non_blank_env("HARNEX_DOMAIN_MANIFEST").map(PathBuf::from));
    let command = args.first().map(String::as_str).unwrap_or("app");
    let rest = if args.is_empty() { &[][..] } else { &args[1..] };
    match command {
        "version" | "--version" | "-v" => {
            println!(
                "harnex {} (native; Local API {PROTOCOL_VERSION})",
                env!("CARGO_PKG_VERSION")
            );
            Ok(())
        }
        "doctor" => doctor(manifest.as_deref()),
        "cli" => cli(manifest.as_deref(), rest),
        "app" | "launch" => app(manifest.as_deref()),
        "serve" => {
            reject_attached(manifest.as_deref(), "serve")?;
            serve(rest)
        }
        "stop" => stop_command(manifest.as_deref(), rest),
        "status" => status(manifest.as_deref()),
        "runtime" => runtime_command(manifest.as_deref(), rest),
        "path" => path_command(rest),
        "remote" => {
            if matches!(
                rest.first().map(String::as_str),
                Some("bridge" | "close" | "close-bound")
            ) {
                reject_attached(manifest.as_deref(), "remote Runtime lifecycle")?;
            }
            remote_commands::command(rest)
        }
        "help" | "--help" | "-h" => {
            help();
            Ok(())
        }
        "tui" => tui_command(manifest.as_deref(), rest),
        other => Err(format!("unknown command '{other}'; try `harnex help`")),
    }
}

fn help() {
    println!(
        r#"Harnex native CLI

Usage:
  harnex version
  harnex doctor [--domain-manifest PATH]
  harnex serve [--server.port={DEFAULT_WEB_PORT}]
  harnex stop
  harnex runtime status|stop|restart
  harnex app
  harnex tui [--conversation] [--workspace PATH] [--lang auto|zh-CN|en] [--renderer fullscreen|inline] [--render-strategy auto|stable|diff] [--theme auto|dark|light|ansi] [--color auto|always|never]
  harnex remote add devbox --host user@host [--identity-file PATH] [--port 22]
  harnex remote list|test|remove devbox
  harnex tui --remote devbox --workspace /srv/project
  harnex cli -p "prompt" [--conversation] [--permission-mode ask|accept-edits|yolo]
  harnex cli                    Alias of tui when no prompt is supplied
  harnex --domain-manifest PATH cli --run ID --steer "instruction"
  harnex --domain-manifest PATH cli --run ID --follow-up "next task"
  harnex path install|uninstall [--machine]

An explicit --domain-manifest, or HARNEX_DOMAIN_MANIFEST injected by an IDE,
uses exact attach. Invalid/stale manifests fail closed and never fall back to spawn.
Attached stop requires --run ID and only cancels that Run.
Standalone TUI: F6 switches personal Conversation / project Agent and keeps each draft.
--conversation uses the shared local personal Runtime, independent of the project directory."#
    );
}

fn tui_command(manifest: Option<&Path>, args: &[String]) -> Result<(), String> {
    use tui::i18n::text as tr;
    let language = option(args, "--lang");
    if args.iter().any(|arg| arg == "--lang") && language.is_none() {
        return Err(
            "--lang requires a value: auto, zh-CN or en / --lang 需要参数：auto、zh-CN 或 en"
                .into(),
        );
    }
    tui::i18n::initialize(language.as_deref())?;
    if args.iter().any(|arg| arg == "--help" || arg == "-h") {
        tui::print_help();
        return Ok(());
    }
    use std::io::IsTerminal;

    if !std::io::stdin().is_terminal() || !std::io::stdout().is_terminal() {
        return Err(tr(
            "interactive tui requires a TTY; use `harnex cli -p <prompt>` for headless execution",
        )
        .to_string());
    }
    let renderer = option(args, "--renderer").unwrap_or_else(|| "fullscreen".to_string());
    if !["fullscreen", "inline"].contains(&renderer.as_str()) {
        return Err(tr("--renderer must be fullscreen or inline").to_string());
    }
    let theme = tui::theme::initial_name(option(args, "--theme"));
    if !["auto", "dark", "light", "ansi"].contains(&theme.as_str()) {
        return Err(tr("--theme must be auto, dark, light or ansi").to_string());
    }
    let color = option(args, "--color").unwrap_or_else(|| "auto".to_string());
    if !["auto", "always", "never"].contains(&color.as_str()) {
        return Err(tr("--color must be auto, always or never").to_string());
    }
    let render_strategy = option(args, "--render-strategy").unwrap_or_else(|| "auto".to_string());
    if !["auto", "stable", "diff", "compat"].contains(&render_strategy.as_str()) {
        return Err(tr("--render-strategy must be auto, stable or diff").to_string());
    }
    let tui_options = tui::Options {
        clipboard: std::sync::Arc::new(tui::SystemClipboard),
        fullscreen: renderer == "fullscreen",
        theme,
        no_color: color == "never" || color == "auto" && std::env::var_os("NO_COLOR").is_some(),
        stable_render: match render_strategy.as_str() {
            "stable" | "compat" => true,
            "diff" => false,
            _ => default_stable_render(),
        },
        background_owned_run: false,
    };
    if let Some(file) = manifest {
        if has_remote(args) {
            reject_attached(manifest, "connect another Runtime")?;
        }
        let session = Session::attach(file, &command_workspace(args)?)?;
        require_conversation_target(&session.manifest.runtime_profile, args)?;
        return tui::run(session, false, tui_options, false, None, None).map(|_| ());
    }
    run_tui_spaces(args, tui_options)
}

fn has_remote(args: &[String]) -> bool {
    args.iter()
        .any(|arg| arg == "--remote" || arg.starts_with("--remote="))
}
fn wants_conversation(args: &[String]) -> bool {
    args.iter().any(|arg| arg == "--conversation")
}
fn require_conversation_target(profile: &str, args: &[String]) -> Result<(), String> {
    if wants_conversation(args) && profile != "PERSONAL" {
        return Err("--conversation requires a PERSONAL Runtime; this attached terminal is bound to its project Runtime".into());
    }
    Ok(())
}

fn open_personal_runtime() -> Result<harnex_host_runtime::personal::PersonalRuntime, String> {
    use harnex_host_runtime::personal::{personal_paths, PersonalLaunch, PersonalRuntime};
    let user = non_blank_env("HARNEX_USER_DATA_DIR")
        .map(PathBuf::from)
        .unwrap_or_else(|| home_dir().join(".harnex"));
    let (_, domain, scope) = personal_paths(&user)?;
    if let Ok(runtime) = PersonalRuntime::attach(&domain, &scope) {
        return Ok(runtime);
    }
    let bundle = RuntimeBundle::locate_and_verify()?;
    let mut configured = Command::new(&bundle.java);
    inject_user_config(&mut configured, &user)?;
    PersonalRuntime::open(
        PersonalLaunch {
            user_data_dir: user,
            owner_kind: "cli".into(),
            jar_sha256: bundle.manifest.files.get(&bundle.manifest.entrypoint).cloned(),
            java: bundle.java,
            jar: bundle.jar,
        },
        |command| {
            for (key, value) in configured.get_envs() {
                if let Some(value) = value {
                    command.env(key, value);
                }
            }
        },
    )
}

#[derive(Clone, Copy, PartialEq, Eq)]
enum WorkMode {
    Agent,
    Conversation,
}
impl WorkMode {
    fn index(self) -> usize {
        if self == Self::Agent {
            0
        } else {
            1
        }
    }
    fn other(self) -> Self {
        if self == Self::Agent {
            Self::Conversation
        } else {
            Self::Agent
        }
    }
}

#[derive(Default)]
struct TuiRuntimes {
    project: Option<OwnedRuntime>,
    remote: Option<harnex_host_runtime::Connection>,
    personal: Option<harnex_host_runtime::personal::PersonalRuntime>,
}
impl TuiRuntimes {
    fn session(&mut self, mode: WorkMode, args: &[String]) -> Result<(Session, bool), String> {
        if mode == WorkMode::Conversation {
            if self
                .personal
                .as_mut()
                .is_some_and(|runtime| runtime.owned_process_exited())
            {
                self.personal = None;
            }
            if self.personal.is_none() {
                eprintln!("{}", tui::i18n::text("Opening personal conversations..."));
                self.personal = Some(open_personal_runtime()?);
            }
            let runtime = self.personal.as_ref().unwrap();
            let owned = runtime.owns_process();
            return match Session::attach(&runtime.domain_dir.join("domain.json"), Path::new(".")) {
                Ok(session) => Ok((session, owned)),
                Err(error) => {
                    if !owned {
                        self.personal = None;
                    }
                    Err(error)
                }
            };
        }
        if has_remote(args) {
            if self.remote.is_none() {
                self.remote = Some(remote_commands::connect(args, "cli-tui")?);
            }
            return match Session::remote(self.remote.as_ref().unwrap()) {
                Ok(session) => Ok((session, true)),
                // Dropping a Connection closes only its local tunnel. Keep the remote Runtime available for retry.
                Err(error) => {
                    self.remote = None;
                    Err(error)
                }
            };
        }
        let cwd = command_workspace(args)?;
        if self
            .project
            .as_mut()
            .is_some_and(|runtime| runtime.child.try_wait().ok().flatten().is_some())
        {
            self.project = None;
        }
        if self.project.is_none() {
            eprintln!("{}", tui::i18n::text("Opening project Agent..."));
            self.project = Some(OwnedRuntime::start(
                &format!("cli-tui-{}", std::process::id()),
                "cli",
                free_port()?,
                &cwd,
            )?);
        }
        Ok((
            Session::attach(&self.project.as_ref().unwrap().manifest_file, &cwd)?,
            true,
        ))
    }
    fn shutdown(mut self) -> Result<(), String> {
        if let Some(runtime) = self.project.as_mut() {
            runtime.terminate();
        }
        if let Some(remote) = self.remote.take() {
            remote_commands::close(remote)?;
        }
        Ok(())
    }
}

fn run_tui_spaces(args: &[String], mut options: tui::Options) -> Result<(), String> {
    let mut runtimes = TuiRuntimes::default();
    let mut mode = if wants_conversation(args) {
        WorkMode::Conversation
    } else {
        WorkMode::Agent
    };
    let mut views: [Option<tui::ViewState>; 2] = [None, None];
    let mut previous = None;
    let mut notice = None;
    let result = (|| loop {
        let mut current_options = options.clone();
        current_options.background_owned_run = views[mode.other().index()]
            .as_ref()
            .is_some_and(|view| view.owned_run_live);
        let next = runtimes.session(mode, args).and_then(|(session, owned)| {
            tui::run(
                session,
                owned,
                current_options,
                true,
                views[mode.index()].clone(),
                notice.take(),
            )
        });
        let exit = match next {
            Ok(exit) => exit,
            Err(error) => {
                if let Some(fallback) = previous.take() {
                    mode = fallback;
                    notice = Some(tui::i18n::tf!(
                        "Could not switch space: {error}. Press F6 to retry.",
                        "无法切换空间：{error}。按 F6 重试。"
                    ));
                    continue;
                } else {
                    break Err(error);
                }
            }
        };
        options.theme = exit.view.theme_name.clone();
        views[mode.index()] = Some(exit.view);
        if !exit.switch_scope {
            break Ok(());
        }
        previous = Some(mode);
        mode = mode.other();
    })();
    let stopped = runtimes.shutdown();
    result.and(stopped)
}

fn default_stable_render() -> bool {
    // Windows terminals do not agree on the cursor width of every CJK/emoji
    // grapheme. Render the same rich frame as contiguous rows there; users can
    // still opt into Ratatui's faster cell-diff transport with `diff`.
    cfg!(windows)
}

#[cfg(windows)]
fn path_command(args: &[String]) -> Result<(), String> {
    use windows_sys::Win32::UI::WindowsAndMessaging::{
        SendMessageTimeoutW, HWND_BROADCAST, SMTO_ABORTIFHUNG, WM_SETTINGCHANGE,
    };
    use winreg::enums::{
        HKEY_CURRENT_USER, HKEY_LOCAL_MACHINE, KEY_READ, KEY_WRITE, REG_EXPAND_SZ, REG_SZ,
    };
    use winreg::{RegKey, RegValue};

    let operation = args.first().map(String::as_str).unwrap_or("");
    if !matches!(operation, "install" | "uninstall") {
        return Err("path requires install or uninstall".to_string());
    }
    let machine = args.iter().any(|arg| arg == "--machine");
    let installer = args.iter().any(|arg| arg == "--installer");
    if installer && (!machine || operation != "install") {
        return Err("--installer is valid only with path install --machine".to_string());
    }
    let (hive, key_name) = if machine {
        (
            RegKey::predef(HKEY_LOCAL_MACHINE),
            r"SYSTEM\CurrentControlSet\Control\Session Manager\Environment",
        )
    } else {
        (RegKey::predef(HKEY_CURRENT_USER), r"Environment")
    };
    let key = hive
        .open_subkey_with_flags(key_name, KEY_READ | KEY_WRITE)
        .map_err(|error| {
            format!(
                "cannot open {} PATH: {error}",
                if machine { "machine" } else { "user" }
            )
        })?;
    let raw = key.get_raw_value("Path").unwrap_or(RegValue {
        bytes: vec![0, 0],
        vtype: REG_EXPAND_SZ,
    });
    if raw.vtype != REG_EXPAND_SZ && raw.vtype != REG_SZ {
        return Err("Windows PATH registry value is not a string".to_string());
    }
    let mut units = Vec::with_capacity(raw.bytes.len() / 2);
    for bytes in raw.bytes.chunks_exact(2) {
        units.push(u16::from_le_bytes([bytes[0], bytes[1]]));
    }
    while units.last() == Some(&0) {
        units.pop();
    }
    let current = String::from_utf16(&units).map_err(display)?;
    let executable = canonical(&env::current_exe().map_err(display)?)?;
    let bin = executable
        .parent()
        .ok_or("native CLI executable has no parent directory")?
        .to_string_lossy()
        .trim_end_matches(['\\', '/'])
        .to_string();
    let same = |entry: &str| {
        entry
            .trim()
            .trim_matches('"')
            .trim_end_matches(['\\', '/'])
            .eq_ignore_ascii_case(&bin)
    };
    let already_present = current.split(';').any(same);
    if operation == "install" && already_present {
        println!(
            "{} PATH: already contains {}",
            if machine { "machine" } else { "user" },
            bin
        );
        return Ok(());
    }
    let mut entries: Vec<String> = current
        .split(';')
        .map(str::trim)
        .filter(|entry| !entry.is_empty() && !same(entry))
        .map(ToString::to_string)
        .collect();
    if operation == "install" {
        entries.push(bin.clone());
    }
    let updated = entries.join(";");
    if updated.encode_utf16().count() >= 32_767 {
        return Err("Windows PATH would exceed the supported registry length".to_string());
    }
    let mut bytes = Vec::with_capacity((updated.len() + 1) * 2);
    for unit in updated.encode_utf16().chain(std::iter::once(0)) {
        bytes.extend_from_slice(&unit.to_le_bytes());
    }
    if installer {
        let (marker, _) = RegKey::predef(HKEY_LOCAL_MACHINE)
            .create_subkey(r"Software\Harnex")
            .map_err(display)?;
        marker
            .set_value("CliPathInstalled", &1_u32)
            .map_err(display)?;
    }
    if let Err(error) = key.set_raw_value(
        "Path",
        &RegValue {
            bytes,
            vtype: raw.vtype,
        },
    ) {
        if installer {
            if let Ok(marker) = RegKey::predef(HKEY_LOCAL_MACHINE)
                .open_subkey_with_flags(r"Software\Harnex", KEY_WRITE)
            {
                let _ = marker.delete_value("CliPathInstalled");
            }
        }
        return Err(display(error));
    }
    let environment: Vec<u16> = "Environment\0".encode_utf16().collect();
    unsafe {
        SendMessageTimeoutW(
            HWND_BROADCAST,
            WM_SETTINGCHANGE,
            0,
            environment.as_ptr() as isize,
            SMTO_ABORTIFHUNG,
            5_000,
            std::ptr::null_mut(),
        );
    }
    println!(
        "{} PATH: {} {}",
        if machine { "machine" } else { "user" },
        if operation == "install" {
            "added"
        } else {
            "removed"
        },
        bin
    );
    Ok(())
}

#[cfg(all(not(windows), not(target_os = "macos")))]
fn path_command(_args: &[String]) -> Result<(), String> {
    Err("PATH installer integration is only available on Windows".to_string())
}

#[cfg(target_os = "macos")]
fn path_command(args: &[String]) -> Result<(), String> {
    let operation = args.first().map(String::as_str).unwrap_or("");
    if !matches!(operation, "install" | "uninstall") {
        return Err("path requires install or uninstall".to_string());
    }
    if args.iter().any(|arg| arg == "--machine") {
        return Err("macOS PATH integration is per-user; --machine is not supported".to_string());
    }
    let bin_dir = home_dir().join(".local").join("bin");
    let link = bin_dir.join("harnex");
    let current = canonical(&env::current_exe().map_err(display)?)?;
    if operation == "uninstall" {
        if managed_cli_link(&link, &current)? {
            fs::remove_file(&link).map_err(display)?;
            println!("user PATH: removed {}", link.display());
        } else {
            println!("user PATH: {} is not installed", link.display());
        }
        return Ok(());
    }
    if current.starts_with("/Volumes") {
        return Err("refusing to install a CLI link from a mounted DMG; copy Harnex.app to /Applications first"
            .to_string());
    }
    fs::create_dir_all(&bin_dir).map_err(display)?;
    if managed_cli_link(&link, &current)? {
        println!(
            "user PATH: already linked {} -> {}",
            link.display(),
            current.display()
        );
        return Ok(());
    }
    std::os::unix::fs::symlink(&current, &link).map_err(display)?;
    println!(
        "user PATH: linked {} -> {}",
        link.display(),
        current.display()
    );
    println!("add this to ~/.zprofile if needed: export PATH=\"$HOME/.local/bin:$PATH\"");
    Ok(())
}

#[cfg(target_os = "macos")]
fn managed_cli_link(link: &Path, current: &Path) -> Result<bool, String> {
    let metadata = match fs::symlink_metadata(link) {
        Ok(metadata) => metadata,
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => return Ok(false),
        Err(error) => return Err(display(error)),
    };
    if !metadata.file_type().is_symlink() {
        return Err(format!(
            "refusing to replace {} because it is not a symbolic link",
            link.display()
        ));
    }
    let raw_target = fs::read_link(link).map_err(display)?;
    let target = if raw_target.is_absolute() {
        raw_target
    } else {
        link.parent()
            .unwrap_or_else(|| Path::new("."))
            .join(raw_target)
    };
    let target = canonical(&target).map_err(|_| {
        format!(
            "refusing to remove {} because its symbolic-link target cannot be verified",
            link.display()
        )
    })?;
    // Compare both identities after resolving aliases, including macOS /var -> /private/var.
    // Callers and tests may supply a valid executable through a non-canonical path.
    let current = canonical(current)?;
    if target != current {
        return Err(format!(
            "refusing to replace {} because it points to {}, not this Harnex executable",
            link.display(),
            target.display()
        ));
    }
    Ok(true)
}

fn global_options(input: Vec<String>) -> Result<(Vec<String>, Option<PathBuf>), String> {
    let mut args = Vec::new();
    let mut manifest = None;
    let mut index = 0;
    while index < input.len() {
        if input[index] == "--domain-manifest" {
            index += 1;
            let value = input
                .get(index)
                .ok_or("--domain-manifest requires a path")?;
            manifest = Some(absolute(PathBuf::from(value))?);
        } else if let Some(value) = input[index].strip_prefix("--domain-manifest=") {
            if value.is_empty() {
                return Err("--domain-manifest requires a path".to_string());
            }
            manifest = Some(absolute(PathBuf::from(value))?);
        } else {
            args.push(input[index].clone());
        }
        index += 1;
    }
    Ok((args, manifest))
}

fn doctor(manifest: Option<&Path>) -> Result<(), String> {
    println!("harnex doctor — native launcher");
    let bundle = RuntimeBundle::locate_and_verify()?;
    println!(
        "PASS  Runtime Bundle  {} ({})",
        bundle.root.display(),
        bundle.manifest.target
    );
    if let Some(file) = manifest {
        let session = Session::attach(file, &env::current_dir().map_err(display)?)?;
        let workspace = session.get_json("/api/v1/scope")?;
        println!("PASS  exact attach    {}", file.display());
        println!(
            "PASS  Runtime Domain  {} ({})",
            session.manifest.runtime_domain_id, session.manifest.owner_kind
        );
        println!(
            "PASS  workspace       {}  {}",
            workspace["scopeId"].as_str().unwrap_or("?"),
            workspace["projectRoot"].as_str().unwrap_or("?")
        );
    }
    Ok(())
}

fn cli(manifest: Option<&Path>, args: &[String]) -> Result<(), String> {
    let run_id = option(args, "--run");
    let steer = option(args, "--steer");
    let follow_up = option(args, "--follow-up");
    if run_id.is_some() || steer.is_some() || follow_up.is_some() {
        let file = manifest.ok_or("active Run input requires an attached Runtime Domain")?;
        let run_id = run_id.ok_or("active Run input requires --run <runId>")?;
        if !valid_run_id(&run_id) {
            return Err("--run requires a valid Run id".to_string());
        }
        let (mode, text) = match (steer, follow_up) {
            (Some(text), None) => ("STEER", text),
            (None, Some(text)) => ("QUEUE", text),
            _ => return Err("specify exactly one of --steer or --follow-up".to_string()),
        };
        let cwd = command_workspace(args)?;
        let session = Session::attach(file, &cwd)?;
        let result = if mode == "STEER" {
            session.post_json(&format!("/api/v1/runs/{}/inputs", url_component(&run_id)), json!({
                "mode": "STEER", "text": text, "channel": "cli", "clientRequestId": format!("native-cli-input-{}", Uuid::new_v4())
            }))?
        } else {
            let run = session.get_json(&format!("/api/v1/runs/{}", url_component(&run_id)))?;
            let conversation = run["conversationId"]
                .as_str()
                .ok_or("Run omitted conversationId")?;
            session.post_json(&format!("/api/v1/conversations/{}/submissions", url_component(conversation)), json!({
                "input": text, "execution": run["executionSpec"], "predecessorTurnId": run["turnId"],
                "channel": "cli", "clientRequestId": format!("native-cli-queue-{}", Uuid::new_v4())
            }))?
        };
        println!(
            "{}: {}",
            result["submissionId"]
                .as_str()
                .or_else(|| result["input"]["inputId"].as_str())
                .unwrap_or("input"),
            result["state"]
                .as_str()
                .or_else(|| result["input"]["status"].as_str())
                .unwrap_or("accepted")
        );
        return Ok(());
    }
    let prompt = option(args, "-p").or_else(|| option(args, "--prompt"));
    let Some(prompt) = prompt else {
        if args.iter().any(|arg| arg == "-p" || arg == "--prompt") {
            return Err("-p/--prompt requires a value".to_string());
        }
        return tui_command(manifest, args);
    };
    if prompt.is_empty() {
        return Err("-p/--prompt requires a value".to_string());
    }
    if wants_conversation(args) && manifest.is_none() {
        if has_remote(args) {
            return Err("--conversation uses local personal space; remove --remote for a one-shot conversation".into());
        }
        let runtime = open_personal_runtime()?;
        let session = Session::attach(&runtime.domain_dir.join("domain.json"), Path::new("."))?;
        return run_one_shot(&session, prompt, args);
    }
    if args
        .iter()
        .any(|arg| arg == "--remote" || arg.starts_with("--remote="))
    {
        reject_attached(manifest, "connect another Runtime")?;
        let remote = remote_commands::connect(args, "cli-headless")?;
        let result = run_one_shot(&Session::remote(&remote)?, prompt, args);
        let stopped = remote_commands::close(remote);
        result?;
        return stopped;
    }
    let cwd = command_workspace(args)?;
    if let Some(file) = manifest {
        let session = Session::attach(file, &cwd)?;
        return run_one_shot(&session, prompt, args);
    }

    let port = free_port()?;
    let domain_id = format!("cli-{}", std::process::id());
    let mut runtime = OwnedRuntime::start(&domain_id, "cli", port, &cwd)?;
    let result = (|| {
        let session = Session::attach(&runtime.manifest_file, &cwd)?;
        run_one_shot(&session, prompt, args)
    })();
    runtime.terminate();
    result
}

fn serve_port(
    args: &[String],
    harnex_port: Option<String>,
    server_port: Option<String>,
) -> Result<u16, String> {
    option(args, "--server.port")
        .or_else(|| option(args, "--port"))
        .or(harnex_port)
        .or(server_port)
        .map(|value| {
            value
                .parse::<u16>()
                .map_err(|_| "invalid serve port".to_string())
        })
        .transpose()
        .map(|port| port.unwrap_or(DEFAULT_WEB_PORT))
}

fn serve(args: &[String]) -> Result<(), String> {
    let cwd = command_workspace(args)?;
    let port = serve_port(
        args,
        non_blank_env("HARNEX_PORT"),
        non_blank_env("SERVER_PORT"),
    )?;
    let stopping = Arc::new(AtomicBool::new(false));
    let signal = stopping.clone();
    ctrlc::set_handler(move || {
        signal.store(true, Ordering::SeqCst);
    })
    .map_err(|error| format!("cannot install signal handler: {error}"))?;
    let mut runtime = OwnedRuntime::start("standalone-web", "standalone", port, &cwd)?;
    println!("Harnex Runtime ready: http://127.0.0.1:{port}");
    loop {
        if let Some(status) = runtime.child.try_wait().map_err(display)? {
            runtime.terminated = true;
            runtime.cleanup();
            return if status.success() {
                Ok(())
            } else {
                Err(format!("Runtime exited with {status}"))
            };
        }
        if stopping.load(Ordering::SeqCst) {
            runtime.terminate();
            return Ok(());
        }
        thread::sleep(Duration::from_millis(250));
    }
}

fn stop_command(manifest: Option<&Path>, args: &[String]) -> Result<(), String> {
    if let Some(file) = manifest {
        let run_id = option(args, "--run").ok_or("attached stop requires --run <runId>")?;
        if !valid_run_id(&run_id) {
            return Err("--run requires a valid Run id".to_string());
        }
        let session = Session::attach(file, &env::current_dir().map_err(display)?)?;
        let response = session.cancel_run(&run_id)?;
        println!(
            "run {run_id}: {}",
            if response["cancelled"].as_bool() == Some(true) {
                "cancelled"
            } else {
                "not running"
            }
        );
        return Ok(());
    }
    stop_standalone()
}

fn runtime_command(manifest: Option<&Path>, args: &[String]) -> Result<(), String> {
    let operation = args.first().map(String::as_str).unwrap_or("status");
    if operation == "status" {
        return status(manifest);
    }
    reject_attached(manifest, operation)?;
    match operation {
        "stop" => stop_standalone(),
        "restart" => {
            stop_standalone()?;
            serve(&args[1..])
        }
        _ => Err("runtime command must be status, stop or restart".to_string()),
    }
}

fn status(manifest: Option<&Path>) -> Result<(), String> {
    if let Some(file) = manifest {
        let session = Session::attach(file, &env::current_dir().map_err(display)?)?;
        let workspace = session.get_json("/api/v1/scope")?;
        println!(
            "attached  domain={} owner={} runtimePid={}",
            session.manifest.runtime_domain_id,
            session.manifest.owner_kind,
            session.manifest.runtime_pid
        );
        println!(
            "workspace={} endpoint={} root={}",
            session.manifest.scope_id,
            session.endpoint,
            workspace["projectRoot"].as_str().unwrap_or("?")
        );
        return Ok(());
    }
    let paths = DomainPaths::new("standalone-web")?;
    if !paths.owner_file.exists() {
        println!("not running");
        return Ok(());
    }
    let owner: OwnerLease = read_json(&paths.owner_file)?;
    owner.validate("standalone-web", "standalone")?;
    if !pid_alive(owner.owner_pid) {
        if stale_lease_old_enough(&paths.owner_file) {
            cleanup_exact_owner(&paths, &owner);
            println!("stale owner cleaned");
        } else {
            println!("owner lease is still initializing; retry");
        }
    } else {
        println!(
            "running  domain={} pid={} started={}",
            owner.runtime_domain_id, owner.owner_pid, owner.started_at
        );
    }
    Ok(())
}

fn app(manifest: Option<&Path>) -> Result<(), String> {
    if let Some(file) = manifest {
        let session = Session::attach(file, &env::current_dir().map_err(display)?)?;
        return open_target(&session.endpoint);
    }
    let current = env::current_exe().map_err(display)?;
    #[cfg(target_os = "macos")]
    if let Some(bundle) = current
        .ancestors()
        .find(|path| path.extension().and_then(|value| value.to_str()) == Some("app"))
    {
        Command::new("open").arg(bundle).spawn().map_err(display)?;
        return Ok(());
    }
    #[cfg(windows)]
    let candidates = [
        current
            .parent()
            .and_then(Path::parent)
            .and_then(Path::parent)
            .map(|p| p.join("Harnex.exe")),
        current
            .parent()
            .and_then(Path::parent)
            .map(|p| p.join("Harnex.exe")),
    ];
    #[cfg(windows)]
    for candidate in candidates.into_iter().flatten() {
        if candidate.is_file() && !same_path(&candidate, &current) {
            Command::new(candidate).spawn().map_err(display)?;
            return Ok(());
        }
    }
    Err("desktop application was not found beside the installed Runtime Bundle".to_string())
}

fn reject_attached(manifest: Option<&Path>, operation: &str) -> Result<(), String> {
    if manifest.is_some() {
        Err(format!(
            "attached Client cannot perform Runtime lifecycle operation '{operation}'"
        ))
    } else {
        Ok(())
    }
}

#[derive(Clone)]
struct Session {
    endpoint: String,
    credentials: SessionCredential,
    manifest: DomainManifest,
    client: Client,
}

impl Session {
    fn remote(connection: &harnex_host_runtime::Connection) -> Result<Self, String> {
        let descriptor = &connection.control.descriptor;
        let session = Self {
            endpoint: connection.endpoint.clone(),
            credentials: SessionCredential::fixed(descriptor.token.clone()),
            client: Client::builder()
                .no_proxy()
                .timeout(Duration::from_secs(35 * 60))
                .build()
                .map_err(display)?,
            manifest: DomainManifest {
                auth_type: Some("bearer-json".into()),
                remote: Some(
                    json!({"host":connection.control.profile.endpoint_label(),"root":descriptor.root,
                    "runtimePid":descriptor.pid,"connectionId":connection.control.profile.id}),
                ),
                schema_version: 3,
                runtime_profile: "PROJECT".into(),
                runtime_domain_id: descriptor.domain_id.clone(),
                owner_kind: "cli".into(),
                owner_pid: std::process::id(),
                owner_instance_id: descriptor.instance_id.clone(),
                runtime_pid: connection
                    .tunnel
                    .as_ref()
                    .ok_or("SSH transport unavailable")?
                    .id(),
                canonical_root: Some(PathBuf::from(&descriptor.root)),
                scope_id: descriptor.workspace_id.clone(),
                instance_id: descriptor.instance_id.clone(),
                endpoint: connection.endpoint.clone(),
                lifecycle: "READY".into(),
                protocol_version: descriptor.protocol_version.clone(),
                boot_token_file: PathBuf::new(),
                config_revision: "remote".into(),
                published_at: now_string(),
            },
        };
        let protocol = session.get_json("/api/v1/protocol")?;
        if protocol["version"].as_str() != Some(PROTOCOL_VERSION) {
            return Err("remote Runtime protocol mismatch".into());
        }
        Ok(session)
    }

    fn attach(file: &Path, cwd: &Path) -> Result<Self, String> {
        let manifest_file = absolute(file.to_path_buf())?;
        let domain_dir = manifest_file
            .parent()
            .ok_or("manifest has no parent directory")?;
        let manifest: DomainManifest = read_json(&manifest_file)?;
        manifest.validate(domain_dir, cwd)?;
        let owner: OwnerLease = read_json(&domain_dir.join("owner.json"))?;
        owner.validate(&manifest.runtime_domain_id, &manifest.owner_kind)?;
        if owner.owner_pid != manifest.owner_pid
            || owner.owner_instance_id != manifest.owner_instance_id
        {
            return Err("Runtime Domain manifest does not match owner lease".to_string());
        }
        if !pid_alive(manifest.owner_pid) || !pid_alive(manifest.runtime_pid) {
            return Err("Runtime Domain manifest refers to a stopped process".to_string());
        }
        let client = Client::builder()
            .no_proxy()
            .timeout(Duration::from_secs(35 * 60))
            .build()
            .map_err(display)?;
        let candidate = fs::read_to_string(&manifest.boot_token_file)
            .map_err(display)?
            .trim()
            .to_string();
        if candidate.is_empty() {
            return Err("Runtime Domain boot token is empty".to_string());
        }
        if manifest.remote.is_some() {
            let credential: Value = serde_json::from_str(&candidate).map_err(display)?;
            let token = credential["token"]
                .as_str()
                .filter(|token| !token.is_empty())
                .ok_or("remote session credential missing")?
                .to_string();
            let session = Self {
                endpoint: manifest.endpoint.clone(),
                credentials: SessionCredential::fixed(token),
                manifest,
                client,
            };
            let scope = session.get_json("/api/v1/scope")?;
            session.manifest.verify_scope(&scope)?;
            if session.get_json("/api/v1/protocol")?["version"].as_str() != Some(PROTOCOL_VERSION) {
                return Err("remote protocol mismatch".into());
            }
            return Ok(session);
        }
        let identity = identity_headers(&manifest)?;
        let scope_response = client
            .get(format!("{}/api/v1/scope", manifest.endpoint))
            .headers(identity.clone())
            .timeout(Duration::from_secs(3))
            .send()
            .map_err(display)?;
        let scope = checked_json(scope_response, &manifest)?;
        manifest.verify_scope(&scope)?;
        let protocol_response = client
            .get(format!("{}/api/v1/protocol", manifest.endpoint))
            .headers(identity.clone())
            .timeout(Duration::from_secs(3))
            .send()
            .map_err(display)?;
        let protocol = checked_json(protocol_response, &manifest)?;
        if protocol["version"].as_str() != Some(manifest.protocol_version.as_str()) {
            return Err("Runtime protocol does not match Domain manifest".to_string());
        }
        let auth_response = client
            .post(format!("{}/api/v1/auth/boot-token", manifest.endpoint))
            .headers(identity)
            .json(&json!({ "token": candidate }))
            .timeout(Duration::from_secs(3))
            .send()
            .map_err(display)?;
        let auth = checked_json(auth_response, &manifest)?;
        let token = auth["token"]
            .as_str()
            .ok_or("boot-token handshake returned no session")?
            .to_string();
        Ok(Self {
            endpoint: manifest.endpoint.clone(),
            credentials: SessionCredential::local(
                token,
                manifest.endpoint.clone(),
                manifest.scope_id.clone(),
                manifest.instance_id.clone(),
                manifest.boot_token_file.clone(),
            ),
            manifest,
            client,
        })
    }

    fn request(
        &self,
        method: reqwest::Method,
        path: &str,
    ) -> Result<reqwest::blocking::RequestBuilder, String> {
        let mut headers = identity_headers(&self.manifest)?;
        headers.insert(
            ACCEPT,
            HeaderValue::from_static("application/json, text/event-stream"),
        );
        Ok(self
            .client
            .request(method, format!("{}{}", self.endpoint, path))
            .headers(headers))
    }

    fn send(&self, request: reqwest::blocking::RequestBuilder) -> Result<Response, String> {
        let token = self.credentials.token(None)?;
        let mut request = request.build().map_err(display)?;
        request.headers_mut().insert(
            AUTHORIZATION,
            HeaderValue::from_str(&format!("Bearer {token}")).map_err(display)?,
        );
        let retry = request.try_clone();
        let response = check_response_identity(
            self.client.execute(request).map_err(display)?,
            &self.manifest,
        )?;
        if response.status() == reqwest::StatusCode::UNAUTHORIZED {
            let renewed = self.credentials.token(Some(&token))?;
            // 401 is rejected before dispatch. Retry only a replayable request body,
            // once, retaining the same identity and clientRequestId for writes.
            if renewed != token {
                if let Some(mut retry) = retry {
                    retry.headers_mut().insert(
                        AUTHORIZATION,
                        HeaderValue::from_str(&format!("Bearer {renewed}")).map_err(display)?,
                    );
                    return check_response_identity(
                        self.client.execute(retry).map_err(display)?,
                        &self.manifest,
                    );
                }
            }
        }
        Ok(response)
    }

    fn get_json(&self, path: &str) -> Result<Value, String> {
        let response = self.send(self.request(reqwest::Method::GET, path)?)?;
        checked_json(response, &self.manifest)
    }

    fn cancel_run(&self, run_id: &str) -> Result<Value, String> {
        let path = format!("/api/v1/runs/{}", url_component(run_id));
        let run = self.get_json(&path)?;
        let body = if run["goalId"].is_string() {
            json!({"revision":run["revision"],"clientRequestId":format!("cli-cancel-{}",Uuid::new_v4())})
        } else {
            json!({})
        };
        self.post_json(&format!("{path}/cancel"), body)
    }

    fn post_json(&self, path: &str, body: Value) -> Result<Value, String> {
        let response = self.send(self.request(reqwest::Method::POST, path)?.json(&body))?;
        checked_json(response, &self.manifest)
    }
}

fn run_one_shot(session: &Session, prompt: String, args: &[String]) -> Result<(), String> {
    require_conversation_target(&session.manifest.runtime_profile, args)?;
    let permission = option(args, "--permission-mode").unwrap_or_else(|| "ask".to_string());
    if !["ask", "accept-edits", "yolo"].contains(&permission.as_str()) {
        return Err("--permission-mode must be ask, accept-edits or yolo".to_string());
    }
    if option(args, "--mode").is_some() {
        return Err("--mode has been removed; use --max-work-iterations for a longer step or /goal for continued work".to_string());
    }
    let mut body = json!({
        "input": prompt,
        "execution": { "kind": "AGENT", "text": {}, "permissionMode": permission,
          "interactionTarget": null, "planReview": "AUTOMATIC", "limits": {} },
        "channel": "cli", "stream": false, "clientRequestId": format!("native-cli-{}", Uuid::new_v4())
    });
    if let Some(value) = option(args, "--max-work-iterations") {
        let limit: u32 = value
            .parse()
            .map_err(|_| "--max-work-iterations must be a positive integer".to_string())?;
        if limit == 0 {
            return Err("--max-work-iterations must be positive".to_string());
        }
        body["execution"]["limits"]["maxWorkIterations"] = json!(limit);
    }
    for (flag, field) in [("--model", "model"), ("--provider", "provider")] {
        if let Some(value) = option(args, flag) {
            body["execution"]["text"][field] = Value::String(value);
        }
    }
    if let Some(value) = option(args, "--toolsets") {
        body["execution"]["toolsets"] = Value::Array(
            value
                .split(',')
                .filter(|item| !item.trim().is_empty())
                .map(|item| Value::String(item.trim().to_string()))
                .collect(),
        );
    }
    if session.manifest.runtime_profile == "PERSONAL" {
        body["execution"] =
            json!({"kind":"CONVERSATION","action":"AUTO","text":body["execution"]["text"]});
    }
    let created = session.post_json("/api/v1/runs", body)?;
    let run_id = created["runId"]
        .as_str()
        .ok_or("Runtime did not return runId")?
        .to_string();
    let interrupted = Arc::new(AtomicBool::new(false));
    let signal = interrupted.clone();
    let cancel_session = session.clone();
    let cancel_run = run_id.clone();
    ctrlc::set_handler(move || {
        if !signal.swap(true, Ordering::SeqCst) {
            let session = cancel_session.clone();
            let run = cancel_run.clone();
            thread::spawn(move || {
                let _ = session.cancel_run(&run);
            });
        }
    })
    .map_err(display)?;

    let mut after_seq = 0_u64;
    let mut stream_epoch: Option<u64> = None;
    for attempt in 0..20 {
        let mut path = format!(
            "/api/v1/runs/{}/events?afterSeq={after_seq}",
            url_component(&run_id)
        );
        if let Some(epoch) = stream_epoch {
            path.push_str(&format!("&streamEpoch={epoch}"));
        }
        let response = session.send(session.request(reqwest::Method::GET, &path)?)?;
        let response = check_response_identity(response, &session.manifest)?;
        if response.status().as_u16() == 409 {
            let snapshot = session.get_json(&format!(
                "/api/v1/runs/{}/presentation",
                url_component(&run_id)
            ))?;
            apply_headless_presentation(&snapshot, &mut after_seq, &mut stream_epoch)?;
            if snapshot["run"]["terminal"].as_bool() == Some(true) {
                return if snapshot["run"]["status"].as_str() == Some("COMPLETED") {
                    Ok(())
                } else {
                    Err(format!(
                        "Run {}",
                        snapshot["run"]["status"].as_str().unwrap_or("failed")
                    ))
                };
            }
            continue;
        }
        if response.status().as_u16() == 410 || response.status().as_u16() == 404 {
            let run = session.get_json(&format!("/api/v1/runs/{}", url_component(&run_id)))?;
            return match run["status"].as_str().unwrap_or("") {
                "COMPLETED" => Ok(()),
                "INCOMPLETE" => Err(run["result"]["detail"]
                    .as_str()
                    .unwrap_or(
                        "Run incomplete; provide the required input or adjust the step limit",
                    )
                    .to_string()),
                "CANCELLED" => Err("Run cancelled".to_string()),
                "EXPIRED" => Err("Run expired".to_string()),
                _ => Err("Run failed".to_string()),
            };
        }
        let (latest_seq, latest_epoch, reset) = consume_sse(
            response,
            args.iter().any(|arg| arg == "--verbose"),
            after_seq,
            stream_epoch,
        )?;
        after_seq = latest_seq;
        stream_epoch = latest_epoch;
        if reset {
            let snapshot = session.get_json(&format!(
                "/api/v1/runs/{}/presentation",
                url_component(&run_id)
            ))?;
            apply_headless_presentation(&snapshot, &mut after_seq, &mut stream_epoch)?;
            if snapshot["run"]["terminal"].as_bool() == Some(true) {
                return if snapshot["run"]["status"].as_str() == Some("COMPLETED") {
                    Ok(())
                } else {
                    Err(format!(
                        "Run {}",
                        snapshot["run"]["status"].as_str().unwrap_or("failed")
                    ))
                };
            }
            continue;
        }
        let run = session.get_json(&format!("/api/v1/runs/{}", url_component(&run_id)))?;
        if run["terminal"].as_bool() == Some(true) {
            if let Ok(snapshot) = session.get_json(&format!(
                "/api/v1/runs/{}/presentation",
                url_component(&run_id)
            )) {
                print_snapshot_artifacts(&snapshot);
            }
        }
        match run["status"].as_str().unwrap_or("") {
            "COMPLETED" => return Ok(()),
            "INCOMPLETE" => {
                return Err(run["result"]["detail"]
                    .as_str()
                    .unwrap_or(
                        "Run incomplete; provide the required input or adjust the step limit",
                    )
                    .to_string())
            }
            "PAUSED" | "BLOCKED" | "BUDGET_LIMITED" | "READY_FOR_REVIEW" | "INTERRUPTED" => {
                return Err(format!(
                    "Goal {}: {}",
                    run["status"].as_str().unwrap_or("stopped"),
                    run["goalExecution"]["stopReason"]
                        .as_str()
                        .unwrap_or("resume or review the Goal to continue")
                ));
            }
            "FAILED" => return Err("Run failed".to_string()),
            "CANCELLED" => return Err("Run cancelled".to_string()),
            "EXPIRED" => return Err("Run expired".to_string()),
            _ => thread::sleep(Duration::from_millis((250 * (attempt + 1)).min(2000) as u64)),
        }
    }
    Err("Run event replay ended before a terminal projection was observed".to_string())
}

fn consume_sse(
    response: Response,
    verbose: bool,
    mut after_seq: u64,
    mut stream_epoch: Option<u64>,
) -> Result<(u64, Option<u64>, bool), String> {
    let status = response.status();
    if !status.is_success() {
        return Err(format!(
            "Runtime HTTP {status}: {}",
            response.text().map_err(display)?
        ));
    }
    let mut reader = BufReader::new(response);
    let mut line = String::new();
    let mut data = Vec::new();
    let mut reset = false;
    loop {
        line.clear();
        let read = reader.read_line(&mut line).map_err(display)?;
        if read == 0 {
            if !data.is_empty() {
                if apply_sse_data(
                    &data,
                    verbose,
                    &mut after_seq,
                    &mut stream_epoch,
                    &mut reset,
                )? {
                    break;
                }
            }
            break;
        }
        let trimmed = line.trim_end_matches(['\r', '\n']);
        if trimmed.is_empty() {
            if !data.is_empty() {
                if apply_sse_data(
                    &data,
                    verbose,
                    &mut after_seq,
                    &mut stream_epoch,
                    &mut reset,
                )? {
                    break;
                }
                data.clear();
            }
        } else if let Some(value) = trimmed.strip_prefix("data:") {
            data.push(value.trim_start().to_string());
        }
    }
    Ok((after_seq, stream_epoch, reset))
}

fn apply_sse_data(
    data: &[String],
    verbose: bool,
    after_seq: &mut u64,
    stream_epoch: &mut Option<u64>,
    reset: &mut bool,
) -> Result<bool, String> {
    let event: Value = serde_json::from_str(&data.join("\n")).map_err(display)?;
    if event["type"] == "notice" && event["data"]["kind"] == "buffer_miss"
        || stream_epoch.is_some()
            && event["streamEpoch"].as_u64().is_some()
            && *stream_epoch != event["streamEpoch"].as_u64()
    {
        *reset = true;
        return Ok(true);
    }
    if event["seq"].as_u64().is_some_and(|seq| seq <= *after_seq) {
        return Ok(false);
    }
    *after_seq = event["seq"]
        .as_u64()
        .map(|seq| (*after_seq).max(seq))
        .unwrap_or(*after_seq);
    *stream_epoch = event["streamEpoch"].as_u64().or(*stream_epoch);
    render_event(&event, verbose)?;
    Ok(event["type"].as_str() == Some("done")
        || event["type"].as_str() == Some("goal_state_changed")
            && matches!(
                event["data"]["status"].as_str().unwrap_or(""),
                "PAUSED" | "BLOCKED" | "BUDGET_LIMITED" | "READY_FOR_REVIEW" | "INTERRUPTED"
            ))
}

fn snapshot_text(snapshot: &Value) -> String {
    if let Some(text) = snapshot["live"]["text"]
        .as_str()
        .filter(|text| !text.is_empty())
    {
        return text.to_string();
    }
    snapshot["history"]["answer"]["contentParts"]
        .as_array()
        .map(|parts| {
            parts
                .iter()
                .filter(|part| part["type"] == "TEXT")
                .filter_map(|part| part["text"].as_str())
                .collect::<Vec<_>>()
                .join("\n")
        })
        .unwrap_or_default()
}
fn print_snapshot_artifacts(snapshot: &Value) {
    if let Some(parts) = snapshot["history"]["answer"]["contentParts"].as_array() {
        let mut seen = std::collections::HashSet::new();
        for id in parts
            .iter()
            .filter(|part| part["type"] == "ARTIFACT_REF")
            .filter_map(|part| part["artifactId"].as_str())
        {
            if seen.insert(id) {
                println!("[Artifact: {id}]");
            }
        }
    }
}
fn apply_headless_presentation(
    snapshot: &Value,
    after_seq: &mut u64,
    epoch: &mut Option<u64>,
) -> Result<(), String> {
    *after_seq = snapshot["cursor"]["projectionSeq"]
        .as_u64()
        .ok_or("Presentation omitted cursor")?;
    *epoch = snapshot["cursor"]["streamEpoch"].as_u64();
    let text = snapshot_text(snapshot);
    eprintln!("[harnex] Restored the captured Run presentation");
    if !text.is_empty() {
        println!("\n[Recovered response]\n{text}");
    }
    print_snapshot_artifacts(snapshot);
    Ok(())
}

fn render_event(event: &Value, verbose: bool) -> Result<(), String> {
    let kind = event["type"].as_str().unwrap_or("");
    let data = &event["data"];
    match kind {
        "text_delta" => {
            print!("{}", data["text"].as_str().unwrap_or(""));
            std::io::stdout().flush().map_err(display)?;
        }
        "message_end" => println!(),
        "tool_use_start" => {
            let name = data["name"].as_str().unwrap_or("tool");
            match data["purpose"].as_str().filter(|purpose| !purpose.is_empty()) {
                Some(purpose) => eprintln!("  ⏵ {name} · {purpose}"),
                None => eprintln!("  ⏵ {name}"),
            }
        }
        "tool_use_end" => eprintln!(
            "  {} {}",
            if data["error"].as_bool().unwrap_or(false) {
                "✗"
            } else {
                "✓"
            },
            data["name"].as_str().unwrap_or("tool")
        ),
        "command_result" => {
            if let Some(message) = data["message"].as_str() {
                println!("{message}");
            }
        }
        "error" => eprintln!(
            "harnex: {}",
            data["message"].as_str().unwrap_or("Run failed")
        ),
        "approval_required" | "user_question" | "plan_review" => {
            return Err(format!(
                "one-shot without a reply handler unexpectedly requested interaction: {kind}"
            ));
        }
        _ if verbose => eprintln!("[{kind}] {data}"),
        _ => {}
    }
    Ok(())
}

fn project_data_directory(
    root: &Path,
    configured: Option<PathBuf>,
    recorded: Option<PathBuf>,
) -> PathBuf {
    root.join(
        configured
            .or(recorded)
            .unwrap_or_else(|| PathBuf::from(".harnex/data")),
    )
}

struct OwnedRuntime {
    child: harnex_host_runtime::owned_process::OwnedChild,
    paths: DomainPaths,
    owner_instance_id: String,
    supervisor_token: String,
    endpoint: String,
    manifest_file: PathBuf,
    terminated: bool,
}

impl OwnedRuntime {
    fn start(domain_id: &str, owner_kind: &str, port: u16, root: &Path) -> Result<Self, String> {
        let bundle = RuntimeBundle::locate_and_verify()?;
        let paths = DomainPaths::new(domain_id)?;
        paths.ensure_private()?;
        cleanup_stale(&paths);
        let owner_instance_id = Uuid::new_v4().to_string();
        let canonical_root = canonical(root)?;
        let workspace_id = short_hash(canonical_root.to_string_lossy().as_bytes());
        let instance_id = Uuid::new_v4().to_string();
        let endpoint = format!("http://127.0.0.1:{port}");
        let supervisor_token = Uuid::new_v4().to_string();
        let catalog_file = paths.user_data_dir.join("catalog").join("workspaces.json");
        let catalog: Value = if catalog_file.is_file() {
            read_json(&catalog_file)?
        } else {
            json!({})
        };
        let recorded_data = catalog["workspaces"]
            .as_array()
            .and_then(|items| {
                items.iter().find(|item| {
                    item["id"].as_str() == Some(&workspace_id) && !item["remoteProfile"].is_string()
                })
            })
            .and_then(|item| item["dataDir"].as_str())
            .filter(|value| !value.is_empty())
            .map(PathBuf::from);
        let data_dir = project_data_directory(
            &canonical_root,
            non_blank_env("HARNEX_DATA_DIR").map(PathBuf::from),
            recorded_data,
        );
        fs::create_dir_all(&data_dir).map_err(display)?;
        let mut command = Command::new(&bundle.java);
        #[cfg(unix)]
        {
            use std::os::unix::process::CommandExt;
            command.process_group(0);
        }
        if let Some(digest) = bundle.manifest.files.get(&bundle.manifest.entrypoint) {
            command.arg(format!("-Dharnex.runtime.verified-sha256={digest}"));
        }
        command
            .args(["-Xms256m", "-Xmx512m", "-jar"])
            .arg(&bundle.jar)
            .arg(format!("--server.port={port}"))
            .arg("--server.address=127.0.0.1")
            .arg(format!("--harnex.data-dir={}", data_dir.display()))
            .arg(format!(
                "--harnex.user-data-dir={}",
                paths.user_data_dir.display()
            ))
            .arg(format!("--harnex.runtime-domain-id={domain_id}"))
            .arg(format!(
                "--harnex.runtime-domain-dir={}",
                paths.dir.display()
            ))
            .arg(format!("--harnex.runtime-owner-kind={owner_kind}"))
            .env("HARNEX_WORKSPACE", &canonical_root)
            .env("HARNEX_SCOPE_ID", &workspace_id)
            .env("HARNEX_INSTANCE_ID", &instance_id)
            .env("HARNEX_USER_DATA_DIR", &paths.user_data_dir)
            .env("HARNEX_RUNTIME_DOMAIN_ID", domain_id)
            .env("HARNEX_RUNTIME_DOMAIN_DIR", &paths.dir)
            .env("HARNEX_RUNTIME_OWNER_KIND", owner_kind)
            .env("HARNEX_INTERNAL_SUPERVISOR_TOKEN", &supervisor_token)
            .current_dir(&canonical_root)
            .stdin(Stdio::null());
        let runtime_log_dir = if owner_kind == "cli" {
            let log_dir = paths.dir.join("logs");
            fs::create_dir_all(&log_dir).map_err(display)?;
            harnex_host_runtime::logs::prepare(&log_dir).map_err(display)?;
            command.stdout(Stdio::piped()).stderr(Stdio::piped());
            Some(log_dir)
        } else {
            command.stdout(Stdio::inherit()).stderr(Stdio::inherit());
            None
        };
        inject_user_config(&mut command, &paths.user_data_dir)?;
        let config_revision = config_revision(&paths.user_data_dir)?;
        // Acquire ownership only after every preparatory fallible operation has succeeded.
        // From this point onward each error path must release this exact lease.
        let owner = OwnerLease {
            schema_version: 1,
            runtime_domain_id: domain_id.to_string(),
            owner_kind: owner_kind.to_string(),
            owner_pid: std::process::id(),
            owner_instance_id: owner_instance_id.clone(),
            started_at: now_string(),
        };
        create_json(&paths.owner_file, &owner).map_err(|error| {
            format!("Runtime Domain {domain_id} is already owned; stop it or retry ({error})")
        })?;
        for file in [&paths.manifest_file, &paths.boot_token_file] {
            let _ = fs::remove_file(file);
        }
        let mut child =
            harnex_host_runtime::owned_process::spawn(&mut command).map_err(|error| {
                let _ = fs::remove_file(&paths.owner_file);
                display(error)
            })?;
        if let Some(directory) = &runtime_log_dir {
            harnex_host_runtime::logs::capture_output(&mut child, directory);
        }
        let runtime_pid = child.id();
        let manifest = DomainManifest {
            remote: None,
            auth_type: None,
            schema_version: 3,
            runtime_profile: "PROJECT".into(),
            runtime_domain_id: domain_id.to_string(),
            owner_kind: owner_kind.to_string(),
            owner_pid: std::process::id(),
            owner_instance_id: owner_instance_id.clone(),
            runtime_pid,
            canonical_root: Some(canonical_root.clone()),
            scope_id: workspace_id,
            instance_id,
            endpoint: endpoint.clone(),
            lifecycle: "READY".to_string(),
            protocol_version: PROTOCOL_VERSION.to_string(),
            boot_token_file: paths.boot_token_file.clone(),
            config_revision,
            published_at: now_string(),
        };
        let mut owned = Self {
            child,
            manifest_file: paths.manifest_file.clone(),
            paths,
            owner_instance_id,
            supervisor_token,
            endpoint,
            terminated: false,
        };
        if let Err(error) = wait_ready(
            &mut owned.child,
            &owned.endpoint,
            &manifest.scope_id,
            &manifest.instance_id,
            runtime_log_dir.as_deref(),
            &canonical_root,
            &owned.paths.user_data_dir,
        ) {
            owned.terminate();
            return Err(error);
        }
        if let Err(error) = write_json(&owned.manifest_file, &manifest) {
            owned.terminate();
            return Err(error);
        }
        Ok(owned)
    }

    fn terminate(&mut self) {
        if self.terminated {
            return;
        }
        self.terminated = true;
        let _ = Client::builder()
            .no_proxy()
            .timeout(Duration::from_secs(12))
            .build()
            .and_then(|client| {
                client
                    .post(format!(
                        "{}/api/v1/admin/drain?timeoutMs=10000",
                        self.endpoint
                    ))
                    .header("X-Harnex-Supervisor-Token", &self.supervisor_token)
                    .send()
            });
        if self.child.try_wait().ok().flatten().is_none() {
            if kill_owned_runtime(self.child.id()).is_err() {
                let _ = self.child.kill();
            }
            let deadline = Instant::now() + Duration::from_secs(5);
            while self.child.try_wait().ok().flatten().is_none() && Instant::now() < deadline {
                thread::sleep(Duration::from_millis(50));
            }
            if self.child.try_wait().ok().flatten().is_none() {
                let _ = force_kill_owned_runtime(self.child.id());
            }
            let _ = self.child.wait();
        }
        self.cleanup();
    }

    fn cleanup(&self) {
        let current = read_json::<OwnerLease>(&self.paths.owner_file).ok();
        let owns_domain = current
            .as_ref()
            .map(|owner| owner.owner_instance_id.as_str())
            == Some(&self.owner_instance_id);
        if owns_domain {
            let _ = fs::remove_file(&self.paths.owner_file);
            let _ = fs::remove_file(&self.paths.manifest_file);
        }
    }
}

fn kill_owned_runtime(pid: u32) -> Result<(), String> {
    #[cfg(unix)]
    {
        let result = unsafe { libc::kill(-(pid as i32), libc::SIGTERM) };
        if result != 0 {
            return Err(std::io::Error::last_os_error().to_string());
        }
        Ok(())
    }
    #[cfg(windows)]
    {
        kill_pid(pid)
    }
}

fn force_kill_owned_runtime(pid: u32) -> Result<(), String> {
    #[cfg(unix)]
    {
        let result = unsafe { libc::kill(-(pid as i32), libc::SIGKILL) };
        if result != 0 {
            return Err(std::io::Error::last_os_error().to_string());
        }
        Ok(())
    }
    #[cfg(windows)]
    {
        kill_pid(pid)
    }
}

impl Drop for OwnedRuntime {
    fn drop(&mut self) {
        self.terminate();
    }
}

fn wait_ready(
    child: &mut Child,
    endpoint: &str,
    workspace_id: &str,
    instance_id: &str,
    log_dir: Option<&Path>,
    workspace_root: &Path,
    user_data_dir: &Path,
) -> Result<(), String> {
    let client = Client::builder()
        .no_proxy()
        .timeout(Duration::from_secs(1))
        .build()
        .map_err(display)?;
    let deadline = Instant::now() + Duration::from_secs(120);
    while Instant::now() < deadline {
        if let Some(status) = child.try_wait().map_err(display)? {
            return Err(runtime_exit_before_ready_message(
                status.to_string(),
                log_dir,
                workspace_root,
                user_data_dir,
            ));
        }
        if let Ok(response) = client.get(format!("{endpoint}/api/v1/protocol")).send() {
            let identity_matches = response
                .headers()
                .get("X-Harnex-Scope-Id")
                .and_then(|value| value.to_str().ok())
                == Some(workspace_id)
                && response
                    .headers()
                    .get("X-Harnex-Instance-Id")
                    .and_then(|value| value.to_str().ok())
                    == Some(instance_id);
            if identity_matches
                && (response.status().is_success() || response.status().as_u16() == 401)
            {
                return Ok(());
            }
        }
        thread::sleep(Duration::from_millis(400));
    }
    Err("Runtime readiness timed out".to_string())
}

fn runtime_exit_before_ready_message(
    status: String,
    log_dir: Option<&Path>,
    workspace_root: &Path,
    user_data_dir: &Path,
) -> String {
    if log_dir.is_some_and(runtime_logs_report_workspace_lock) {
        let mut message = format!(
            "Workspace is already open in another Harnex Runtime: {}. Close the other desktop/tray or terminal Runtime before starting a standalone TUI.",
            workspace_root.display()
        );
        if let Some(manifest) = desktop_manifest_for_workspace(user_data_dir, workspace_root) {
            message.push_str(&format!(
                "\nTo share the desktop Runtime instead, run:\n  harnex --domain-manifest \"{}\" tui --workspace \"{}\"",
                manifest.display(),
                workspace_root.display()
            ));
        }
        return message;
    }

    let logs = log_dir.map_or_else(
        || "Runtime logs were not captured for this owner".to_string(),
        |directory| format!("See Runtime logs in {}", directory.display()),
    );
    format!("Runtime exited before readiness ({status}). {logs}")
}

fn runtime_logs_report_workspace_lock(log_dir: &Path) -> bool {
    ["runtime-err.log", "runtime-out.log"]
        .into_iter()
        .filter_map(|name| fs::read_to_string(log_dir.join(name)).ok())
        .any(|log| {
            log.contains("already locked by another Harnex JVM")
                || log.contains("DataDirLock") && log.contains("refusing to start")
        })
}

fn desktop_manifest_for_workspace(user_data_dir: &Path, workspace_root: &Path) -> Option<PathBuf> {
    let manifest_file = user_data_dir
        .join("runtime-domains")
        .join("tauri-main")
        .join("domain.json");
    let manifest: DomainManifest = read_json(&manifest_file).ok()?;
    (manifest.lifecycle == "READY"
        && manifest
            .canonical_root
            .as_ref()
            .is_some_and(|root| same_path(root, workspace_root)))
    .then_some(manifest_file)
}

#[derive(Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
struct BundleManifest {
    schema_version: u32,
    runtime_version: String,
    protocol_version: String,
    target: String,
    entrypoint: String,
    java_executable: String,
    bundle_digest: String,
    files: BTreeMap<String, String>,
}

struct RuntimeBundle {
    root: PathBuf,
    manifest: BundleManifest,
    java: PathBuf,
    jar: PathBuf,
}

impl RuntimeBundle {
    fn locate_and_verify() -> Result<Self, String> {
        let mut candidates = Vec::new();
        if let Some(value) = non_blank_env("HARNEX_RUNTIME_BUNDLE") {
            candidates.push(PathBuf::from(value));
        }
        let exe = env::current_exe().map_err(display)?;
        if let Some(parent) = exe.parent() {
            candidates.push(parent.join("runtime"));
            candidates.push(parent.join("resources"));
            if let Some(up) = parent.parent() {
                // Portable layout: <bundle>/bin/harnex(.exe).
                candidates.push(up.to_path_buf());
                candidates.push(up.join("resources"));
            }
        }
        let mut ancestor = exe.parent();
        for _ in 0..7 {
            if let Some(dir) = ancestor {
                candidates.push(dir.join("dist").join("runtime-bundles").join(format!(
                    "{}-{}",
                    env!("CARGO_PKG_VERSION"),
                    runtime_target()
                )));
                ancestor = dir.parent();
            }
        }
        for candidate in candidates {
            if candidate.join("runtime-bundle.json").is_file() {
                return Self::verify(candidate);
            }
        }
        Err(
            "verified Runtime Bundle not found (set HARNEX_RUNTIME_BUNDLE for development)"
                .to_string(),
        )
    }

    fn verify(root: PathBuf) -> Result<Self, String> {
        let manifest: BundleManifest = read_json(&root.join("runtime-bundle.json"))?;
        if manifest.schema_version != 1 || manifest.protocol_version != PROTOCOL_VERSION {
            return Err("unsupported Runtime Bundle schema/protocol".to_string());
        }
        if manifest.runtime_version != env!("CARGO_PKG_VERSION")
            || manifest.target != runtime_target()
        {
            return Err(format!(
                "Runtime Bundle version/target mismatch: {} {}",
                manifest.runtime_version, manifest.target
            ));
        }
        if manifest.files.is_empty() {
            return Err("Runtime Bundle file manifest is empty".to_string());
        }
        for (name, expected) in &manifest.files {
            let relative = safe_relative(name)?;
            let file = root.join(relative);
            if fs::symlink_metadata(&file)
                .map_err(display)?
                .file_type()
                .is_symlink()
            {
                return Err(format!(
                    "Runtime Bundle must not contain symbolic links: {name}"
                ));
            }
            let actual = full_hash(&fs::read(file).map_err(display)?);
            if &actual != expected {
                return Err(format!("Runtime Bundle hash mismatch: {name}"));
            }
        }
        let actual_files = bundle_file_names(&root)?;
        let declared_files: Vec<String> = manifest.files.keys().cloned().collect();
        if actual_files != declared_files {
            let extras = actual_files
                .iter()
                .filter(|name| !manifest.files.contains_key(*name))
                .take(5)
                .cloned()
                .collect::<Vec<_>>();
            let missing = declared_files
                .iter()
                .filter(|name| actual_files.binary_search(name).is_err())
                .take(5)
                .cloned()
                .collect::<Vec<_>>();
            return Err(format!(
                "Runtime Bundle file set mismatch (extra: {}; missing: {}). Rebuild or reinstall the Bundle; never use its install directory as a workspace",
                if extras.is_empty() { "none".to_string() } else { extras.join(", ") },
                if missing.is_empty() { "none".to_string() } else { missing.join(", ") }
            ));
        }
        let digest = full_hash(
            serde_json::to_string(&manifest.files)
                .map_err(display)?
                .as_bytes(),
        );
        if digest != manifest.bundle_digest {
            return Err("Runtime Bundle digest mismatch".to_string());
        }
        let java = root.join(safe_relative(&manifest.java_executable)?);
        let jar = root.join(safe_relative(&manifest.entrypoint)?);
        if !java.is_file() || !jar.is_file() {
            return Err("Runtime Bundle entrypoints are missing".to_string());
        }
        Ok(Self {
            root,
            manifest,
            java,
            jar,
        })
    }
}

#[derive(Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct DomainManifest {
    #[serde(default, skip_serializing_if = "Option::is_none")]
    auth_type: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    remote: Option<Value>,
    schema_version: u32,
    runtime_domain_id: String,
    owner_kind: String,
    owner_pid: u32,
    owner_instance_id: String,
    runtime_pid: u32,
    canonical_root: Option<PathBuf>,
    scope_id: String,
    runtime_profile: String,
    instance_id: String,
    endpoint: String,
    lifecycle: String,
    protocol_version: String,
    boot_token_file: PathBuf,
    config_revision: String,
    published_at: String,
}

impl DomainManifest {
    fn verify_scope(&self, scope: &Value) -> Result<(), String> {
        if scope["scopeId"].as_str() != Some(&self.scope_id)
            || scope["instanceId"].as_str() != Some(&self.instance_id)
            || scope["runtimeProfile"].as_str() != Some(&self.runtime_profile)
            || scope["runtimeDomainId"].as_str() != Some(&self.runtime_domain_id)
        {
            return Err("Runtime scope/profile/instance identity mismatch".into());
        }
        if let Some(expected) = &self.canonical_root {
            let actual = scope["projectRoot"]
                .as_str()
                .ok_or("Runtime project root is missing")?;
            let matched = if self.remote.is_some() {
                actual == expected.to_string_lossy()
            } else {
                same_path(&canonical(Path::new(actual))?, &canonical(expected)?)
            };
            if !matched {
                return Err("Runtime project root identity mismatch".into());
            }
        } else if !scope["projectRoot"].is_null() {
            return Err("Personal Runtime declares a project root".into());
        }
        Ok(())
    }
    fn validate(&self, domain_dir: &Path, cwd: &Path) -> Result<(), String> {
        if self.schema_version != 3
            || self.lifecycle != "READY"
            || self.protocol_version != PROTOCOL_VERSION
        {
            return Err("Runtime Domain manifest schema/lifecycle/protocol mismatch".to_string());
        }
        if !valid_domain_id(&self.runtime_domain_id)
            || self.owner_instance_id.is_empty()
            || self.scope_id.is_empty()
            || self.instance_id.is_empty()
            || self.config_revision.is_empty()
            || self.published_at.is_empty()
            || !["web", "tauri", "vscode", "cli", "standalone"].contains(&self.owner_kind.as_str())
        {
            return Err("Runtime Domain manifest identity is incomplete".to_string());
        }
        let published = chrono::DateTime::parse_from_rfc3339(&self.published_at)
            .map_err(|_| "Runtime Domain publication timestamp is invalid".to_string())?;
        if published > chrono::Utc::now() + chrono::Duration::minutes(5) {
            return Err("Runtime Domain publication timestamp is in the future".to_string());
        }
        let endpoint = Url::parse(&self.endpoint).map_err(display)?;
        if endpoint.scheme() != "http"
            || endpoint.port().is_none()
            || !matches!(endpoint.host_str(), Some("127.0.0.1") | Some("::1"))
            || endpoint.path() != "/"
            || endpoint.query().is_some()
            || endpoint.fragment().is_some()
        {
            return Err("Runtime Domain endpoint must be a loopback HTTP origin".to_string());
        }
        let domain = canonical(domain_dir)?;
        let token = canonical(&self.boot_token_file)?;
        if !path_within(&token, &domain) {
            return Err("boot token resolves outside Runtime Domain".to_string());
        }
        if self.runtime_profile == "PERSONAL" {
            if self.canonical_root.is_some() || self.remote.is_some() {
                return Err("personal Runtime must not declare a project".into());
            }
            return Ok(());
        }
        if self.runtime_profile != "PROJECT" {
            return Err("invalid Runtime profile".into());
        }
        if self.remote.is_some() {
            if self.auth_type.as_deref() != Some("bearer-json") {
                return Err("remote manifest authentication type is invalid".into());
            }
            let remote = self
                .remote
                .as_ref()
                .ok_or("remote manifest requires location identity")?;
            if remote["root"].as_str()
                != Some(
                    self.canonical_root
                        .as_ref()
                        .ok_or("project root is missing")?
                        .to_string_lossy()
                        .as_ref(),
                )
                || !self
                    .canonical_root
                    .as_ref()
                    .ok_or("project root is missing")?
                    .to_string_lossy()
                    .starts_with('/')
                || remote["host"].as_str().is_none_or(str::is_empty)
                || remote["runtimePid"].as_u64().is_none_or(|pid| pid == 0)
                || remote["connectionId"].as_str().is_none_or(str::is_empty)
            {
                return Err("remote manifest location identity is invalid".into());
            }
            return Ok(());
        }
        let root = canonical(
            self.canonical_root
                .as_ref()
                .ok_or("project root is missing")?,
        )?;
        let client_root = canonical(cwd)?;
        if !path_within(&client_root, &root) {
            return Err("Client cwd is outside manifest workspace".to_string());
        }
        Ok(())
    }
}

#[derive(Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct OwnerLease {
    schema_version: u32,
    runtime_domain_id: String,
    owner_kind: String,
    owner_pid: u32,
    owner_instance_id: String,
    started_at: String,
}

impl OwnerLease {
    fn validate(&self, expected_domain: &str, expected_kind: &str) -> Result<(), String> {
        if self.schema_version != 1
            || self.runtime_domain_id != expected_domain
            || self.owner_kind != expected_kind
            || self.owner_pid == 0
            || self.owner_instance_id.is_empty()
        {
            return Err("Runtime Domain owner lease identity is invalid".to_string());
        }
        let started = chrono::DateTime::parse_from_rfc3339(&self.started_at)
            .map_err(|_| "Runtime Domain owner lease timestamp is invalid".to_string())?;
        if started > chrono::Utc::now() + chrono::Duration::minutes(5) {
            return Err("Runtime Domain owner lease timestamp is in the future".to_string());
        }
        Ok(())
    }
}

struct DomainPaths {
    user_data_dir: PathBuf,
    dir: PathBuf,
    owner_file: PathBuf,
    manifest_file: PathBuf,
    boot_token_file: PathBuf,
}

impl DomainPaths {
    fn new(id: &str) -> Result<Self, String> {
        if id.is_empty()
            || id.len() > 128
            || !id
                .chars()
                .all(|ch| ch.is_ascii_alphanumeric() || ".-_".contains(ch))
        {
            return Err("invalid Runtime Domain id".to_string());
        }
        let user_data_dir = non_blank_env("HARNEX_USER_DATA_DIR")
            .map(PathBuf::from)
            .unwrap_or_else(|| home_dir().join(".harnex"));
        let dir = user_data_dir.join("runtime-domains").join(id);
        Ok(Self {
            owner_file: dir.join("owner.json"),
            manifest_file: dir.join("domain.json"),
            boot_token_file: dir.join("boot-token"),
            user_data_dir,
            dir,
        })
    }

    fn ensure_private(&self) -> Result<(), String> {
        fs::create_dir_all(&self.dir).map_err(display)?;
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            fs::set_permissions(&self.dir, fs::Permissions::from_mode(0o700)).map_err(display)?;
        }
        #[cfg(windows)]
        if let Some(user) = non_blank_env("USERNAME") {
            let _ = Command::new("icacls.exe")
                .args([
                    self.dir.to_string_lossy().as_ref(),
                    "/inheritance:r",
                    "/grant:r",
                    &format!("{user}:(OI)(CI)F"),
                ])
                .stdout(Stdio::null())
                .stderr(Stdio::null())
                .status();
        }
        Ok(())
    }
}

fn checked_json(response: Response, manifest: &DomainManifest) -> Result<Value, String> {
    let response = check_response_identity(response, manifest)?;
    let status = response.status();
    let text = response.text().map_err(display)?;
    if !status.is_success() {
        return Err(format!("Runtime HTTP {status}: {text}"));
    }
    serde_json::from_str(&text).map_err(display)
}

fn check_response_identity(
    response: Response,
    manifest: &DomainManifest,
) -> Result<Response, String> {
    let workspace = response
        .headers()
        .get("X-Harnex-Scope-Id")
        .and_then(|v| v.to_str().ok());
    let instance = response
        .headers()
        .get("X-Harnex-Instance-Id")
        .and_then(|v| v.to_str().ok());
    if workspace != Some(manifest.scope_id.as_str())
        || instance != Some(manifest.instance_id.as_str())
    {
        return Err("Runtime identity changed while attached".to_string());
    }
    Ok(response)
}

fn identity_headers(manifest: &DomainManifest) -> Result<HeaderMap, String> {
    let mut headers = HeaderMap::new();
    headers.insert(
        "X-Harnex-Scope-Id",
        HeaderValue::from_str(&manifest.scope_id).map_err(display)?,
    );
    headers.insert(
        "X-Harnex-Instance-Id",
        HeaderValue::from_str(&manifest.instance_id).map_err(display)?,
    );
    headers.insert(CONTENT_TYPE, HeaderValue::from_static("application/json"));
    Ok(headers)
}

fn stop_standalone() -> Result<(), String> {
    let paths = DomainPaths::new("standalone-web")?;
    if !paths.owner_file.exists() {
        println!("not running");
        return Ok(());
    }
    let owner: OwnerLease = read_json(&paths.owner_file)?;
    owner.validate("standalone-web", "standalone")?;
    if pid_alive(owner.owner_pid) {
        kill_pid(owner.owner_pid)?;
        let deadline = Instant::now() + Duration::from_secs(25);
        while pid_alive(owner.owner_pid) && Instant::now() < deadline {
            thread::sleep(Duration::from_millis(100));
        }
        if pid_alive(owner.owner_pid) {
            return Err(format!(
                "Runtime owner pid {} did not exit; ownership was retained",
                owner.owner_pid
            ));
        }
        cleanup_exact_owner(&paths, &owner);
    } else if stale_lease_old_enough(&paths.owner_file) {
        cleanup_exact_owner(&paths, &owner);
    } else {
        return Err("Runtime owner lease is still initializing; retry".to_string());
    }
    println!("stopped Runtime Domain standalone-web");
    Ok(())
}

fn kill_pid(pid: u32) -> Result<(), String> {
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        let status = Command::new("taskkill")
            .args(["/PID", &pid.to_string(), "/T", "/F"])
            .creation_flags(0x08000000)
            .stdout(Stdio::null())
            .stderr(Stdio::null())
            .status()
            .map_err(display)?;
        if !status.success() && pid_alive(pid) {
            return Err(format!("taskkill failed for pid {pid}: {status}"));
        }
    }
    #[cfg(unix)]
    {
        let status = Command::new("kill")
            .args(["-TERM", &pid.to_string()])
            .status()
            .map_err(display)?;
        if !status.success() && pid_alive(pid) {
            return Err(format!("kill failed for pid {pid}: {status}"));
        }
    }
    Ok(())
}

fn pid_alive(pid: u32) -> bool {
    if pid == 0 {
        return false;
    }
    #[cfg(windows)]
    {
        Command::new("tasklist")
            .args(["/FI", &format!("PID eq {pid}"), "/FO", "CSV", "/NH"])
            .output()
            .map(|output| String::from_utf8_lossy(&output.stdout).contains(&format!("\"{pid}\"")))
            .unwrap_or(false)
    }
    #[cfg(unix)]
    {
        Command::new("kill")
            .args(["-0", &pid.to_string()])
            .status()
            .map(|s| s.success())
            .unwrap_or(false)
    }
}

fn cleanup_stale(paths: &DomainPaths) {
    if let Ok(owner) = read_json::<OwnerLease>(&paths.owner_file) {
        if pid_alive(owner.owner_pid) {
            return;
        }
    }
    // A freshly-created lease can be observed before its owner process is queryable (and a
    // concurrent writer may still be publishing it). Match the Node/VS Code/Tauri owners:
    // only reclaim a dead or malformed lease after the small crash-recovery TTL.
    let lease_old_enough = stale_lease_old_enough(&paths.owner_file);
    if paths.owner_file.exists() && !lease_old_enough {
        return;
    }
    for file in [
        &paths.owner_file,
        &paths.manifest_file,
        &paths.boot_token_file,
    ] {
        let _ = fs::remove_file(file);
    }
}

fn cleanup_exact_owner(paths: &DomainPaths, expected: &OwnerLease) {
    let current = read_json::<OwnerLease>(&paths.owner_file).ok();
    if current
        .as_ref()
        .map(|owner| owner.owner_instance_id.as_str())
        != Some(expected.owner_instance_id.as_str())
    {
        return;
    }
    for file in [
        &paths.owner_file,
        &paths.manifest_file,
        &paths.boot_token_file,
    ] {
        let _ = fs::remove_file(file);
    }
}

fn stale_lease_old_enough(owner_file: &Path) -> bool {
    fs::metadata(owner_file)
        .ok()
        .and_then(|metadata| metadata.modified().ok())
        .and_then(|modified| modified.elapsed().ok())
        .map(|age| age >= Duration::from_secs(2))
        .unwrap_or(false)
}

fn inject_user_config(command: &mut Command, user_data: &Path) -> Result<(), String> {
    let mut locations = Vec::new();
    let mut files = Vec::new();
    for name in ["agent-config.yml", "application.yml"] {
        let file = user_data.join(name);
        if file.is_file() {
            #[cfg(target_os = "macos")]
            require_resolvable_keychain_references(&file)?;
            let url = Url::from_file_path(&file)
                .map_err(|_| format!("cannot encode config path {}", file.display()))?;
            locations.push(url.to_string().replace(',', "%2C").replace(';', "%3B"));
            files.push(file.to_string_lossy().to_string());
        }
    }
    if !locations.is_empty() {
        command.env("SPRING_CONFIG_ADDITIONAL_LOCATION", locations.join(","));
        command.env("HARNEX_CONFIG_FILES", files.join("\n"));
    }
    Ok(())
}

#[cfg(target_os = "macos")]
fn require_resolvable_keychain_references(file: &Path) -> Result<(), String> {
    let body = fs::read_to_string(file).map_err(display)?;
    let mut missing = Vec::new();
    for line in body
        .lines()
        .filter(|line| !line.trim_start().starts_with('#'))
    {
        let mut remaining = line;
        while let Some(offset) = remaining.find("${HARNEX_KEYCHAIN_PROVIDER_") {
            let candidate = &remaining[offset + 2..];
            let name = candidate
                .chars()
                .take_while(|ch| ch.is_ascii_uppercase() || ch.is_ascii_digit() || *ch == '_')
                .collect::<String>();
            if !name.is_empty() && env::var_os(&name).is_none() && !missing.contains(&name) {
                missing.push(name.clone());
            }
            remaining = &candidate[name.len()..];
        }
    }
    if missing.is_empty() {
        return Ok(());
    }
    Err(format!(
        "{} contains desktop-only macOS Keychain Provider references ({}). Open the workspace through Harnex.app/attach to its Runtime, or provide explicit credentials for this CLI process",
        file.display(),
        missing.join(", ")
    ))
}

fn config_revision(user_data: &Path) -> Result<String, String> {
    let mut hash = Sha256::new();
    for name in ["agent-config.yml", "application.yml"] {
        let file = user_data.join(name);
        if file.is_file() {
            hash.update(name.as_bytes());
            hash.update(fs::read(file).map_err(display)?);
        }
    }
    Ok(format!("{:x}", hash.finalize()))
}

fn command_workspace(args: &[String]) -> Result<PathBuf, String> {
    let requested = option(args, "--workspace")
        .map(PathBuf::from)
        .or_else(|| non_blank_env("HARNEX_WORKSPACE").map(PathBuf::from))
        .unwrap_or(env::current_dir().map_err(display)?);
    let workspace = canonical(&requested)?;
    if !workspace.is_dir() {
        return Err(format!(
            "workspace must be an existing directory: {}",
            workspace.display()
        ));
    }
    let executable = canonical(&env::current_exe().map_err(display)?)?;
    if let Some(bundle_root) = containing_bundle_root(&executable) {
        if path_within(&workspace, &bundle_root) {
            return Err(format!(
                "refusing to use the Runtime Bundle as a workspace: {}. Change to a project directory or pass --workspace <path>",
                workspace.display()
            ));
        }
    }
    Ok(workspace)
}

fn containing_bundle_root(executable: &Path) -> Option<PathBuf> {
    executable
        .ancestors()
        .take(5)
        .find(|directory| directory.join("runtime-bundle.json").is_file())
        .and_then(|directory| canonical(directory).ok())
}

fn option(args: &[String], name: &str) -> Option<String> {
    for (index, arg) in args.iter().enumerate() {
        if arg == name {
            return args.get(index + 1).cloned();
        }
        if let Some(value) = arg.strip_prefix(&format!("{name}=")) {
            return Some(value.to_string());
        }
    }
    None
}

fn safe_relative(value: &str) -> Result<PathBuf, String> {
    let path = PathBuf::from(value.replace('/', std::path::MAIN_SEPARATOR_STR));
    if path.is_absolute()
        || path
            .components()
            .any(|part| !matches!(part, Component::Normal(_)))
    {
        return Err(format!("unsafe relative path: {value}"));
    }
    Ok(path)
}

fn free_port() -> Result<u16, String> {
    TcpListener::bind(("127.0.0.1", 0))
        .and_then(|listener| listener.local_addr())
        .map(|a| a.port())
        .map_err(display)
}

fn open_target(target: &str) -> Result<(), String> {
    #[cfg(windows)]
    Command::new("cmd.exe")
        .args(["/d", "/s", "/c", "start", "", target])
        .spawn()
        .map_err(display)?;
    #[cfg(target_os = "macos")]
    Command::new("open").arg(target).spawn().map_err(display)?;
    #[cfg(all(unix, not(target_os = "macos")))]
    Command::new("xdg-open")
        .arg(target)
        .spawn()
        .map_err(display)?;
    Ok(())
}

fn home_dir() -> PathBuf {
    non_blank_env(if cfg!(windows) { "USERPROFILE" } else { "HOME" })
        .map(PathBuf::from)
        .unwrap_or_else(env::temp_dir)
}

fn non_blank_env(name: &str) -> Option<String> {
    env::var(name).ok().filter(|value| !value.trim().is_empty())
}
fn runtime_target() -> &'static str {
    if cfg!(all(target_os = "windows", target_arch = "x86_64")) {
        "win32-x64"
    } else if cfg!(all(target_os = "windows", target_arch = "aarch64")) {
        "win32-arm64"
    } else if cfg!(all(target_os = "linux", target_arch = "x86_64")) {
        "linux-x64"
    } else if cfg!(all(target_os = "linux", target_arch = "aarch64")) {
        "linux-arm64"
    } else if cfg!(all(target_os = "macos", target_arch = "x86_64")) {
        "darwin-x64"
    } else if cfg!(all(target_os = "macos", target_arch = "aarch64")) {
        "darwin-arm64"
    } else {
        "unsupported"
    }
}
fn url_component(value: &str) -> String {
    url::form_urlencoded::byte_serialize(value.as_bytes()).collect()
}
fn valid_run_id(value: &str) -> bool {
    !value.is_empty()
        && value.len() <= 160
        && value
            .chars()
            .all(|ch| ch.is_ascii_alphanumeric() || ".:_-".contains(ch))
}
fn valid_domain_id(value: &str) -> bool {
    !value.is_empty()
        && value.len() <= 128
        && value
            .chars()
            .all(|ch| ch.is_ascii_alphanumeric() || ".-_".contains(ch))
}
fn full_hash(bytes: &[u8]) -> String {
    format!("{:x}", Sha256::digest(bytes))
}
fn short_hash(bytes: &[u8]) -> String {
    full_hash(bytes)[..12].to_string()
}
fn now_string() -> String {
    chrono::Utc::now().to_rfc3339_opts(chrono::SecondsFormat::Millis, true)
}
fn same_path(a: &Path, b: &Path) -> bool {
    let a = canonical(a).unwrap_or_else(|_| a.to_path_buf());
    let b = canonical(b).unwrap_or_else(|_| b.to_path_buf());
    if cfg!(windows) {
        a.to_string_lossy()
            .eq_ignore_ascii_case(&b.to_string_lossy())
    } else {
        a == b
    }
}
fn path_within(candidate: &Path, root: &Path) -> bool {
    if cfg!(windows) {
        let candidate = candidate.to_string_lossy().to_lowercase();
        let root = root
            .to_string_lossy()
            .trim_end_matches(['\\', '/'])
            .to_lowercase();
        candidate == root
            || candidate
                .strip_prefix(&root)
                .is_some_and(|rest| rest.starts_with(['\\', '/']))
    } else {
        candidate.starts_with(root)
    }
}
fn canonical(path: &Path) -> Result<PathBuf, String> {
    let value = fs::canonicalize(path).map_err(display)?;
    #[cfg(windows)]
    {
        let text = value.to_string_lossy();
        if let Some(rest) = text.strip_prefix(r"\\?\UNC\") {
            return Ok(PathBuf::from(format!(r"\\{rest}")));
        }
        if let Some(rest) = text.strip_prefix(r"\\?\") {
            return Ok(PathBuf::from(rest));
        }
    }
    Ok(value)
}
fn absolute(path: PathBuf) -> Result<PathBuf, String> {
    if path.is_absolute() {
        Ok(path)
    } else {
        Ok(env::current_dir().map_err(display)?.join(path))
    }
}
fn display(error: impl std::fmt::Display) -> String {
    error.to_string()
}
fn read_json<T: for<'de> Deserialize<'de>>(file: &Path) -> Result<T, String> {
    serde_json::from_slice(&fs::read(file).map_err(display)?).map_err(display)
}
fn write_json<T: Serialize>(file: &Path, value: &T) -> Result<(), String> {
    let tmp = file.with_extension(format!(
        "{}.{}.tmp",
        file.extension().and_then(|v| v.to_str()).unwrap_or("json"),
        Uuid::new_v4()
    ));
    fs::write(&tmp, serde_json::to_vec_pretty(value).map_err(display)?).map_err(display)?;
    let result = atomic_replace(&tmp, file);
    if result.is_err() {
        let _ = fs::remove_file(&tmp);
    }
    result
}

fn bundle_file_names(root: &Path) -> Result<Vec<String>, String> {
    fn visit(
        root: &Path,
        directory: &Path,
        output: &mut Vec<String>,
        collect_runtime_files: bool,
    ) -> Result<(), String> {
        for entry in fs::read_dir(directory).map_err(display)? {
            let entry = entry.map_err(display)?;
            let path = entry.path();
            let file_type = entry.file_type().map_err(display)?;
            if file_type.is_symlink() {
                return Err(format!(
                    "Runtime Bundle must not contain symbolic links: {}",
                    path.display()
                ));
            }
            if file_type.is_dir() {
                // Desktop installations colocate the independently built SPA
                // at `<bundle>/ui`. It is outside runtime-bundle.json, but we
                // still walk it so symlinks/unsupported entries fail closed.
                let collect_child =
                    collect_runtime_files && !(directory == root && entry.file_name() == "ui");
                visit(root, &path, output, collect_child)?;
            } else if file_type.is_file() {
                if collect_runtime_files {
                    let name = path
                        .strip_prefix(root)
                        .map_err(display)?
                        .to_string_lossy()
                        .replace('\\', "/");
                    if name != "runtime-bundle.json" && name != "SHA256SUMS" {
                        output.push(name);
                    }
                }
            } else {
                return Err(format!(
                    "unsupported Runtime Bundle entry: {}",
                    path.display()
                ));
            }
        }
        Ok(())
    }
    let mut output = Vec::new();
    visit(root, root, &mut output, true)?;
    output.sort();
    Ok(output)
}

#[cfg(windows)]
fn atomic_replace(source: &Path, target: &Path) -> Result<(), String> {
    use std::os::windows::ffi::OsStrExt;
    use windows_sys::Win32::Storage::FileSystem::{
        MoveFileExW, MOVEFILE_REPLACE_EXISTING, MOVEFILE_WRITE_THROUGH,
    };
    let from: Vec<u16> = source.as_os_str().encode_wide().chain(Some(0)).collect();
    let to: Vec<u16> = target.as_os_str().encode_wide().chain(Some(0)).collect();
    let success = unsafe {
        MoveFileExW(
            from.as_ptr(),
            to.as_ptr(),
            MOVEFILE_REPLACE_EXISTING | MOVEFILE_WRITE_THROUGH,
        )
    };
    if success != 0 {
        Ok(())
    } else {
        Err(std::io::Error::last_os_error().to_string())
    }
}

#[cfg(not(windows))]
fn atomic_replace(source: &Path, target: &Path) -> Result<(), String> {
    fs::rename(source, target).map_err(display)
}
fn create_json<T: Serialize>(file: &Path, value: &T) -> std::io::Result<()> {
    if let Some(parent) = file.parent() {
        fs::create_dir_all(parent)?;
    }
    let mut handle = OpenOptions::new().write(true).create_new(true).open(file)?;
    handle.write_all(&serde_json::to_vec_pretty(value).map_err(std::io::Error::other)?)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn serve_port_preserves_cli_and_environment_precedence() {
        assert_eq!(serve_port(&[], None, None).unwrap(), 18081);
        assert_eq!(
            serve_port(&[], Some("19001".into()), Some("19002".into())).unwrap(),
            19001
        );
        assert_eq!(serve_port(&[], None, Some("19002".into())).unwrap(), 19002);
        assert_eq!(
            serve_port(&["--server.port=19003".into()], Some("19001".into()), None).unwrap(),
            19003
        );
        assert_eq!(
            serve_port(&["--port".into(), "19004".into()], None, None).unwrap(),
            19004
        );
        assert!(serve_port(&["--server.port=invalid".into()], None, None).is_err());
    }

    #[test]
    fn project_data_defaults_to_project_disk_and_resolves_relative_overrides() {
        let root = env::temp_dir().join("project");
        assert_eq!(
            project_data_directory(&root, None, None),
            root.join(".harnex/data")
        );
        assert_eq!(
            project_data_directory(&root, Some("custom".into()), Some("recorded".into())),
            root.join("custom")
        );
        assert_eq!(
            project_data_directory(&root, None, Some("recorded".into())),
            root.join("recorded")
        );
        let external = env::temp_dir().join("external");
        assert_eq!(
            project_data_directory(&root, Some(external.clone()), None),
            external
        );
    }

    #[test]
    fn conversation_requests_never_bypass_an_attached_project_identity() {
        let args = vec!["--conversation".to_string()];
        assert!(require_conversation_target("PERSONAL", &args).is_ok());
        assert!(require_conversation_target("PROJECT", &args).is_err());
        assert!(require_conversation_target("PROJECT", &[]).is_ok());
        assert_eq!(
            WorkMode::Agent.other().index(),
            WorkMode::Conversation.index()
        );
    }

    #[test]
    #[ignore = "requires HARNEX_RUNTIME_BUNDLE and isolated HARNEX_TEST_SPACE_ROOT"]
    fn terminal_spaces_keep_personal_and_project_owners_separate() {
        let root =
            PathBuf::from(env::var_os("HARNEX_TEST_SPACE_ROOT").expect("isolated fixture root"));
        let user = PathBuf::from(env::var_os("HARNEX_USER_DATA_DIR").expect("isolated user root"));
        assert_eq!(user, root.join("user"));
        let project = root.join("project");
        fs::create_dir_all(&project).unwrap();
        let args = vec!["--workspace".into(), project.to_string_lossy().into_owned()];
        let mut runtimes = TuiRuntimes::default();
        let (personal, owned) = runtimes.session(WorkMode::Conversation, &args).unwrap();
        assert!(owned);
        assert_eq!(personal.manifest.runtime_profile, "PERSONAL");
        let (agent, owned) = runtimes.session(WorkMode::Agent, &args).unwrap();
        assert!(owned);
        assert_eq!(agent.manifest.runtime_profile, "PROJECT");
        let project_data = agent.get_json("/api/v1/diagnostics").unwrap()["storage"]["dataDir"]
            .as_str()
            .unwrap()
            .to_string();
        assert_eq!(
            canonical(Path::new(&project_data)).unwrap(),
            canonical(&project.join(".harnex/data")).unwrap()
        );
        assert!(!user.join("workspaces").exists());
        assert_eq!(
            runtimes
                .session(WorkMode::Conversation, &args)
                .unwrap()
                .0
                .manifest
                .instance_id,
            personal.manifest.instance_id
        );
        assert_eq!(
            runtimes
                .session(WorkMode::Agent, &args)
                .unwrap()
                .0
                .manifest
                .instance_id,
            agent.manifest.instance_id
        );
        let mut borrowing = TuiRuntimes::default();
        assert!(!borrowing.session(WorkMode::Conversation, &args).unwrap().1);
        borrowing.shutdown().unwrap();
        assert_eq!(
            personal.get_json("/api/v1/scope").unwrap()["instanceId"],
            personal.manifest.instance_id
        );
        let bad_args = vec![
            "--workspace".into(),
            root.join("absent").to_string_lossy().into_owned(),
        ];
        assert!(runtimes.session(WorkMode::Agent, &bad_args).is_err());
        assert_eq!(
            runtimes
                .session(WorkMode::Conversation, &args)
                .unwrap()
                .0
                .manifest
                .instance_id,
            personal.manifest.instance_id
        );
        let mut retrying = TuiRuntimes::default();
        assert!(!retrying.session(WorkMode::Conversation, &args).unwrap().1);
        drop(runtimes.personal.take());
        assert!(retrying.session(WorkMode::Conversation, &args).is_err());
        assert!(
            retrying.personal.is_none(),
            "a stale borrowed owner must not permanently block reconnect"
        );
        let (reopened, owned) = retrying.session(WorkMode::Conversation, &args).unwrap();
        assert!(owned);
        assert_ne!(reopened.manifest.instance_id, personal.manifest.instance_id);
        retrying.shutdown().unwrap();
        runtimes.shutdown().unwrap();
    }

    #[cfg(target_os = "macos")]
    #[test]
    fn path_management_refuses_unowned_entries() {
        use std::os::unix::fs::symlink;
        let base = env::temp_dir().join(format!("harnex-path-test-{}", Uuid::new_v4()));
        fs::create_dir_all(&base).unwrap();
        let current = base.join("current-harnex");
        let other = base.join("other-harnex");
        let link = base.join("harnex");
        fs::write(&current, "current").unwrap();
        fs::write(&other, "other").unwrap();

        fs::write(&link, "user file").unwrap();
        assert!(managed_cli_link(&link, &current).is_err());
        fs::remove_file(&link).unwrap();
        symlink(&other, &link).unwrap();
        assert!(managed_cli_link(&link, &current).is_err());
        fs::remove_file(&link).unwrap();
        symlink(&current, &link).unwrap();
        assert!(managed_cli_link(&link, &current).unwrap());
        let alias = base.join("current-alias");
        symlink(&current, &alias).unwrap();
        assert!(managed_cli_link(&link, &alias).unwrap());
        fs::remove_file(&link).unwrap();
        symlink("current-harnex", &link).unwrap();
        assert!(managed_cli_link(&link, &alias).unwrap());
        fs::remove_file(&link).unwrap();
        symlink(base.join("missing"), &link).unwrap();
        assert!(managed_cli_link(&link, &current).is_err());
        fs::remove_dir_all(base).unwrap();
    }

    #[cfg(target_os = "macos")]
    #[test]
    fn standalone_runtime_rejects_unresolved_desktop_keychain_reference() {
        let file = env::temp_dir().join(format!("harnex-keychain-test-{}.yml", Uuid::new_v4()));
        fs::write(
            &file,
            "api-key: ${HARNEX_KEYCHAIN_PROVIDER_TEST_MISSING:}\n",
        )
        .unwrap();
        let error = require_resolvable_keychain_references(&file).unwrap_err();
        assert!(error.contains("desktop-only macOS Keychain Provider references"));
        fs::remove_file(file).unwrap();
    }

    #[test]
    fn domain_manifest_is_a_global_option_in_any_position() {
        let (args, manifest) = global_options(vec![
            "cli".into(),
            "-p".into(),
            "hello".into(),
            "--domain-manifest=domain.json".into(),
        ])
        .unwrap();
        assert_eq!(args, ["cli", "-p", "hello"]);
        assert!(manifest.unwrap().ends_with("domain.json"));
    }

    #[test]
    fn bundle_paths_reject_absolute_and_parent_traversal() {
        assert!(safe_relative("jre/bin/java").is_ok());
        assert!(safe_relative("../boot-token").is_err());
        assert!(safe_relative(if cfg!(windows) {
            r"C:\\Windows"
        } else {
            "/etc"
        })
        .is_err());
    }

    #[test]
    fn option_supports_split_and_equals_forms() {
        assert_eq!(
            option(&["--run=run-1".into()], "--run"),
            Some("run-1".into())
        );
        assert_eq!(
            option(&["--run".into(), "run-2".into()], "--run"),
            Some("run-2".into())
        );
    }

    #[test]
    fn fresh_owner_lease_is_not_reclaimable() {
        let file = std::env::temp_dir().join(format!("harnex-owner-{}.json", Uuid::new_v4()));
        fs::write(&file, b"{}").unwrap();
        assert!(!stale_lease_old_enough(&file));
        let _ = fs::remove_file(file);
    }

    #[test]
    fn workspace_guard_finds_runtime_bundle_ancestor() {
        let root = std::env::temp_dir().join(format!("harnex-bundle-{}", Uuid::new_v4()));
        let bin = root.join("bin");
        fs::create_dir_all(&bin).unwrap();
        fs::write(root.join("runtime-bundle.json"), b"{}").unwrap();
        let executable = bin.join(if cfg!(windows) {
            "harnex.exe"
        } else {
            "harnex"
        });
        fs::write(&executable, b"binary").unwrap();
        let detected = containing_bundle_root(&executable).expect("bundle ancestor");
        assert!(same_path(&detected, &root));
        let _ = fs::remove_dir_all(root);
    }

    #[test]
    fn installed_bundle_ignores_ui_but_rejects_other_extra_files() {
        let root = std::env::temp_dir().join(format!("harnex-installed-bundle-{}", Uuid::new_v4()));
        fs::create_dir_all(root.join("jre/bin")).unwrap();
        fs::create_dir_all(root.join("ui/assets")).unwrap();
        fs::write(root.join("harnex.jar"), b"jar").unwrap();
        fs::write(root.join("jre/bin/java"), b"java").unwrap();
        fs::write(root.join("ui/index.html"), b"ui").unwrap();
        fs::write(root.join("ui/assets/app.js"), b"js").unwrap();
        fs::write(root.join("unexpected.dll"), b"extra").unwrap();
        fs::write(root.join("runtime-bundle.json"), b"{}").unwrap();
        fs::write(root.join("SHA256SUMS"), b"").unwrap();

        assert_eq!(
            bundle_file_names(&root).unwrap(),
            ["harnex.jar", "jre/bin/java", "unexpected.dll"]
        );
        let _ = fs::remove_dir_all(root);
    }

    #[test]
    fn runtime_lock_exit_points_to_the_desktop_attach_command() {
        let base = std::env::temp_dir().join(format!("harnex-runtime-lock-{}", Uuid::new_v4()));
        let workspace = base.join("workspace");
        let logs = base.join("cli-logs");
        let user_data = base.join("user");
        let manifest_file = user_data
            .join("runtime-domains")
            .join("tauri-main")
            .join("domain.json");
        fs::create_dir_all(&workspace).unwrap();
        fs::create_dir_all(&logs).unwrap();
        fs::create_dir_all(manifest_file.parent().unwrap()).unwrap();
        fs::write(
            logs.join("runtime-err.log"),
            "dataDir is already locked by another Harnex JVM — refusing to start",
        )
        .unwrap();
        let manifest = DomainManifest {
            schema_version: 3,
            runtime_profile: "PROJECT".into(),
            remote: None,
            auth_type: None,
            runtime_domain_id: "tauri-main".to_string(),
            owner_kind: "tauri".to_string(),
            owner_pid: 1,
            owner_instance_id: "owner".to_string(),
            runtime_pid: 2,
            canonical_root: Some(canonical(&workspace).unwrap()),
            scope_id: "workspace".to_string(),
            instance_id: "instance".to_string(),
            endpoint: "http://127.0.0.1:8088".to_string(),
            lifecycle: "READY".to_string(),
            protocol_version: PROTOCOL_VERSION.to_string(),
            boot_token_file: base.join("boot-token"),
            config_revision: "config".to_string(),
            published_at: now_string(),
        };
        fs::write(&manifest_file, serde_json::to_vec(&manifest).unwrap()).unwrap();

        let message = runtime_exit_before_ready_message(
            "exit code: 1".to_string(),
            Some(&logs),
            &workspace,
            &user_data,
        );
        assert!(message.contains("already open in another Harnex Runtime"));
        assert!(message.contains("--domain-manifest"));
        assert!(message.contains("tauri-main"));
        let _ = fs::remove_dir_all(base);
    }
}

#[cfg(test)]
mod agent_run_protocol_tests {
    use super::*;

    #[test]
    fn quiescent_goal_closes_only_the_one_shot_subscription() {
        for status in [
            "BLOCKED",
            "PAUSED",
            "BUDGET_LIMITED",
            "READY_FOR_REVIEW",
            "INTERRUPTED",
        ] {
            let frame = json!({"type":"goal_state_changed", "seq":4, "streamEpoch":2, "data":{"status":status}}).to_string();
            let mut seq = 0;
            let mut epoch = None;
            assert!(apply_sse_data(&[frame], false, &mut seq, &mut epoch, &mut false).unwrap());
            assert_eq!(seq, 4);
            assert_eq!(epoch, Some(2));
        }
        let mut seq = 0;
        let mut epoch = None;
        let frame =
            json!({"type":"goal_state_changed", "data":{"status":"WAITING_RETRY"}}).to_string();
        assert!(!apply_sse_data(&[frame], false, &mut seq, &mut epoch, &mut false).unwrap());
    }
    #[test]
    fn buffer_loss_and_epoch_changes_require_a_snapshot_without_advancing_the_cursor() {
        for frame in [
            json!({"type":"notice","seq":90,"streamEpoch":2,"data":{"kind":"buffer_miss"}}),
            json!({"type":"text_delta","seq":90,"streamEpoch":3,"data":{"text":"new epoch"}}),
        ] {
            let mut seq = 50;
            let mut epoch = Some(2);
            let mut reset = false;
            assert!(apply_sse_data(
                &[frame.to_string()],
                false,
                &mut seq,
                &mut epoch,
                &mut reset
            )
            .unwrap());
            assert!(reset);
            assert_eq!(seq, 50);
            assert_eq!(epoch, Some(2));
        }
        assert_eq!(
            snapshot_text(
                &json!({"live":{"text":""},"history":{"answer":{"contentParts":[{"type":"TEXT","text":"complete answer"}]}}})
            ),
            "complete answer"
        );
    }
}
