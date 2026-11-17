//! Local and personal Runtime process lifecycle and connection credentials.

use crate::bundle::{bundled_runtime, bundled_ui, prefetch_runtime_artifact, resource_roots};
use crate::catalog::workspace_data_dir;
#[cfg(target_os = "macos")]
use crate::credentials::inject_keychain_provider_secrets;
use crate::host::{
    env_or, runtime_domain_dir, runtime_domain_id, runtime_instance_id, runtime_workspace_id,
    shell_log, shell_log_dir, user_data_dir,
};
use crate::state::{PersonalProcess, PersonalState, RuntimeChild, RuntimeProcess, ServerProcess};
use serde_json::json;
use serde_json::Value;
use std::io::Read;
use std::io::Write;
use std::net::TcpListener;
use std::net::TcpStream;
use std::net::ToSocketAddrs;
use std::path::Path;
use std::path::PathBuf;
#[cfg(unix)]
use std::process::Child;
use std::process::Command;
use std::sync::OnceLock;
use std::time::Duration;
use std::time::Instant;
use tauri::AppHandle;
use tauri::Manager;
const DEFAULT_PORT: u16 = 8088;

pub(crate) const HEALTH_TIMEOUT: Duration = harnex_host_runtime::RUNTIME_STARTUP_TIMEOUT;

const DRAIN_TIMEOUT: Duration = Duration::from_secs(10);

pub(crate) fn resolve_port() -> u16 {
    static PORT: OnceLock<u16> = OnceLock::new();
    *PORT.get_or_init(|| {
        env_or("HARNEX_PORT")
            .or_else(|| env_or("SERVER_PORT"))
            .and_then(|v| v.parse::<u16>().ok())
            .or_else(|| {
                TcpListener::bind(("127.0.0.1", 0))
                    .ok()
                    .and_then(|listener| listener.local_addr().ok())
                    .map(|address| address.port())
            })
            .unwrap_or(DEFAULT_PORT)
    })
}

/// Runtime readiness is stronger than "something accepted a TCP connection". The workspace
/// identity response prevents a stale/unrelated JVM on the fixed port from being accepted.
fn runtime_http_ready(
    host: &str,
    port: u16,
    expected_workspace_id: &str,
    expected_instance_id: &str,
) -> bool {
    let address = match format!("{host}:{port}")
        .to_socket_addrs()
        .ok()
        .and_then(|mut addresses| addresses.next())
    {
        Some(address) => address,
        None => return false,
    };
    let mut stream = match TcpStream::connect_timeout(&address, Duration::from_millis(800)) {
        Ok(stream) => stream,
        Err(_) => return false,
    };
    let _ = stream.set_write_timeout(Some(Duration::from_millis(800)));
    let _ = stream.set_read_timeout(Some(Duration::from_millis(800)));
    let request = format!(
        "GET /api/v1/workspace HTTP/1.1\r\nHost: {host}:{port}\r\nConnection: close\r\n\r\n"
    );
    if stream.write_all(request.as_bytes()).is_err() {
        return false;
    }
    let mut response = Vec::new();
    if (&mut stream)
        .take(64 * 1024)
        .read_to_end(&mut response)
        .is_err()
    {
        return false;
    }
    let payload = String::from_utf8_lossy(&response);
    let status_ok = payload.starts_with("HTTP/1.1 200") || payload.starts_with("HTTP/1.0 200");
    status_ok
        && payload.contains(&format!("\"workspaceId\":\"{expected_workspace_id}\""))
        && payload.contains(&format!("\"instanceId\":\"{expected_instance_id}\""))
}

fn runtime_process_exited(app: &AppHandle) -> bool {
    let Some(state) = app.try_state::<ServerProcess>() else {
        return true;
    };
    let mut guard = state.0.lock().unwrap();
    let Some(runtime) = guard.as_mut() else {
        return true;
    };
    match runtime.child.try_wait() {
        Ok(Some(status)) => {
            shell_log(
                &shell_log_dir(),
                &format!("[wait] runtime exited before readiness: {status}"),
            );
            true
        }
        Ok(None) => false,
        Err(error) => {
            shell_log(
                &shell_log_dir(),
                &format!("[wait] cannot inspect runtime process: {error}"),
            );
            false
        }
    }
}

