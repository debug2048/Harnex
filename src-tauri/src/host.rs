//! Desktop host identity, runtime-domain persistence and filesystem primitives.

use chrono::SecondsFormat;
use chrono::Utc;
use serde_json::json;
use serde_json::Value;
use sha2::Digest;
use sha2::Sha256;
use std::io::Write;
use std::path::Path;
use std::path::PathBuf;
use std::process::Command;
use std::sync::Mutex;
use std::sync::OnceLock;
use std::time::Duration;
use std::time::SystemTime;

pub(crate) fn env_or(key: &str) -> Option<String> {
    std::env::var(key).ok().filter(|v| !v.trim().is_empty())
}

fn home_dir() -> PathBuf {
    #[cfg(windows)]
    {
        if let Some(p) = env_or("USERPROFILE") {
            return PathBuf::from(p);
        }
    }
    if let Some(p) = env_or("HOME") {
        return PathBuf::from(p);
    }
    std::env::temp_dir()
}

pub(crate) fn user_data_dir() -> PathBuf {
    let configured = env_or("HARNEX_USER_DATA_DIR")
        .map(PathBuf::from)
        .unwrap_or_else(|| home_dir().join(".harnex"));
    absolute_path(configured)
}

pub(crate) fn absolute_path(path: PathBuf) -> PathBuf {
    let absolute = if path.is_absolute() {
        path
    } else {
        std::env::current_dir()
            .unwrap_or_else(|_| PathBuf::from("."))
            .join(path)
    };
    let mut normalized = PathBuf::new();
    for component in absolute.components() {
        match component {
            std::path::Component::CurDir => {}
            std::path::Component::ParentDir => {
                normalized.pop();
            }
            other => normalized.push(other.as_os_str()),
        }
    }
    normalized
}

pub(crate) fn runtime_domain_id() -> String {
    let value = env_or("HARNEX_RUNTIME_DOMAIN_ID").unwrap_or_else(|| "tauri-main".to_string());
    let valid = !value.is_empty()
        && value.len() <= 128
        && value.chars().enumerate().all(|(index, ch)| {
            ch.is_ascii_alphanumeric() || (index > 0 && matches!(ch, '.' | '_' | '-'))
        });
    if !valid {
        panic!("invalid HARNEX_RUNTIME_DOMAIN_ID: {value}");
    }
    value
}

pub(crate) fn runtime_domain_dir() -> PathBuf {
    let configured = env_or("HARNEX_RUNTIME_DOMAIN_DIR")
        .map(PathBuf::from)
        .unwrap_or_else(|| {
            user_data_dir()
                .join("runtime-domains")
                .join(runtime_domain_id())
        });
    let result = absolute_path(configured);
    let user_root = user_data_dir();
    let same_as_user_root = result == user_root
        || match (
            std::fs::canonicalize(&result),
            std::fs::canonicalize(&user_root),
        ) {
            (Ok(domain), Ok(user)) => domain == user,
            _ => false,
        };
    if same_as_user_root {
        panic!("runtime domain directory must not equal user data directory");
    }
    result
}

pub(crate) fn domain_manifest_path() -> PathBuf {
    runtime_domain_dir().join("domain.json")
}

fn domain_boot_token_path() -> PathBuf {
    runtime_domain_dir().join("boot-token")
}

fn domain_owner_path() -> PathBuf {
    runtime_domain_dir().join("owner.json")
}

fn domain_state_path() -> PathBuf {
    runtime_domain_dir().join("state.json")
}

pub(crate) fn owner_instance_id() -> &'static str {
    static OWNER: OnceLock<String> = OnceLock::new();
    OWNER.get_or_init(|| format!("tauri-owner-{}", runtime_instance_id()))
}

/// Domain-owned shell and Runtime diagnostics.
pub(crate) fn shell_log_dir() -> PathBuf {
    runtime_domain_dir().join("logs")
}

