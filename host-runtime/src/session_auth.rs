//! Renewable local boot-token credentials shared by desktop, CLI and TUI clients.
use std::fs;
use std::path::PathBuf;
use std::sync::{Arc, Mutex};
use std::time::{Duration, SystemTime};

use reqwest::blocking::Client;
use serde_json::{json, Value};

// Renew before the server's 2h idle / 24h absolute limits, including after sleep.
const REFRESH_AFTER: Duration = Duration::from_secs(60 * 60);

#[derive(Clone)]
pub struct SessionCredential {
    state: Arc<Mutex<TokenState>>,
    target: Option<BootTarget>,
}

struct TokenState {
    token: String,
    issued_at: SystemTime,
}

#[derive(Clone)]
struct BootTarget {
    endpoint: String,
    scope_id: String,
    instance_id: String,
    boot_token_file: PathBuf,
}

impl SessionCredential {
    /// Static remote credentials are owned by the connection, not a local boot file.
    pub fn fixed(token: String) -> Self {
        Self {
            state: Arc::new(Mutex::new(TokenState { token, issued_at: SystemTime::now() })),
            target: None,
        }
    }

    /// The caller must first verify the Runtime and its boot-token path during attach.
    pub fn local(token: String, endpoint: String, scope_id: String, instance_id: String,
        boot_token_file: PathBuf) -> Self {
        let mut credentials = Self::fixed(token);
        credentials.target = Some(BootTarget { endpoint, scope_id, instance_id, boot_token_file });
        credentials
    }

    /// Pass the credential rejected with HTTP 401 to force one renewal. The mutex
    /// coalesces concurrent failures; late responses reuse an already-renewed token.
    /// May perform blocking IO: async clients must call this on a blocking worker.
    pub fn token(&self, rejected: Option<&str>) -> Result<String, String> {
        let mut state = self.state.lock().map_err(|_| "Session credential lock poisoned")?;
        let Some(target) = &self.target else { return Ok(state.token.clone()); };
        let expired = state.issued_at.elapsed().map_or(true, |age| age >= REFRESH_AFTER);
        let refresh = rejected.map_or(expired, |token| token == state.token);
        if !refresh { return Ok(state.token.clone()); }

        let candidate = fs::read_to_string(&target.boot_token_file).map_err(|e| e.to_string())?;
        if candidate.trim().is_empty() { return Err("Runtime boot token is empty".into()); }
        let client = Client::builder().no_proxy().timeout(Duration::from_secs(5))
            .redirect(reqwest::redirect::Policy::none()).build().map_err(|e| e.to_string())?;
        let response = client.post(format!("{}/api/v1/auth/boot-token", target.endpoint))
            .header("X-Harnex-Scope-Id", &target.scope_id)
            .header("X-Harnex-Instance-Id", &target.instance_id)
            .json(&json!({"token":candidate.trim()})).send().map_err(|e| e.to_string())?;
        if response.headers().get("X-Harnex-Scope-Id").and_then(|v| v.to_str().ok()) != Some(&target.scope_id)
            || response.headers().get("X-Harnex-Instance-Id").and_then(|v| v.to_str().ok()) != Some(&target.instance_id) {
            return Err("Runtime identity changed during authentication renewal; reconnect before retrying".into());
        }
        if !response.status().is_success() {
            return Err(format!("Runtime authentication renewal failed: HTTP {}", response.status()));
        }
        let body: Value = response.json().map_err(|e| e.to_string())?;
        let token = body["token"].as_str().filter(|token| !token.is_empty())
            .ok_or("Runtime authentication renewal returned no session token")?;
        // Commit only a complete, identity-checked handshake; failures remain retryable.
        state.token = token.to_string();
        state.issued_at = SystemTime::now();
        Ok(state.token.clone())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::{BufRead, BufReader, Read, Write};
    use std::net::{TcpListener, TcpStream};
    use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering};
    use std::thread;

    struct Fixture {
        credentials: SessionCredential,
        root: PathBuf,
        calls: Arc<AtomicUsize>,
        status: Arc<AtomicUsize>,
        wrong_identity: Arc<AtomicBool>,
        stop: Arc<AtomicBool>,
        address: std::net::SocketAddr,
        worker: Option<thread::JoinHandle<()>>,
    }

