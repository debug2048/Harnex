//! Workspace admission, transitions and desktop lifecycle projections.

use crate::catalog::{
    load_domain_last_workspace, load_workspace_registry, record_remote_retained, record_workspace,
    update_workspace_registry,
};
use crate::desktop::update_workspace_window;
use crate::host::{
    atomic_write_json, claim_domain_owner, domain_manifest_path, env_or, load_domain_state,
    owner_instance_id, publish_domain_manifest, release_domain_owner, runtime_domain_dir,
    runtime_domain_id, runtime_workspace_id, same_workspace, set_domain_last, shell_log,
    shell_log_dir, strip_verbatim, unpublish_domain_manifest, update_domain_state,
};
use crate::remote::{connection_jar, connection_store};
use crate::runtime::{
    drain_runtime, kill_child, personal_open, resolve_port, spawn_runtime,
    wait_until_runtime_ready, HEALTH_TIMEOUT,
};
use crate::state::{
    CurrentRemote, CurrentWorkspace, DesktopLifecycle, HostClosing, PersonalProcess, PersonalState,
    RuntimeChild, RuntimeProcess, ServerProcess, WorkspaceControl, WorkspaceTransition,
};
use chrono::Utc;
use serde_json::json;
use serde_json::Value;
use std::path::PathBuf;
use std::time::Duration;
use std::time::Instant;
use tauri::AppHandle;
use tauri::Manager;

fn active_workspace_id(app: &AppHandle) -> Option<String> {
    if let Some(remote) = app
        .try_state::<CurrentRemote>()
        .and_then(|state| state.0.lock().unwrap().clone())
    {
        return Some(remote.1);
    }
    app.try_state::<CurrentWorkspace>()
        .and_then(|state| state.0.lock().unwrap().clone())
        .map(|root| runtime_workspace_id(&root))
}

fn release_runtime(app: &AppHandle, stop_remote: bool) -> Result<(), String> {
    let remote_id = app
        .state::<CurrentRemote>()
        .0
        .lock()
        .unwrap()
        .as_ref()
        .map(|entry| entry.1.clone());
    let remote_workspace = remote_id.as_ref().and_then(|id| {
        load_workspace_registry()["workspaces"]
            .as_array()
            .and_then(|items| {
                items
                    .iter()
                    .find(|item| item["id"].as_str() == Some(id.as_str()))
                    .cloned()
            })
    });
    let keep_running = !stop_remote
        && remote_workspace
            .as_ref()
            .is_some_and(|item| item["remoteKeepRunning"] == true);
    let state = app.state::<ServerProcess>();
    let current = state.0.lock().unwrap().take();
    if current.is_none() {
        if let Some(id) = &remote_id {
            if stop_remote {
                return Err("remote state is unconfirmed; reconnect to the current workspace before stopping it".into());
            }
            // No local tunnel exists after a failed restore. Retain the remote
            // catalog identity for reconnecting, then release the current slot.
            record_remote_retained(
                id,
                !remote_workspace
                    .as_ref()
                    .is_some_and(|item| item["remoteService"].is_object()),
            )?;
        }
    }
    if let Some(mut runtime) = current {
        if let Some(remote) = &runtime.remote {
            if !keep_running && remote.descriptor.ownership != "attached" {
                if let Err(error) = remote.close() {
                    *state.0.lock().unwrap() = Some(runtime);
                    set_lifecycle(app, "FAILED");
                    return Err(format!("remote stop unconfirmed: {error}"));
                }
            }
            if let Some(id) = &remote_id {
                record_remote_retained(
                    id,
                    keep_running && remote.descriptor.ownership != "attached",
                )?;
            }
        } else {
            drain_runtime(resolve_port(), &runtime.supervisor_token);
        }
        kill_child(&mut runtime.child);
    }
    *app.state::<CurrentRemote>().0.lock().unwrap() = None;
    Ok(())
}

fn launch_remote_workspace(
    app: &AppHandle,
    item: Value,
    restart: bool,
    upgrade: bool,
) -> Result<(), String> {
    let was_ready = *app.state::<DesktopLifecycle>().0.lock().unwrap() == "READY";
    let result = launch_remote_workspace_inner(app, item, restart, upgrade);
    if let Err(error) = &result {
        set_lifecycle(
            app,
            if upgrade
                && was_ready
                && (error.contains("UPGRADE_BUSY") || error.contains("UPGRADE_UNSUPPORTED"))
            {
                "READY"
            } else {
                "FAILED"
            },
        );
    }
    result
}

