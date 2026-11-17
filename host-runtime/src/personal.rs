//! A shared personal data owner. Attaching never gives a host process ownership.
use std::fs::{self, OpenOptions};
use std::io::Write;
use std::net::TcpListener;
use std::path::{Component, Path, PathBuf};
use std::process::{Command, Stdio};
use crate::owned_process::OwnedChild;
use crate::session_auth::SessionCredential;
use std::time::{Duration, Instant};
use reqwest::blocking::Client;
use serde_json::{json, Value};
use sha2::{Digest, Sha256};
use uuid::Uuid;

pub struct PersonalLaunch {
    pub user_data_dir: PathBuf,
    pub owner_kind: String,
    pub java: PathBuf,
    pub jar: PathBuf,
    /// Set only after the owning host verifies the Runtime Bundle's JAR bytes.
    pub jar_sha256: Option<String>,
}

pub struct PersonalRuntime {
    pub scope_id: String,
    pub instance_id: String,
    pub endpoint: String,
    pub credentials: SessionCredential,
    pub domain_dir: PathBuf,
    owner_instance_id: String,
    supervisor_token: String,
    child: Option<OwnedChild>,
}

pub fn personal_paths(user_root: &Path) -> Result<(PathBuf, PathBuf, String), String> {
    let absolute = if user_root.is_absolute() { user_root.to_path_buf() }
        else { std::env::current_dir().map_err(err)?.join(user_root) };
    let mut clean = PathBuf::new();
    for part in absolute.components() {
        match part { Component::CurDir => {}, Component::ParentDir => { clean.pop(); }, value => clean.push(value.as_os_str()) }
    }
    let absolute = clean;
    let mut ancestor = absolute.as_path();
    while !ancestor.exists() { ancestor = ancestor.parent().ok_or("personal data root is unavailable")?; }
    let mut canonical = normalize(fs::canonicalize(ancestor).map_err(err)?);
    let suffix = absolute.strip_prefix(ancestor).map_err(err)?;
    if !suffix.as_os_str().is_empty() { canonical.push(suffix); }
    let scope_id = format!("{:x}", Sha256::digest(format!("personal:{}", canonical.display()).as_bytes()))[..12].to_string();
    let domain = canonical.join("runtime-domains").join("personal");
    Ok((canonical, domain, scope_id))
}

impl PersonalRuntime {
    pub fn owns_process(&self) -> bool { self.child.is_some() }
    pub fn owned_process_exited(&mut self) -> bool {
        self.child.as_mut().is_some_and(|child| child.try_wait().ok().flatten().is_some())
    }
    pub fn view(&self) -> Value {
        json!({"scopeId":self.scope_id,"instanceId":self.instance_id,"runtimeProfile":"PERSONAL",
            "name":"Personal","state":"READY","endpoint":self.endpoint,"owned":self.child.is_some()})
    }

