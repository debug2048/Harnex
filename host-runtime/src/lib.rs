//! Shared SSH Runtime ownership used by desktop and native CLI; npm hosts call the same native bridge.
use fs2::FileExt;
use reqwest::blocking::Client;
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use sha2::{Digest, Sha256};
use std::fs::{self, File, OpenOptions};
use std::io::Read;
use std::net::TcpListener;
use std::path::{Path, PathBuf};
use std::process::{Child, Command, Stdio};
use std::sync::{
    atomic::{AtomicBool, Ordering},
    Arc, Mutex,
};
use std::thread;
use std::time::{Duration, Instant};
use uuid::Uuid;
pub mod personal;
pub mod session_auth;
pub mod owned_process;
pub mod logs;
pub mod preview;
pub mod interaction;
pub mod workspace_control;
mod runtime_environment;
mod runtime_cache;
pub mod services;
mod runtime_tools;
pub mod ssh;

// Cold Windows installs can spend over two minutes loading classes and opening
// existing stores. This is an upper bound; ready/exited processes resolve early.
pub const RUNTIME_STARTUP_TIMEOUT: Duration = Duration::from_secs(300);

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct Profile {
    pub id: String,
    pub name: String,
    pub host: String,
    #[serde(default)]
    pub port: Option<u16>,
    #[serde(default)]
    pub identity_file: Option<PathBuf>,
    #[serde(default = "default_java")]
    pub java: String,
}
fn default_java() -> String {
    "java".into()
}

impl Profile {
    pub fn endpoint_label(&self) -> String {
        match self.port {
            Some(port) => format!("{}:{port}", self.host),
            None => self.host.clone(),
        }
    }
    pub fn validate(&self) -> Result<(), String> {
        if self.id.is_empty()
            || self.id.len() > 64
            || !self
                .id
                .chars()
                .all(|c| c.is_ascii_alphanumeric() || c == '-' || c == '_')
        {
            return Err("connection id must contain only letters, digits, '-' or '_'".into());
        }
        if self.name.trim().is_empty()
            || self.name.len() > 256
            || self.name.chars().any(char::is_control)
        {
            return Err("connection name is required (max 256 bytes)".into());
        }
        if self.host.is_empty()
            || self.host.len() > 512
            || self.host.starts_with('-')
            || !self
                .host
                .chars()
                .all(|c| c.is_ascii_alphanumeric() || "_.-@:%[]".contains(c))
        {
            return Err("SSH host must be a concrete SSH alias or user@hostname".into());
        }
        if self.port == Some(0) {
            return Err("SSH port must be 1..65535".into());
        }
        if self.java.is_empty() || self.java.contains(['\0', '\n', '\r']) {
            return Err("invalid remote Java executable".into());
        }
        if let Some(key) = &self.identity_file {
            let key = ssh::expand_identity(key);
            if !key.is_absolute() || !key.is_file() {
                return Err(
                    "identity file must be an existing absolute path on the connecting machine"
                        .into(),
                );
            }
        }
        Ok(())
    }

    fn ssh(&self) -> Result<Command, String> {
        self.validate()?;
        let mut command = Command::new("ssh");
        prepare_process(&mut command);
        command.args([
            "-T",
            "-o",
            "ConnectTimeout=10",
            "-o",
            "ServerAliveInterval=15",
            "-o",
            "ServerAliveCountMax=3",
        ]);
        if let Some(port) = self.port {
            command.args(["-p", &port.to_string()]);
        }
        if let Some(key) = &self.identity_file {
            command.arg("-i").arg(ssh::expand_identity(key));
        }
        interaction::configure_ssh(&mut command, self)?;
        Ok(command)
    }
}