pub(crate) fn harden_private_file(path: &Path) {
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        let _ = std::fs::set_permissions(path, std::fs::Permissions::from_mode(0o600));
    }
    #[cfg(not(unix))]
    let _ = path;
}

pub(crate) fn pid_alive(pid: u32) -> bool {
    if pid == 0 {
        return false;
    }
    if pid == std::process::id() {
        return true;
    }
    #[cfg(windows)]
    {
        use std::ffi::c_void;
        #[link(name = "Kernel32")]
        extern "system" {
            fn OpenProcess(access: u32, inherit: i32, process_id: u32) -> *mut c_void;
            fn GetExitCodeProcess(process: *mut c_void, exit_code: *mut u32) -> i32;
            fn CloseHandle(object: *mut c_void) -> i32;
        }
        const PROCESS_QUERY_LIMITED_INFORMATION: u32 = 0x1000;
        const STILL_ACTIVE: u32 = 259;
        const ERROR_INVALID_PARAMETER: i32 = 87;
        let handle = unsafe { OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, 0, pid) };
        if handle.is_null() {
            // Access-denied is not proof of death; only Windows' unknown-PID result permits
            // stale-lease cleanup. This keeps takeover conservative across privilege levels.
            return std::io::Error::last_os_error().raw_os_error() != Some(ERROR_INVALID_PARAMETER);
        }
        let mut exit_code = 0;
        let queried = unsafe { GetExitCodeProcess(handle, &mut exit_code) } != 0;
        unsafe {
            CloseHandle(handle);
        }
        !queried || exit_code == STILL_ACTIVE
    }
    #[cfg(not(windows))]
    {
        Command::new("kill")
            .args(["-0", &pid.to_string()])
            .status()
            .is_ok_and(|s| s.success())
    }
}

pub(crate) fn load_domain_state() -> Value {
    std::fs::read_to_string(domain_state_path())
        .ok()
        .and_then(|body| serde_json::from_str(&body).ok())
        .unwrap_or_else(|| {
            json!({
                "schemaVersion": 1, "runtimeDomainId": runtime_domain_id(), "lastWorkspaceId": null
            })
        })
}

pub(crate) fn set_domain_last(workspace_id: Option<&str>) {
    update_domain_state(|value| {
        value["lastWorkspaceId"] = workspace_id.map_or(Value::Null, |id| json!(id));
    });
}

pub(crate) fn update_domain_state<F>(update: F)
where
    F: FnOnce(&mut Value),
{
    static DOMAIN_STATE_LOCK: OnceLock<Mutex<()>> = OnceLock::new();
    let _guard = DOMAIN_STATE_LOCK
        .get_or_init(|| Mutex::new(()))
        .lock()
        .unwrap();
    let mut value = load_domain_state();
    value["schemaVersion"] = json!(1);
    value["runtimeDomainId"] = json!(runtime_domain_id());
    update(&mut value);
    value["updatedAt"] = json!(Utc::now().to_rfc3339_opts(SecondsFormat::Millis, true));
    let _ = atomic_write_json(&domain_state_path(), &value);
}

pub(crate) fn atomic_write_json(path: &Path, value: &Value) -> Result<(), String> {
    if let Some(parent) = path.parent() {
        std::fs::create_dir_all(parent).map_err(|e| e.to_string())?;
    }
    let nonce = Utc::now().timestamp_nanos_opt().unwrap_or_default();
    let temporary = PathBuf::from(format!(
        "{}.{}.{}.tmp",
        path.to_string_lossy(),
        std::process::id(),
        nonce
    ));
    let body = format!(
        "{}\n",
        serde_json::to_string_pretty(value).map_err(|e| e.to_string())?
    );
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
            replace_file(&temporary, path)
        });
    if result.is_err() {
        let _ = std::fs::remove_file(&temporary);
    }
    result
}

#[cfg(not(windows))]
pub(crate) fn replace_file(source: &Path, target: &Path) -> Result<(), String> {
    std::fs::rename(source, target).map_err(|error| error.to_string())
}