    pub fn open(launch: PersonalLaunch, configure: impl FnOnce(&mut Command)) -> Result<Self, String> {
        let (user_root, domain_dir, scope_id) = personal_paths(&launch.user_data_dir)?;
        private_directory(&domain_dir)?;
        let owner_file = domain_dir.join("owner.json");
        let manifest_file = domain_dir.join("domain.json");
        let owner_instance_id = Uuid::new_v4().to_string();
        let deadline = Instant::now() + crate::RUNTIME_STARTUP_TIMEOUT;
        // All host languages claim the same create-new lease. A live lease is never stolen.
        loop {
            if let Ok(owner) = read_json(&owner_file) {
                if owner_alive(&owner) {
                    if manifest_file.exists() { return Self::attach(&domain_dir, &scope_id); }
                    if Instant::now() >= deadline { return Err("The shared personal Runtime is owned but unavailable".into()); }
                    std::thread::sleep(Duration::from_millis(200));
                    continue;
                }
            }
            match OpenOptions::new().write(true).create_new(true).open(&owner_file) {
                Ok(mut file) => {
                    let lease = json!({"schemaVersion":1,"runtimeDomainId":"personal","ownerKind":launch.owner_kind,
                        "ownerPid":std::process::id(),"ownerInstanceId":owner_instance_id,"startedAt":timestamp(),
                        "ownerProcessStartedAt":process_started_at(std::process::id()).and_then(|ms|
                            chrono::DateTime::from_timestamp_millis(ms).map(|date| date.to_rfc3339_opts(chrono::SecondsFormat::Millis, true)))});
                    file.write_all(serde_json::to_string(&lease).map_err(err)?.as_bytes()).map_err(err)?;
                    file.sync_all().map_err(err)?;
                    break;
                }
                Err(error) if error.kind() == std::io::ErrorKind::AlreadyExists => {
                    let age = fs::metadata(&owner_file).and_then(|m| m.modified()).ok()
                        .and_then(|date| date.elapsed().ok()).unwrap_or_default();
                    let live = read_json(&owner_file).ok().is_some_and(|owner| owner_alive(&owner));
                    if !live && age > Duration::from_secs(2) { let _ = fs::remove_file(&owner_file); }
                    if Instant::now() >= deadline { return Err("Could not claim the personal Runtime lease".into()); }
                    std::thread::sleep(Duration::from_millis(100));
                }
                Err(error) => return Err(err(error)),
            }
        }
        // A new lease must not expose a previous owner's READY manifest while booting.
        let _ = fs::remove_file(&manifest_file);
        let result = Self::launch(&launch, configure, user_root, domain_dir.clone(), scope_id, owner_instance_id.clone(), deadline);
        if result.is_err() { release_owner(&domain_dir, &owner_instance_id); }
        result
    }