pub(crate) fn wait_until_runtime_ready(
    app: &AppHandle,
    host: &str,
    port: u16,
    expected_workspace_id: &str,
    expected_instance_id: &str,
    timeout: Duration,
) -> Result<(), String> {
    let start = Instant::now();
    while start.elapsed() < timeout {
        if runtime_process_exited(app) {
            return Err(runtime_startup_failure(
                &shell_log_dir(),
                "Runtime exited before readiness",
            ));
        }
        if runtime_http_ready(host, port, expected_workspace_id, expected_instance_id) {
            return Ok(());
        }
        std::thread::sleep(Duration::from_millis(400));
    }
    Err(runtime_startup_failure(
        &shell_log_dir(),
        "Runtime readiness timed out",
    ))
}

fn runtime_startup_failure(log_dir: &Path, fallback: &str) -> String {
    use std::io::{Read, Seek, SeekFrom};
    for name in ["runtime-out.log", "runtime-err.log"] {
        let Ok(mut file) = std::fs::File::open(log_dir.join(name)) else {
            continue;
        };
        let size = file.metadata().map(|meta| meta.len()).unwrap_or(0);
        let _ = file.seek(SeekFrom::Start(size.saturating_sub(32 * 1024)));
        let mut bytes = Vec::new();
        let _ = file.take(32 * 1024).read_to_end(&mut bytes);
        let text = String::from_utf8_lossy(&bytes);
        if let Some(cause) = text
            .lines()
            .rev()
            .find_map(|line| line.strip_prefix("Caused by: "))
        {
            // The old backend appended an unsafe lock-bypass suggestion; surface the cause only.
            return cause
                .split(" Set -Dharnex.disableDataDirLock")
                .next()
                .unwrap_or(cause)
                .chars()
                .take(1200)
                .collect();
        }
        if let Some(error) = text
            .lines()
            .rev()
            .find(|line| line.contains(" ERROR ") || line.starts_with("Error:"))
        {
            return error.chars().take(1200).collect();
        }
    }
    format!("{fallback}; see {}", log_dir.display())
}

/// Best-effort DRAIN barrier before terminating the current workspace JVM.
pub(crate) fn drain_runtime(port: u16, supervisor_token: &str) {
    let started = Instant::now();
    if let Ok(mut stream) = TcpStream::connect_timeout(
        &format!("127.0.0.1:{port}")
            .to_socket_addrs()
            .ok()
            .and_then(|mut a| a.next())
            .unwrap_or_else(|| "127.0.0.1:8088".parse().unwrap()),
        Duration::from_secs(2),
    ) {
        let request = format!(
            "POST /api/v1/admin/drain?timeoutMs={} HTTP/1.1\r\nHost: 127.0.0.1:{port}\r\nX-Harnex-Supervisor-Token: {supervisor_token}\r\nContent-Length: 0\r\nConnection: close\r\n\r\n",
            DRAIN_TIMEOUT.as_millis()
        );
        let _ = stream.set_write_timeout(Some(Duration::from_secs(2)));
        let _ = stream.set_read_timeout(Some(DRAIN_TIMEOUT + Duration::from_secs(2)));
        let _ = stream.write_all(request.as_bytes());
        let mut sink = [0u8; 512];
        let _ = std::io::Read::read(&mut stream, &mut sink);
    }
    shell_log(
        &shell_log_dir(),
        &format!("[drain] completed in {}ms", started.elapsed().as_millis()),
    );
}