fn launch_remote_workspace_inner(
    app: &AppHandle,
    item: Value,
    restart: bool,
    upgrade: bool,
) -> Result<(), String> {
    let transition = app.state::<WorkspaceTransition>();
    let _guard = transition.0.lock().unwrap();
    let id = item["id"].as_str().ok_or("remote workspace id missing")?;
    let profile_id = item["remoteProfile"]
        .as_str()
        .ok_or("remote profile missing")?;
    let root = item["root"].as_str().ok_or("remote root missing")?;
    // Reopening after a lost tunnel resumes the same remote owner, not a second Runtime.
    let same = active_workspace_id(app).as_deref() == Some(id);
    if item["remoteService"].is_object() && (restart || upgrade) {
        return Err(
            "UPGRADE_UNSUPPORTED: this existing service must be restarted or updated on its server"
                .into(),
        );
    }
    if upgrade {
        if !same {
            return Err("reconnect to the managed remote project before updating".into());
        }
        let control = app
            .state::<ServerProcess>()
            .0
            .lock()
            .unwrap()
            .as_ref()
            .and_then(|runtime| runtime.remote.clone())
            .ok_or("remote connection is unavailable")?;
        control.upgrade(&connection_jar(app)?)?;
    }
    let attaching_current = item["remoteService"].is_object()
        && app
            .state::<ServerProcess>()
            .0
            .lock()
            .unwrap()
            .as_ref()
            .and_then(|runtime| runtime.remote.as_ref())
            .is_some_and(|remote| {
                remote.profile.id == profile_id
                    && remote.descriptor.workspace_id
                        == item["runtimeScopeId"].as_str().unwrap_or("")
                    && remote.descriptor.domain_id
                        == item["remoteService"]["domainId"].as_str().unwrap_or("")
            });
    if same && (!restart || upgrade) || attaching_current {
        if let Some(mut old) = app.state::<ServerProcess>().0.lock().unwrap().take() {
            if attaching_current
                && old
                    .remote
                    .as_ref()
                    .is_some_and(|remote| remote.descriptor.ownership == "managed")
            {
                if let Some((_, old_id)) = app.state::<CurrentRemote>().0.lock().unwrap().as_ref() {
                    record_remote_retained(old_id, true)?;
                }
            }
            kill_child(&mut old.child);
        }
    } else {
        set_lifecycle(app, "DRAINING");
        release_runtime(app, same && restart)?;
    }
    unpublish_domain_manifest();
    *app.state::<CurrentRemote>().0.lock().unwrap() = Some((profile_id.into(), id.into()));
    *app.state::<CurrentWorkspace>().0.lock().unwrap() = Some(PathBuf::from(root));
    set_lifecycle(app, "OPENING");
    let store = connection_store();
    let domain = store.workspace_domain(&runtime_domain_id(), profile_id, id)?;
    let opened = if item["remoteService"].is_object() {
        let reference = serde_json::from_value(item["remoteService"].clone())
            .map_err(|error| error.to_string())?;
        harnex_host_runtime::services::connect(&store.get(profile_id)?, &reference, resolve_port())
    } else {
        harnex_host_runtime::Connection::connect(
            &store.get(profile_id)?,
            &connection_jar(app)?,
            &domain,
            root,
            resolve_port(),
            Some(id),
        )
    };
    let connection = match opened {
        Ok(connection) => connection,
        Err(error) => {
            set_lifecycle(app, "FAILED");
            return Err(error);
        }
    };
    let (child, control, _) = connection.into_parts();
    let credential_file = runtime_domain_dir().join("remote-session.json");
    atomic_write_json(&credential_file, &json!({"token":control.descriptor.token}))?;
    atomic_write_json(
        &domain_manifest_path(),
        &json!({
            "schemaVersion":3, "runtimeProfile":"PROJECT", "authType":"bearer-json", "runtimeDomainId":runtime_domain_id(), "ownerKind":"tauri",
            "ownerPid":std::process::id(), "runtimePid":std::process::id(), "ownerInstanceId":owner_instance_id(),
            "canonicalRoot":control.descriptor.root, "scopeId":control.descriptor.workspace_id,
            "instanceId":control.descriptor.instance_id, "endpoint":format!("http://127.0.0.1:{}", resolve_port()),
            "lifecycle":"READY", "protocolVersion":"1.0", "bootTokenFile":credential_file.to_string_lossy(),
            "configRevision":"remote", "publishedAt":Utc::now().to_rfc3339(),
            "remote":{"host":control.profile.endpoint_label(),"root":control.descriptor.root,
                "runtimePid":control.descriptor.pid,"connectionId":control.profile.id}
        }),
    )?;
    let instance_id = control.descriptor.instance_id.clone();
    let remote_host = control.profile.endpoint_label();
    *app.state::<ServerProcess>().0.lock().unwrap() = Some(RuntimeProcess {
        child: RuntimeChild::Ssh(child),
        supervisor_token: control.descriptor.supervisor_token.clone(),
        instance_id,
        remote: Some(control),
    });
    set_domain_last(Some(id));
    record_remote_retained(id, false)?;
    set_lifecycle(app, "READY");
    update_workspace_window(app, format!("Harnex — SSH {remote_host} · {root}"), false);
    Ok(())
}

