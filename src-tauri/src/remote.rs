//! Remote connection settings and SSH discovery command adapters.

use crate::bundle::bundled_runtime;
use crate::catalog::update_workspace_registry;
use crate::host::{env_or, user_data_dir};
use crate::state::CurrentRemote;
use chrono::Utc;
use serde_json::json;
use serde_json::Value;
use std::path::PathBuf;
use tauri::AppHandle;
use tauri::Manager;
use tauri_plugin_dialog::DialogExt;

pub(crate) fn connection_store() -> harnex_host_runtime::ProfileStore {
    harnex_host_runtime::ProfileStore::new(user_data_dir())
}

pub(crate) fn connection_jar(app: &AppHandle) -> Result<PathBuf, String> {
    if let Some(bundle) = bundled_runtime(app)? {
        return Ok(bundle.jar);
    }
    env_or("HARNEX_JAR")
        .map(PathBuf::from)
        .filter(|path| path.is_file())
        .ok_or_else(|| "packaged Runtime JAR is required for remote connections".into())
}

#[tauri::command]
pub(crate) fn remote_connections() -> Result<Value, String> {
    serde_json::to_value(connection_store().list()?).map_err(|error| error.to_string())
}

#[tauri::command]
pub(crate) fn remote_connection_save(profile: Value) -> Result<(), String> {
    connection_store().save(serde_json::from_value(profile).map_err(|error| error.to_string())?)
}

#[tauri::command]
pub(crate) fn remote_connection_remove(app: AppHandle, id: String) -> Result<(), String> {
    if app
        .state::<CurrentRemote>()
        .0
        .lock()
        .unwrap()
        .as_ref()
        .is_some_and(|(profile, _)| profile == &id)
    {
        return Err("close the active remote workspace before removing its connection".into());
    }
    connection_store().remove(&id)
}

#[tauri::command]
pub(crate) async fn remote_inspect(
    id: String,
    operation: String,
    root: Option<String>,
) -> Result<Value, String> {
    tauri::async_runtime::spawn_blocking(move || {
        harnex_host_runtime::ssh::inspect(
            &connection_store().get(&id)?,
            &operation,
            root.as_deref(),
        )
    })
    .await
    .map_err(|error| error.to_string())?
}

#[tauri::command]
pub(crate) async fn remote_interaction(
    app: AppHandle,
    action: String,
    args: Value,
) -> Result<Value, String> {
    tauri::async_runtime::spawn_blocking(move || {
        let jar = if args["operation"] == "prepare" {
            Some(connection_jar(&app)?)
        } else {
            None
        };
        harnex_host_runtime::interaction::global()?.request(&action, args, user_data_dir(), jar)
    })
    .await
    .map_err(|error| error.to_string())?
}

#[tauri::command]
pub(crate) async fn remote_identity_pick(app: AppHandle) -> Result<Option<String>, String> {
    tauri::async_runtime::spawn_blocking(move || {
        app.dialog()
            .file()
            .blocking_pick_file()
            .map(|file| {
                file.into_path()
                    .map(|path| path.to_string_lossy().into_owned())
                    .map_err(|e| e.to_string())
            })
            .transpose()
    })
    .await
    .map_err(|error| error.to_string())?
}

#[tauri::command]
pub(crate) async fn remote_workspace_register(
    app: AppHandle,
    profile_id: String,
    root: String,
    preparation_id: Option<String>,
) -> Result<Value, String> {
    tauri::async_runtime::spawn_blocking(move || {
        let profile = connection_store().get(&profile_id)?;
        let described = if let Some(id) = preparation_id {
            harnex_host_runtime::interaction::global()?.request("prepared", json!({"id":id,"profileId":profile_id,"root":root}), user_data_dir(), None)?
        } else { harnex_host_runtime::inspect(&profile, &connection_jar(&app)?, "describe", Some(&root))? };
        let id = described.get("catalogId").unwrap_or(&described["workspaceId"]).as_str().ok_or("remote workspace identity missing")?.to_string();
        let root = described["root"].as_str().ok_or("remote workspace root missing")?.to_string();
        let now = Utc::now().to_rfc3339();
        let mut item = json!({"id":id,"root":root,"remoteProfile":profile_id,"remoteHost":profile.endpoint_label(),"name":root.rsplit('/').find(|part| !part.is_empty()).unwrap_or("/"),
            "createdAt":now,"lastOpened":Value::Null});
        if described["service"].is_object() { item["remoteService"] = described["service"].clone(); item["runtimeScopeId"] = described["workspaceId"].clone(); }
        let mut registered = item.clone();
        update_workspace_registry(|registry| {
            let entries = registry["workspaces"].as_array_mut().ok_or("invalid workspace catalog")?;
            if let Some(old) = entries.iter_mut().find(|entry| entry["id"] == id) {
                old["remoteProfile"] = item["remoteProfile"].clone();
                old["remoteHost"] = item["remoteHost"].clone();
                old["root"] = item["root"].clone();
                if item["remoteService"].is_object() { old["remoteService"] = item["remoteService"].clone(); old["runtimeScopeId"] = item["runtimeScopeId"].clone(); }
                registered = old.clone();
            }
            else { entries.push(item.clone()); }
            Ok(())
        })?;
        registered["state"] = json!("CLOSED"); registered["isActive"] = json!(false); registered["instanceId"] = Value::Null;
        Ok(registered)
    }).await.map_err(|error| error.to_string())?
}
