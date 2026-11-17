//! Shared workspace catalog transactions, independent of the desktop lifecycle.

use crate::host::{
    absolute_path, env_or, harden_private_file, load_domain_state, pid_alive, replace_file,
    runtime_workspace_id, same_workspace, strip_verbatim, user_data_dir,
};
use chrono::SecondsFormat;
use chrono::Utc;
use serde_json::json;
use serde_json::Value;
use std::io::Write;
use std::path::Path;
use std::path::PathBuf;
use std::time::Duration;
use std::time::Instant;
use std::time::SystemTime;

/// Workspace-owned Runtime data. An explicit HARNEX_DATA_DIR wins; otherwise every
/// workspace uses its registered data directory or `<project>/.harnex/data`.
pub(crate) fn workspace_data_dir(root: &Path) -> PathBuf {
    if let Some(d) = env_or("HARNEX_DATA_DIR") {
        return absolute_path(root.join(d));
    }
    let id = runtime_workspace_id(root);
    if let Some(configured) = load_workspace_registry()["workspaces"]
        .as_array()
        .and_then(|items| items.iter().find(|item| item["id"].as_str() == Some(&id)))
        .and_then(|item| item["dataDir"].as_str())
        .filter(|value| !value.is_empty())
    {
        return absolute_path(root.join(configured));
    }
    root.join(".harnex").join("data")
}

fn workspace_registry_path() -> PathBuf {
    user_data_dir().join("catalog").join("workspaces.json")
}

pub(crate) fn load_workspace_registry() -> Value {
    load_workspace_registry_checked().unwrap_or_else(|_| empty_workspace_registry())
}

fn empty_workspace_registry() -> Value {
    json!({ "schemaVersion": 1, "workspaces": [] })
}

fn read_workspace_registry_file(path: &Path) -> Result<Value, String> {
    let body = std::fs::read_to_string(path).map_err(|error| error.to_string())?;
    let value: Value = serde_json::from_str(&body).map_err(|error| error.to_string())?;
    if !value.is_object() || value.get("workspaces").and_then(Value::as_array).is_none() {
        return Err(format!(
            "invalid workspace catalog shape: {}",
            path.display()
        ));
    }
    Ok(value)
}

fn load_workspace_registry_checked() -> Result<Value, String> {
    let path = workspace_registry_path();
    if !path.exists() {
        return Ok(empty_workspace_registry());
    }
    read_workspace_registry_file(&path).or_else(|primary_error| {
        let backup = PathBuf::from(format!("{}.bak", path.to_string_lossy()));
        read_workspace_registry_file(&backup).map_err(|backup_error| {
            format!("workspace catalog and backup are invalid ({primary_error}; {backup_error})")
        })
    })
}

fn write_workspace_registry_unlocked(registry: &Value) -> Result<(), String> {
    let path = workspace_registry_path();
    if let Some(parent) = path.parent() {
        std::fs::create_dir_all(parent).map_err(|error| error.to_string())?;
    }
    let body = format!(
        "{}\n",
        serde_json::to_string_pretty(registry).map_err(|error| error.to_string())?
    );
    let nonce = Utc::now().timestamp_nanos_opt().unwrap_or_default();
    let temporary = PathBuf::from(format!(
        "{}.{}.{}.tmp",
        path.to_string_lossy(),
        std::process::id(),
        nonce
    ));
    if path.is_file() && read_workspace_registry_file(&path).is_ok() {
        let backup = PathBuf::from(format!("{}.bak", path.to_string_lossy()));
        let backup_temporary = PathBuf::from(format!(
            "{}.{}.{}.tmp",
            backup.to_string_lossy(),
            std::process::id(),
            nonce
        ));
        std::fs::copy(&path, &backup_temporary).map_err(|error| error.to_string())?;
        harden_private_file(&backup_temporary);
        if let Err(error) = replace_file(&backup_temporary, &backup) {
            let _ = std::fs::remove_file(&backup_temporary);
            return Err(error);
        }
        harden_private_file(&backup);
    }
    let result = std::fs::OpenOptions::new()
        .write(true)
        .create_new(true)
        .open(&temporary)
        .and_then(|mut file| {
            file.write_all(body.as_bytes())?;
            file.sync_all()
        })
        .map_err(|error| error.to_string())
        .and_then(|_| {
            harden_private_file(&temporary);
            replace_file(&temporary, &path)?;
            harden_private_file(&path);
            Ok(())
        });
    if result.is_err() {
        let _ = std::fs::remove_file(&temporary);
    }
    result
}

