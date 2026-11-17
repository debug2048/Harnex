//! Provider credentials and native Keychain integration.

#[cfg(target_os = "macos")]
use crate::host::{atomic_write_json, harden_private_file, shell_log, user_data_dir};
#[cfg(target_os = "macos")]
use serde_json::json;
use serde_json::Value;
#[cfg(any(target_os = "macos", test))]
use sha2::Digest;
#[cfg(any(target_os = "macos", test))]
use sha2::Sha256;
#[cfg(target_os = "macos")]
use std::path::Path;
#[cfg(target_os = "macos")]
use std::path::PathBuf;
#[cfg(target_os = "macos")]
use std::process::Command;
#[cfg(target_os = "macos")]
use std::sync::Mutex;
#[cfg(target_os = "macos")]
use std::sync::OnceLock;

#[cfg(target_os = "macos")]
fn provider_secret_index_path() -> PathBuf {
    user_data_dir().join("keychain-providers.json")
}

#[cfg(any(target_os = "macos", test))]
fn provider_secret_env(provider_id: &str) -> String {
    let digest = Sha256::digest(provider_id.as_bytes());
    const PREFIX: &str = "HARNEX_KEYCHAIN_PROVIDER_";
    let suffix_len = 42usize.saturating_sub(PREFIX.len());
    let suffix = format!("{:X}", digest)
        .chars()
        .take(suffix_len)
        .collect::<String>();
    format!("{PREFIX}{suffix}")
}

fn valid_provider_secret_id(value: &str) -> bool {
    // Image credentials occupy a separate namespace from text connections.
    let value = value.strip_prefix("image:").unwrap_or(value);
    !value.is_empty()
        && value.len() <= 64
        && value
            .chars()
            .enumerate()
            .all(|(index, ch)| ch.is_ascii_alphanumeric() || (index > 0 && matches!(ch, '_' | '-')))
}

#[cfg(target_os = "macos")]
fn provider_secret_ids() -> Result<Vec<String>, String> {
    let body = match std::fs::read_to_string(provider_secret_index_path()) {
        Ok(body) => body,
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => return Ok(Vec::new()),
        Err(error) => return Err(format!("cannot read Keychain index: {error}")),
    };
    let value: Value =
        serde_json::from_str(&body).map_err(|error| format!("invalid Keychain index: {error}"))?;
    let providers = value
        .get("providers")
        .and_then(Value::as_array)
        .ok_or_else(|| "invalid Keychain index providers".to_string())?;
    providers
        .iter()
        .map(|value| {
            value
                .as_str()
                .filter(|id| valid_provider_secret_id(id))
                .map(ToOwned::to_owned)
                .ok_or_else(|| "invalid provider in Keychain index".to_string())
        })
        .collect()
}

#[cfg(target_os = "macos")]
fn provider_secret_lock() -> &'static Mutex<()> {
    static LOCK: OnceLock<Mutex<()>> = OnceLock::new();
    LOCK.get_or_init(|| Mutex::new(()))
}

#[cfg(target_os = "macos")]
fn write_provider_secret_ids(ids: &[String]) -> Result<(), String> {
    let mut sorted = ids.to_vec();
    sorted.sort();
    sorted.dedup();
    atomic_write_json(
        &provider_secret_index_path(),
        &json!({ "schemaVersion": 1, "providers": sorted }),
    )?;
    harden_private_file(&provider_secret_index_path());
    Ok(())
}

#[tauri::command]
pub(crate) fn provider_secret_store(provider_id: String, secret: String) -> Result<Value, String> {
    if !valid_provider_secret_id(&provider_id) {
        return Err("invalid provider id".to_string());
    }
    if secret.trim().is_empty() || secret.len() > 32_768 {
        return Err("provider secret must be non-empty and at most 32 KiB".to_string());
    }
    #[cfg(target_os = "macos")]
    {
        let _guard = provider_secret_lock()
            .lock()
            .map_err(|_| "provider secret index lock is poisoned".to_string())?;
        let mut ids = provider_secret_ids()?;
        let indexed = ids.contains(&provider_id);
        let entry = keyring::Entry::new("com.harnex.provider", &provider_id)
            .map_err(|error| error.to_string())?;
        let previous = match entry.get_password() {
            Ok(value) => Some(value),
            Err(keyring::Error::NoEntry) => None,
            Err(error) => return Err(error.to_string()),
        };
        entry
            .set_password(&secret)
            .map_err(|error| error.to_string())?;
        if !indexed {
            ids.push(provider_id.clone());
            if let Err(index_error) = write_provider_secret_ids(&ids) {
                let compensation = match previous.as_ref() {
                    Some(value) => entry.set_password(value),
                    None => entry.delete_credential(),
                };
                let rollback = match compensation {
                    Ok(()) | Err(keyring::Error::NoEntry) => {
                        "rolled back Keychain entry".to_string()
                    }
                    Err(error) => format!("Keychain rollback also failed: {error}"),
                };
                return Err(format!(
                    "provider secret index update failed: {index_error}; {rollback}"
                ));
            }
        }
        let env = provider_secret_env(&provider_id);
        return Ok(json!({ "stored": true, "reference": format!("${{{env}:}}") }));
    }
    #[cfg(not(target_os = "macos"))]
    {
        let _ = secret;
        Err("native provider secret storage is available on macOS only".to_string())
    }
}

