//! Shared, cancellable connection attempts for desktop, Web owner and terminal clients.
//! Secrets live only in the owner process. The askpass helper uses an authenticated loopback channel.
use super::*;
use std::cell::RefCell;
use std::collections::{HashMap, HashSet};
use std::io::{BufRead, BufReader, Write};
use std::net::TcpStream;
use std::sync::{OnceLock, Weak};

static HUB: OnceLock<Arc<Hub>> = OnceLock::new();
thread_local! { static CURRENT: RefCell<Option<(Weak<Hub>, String)>> = const { RefCell::new(None) }; }
thread_local! { static EXTERNAL_STATUS: RefCell<Option<(Instant, Value)>> = const { RefCell::new(None) }; }

struct Attempt {
    profile_key: String,
    profile: Profile,
    operation: String,
    root: String,
    external: bool,
    state: String,
    phase: String,
    challenge: Option<Value>,
    answer: Option<String>,
    result: Option<Value>,
    error: Option<String>,
    touched: Instant,
}
#[derive(Default)]
struct State {
    attempts: HashMap<String, Attempt>,
    secrets: HashMap<(String, String), String>,
    used: HashSet<(String, String)>,
    observer: Option<Instant>,
    terminal_observer: bool,
}
pub struct Hub {
    endpoint: String,
    token: String,
    state: Mutex<State>,
    workers: std::sync::atomic::AtomicUsize,
    closing: AtomicBool,
}

pub fn global() -> Result<Arc<Hub>, String> {
    if let Some(hub) = HUB.get() {
        return Ok(hub.clone());
    }
    let hub = Hub::new()?;
    let _ = HUB.set(hub.clone());
    Ok(HUB.get().cloned().unwrap_or(hub))
}

impl Hub {
    pub fn observe_terminal(&self, active: bool) {
        self.state.lock().unwrap().terminal_observer = active;
    }
    pub fn shutdown(&self) {
        self.closing.store(true, Ordering::Release);
        let external = {
            let mut state = self.state.lock().unwrap();
            let mut external = false;
            for attempt in state.attempts.values_mut().filter(|a| a.state == "running") {
                external |= attempt.external;
                attempt.state = "cancelled".into();
                attempt.answer = None;
                attempt.challenge = None;
            }
            external
        };
        let started = Instant::now();
        while started.elapsed() < Duration::from_secs(5)
            && (self.workers.load(Ordering::Acquire) != 0
                || external && started.elapsed() < Duration::from_millis(500))
        {
            thread::sleep(Duration::from_millis(20));
        }
    }
    pub fn new() -> Result<Arc<Self>, String> {
        let listener = TcpListener::bind(("127.0.0.1", 0)).map_err(err)?;
        let hub = Arc::new(Self {
            endpoint: listener.local_addr().map_err(err)?.to_string(),
            token: Uuid::new_v4().to_string(),
            state: Mutex::new(State::default()),
            workers: std::sync::atomic::AtomicUsize::new(0),
            closing: AtomicBool::new(false),
        });
        let owner = Arc::downgrade(&hub);
        listener.set_nonblocking(true).map_err(err)?;
        thread::spawn(move || {
            while let Some(hub) = owner.upgrade() {
                match listener.accept() {
                    Ok((stream, _)) => {
                        thread::spawn(move || {
                            let _ = hub.serve(stream);
                        });
                    }
                    Err(e) if e.kind() == std::io::ErrorKind::WouldBlock => {
                        thread::sleep(Duration::from_millis(50))
                    }
                    Err(_) => break,
                }
            }
        });
        Ok(hub)
    }
    /// Private parent/child bootstrap data; never return this to the browser.
    pub fn environment(&self) -> Value {
        json!({"HARNEX_SSH_BROKER":self.endpoint,"HARNEX_SSH_BROKER_TOKEN":self.token})
    }