pub(crate) fn update_workspace_registry<F>(update: F) -> Result<Value, String>
where
    F: FnOnce(&mut Value) -> Result<(), String>,
{
    let target = workspace_registry_path();
    if let Some(parent) = target.parent() {
        std::fs::create_dir_all(parent).map_err(|e| e.to_string())?;
    }
    let lock_path = PathBuf::from(format!("{}.lock", target.to_string_lossy()));
    let deadline = Instant::now() + Duration::from_secs(5);
    loop {
        match std::fs::OpenOptions::new()
            .write(true)
            .create_new(true)
            .open(&lock_path)
        {
            Ok(mut lock) => {
                let lease =
                    json!({ "pid": std::process::id(), "acquiredAt": Utc::now().to_rfc3339() });
                if let Err(error) = write!(lock, "{lease}").and_then(|_| lock.sync_all()) {
                    drop(lock);
                    let _ = std::fs::remove_file(&lock_path);
                    return Err(error.to_string());
                }
                let result = (|| {
                    let mut registry = load_workspace_registry_checked()?;
                    update(&mut registry)?;
                    registry["schemaVersion"] = json!(1);
                    let revision = registry
                        .get("revision")
                        .and_then(Value::as_u64)
                        .unwrap_or_default()
                        + 1;
                    registry["revision"] = json!(revision);
                    write_workspace_registry_unlocked(&registry)?;
                    Ok(registry)
                })();
                drop(lock);
                let _ = std::fs::remove_file(&lock_path);
                return result;
            }
            Err(error) if error.kind() == std::io::ErrorKind::AlreadyExists => {
                let owner_pid = std::fs::read_to_string(&lock_path)
                    .ok()
                    .and_then(|body| serde_json::from_str::<Value>(&body).ok())
                    .and_then(|value| value.get("pid").and_then(Value::as_u64));
                let stale = owner_pid.is_none_or(|pid| !pid_alive(pid as u32));
                let old_enough = std::fs::metadata(&lock_path)
                    .ok()
                    .and_then(|meta| meta.modified().ok())
                    .and_then(|time| SystemTime::now().duration_since(time).ok())
                    .is_some_and(|age| age >= Duration::from_secs(2));
                if stale && old_enough {
                    let _ = std::fs::remove_file(&lock_path);
                    continue;
                }
                if Instant::now() >= deadline {
                    return Err(format!(
                        "workspace catalog lock timeout: {}",
                        lock_path.display()
                    ));
                }
                std::thread::sleep(Duration::from_millis(25));
            }
            Err(error) => return Err(error.to_string()),
        }
    }
}

pub(crate) fn load_domain_last_workspace() -> Option<PathBuf> {
    let id = load_domain_state()
        .get("lastWorkspaceId")
        .and_then(Value::as_str)?
        .to_string();
    load_workspace_registry()
        .get("workspaces")
        .and_then(Value::as_array)
        .and_then(|entries| {
            entries.iter().find(|entry| {
                entry.get("id").and_then(Value::as_str) == Some(&id)
                    && !entry["remoteProfile"].is_string()
            })
        })
        .and_then(|entry| entry.get("root"))
        .and_then(Value::as_str)
        .map(PathBuf::from)
        .filter(|path| path.is_dir())
}