fn set_lifecycle(app: &AppHandle, phase: &'static str) {
    if let Some(state) = app.try_state::<DesktopLifecycle>() {
        *state.0.lock().unwrap() = phase;
    }
    let active_id = active_workspace_id(app);
    let pending_remote = app
        .try_state::<CurrentRemote>()
        .and_then(|remote| remote.0.lock().unwrap().as_ref().map(|(_, id)| id.clone()));
    update_domain_state(|domain| {
        domain["ownerKind"] = json!("tauri");
        domain["port"] = json!(resolve_port());
        domain["activeWorkspaceId"] = active_id.as_deref().map_or(Value::Null, |id| json!(id));
        domain["lifecycle"] = json!(phase);
        if phase != "FAILED" {
            domain["failureMessage"] = Value::Null;
        }
        domain["pendingRemoteId"] = pending_remote
            .as_deref()
            .map_or(Value::Null, |id| json!(id));
    });
}

fn set_workspace_failure(app: &AppHandle, message: &str) {
    update_domain_state(|domain| {
        domain["failureMessage"] = json!(message);
    });
    set_lifecycle(app, "FAILED");
    shell_log(&shell_log_dir(), &format!("[open] {message}"));
}

fn pending_remote_workspace(previous: &Value, registry: &Value) -> Option<Value> {
    let id = previous["pendingRemoteId"].as_str().or_else(|| {
        (previous["lifecycle"].as_str() != Some("CLOSED"))
            .then(|| previous["activeWorkspaceId"].as_str())
            .flatten()
    })?;
    registry["workspaces"]
        .as_array()?
        .iter()
        .find(|item| {
            item["id"].as_str() == Some(id)
                && item["remoteProfile"].is_string()
                && item["root"].is_string()
        })
        .cloned()
}

fn workspace_list_value(app: &AppHandle) -> Value {
    let registry = load_workspace_registry();
    let domain_state = load_domain_state();
    let lifecycle = app
        .try_state::<DesktopLifecycle>()
        .map(|state| *state.0.lock().unwrap())
        .unwrap_or("CLOSED");
    let active_id = active_workspace_id(app);
    let remote_details = app.try_state::<ServerProcess>().and_then(|state| state.0.lock().unwrap().as_ref().and_then(|runtime| runtime.remote.as_ref().map(|remote| {
        let value = &remote.descriptor;
        json!({"ownership":value.ownership,"runtimeVersion":value.runtime_version,"runtimeSha256":value.runtime_sha256,
            "targetVersion":value.target_version,"targetSha256":value.target_sha256,"updateAvailable":value.update_available,"canUpgrade":value.can_upgrade})
    })));
    let instance_id = if lifecycle == "READY" {
        app.try_state::<ServerProcess>().and_then(|state| {
            state
                .0
                .lock()
                .unwrap()
                .as_ref()
                .map(|runtime| runtime.instance_id.clone())
        })
    } else {
        None
    };
    let entries = registry
        .get("workspaces")
        .and_then(Value::as_array)
        .cloned()
        .unwrap_or_default()
        .into_iter()
        .filter_map(|entry| {
            let mut item = entry.as_object()?.clone();
            let id = item
                .get("id")
                .and_then(Value::as_str)
                .unwrap_or("")
                .to_string();
            let active = active_id.as_deref() == Some(id.as_str());
            item.insert(
                "state".into(),
                json!(if active { lifecycle } else { "CLOSED" }),
            );
            item.insert("isActive".into(), json!(active));
            if active {
                if let Some(details) = &remote_details {
                    item.insert("remoteRuntime".into(), details.clone());
                }
            }
            item.insert(
                "instanceId".into(),
                if active {
                    instance_id
                        .as_ref()
                        .map_or(Value::Null, |value| json!(value))
                } else {
                    Value::Null
                },
            );
            Some(Value::Object(item))
        })
        .collect::<Vec<_>>();
    json!({
        "workspaces": entries,
        "activeWorkspaceId": active_id,
        "lastWorkspaceId": domain_state.get("lastWorkspaceId").cloned().unwrap_or(Value::Null),
        "lifecycle": lifecycle,
        "failureMessage": if lifecycle == "FAILED" { domain_state["failureMessage"].clone() } else { Value::Null },
        "instanceId": instance_id,
        "personal": app.state::<PersonalState>().0.lock().unwrap().clone()
    })
}

