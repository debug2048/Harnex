//! Window, tray, file reveal and signed update integration.

use crate::host::{env_or, shell_log, shell_log_dir, strip_verbatim};
use crate::state::{CurrentRemote, CurrentWorkspace, HostClosing};
use serde_json::json;
use serde_json::Value;
use std::path::Path;
use std::path::PathBuf;
use std::process::Command;
use tauri::menu::Menu;
use tauri::menu::MenuItem;
use tauri::tray::MouseButton;
use tauri::tray::MouseButtonState;
use tauri::tray::TrayIconBuilder;
use tauri::tray::TrayIconEvent;
use tauri::AppHandle;
use tauri::Manager;
use tauri::Theme;
use tauri_plugin_dialog::DialogExt;
use tauri_plugin_dialog::MessageDialogButtons;
use tauri_plugin_updater::UpdaterExt;

pub(crate) fn show_main_window(app: &AppHandle) {
    if let Some(win) = app.get_webview_window("main") {
        let _ = win.unminimize();
        let _ = win.show();
        let _ = win.set_focus();
    }
}

/// Lifecycle workers never wait for the UI thread while holding WorkspaceTransition.
pub(crate) fn update_workspace_window(app: &AppHandle, title: String, reveal: bool) {
    let handle = app.clone();
    let _ = app.run_on_main_thread(move || {
        if handle
            .state::<HostClosing>()
            .0
            .load(std::sync::atomic::Ordering::Acquire)
        {
            return;
        }
        if let Some(window) = handle.get_webview_window("main") {
            let _ = window.set_title(&title);
            if reveal {
                let _ = window.show();
                let _ = window.set_focus();
            }
        }
    });
}

pub(crate) fn hide_main_window(app: &AppHandle) {
    if let Some(win) = app.get_webview_window("main") {
        let _ = win.hide();
    }
}

/// Create the desktop lifecycle surface. Left-click and the global shortcut reveal the
/// existing single window; closing the window only hides it, while "Quit" performs the
/// strict Runtime drain/termination in the RunEvent::Exit handler.
pub(crate) fn build_tray(app: &AppHandle) -> tauri::Result<()> {
    let show = MenuItem::with_id(
        app,
        "desktop:show",
        "显示 Harnex / Show Harnex",
        true,
        None::<&str>,
    )?;
    let hide = MenuItem::with_id(
        app,
        "desktop:hide",
        "隐藏到托盘 / Hide to tray",
        true,
        None::<&str>,
    )?;
    let quit = MenuItem::with_id(
        app,
        "desktop:quit",
        "退出 Harnex / Quit Harnex",
        true,
        None::<&str>,
    )?;
    let update = MenuItem::with_id(
        app,
        "desktop:update",
        "检查更新 / Check for Updates…",
        update_configuration().is_some(),
        None::<&str>,
    )?;
    let menu = Menu::with_items(app, &[&show, &hide, &update, &quit])?;

    TrayIconBuilder::with_id("harnex")
        .icon(
            app.default_window_icon()
                .cloned()
                .ok_or_else(|| tauri::Error::AssetNotFound("default window icon".to_string()))?,
        )
        .icon_as_template(cfg!(target_os = "macos"))
        .tooltip("Harnex")
        .menu(&menu)
        .show_menu_on_left_click(false)
        .on_tray_icon_event(|tray, event| {
            if matches!(
                event,
                TrayIconEvent::Click {
                    button: MouseButton::Left,
                    button_state: MouseButtonState::Up,
                    ..
                }
            ) {
                show_main_window(tray.app_handle());
            }
        })
        .build(app)?;
    // REMOVED app.manage(DesktopMenu { autostart }); - plugin disabled
    Ok(())
}

fn select_update_configuration(
    compiled_endpoint: Option<&str>,
    compiled_pubkey: Option<&str>,
    runtime_endpoint: Option<String>,
    runtime_pubkey: Option<String>,
    allow_runtime_override: bool,
) -> Option<(String, String)> {
    let endpoint = if allow_runtime_override {
        runtime_endpoint.or_else(|| compiled_endpoint.map(ToOwned::to_owned))?
    } else {
        compiled_endpoint.map(ToOwned::to_owned)?
    };
    let pubkey = if allow_runtime_override {
        runtime_pubkey.or_else(|| compiled_pubkey.map(ToOwned::to_owned))?
    } else {
        compiled_pubkey.map(ToOwned::to_owned)?
    };
    if !endpoint.starts_with("https://") || pubkey.trim().is_empty() {
        return None;
    }
    Some((endpoint, pubkey))
}