    fn launch(launch: &PersonalLaunch, configure: impl FnOnce(&mut Command), user_root: PathBuf,
        domain_dir: PathBuf, scope_id: String, owner_instance_id: String, deadline: Instant) -> Result<Self, String> {
        let listener = TcpListener::bind(("127.0.0.1", 0)).map_err(err)?;
        let port = listener.local_addr().map_err(err)?.port();
        drop(listener);
        let instance_id = Uuid::new_v4().to_string();
        let supervisor_token = Uuid::new_v4().to_string();
        let endpoint = format!("http://127.0.0.1:{port}");
        let log_dir = domain_dir.join("logs");
        private_directory(&log_dir)?;
        let mut command = Command::new(&launch.java);
        command.args(["-Xms128m", "-Xmx512m", "-XX:TieredStopAtLevel=1"]);
        if let Some(digest) = &launch.jar_sha256 {
            command.arg(format!("-Dharnex.runtime.verified-sha256={digest}"));
        }
        command.arg("-jar").arg(&launch.jar).arg(format!("--server.port={port}"))
            .args(["--server.address=127.0.0.1", "--harnex.runtime-profile=PERSONAL", "--harnex.auth.enabled=true"])
            .arg(format!("--harnex.user-data-dir={}", user_root.display()))
            .arg(format!("--harnex.data-dir={}", user_root.join("personal").display()))
            .arg("--harnex.runtime-domain-id=personal")
            .arg(format!("--harnex.runtime-domain-dir={}", domain_dir.display()))
            .arg(format!("--harnex.runtime-owner-kind={}", launch.owner_kind))
            .args(["--harnex.cors.allowed-origins[0]=http://tauri.localhost", "--harnex.cors.allowed-origins[1]=tauri://localhost",
                "--harnex.cors.allowed-headers[0]=*", "--harnex.cors.allow-credentials=false"])
            .env("HARNEX_RUNTIME_PROFILE", "PERSONAL").env("HARNEX_SCOPE_ID", &scope_id)
            .env("HARNEX_INSTANCE_ID", &instance_id).env("HARNEX_USER_DATA_DIR", &user_root)
            .env("HARNEX_DATA_DIR", user_root.join("personal")).env("HARNEX_DATADIR", user_root.join("personal"))
            .env("HARNEX_RUNTIME_DOMAIN_ID", "personal").env("HARNEX_RUNTIME_DOMAIN_DIR", &domain_dir)
            .env("HARNEX_RUNTIME_OWNER_KIND", &launch.owner_kind).env("HARNEX_INTERNAL_SUPERVISOR_TOKEN", &supervisor_token)
            .env_remove("HARNEX_WORKSPACE").current_dir(&domain_dir).stdin(Stdio::null());
        for (index, method) in ["GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"].iter().enumerate() {
            command.arg(format!("--harnex.cors.allowed-methods[{index}]={method}"));
        }
        let mut config_files = vec![];
        let mut config_locations = vec![];
        for name in ["agent-config.yml", "application.yml"] {
            let file = user_root.join(name);
            if file.exists() {
                config_locations.push(reqwest::Url::from_file_path(&file).map_err(|_| "Invalid user config path")?
                    .as_str().replace(',', "%2C").replace(';', "%3B"));
                config_files.push(file.to_string_lossy().into_owned());
            }
        }
        if !config_locations.is_empty() {
            command.env("SPRING_CONFIG_ADDITIONAL_LOCATION", config_locations.join(","))
                .env("HARNEX_CONFIG_FILES", config_files.join("\n"));
        }
        crate::logs::prepare(&log_dir).map_err(err)?;
        command.stdout(Stdio::piped()).stderr(Stdio::piped());
        #[cfg(windows)] { use std::os::windows::process::CommandExt; command.creation_flags(0x08000000); }
        #[cfg(unix)] { use std::os::unix::process::CommandExt; command.process_group(0); }
        configure(&mut command);
        let mut child = crate::owned_process::spawn(&mut command).map_err(err)?;
        crate::logs::capture_output(&mut child, &log_dir);
        let pid = child.id();
        let mut owned = Self { scope_id, instance_id, endpoint, credentials: SessionCredential::fixed(String::new()), domain_dir,
            owner_instance_id, supervisor_token, child: Some(child) };
        let client = http_client()?;
        let mut last_error = String::new();
        while Instant::now() < deadline {
            if let Some(status) = owned.child.as_mut().unwrap().try_wait().map_err(err)? {
                return Err(format!("Personal Runtime exited before readiness ({status}); see {}", log_dir.display()));
            }
            // /scope may respond before the first /protocol request finishes hashing
            // the packaged JAR. A transport timeout must not kill an otherwise healthy
            // JVM. Publish READY only after the whole identity/protocol/auth handshake.
            match handshake(&client, &owned.endpoint, &owned.scope_id, &owned.instance_id,
                &owned.domain_dir, &owned.domain_dir.join("boot-token")) {
                Ok(credentials) => {
                    owned.credentials = credentials;
                    let manifest = json!({"schemaVersion":3,"runtimeProfile":"PERSONAL","canonicalRoot":Value::Null,
                        "scopeId":owned.scope_id,"instanceId":owned.instance_id,"runtimeDomainId":"personal",
                        "ownerKind":launch.owner_kind,"ownerPid":std::process::id(),"ownerInstanceId":owned.owner_instance_id,
                        "runtimePid":pid,"endpoint":owned.endpoint,"lifecycle":"READY","protocolVersion":"1.0",
                        "bootTokenFile":owned.domain_dir.join("boot-token"),"configRevision":"personal","publishedAt":timestamp()});
                    atomic_json(&owned.domain_dir.join("domain.json"), &manifest)?;
                    return Ok(owned);
                }
                Err(HandshakeError::Retry(message)) => last_error = message,
                Err(HandshakeError::Fatal(message)) => return Err(message),
            }
            std::thread::sleep(Duration::from_millis(200));
        }
        Err(format!("Personal Runtime did not become ready in time: {last_error}; see {}", log_dir.display()))
    }