pub struct ProfileStore {
    root: PathBuf,
}
impl ProfileStore {
    pub fn new(root: PathBuf) -> Self {
        Self { root }
    }
    pub fn list(&self) -> Result<Vec<Profile>, String> {
        let path = self.root.join("connections.json");
        if !path.exists() {
            return Ok(Vec::new());
        }
        let document: Value = serde_json::from_slice(&fs::read(path).map_err(err)?).map_err(err)?;
        if document["schemaVersion"] != 1 {
            return Err("unsupported SSH connection schema".into());
        }
        serde_json::from_value(document["connections"].clone()).map_err(err)
    }
    pub fn get(&self, id: &str) -> Result<Profile, String> {
        let profiles = self.list()?;
        if let Some(profile) = profiles.iter().find(|profile| profile.id == id) {
            return Ok(profile.clone());
        }
        let matches: Vec<_> = profiles
            .into_iter()
            .filter(|p| p.name == id || p.host == id)
            .collect();
        if matches.len() == 1 {
            return Ok(matches[0].clone());
        }
        if matches.len() > 1 {
            return Err(format!(
                "SSH alias {id} matches several saved connections; use its connection id"
            ));
        }
        if ssh::discover()["aliases"]
            .as_array()
            .is_some_and(|aliases| aliases.iter().any(|alias| alias == id))
        {
            return Ok(Profile {
                id: format!("ssh-{}", &hex_hash(id.as_bytes())[..24]),
                name: id.into(),
                host: id.into(),
                port: None,
                identity_file: None,
                java: default_java(),
            });
        }
        Err(format!("unknown SSH connection: {id}"))
    }
    pub fn save(&self, profile: Profile) -> Result<(), String> {
        profile.validate()?;
        self.update(|profiles| {
            profiles.retain(|old| old.id != profile.id);
            profiles.push(profile);
        })
    }
    pub fn remove(&self, id: &str) -> Result<(), String> {
        self.update(|profiles| profiles.retain(|profile| profile.id != id))
    }
    fn update(&self, change: impl FnOnce(&mut Vec<Profile>)) -> Result<(), String> {
        private_directory(&self.root)?;
        let lock = OpenOptions::new()
            .read(true)
            .write(true)
            .create(true)
            .truncate(false)
            .open(self.root.join("connections.lock"))
            .map_err(err)?;
        lock.try_lock_exclusive()
            .map_err(|_| "connections are being updated; retry".to_string())?;
        let mut profiles = self.list()?;
        change(&mut profiles);
        let path = self.root.join("connections.json");
        write_private(&path, &json!({"schemaVersion":1,"connections":profiles}))
    }
    pub fn owner_domain(&self, owner: &str, profile: &str) -> Result<String, String> {
        private_directory(&self.root)?;
        let path = self.root.join("client-id");
        if !path.exists() {
            use std::io::Write;
            match OpenOptions::new().write(true).create_new(true).open(&path) {
                Ok(mut file) => file
                    .write_all(Uuid::new_v4().to_string().as_bytes())
                    .map_err(err)?,
                Err(error) if error.kind() == std::io::ErrorKind::AlreadyExists => {}
                Err(error) => return Err(err(error)),
            }
        }
        let client = fs::read_to_string(path).map_err(err)?;
        if client.trim().is_empty() {
            return Err("client identity is being initialized; retry".into());
        }
        Ok(format!(
            "remote-{}",
            &hex_hash(format!("{client}\n{owner}\n{profile}").as_bytes())[..40]
        ))
    }
    pub fn owner_lease(&self, owner: &str, profile: &str) -> Result<File, String> {
        let domain = self.owner_domain(owner, profile)?;
        let directory = self.root.join("connection-owners");
        private_directory(&directory)?;
        let file = OpenOptions::new()
            .read(true)
            .write(true)
            .create(true)
            .truncate(false)
            .open(directory.join(format!("{domain}.lock")))
            .map_err(err)?;
        file.try_lock_exclusive().map_err(|_| {
            "this remote connection is already owned by another client in the same domain"
                .to_string()
        })?;
        Ok(file)
    }
    /// GUI projects may remain on the server after their transport is detached.
    /// A separate durable owner per workspace allows another project on the same host to open.
    pub fn workspace_domain(
        &self,
        owner: &str,
        profile: &str,
        workspace: &str,
    ) -> Result<String, String> {
        self.owner_domain(&format!("{owner}/workspace/{workspace}"), profile)
    }
}

#[derive(Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Descriptor {
    pub schema_version: u32,
    pub root: String,
    pub workspace_id: String,
    pub instance_id: String,
    pub domain_id: String,
    pub port: u16,
    pub pid: u32,
    pub process_start: String,
    pub token: String,
    pub supervisor_token: String,
    pub protocol_version: String,
    #[serde(default = "managed_ownership")]
    pub ownership: String,
    #[serde(default)] pub runtime_version: String,
    #[serde(default)] pub runtime_sha256: String,
    #[serde(default)] pub target_version: String,
    #[serde(default)] pub target_sha256: String,
    #[serde(default)] pub update_available: bool,
    #[serde(default)] pub can_upgrade: bool,
}
fn managed_ownership() -> String { "managed".into() }
impl Descriptor {
    fn validate(&self, domain: &str) -> Result<(), String> {
        if self.schema_version != 1
            || self.domain_id != domain
            || self.port == 0
            || self.pid == 0
            || self.protocol_version != "1.0"
            || !self.root.starts_with('/')
            || self.root.contains('\0')
            || self.workspace_id.is_empty()
            || self.instance_id.is_empty()
            || !["managed", "attached"].contains(&self.ownership.as_str())
            || self.ownership == "managed" && (self.token.is_empty() || self.supervisor_token.is_empty())
            || self.process_start.is_empty()
        {
            return Err("remote Runtime returned an invalid identity".into());
        }
        Ok(())
    }
}