#[tauri::command]
pub(crate) async fn workspace_list(app: AppHandle) -> Result<Value, String> {
    // Polling resolves paths and reads the catalog while startup workers update it.
    // Keep filesystem access and mutex waits off the native event loop.
    tauri::async_runtime::spawn_blocking(move || workspace_list_value(&app))
        .await
        .map_err(|error| error.to_string())
}

#[tauri::command]
pub(crate) fn workspace_background(
    app: AppHandle,
    id: String,
    expected_instance_id: String,
    keep_running: bool,
) -> Result<Value, String> {
    let transition = app.state::<WorkspaceTransition>();
    let _guard = transition
        .0
        .try_lock()
        .map_err(|_| "workspace is changing; retry after it is ready".to_string())?;
    if active_workspace_id(&app).as_deref() != Some(&id) {
        return Err("workspace changed; reconnect before changing background behavior".into());
    }
    let current = app
        .state::<ServerProcess>()
        .0
        .lock()
        .unwrap()
        .as_ref()
        .map(|runtime| runtime.instance_id.clone());
    if current.as_deref() != Some(&expected_instance_id) {
        return Err("workspace instance changed; refresh and retry".into());
    }
    update_workspace_registry(|registry| {
        let item = registry["workspaces"]
            .as_array_mut()
            .and_then(|items| {
                items
                    .iter_mut()
                    .find(|item| item["id"].as_str() == Some(&id))
            })
            .ok_or("workspace not found")?;
        if !item["remoteProfile"].is_string() {
            return Err("background retention is only available for SSH workspaces".into());
        }
        if item["remoteService"].is_object() {
            return Err(
                "an existing service always stays on its server after disconnecting".into(),
            );
        }
        item["remoteKeepRunning"] = json!(keep_running);
        Ok(())
    })?;
    workspace_list_value(&app)["workspaces"]
        .as_array()
        .and_then(|items| items.iter().find(|item| item["id"].as_str() == Some(&id)))
        .cloned()
        .ok_or_else(|| "workspace not found".into())
}

#[tauri::command]
pub(crate) async fn workspace_register(
    app: AppHandle,
    root: String,
    name: Option<String>,
    data_dir: Option<String>,
) -> Result<Value, String> {
    tauri::async_runtime::spawn_blocking(move || register_workspace(&app, root, name, data_dir))
        .await
        .map_err(|error| error.to_string())?
}

fn register_workspace(
    app: &AppHandle,
    root: String,
    name: Option<String>,
    data_dir: Option<String>,
) -> Result<Value, String> {
    let path = strip_verbatim(std::fs::canonicalize(&root).map_err(|error| error.to_string())?);
    if !path.is_dir() {
        return Err("workspace root must be an existing directory".into());
    }
    record_workspace(&path, false)?;
    if name
        .as_deref()
        .is_some_and(|value| !value.trim().is_empty())
        || data_dir.is_some()
    {
        let id = runtime_workspace_id(&path);
        update_workspace_registry(|registry| {
            if let Some(entry) = registry
                .get_mut("workspaces")
                .and_then(Value::as_array_mut)
                .and_then(|items| {
                    items
                        .iter_mut()
                        .find(|item| item.get("id").and_then(Value::as_str) == Some(&id))
                })
            {
                if let Some(value) = name.filter(|value| !value.trim().is_empty()) {
                    entry["name"] = json!(value.trim());
                }
                if let Some(value) = data_dir.filter(|value| !value.trim().is_empty()) {
                    entry["dataDir"] = json!(value);
                }
            }
            Ok(())
        })?;
    }
    workspace_list_value(app)
        .get("workspaces")
        .and_then(Value::as_array)
        .and_then(|items| {
            items.iter().find(|item| {
                item.get("id").and_then(Value::as_str) == Some(runtime_workspace_id(&path).as_str())
            })
        })
        .cloned()
        .ok_or_else(|| "registered workspace projection is unavailable".to_string())
}