/// Spawn the Runtime for a given workspace `root`. The Runtime runs with `root` as its
/// working dir + HARNEX_WORKSPACE (AGENTS.md discovery) and `workspace_data_dir(root)` as its
/// data dir. Returns None when neither a bundled jar nor HARNEX_JAR is available (in which
/// case the shell still opens, assuming the operator started a server elsewhere).
pub(crate) fn spawn_runtime(
    app: &tauri::AppHandle,
    port: u16,
    root: &Path,
) -> Option<RuntimeProcess> {
    let log = shell_log_dir();
    let shared_data = workspace_data_dir(root);
    let data = shared_data.join("runtime-stores").join("tauri");
    let _ = std::fs::create_dir_all(&data);

    let bundled = match bundled_runtime(app) {
        Ok(bundle) => bundle,
        Err(error) => {
            shell_log(
                &log,
                &format!("[spawn] ABORT: invalid Runtime Bundle: {error}"),
            );
            return None;
        }
    };
    let (resolved_java, jar, jar_sha256) = match bundled {
        Some(bundle) => (Some(bundle.java), bundle.jar, Some(bundle.jar_sha256)),
        None if env_or("HARNEX_JAR").is_some() => (
            None,
            PathBuf::from(env_or("HARNEX_JAR").expect("checked above")),
            None,
        ),
        None => {
            shell_log(
                &log,
                &format!(
                    "[spawn] ABORT: no verified Runtime Bundle found. roots={:?}",
                    resource_roots(app)
                ),
            );
            return None;
        }
    };
    let java = resolved_java
        .clone()
        .unwrap_or_else(|| PathBuf::from(if cfg!(windows) { "java.exe" } else { "java" }));
    if !jar.is_file() {
        shell_log(
            &log,
            &format!("[spawn] ABORT: jar does not exist: {}", jar.display()),
        );
        return None;
    }

    if jar_sha256.is_none() { prefetch_runtime_artifact(&jar, &log); }

    let mut cmd = Command::new(&java);
    #[cfg(unix)]
    {
        // Keep the JVM, MCP stdio servers and ordinary tool subprocesses in an owned process
        // group. Desktop shutdown can then terminate the whole Runtime Domain without leaving
        // Homebrew/node/java helpers behind after a workspace switch.
        use std::os::unix::process::CommandExt;
        cmd.process_group(0);
    }
    let supervisor_token = format!("desktop-{}", runtime_instance_id());
    let instance_id = runtime_instance_id();
    let tiered_stop_level =
        env_or("HARNEX_GUI_TIERED_STOP_LEVEL").unwrap_or_else(|| "1".to_string());
    let java_xms = env_or("HARNEX_SUPERVISOR_JAVA_XMS").unwrap_or_else(|| "256m".to_string());
    let java_xmx = env_or("HARNEX_SUPERVISOR_JAVA_XMX").unwrap_or_else(|| "512m".to_string());
    cmd.arg(format!("-Xms{java_xms}"));
    cmd.arg(format!("-Xmx{java_xmx}"));
    if matches!(tiered_stop_level.as_str(), "1" | "2" | "3" | "4") {
        cmd.arg(format!("-XX:TieredStopAtLevel={tiered_stop_level}"));
    }
    if let Some(digest) = jar_sha256 {
        cmd.arg(format!("-Dharnex.runtime.verified-sha256={digest}"));
    }
    cmd.arg("-jar").arg(&jar);
    cmd.arg(format!("--server.port={port}"));
    cmd.arg("--server.address=127.0.0.1");
    // The full SPA remains on the trusted bundled Tauri origin and calls this loopback API cross-origin.
    // Pin the desktop Runtime to those two platform origins even if a user-level remote-deployment CORS list exists.
    cmd.arg("--harnex.cors.allowed-origins[0]=http://tauri.localhost");
    cmd.arg("--harnex.cors.allowed-origins[1]=tauri://localhost");
    cmd.arg("--harnex.cors.allowed-methods[0]=GET");
    cmd.arg("--harnex.cors.allowed-methods[1]=POST");
    cmd.arg("--harnex.cors.allowed-methods[2]=PUT");
    cmd.arg("--harnex.cors.allowed-methods[3]=DELETE");
    cmd.arg("--harnex.cors.allowed-methods[4]=OPTIONS");
    cmd.arg("--harnex.cors.allowed-headers[0]=*");
    cmd.arg("--harnex.cors.allow-credentials=false");
    // Pin the data dir to this workspace and expose the workspace root for AGENTS.md.
    cmd.arg(format!("--harnex.data-dir={}", data.to_string_lossy()));
    cmd.arg(format!(
        "--harnex.shared-data-dir={}",
        shared_data.to_string_lossy()
    ));
    cmd.arg(format!(
        "--harnex.user-data-dir={}",
        user_data_dir().to_string_lossy()
    ));
    cmd.arg(format!(
        "--harnex.runtime-domain-id={}",
        runtime_domain_id()
    ));
    cmd.arg(format!(
        "--harnex.runtime-domain-dir={}",
        runtime_domain_dir().to_string_lossy()
    ));
    cmd.arg("--harnex.runtime-owner-kind=tauri");
    cmd.env("HARNEX_DATA_DIR", &data);
    cmd.env("HARNEX_DATADIR", &data); // Alias for Spring Boot compatibility
    cmd.env("HARNEX_WORKSPACE", root);
    cmd.env("HARNEX_SCOPE_ID", runtime_workspace_id(root));
    cmd.env("HARNEX_INSTANCE_ID", &instance_id);
    cmd.env("HARNEX_USER_DATA_DIR", user_data_dir());
    cmd.env("HARNEX_RUNTIME_DOMAIN_ID", runtime_domain_id());
    cmd.env("HARNEX_RUNTIME_DOMAIN_DIR", runtime_domain_dir());
    cmd.env("HARNEX_RUNTIME_OWNER_KIND", "tauri");
    cmd.env("HARNEX_INTERNAL_SUPERVISOR_TOKEN", &supervisor_token);
    // Single-user desktop is deliberately account-free. Pinning the socket to loopback plus an
    // exact CORS allow-list is the host boundary; remote deployments keep their own auth policy.
    cmd.arg("--harnex.auth.enabled=false");
    // Keep the Runtime capable of serving the same assets for explicit attach/browser
    // diagnostics. Normal desktop navigation stays on the bundled Tauri SPA.
    if let Some(ui) = bundled_ui(app) {
        cmd.arg(format!("--harnex.web.ui-dir={}", ui.to_string_lossy()));
        shell_log(&log, &format!("[spawn] ui-dir={}", ui.display()));
    } else {
        shell_log(
            &log,
            "[spawn] no bundled resources/ui; backend stays API-only",
        );
    }
    cmd.current_dir(root);

    // Make sure the JVM can find its own libs by putting the bundled jre/bin first on PATH.
    if let Some(jbin) = resolved_java.as_ref().and_then(|j| j.parent()) {
        let prev = std::env::var("PATH").unwrap_or_default();
        let sep = if cfg!(windows) { ";" } else { ":" };
        cmd.env("PATH", format!("{}{}{}", jbin.to_string_lossy(), sep, prev));
    }

    // Capture the JVM's own stdout/stderr to files in the data dir. Without this, a GUI
    // (no-console) shell sends the child's early output to invalid handles, so a crash
    // before logback initialises would be invisible. These files are the second place to
    // check (after desktop-shell.log) when the splash hangs.
    let _ = std::fs::create_dir_all(&log);
    if let Ok(out) = std::fs::File::create(log.join("runtime-out.log")) {
        cmd.stdout(out);
    }
    if let Ok(err) = std::fs::File::create(log.join("runtime-err.log")) {
        cmd.stderr(err);
    }

    // Mirror bin/harnex.mjs: auto-load BOTH user-level agent-config.yml AND
    // application.yml so jar-internal defaults still load and the user only writes the diff. Point at
    // the SPECIFIC files, not their directory: Spring resolves a directory location by scanning for
    // application.* only, so a co-located agent-config.yml would be silently ignored.
    //
    // Agent/Provider/safety configuration is user-level. Project-local agent-config.yml is
    // intentionally not imported; workspace Memory/Skills/Automations use dedicated stores.
    // Environment variables / CLI args still sit above the user document.
    let mut locations: Vec<String> = Vec::new();
    let mut config_files: Vec<std::ffi::OsString> = Vec::new();
    for dir in [user_data_dir()] {
        for name in ["agent-config.yml", "application.yml"] {
            let cf = dir.join(name);
            if !cf.exists() {
                continue;
            }
            // Build a proper file: URL via Url::from_file_path instead of hand-splicing
            // "file:" + backslash→slash: the manual form silently truncates at the first `#`,
            // mis-encodes spaces / `%` and breaks on non-ASCII paths. from_file_path percent-encodes.
            // Spring then splits SPRING_CONFIG_ADDITIONAL_LOCATION on ',' and ';', which from_file_path
            // leaves unescaped inside the path, so encode just those two delimiters.
            let loc = match url::Url::from_file_path(&cf) {
                Ok(u) => u.as_str().replace(',', "%2C").replace(';', "%3B"),
                Err(_) => format!("file:{}", cf.to_string_lossy().replace('\\', "/"))
                    .replace(',', "%2C")
                    .replace(';', "%3B"),
            };
            if !locations.contains(&loc) {
                locations.push(loc);
                config_files.push(cf.into_os_string());
            }
        }
    }
    if !locations.is_empty() {
        // Spring merges multiple additional-locations separated by ',' (lowest-to-highest precedence).
        cmd.env("SPRING_CONFIG_ADDITIONAL_LOCATION", locations.join(","));
        // Tell the backend's PathProtection exactly which control-plane config files were injected, so
        // it write-protects these (and not unrelated agent-config.yml files in the user's repo). OS path
        // form, NEWLINE-separated (a path may legitimately contain ',' or ';') — PathProtection
        // real-path-resolves each entry.
        let joined = config_files
            .iter()
            .map(|p| p.to_string_lossy().into_owned())
            .collect::<Vec<_>>()
            .join("\n");
        cmd.env("HARNEX_CONFIG_FILES", joined);
    }
    #[cfg(target_os = "macos")]
    inject_keychain_provider_secrets(&mut cmd, &log);

    // Windows: don't flash a console window for the JVM child.
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        const CREATE_NO_WINDOW: u32 = 0x0800_0000;
        cmd.creation_flags(CREATE_NO_WINDOW);
    }

    shell_log(
        &log,
        &format!(
            "[spawn] java={} jar={} cwd={} data={} port={}",
            java.display(),
            jar.display(),
            root.display(),
            data.display(),
            port
        ),
    );

    match cmd.spawn() {
        Ok(child) => {
            shell_log(&log, &format!("[spawn] OK pid={}", child.id()));
            Some(RuntimeProcess {
                remote: None,
                child: RuntimeChild::Local(child),
                supervisor_token,
                instance_id,
            })
        }
        Err(e) => {
            shell_log(&log, &format!("[spawn] FAILED: {e}"));
            None
        }
    }
}