#[derive(Clone, Serialize, Deserialize)]
pub struct RemoteControl {
    pub profile: Profile,
    pub jar: String,
    pub descriptor: Descriptor,
    #[serde(default)]
    pub routing_key: Option<String>,
}
impl RemoteControl {
    pub fn close(&self) -> Result<(), String> {
        interaction::with_lifecycle(&self.profile, || self.close_inner())
    }
    fn close_inner(&self) -> Result<(), String> {
        if self.descriptor.ownership == "attached" { return Err("This service is managed on the server; disconnecting does not stop it".into()); }
        if let Some(expected) = &self.routing_key {
            if &ssh::routing_key(&self.profile)? != expected {
                return Err("the SSH destination settings changed; restore the original host settings before closing this remote Runtime".into());
            }
        }
        self.descriptor.validate(&self.descriptor.domain_id)?;
        let hash = self
            .jar
            .strip_prefix(".harnex/runtime-cache/")
            .and_then(|path| path.strip_suffix(".jar"));
        if !hash.is_some_and(|hash| {
            hash.len() == 64 && hash.bytes().all(|byte| byte.is_ascii_hexdigit())
        }) {
            return Err("invalid bound remote Runtime artifact".into());
        }
        let value = invoke(
            &self.profile,
            &self.jar,
            "close",
            &[
                ("--workspace", &self.descriptor.root),
                ("--domain", &self.descriptor.domain_id),
                ("--instance", &self.descriptor.instance_id),
            ],
            Duration::from_secs(35),
        )?;
        if value["closed"] != true {
            return Err("remote stop was not confirmed".into());
        }
        Ok(())
    }
    pub fn upgrade(&self, jar: &Path) -> Result<(), String> {
        if self.descriptor.ownership == "attached" { return Err("UPGRADE_UNSUPPORTED: update this independently managed service on its server".into()); }
        interaction::with_lifecycle(&self.profile, || {
            if self.routing_key.as_ref().is_some_and(|key| ssh::routing_key(&self.profile).as_ref() != Ok(key)) {
                return Err("SSH destination changed; reconnect before updating".into());
            }
            let (profile, remote_jar) = runtime_cache::prepare(&self.profile, jar)?;
            interaction::progress("starting");
            let value = invoke(&profile, &remote_jar, "upgrade", &[("--workspace", &self.descriptor.root),
                ("--domain", &self.descriptor.domain_id), ("--instance", &self.descriptor.instance_id)], Duration::from_secs(150))?;
            let result: Descriptor = serde_json::from_value(value).map_err(err)?;
            result.validate(&self.descriptor.domain_id)?;
            if result.workspace_id != self.descriptor.workspace_id || result.root != self.descriptor.root {
                return Err("updated remote Runtime identity did not match the selected project".into());
            }
            Ok(())
        })
    }
}

