//! Attach to a published service through SSH without uploading, starting or owning its Runtime.
use super::*;

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ServiceRef {
    pub user_data_dir: String,
    pub domain_id: String,
    pub manifest: String,
    pub scope_id: String,
    pub root: String,
    #[serde(default)]
    pub port: Option<u16>,
}
impl ServiceRef {
    fn validate(&self) -> Result<(), String> {
        ssh::validate_root(&self.user_data_dir)?;
        ssh::validate_root(&self.root)?;
        if self.domain_id.is_empty()
            || self.domain_id.len() > 128
            || !self
                .domain_id
                .chars()
                .all(|value| value.is_ascii_alphanumeric() || "._-".contains(value))
            || self.domain_id == "."
            || self.domain_id == ".."
            || !["service.json", "domain.json"].contains(&self.manifest.as_str())
            || self.scope_id.is_empty()
            || self.scope_id.len() > 160
            || self.port == Some(0)
        {
            return Err("invalid existing-service reference".into());
        }
        Ok(())
    }
    fn directory(&self) -> String {
        format!(
            "{}/runtime-domains/{}",
            self.user_data_dir.trim_end_matches('/'),
            self.domain_id
        )
    }
}

fn shell_directory(value: &str) -> String {
    if value == "~" {
        "\"$HOME\"".into()
    } else if let Some(relative) = value.strip_prefix("~/") {
        format!("\"$HOME\"/{}", quote(relative))
    } else {
        quote(value)
    }
}
fn metadata(profile: &Profile, reference: &ServiceRef) -> Result<Value, String> {
    reference.validate()?;
    let marker = format!("HARNEX_SERVICE_{}", Uuid::new_v4());
    let mut command = profile.ssh()?;
    command.arg(&profile.host).arg(format!(r#"set -eu; cd {dir}; f={file}; test -f "$f"; test ! -L "$f"; test "$(wc -c < "$f")" -le 32768; {{ test "$(id -u)" = 0 || test "$(stat -c %u "$f")" = "$(id -u)"; }}; p=$(stat -c %a "$f"); test $((0$p & 0022)) -eq 0; printf '{marker}\n'; cat "$f""#,
        dir=shell_directory(&reference.directory()), file=quote(&reference.manifest)));
    let response = run(command, Stdio::null(), Duration::from_secs(25))?;
    let raw = response
        .split_once(&format!("{marker}\n"))
        .ok_or("service metadata response was invalid")?
        .1;
    let value: Value =
        serde_json::from_str(raw.trim()).map_err(|_| "service metadata was not valid JSON")?;
    validate_manifest(&value, reference)?;
    Ok(value)
}
fn validate_manifest(value: &Value, reference: &ServiceRef) -> Result<(), String> {
    let schema = if reference.manifest == "service.json" {
        1
    } else {
        3
    };
    if value["schemaVersion"] != schema
        || value["runtimeProfile"] != "PROJECT"
        || value["lifecycle"] != "READY"
        || value["protocolVersion"] != "1.0"
        || value["runtimeDomainId"] != reference.domain_id
        || value["scopeId"] != reference.scope_id
        || value["canonicalRoot"] != reference.root
        || value["instanceId"].as_str().is_none_or(str::is_empty)
        || !value["remote"].is_null()
    {
        return Err(
            "service identity or protocol changed; refresh the service list and select it again"
                .into(),
        );
    }
    endpoint_port(value["endpoint"].as_str().unwrap_or(""))?;
    Ok(())
}
fn endpoint_port(endpoint: &str) -> Result<u16, String> {
    let url = reqwest::Url::parse(endpoint).map_err(err)?;
    if url.scheme() != "http"
        || !matches!(url.host_str(), Some("127.0.0.1"))
        || url.path() != "/"
        || url.query().is_some()
        || url.fragment().is_some()
        || !url.username().is_empty()
        || url.password().is_some()
    {
        return Err("existing service must publish an IPv4 loopback HTTP endpoint".into());
    }
    url.port_or_known_default()
        .filter(|port| *port > 0)
        .ok_or("existing service port is missing".into())
}
fn view(routing: &str, reference: &ServiceRef, value: &Value) -> Result<Value, String> {
    validate_manifest(value, reference)?;
    let id = format!(
        "ssh-{}",
        &hex_hash(
            format!(
                "{}\n{}\n{}\n{}",
                routing, reference.user_data_dir, reference.domain_id, reference.scope_id
            )
            .as_bytes()
        )[..24]
    );
    Ok(
        json!({"catalogId":id,"workspaceId":reference.scope_id,"root":reference.root,
        "name":reference.root.rsplit('/').find(|part| !part.is_empty()).unwrap_or("/"),
        "instanceId":value["instanceId"],"runtimeVersion":value.get("runtimeVersion").and_then(Value::as_str).unwrap_or("unknown"),
        "domainId":reference.domain_id,"port":reference.port.unwrap_or(endpoint_port(value["endpoint"].as_str().unwrap_or(""))?),"service":reference}),
    )
}
pub fn inspect(profile: &Profile, reference: &ServiceRef) -> Result<Value, String> {
    interaction::progress("services");
    view(
        &ssh::routing_key(profile)?,
        reference,
        &metadata(profile, reference)?,
    )
}
pub fn discover(profile: &Profile, user_data_dir: &str) -> Result<Value, String> {
    ssh::validate_root(user_data_dir)?;
    interaction::progress("services");
    let home = format!("HARNEX_SERVICES_HOME_{}", Uuid::new_v4());
    let entry = format!("HARNEX_SERVICE_ENTRY_{}", Uuid::new_v4());
    let mut command = profile.ssh()?;
    command.arg(&profile.host).arg(format!(
        r#"set -eu; cd {dir}; printf '{home}\n'; pwd -P; count=0
for d in runtime-domains/*; do
  test -d "$d" && test ! -L "$d" || continue
  name=${{d##*/}}; case "$name" in *[!a-zA-Z0-9._-]*|.|..) continue;; esac
  f=service.json; test -f "$d/$f" || f=domain.json
  test -f "$d/$f" && test ! -L "$d/$f" || continue
  test "$(wc -c < "$d/$f")" -le 32768 || continue
  {{ test "$(id -u)" = 0 || test "$(stat -c %u "$d/$f")" = "$(id -u)"; }} || continue
  p=$(stat -c %a "$d/$f"); test $((0$p & 0022)) -eq 0 || continue
  printf '\n{entry}\n%s/%s\n' "$name" "$f"; cat "$d/$f"
  count=$((count+1)); test "$count" -lt 32 || break