#[tauri::command]
pub(crate) fn workspace_remove(app: AppHandle, id: String) -> Result<(), String> {
    let active = active_workspace_id(&app);
    if active.as_deref() == Some(id.as_str()) {
        return Err("close the active workspace before removing it".into());
    }
    update_workspace_registry(|registry| {
        let entries = registry
            .get_mut("workspaces")
            .and_then(Value::as_array_mut)
            .ok_or_else(|| "workspace registry is invalid".to_string())?;
        let before = entries.len();
        entries.retain(|entry| entry.get("id").and_then(Value::as_str) != Some(id.as_str()));
        if entries.len() == before {
            return Err(format!("unknown workspace: {id}"));
        }
        Ok(())
    })?;
    if load_domain_state()
        .get("lastWorkspaceId")
        .and_then(Value::as_str)
        == Some(id.as_str())
    {
        set_domain_last(None);
    }
    Ok(())
}

#[tauri::command]
pub(crate) async fn workspace_switch(
    app: AppHandle,
    id: String,
    restart_if_current: Option<bool>,
    upgrade_remote: Option<bool>,
) -> Result<bool, String> {
    let registry = load_workspace_registry();
    if let Some(item) = registry["workspaces"]
        .as_array()
        .and_then(|items| {
            items
                .iter()
                .find(|item| item["id"].as_str() == Some(&id) && item["remoteProfile"].is_string())
        })
        .cloned()
    {
        return tauri::async_runtime::spawn_blocking(move || {
            launch_remote_workspace(
                &app,
                item,
                restart_if_current.unwrap_or(false),
                upgrade_remote.unwrap_or(false),
            )
            .map(|_| true)
        })
        .await
        .map_err(|error| error.to_string())?;
    }
    let root = registry
        .get("workspaces")
        .and_then(Value::as_array)
        .and_then(|items| {
            items
                .iter()
                .find(|entry| entry.get("id").and_then(Value::as_str) == Some(id.as_str()))
        })
        .and_then(|entry| entry.get("root"))
        .and_then(Value::as_str)
        .map(PathBuf::from)
        .ok_or_else(|| format!("unknown workspace: {id}"))?;
    if !root.is_dir() {
        return Err(format!(
            "workspace directory does not exist: {}",
            root.display()
        ));
    }
    tauri::async_runtime::spawn_blocking(move || {
        launch_workspace(&app, root, true, restart_if_current.unwrap_or(false));
    })
    .await
    .map_err(|error| error.to_string())?;
    Ok(true)
}

fn close_workspace_impl(
    app: &AppHandle,
    stop_remote: bool,
    expected: Option<&str>,
) -> Result<(), String> {
    let transition = app.state::<WorkspaceTransition>();
    let _transition_guard = transition.0.lock().unwrap();
    if stop_remote
        && app
            .state::<ServerProcess>()
            .0
            .lock()
            .unwrap()
            .as_ref()
            .is_some_and(|runtime| {
                runtime
                    .remote
                    .as_ref()
                    .is_some_and(|remote| remote.descriptor.ownership == "attached")
            })
    {
        return Err(
            "this existing service is managed on the server; disconnect this client instead".into(),
        );
    }
    if let Some(expected) = expected {
        let current = app
            .state::<ServerProcess>()
            .0
            .lock()
            .unwrap()
            .as_ref()
            .map(|runtime| runtime.instance_id.clone());
        if current.as_deref() != Some(expected) {
            return Err("workspace changed; refresh before closing".into());
        }
    }
    set_lifecycle(app, "DRAINING");
    unpublish_domain_manifest();
    release_runtime(app, stop_remote).map_err(|error| {
        set_lifecycle(app, "FAILED");
        error
    })?;
    if let Some(workspace) = app.try_state::<CurrentWorkspace>() {
        *workspace.0.lock().unwrap() = None;
    }
    set_lifecycle(app, "CLOSED");
    update_workspace_window(&app, "Harnex".into(), false);
    Ok(())
}

#[tauri::command]
pub(crate) async fn workspace_close(
    app: AppHandle,
    stop_remote: Option<bool>,
    expected_instance_id: Option<String>,
) -> Result<bool, String> {
    tauri::async_runtime::spawn_blocking(move || {
        close_workspace_impl(
            &app,
            stop_remote.unwrap_or(false),
            expected_instance_id.as_deref(),
        )
    })
    .await
    .map_err(|error| error.to_string())??;
    Ok(true)
}

/// Stop any running Runtime, then launch a fresh one for `root`. The trusted SPA remains
/// mounted and observes this transaction through the host lifecycle projection.
fn launch_workspace(app: &AppHandle, root: PathBuf, reveal_window: bool, force_restart: bool) {
    let transition = app.state::<WorkspaceTransition>();
    let _transition_guard = transition.0.lock().unwrap();
    launch_workspace_locked(app, root, reveal_window, force_restart);
}