pub struct Tunnel {
    stop: Arc<AtomicBool>,
    connected: Arc<AtomicBool>,
    failure: Arc<Mutex<Option<String>>>,
    worker: Option<thread::JoinHandle<()>>,
    token: Arc<Mutex<String>>,
}
impl Tunnel {
    fn start(
        profile: Profile,
        descriptor: Descriptor,
        local_port: u16,
        routing_key: String,
    ) -> Self {
        let prompt_attempt = interaction::current_attempt();
        let stop = Arc::new(AtomicBool::new(false));
        let connected = Arc::new(AtomicBool::new(false));
        let failure = Arc::new(Mutex::new(None));
        let token = Arc::new(Mutex::new(descriptor.token.clone()));
        let current_token = token.clone();
        let (stop_worker, ready, issue) = (stop.clone(), connected.clone(), failure.clone());
        let worker = thread::spawn(move || {
            let client = match Client::builder()
                .no_proxy()
                .timeout(Duration::from_secs(2))
                .build()
            {
                Ok(client) => client,
                Err(error) => {
                    *issue.lock().unwrap() = Some(err(error));
                    return;
                }
            };
            let mut child: Option<owned_process::OwnedChild> = None;
            let mut next_probe = Instant::now();
            let mut unhealthy_since: Option<Instant> = None;
            let mut next_spawn = Instant::now();
            let mut retry_delay = Duration::from_millis(500);
            let mut foreground = true;
            while !stop_worker.load(Ordering::Acquire) {
                if child
                    .as_mut()
                    .is_some_and(|process| process.try_wait().ok().flatten().is_some())
                {
                    child = None;
                    ready.store(false, Ordering::Release);
                    next_spawn = Instant::now() + retry_delay;
                    retry_delay = (retry_delay * 2).min(Duration::from_secs(10));
                }
                if child.is_none() {
                    if Instant::now() < next_spawn {
                        thread::sleep(Duration::from_millis(200));
                        continue;
                    }
                    let route = ssh::routing_key_interruptible(&profile, Some(&stop_worker));
                    if !route.as_ref().is_ok_and(|current| current == &routing_key) {
                        *issue.lock().unwrap() = Some(route.err().unwrap_or_else(|| "the SSH destination changed; restore its original settings to reconnect the existing Runtime".into()));
                        ready.store(false, Ordering::Release);
                        next_spawn = Instant::now() + Duration::from_secs(2);
                        continue;
                    }
                    *issue.lock().unwrap() = None;
                    if stop_worker.load(Ordering::Acquire) {
                        break;
                    }
                    let spawned = (|| {
                        let mut command = profile.ssh()?;
                        // Initial tunnel authentication belongs to the opening flow; later reconnects reuse the session.
                        command.env(
                            "HARNEX_SSH_ATTEMPT",
                            if foreground { &prompt_attempt } else { "" },
                        );
                        command
                            .args(["-N", "-o", "ExitOnForwardFailure=yes", "-L"])
                            .arg(format!(
                                "127.0.0.1:{local_port}:127.0.0.1:{}",
                                descriptor.port
                            ))
                            .arg(&profile.host)
                            .stdin(Stdio::null())
                            .stdout(Stdio::null())
                            .stderr(Stdio::null());
                        owned_process::spawn(&mut command).map_err(err)
                    })();
                    match spawned {
                        Ok(process) => {
                            child = Some(process);
                            unhealthy_since = Some(Instant::now());
                        }
                        Err(error) => {
                            *issue.lock().unwrap() = Some(error);
                            break;
                        }
                    }
                }
                if Instant::now() >= next_probe {
                    next_probe = Instant::now() + Duration::from_secs(2);
                    let credential = current_token.lock().unwrap().clone();
                    let public_probe = descriptor.ownership == "attached" && credential.is_empty();
                    let resource = if public_probe { "/api/v1/protocol" } else { "/api/v1/workspace" };
                    let response = client
                        .get(format!("http://127.0.0.1:{local_port}{resource}"))
                        .bearer_auth(&credential)
                        .header("X-Harnex-Scope-Id", &descriptor.workspace_id)
                        .header("X-Harnex-Instance-Id", &descriptor.instance_id)
                        .send();
                    let valid = match response {
                        Ok(response) if response.status().is_success() => {
                            let headers_match = response.headers().get("X-Harnex-Scope-Id").and_then(|value| value.to_str().ok()) == Some(&descriptor.workspace_id)
                                && response.headers().get("X-Harnex-Instance-Id").and_then(|value| value.to_str().ok()) == Some(&descriptor.instance_id);
                            let actual = response.json::<Value>();
                            if actual.as_ref().is_ok_and(|actual| {
                                if public_probe { return headers_match && actual["name"] == "harnex-local-api" && actual["version"] == "1.0" && actual["runtimeProfile"] == "PROJECT"; }
                                actual["workspaceId"].as_str() == Some(&descriptor.workspace_id)
                                    && actual["instanceId"].as_str()
                                        == Some(&descriptor.instance_id)
                                    && actual["root"].as_str() == Some(&descriptor.root)
                            }) {
                                true
                            } else {
                                *issue.lock().unwrap() = Some(
                                    "SSH endpoint identity changed; reopen the workspace".into(),
                                );
                                break;
                            }
                        }
                        Ok(response) if matches!(response.status().as_u16(), 401 | 403 | 409) => {
                            *issue.lock().unwrap() = Some("remote Runtime identity or authorization changed; reopen the workspace".into());
                            break;
                        }
                        _ => false,
                    };
                    ready.store(valid, Ordering::Release);
                    if valid {
                        foreground = false;
                        unhealthy_since = None;
                        retry_delay = Duration::from_millis(500);
                    } else if foreground && interaction::awaiting_attempt(&prompt_attempt) {
                        unhealthy_since = Some(Instant::now());
                    } else {
                        let since = unhealthy_since.get_or_insert_with(Instant::now);
                        if since.elapsed() > Duration::from_secs(10) {
                            if let Some(mut old) = child.take() {
                                terminate_transport(&mut old);
                            }
                        }
                    }
                }
                thread::sleep(Duration::from_millis(200));
            }
            ready.store(false, Ordering::Release);
            if let Some(mut child) = child {
                terminate_transport(&mut child);
            }
        });
        Self {
            stop,
            connected,
            failure,
            worker: Some(worker),
            token,
        }
    }
    pub fn connected(&self) -> bool {
        self.connected.load(Ordering::Acquire)
    }
    pub fn failure(&self) -> Option<String> {
        self.failure.lock().unwrap().clone()
    }
    fn authenticate(&self, value: String) { *self.token.lock().unwrap() = value; }
    /// The transport is owned by a host thread, so local manifests refer to that host's lifetime.
    pub fn id(&self) -> u32 {
        std::process::id()
    }
    pub fn try_wait(&mut self) -> std::io::Result<Option<std::process::ExitStatus>> {
        Ok(None)
    }
    pub fn stop(&mut self) {
        self.stop.store(true, Ordering::Release);
        if let Some(worker) = self.worker.take() {
            let _ = worker.join();
        }
    }
}
impl Drop for Tunnel {
    fn drop(&mut self) {
        self.stop();
    }
}