    impl Fixture {
        fn new() -> Self {
            let root = std::env::temp_dir().join(format!("harnex-session-{}", uuid::Uuid::new_v4()));
            fs::create_dir_all(&root).unwrap();
            let file = root.join("boot-token");
            fs::write(&file, "fixture-boot").unwrap();
            let listener = TcpListener::bind("127.0.0.1:0").unwrap();
            let address = listener.local_addr().unwrap();
            let credentials = SessionCredential::local("expired".into(), format!("http://{address}"),
                "scope".into(), "instance".into(), file);
            let calls = Arc::new(AtomicUsize::new(0));
            let status = Arc::new(AtomicUsize::new(200));
            let wrong_identity = Arc::new(AtomicBool::new(false));
            let stop = Arc::new(AtomicBool::new(false));
            let (count, code, wrong, done) = (calls.clone(), status.clone(), wrong_identity.clone(), stop.clone());
            let worker = thread::spawn(move || {
                for stream in listener.incoming() {
                    let mut stream = stream.unwrap();
                    if done.load(Ordering::SeqCst) { break; }
                    stream.set_read_timeout(Some(Duration::from_secs(5))).unwrap();
                    let mut reader = BufReader::new(&stream);
                    let mut header = String::new();
                    loop {
                        let mut line = String::new();
                        reader.read_line(&mut line).unwrap();
                        if line == "\r\n" { break; }
                        header.push_str(&line);
                    }
                    assert!(header.starts_with("POST /api/v1/auth/boot-token "));
                    let lower = header.to_lowercase();
                    assert!(lower.contains("x-harnex-scope-id: scope"));
                    assert!(lower.contains("x-harnex-instance-id: instance"));
                    let length: usize = lower.lines().find_map(|line| line.strip_prefix("content-length: ")).unwrap().trim().parse().unwrap();
                    let mut body = vec![0; length];
                    reader.read_exact(&mut body).unwrap();
                    assert_eq!(serde_json::from_slice::<Value>(&body).unwrap(), json!({"token":"fixture-boot"}));
                    let n = count.fetch_add(1, Ordering::SeqCst) + 1;
                    let body = json!({"token":format!("renewed-{n}")}).to_string();
                    let instance = if wrong.load(Ordering::SeqCst) { "replacement" } else { "instance" };
                    write!(stream, "HTTP/1.1 {} Result\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\nX-Harnex-Scope-Id: scope\r\nX-Harnex-Instance-Id: {instance}\r\n\r\n{body}", code.load(Ordering::SeqCst), body.len()).unwrap();
                }
            });
            Self { credentials, root, calls, status, wrong_identity, stop, address, worker: Some(worker) }
        }
    }

    impl Drop for Fixture {
        fn drop(&mut self) {
            self.stop.store(true, Ordering::SeqCst);
            let _ = TcpStream::connect(self.address);
            self.worker.take().unwrap().join().unwrap();
            fs::remove_dir_all(&self.root).unwrap();
        }
    }

    #[test]
    fn idle_and_absolute_expiry_renew_once_across_cloned_clients() {
        let fixture = Fixture::new();
        assert_eq!(fixture.credentials.token(None).unwrap(), "expired");
        for (hours, expected) in [(3, "renewed-1"), (25, "renewed-2")] {
            fixture.credentials.state.lock().unwrap().issued_at = SystemTime::now() - Duration::from_secs(hours * 3600);
            let clients: Vec<_> = (0..8).map(|_| {
                let credentials = fixture.credentials.clone();
                thread::spawn(move || credentials.token(None).unwrap())
            }).collect();
            for client in clients { assert_eq!(client.join().unwrap(), expected); }
        }
        assert_eq!(fixture.calls.load(Ordering::SeqCst), 2);
    }

    #[test]
    fn concurrent_and_late_unauthorized_responses_share_one_renewal() {
        let fixture = Fixture::new();
        let clients: Vec<_> = (0..8).map(|_| {
            let credentials = fixture.credentials.clone();
            thread::spawn(move || credentials.token(Some("expired")).unwrap())
        }).collect();
        for client in clients { assert_eq!(client.join().unwrap(), "renewed-1"); }
        assert_eq!(fixture.credentials.token(Some("expired")).unwrap(), "renewed-1");
        assert_eq!(fixture.calls.load(Ordering::SeqCst), 1);
    }

    #[test]
    fn failed_or_wrong_instance_handshakes_do_not_replace_the_credential() {
        let fixture = Fixture::new();
        fixture.status.store(503, Ordering::SeqCst);
        assert!(fixture.credentials.token(Some("expired")).unwrap_err().contains("503"));
        assert_eq!(fixture.credentials.token(None).unwrap(), "expired");
        fixture.status.store(200, Ordering::SeqCst);
        fixture.wrong_identity.store(true, Ordering::SeqCst);
        assert!(fixture.credentials.token(Some("expired")).unwrap_err().contains("identity changed"));
        assert_eq!(fixture.credentials.token(None).unwrap(), "expired");
        fixture.wrong_identity.store(false, Ordering::SeqCst);
        assert_eq!(fixture.credentials.token(Some("expired")).unwrap(), "renewed-3");
    }

    #[test]
    fn fixed_remote_credentials_are_not_exchanged_with_a_local_boot_file() {
        let credentials = SessionCredential::fixed("remote".into());
        credentials.state.lock().unwrap().issued_at = SystemTime::now() - Duration::from_secs(25 * 3600);
        assert_eq!(credentials.token(None).unwrap(), "remote");
        assert_eq!(credentials.token(Some("remote")).unwrap(), "remote");
    }
}