pub(crate) fn update_configuration() -> Option<(String, String)> {
    // A release binary's updater public key is a compiled trust root. Runtime environment
    // variables (including a per-user launchd environment) must never replace it. Debug builds
    // retain env overrides so local updater-contract testing remains possible.
    #[cfg(debug_assertions)]
    let runtime = (
        env_or("HARNEX_UPDATE_ENDPOINT"),
        env_or("HARNEX_UPDATE_PUBKEY"),
    );
    #[cfg(not(debug_assertions))]
    let runtime = (None, None);
    select_update_configuration(
        option_env!("HARNEX_UPDATE_ENDPOINT"),
        option_env!("HARNEX_UPDATE_PUBKEY"),
        runtime.0,
        runtime.1,
        cfg!(debug_assertions),
    )
}

pub(crate) async fn check_for_updates(app: AppHandle, interactive: bool) {
    let Some((endpoint, pubkey)) = update_configuration() else {
        if interactive {
            app.dialog()
                .message("This build has no signed update channel configured.")
                .title("Harnex")
                .show(|_| {});
        }
        return;
    };
    let result = async {
        let url = url::Url::parse(&endpoint).map_err(|error| error.to_string())?;
        let updater = app
            .updater_builder()
            .endpoints(vec![url])
            .map_err(|error| error.to_string())?
            .pubkey(pubkey)
            .build()
            .map_err(|error| error.to_string())?;
        updater.check().await.map_err(|error| error.to_string())
    }
    .await;
    match result {
        Ok(Some(update)) => {
            let version = update.version.clone();
            let update_app = app.clone();
            app.dialog()
                .message(format!(
                    "Harnex {version} is available. Download, verify and install it now?"
                ))
                .title("Harnex Update")
                .buttons(MessageDialogButtons::OkCancelCustom(
                    "Install".to_string(),
                    "Later".to_string(),
                ))
                .show(move |install| {
                    if !install {
                        return;
                    }
                    tauri::async_runtime::spawn(async move {
                        match update.download_and_install(|_, _| {}, || {}).await {
                            Ok(()) => update_app.restart(),
                            Err(error) => {
                                shell_log(
                                    &shell_log_dir(),
                                    &format!("[updater] install failed: {error}"),
                                );
                                update_app
                                    .dialog()
                                    .message(format!("Update installation failed: {error}"))
                                    .title("Harnex Update")
                                    .show(|_| {});
                            }
                        }
                    });
                });
        }
        Ok(None) if interactive => {
            app.dialog()
                .message("Harnex is up to date.")
                .title("Harnex Update")
                .show(|_| {});
        }
        Ok(None) => {}
        Err(error) => {
            shell_log(
                &shell_log_dir(),
                &format!("[updater] check failed: {error}"),
            );
            if interactive {
                app.dialog()
                    .message(format!("Update check failed: {error}"))
                    .title("Harnex Update")
                    .show(|_| {});
            }
        }
    }
}

/// Native folder picker. Only the bundled Tauri SPA receives IPC capability; Runtime-served pages never do.
fn pick_folder(app: &AppHandle) -> Option<PathBuf> {
    app.dialog()
        .file()
        .blocking_pick_folder()
        .and_then(|fp| fp.into_path().ok())
}

#[tauri::command]
pub(crate) fn window_set_theme(app: AppHandle, mode: String) -> Result<(), String> {
    let theme = match mode.trim().to_ascii_lowercase().as_str() {
        "system" => None,
        "dark" => Some(Theme::Dark),
        "light" => Some(Theme::Light),
        _ => return Err("window theme must be system, light or dark".to_string()),
    };
    let window = app
        .get_webview_window("main")
        .ok_or_else(|| "main window is unavailable".to_string())?;
    window.set_theme(theme).map_err(|error| error.to_string())
}

#[tauri::command]
pub(crate) fn workspace_pick_directory(app: AppHandle) -> Option<String> {
    pick_folder(&app).map(|path| strip_verbatim(path).to_string_lossy().to_string())
}

fn resolve_workspace_open_target(root: &Path, relative: &str) -> Result<PathBuf, String> {
    let requested = Path::new(relative.trim());
    if relative.trim().is_empty() || requested.is_absolute() {
        return Err("path must be a non-empty workspace-relative path".to_string());
    }
    let real_root = std::fs::canonicalize(root).map_err(|error| error.to_string())?;
    let target =
        std::fs::canonicalize(real_root.join(requested)).map_err(|error| error.to_string())?;
    if !target.starts_with(&real_root) {
        return Err("path resolves outside the active workspace".to_string());
    }
    Ok(strip_verbatim(target))
}

fn containing_directory_for_target(target: PathBuf) -> Result<PathBuf, String> {
    if target.is_dir() {
        return Ok(target);
    }
    target
        .parent()
        .map(Path::to_path_buf)
        .ok_or_else(|| "containing directory is unavailable".to_string())
}