    pub fn request(
        self: &Arc<Self>,
        action: &str,
        args: Value,
        store: PathBuf,
        jar: Option<PathBuf>,
    ) -> Result<Value, String> {
        if matches!(action, "begin" | "start") && self.closing.load(Ordering::Acquire) {
            return Err("SSH connection owner is closing".into());
        }
        match action {
            "discover" => Ok(ssh::discover()),
            "parse" => ssh::parse_destination(args["input"].as_str().unwrap_or("")),
            "pending" => {
                let mut state = self.state.lock().unwrap();
                state.observer = Some(Instant::now());
                Ok(state
                    .attempts
                    .iter()
                    .filter(|(_, a)| a.external && a.state == "running")
                    .min_by_key(|(_, a)| a.touched)
                    .map(|(id, a)| public_attempt(id, a))
                    .unwrap_or(Value::Null))
            }
            "prepared" => {
                let profile = ProfileStore::new(store).get(
                    args["profileId"]
                        .as_str()
                        .ok_or("SSH connection id missing")?,
                )?;
                let state = self.state.lock().unwrap();
                let attempt = state
                    .attempts
                    .get(args["id"].as_str().ok_or("preparation id missing")?)
                    .ok_or("remote preparation expired; retry opening the directory")?;
                if attempt.state != "succeeded"
                    || !["prepare", "service"].contains(&attempt.operation.as_str())
                    || attempt.profile_key != profile_key(&profile)
                    || args["root"].as_str() != Some(&attempt.root)
                {
                    return Err(
                        "remote preparation no longer matches this connection and directory".into(),
                    );
                }
                attempt
                    .result
                    .clone()
                    .ok_or_else(|| "remote preparation result is missing".into())
            }
            "begin" => {
                let profile: Profile = if args["profile"].is_object() {
                    serde_json::from_value(args["profile"].clone()).map_err(err)?
                } else {
                    ProfileStore::new(store)
                        .get(args["profileId"].as_str().ok_or("connection id missing")?)?
                };
                profile.validate()?;
                let id = Uuid::new_v4().to_string();
                let mut state = self.state.lock().unwrap();
                prune_attempts(&mut state);
                if state.attempts.len() >= 64 {
                    return Err("too many connection attempts; retry later".into());
                }
                state.attempts.insert(
                    id.clone(),
                    Attempt {
                        profile_key: profile_key(&profile),
                        profile,
                        operation: "lifecycle".into(),
                        root: String::new(),
                        external: true,
                        state: "running".into(),
                        phase: "runtime".into(),
                        challenge: None,
                        answer: None,
                        result: None,
                        error: None,
                        touched: Instant::now(),
                    },
                );
                Ok(json!({"id":id}))
            }
            "finish" => {
                let mut state = self.state.lock().unwrap();
                let attempt = state
                    .attempts
                    .get_mut(args["id"].as_str().ok_or("attempt id missing")?)
                    .filter(|a| a.external)
                    .ok_or("connection attempt expired")?;
                if attempt.state == "running" {
                    attempt.state = "succeeded".into();
                }
                attempt.challenge = None;
                attempt.answer = None;
                Ok(Value::Null)
            }
            "start" => {
                let profile: Profile =
                    serde_json::from_value(args["profile"].clone()).map_err(err)?;
                profile.validate()?;
                let operation = args["operation"].as_str().unwrap_or("connect").to_string();
                if !matches!(operation.as_str(), "connect" | "directories" | "prepare" | "services" | "service") {
                    return Err("invalid connection operation".into());
                }
                let root = args["root"].as_str().unwrap_or("~").to_string();
                ssh::validate_root(&root)?;
                let service = if operation == "service" {
                    Some(serde_json::from_value::<super::services::ServiceRef>(args["service"].clone()).map_err(err)?)
                } else { None };
                let id = Uuid::new_v4().to_string();
                let mut state = self.state.lock().unwrap();
                prune_attempts(&mut state);
                if state
                    .attempts
                    .values()
                    .any(|a| a.state == "running" && a.profile_key == profile_key(&profile))
                {
                    return Err("a connection to this host is already in progress".into());
                }
                if state.attempts.len() >= 64 {
                    return Err("too many connection attempts; retry later".into());
                }
                state.attempts.insert(
                    id.clone(),
                    Attempt {
                        profile_key: profile_key(&profile),
                        profile: profile.clone(),
                        operation: operation.clone(),
                        root: root.clone(),
                        external: false,
                        state: "running".into(),
                        phase: "connecting".into(),
                        challenge: None,
                        answer: None,
                        result: None,
                        error: None,
                        touched: Instant::now(),
                    },
                );
                drop(state);
                let (hub, attempt_id) = (self.clone(), id.clone());
                self.workers.fetch_add(1, Ordering::AcqRel);
                thread::spawn(move || {
                    CURRENT.with(|current| {
                        *current.borrow_mut() = Some((Arc::downgrade(&hub), attempt_id.clone()))
                    });
                    let result = (|| {
                        progress(if operation == "prepare" {
                            "environment"
                        } else {
                            "connecting"
                        });
                        let result = if operation == "services" {
                            super::services::discover(&profile, &root)?
                        } else if operation == "service" {
                            super::services::inspect(&profile, service.as_ref().ok_or("existing service reference is required")?)?
                        } else if operation == "prepare" {
                            let jar = jar.as_deref().ok_or("packaged Runtime is unavailable")?;
                            super::inspect(&profile, jar, "describe", Some(&root))?
                        } else {
                            ssh::inspect(&profile, "directories", Some(&root))?
                        };
                        check_cancelled()?;
                        if operation == "connect" {
                            ProfileStore::new(store).save(profile)?;
                        }
                        Ok(result)
                    })();
                    if let Some(attempt) = hub.state.lock().unwrap().attempts.get_mut(&attempt_id) {
                        if attempt.state == "running" {
                            match result {
                                Ok(result) => {
                                    attempt.state = "succeeded".into();
                                    attempt.result = Some(result);
                                }
                                Err(error) => {
                                    attempt.state = "failed".into();
                                    attempt.error = Some(error);
                                }
                            }
                        }
                        attempt.challenge = None;
                        attempt.answer = None;
                    }
                    CURRENT.with(|current| *current.borrow_mut() = None);
                    hub.workers.fetch_sub(1, Ordering::AcqRel);
                });
                Ok(json!({"id":id}))
            }
            "status" | "cancel" | "reply" => {
                let id = args["id"]
                    .as_str()
                    .ok_or("connection attempt id is required")?;
                let mut state = self.state.lock().unwrap();
                let attempt = state
                    .attempts
                    .get_mut(id)
                    .ok_or("connection attempt expired")?;
                attempt.touched = Instant::now();
                if action == "cancel" && attempt.state == "running" {
                    attempt.state = "cancelled".into();
                    attempt.challenge = None;
                    attempt.answer = None;
                }
                if action == "reply" {
                    if attempt.state != "running"
                        || attempt.challenge.as_ref().and_then(|v| v["id"].as_str())
                            != args["challengeId"].as_str()
                        || attempt.challenge.is_none()
                    {
                        return Err("authentication request expired".into());
                    }
                    let answer = args["answer"]
                        .as_str()
                        .ok_or("authentication answer missing")?;
                    if answer.len() > 16_384 || answer.contains(['\0', '\n', '\r']) {
                        return Err("invalid authentication answer".into());
                    }
                    attempt.answer = Some(answer.to_string());
                }
                Ok(public_attempt(id, attempt))
            }
            _ => Err("unknown SSH interaction request".into()),
        }
    }