pub(crate) fn kill_child(managed: &mut RuntimeChild) {
    let child = match managed {
        RuntimeChild::Local(child) => child,
        RuntimeChild::Ssh(tunnel) => {
            tunnel.stop();
            return;
        }
    };
    let started = Instant::now();
    let pid = child.id();
    #[cfg(windows)]
    {
        // Tree-kill: Spring Boot may have spawned sandbox subprocesses.
        let pid = pid.to_string();
        let _ = Command::new("taskkill")
            .args(["/PID", &pid, "/T", "/F"])
            .output();
    }
    #[cfg(unix)]
    {
        terminate_unix_process_group(child, Duration::from_secs(5));
    }
    #[cfg(windows)]
    let _ = child.kill();
    let _ = child.wait();
    shell_log(
        &shell_log_dir(),
        &format!(
            "[close] pid={pid} exited in {}ms",
            started.elapsed().as_millis()
        ),
    );
}

#[cfg(unix)]
fn terminate_unix_process_group(child: &mut Child, grace: Duration) {
    let pgid = child.id() as i32;
    // Negative PID targets the process group created at spawn. Ignore ESRCH races: an already
    // exited Runtime is the desired state.
    unsafe {
        libc::kill(-pgid, libc::SIGTERM);
    }
    let deadline = Instant::now() + grace;
    while Instant::now() < deadline {
        match child.try_wait() {
            Ok(Some(_)) => return,
            Ok(None) => std::thread::sleep(Duration::from_millis(50)),
            Err(_) => break,
        }
    }
    unsafe {
        libc::kill(-pgid, libc::SIGKILL);
    }
}