fn switch_attached_workspace(
    app: &AppHandle,
    root: &str,
    expected_instance: &str,
) -> Result<Value, String> {
    let root = std::fs::canonicalize(root).map_err(|e| format!("项目目录不可用: {e}"))?;
    if !root.is_dir() {
        return Err("项目路径必须是目录".into());
    }
    let transition = app.state::<WorkspaceTransition>();
    let _guard = transition
        .0
        .try_lock()
        .map_err(|_| "宿主正在切换项目，请稍后重试".to_string())?;
    if app
        .state::<HostClosing>()
        .0
        .load(std::sync::atomic::Ordering::Acquire)
    {
        return Err("宿主正在退出".into());
    }
    if *app.state::<DesktopLifecycle>().0.lock().unwrap() != "READY"
        || app.state::<CurrentRemote>().0.lock().unwrap().is_some()
    {
        return Err("宿主必须处于本地项目就绪状态".into());
    }
    let token = {
        let state = app.state::<ServerProcess>();
        let runtime = state.0.lock().unwrap();
        let runtime = runtime.as_ref().ok_or("Runtime 已退出")?;
        if runtime.instance_id != expected_instance {
            return Err("宿主项目已由其他窗口切换，请主动重新连接".into());
        }
        runtime.supervisor_token.clone()
    };
    let already = app
        .state::<CurrentWorkspace>()
        .0
        .lock()
        .unwrap()
        .as_ref()
        .is_some_and(|current| same_workspace(current, &root));
    if !already {
        record_workspace(&root, false).map_err(|e| format!("Cannot record workspace: {e}"))?;
        harnex_host_runtime::workspace_control::stop_idle(resolve_port(), &token)?;
        launch_workspace_locked(app, root.clone(), false, false);
    }
    let opened_instance = app
        .state::<ServerProcess>()
        .0
        .lock()
        .unwrap()
        .as_ref()
        .map(|runtime| runtime.instance_id.clone())
        .ok_or("Runtime 未能启动")?;
    // Readiness must not hold the lifecycle mutex: the desktop can still close or replace a slow opening project.
    drop(_guard);
    let deadline = Instant::now() + HEALTH_TIMEOUT + Duration::from_secs(5);
    loop {
        if app
            .state::<HostClosing>()
            .0
            .load(std::sync::atomic::Ordering::Acquire)
        {
            return Err("宿主正在退出".into());
        }
        let current = app
            .state::<ServerProcess>()
            .0
            .lock()
            .unwrap()
            .as_ref()
            .map(|runtime| runtime.instance_id.clone());
        if current.as_deref() != Some(opened_instance.as_str()) {
            return Err("宿主项目已由其他操作改变，请重新连接".into());
        }
        let lifecycle = *app.state::<DesktopLifecycle>().0.lock().unwrap();
        if lifecycle == "READY" {
            let bytes = std::fs::read(domain_manifest_path()).map_err(|e| e.to_string())?;
            let manifest: Value = serde_json::from_slice(&bytes).map_err(|e| e.to_string())?;
            if manifest["instanceId"] != opened_instance {
                return Err("Runtime 实例已变化，请重新连接".into());
            }
            return Ok(manifest);
        }
        if lifecycle == "FAILED" || Instant::now() >= deadline {
            return Err("宿主未能打开新项目，请查看桌面端状态后重试".into());
        }
        std::thread::sleep(Duration::from_millis(200));
    }
}