/// Record `root` in the same registry used by the Node Supervisor. The desktop
/// shell owns no second recent-project format and does no historical migration.
pub(crate) fn record_workspace(root: &Path, opened: bool) -> Result<(), String> {
    let absolute =
        strip_verbatim(std::fs::canonicalize(root).unwrap_or_else(|_| root.to_path_buf()));
    let root_text = absolute.to_string_lossy().to_string();
    let id = runtime_workspace_id(&absolute);
    let now = Utc::now().to_rfc3339_opts(SecondsFormat::Millis, true);
    update_workspace_registry(|registry| {
        if registry
            .get("workspaces")
            .and_then(Value::as_array)
            .is_none()
        {
            registry["workspaces"] = json!([]);
        }
        let entries = registry["workspaces"].as_array_mut().unwrap();
        if let Some(entry) = entries.iter_mut().find(|entry| {
            if entry["remoteProfile"].is_string() {
                return false;
            }
            entry
                .get("root")
                .and_then(Value::as_str)
                .map(|candidate| same_workspace(Path::new(candidate), &absolute))
                .unwrap_or(false)
        }) {
            if opened {
                entry["lastOpened"] = json!(now);
            }
        } else {
            let name = absolute
                .file_name()
                .map(|part| part.to_string_lossy().to_string())
                .unwrap_or_else(|| root_text.clone());
            entries.push(json!({
                "id": id.clone(), "name": name, "root": root_text,
                "dataDir": workspace_data_dir(&absolute).to_string_lossy().to_string(),
                "createdAt": now.clone(), "lastOpened": if opened { json!(now.clone()) } else { Value::Null }
            }));
        }
        Ok(())
    })?;
    Ok(())
}

pub(crate) fn record_remote_retained(id: &str, retained: bool) -> Result<(), String> {
    update_workspace_registry(|registry| {
        if let Some(item) = registry["workspaces"].as_array_mut().and_then(|items| {
            items
                .iter_mut()
                .find(|item| item["id"].as_str() == Some(id))
        }) {
            item["remoteRetainedAt"] = if retained {
                json!(Utc::now().to_rfc3339())
            } else {
                Value::Null
            };
        }
        Ok(())
    })?;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::time::{SystemTime, UNIX_EPOCH};

    #[test]
    fn concurrent_catalog_updates_merge_without_runtime_state_fields() {
        let nonce = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap()
            .as_nanos();
        let base = std::env::temp_dir().join(format!(
            "harnex-catalog-rust-{}-{nonce}",
            std::process::id()
        ));
        let user = base.join("user");
        let previous = std::env::var_os("HARNEX_USER_DATA_DIR");
        std::env::set_var("HARNEX_USER_DATA_DIR", &user);
        let roots = (0..8)
            .map(|index| {
                let root = base.join(format!("workspace-{index}"));
                std::fs::create_dir_all(&root).unwrap();
                root
            })
            .collect::<Vec<_>>();
        let threads = roots
            .into_iter()
            .map(|root| std::thread::spawn(move || record_workspace(&root, true)))
            .collect::<Vec<_>>();
        for thread in threads {
            thread.join().unwrap().unwrap();
        }
        let catalog = load_workspace_registry();
        assert_eq!(
            8,
            catalog["workspaces"].as_array().unwrap().len(),
            "lease lock must retain every keyed update"
        );
        assert_eq!(8, catalog["revision"].as_u64().unwrap());
        assert!(catalog.get("activeWorkspaceId").is_none());
        assert!(catalog.get("lastWorkspaceId").is_none());
        assert!(workspace_registry_path()
            .with_file_name("workspaces.json.bak")
            .is_file());

        if let Some(value) = previous {
            std::env::set_var("HARNEX_USER_DATA_DIR", value);
        } else {
            std::env::remove_var("HARNEX_USER_DATA_DIR");
        }
        std::fs::remove_dir_all(&base).unwrap();
    }
}