#[tauri::command]
pub(crate) fn runtime_base_url() -> String {
    format!("http://127.0.0.1:{}", resolve_port())
}

#[tauri::command]
pub(crate) async fn runtime_session_token(
    app: AppHandle,
    scope_id: String,
    instance_id: String,
    rejected_token: Option<String>,
) -> Result<Value, String> {
    tauri::async_runtime::spawn_blocking(move || {
        // Clone the exact instance's credentials before IO; renewal must not hold the
        // process lock or block the desktop event loop while a Runtime is unavailable.
        let personal = app.state::<PersonalProcess>().0.lock().unwrap().as_ref()
            .filter(|runtime| runtime.scope_id == scope_id && runtime.instance_id == instance_id)
            .map(|runtime| runtime.credentials.clone());
        if let Some(credentials) = personal {
            let token = credentials.token(rejected_token.as_deref())?;
            let current = app.state::<PersonalProcess>();
            let guard = current.0.lock().unwrap();
            if !guard.as_ref().is_some_and(|runtime| runtime.scope_id == scope_id && runtime.instance_id == instance_id) {
                return Err("Personal Runtime changed during authentication renewal".into());
            }
            return Ok(json!({"scopeId":scope_id,"instanceId":instance_id,"token":token}));
        }
        Ok(app.state::<ServerProcess>()
            .0.lock().unwrap().as_ref().and_then(|runtime| {
                runtime.remote.as_ref().filter(|remote| remote.descriptor.workspace_id == scope_id && runtime.instance_id == instance_id)
                    .map(|remote| json!({"scopeId":scope_id,"instanceId":runtime.instance_id,"token":remote.descriptor.token}))
            }).unwrap_or(Value::Null))
    }).await.map_err(|error| error.to_string())?
}