#[tauri::command]
pub(crate) fn provider_secret_reveal(provider_id: String) -> Result<Value, String> {
    if !valid_provider_secret_id(&provider_id) {
        return Err("invalid provider id".to_string());
    }
    #[cfg(target_os = "macos")]
    {
        let secret = keyring::Entry::new("com.harnex.provider", &provider_id)
            .map_err(|error| error.to_string())?
            .get_password()
            .map_err(|error| error.to_string())?;
        return Ok(json!({ "apiKey": secret }));
    }
    #[cfg(not(target_os = "macos"))]
    Err("native provider secret storage is available on macOS only".to_string())
}

#[tauri::command]
pub(crate) fn provider_secret_delete(provider_id: String) -> Result<Value, String> {
    if !valid_provider_secret_id(&provider_id) {
        return Err("invalid provider id".to_string());
    }
    #[cfg(target_os = "macos")]
    {
        let _guard = provider_secret_lock()
            .lock()
            .map_err(|_| "provider secret index lock is poisoned".to_string())?;
        let mut ids = provider_secret_ids()?;
        let entry = keyring::Entry::new("com.harnex.provider", &provider_id)
            .map_err(|error| error.to_string())?;
        let previous = match entry.get_password() {
            Ok(value) => Some(value),
            Err(keyring::Error::NoEntry) => None,
            Err(error) => return Err(error.to_string()),
        };
        // A missing credential already satisfies delete semantics.
        match entry.delete_credential() {
            Ok(()) | Err(keyring::Error::NoEntry) => {}
            Err(error) => return Err(format!("provider secret deletion failed: {error}")),
        }
        ids.retain(|id| id != &provider_id);
        if let Err(error) = write_provider_secret_ids(&ids) {
            if let Some(previous) = previous {
                entry.set_password(&previous).map_err(|rollback| {
                    format!(
                        "index update failed: {error}; credential restoration failed: {rollback}"
                    )
                })?;
            }
            return Err(error);
        }
        return Ok(json!({ "deleted": true }));
    }
    #[cfg(not(target_os = "macos"))]
    Err("native provider secret storage is available on macOS only".to_string())
}

#[cfg(target_os = "macos")]
pub(crate) fn inject_keychain_provider_secrets(command: &mut Command, log: &Path) {
    let _guard = match provider_secret_lock().lock() {
        Ok(guard) => guard,
        Err(_) => {
            shell_log(log, "Keychain index lock unavailable");
            return;
        }
    };
    let mut injected = 0;
    let ids = match provider_secret_ids() {
        Ok(ids) => ids,
        Err(error) => {
            shell_log(log, &error);
            return;
        }
    };
    for provider_id in ids {
        let Ok(entry) = keyring::Entry::new("com.harnex.provider", &provider_id) else {
            continue;
        };
        let Ok(secret) = entry.get_password() else {
            shell_log(
                log,
                &format!("[keychain] secret unavailable for provider={provider_id}"),
            );
            continue;
        };
        command.env(provider_secret_env(&provider_id), secret);
        injected += 1;
    }
    shell_log(log, &format!("[keychain] injectedProviders={injected}"));
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn provider_keychain_reference_uses_safe_deterministic_environment_name() {
        let first = provider_secret_env("openai-primary");
        assert_eq!(first, provider_secret_env("openai-primary"));
        assert_ne!(first, provider_secret_env("openai-secondary"));
        assert!(first.starts_with("HARNEX_KEYCHAIN_PROVIDER_"));
        assert!(first.chars().all(|character| character.is_ascii_uppercase()
            || character.is_ascii_digit()
            || character == '_'));
        assert!(valid_provider_secret_id("openai-primary"));
        assert!(valid_provider_secret_id("image:openai-primary"));
        assert!(!valid_provider_secret_id("image:../openai"));
        assert!(!valid_provider_secret_id("image:"));
        assert_ne!(provider_secret_env("image:openai-primary"), first);
        assert!(!valid_provider_secret_id("../openai"));
    }
}
