//! Narrow local project-switch channel, owned by the host rather than its replaceable JVM.
use super::{err, write_private};
use serde_json::{json, Value};
use std::io::{BufRead, BufReader, Read, Write};
use std::net::TcpListener;
use std::path::PathBuf;
use std::sync::{atomic::{AtomicBool, Ordering}, Arc};
use std::time::Duration;

pub struct WorkspaceControl {
    closing: Arc<AtomicBool>,
    file: PathBuf,
    owner: String,
}

impl WorkspaceControl {
    pub fn start(
        directory: PathBuf,
        owner: String,
        switch: impl Fn(&str, &str) -> Result<Value, String> + Send + 'static,
    ) -> Result<Self, String> {
        let listener = TcpListener::bind(("127.0.0.1", 0)).map_err(err)?;
        listener.set_nonblocking(true).map_err(err)?;
        let port = listener.local_addr().map_err(err)?.port();
        let token = format!("{}{}", uuid::Uuid::new_v4().simple(), uuid::Uuid::new_v4().simple());
        let file = directory.join("workspace-control.json");
        write_private(&file, &json!({"schemaVersion":1, "ownerInstanceId":owner,
            "host":"127.0.0.1", "port":port, "token":token}))?;
        let closing = Arc::new(AtomicBool::new(false));
        let stopped = closing.clone();
        let expected_owner = owner.clone();
        std::thread::spawn(move || {
            while !stopped.load(Ordering::Acquire) {
                let (mut stream, _) = match listener.accept() {
                    Ok(connection) => connection,
                    Err(error) if error.kind() == std::io::ErrorKind::WouldBlock => {
                        std::thread::sleep(Duration::from_millis(100)); continue;
                    }
                    Err(_) => break,
                };
                let _ = stream.set_read_timeout(Some(Duration::from_secs(5)));
                let _ = stream.set_write_timeout(Some(Duration::from_secs(5)));
                let mut line = Vec::new();
                if BufReader::new((&stream).take(65537)).read_until(b'\n', &mut line).is_err()
                    || line.len() > 65536 || line.last() != Some(&b'\n') { continue; }
                let request: Value = match serde_json::from_slice(&line) { Ok(value) => value, Err(_) => continue };
                let result = (|| {
                    let supplied = request["token"].as_str().unwrap_or_default().as_bytes();
                    let same_token = supplied.len() == token.len()
                        && supplied.iter().zip(token.as_bytes()).fold(0u8, |diff, (a, b)| diff | (a ^ b)) == 0;
                    if stopped.load(Ordering::Acquire) || !same_token || request["ownerInstanceId"] != expected_owner {
                        return Err("Workspace host connection is no longer valid".to_string());
                    }
                    let root = request["root"].as_str().filter(|v| !v.is_empty());
                    let instance = request["expectedInstanceId"].as_str().filter(|v| !v.is_empty());
                    if request["action"] != "switch" || root.is_none() || instance.is_none() {
                        return Err("Invalid workspace switch request".to_string());
                    }
                    switch(root.unwrap(), instance.unwrap())
                })();
                let reply = match result {
                    Ok(result) => json!({"id":request["id"], "ok":true, "result":result}),
                    Err(error) => json!({"id":request["id"], "ok":false, "error":error}),
                };
                let _ = writeln!(stream, "{reply}");
            }
        });
        Ok(Self { closing, file, owner })
    }
}

impl Drop for WorkspaceControl {
    fn drop(&mut self) {
        self.closing.store(true, Ordering::Release);
        if let Ok(bytes) = std::fs::read(&self.file) {
            if serde_json::from_slice::<Value>(&bytes).is_ok_and(|value| value["ownerInstanceId"] == self.owner) {
                let _ = std::fs::remove_file(&self.file);
            }
        }
    }
}

/// Admission closes atomically only when no task or conversation mutation remains active.
pub fn stop_idle(port: u16, supervisor_token: &str) -> Result<(), String> {
    let response = reqwest::blocking::Client::builder().no_proxy().timeout(Duration::from_secs(5))
        .build().map_err(err)?.post(format!("http://127.0.0.1:{port}/api/v1/admin/upgrade-stop-idle"))
        .header("X-Harnex-Supervisor-Token", supervisor_token).send().map_err(err)?;
    if response.status().as_u16() == 409 {
        return Err("当前 Runtime 有任务或会话操作尚未结束，请结束后在 VS Code 重试连接。".into());
    }
    if !response.status().is_success() { return Err(format!("Cannot prepare project switch: HTTP {}", response.status())); }
    Ok(())
}