    fn serve(&self, mut stream: TcpStream) -> Result<(), String> {
        stream
            .set_read_timeout(Some(Duration::from_secs(3)))
            .map_err(err)?;
        stream
            .set_write_timeout(Some(Duration::from_secs(3)))
            .map_err(err)?;
        let mut line = String::new();
        BufReader::new(&stream)
            .take(16_384)
            .read_line(&mut line)
            .map_err(err)?;
        let request: Value = serde_json::from_str(&line).map_err(err)?;
        if request["token"].as_str() != Some(&self.token) {
            return Err("unauthorized askpass client".into());
        }
        if request["action"] == "state" {
            let state = self.state.lock().unwrap();
            let attempt = state
                .attempts
                .get(request["attempt"].as_str().unwrap_or(""));
            let value = json!({"state":attempt.map(|a| a.state.as_str()).unwrap_or("expired"),"waiting":attempt.is_some_and(|a| a.challenge.is_some())});
            return writeln!(stream, "{value}").map_err(err);
        }
        if request["action"] == "progress" {
            let phase = request["phase"].as_str().unwrap_or("");
            if !["connecting", "environment", "cache", "download", "upload", "runtime", "reusing", "starting", "tunnel", "services"].contains(&phase) {
                return Err("invalid SSH progress phase".into());
            }
            let mut state = self.state.lock().unwrap();
            if let Some(attempt) = state.attempts.get_mut(request["attempt"].as_str().unwrap_or("")) {
                if attempt.state == "running" { attempt.phase = phase.to_string(); }
            }
            return writeln!(stream, "{{\"ok\":true}}").map_err(err);
        }
        let answer = self.authenticate(&request);
        let value = match answer {
            Ok(answer) => json!({"answer":answer}),
            Err(error) => json!({"error":error}),
        };
        writeln!(stream, "{value}").map_err(err)
    }