pub struct Connection {
    pub tunnel: Option<Tunnel>,
    pub control: RemoteControl,
    pub endpoint: String,
    pub owner_lease: Option<File>,
}
impl Connection {
    pub fn connect(
        profile: &Profile,
        jar: &Path,
        domain: &str,
        root: &str,
        local_port: u16,
        expected_workspace: Option<&str>,
    ) -> Result<Self, String> {
        ssh::validate_root(root)?;
        interaction::with_lifecycle(profile, || {
            Self::connect_inner(profile, jar, domain, root, local_port, expected_workspace)
        })
    }
    fn connect_inner(
        profile: &Profile,
        jar: &Path,
        domain: &str,
        root: &str,
        local_port: u16,
        expected_workspace: Option<&str>,
    ) -> Result<Self, String> {
        let routing_key = ssh::routing_key(profile)?;
        let (prepared, remote_jar) = runtime_cache::prepare(profile, jar)?;
        let profile = &prepared;
        interaction::progress("runtime");
        if let Some(expected) = expected_workspace {
            let described = invoke(
                profile,
                &remote_jar,
                "describe",
                &[("--workspace", root)],
                Duration::from_secs(20),
            )?;
            if described["workspaceId"].as_str() != Some(expected) {
                return Err("remote workspace identity changed; select its directory again".into());
            }
        }
        interaction::progress("starting");
        let value = invoke(
            profile,
            &remote_jar,
            "open",
            &[("--workspace", root), ("--domain", domain)],
            Duration::from_secs(125),
        )?;
        let descriptor: Descriptor = serde_json::from_value(value).map_err(err)?;
        descriptor.validate(domain)?;
        if expected_workspace.is_some_and(|expected| descriptor.workspace_id != expected) {
            return Err("remote workspace identity changed while opening; the Runtime is retained for identity-checked reconnection".into());
        }
        interaction::progress("tunnel");
        let tunnel = Tunnel::start(
            profile.clone(),
            descriptor.clone(),
            local_port,
            routing_key.clone(),
        );
        let endpoint = format!("http://127.0.0.1:{local_port}");
        let mut deadline = Instant::now() + Duration::from_secs(15);
        let mut last_tick = Instant::now();
        while Instant::now() < deadline {
            interaction::check_cancelled()?;
            let now = Instant::now();
            if interaction::awaiting_authentication() {
                deadline += now - last_tick;
            }
            last_tick = now;
            if let Some(error) = tunnel.failure() {
                return Err(error);
            }
            if tunnel.connected() {
                return Ok(Self {
                    tunnel: Some(tunnel),
                    endpoint,
                    owner_lease: None,
                    control: RemoteControl {
                        profile: profile.clone(),
                        jar: remote_jar,
                        descriptor,
                        routing_key: Some(routing_key),
                    },
                });
            }
            thread::sleep(Duration::from_millis(150));
        }
        Err(
            "SSH tunnel did not become ready. The remote Runtime is retained for reconnection."
                .into(),
        )
    }
    pub fn close(&mut self) -> Result<(), String> {
        if self.control.descriptor.ownership != "attached" { self.control.close()?; }
        if let Some(tunnel) = self.tunnel.as_mut() {
            tunnel.stop();
        }
        Ok(())
    }
    pub fn into_parts(mut self) -> (Tunnel, RemoteControl, String) {
        (
            self.tunnel.take().expect("owned SSH transport"),
            self.control.clone(),
            self.endpoint.clone(),
        )
    }
}
impl Drop for Connection {
    fn drop(&mut self) {
        if let Some(tunnel) = self.tunnel.as_mut() {
            tunnel.stop();
        }
    }
}