    pub fn attach(domain_dir: &Path, scope_id: &str) -> Result<Self, String> {
        let manifest = read_json(&domain_dir.join("domain.json"))?;
        let owner = read_json(&domain_dir.join("owner.json"))?;
        let owner_kind = field(&manifest, "ownerKind")?;
        field(&manifest, "ownerInstanceId")?;
        for (value, key) in [(&manifest, "publishedAt"), (&owner, "startedAt")] {
            let timestamp = chrono::DateTime::parse_from_rfc3339(field(value, key)?).map_err(err)?;
            if timestamp > chrono::Utc::now() + chrono::Duration::minutes(5) { return Err("Personal Runtime lease timestamp is invalid".into()); }
        }
        if manifest["schemaVersion"] != 3 || manifest["runtimeProfile"] != "PERSONAL" || manifest["scopeId"] != scope_id
            || !["web", "tauri", "vscode", "cli", "standalone"].contains(&owner_kind)
            || manifest["runtimeDomainId"] != "personal" || manifest["lifecycle"] != "READY" || manifest["protocolVersion"] != "1.0"
            || !manifest["canonicalRoot"].is_null() || !manifest["remote"].is_null()
            || owner["schemaVersion"] != 1 || owner["runtimeDomainId"] != "personal"
            || owner["ownerKind"] != manifest["ownerKind"] || owner["ownerInstanceId"] != manifest["ownerInstanceId"]
            || owner["ownerPid"] != manifest["ownerPid"]
            || !owner_alive(&owner)
            || !manifest["runtimePid"].as_u64().is_some_and(|pid| pid_alive(pid as u32)) {
            return Err("Shared personal Runtime manifest/owner identity is invalid".into());
        }
        let endpoint = field(&manifest, "endpoint")?;
        let url = reqwest::Url::parse(endpoint).map_err(err)?;
        if url.scheme() != "http" || !matches!(url.host_str(), Some("127.0.0.1") | Some("[::1]") | Some("::1"))
            || url.port().is_none() || url.path() != "/" || url.query().is_some() || url.fragment().is_some()
            || !url.username().is_empty() || url.password().is_some() { return Err("Personal Runtime endpoint must be loopback HTTP".into()); }
        let instance_id = field(&manifest, "instanceId")?;
        let token_path = fs::canonicalize(field(&manifest, "bootTokenFile")?).map_err(err)?;
        if !token_path.starts_with(fs::canonicalize(domain_dir).map_err(err)?) { return Err("Personal boot token is outside its Runtime domain".into()); }
        let credentials = handshake(&http_client()?, endpoint, scope_id, instance_id, domain_dir, &token_path)
            .map_err(|error| match error { HandshakeError::Retry(message) | HandshakeError::Fatal(message) => message })?;
        Ok(Self { scope_id: scope_id.into(), instance_id: instance_id.into(), endpoint: endpoint.into(), credentials,
            domain_dir: domain_dir.to_path_buf(), owner_instance_id: String::new(), supervisor_token: String::new(), child: None })
    }
}

enum HandshakeError { Retry(String), Fatal(String) }

impl From<String> for HandshakeError {
    fn from(message: String) -> Self { Self::Fatal(message) }
}

impl From<reqwest::Error> for HandshakeError {
    fn from(error: reqwest::Error) -> Self {
        let message = if error.is_timeout() { format!("{error}: request timed out") } else { error.to_string() };
        if error.is_timeout() || error.is_connect() || error.is_request() || error.is_body() {
            Self::Retry(message)
        } else { Self::Fatal(message) }
    }
}