#[cfg(windows)]
fn open_path_with_system(target: &Path, reveal_file: bool) -> Result<(), String> {
    use std::os::windows::process::CommandExt;
    const CREATE_NO_WINDOW: u32 = 0x08000000;
    if reveal_file {
        return Command::new("explorer.exe")
            .arg(format!("/select,{}", target.to_string_lossy()))
            .creation_flags(CREATE_NO_WINDOW)
            .spawn()
            .map(|_| ())
            .map_err(|error| error.to_string());
    }
    Command::new("powershell.exe")
        .args([
            "-NoLogo",
            "-NoProfile",
            "-NonInteractive",
            "-WindowStyle",
            "Hidden",
            "-Command",
            "Invoke-Item -LiteralPath $env:HARNEX_OPEN_PATH",
        ])
        .env("HARNEX_OPEN_PATH", target)
        .creation_flags(CREATE_NO_WINDOW)
        .spawn()
        .map(|_| ())
        .map_err(|error| error.to_string())
}

#[cfg(target_os = "macos")]
fn open_path_with_system(target: &Path, reveal_file: bool) -> Result<(), String> {
    let mut command = Command::new("open");
    if reveal_file {
        command.arg("-R");
    }
    command
        .arg(target)
        .spawn()
        .map(|_| ())
        .map_err(|error| error.to_string())
}

#[cfg(all(unix, not(target_os = "macos")))]
fn open_path_with_system(target: &Path, reveal_file: bool) -> Result<(), String> {
    let open_target = if reveal_file {
        target.parent().unwrap_or(target)
    } else {
        target
    };
    Command::new("xdg-open")
        .arg(open_target)
        .spawn()
        .map(|_| ())
        .map_err(|error| error.to_string())
}

#[tauri::command]
pub(crate) fn workspace_reveal_path(app: AppHandle, path: String) -> Result<Value, String> {
    if app.state::<CurrentRemote>().0.lock().unwrap().is_some() {
        return Err("remote files are available in the workspace viewer; they have no local filesystem path".into());
    }
    let root = app
        .try_state::<CurrentWorkspace>()
        .and_then(|state| state.0.lock().unwrap().clone())
        .ok_or_else(|| "no active desktop workspace".to_string())?;
    let target = resolve_workspace_open_target(&root, &path)?;
    let reveal_file = target.is_file();
    let directory = containing_directory_for_target(target.clone())?;
    open_path_with_system(&target, reveal_file)?;
    Ok(json!({ "opened": true, "path": path, "directory": directory }))
}

#[tauri::command]
pub(crate) fn preview_open_browser(url: String) -> Result<(), String> {
    harnex_host_runtime::preview::open_preview_url(&url)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::time::{SystemTime, UNIX_EPOCH};

    #[test]
    fn open_target_stays_inside_workspace() {
        let nonce = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap()
            .as_nanos();
        let base = std::env::temp_dir().join(format!("harnex-open-{}-{nonce}", std::process::id()));
        let root = base.join("workspace");
        std::fs::create_dir_all(&root).unwrap();
        std::fs::write(root.join("inside.txt"), "ok").unwrap();
        std::fs::write(base.join("outside.txt"), "no").unwrap();

        let inside = resolve_workspace_open_target(&root, "inside.txt").unwrap();
        assert_eq!(
            containing_directory_for_target(inside).unwrap().file_name(),
            root.file_name()
        );
        let directory = resolve_workspace_open_target(&root, ".").unwrap();
        assert_eq!(
            containing_directory_for_target(directory)
                .unwrap()
                .file_name(),
            root.file_name()
        );
        assert!(resolve_workspace_open_target(&root, "../outside.txt").is_err());
        assert!(resolve_workspace_open_target(&root, "").is_err());

        std::fs::remove_dir_all(&base).unwrap();
    }

    #[test]
    fn bundled_updater_config_deserializes_without_an_update_channel() {
        let config: serde_json::Value =
            serde_json::from_str(include_str!("../tauri.conf.json")).unwrap();
        let updater: tauri_plugin_updater::Config =
            serde_json::from_value(config["plugins"]["updater"].clone()).unwrap();
        assert!(updater.endpoints.is_empty());
        assert!(updater.pubkey.is_empty());
        assert!(select_update_configuration(None, None, None, None, false).is_none());
    }

    #[test]
    fn release_updater_trust_ignores_runtime_environment() {
        let release = select_update_configuration(
            Some("https://release.example/latest.json"),
            Some("release-public-key"),
            Some("https://attacker.example/latest.json".to_string()),
            Some("attacker-public-key".to_string()),
            false,
        )
        .unwrap();
        assert_eq!(release.0, "https://release.example/latest.json");
        assert_eq!(release.1, "release-public-key");

        let debug = select_update_configuration(
            Some("https://release.example/latest.json"),
            Some("release-public-key"),
            Some("https://staging.example/latest.json".to_string()),
            Some("staging-public-key".to_string()),
            true,
        )
        .unwrap();
        assert_eq!(debug.0, "https://staging.example/latest.json");
        assert_eq!(debug.1, "staging-public-key");
    }
}