pub fn inspect(
    profile: &Profile,
    jar: &Path,
    operation: &str,
    root: Option<&str>,
) -> Result<Value, String> {
    if matches!(operation, "probe" | "directories") {
        return ssh::inspect(profile, operation, root);
    }
    if !["probe", "describe", "directories"].contains(&operation) {
        return Err("invalid inspection operation".into());
    }
    let root = root.unwrap_or("~");
    ssh::validate_root(root)?;
    let (prepared, remote_jar) = runtime_cache::prepare(profile, jar)?;
    let profile = &prepared;
    interaction::progress("runtime");
    let arguments = vec![("--workspace", root)];
    invoke(
        profile,
        &remote_jar,
        operation,
        &arguments,
        Duration::from_secs(25),
    )
}

pub fn close_owned(
    profile: &Profile,
    jar: &Path,
    domain: &str,
    root: &str,
    instance: &str,
) -> Result<(), String> {
    ssh::validate_root(root)?;
    let prepared = runtime_environment::prepare(profile)?;
    let profile = &prepared;
    let remote_jar = prepare_jar(profile, jar)?;
    let result = invoke(
        profile,
        &remote_jar,
        "close",
        &[
            ("--workspace", root),
            ("--domain", domain),
            ("--instance", instance),
        ],
        Duration::from_secs(35),
    )?;
    if result["closed"] != true {
        return Err("remote stop was not confirmed".into());
    }
    Ok(())
}

fn prepare_jar(profile: &Profile, jar: &Path) -> Result<String, String> {
    interaction::progress("cache");
    let hash = runtime_cache::fingerprint(jar)?;
    let target = format!(".harnex/runtime-cache/{hash}.jar");
    let mut probe = profile.ssh()?;
    probe.arg(&profile.host).arg(runtime_cache::pin_script(&target, &format!(
        "cd \"$HOME\" && test -f {} && sha256sum {}",
        quote(&target),
        quote(&target)
    )));
    if let Ok(output) = run(probe, Stdio::null(), Duration::from_secs(20)) {
        if output.split_whitespace().next() == Some(hash.as_str()) {
            return Ok(target);
        }
    }
    let temporary = format!("{target}.{}.tmp", Uuid::new_v4());
    interaction::progress("upload");
    let script = format!("set -eu; cd \"$HOME\"; umask 077; mkdir -p .harnex/runtime-cache; t={}; trap 'rm -f -- \"$t\"' EXIT; cat > \"$t\"; test \"$(sha256sum \"$t\" | cut -d ' ' -f 1)\" = {}; mv -f -- \"$t\" {}",
        quote(&temporary), quote(&hash), quote(&target));
    let mut upload = profile.ssh()?;
    upload.arg(&profile.host).arg(runtime_cache::pin_script(&target, &script));
    run(
        upload,
        Stdio::from(File::open(jar).map_err(err)?),
        Duration::from_secs(180),
    )?;
    Ok(target)
}