fn handshake(client: &Client, endpoint: &str, scope_id: &str, instance_id: &str,
    domain_dir: &Path, token_path: &Path) -> Result<SessionCredential, HandshakeError> {
    let headers = |request: reqwest::blocking::RequestBuilder| request
        .header("X-Harnex-Scope-Id", scope_id).header("X-Harnex-Instance-Id", instance_id);
    let checked = |response: reqwest::blocking::Response| -> Result<Value, HandshakeError> {
        if response.status().is_server_error() {
            return Err(HandshakeError::Retry(format!("Personal Runtime is not ready: HTTP {}", response.status())));
        }
        if !response.status().is_success() {
            return Err(HandshakeError::Fatal(format!("Personal Runtime authentication or request rejected: HTTP {}", response.status())));
        }
        if response.headers().get("X-Harnex-Scope-Id").and_then(|v| v.to_str().ok()) != Some(scope_id)
            || response.headers().get("X-Harnex-Instance-Id").and_then(|v| v.to_str().ok()) != Some(instance_id) {
            return Err(HandshakeError::Fatal("Personal Runtime response identity mismatch".into()));
        }
        Ok(response.json()?)
    };
    verify_scope(&checked(headers(client.get(format!("{endpoint}/api/v1/scope"))).send()?)?, scope_id, instance_id)?;
    let protocol = checked(headers(client.get(format!("{endpoint}/api/v1/protocol"))).send()?)?;
    if protocol["version"] != "1.0" { return Err(HandshakeError::Fatal("Personal Runtime protocol mismatch".into())); }
    let token_path = fs::canonicalize(token_path).map_err(|error| {
        if error.kind() == std::io::ErrorKind::NotFound { HandshakeError::Retry("Personal Runtime boot token is not ready".into()) }
        else { HandshakeError::Fatal(err(error)) }
    })?;
    if !token_path.starts_with(fs::canonicalize(domain_dir).map_err(err)?) {
        return Err(HandshakeError::Fatal("Personal boot token is outside its Runtime domain".into()));
    }
    let auth = checked(headers(client.post(format!("{endpoint}/api/v1/auth/boot-token")))
        .json(&json!({"token":fs::read_to_string(&token_path).map_err(err)?.trim()})).send()?)?;
    Ok(SessionCredential::local(field(&auth, "token")?.into(), endpoint.into(), scope_id.into(), instance_id.into(), token_path))
}

impl Drop for PersonalRuntime {
    fn drop(&mut self) {
        let Some(mut child) = self.child.take() else { return; };
        if let Ok(client) = Client::builder().no_proxy().timeout(Duration::from_secs(12)).build() {
            let _ = client.post(format!("{}/api/v1/admin/drain?timeoutMs=10000", self.endpoint))
                .header("X-Harnex-Supervisor-Token", &self.supervisor_token).send();
        }
        if child.try_wait().ok().flatten().is_none() { crate::terminate_transport(&mut child); }
        let _ = child.wait();
        let _ = fs::remove_file(self.domain_dir.join("domain.json"));
        release_owner(&self.domain_dir, &self.owner_instance_id);
    }
}