done"#,
        dir = shell_directory(user_data_dir)
    ));
    let output = run(command, Stdio::null(), Duration::from_secs(30))?;
    let data = output
        .split_once(&format!("{home}\n"))
        .ok_or("service discovery response was invalid")?
        .1;
    let (directory, rest) = data
        .split_once('\n')
        .ok_or("remote user data directory is missing")?;
    ssh::validate_root(directory)?;
    let routing = ssh::routing_key(profile)?;
    let mut services = Vec::new();
    for block in rest.split(&format!("{entry}\n")).skip(1) {
        let Some((name, raw)) = block.split_once('\n') else {
            continue;
        };
        let Some((domain, manifest)) = name.split_once('/') else {
            continue;
        };
        let Ok(value) = serde_json::from_str::<Value>(raw.trim()) else {
            continue;
        };
        let reference = ServiceRef {
            user_data_dir: directory.into(),
            domain_id: domain.into(),
            manifest: manifest.into(),
            scope_id: value["scopeId"].as_str().unwrap_or("").into(),
            root: value["canonicalRoot"].as_str().unwrap_or("").into(),
            port: None,
        };
        if reference.validate().is_ok() {
            if let Ok(item) = view(&routing, &reference, &value) {
                services.push(item);
            }
        }
    }
    Ok(json!({"userDataDir":directory,"services":services}))
}
fn credential_name<'a>(reference: &ServiceRef, value: &'a Value) -> Result<&'a str, String> {
    let raw = value["bootTokenFile"]
        .as_str()
        .ok_or("service credential location is missing")?;
    // These are Linux paths even when the connecting host is Windows.
    let name = if raw.starts_with('/') {
        let (directory, name) = raw.rsplit_once('/').ok_or("invalid credential filename")?;
        if directory != reference.directory() {
            return Err("service credential is outside its published directory".into());
        }
        name
    } else {
        raw
    };
    if name.is_empty()
        || !name
            .chars()
            .all(|ch| ch.is_ascii_alphanumeric() || "._-".contains(ch))
        || [".", ".."].contains(&name)
    {
        return Err("invalid credential filename".into());
    }
    if !matches!(
        value["authType"].as_str(),
        None | Some("boot-token" | "bearer-json")
    ) {
        return Err("unsupported service authentication type".into());
    }
    Ok(name)
}
fn credential(
    profile: &Profile,
    reference: &ServiceRef,
    value: &Value,
) -> Result<(String, bool), String> {
    let name = credential_name(reference, value)?;
    let marker = format!("HARNEX_CREDENTIAL_{}", Uuid::new_v4());
    let mut command = profile.ssh()?;
    command.arg(&profile.host).arg(format!(r#"set -eu; cd {dir}; f={file}; test -f "$f"; test ! -L "$f"; test "$(wc -c < "$f")" -le 16384; {{ test "$(id -u)" = 0 || test "$(stat -c %u "$f")" = "$(id -u)"; }}; p=$(stat -c %a "$f"); test $((0$p & 0077)) -eq 0; printf '{marker}\n'; cat "$f""#,
        dir=shell_directory(&reference.directory()),file=quote(name)));
    let output = run(command, Stdio::null(), Duration::from_secs(25))?;
    let secret = output
        .split_once(&format!("{marker}\n"))
        .ok_or("service credential response was invalid")?
        .1
        .trim();
    if value["authType"] == "bearer-json" {
        let decoded: Value =
            serde_json::from_str(secret).map_err(|_| "invalid service credential format")?;
        return Ok((
            decoded["token"]
                .as_str()
                .filter(|token| !token.is_empty())
                .ok_or("service token is missing")?
                .into(),
            false,
        ));
    }
    if secret.is_empty() || secret.contains(['\n', '\r']) {
        return Err("service boot token is missing or malformed".into());
    }
    Ok((secret.into(), true))
}
pub fn connect(
    profile: &Profile,
    reference: &ServiceRef,
    local_port: u16,
) -> Result<Connection, String> {
    interaction::with_lifecycle(profile, || {
        let routing = ssh::routing_key(profile)?;
        let value = metadata(profile, reference)?;
        let (credential, exchange) = credential(profile, reference, &value)?;
        let mut descriptor = Descriptor {
            schema_version: 1,
            root: reference.root.clone(),
            workspace_id: reference.scope_id.clone(),
            instance_id: value["instanceId"].as_str().unwrap().into(),
            domain_id: reference.domain_id.clone(),
            port: reference
                .port
                .unwrap_or(endpoint_port(value["endpoint"].as_str().unwrap_or(""))?),
            pid: value["runtimePid"].as_u64().unwrap_or(1) as u32,
            process_start: value["publishedAt"].as_str().unwrap_or("published").into(),
            token: String::new(),
            supervisor_token: String::new(),
            protocol_version: "1.0".into(),
            ownership: "attached".into(),
            runtime_version: String::new(),
            runtime_sha256: String::new(),
            target_version: String::new(),
            target_sha256: String::new(),
            update_available: false,
            can_upgrade: false,
        };
        descriptor.validate(&reference.domain_id)?;
        interaction::progress("tunnel");
        let tunnel = Tunnel::start(
            profile.clone(),
            descriptor.clone(),
            local_port,
            routing.clone(),
        );
        let endpoint = format!("http://127.0.0.1:{local_port}");
        let mut deadline = Instant::now() + Duration::from_secs(25);
        while !tunnel.connected() {
            interaction::check_cancelled()?;
            if interaction::awaiting_authentication() {
                deadline = Instant::now() + Duration::from_secs(25);
            }
            if let Some(error) = tunnel.failure() {
                return Err(error);
            }
            if Instant::now() >= deadline {
                return Err("existing service is unavailable; start it on the server and reconnect. No new Runtime was deployed".into());
            }
            thread::sleep(Duration::from_millis(100));
        }
        authenticate(&endpoint, &mut descriptor, credential, exchange)?;
        tunnel.authenticate(descriptor.token.clone());
        Ok(Connection {
            tunnel: Some(tunnel),
            endpoint,
            owner_lease: None,
            control: RemoteControl {
                profile: profile.clone(),
                jar: String::new(),
                descriptor,
                routing_key: Some(routing),
            },
        })
    })
}