fn invoke(
    profile: &Profile,
    jar: &str,
    operation: &str,
    arguments: &[(&str, &str)],
    timeout: Duration,
) -> Result<Value, String> {
    let mut remote = format!(
        "cd \"$HOME\" && PATH=\"$HOME\"/{}:\"$PATH\" exec {} -jar {} --harnex-remote {}",
        quote(&runtime_tools::bin_directory(jar)),
        quote(&profile.java),
        quote(jar),
        quote(operation)
    );
    for (key, value) in arguments {
        if value.contains(['\0', '\n', '\r']) {
            return Err("remote arguments cannot contain control characters".into());
        }
        remote.push_str(&format!(" {} {}", quote(key), quote(value)));
    }
    let mut command = profile.ssh()?;
    command.arg(&profile.host).arg(runtime_cache::pin_script(jar, &remote));
    let output = run(command, Stdio::null(), timeout)?;
    // Login banners are allowed; only the final complete JSON record is the control protocol.
    let line = output
        .lines()
        .rev()
        .find(|line| line.trim_start().starts_with('{'))
        .ok_or("remote host returned no Runtime response")?;
    serde_json::from_str(line)
        .map_err(|_| "remote host returned an invalid Runtime response".into())
}

fn run(command: Command, input: Stdio, timeout: Duration) -> Result<String, String> {
    run_interruptible(command, input, timeout, None)
}

fn run_interruptible(
    mut command: Command,
    input: Stdio,
    timeout: Duration,
    stop: Option<&AtomicBool>,
) -> Result<String, String> {
    interaction::check_cancelled()?;
    command
        .stdin(input)
        .stdout(Stdio::piped())
        .stderr(Stdio::piped());
    let mut child = owned_process::spawn(&mut command)
        .map_err(|error| format!("cannot start SSH: {error}"))?;
    let stdout = child.stdout.take().ok_or("SSH stdout unavailable")?;
    let stderr = child.stderr.take().ok_or("SSH stderr unavailable")?;
    let output = thread::spawn(move || {
        let mut bytes = Vec::new();
        let _ = stdout.take(2_000_000).read_to_end(&mut bytes);
        bytes
    });
    let errors = thread::spawn(move || {
        let mut bytes = Vec::new();
        let _ = stderr.take(128_000).read_to_end(&mut bytes);
        bytes
    });
    let mut deadline = Instant::now() + timeout;
    let mut last_tick = Instant::now();
    let status = loop {
        if stop.is_some_and(|stop| stop.load(Ordering::Acquire)) {
            terminate_transport(&mut child);
            return Err("SSH connection cancelled".into());
        }
        if let Err(error) = interaction::check_cancelled() {
            terminate_transport(&mut child);
            return Err(error);
        }
        let now = Instant::now();
        if interaction::awaiting_authentication() {
            deadline += now - last_tick;
        }
        last_tick = now;
        if let Some(status) = child.try_wait().map_err(err)? {
            break status;
        }
        if Instant::now() >= deadline {
            terminate_transport(&mut child);
            return Err(
                "SSH operation timed out; reconnect to check remote state before retrying a task"
                    .into(),
            );
        }
        thread::sleep(Duration::from_millis(50));
    };
    while !output.is_finished() || !errors.is_finished() {
        if Instant::now() >= deadline {
            return Err("SSH output did not close before the operation deadline".into());
        }
        thread::sleep(Duration::from_millis(20));
    }
    let stdout = String::from_utf8_lossy(&output.join().unwrap_or_default()).into_owned();
    let stderr = String::from_utf8_lossy(&errors.join().unwrap_or_default()).into_owned();
    if !status.success() {
        let detail: String = stderr.chars().take(2000).collect();
        return Err(format!("SSH operation failed: {}", detail.trim()));
    }
    Ok(stdout)
}

pub fn terminate_transport(child: &mut Child) {
    if child.try_wait().ok().flatten().is_some() {
        return;
    }
    #[cfg(unix)]
    unsafe {
        libc::kill(-(child.id() as i32), libc::SIGKILL);
    }
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        let _ = Command::new("taskkill")
            .args(["/F", "/T", "/PID", &child.id().to_string()])
            .creation_flags(0x08000000)
            .stdout(Stdio::null())
            .stderr(Stdio::null())
            .status();
    }
    let _ = child.kill();
    let _ = child.wait();
}

fn prepare_process(command: &mut Command) {
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        command.creation_flags(0x08000000);
    }
    #[cfg(unix)]
    {
        use std::os::unix::process::CommandExt;
        command.process_group(0);
    }
}

pub fn free_port() -> Result<u16, String> {
    TcpListener::bind(("127.0.0.1", 0))
        .and_then(|listener| listener.local_addr())
        .map(|address| address.port())
        .map_err(err)
}
pub fn quote(value: &str) -> String {
    format!("'{}'", value.replace('\'', "'\\''"))
}
fn hex_hash(value: &[u8]) -> String {
    format!("{:x}", Sha256::digest(value))
}
fn err(error: impl std::fmt::Display) -> String {
    error.to_string()
}