fn launch_workspace_locked(
    app: &AppHandle,
    root: PathBuf,
    reveal_window: bool,
    force_restart: bool,
) {
    if app
        .state::<HostClosing>()
        .0
        .load(std::sync::atomic::Ordering::Acquire)
    {
        return;
    }
    let switch_started = Instant::now();
    // Already on this workspace? Re-launching would needlessly kill+restart the Runtime and
    // reload the UI (the user reported the menu "re-opening" an already-open project). Treat it
    // as a no-op and just refocus the existing window.
    let already = app
        .try_state::<CurrentWorkspace>()
        .and_then(|s| s.0.lock().unwrap().clone())
        .map(|cur| same_workspace(&cur, &root))
        .unwrap_or(false);
    if already
        && !force_restart
        && *app.state::<DesktopLifecycle>().0.lock().unwrap() == "READY"
        && app.state::<CurrentRemote>().0.lock().unwrap().is_none()
    {
        if reveal_window {
            update_workspace_window(app, format!("Harnex — {}", root.display()), true);
        }
        return;
    }

    let port = resolve_port();

    unpublish_domain_manifest();
    set_lifecycle(app, "DRAINING");
    if let Err(error) = release_runtime(app, false) {
        set_workspace_failure(app, &error);
        return;
    }

    if let Err(error) = record_workspace(&root, false) {
        set_workspace_failure(app, &format!("Cannot record workspace: {error}"));
        return;
    }
    if let Some(ws) = app.try_state::<CurrentWorkspace>() {
        *ws.0.lock().unwrap() = Some(root.clone());
    }
    set_lifecycle(app, "OPENING");

    // The trusted bundled SPA remains mounted for the whole transaction. It renders the public
    // "close current / open target" progress while polling this Rust host projection.
    update_workspace_window(app, format!("Harnex — {}", root.display()), reveal_window);

    // Step 2: Start the JVM in the background.
    let (expected_instance_id, expected_runtime_pid) = match spawn_runtime(app, port, &root) {
        Some(runtime) => {
            let instance_id = runtime.instance_id.clone();
            let runtime_pid = runtime.child.id();
            if let Some(state) = app.try_state::<ServerProcess>() {
                *state.0.lock().unwrap() = Some(runtime);
            }
            (instance_id, runtime_pid)
        }
        None => {
            shell_log(
                &shell_log_dir(),
                "[launch] spawn_runtime returned None (no JVM started)",
            );
            if let Some(workspace) = app.try_state::<CurrentWorkspace>() {
                *workspace.0.lock().unwrap() = None;
            }
            unpublish_domain_manifest();
            set_workspace_failure(
                app,
                "Runtime could not be started; check the desktop diagnostics log",
            );
            return;
        }
    };

    // Step 3: In a background thread (NEVER block the UI thread), wait for the
    // backend port to accept connections, then switch to the real Web UI.
    let handle = app.clone();
    let expected_root = root.clone();
    let expected_workspace_id = runtime_workspace_id(&root);
    std::thread::spawn(move || {
        let ready = wait_until_runtime_ready(
            &handle,
            "127.0.0.1",
            port,
            &expected_workspace_id,
            &expected_instance_id,
            HEALTH_TIMEOUT,
        );
        shell_log(
            &shell_log_dir(),
            &format!(
                "[wait] 127.0.0.1:{port} ready={} switchDurationMs={}",
                ready.is_ok(),
                switch_started.elapsed().as_millis()
            ),
        );
        let still_current_workspace = handle
            .try_state::<CurrentWorkspace>()
            .and_then(|state| state.0.lock().unwrap().clone())
            .map(|current| same_workspace(&current, &expected_root))
            .unwrap_or(false);
        let still_current_instance = handle
            .try_state::<ServerProcess>()
            .and_then(|state| {
                state
                    .0
                    .lock()
                    .unwrap()
                    .as_ref()
                    .map(|runtime| runtime.instance_id == expected_instance_id)
            })
            .unwrap_or(false);
        if !still_current_workspace
            || !still_current_instance
            || handle
                .state::<HostClosing>()
                .0
                .load(std::sync::atomic::Ordering::Acquire)
        {
            shell_log(
                &shell_log_dir(),
                "[wait] ignored stale readiness result from a superseded workspace Runtime",
            );
            return;
        }
        if ready.is_ok() {
            match publish_domain_manifest(
                &expected_root,
                &expected_workspace_id,
                &expected_instance_id,
                expected_runtime_pid,
                port,
            ) {
                Ok(()) => {
                    if let Err(error) = record_workspace(&expected_root, true) {
                        shell_log(
                            &shell_log_dir(),
                            &format!("[catalog] cannot update recent workspace: {error}"),
                        );
                    }
                    set_domain_last(Some(&expected_workspace_id));
                    set_lifecycle(&handle, "READY");
                }
                Err(error) => {
                    set_workspace_failure(
                        &handle,
                        &format!("Runtime manifest publish failed: {error}"),
                    );
                }
            }
        } else if let Err(error) = ready {
            unpublish_domain_manifest();
            set_workspace_failure(&handle, &error);
        }
    });
}