    fn authenticate(&self, request: &Value) -> Result<String, String> {
        let key = request["profile"]
            .as_str()
            .ok_or("missing SSH identity")?
            .to_string();
        let prompt = request["prompt"]
            .as_str()
            .ok_or("missing SSH prompt")?
            .to_string();
        if prompt.len() > 4096 {
            return Err("SSH prompt exceeded its limit".into());
        }
        let invocation = request["invocation"]
            .as_str()
            .ok_or("missing SSH invocation")?
            .to_string();
        let prompt_lower = prompt.to_ascii_lowercase();
        let confirm = request["hint"] == "confirm"
            || prompt_lower.contains("(yes/no")
            || prompt_lower.contains("are you sure you want to continue connecting");
        let cacheable = !confirm && cacheable_prompt(&prompt_lower);
        let cache_key = (key.clone(), prompt.clone());
        let mut state = self.state.lock().unwrap();
        if let Some(id) = request["attempt"].as_str().filter(|id| !id.is_empty()) {
            if !state
                .attempts
                .get(id)
                .is_some_and(|a| a.state == "running" && a.profile_key == key)
            {
                return Err("connection cancelled or changed".into());
            }
        }
        let first = state.used.insert((invocation, prompt.clone()));
        if state.used.len() > 2048 {
            state.used.clear();
        }
        if cacheable && first {
            if let Some(answer) = state.secrets.get(&cache_key) {
                return Ok(answer.clone());
            }
        } else if !first {
            state.secrets.remove(&cache_key);
        }
        let attempt_id = request["attempt"].as_str().unwrap_or("").to_string();
        let attempt = state
            .attempts
            .get_mut(&attempt_id)
            .filter(|a| a.profile_key == key && a.state == "running")
            .ok_or("SSH authentication is required; open the SSH connection in Harnex and retry")?;
        let challenge_id = Uuid::new_v4().to_string();
        attempt.challenge = Some(
            json!({"id":challenge_id,"kind":if confirm {"hostKey"} else {"secret"},"prompt":prompt}),
        );
        attempt.answer = None;
        let external = attempt.external;
        drop(state);
        let started = Instant::now();
        let deadline = started + Duration::from_secs(300);
        loop {
            let mut state = self.state.lock().unwrap();
            let observed = state.terminal_observer
                || state
                    .observer
                    .is_some_and(|time| time.elapsed() < Duration::from_secs(3));
            let attempt = state
                .attempts
                .get_mut(&attempt_id)
                .filter(|a| a.state == "running")
                .ok_or("connection cancelled")?;
            if external && !observed && started.elapsed() > Duration::from_secs(1) {
                attempt.challenge = None;
                return Err(
                    "SSH authentication requires an open connection window or interactive terminal"
                        .into(),
                );
            }
            if let Some(answer) = attempt.answer.take() {
                attempt.challenge = None;
                if confirm && answer != "yes" {
                    return Err("host key was not accepted".into());
                }
                if cacheable {
                    if state.secrets.len() >= 256 {
                        state.secrets.clear();
                    }
                    state.secrets.insert(cache_key, answer.clone());
                }
                return Ok(answer);
            }
            if Instant::now() >= deadline {
                attempt.challenge = None;
                return Err("SSH authentication timed out".into());
            }
            drop(state);
            thread::sleep(Duration::from_millis(50));
        }
    }
}

fn public_attempt(id: &str, attempt: &Attempt) -> Value {
    json!({"id":id,"host":attempt.profile.host,"state":attempt.state,"phase":attempt.phase,
        "challenge":attempt.challenge,"result":attempt.result,"error":attempt.error,"errorCode":attempt.error.as_deref().map(error_code)})
}

fn error_code(message: &str) -> &'static str {
    let text = message.to_ascii_lowercase();
    if text.contains("cancelled") {
        "cancelled"
    } else if text.contains("host identification has changed")
        || text.contains("host key verification failed")
    {
        "hostKey"
    } else if text.contains("unprotected private key") || text.contains("bad permissions") {
        "keyPermissions"
    } else if text.contains("permission denied") || text.contains("authentication") {
        "authentication"
    } else if text.contains("timed out")
        || text.contains("refused")
        || text.contains("no route")
        || text.contains("resolve hostname")
        || text.contains("unreachable")
    {
        "network"
    } else if text.contains("not a directory")
        || text.contains("can't cd")
        || text.contains("cannot cd")
    {
        "directory"
    } else if text.contains("java")
        || text.contains("linux")
        || text.contains("setsid")
        || text.contains("sha256sum")
        || text.contains("runtime")
    {
        "environment"
    } else {
        "connection"
    }
}

fn prune_attempts(state: &mut State) {
    state
        .attempts
        .retain(|_, a| a.touched.elapsed() < Duration::from_secs(600));
    while state.attempts.len() >= 64 {
        let oldest = state
            .attempts
            .iter()
            .filter(|(_, a)| a.state != "running")
            .min_by_key(|(_, a)| a.touched)
            .map(|(id, _)| id.clone());
        if let Some(id) = oldest {
            state.attempts.remove(&id);
        } else {
            break;
        }
    }
}

pub fn with_lifecycle<T>(
    profile: &Profile,
    action: impl FnOnce() -> Result<T, String>,
) -> Result<T, String> {
    if CURRENT.with(|current| current.borrow().is_some()) {
        return action();
    }
    let Some(hub) = HUB.get() else {
        return action();
    };
    if hub.closing.load(Ordering::Acquire) {
        return action();
    }
    let started = hub.request("begin", json!({"profile":profile}), PathBuf::new(), None)?;
    let id = started["id"]
        .as_str()
        .ok_or("connection attempt id missing")?
        .to_string();
    CURRENT.with(|current| *current.borrow_mut() = Some((Arc::downgrade(hub), id.clone())));
    let result = action();
    CURRENT.with(|current| *current.borrow_mut() = None);
    let _ = hub.request("finish", json!({"id":id}), PathBuf::new(), None);
    result
}

