// Desktop composition root: commands and lifecycle behavior live in their owning modules.
#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

mod bundle;
mod catalog;
mod credentials;
mod desktop;
mod file_save;
mod host;
mod remote;
mod runtime;
mod state;
mod workspaces;

use crate::desktop::{
    build_tray, check_for_updates, hide_main_window, show_main_window, update_configuration,
};
use crate::host::{env_or, shell_log, shell_log_dir};
use crate::state::{
    CurrentRemote, CurrentWorkspace, DesktopLifecycle, HostClosing, PersonalProcess, PersonalState,
    ServerProcess, WorkspaceControl, WorkspaceTransition,
};
use crate::workspaces::{initialize_workspaces, restore_workspaces, shutdown_workspaces};
use serde_json::Value;
use std::sync::Mutex;
use tauri::RunEvent;
use tauri::WindowEvent;
use tauri_plugin_global_shortcut::ShortcutState;

fn main() {
    if harnex_host_runtime::interaction::handle_askpass() {
        return;
    }
    #[cfg(any(target_os = "macos", target_os = "linux"))]
    let _ = fix_path_env::fix();

    // macOS defaults to no global shortcut: Cmd+Shift+H is Finder's Home command. Users who
    // explicitly want a system-wide binding can opt in with HARNEX_GLOBAL_SHORTCUT.
    let shortcut = env_or("HARNEX_GLOBAL_SHORTCUT").or_else(|| {
        if cfg!(target_os = "macos") {
            None
        } else {
            Some("CmdOrCtrl+Shift+H".to_string())
        }
    });
    let shortcut_builder = if let Some(value) = shortcut {
        match tauri_plugin_global_shortcut::Builder::new().with_shortcut(value.as_str()) {
            Ok(builder) => builder,
            Err(error) => {
                shell_log(
                    &shell_log_dir(),
                    &format!(
                        "[shortcut] ignoring invalid HARNEX_GLOBAL_SHORTCUT={value:?}: {error}"
                    ),
                );
                tauri_plugin_global_shortcut::Builder::new()
            }
        }
    } else {
        tauri_plugin_global_shortcut::Builder::new()
    };
    let global_shortcut = shortcut_builder
        .with_handler(|app, _shortcut, event| {
            if event.state == ShortcutState::Pressed {
                show_main_window(app);
            }
        })
        .build();
    let app = tauri::Builder::default()
        .plugin(tauri_plugin_single_instance::init(|app, _argv, _cwd| {
            // Second launch → focus the existing window instead of opening a new one.
            show_main_window(app);
        }))
        .plugin(tauri_plugin_dialog::init())
        .plugin(tauri_plugin_updater::Builder::new().build())
        .plugin(tauri_plugin_window_state::Builder::default().build())
        .plugin(global_shortcut)
        .manage(ServerProcess(Mutex::new(None)))
        .manage(PersonalProcess(Mutex::new(None)))
        .manage(PersonalState(Mutex::new(Value::Null)))
        .manage(CurrentWorkspace(Mutex::new(None)))
        .manage(CurrentRemote(Mutex::new(None)))
        .manage(DesktopLifecycle(Mutex::new("CLOSED")))
        .manage(WorkspaceTransition(Mutex::new(())))
        .manage(WorkspaceControl(Mutex::new(None)))
        .manage(HostClosing(std::sync::atomic::AtomicBool::new(false)))
        .manage(file_save::SaveTargets::default())
        .invoke_handler(tauri::generate_handler![
            desktop::preview_open_browser,
            file_save::file_save_pick,
            file_save::file_save_write,
            file_save::file_save_cancel,
            runtime::runtime_base_url,
            runtime::runtime_session_token,
            runtime::personal_open,
            remote::remote_connections,
            remote::remote_interaction,
            remote::remote_identity_pick,
            remote::remote_connection_save,
            remote::remote_connection_remove,
            remote::remote_inspect,
            remote::remote_workspace_register,
            desktop::window_set_theme,
            desktop::workspace_pick_directory,
            desktop::workspace_reveal_path,
            credentials::provider_secret_store,
            credentials::provider_secret_reveal,
            credentials::provider_secret_delete,
            workspaces::workspace_list,
            workspaces::workspace_background,
            workspaces::workspace_register,
            workspaces::workspace_remove,
            workspaces::workspace_switch,
            workspaces::workspace_close
        ])
        .on_window_event(|window, event| {
            if let WindowEvent::CloseRequested { api, .. } = event {
                // Closing the main window is reversible and keeps the single active Runtime
                // available from the tray. Explicit tray Quit still exits and drains it.
                api.prevent_close();
                let _ = window.hide();
            }
        })
        .on_menu_event(|app, event| {
            let id = event.id().as_ref().to_string();
            if id == "desktop:show" {
                show_main_window(app);
            } else if id == "desktop:hide" {
                hide_main_window(app);
            } else if id == "desktop:update" {
                let handle = app.clone();
                tauri::async_runtime::spawn(async move { check_for_updates(handle, true).await });
            } else if id == "desktop:quit" {
                app.exit(0);
            }
        })
        .setup(|app| {
            let handle = app.handle().clone();
            initialize_workspaces(&handle).map_err(std::io::Error::other)?;
            // autostart 插件已禁用，但保留参数检测（不依赖插件）
            let launched_at_login = std::env::args().any(|arg| arg == "--autostart");
            build_tray(&handle)?;
            if launched_at_login {
                hide_main_window(&handle);
            }

            if update_configuration().is_some() && !launched_at_login {
                let updater_handle = handle.clone();
                tauri::async_runtime::spawn(async move {
                    check_for_updates(updater_handle, false).await;
                });
            }

            restore_workspaces(&handle, !launched_at_login);

            Ok(())
        })
        .build(tauri::generate_context!())
        .expect("error while building Harnex desktop shell");

    #[cfg(unix)]
    {
        let signal_handle = app.handle().clone();
        ctrlc::set_handler(move || signal_handle.exit(0))
            .expect("cannot install desktop termination signal handler");
    }

    app.run(|handle, event| match event {
        #[cfg(target_os = "macos")]
        RunEvent::Reopen { .. } => show_main_window(handle),
        RunEvent::Exit => shutdown_workspaces(handle),
        _ => {}
    });
}