/// Initialize the host lease and restore published workspace identity before UI setup.
pub(crate) fn initialize_workspaces(app: &AppHandle) -> Result<(), String> {
    let handle = app.clone();
    let previous = load_domain_state();
    claim_domain_owner()?;
    let control_handle = handle.clone();
    match harnex_host_runtime::workspace_control::WorkspaceControl::start(
        runtime_domain_dir(),
        owner_instance_id().to_string(),
        move |root, instance| switch_attached_workspace(&control_handle, root, instance),
    ) {
        Ok(control) => *handle.state::<WorkspaceControl>().0.lock().unwrap() = Some(control),
        Err(error) => shell_log(
            &shell_log_dir(),
            &format!("[workspace-control] unavailable: {error}"),
        ),
    }
    unpublish_domain_manifest();
    let registry = load_workspace_registry();
    let pending = pending_remote_workspace(&previous, &registry);
    if let Some(pending) = pending {
        *handle.state::<CurrentRemote>().0.lock().unwrap() = Some((
            pending["remoteProfile"].as_str().unwrap().into(),
            pending["id"].as_str().unwrap().into(),
        ));
        *handle.state::<CurrentWorkspace>().0.lock().unwrap() =
            Some(PathBuf::from(pending["root"].as_str().unwrap()));
        set_domain_last(pending["id"].as_str());
        // The old remote instance is unverified, not an opening failure.
        // Retain ownership and let the workbench reconnect with authentication.
        set_lifecycle(&handle, "CLOSED");
    } else {
        set_lifecycle(&handle, "CLOSED");
    }
    Ok(())
}

/// Warm personal space and reopen the selected project after desktop setup.
pub(crate) fn restore_workspaces(handle: &AppHandle, reveal_window: bool) {
    let log_dir = shell_log_dir();
    let _ = std::fs::create_dir_all(&log_dir);

    // Personal space warms in parallel with project restoration, even when the
    // previous view was Agent. personal_open serializes concurrent UI requests.
    let personal_handle = handle.clone();
    tauri::async_runtime::spawn(async move {
        if let Err(error) = personal_open(personal_handle, None).await {
            shell_log(
                &shell_log_dir(),
                &format!("[setup] personal warmup failed: {error}"),
            );
        }
    });

    // HARNEX_WORKSPACE pins the project root. HARNEX_DATA_DIR is deliberately
    // independent: it never doubles as a project path.
    if let Some(root) = env_or("HARNEX_WORKSPACE") {
        shell_log(&log_dir, &format!("[setup] pinned workspace={root}"));
        launch_workspace(&handle, PathBuf::from(root), reveal_window, false);
    } else if let Some(last) = load_domain_last_workspace() {
        // Reopen the most-recent workspace (VS Code style); later switches use the in-app GUI.
        shell_log(
            &log_dir,
            &format!("[setup] reopen workspace={}", last.display()),
        );
        launch_workspace(&handle, last, reveal_window, false);
    } else {
        // First run is the normal empty workbench. The upper-left Workspace action opens
        // the same native directory picker used for all later switches.
        shell_log(&log_dir, "[setup] first run → empty main workbench");
    }
}

/// Close all owned runtime resources under the same transition lock used by commands.
pub(crate) fn shutdown_workspaces(handle: &AppHandle) {
    handle
        .state::<HostClosing>()
        .0
        .store(true, std::sync::atomic::Ordering::Release);
    handle.state::<WorkspaceControl>().0.lock().unwrap().take();
    harnex_host_runtime::interaction::shutdown();
    let transition = handle.state::<WorkspaceTransition>();
    let _guard = transition.0.lock().unwrap();
    handle.state::<PersonalProcess>().0.lock().unwrap().take();
    unpublish_domain_manifest();
    if let Err(error) = release_runtime(handle, false) {
        shell_log(&shell_log_dir(), &error);
        if let Some(mut runtime) = handle.state::<ServerProcess>().0.lock().unwrap().take() {
            kill_child(&mut runtime.child); // transport only; remote state remains explicitly unconfirmed
        }
        set_lifecycle(handle, "FAILED");
    } else {
        set_lifecycle(handle, "CLOSED");
    }
    release_domain_owner();
}

#[cfg(test)]
mod tests {
    #[test]
    fn pending_remote_survives_booting_again_before_reconnection() {
        let remote =
            serde_json::json!({"id":"remote", "remoteProfile":"ssh", "root":"/srv/project"});
        let registry = serde_json::json!({"workspaces":[remote.clone()]});
        for previous in [
            serde_json::json!({"lifecycle":"READY", "activeWorkspaceId":"remote"}),
            serde_json::json!({"lifecycle":"FAILED", "activeWorkspaceId":"remote"}),
            serde_json::json!({"lifecycle":"CLOSED", "activeWorkspaceId":"remote", "pendingRemoteId":"remote"}),
        ] {
            assert_eq!(
                super::pending_remote_workspace(&previous, &registry),
                Some(remote.clone())
            );
        }
        assert!(super::pending_remote_workspace(&serde_json::json!({"lifecycle":"CLOSED", "activeWorkspaceId":"remote", "pendingRemoteId":null}), &registry).is_none());
    }
}