pub fn shutdown() {
    if let Some(hub) = HUB.get() {
        hub.shutdown();
    }
}

fn cacheable_prompt(prompt: &str) -> bool {
    let prompt = prompt.trim();
    prompt == "password:"
        || prompt.ends_with("'s password:")
        || prompt.starts_with("enter passphrase for key ")
}

pub fn profile_key(profile: &Profile) -> String {
    hex_hash(
        format!(
            "{}\n{:?}\n{:?}\n{}",
            profile.host,
            profile.port,
            profile
                .identity_file
                .as_ref()
                .map(|p| ssh::expand_identity(p)),
            ssh::configuration_fingerprint()
        )
        .as_bytes(),
    )
}

pub fn current_attempt() -> String {
    CURRENT
        .with(|current| current.borrow().as_ref().map(|(_, id)| id.clone()))
        .or_else(|| std::env::var("HARNEX_SSH_ATTEMPT").ok())
        .unwrap_or_default()
}

pub fn progress(phase: &str) {
    let local = CURRENT.with(|current| {
        if let Some((hub, id)) = current.borrow().as_ref() {
            if let Some(hub) = hub.upgrade() {
                if let Some(attempt) = hub.state.lock().unwrap().attempts.get_mut(id) {
                    attempt.phase = phase.to_string();
                    return true;
                }
            }
        }
        false
    });
    if !local && HUB.get().is_none() {
        if let Ok(attempt) = std::env::var("HARNEX_SSH_ATTEMPT") {
            if !attempt.is_empty() { let _ = broker_request(json!({"action":"progress","attempt":attempt,"phase":phase})); }
        }
    }
}
pub fn check_cancelled() -> Result<(), String> {
    CURRENT.with(|current| {
        if let Some((hub, id)) = current.borrow().as_ref() {
            let hub = hub.upgrade().ok_or("connection cancelled")?;
            let state = hub.state.lock().unwrap();
            if !state.attempts.get(id).is_some_and(|a| a.state == "running") { return Err("connection cancelled".into()); }
        }
        if external_status().is_some_and(|state| state["state"] == "cancelled") { return Err("SSH connection cancelled; any existing remote Runtime is retained for reconnection".into()); }
        Ok(())
    })
}

pub fn awaiting_authentication() -> bool {
    CURRENT.with(|current| {
        current
            .borrow()
            .as_ref()
            .and_then(|(hub, id)| {
                hub.upgrade().map(|hub| {
                    hub.state
                        .lock()
                        .unwrap()
                        .attempts
                        .get(id)
                        .is_some_and(|a| a.challenge.is_some())
                })
            })
            .unwrap_or_else(|| external_status().is_some_and(|state| state["waiting"] == true))
    })
}

pub fn awaiting_attempt(id: &str) -> bool {
    if let Some(hub) = HUB.get() {
        return hub
            .state
            .lock()
            .unwrap()
            .attempts
            .get(id)
            .is_some_and(|a| a.challenge.is_some());
    }
    external_status().is_some_and(|state| state["waiting"] == true)
}

fn external_status() -> Option<Value> {
    if HUB.get().is_some() {
        return None;
    }
    let attempt = std::env::var("HARNEX_SSH_ATTEMPT")
        .ok()
        .filter(|id| !id.is_empty())?;
    EXTERNAL_STATUS.with(|cache| {
        if let Some((time, value)) = cache.borrow().as_ref() { if time.elapsed() < Duration::from_millis(200) { return Some(value.clone()); } }
        let response = broker_request(json!({"action":"state","attempt":attempt})).unwrap_or(Value::Null);
        *cache.borrow_mut() = Some((Instant::now(), response.clone()));
        Some(response)
    })
}
fn broker_request(mut request: Value) -> Option<Value> {
    let address: std::net::SocketAddr = std::env::var("HARNEX_SSH_BROKER").ok()?.parse().ok()?;
    if !address.ip().is_loopback() { return None; }
    let mut stream = TcpStream::connect_timeout(&address, Duration::from_millis(300)).ok()?;
    stream.set_read_timeout(Some(Duration::from_millis(300))).ok()?;
    stream.set_write_timeout(Some(Duration::from_millis(300))).ok()?;
    request["token"] = json!(std::env::var("HARNEX_SSH_BROKER_TOKEN").ok()?);
    writeln!(stream, "{request}").ok()?;
    let mut line = String::new(); BufReader::new(stream).take(4096).read_line(&mut line).ok()?;
    serde_json::from_str(&line).ok()
}