fn verify_scope(scope: &Value, scope_id: &str, instance_id: &str) -> Result<(), String> {
    if scope["scopeId"] != scope_id || scope["instanceId"] != instance_id || scope["runtimeProfile"] != "PERSONAL"
        || scope["runtimeDomainId"] != "personal" || !scope["projectRoot"].is_null() { return Err("Personal Runtime scope identity mismatch".into()); }
    Ok(())
}
fn http_client() -> Result<Client, String> { Client::builder().no_proxy().timeout(Duration::from_secs(3)).build().map_err(err) }
fn field<'a>(value: &'a Value, key: &str) -> Result<&'a str, String> { value[key].as_str().filter(|value| !value.is_empty()).ok_or_else(|| format!("Missing personal Runtime {key}")) }
fn read_json(path: &Path) -> Result<Value, String> { serde_json::from_slice(&fs::read(path).map_err(err)?).map_err(err) }
fn timestamp() -> String { chrono::Utc::now().to_rfc3339_opts(chrono::SecondsFormat::Millis, true) }
fn normalize(path: PathBuf) -> PathBuf {
    #[cfg(windows)] {
        let value = path.to_string_lossy();
        if let Some(value) = value.strip_prefix(r"\\?\UNC\") { return PathBuf::from(format!(r"\\{value}")); }
        if let Some(value) = value.strip_prefix(r"\\?\") { return PathBuf::from(value); }
    }
    path
}
fn private_directory(path: &Path) -> Result<(), String> {
    fs::create_dir_all(path).map_err(err)?;
    #[cfg(unix)] { use std::os::unix::fs::PermissionsExt; fs::set_permissions(path, fs::Permissions::from_mode(0o700)).map_err(err)?; }
    Ok(())
}
fn atomic_json(path: &Path, value: &Value) -> Result<(), String> {
    crate::write_private(path, value)
}
fn release_owner(domain: &Path, id: &str) {
    let path = domain.join("owner.json");
    if let Ok(owner) = read_json(&path) { if owner["ownerPid"] == std::process::id() && owner["ownerInstanceId"] == id { let _ = fs::remove_file(path); } }
}
fn pid_alive(pid: u32) -> bool {
    if pid == 0 { return false; }
    #[cfg(unix)] { unsafe { libc::kill(pid as i32, 0) == 0 || std::io::Error::last_os_error().raw_os_error() == Some(libc::EPERM) } }
    #[cfg(windows)] { unsafe {
        use windows_sys::Win32::{Foundation::CloseHandle, System::Threading::{OpenProcess, GetExitCodeProcess, PROCESS_QUERY_LIMITED_INFORMATION}};
        let handle = OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, 0, pid);
        if handle.is_null() { return false; }
        let mut code = 0; let queried = GetExitCodeProcess(handle, &mut code); CloseHandle(handle);
        queried != 0 && code == 259
    } }
}

// A PID can be reused after a host exits. Match the same millisecond timestamp used
// by the Node host; old leases are valid only if their process predates the lease.
fn owner_alive(owner: &Value) -> bool {
    let Some(pid) = owner["ownerPid"].as_u64().and_then(|pid| u32::try_from(pid).ok()) else { return false; };
    if !pid_alive(pid) { return false; }
    let Some(actual) = process_started_at(pid) else { return pid_alive(pid); };
    let parse = |key: &str| owner[key].as_str().and_then(|value|
        chrono::DateTime::parse_from_rfc3339(value).ok()).map(|date| date.timestamp_millis());
    if let Some(expected) = parse("ownerProcessStartedAt") { return actual == expected; }
    parse("startedAt").is_none_or(|lease| actual <= lease)
}

#[cfg(windows)]
fn process_started_at(pid: u32) -> Option<i64> {
    use windows_sys::Win32::{Foundation::{CloseHandle, FILETIME},
        System::Threading::{GetProcessTimes, OpenProcess, PROCESS_QUERY_LIMITED_INFORMATION}};
    unsafe {
        let handle = OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, 0, pid);
        if handle.is_null() { return None; }
        let mut creation: FILETIME = std::mem::zeroed();
        let mut exit: FILETIME = std::mem::zeroed();
        let mut kernel: FILETIME = std::mem::zeroed();
        let mut user: FILETIME = std::mem::zeroed();
        let ok = GetProcessTimes(handle, &mut creation, &mut exit, &mut kernel, &mut user);
        CloseHandle(handle);
        if ok == 0 { return None; }
        let ticks = (u64::from(creation.dwHighDateTime) << 32) | u64::from(creation.dwLowDateTime);
        Some((ticks / 10_000) as i64 - 11_644_473_600_000)
    }
}