#[cfg(windows)]
pub(crate) fn replace_file(source: &Path, target: &Path) -> Result<(), String> {
    use std::os::windows::ffi::OsStrExt;
    #[link(name = "Kernel32")]
    extern "system" {
        fn MoveFileExW(existing: *const u16, replacement: *const u16, flags: u32) -> i32;
    }
    const MOVEFILE_REPLACE_EXISTING: u32 = 0x1;
    const MOVEFILE_WRITE_THROUGH: u32 = 0x8;
    let from: Vec<u16> = source.as_os_str().encode_wide().chain(Some(0)).collect();
    let to: Vec<u16> = target.as_os_str().encode_wide().chain(Some(0)).collect();
    let ok = unsafe {
        MoveFileExW(
            from.as_ptr(),
            to.as_ptr(),
            MOVEFILE_REPLACE_EXISTING | MOVEFILE_WRITE_THROUGH,
        )
    };
    if ok != 0 {
        Ok(())
    } else {
        Err(std::io::Error::last_os_error().to_string())
    }
}

pub(crate) fn claim_domain_owner() -> Result<(), String> {
    let dir = runtime_domain_dir();
    std::fs::create_dir_all(&dir).map_err(|e| e.to_string())?;
    harden_domain_dir(&dir);
    let path = domain_owner_path();
    let owner = json!({
        "schemaVersion": 1, "runtimeDomainId": runtime_domain_id(), "ownerKind": "tauri",
        "ownerPid": std::process::id(), "ownerInstanceId": owner_instance_id(),
        "startedAt": Utc::now().to_rfc3339_opts(SecondsFormat::Millis, true)
    });
    match std::fs::OpenOptions::new()
        .write(true)
        .create_new(true)
        .open(&path)
    {
        Ok(mut file) => writeln!(file, "{}", serde_json::to_string_pretty(&owner).unwrap())
            .map_err(|e| e.to_string()),
        Err(error) if error.kind() == std::io::ErrorKind::AlreadyExists => {
            let current = std::fs::read_to_string(&path)
                .ok()
                .and_then(|body| serde_json::from_str::<Value>(&body).ok())
                .unwrap_or(Value::Null);
            let pid = current
                .get("ownerPid")
                .and_then(Value::as_u64)
                .unwrap_or_default() as u32;
            let old_enough = std::fs::metadata(&path)
                .ok()
                .and_then(|meta| meta.modified().ok())
                .and_then(|time| SystemTime::now().duration_since(time).ok())
                .is_some_and(|age| age >= Duration::from_secs(2));
            if !pid_alive(pid) && old_enough {
                for stale in [
                    path.clone(),
                    domain_manifest_path(),
                    domain_boot_token_path(),
                ] {
                    let _ = std::fs::remove_file(stale);
                }
                return claim_domain_owner();
            }
            Err(format!(
                "Runtime Domain {} is already owned by pid {pid}; stop it or retry",
                runtime_domain_id()
            ))
        }
        Err(error) => Err(error.to_string()),
    }
}

fn harden_domain_dir(dir: &Path) {
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        let _ = std::fs::set_permissions(dir, std::fs::Permissions::from_mode(0o700));
    }
    #[cfg(windows)]
    if let Some(user) = env_or("USERNAME") {
        let grant = format!("{user}:(OI)(CI)F");
        let _ = Command::new("icacls.exe")
            .arg(dir)
            .args(["/inheritance:r", "/grant:r", &grant])
            .output();
    }
}

pub(crate) fn release_domain_owner() {
    let path = domain_owner_path();
    let ours = std::fs::read_to_string(&path)
        .ok()
        .and_then(|body| serde_json::from_str::<Value>(&body).ok())
        .is_some_and(|owner| {
            owner.get("ownerPid").and_then(Value::as_u64) == Some(std::process::id() as u64)
                && owner.get("ownerInstanceId").and_then(Value::as_str) == Some(owner_instance_id())
        });
    if ours {
        let _ = std::fs::remove_file(path);
    }
}