fn private_directory(path: &Path) -> Result<(), String> {
    fs::create_dir_all(path).map_err(err)?;
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        fs::set_permissions(path, fs::Permissions::from_mode(0o700)).map_err(err)?;
    }
    Ok(())
}
fn write_private(path: &Path, value: &Value) -> Result<(), String> {
    let temporary = path.with_extension(format!("{}.tmp", Uuid::new_v4()));
    fs::write(&temporary, serde_json::to_vec_pretty(value).map_err(err)?).map_err(err)?;
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        fs::set_permissions(&temporary, fs::Permissions::from_mode(0o600)).map_err(err)?;
    }
    #[cfg(windows)]
    let replaced = {
        use std::os::windows::ffi::OsStrExt;
        use windows_sys::Win32::Storage::FileSystem::{
            MoveFileExW, MOVEFILE_REPLACE_EXISTING, MOVEFILE_WRITE_THROUGH,
        };
        let from: Vec<u16> = temporary.as_os_str().encode_wide().chain(Some(0)).collect();
        let to: Vec<u16> = path.as_os_str().encode_wide().chain(Some(0)).collect();
        if unsafe {
            MoveFileExW(
                from.as_ptr(),
                to.as_ptr(),
                MOVEFILE_REPLACE_EXISTING | MOVEFILE_WRITE_THROUGH,
            )
        } == 0
        {
            Err(std::io::Error::last_os_error())
        } else {
            Ok(())
        }
    };
    #[cfg(not(windows))]
    let replaced = fs::rename(&temporary, path);
    if let Err(error) = replaced {
        let _ = fs::remove_file(&temporary);
        return Err(err(error));
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    fn profile(host: &str) -> Profile {
        Profile {
            id: "dev".into(),
            name: "Dev".into(),
            host: host.into(),
            port: None,
            identity_file: None,
            java: default_java(),
        }
    }
    #[test]
    fn obsolete_jar_is_rejected_before_ssh_can_start_an_unmanaged_server() {
        let path = std::env::temp_dir().join(format!("harnex-old-{}.jar", Uuid::new_v4()));
        fs::write(&path, b"old runtime without remote entry point").unwrap();
        let error = prepare_jar(&profile("unreachable.invalid"), &path).unwrap_err();
        fs::remove_file(path).unwrap();
        assert!(error.contains("predates the SSH bootstrap protocol"));
    }
    #[test]
    fn host_arguments_cannot_be_flags_or_shell_programs() {
        for host in [
            "-oProxyCommand=evil",
            "host;touch x",
            "user@host\nother",
            "a b",
            "$(whoami)",
        ] {
            assert!(profile(host).validate().is_err());
        }
        for host in ["devbox", "ops@dev.example", "user@2001:db8::1"] {
            assert!(profile(host).validate().is_ok());
        }
    }
    #[test]
    fn remote_paths_preserve_spaces_quotes_and_unicode() {
        assert_eq!(quote("/srv/用户's repo"), "'/srv/用户'\\''s repo'");
        assert_eq!(quote("~/repo"), "'~/repo'"); // expanded by Java, not a shell
    }
    #[test]
    fn profiles_share_a_locked_store_and_stable_owner_identity() {
        let root = std::env::temp_dir().join(format!("harnex-profile-test-{}", Uuid::new_v4()));
        let store = ProfileStore::new(root.clone());
        store.save(profile("devbox")).unwrap();
        assert_eq!(store.get("dev").unwrap().host, "devbox");
        assert_eq!(store.get("Dev").unwrap().id, "dev");
        assert_eq!(store.get("devbox").unwrap().id, "dev");
        assert_eq!(
            store.owner_domain("web", "dev").unwrap(),
            store.owner_domain("web", "dev").unwrap()
        );
        assert_ne!(
            store.owner_domain("web", "dev").unwrap(),
            store.owner_domain("tauri", "dev").unwrap()
        );
        assert_ne!(
            store.workspace_domain("web", "dev", "project-a").unwrap(),
            store.workspace_domain("web", "dev", "project-b").unwrap()
        );
        assert_eq!(
            store.workspace_domain("web", "dev", "project-a").unwrap(),
            ProfileStore::new(root.clone())
                .workspace_domain("web", "dev", "project-a")
                .unwrap()
        );
        store.remove("dev").unwrap();
        assert!(store.list().unwrap().is_empty());
        fs::remove_dir_all(root).unwrap();
    }
}