#[cfg(not(windows))]
fn process_started_at(pid: u32) -> Option<i64> {
    use chrono::TimeZone;
    let output = Command::new("ps").args(["-p", &pid.to_string(), "-o", "lstart="])
        .env("LC_ALL", "C").output().ok()?;
    if !output.status.success() { return None; }
    let value = String::from_utf8(output.stdout).ok()?;
    let date = chrono::NaiveDateTime::parse_from_str(value.trim(), "%a %b %e %T %Y").ok()?;
    chrono::Local.from_local_datetime(&date).single().map(|date| date.timestamp_millis())
}
fn err(error: impl std::fmt::Display) -> String { error.to_string() }

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::{BufRead, BufReader};

    #[test]
    fn reused_owner_pid_does_not_keep_a_stale_lease_alive() {
        let actual = process_started_at(std::process::id()).expect("query current process start");
        let date = |ms| chrono::DateTime::from_timestamp_millis(ms).unwrap().to_rfc3339_opts(chrono::SecondsFormat::Millis, true);
        let mut owner = json!({"ownerPid":std::process::id(),"startedAt":timestamp(),
            "ownerProcessStartedAt":date(actual)});
        assert!(owner_alive(&owner));
        owner["ownerProcessStartedAt"] = json!(date(actual - 60_000));
        assert!(!owner_alive(&owner));
        owner.as_object_mut().unwrap().remove("ownerProcessStartedAt");
        assert!(owner_alive(&owner));
        owner["startedAt"] = json!(date(actual - 60_000));
        assert!(!owner_alive(&owner));
    }

    #[test]
    fn personal_identity_survives_directory_creation() {
        let base = std::env::temp_dir().join(format!("harnex-personal-{}", Uuid::new_v4()));
        fs::create_dir_all(&base).unwrap();
        let root = base.join("future").join("user");
        let before = personal_paths(&root).unwrap();
        assert!(!root.exists());
        fs::create_dir_all(&root).unwrap();
        let after = personal_paths(&root).unwrap();
        assert_eq!(before, after);
        assert_eq!(after.1, after.0.join("runtime-domains").join("personal"));
        fs::remove_dir_all(base).unwrap();
    }

    #[test]
    fn attached_host_cannot_stop_the_owned_process() {
        let root = std::env::temp_dir().join(format!("harnex-personal-host-{}", Uuid::new_v4()));
        let owned = open_fixture(&root, "normal").unwrap();
        let pid = owned.child.as_ref().unwrap().id();
        let attached = PersonalRuntime::attach(&owned.domain_dir, &owned.scope_id).unwrap();
        assert!(!attached.owns_process());
        assert_eq!(attached.instance_id, owned.instance_id);
        drop(attached);
        assert!(pid_alive(pid));
        let scope = http_client().unwrap().get(format!("{}/api/v1/scope", owned.endpoint)).send().unwrap().json::<Value>().unwrap();
        assert_eq!(scope["scopeId"], owned.scope_id);
        drop(owned);
        assert!(!pid_alive(pid));
        assert!(!root.join("runtime-domains/personal/owner.json").exists());
        fs::remove_dir_all(root).unwrap();
    }

    fn open_fixture(root: &Path, mode: &str) -> Result<PersonalRuntime, String> {
        let launch = PersonalLaunch { user_data_dir: root.to_path_buf(), owner_kind: "tauri".into(), java: PathBuf::new(), jar: PathBuf::new(), jar_sha256: None };
        PersonalRuntime::open(launch, |command| {
            let envs: Vec<_> = command.get_envs().map(|(key, value)| (key.to_os_string(), value.map(|v| v.to_os_string()))).collect();
            let port = command.get_args().find_map(|arg| arg.to_str().and_then(|arg| arg.strip_prefix("--server.port=")).map(str::to_string)).unwrap();
            *command = Command::new(std::env::current_exe().unwrap());
            command.args(["--exact", "personal::tests::fake_runtime", "--nocapture"])
                .env("HARNEX_TEST_PERSONAL_PORT", port).env("HARNEX_TEST_PERSONAL_MODE", mode)
                .stdin(Stdio::null()).stdout(Stdio::null()).stderr(Stdio::null());
            for (key, value) in envs { if let Some(value) = value { command.env(key, value); } else { command.env_remove(key); } }
            crate::prepare_process(command);
        })
    }

    #[test]
    fn startup_retries_cold_protocol_and_publishes_only_after_authentication() {
        for mode in ["slow-protocol", "unavailable-protocol"] {
            let root = std::env::temp_dir().join(format!("harnex-personal-retry-{}", Uuid::new_v4()));
            let owned = open_fixture(&root, mode).unwrap();
            assert_eq!(owned.credentials.token(None).unwrap(), "fixture-session");
            assert!(owned.domain_dir.join("domain.json").exists());
            assert!(!owned.domain_dir.join("premature-ready").exists());
            drop(owned);
            fs::remove_dir_all(root).unwrap();
        }
    }

    #[test]
    fn startup_rejects_mismatched_identity_or_protocol_without_retrying() {
        for mode in ["wrong-identity", "wrong-protocol", "auth-rejected"] {
            let root = std::env::temp_dir().join(format!("harnex-personal-reject-{}", Uuid::new_v4()));
            let error = open_fixture(&root, mode).err().expect("invalid handshake must fail");
            assert!(error.contains("identity") || error.contains("protocol") || error.contains("401"), "{error}");
            let domain = root.join("runtime-domains/personal");
            assert!(!domain.join("domain.json").exists());
            assert!(!domain.join("owner.json").exists());
            fs::remove_dir_all(root).unwrap();
        }
    }

    #[test]
    fn fake_runtime() {
        let Ok(port) = std::env::var("HARNEX_TEST_PERSONAL_PORT") else { return; };
        let scope = std::env::var("HARNEX_SCOPE_ID").unwrap();
        let instance = std::env::var("HARNEX_INSTANCE_ID").unwrap();
        let domain = PathBuf::from(std::env::var("HARNEX_RUNTIME_DOMAIN_DIR").unwrap());
        let mode = std::env::var("HARNEX_TEST_PERSONAL_MODE").unwrap_or_default();
        fs::write(domain.join("boot-token"), "fixture-boot").unwrap();
        let listener = TcpListener::bind(format!("127.0.0.1:{port}")).unwrap();
        let mut protocol_requests = 0;
        let mut authenticated = false;
        for connection in listener.incoming() {
            let mut connection = connection.unwrap();
            let mut request = String::new();
            BufReader::new(&connection).read_line(&mut request).unwrap();
            if !authenticated && domain.join("domain.json").exists() {
                fs::write(domain.join("premature-ready"), "READY before handshake completed").unwrap();
            }
            let mut status = "200 OK";
            if request.contains("/api/v1/protocol") {
                protocol_requests += 1;
                if protocol_requests == 1 && mode == "slow-protocol" { std::thread::sleep(Duration::from_millis(3500)); }
                if protocol_requests == 1 && mode == "unavailable-protocol" { status = "503 Service Unavailable"; }
            }
            if request.contains("/api/v1/auth/boot-token") {
                authenticated = true;
                if mode == "auth-rejected" { status = "401 Unauthorized"; }
            }
            let data = if request.contains("/api/v1/scope") { json!({"scopeId":scope,"instanceId":instance,"runtimeProfile":"PERSONAL","runtimeDomainId":"personal","projectRoot":Value::Null}) }
                else if request.contains("/api/v1/protocol") { json!({"version":if mode == "wrong-protocol" { "2.0" } else { "1.0" }}) }
                else if request.contains("/api/v1/auth/boot-token") { json!({"token":"fixture-session"}) }
                else { json!({}) };
            let body = serde_json::to_string(&data).unwrap();
            let echo_instance = if mode == "wrong-identity" { "another-instance" } else { &instance };
            let _ = write!(connection, "HTTP/1.1 {status}\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\nX-Harnex-Scope-Id: {scope}\r\nX-Harnex-Instance-Id: {echo_instance}\r\n\r\n{body}", body.len());
        }
    }
}