pub(crate) fn unpublish_domain_manifest() {
    let _ = std::fs::remove_file(domain_manifest_path());
}

fn config_revision() -> String {
    let mut hash = Sha256::new();
    for name in ["agent-config.yml", "application.yml"] {
        let file = user_data_dir().join(name);
        if let Ok(bytes) = std::fs::read(&file) {
            hash.update(name.as_bytes());
            hash.update(bytes);
        }
    }
    format!("{:x}", hash.finalize())
}

pub(crate) fn publish_domain_manifest(
    root: &Path,
    workspace_id: &str,
    instance_id: &str,
    runtime_pid: u32,
    port: u16,
) -> Result<(), String> {
    let canonical = strip_verbatim(std::fs::canonicalize(root).map_err(|e| e.to_string())?);
    let manifest = json!({
        "schemaVersion": 3, "runtimeProfile": "PROJECT",
        "runtimeDomainId": runtime_domain_id(),
        "ownerKind": "tauri",
        "ownerPid": std::process::id(),
        "ownerInstanceId": owner_instance_id(),
        "runtimePid": runtime_pid,
        "canonicalRoot": canonical.to_string_lossy(),
        "scopeId": workspace_id,
        "instanceId": instance_id,
        "endpoint": format!("http://127.0.0.1:{port}"),
        "lifecycle": "READY",
        "protocolVersion": "1.0",
        "bootTokenFile": domain_boot_token_path().to_string_lossy(),
        "configRevision": config_revision(),
        "publishedAt": Utc::now().to_rfc3339_opts(SecondsFormat::Millis, true)
    });
    atomic_write_json(&domain_manifest_path(), &manifest)
}

/// Path equality that tolerates the differences between a freshly-picked folder and a stored
/// recent entry (case on Windows, trailing separators, `.`/`..`, symlinks). Falls back to a raw
/// compare when canonicalisation fails (e.g. the dir was since deleted).
pub(crate) fn same_workspace(a: &Path, b: &Path) -> bool {
    match (std::fs::canonicalize(a), std::fs::canonicalize(b)) {
        (Ok(ca), Ok(cb)) => ca == cb,
        _ => a == b,
    }
}

/// Append a line to the desktop shell diagnostics log in the data dir. Best-effort.
/// This is the *only* place to look when the splash hangs: it records exactly which
/// java/jar paths were resolved and whether the JVM spawn succeeded.
pub(crate) fn shell_log(dir: &Path, msg: &str) {
    let p = dir.join("desktop-shell.log");
    if let Ok(mut f) = std::fs::OpenOptions::new()
        .create(true)
        .append(true)
        .open(p)
    {
        let _ = writeln!(f, "{msg}");
    }
}

/// Strip the Windows `\\?\` verbatim (extended-length) prefix that `current_exe()` returns.
/// The JVM cannot open a `-jar \\?\D:\...jar` argument, so all paths handed to `java` must
/// be plain. No-op on non-Windows and on paths without the prefix.
pub(crate) fn strip_verbatim(p: PathBuf) -> PathBuf {
    #[cfg(windows)]
    {
        let s = p.to_string_lossy();
        if let Some(rest) = s.strip_prefix(r"\\?\UNC\") {
            return PathBuf::from(format!(r"\\{rest}"));
        }
        if let Some(rest) = s.strip_prefix(r"\\?\") {
            return PathBuf::from(rest);
        }
    }
    p
}

pub(crate) fn runtime_workspace_id(root: &Path) -> String {
    let absolute =
        strip_verbatim(std::fs::canonicalize(root).unwrap_or_else(|_| root.to_path_buf()));
    let digest = Sha256::digest(absolute.to_string_lossy().as_bytes());
    format!("{digest:x}")[..12].to_string()
}

pub(crate) fn runtime_instance_id() -> String {
    format!(
        "{}-{}",
        std::process::id(),
        std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap_or_default()
            .as_nanos()
    )
}