fn authenticate(
    endpoint: &str,
    descriptor: &mut Descriptor,
    credential: String,
    exchange: bool,
) -> Result<(), String> {
    let client = Client::builder()
        .no_proxy()
        .timeout(Duration::from_secs(5))
        .build()
        .map_err(err)?;
    let headers = |request: reqwest::blocking::RequestBuilder| {
        request
            .header("X-Harnex-Scope-Id", &descriptor.workspace_id)
            .header("X-Harnex-Instance-Id", &descriptor.instance_id)
    };
    let token = if exchange {
        let response = headers(client.post(format!("{endpoint}/api/v1/auth/boot-token")))
            .json(&json!({"token":credential}))
            .send()
            .map_err(err)?;
        if !response.status().is_success() {
            return Err(
                "service credential expired or instance changed; reconnect to refresh it".into(),
            );
        }
        let body: Value = response.json().map_err(err)?;
        body["token"]
            .as_str()
            .filter(|token| !token.is_empty())
            .ok_or("service authentication returned no session")?
            .to_string()
    } else {
        credential
    };
    let response = headers(client.get(format!("{endpoint}/api/v1/workspace")))
        .bearer_auth(&token)
        .send()
        .map_err(err)?;
    if !response.status().is_success() {
        return Err("existing service rejected this connection".into());
    }
    let actual: Value = response.json().map_err(err)?;
    if actual["workspaceId"] != descriptor.workspace_id
        || actual["instanceId"] != descriptor.instance_id
        || actual["root"] != descriptor.root
    {
        return Err(
            "existing service workspace identity did not match its published record".into(),
        );
    }
    let response = headers(client.get(format!("{endpoint}/api/v1/protocol")))
        .bearer_auth(&token)
        .send()
        .map_err(err)?;
    if !response.status().is_success() {
        return Err("existing service protocol is unavailable".into());
    }
    let protocol: Value = response.json().map_err(err)?;
    if protocol["name"] != "harnex-local-api"
        || protocol["version"] != "1.0"
        || protocol["runtimeProfile"] != "PROJECT"
    {
        return Err("existing service protocol is incompatible".into());
    }
    descriptor.runtime_version = protocol["runtimeVersion"]
        .as_str()
        .unwrap_or("unknown")
        .into();
    descriptor.runtime_sha256 = protocol["runtimeSha256"].as_str().unwrap_or("").into();
    descriptor.token = token;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    fn reference() -> ServiceRef {
        ServiceRef {
            user_data_dir: "/home/user/.harnex".into(),
            domain_id: "server-main".into(),
            manifest: "service.json".into(),
            scope_id: "project-scope".into(),
            root: "/srv/project".into(),
            port: None,
        }
    }
    fn manifest() -> Value {
        json!({"schemaVersion":1,"runtimeProfile":"PROJECT","runtimeDomainId":"server-main","scopeId":"project-scope",
            "canonicalRoot":"/srv/project","instanceId":"server-instance","endpoint":"http://127.0.0.1:18080",
            "protocolVersion":"1.0","lifecycle":"READY","authType":"boot-token","bootTokenFile":"boot-token"})
    }
    fn descriptor() -> Descriptor {
        serde_json::from_value(json!({"schemaVersion":1,"root":"/srv/project","workspaceId":"project-scope","instanceId":"server-instance",
            "domainId":"server-main","port":18080,"pid":1,"processStart":"published","token":"","supervisorToken":"",
            "protocolVersion":"1.0","ownership":"attached"})).unwrap()
    }
    #[test]
    fn catalog_separates_hosts_while_preserving_server_scope_and_rejects_stale_metadata() {
        assert_eq!(endpoint_port("http://127.0.0.1:80").unwrap(),80);
        let reference = reference();
        let mut value = manifest();
        let first = view("host-one", &reference, &value).unwrap();
        let second = view("host-two", &reference, &value).unwrap();
        assert_ne!(first["catalogId"], second["catalogId"]);
        assert_eq!(first["workspaceId"], second["workspaceId"]);
        assert!(first.get("bootTokenFile").is_none());
        value["scopeId"] = json!("other");
        assert!(validate_manifest(&value, &reference).is_err());
        value = manifest();
        value["remote"] = json!({"host":"proxy"});
        assert!(validate_manifest(&value, &reference).is_err());
        value = manifest();
        value["endpoint"] = json!("http://example.com:8080");
        assert!(validate_manifest(&value, &reference).is_err());
    }
    #[test]
    fn remote_credential_paths_use_linux_rules_on_every_client_os() {
        let mut reference = reference();
        let mut value = manifest();
        assert_eq!(credential_name(&reference, &value).unwrap(), "boot-token");
        value["bootTokenFile"] = json!("/home/user/.harnex/runtime-domains/server-main/boot-token");
        assert_eq!(credential_name(&reference, &value).unwrap(), "boot-token");
        for path in [
            "../boot-token",
            "/tmp/secret",
            "nested/secret",
            "C:\\secret",
            ".",
            "..",
        ] {
            value["bootTokenFile"] = json!(path);
            assert!(credential_name(&reference, &value).is_err(), "{path}");
        }
        reference.domain_id = "..".into();
        assert!(reference.validate().is_err());
    }
    fn server(replies: Vec<(&'static str, Value)>) -> (String, thread::JoinHandle<Vec<String>>) {
        use std::io::{Read, Write};
        let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
        let endpoint = format!("http://{}", listener.local_addr().unwrap());
        let worker = thread::spawn(move || {
            let mut requests = Vec::new();
            for (status, body) in replies {
                let (mut stream, _) = listener.accept().unwrap();
                stream
                    .set_read_timeout(Some(Duration::from_secs(5)))
                    .unwrap();
                let mut bytes = Vec::new();
                let mut chunk = [0; 1024];
                loop {
                    let count = stream.read(&mut chunk).unwrap();
                    assert!(count > 0);
                    bytes.extend_from_slice(&chunk[..count]);
                    if let Some(index) = bytes.windows(4).position(|value| value == b"\r\n\r\n") {
                        let headers = String::from_utf8_lossy(&bytes[..index]).to_lowercase();
                        let len = headers
                            .lines()
                            .find_map(|line| {
                                line.strip_prefix("content-length:")
                                    .and_then(|value| value.trim().parse::<usize>().ok())
                            })
                            .unwrap_or(0);
                        if bytes.len() >= index + 4 + len {
                            break;
                        }
                    }
                }
                requests.push(String::from_utf8(bytes).unwrap());
                let body = body.to_string();
                write!(stream,"HTTP/1.1 {status}\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{body}",body.len()).unwrap();
            }
            requests
        });
        (endpoint, worker)
    }
    #[test]
    fn boot_token_exchange_verifies_workspace_before_accepting_session() {
        let (endpoint, worker) = server(vec![
            ("200 OK", json!({"token":"fixture-session"})),
            (
                "200 OK",
                json!({"workspaceId":"project-scope","instanceId":"server-instance","root":"/srv/project"}),
            ),
            (
                "200 OK",
                json!({"name":"harnex-local-api","version":"1.0","runtimeProfile":"PROJECT","runtimeVersion":"2.0","runtimeSha256":"fixture-build"}),
            ),
        ]);
        let mut descriptor = descriptor();
        authenticate(&endpoint, &mut descriptor, "fixture-boot".into(), true).unwrap();
        assert_eq!(descriptor.token, "fixture-session");
        assert_eq!(descriptor.runtime_version, "2.0");
        assert_eq!(descriptor.ownership, "attached");
        assert!(descriptor.supervisor_token.is_empty());
        let requests = worker.join().unwrap();
        assert!(requests[0].starts_with("POST /api/v1/auth/boot-token"));
        assert!(requests[1]
            .to_lowercase()
            .contains("authorization: bearer fixture-session"));
        assert!(requests.iter().all(|request| request
            .to_lowercase()
            .contains("x-harnex-scope-id: project-scope")));
    }
    #[test]
    fn wrong_workspace_or_protocol_never_installs_a_session() {
        for wrong_protocol in [false, true] {
            let mut responses = vec![(
                "200 OK",
                json!({"workspaceId":"project-scope","instanceId":"server-instance",
                "root":if wrong_protocol {"/srv/project"} else {"/other"}}),
            )];
            if wrong_protocol {
                responses.push((
                    "200 OK",
                    json!({"name":"unrelated-service","version":"1.0","runtimeProfile":"PROJECT"}),
                ));
            }
            let (endpoint, worker) = server(responses);
            let mut descriptor = descriptor();
            assert!(
                authenticate(&endpoint, &mut descriptor, "fixture-bearer".into(), false).is_err()
            );
            assert!(descriptor.token.is_empty());
            worker.join().unwrap();
        }
    }
}