pub fn configure_ssh(command: &mut Command, profile: &Profile) -> Result<(), String> {
    check_cancelled()?;
    let environment = HUB.get().map(|hub| hub.environment()).or_else(|| {
        let endpoint = std::env::var("HARNEX_SSH_BROKER").ok()?;
        let token = std::env::var("HARNEX_SSH_BROKER_TOKEN").ok()?;
        Some(json!({"HARNEX_SSH_BROKER":endpoint,"HARNEX_SSH_BROKER_TOKEN":token}))
    });
    if let Some(environment) = environment {
        for (key, value) in environment.as_object().unwrap() {
            command.env(key, value.as_str().unwrap());
        }
        let attempt = current_attempt();
        let executable = std::env::current_exe().map_err(err)?;
        #[cfg(windows)]
        let askpass = windows_askpass(command, &executable)?;
        #[cfg(not(windows))]
        let askpass = executable.into_os_string();
        command
            .args([
                "-o",
                "BatchMode=no",
                "-o",
                "StrictHostKeyChecking=ask",
                "-o",
                "NumberOfPasswordPrompts=3",
            ])
            .env("SSH_ASKPASS", askpass)
            .env("SSH_ASKPASS_REQUIRE", "force")
            .env("DISPLAY", "harnex:0")
            .env("HARNEX_SSH_ASKPASS", "1")
            .env("HARNEX_SSH_PROFILE", profile_key(profile))
            .env("HARNEX_SSH_ATTEMPT", attempt)
            .env("HARNEX_SSH_INVOCATION", Uuid::new_v4().to_string());
    } else {
        command.args(["-o", "BatchMode=yes", "-o", "StrictHostKeyChecking=yes"]);
    }
    Ok(())
}

#[cfg(windows)]
fn windows_askpass(command: &mut Command, executable: &Path) -> Result<String, String> {
    use std::os::windows::ffi::OsStrExt;
    use windows_sys::Win32::Storage::FileSystem::GetShortPathNameW;
    if executable.to_string_lossy().is_ascii() {
        return Ok(format!("\"{}\"", executable.display()));
    }
    // Windows OpenSSH 9.x reads SSH_ASKPASS through the ANSI environment before decoding it as UTF-8.
    // Prefer an ASCII short path without changing SSH's working directory.
    let long: Vec<u16> = executable
        .as_os_str()
        .encode_wide()
        .chain(Some(0))
        .collect();
    let size = unsafe { GetShortPathNameW(long.as_ptr(), std::ptr::null_mut(), 0) };
    if size > 0 && size < 32_768 {
        let mut short = vec![0_u16; size as usize];
        let length = unsafe { GetShortPathNameW(long.as_ptr(), short.as_mut_ptr(), size) };
        if length > 0 && length < size {
            let short = String::from_utf16_lossy(&short[..length as usize]);
            if short.is_ascii() {
                return Ok(format!("\"{short}\""));
            }
        }
    }
    // Volumes with 8.3 names disabled still support a Unicode working directory through CreateProcessW.
    // The explicit relative filename cannot resolve to a different executable through PATH.
    let filename = executable
        .file_name()
        .and_then(|name| name.to_str())
        .filter(|name| name.is_ascii())
        .ok_or("Windows SSH authentication requires the packaged executable name")?;
    command.current_dir(
        executable
            .parent()
            .ok_or("SSH helper directory is unavailable")?,
    );
    Ok(format!("\".\\{filename}\""))
}