#[tauri::command]
pub(crate) async fn personal_open(
    app: AppHandle,
    restart_if_current: Option<bool>,
) -> Result<Value, String> {
    tauri::async_runtime::spawn_blocking(move || {
        let process = app.state::<PersonalProcess>();
        let mut guard = process.0.lock().unwrap();
        if restart_if_current == Some(true) {
            if guard.as_ref().is_some_and(|runtime| !runtime.owns_process()) {
                return Err("This personal Runtime belongs to another host; restart it from its owning host".into());
            }
            guard.take();
        }
        if guard.as_mut().is_some_and(|runtime| runtime.owned_process_exited()) { guard.take(); }
        if let Some(runtime) = guard.as_mut() {
            match harnex_host_runtime::personal::PersonalRuntime::attach(&runtime.domain_dir, &runtime.scope_id) {
                Ok(verified) => {
                    runtime.credentials = verified.credentials.clone();
                    let value = runtime.view();
                    *app.state::<PersonalState>().0.lock().unwrap() = value.clone();
                    return Ok(value);
                }
                Err(error) if runtime.owns_process() => {
                    let mut value = runtime.view();
                    value["state"] = json!("FAILED");
                    value["instanceId"] = Value::Null;
                    value["failureMessage"] = json!(error);
                    *app.state::<PersonalState>().0.lock().unwrap() = value;
                    return Err(error);
                }
                // The other host may have exited. Re-open through the lease check;
                // an attached client never stops that host or steals a live owner.
                Err(_) => {}
            }
        }
        guard.take();
        let (_, _, scope_id) = harnex_host_runtime::personal::personal_paths(&user_data_dir())?;
        *app.state::<PersonalState>().0.lock().unwrap() = json!({"scopeId":scope_id,"runtimeProfile":"PERSONAL",
            "name":"Personal","state":"OPENING","instanceId":Value::Null});
        let result = (|| {
            let (java, jar, jar_sha256) = match bundled_runtime(&app)? {
                Some(bundle) => (bundle.java, bundle.jar, Some(bundle.jar_sha256)),
                None => (PathBuf::from(if cfg!(windows) { "java.exe" } else { "java" }),
                    env_or("HARNEX_JAR").map(PathBuf::from).unwrap_or_default(), None),
            };
            let runtime = harnex_host_runtime::personal::PersonalRuntime::open(
                harnex_host_runtime::personal::PersonalLaunch { user_data_dir: user_data_dir(), owner_kind: "tauri".into(), java, jar, jar_sha256 },
                |command| {
                    #[cfg(target_os = "macos")]
                    inject_keychain_provider_secrets(command, &shell_log_dir());
                    #[cfg(not(target_os = "macos"))]
                    let _ = command;
                })?;
            let value = runtime.view();
            *guard = Some(runtime);
            Ok::<Value, String>(value)
        })();
        *app.state::<PersonalState>().0.lock().unwrap() = match &result {
            Ok(value) => value.clone(),
            Err(error) => json!({"scopeId":scope_id,"runtimeProfile":"PERSONAL","name":"Personal",
                "state":"FAILED","instanceId":Value::Null,"failureMessage":error}),
        };
        result
    }).await.map_err(|error| error.to_string())?
}