/// Call before normal argument parsing in each native executable, including the desktop host.
pub fn handle_askpass() -> bool {
    if std::env::var("HARNEX_SSH_ASKPASS").as_deref() != Ok("1") {
        return false;
    }
    let result = (|| -> Result<String, String> {
        let address: std::net::SocketAddr = std::env::var("HARNEX_SSH_BROKER")
            .map_err(err)?
            .parse()
            .map_err(err)?;
        if !address.ip().is_loopback() {
            return Err("askpass broker must be loopback".into());
        }
        let mut stream =
            TcpStream::connect_timeout(&address, Duration::from_secs(3)).map_err(err)?;
        stream
            .set_read_timeout(Some(Duration::from_secs(310)))
            .map_err(err)?;
        let request = json!({"token":std::env::var("HARNEX_SSH_BROKER_TOKEN").map_err(err)?,
            "profile":std::env::var("HARNEX_SSH_PROFILE").map_err(err)?, "attempt":std::env::var("HARNEX_SSH_ATTEMPT").unwrap_or_default(),
            "invocation":std::env::var("HARNEX_SSH_INVOCATION").map_err(err)?, "hint":std::env::var("SSH_ASKPASS_PROMPT").unwrap_or_default(),
            "prompt":std::env::args().nth(1).unwrap_or_default()});
        writeln!(stream, "{request}").map_err(err)?;
        let mut response = String::new();
        BufReader::new(stream)
            .take(32_768)
            .read_line(&mut response)
            .map_err(err)?;
        let response: Value = serde_json::from_str(&response).map_err(err)?;
        response["answer"]
            .as_str()
            .map(str::to_string)
            .ok_or_else(|| "SSH authentication cancelled or unavailable".into())
    })();
    match result {
        Ok(answer) => {
            println!("{answer}");
            std::process::exit(0);
        }
        Err(_) => std::process::exit(1),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn authentication_is_bound_to_the_transport_not_its_display_name() {
        let mut p: Profile =
            serde_json::from_value(json!({"id":"dev","name":"Dev","host":"devbox"})).unwrap();
        let key = profile_key(&p);
        p.name = "Renamed".into();
        assert_eq!(key, profile_key(&p));
        p.port = Some(2222);
        assert_ne!(key, profile_key(&p));
    }
    #[test]
    fn headless_requests_cannot_create_unanswerable_challenges() {
        let hub = Hub::new().unwrap();
        assert!(hub
            .authenticate(&json!({"profile":"unknown","prompt":"Password:","invocation":"a"}))
            .unwrap_err()
            .contains("authentication is required"));
    }
    #[test]
    fn verification_codes_are_not_reused_as_passwords() {
        for prompt in ["one-time password:", "verification code:", "otp:"] {
            assert!(!cacheable_prompt(prompt));
        }
        assert!(cacheable_prompt("dev@server's password: "));
    }
    fn attempt(hub: &Hub, id: &str, key: &str) {
        let profile: Profile =
            serde_json::from_value(json!({"id":"fixture","name":"Fixture","host":"fixture"}))
                .unwrap();
        hub.state.lock().unwrap().attempts.insert(
            id.into(),
            Attempt {
                profile_key: key.into(),
                profile,
                operation: "connect".into(),
                root: "~".into(),
                external: false,
                state: "running".into(),
                phase: "connecting".into(),
                challenge: None,
                answer: None,
                result: None,
                error: None,
                touched: Instant::now(),
            },
        );
    }
    fn wait_challenge(hub: &Arc<Hub>, id: &str) -> Value {
        let deadline = Instant::now() + Duration::from_secs(3);
        loop {
            let status = hub
                .request("status", json!({"id":id}), PathBuf::new(), None)
                .unwrap();
            if status["challenge"].is_object() {
                return status;
            }
            assert!(
                Instant::now() < deadline,
                "authentication challenge did not arrive"
            );
            thread::sleep(Duration::from_millis(10));
        }
    }
    #[test]
    fn secrets_are_scoped_to_the_transport_and_never_exposed_in_status() {
        let hub = Hub::new().unwrap();
        attempt(&hub, "one", "host-one");
        let worker_hub = hub.clone();
        let worker = thread::spawn(move || {
            worker_hub.authenticate(&json!({"profile":"host-one","prompt":"Password:","invocation":"ssh-one","attempt":"one"}))
        });
        let status = wait_challenge(&hub, "one");
        let reply = hub
            .request(
                "reply",
                json!({"id":"one","challengeId":status["challenge"]["id"],"answer":"test-secret"}),
                PathBuf::new(),
                None,
            )
            .unwrap();
        assert!(!reply.to_string().contains("test-secret"));
        assert_eq!(worker.join().unwrap().unwrap(), "test-secret");
        assert_eq!(
            hub.authenticate(
                &json!({"profile":"host-one","prompt":"Password:","invocation":"ssh-two"})
            )
            .unwrap(),
            "test-secret"
        );
        assert!(hub
            .authenticate(
                &json!({"profile":"host-two","prompt":"Password:","invocation":"ssh-three"})
            )
            .is_err());
        // A second prompt from the same SSH invocation means the cached credential was rejected.
        assert!(hub
            .authenticate(
                &json!({"profile":"host-one","prompt":"Password:","invocation":"ssh-two"})
            )
            .is_err());
        assert!(hub.state.lock().unwrap().secrets.is_empty());
    }
    #[test]
    fn cancelling_authentication_invalidates_late_answers() {
        let hub = Hub::new().unwrap();
        attempt(&hub, "cancel-me", "host");
        let worker_hub = hub.clone();
        let worker = thread::spawn(move || {
            worker_hub.authenticate(&json!({"profile":"host","prompt":"Trust this host?","hint":"confirm","invocation":"ssh-one","attempt":"cancel-me"}))
        });
        let status = wait_challenge(&hub, "cancel-me");
        hub.request("cancel", json!({"id":"cancel-me"}), PathBuf::new(), None)
            .unwrap();
        assert!(hub
            .request(
                "reply",
                json!({"id":"cancel-me","challengeId":status["challenge"]["id"],"answer":"yes"}),
                PathBuf::new(),
                None
            )
            .is_err());
        assert!(worker.join().unwrap().is_err());
        assert!(hub.state.lock().unwrap().secrets.is_empty());
    }
    #[test]
    fn bridge_progress_is_authenticated_and_cannot_revive_a_cancelled_attempt() {
        let hub = Hub::new().unwrap();
        attempt(&hub, "progress", "host");
        let send = |token: &str, phase: &str| {
            let mut stream = TcpStream::connect(&hub.endpoint).unwrap();
            stream.set_read_timeout(Some(Duration::from_secs(2))).unwrap();
            writeln!(stream, "{}", json!({"token":token,"action":"progress","attempt":"progress","phase":phase})).unwrap();
            let mut response = String::new();
            BufReader::new(stream).read_line(&mut response).unwrap(); response
        };
        assert!(send("wrong", "upload").is_empty());
        assert_eq!(hub.state.lock().unwrap().attempts["progress"].phase, "connecting");
        assert!(send(&hub.token, "starting").contains("true"));
        assert_eq!(hub.state.lock().unwrap().attempts["progress"].phase, "starting");
        hub.request("cancel", json!({"id":"progress"}), PathBuf::new(), None).unwrap();
        send(&hub.token, "upload");
        let state = hub.state.lock().unwrap();
        assert_eq!(state.attempts["progress"].state, "cancelled");
        assert_eq!(state.attempts["progress"].phase, "starting");
    }
    #[test]
    fn prepared_projects_cannot_be_registered_under_another_host_or_path() {
        let base = std::env::temp_dir().join(format!("harnex-prepared-{}", Uuid::new_v4()));
        let store = ProfileStore::new(base.clone());
        let mut profile: Profile =
            serde_json::from_value(json!({"id":"fixture","name":"Fixture","host":"fixture"}))
                .unwrap();
        store.save(profile.clone()).unwrap();
        let hub = Hub::new().unwrap();
        attempt(&hub, "ready", &profile_key(&profile));
        {
            let mut state = hub.state.lock().unwrap();
            let prepared = state.attempts.get_mut("ready").unwrap();
            prepared.operation = "prepare".into();
            prepared.state = "succeeded".into();
            prepared.root = "/srv/project".into();
            prepared.result = Some(json!({"root":"/srv/project","workspaceId":"remote-fixture"}));
        }
        let request = json!({"id":"ready","profileId":"fixture","root":"/srv/project"});
        assert_eq!(
            hub.request("prepared", request.clone(), base.clone(), None)
                .unwrap()["workspaceId"],
            "remote-fixture"
        );
        assert!(hub
            .request(
                "prepared",
                json!({"id":"ready","profileId":"fixture","root":"/other"}),
                base.clone(),
                None
            )
            .is_err());
        profile.name = "Friendly name".into();
        store.save(profile.clone()).unwrap();
        assert!(hub
            .request("prepared", request.clone(), base.clone(), None)
            .is_ok());
        profile.host = "another-host".into();
        store.save(profile).unwrap();
        assert!(hub
            .request("prepared", request, base.clone(), None)
            .is_err());
        let checked = base.canonicalize().unwrap();
        assert!(checked.starts_with(std::env::temp_dir().canonicalize().unwrap()));
        fs::remove_dir_all(checked).unwrap();
    }
    #[test]
    fn browsing_many_directories_retires_old_results_instead_of_blocking_connections() {
        let hub = Hub::new().unwrap();
        for index in 0..64 {
            attempt(&hub, &index.to_string(), "host");
        }
        let mut state = hub.state.lock().unwrap();
        state.attempts.get_mut("0").unwrap().state = "succeeded".into();
        prune_attempts(&mut state);
        assert_eq!(state.attempts.len(), 63);
        assert!(state.attempts.values().all(|a| a.state == "running"));
    }
    #[test]
    fn finishing_a_lifecycle_does_not_erase_its_cancellation() {
        let hub = Hub::new().unwrap();
        let started = hub
            .request(
                "begin",
                json!({"profile":{"id":"fixture","name":"Fixture","host":"fixture"}}),
                PathBuf::new(),
                None,
            )
            .unwrap();
        let request = json!({"id":started["id"]});
        hub.request("cancel", request.clone(), PathBuf::new(), None)
            .unwrap();
        hub.request("finish", request.clone(), PathBuf::new(), None)
            .unwrap();
        assert_eq!(
            hub.request("status", request, PathBuf::new(), None)
                .unwrap()["state"],
            "cancelled"
        );
    }
}
