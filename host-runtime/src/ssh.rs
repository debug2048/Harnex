//! SSH configuration discovery and filesystem navigation do not require a remote JVM.
use super::*;
use std::collections::BTreeSet;

pub fn home_directory() -> PathBuf {
    std::env::var_os(if cfg!(windows) { "USERPROFILE" } else { "HOME" })
        .map(PathBuf::from)
        .unwrap_or_default()
}

pub fn expand_identity(path: &Path) -> PathBuf {
    let value = path.to_string_lossy();
    if let Some(relative) = value
        .strip_prefix("~/")
        .or_else(|| value.strip_prefix("~\\"))
    {
        home_directory().join(relative)
    } else {
        path.to_path_buf()
    }
}

/// OpenSSH still resolves all settings, Match blocks and proxy commands itself.
/// Discovery only extracts concrete Host names; it never executes configuration text.
pub fn discover() -> Value {
    let config = home_directory().join(".ssh/config");
    let mut aliases = BTreeSet::new();
    let mut visited = BTreeSet::new();
    read_config(&config, &mut aliases, &mut visited, 0);
    json!({"aliases": aliases, "configPath": config, "machine": std::env::var("COMPUTERNAME")
        .or_else(|_| std::env::var("HOSTNAME")).unwrap_or_else(|_| "SSH host application".into())})
}

pub(crate) fn configuration_fingerprint() -> String {
    let mut visited = BTreeSet::new();
    let mut aliases = BTreeSet::new();
    read_config(
        &home_directory().join(".ssh/config"),
        &mut aliases,
        &mut visited,
        0,
    );
    let system = if cfg!(windows) {
        std::env::var_os("PROGRAMDATA")
            .map(PathBuf::from)
            .unwrap_or_else(|| PathBuf::from("C:/ProgramData"))
            .join("ssh/ssh_config")
    } else {
        PathBuf::from("/etc/ssh/ssh_config")
    };
    read_config(&system, &mut aliases, &mut visited, 0);
    let mut digest = Sha256::new();
    for path in visited {
        digest.update(path.to_string_lossy().as_bytes());
        digest.update(b"\0");
        if let Ok(mut file) = File::open(path) {
            let mut bytes = Vec::new();
            let _ = (&mut file).take(1_000_000).read_to_end(&mut bytes);
            digest.update(bytes);
        }
    }
    format!("{:x}", digest.finalize())
}

fn read_config(
    path: &Path,
    aliases: &mut BTreeSet<String>,
    visited: &mut BTreeSet<PathBuf>,
    depth: usize,
) {
    if depth > 8 || visited.len() >= 128 || !visited.insert(path.to_path_buf()) {
        return;
    }
    let Ok(raw) = fs::read_to_string(path) else {
        return;
    };
    if raw.len() > 1_000_000 {
        return;
    }
    for line in raw.lines() {
        let Ok(words) = words(line) else {
            continue;
        };
        let Some(key) = words.first() else {
            continue;
        };
        if key.eq_ignore_ascii_case("host") {
            for alias in &words[1..] {
                if !alias.is_empty()
                    && !alias.starts_with('-')
                    && alias
                        .chars()
                        .all(|c| c.is_ascii_alphanumeric() || "_.-".contains(c))
                {
                    aliases.insert(alias.clone());
                }
            }
        } else if key.eq_ignore_ascii_case("include") {
            for pattern in &words[1..] {
                let mut pattern = expand_identity(Path::new(pattern));
                if !pattern.is_absolute() {
                    pattern = home_directory().join(".ssh").join(pattern);
                }
                if let Ok(paths) = glob::glob(&pattern.to_string_lossy()) {
                    for included in paths.flatten().take(128) {
                        read_config(&included, aliases, visited, depth + 1);
                    }
                }
            }
        }
    }
}

fn words(input: &str) -> Result<Vec<String>, String> {
    let mut result = Vec::new();
    let mut word = String::new();
    let mut delimiter = None;
    let mut escaped = false;
    for ch in input.chars() {
        if escaped {
            word.push(ch);
            escaped = false;
            continue;
        }
        if ch == '\\' && delimiter != Some('\'') {
            escaped = true;
            continue;
        }
        if let Some(quote) = delimiter {
            if ch == quote {
                delimiter = None;
            } else {
                word.push(ch);
            }
        } else if ch == '\'' || ch == '"' {
            delimiter = Some(ch);
        } else if ch == '#' {
            break;
        } else if ch.is_whitespace() || ch == '=' {
            if !word.is_empty() {
                result.push(std::mem::take(&mut word));
            }
        } else {
            word.push(ch);
        }
    }
    if delimiter.is_some() || escaped {
        return Err("incomplete SSH command quoting".into());
    }
    if !word.is_empty() {
        result.push(word);
    }
    Ok(result)
}

/// Deliberately allow only connection fields. Pasted SSH commands are never evaluated.
pub fn parse_destination(input: &str) -> Result<Value, String> {
    let mut args = words(input)?.into_iter().peekable();
    if args.peek().is_some_and(|s| s == "ssh") {
        args.next();
    }
    let mut host = None;
    let mut user = None;
    let mut port = None;
    let mut identity = None;
    while let Some(arg) = args.next() {
        match arg.as_str() {
            "-p" => {
                port = Some(
                    args.next()
                        .ok_or("-p requires a port")?
                        .parse::<u16>()
                        .map_err(err)?,
                )
            }
            "-l" => user = Some(args.next().ok_or("-l requires a user")?),
            "-i" => identity = Some(args.next().ok_or("-i requires a private key path")?),
            _ if arg.starts_with('-') => {
                return Err(format!(
                    "unsupported pasted SSH option {arg}; use ~/.ssh/config for advanced settings"
                ))
            }
            _ if host.is_none() => host = Some(arg),
            _ => return Err("paste an SSH connection without a remote command".into()),
        }
    }
    let mut host = host.ok_or("SSH hostname or config alias is required")?;
    if let Some(user) = user {
        if host.contains('@') {
            return Err("specify the SSH user once".into());
        }
        host = format!("{user}@{host}");
    }
    let profile = Profile {
        id: "draft".into(),
        name: host.clone(),
        host: host.clone(),
        port,
        identity_file: identity.map(PathBuf::from),
        java: default_java(),
    };
    profile.validate()?;
    Ok(json!({"host":host,"port":profile.port,"identityFile":profile.identity_file}))
}

pub fn validate_root(root: &str) -> Result<(), String> {
    if root.contains(['\0', '\n', '\r']) {
        return Err("invalid remote directory".into());
    }
    if root != "~" && !root.starts_with("~/") && !root.starts_with('/') {
        return Err(
            "remote directory must start with / or ~/; local paths cannot be used for SSH projects"
                .into(),
        );
    }
    Ok(())
}

pub(crate) fn routing_key(profile: &Profile) -> Result<String, String> {
    routing_key_interruptible(profile, None)
}

pub(crate) fn routing_key_interruptible(
    profile: &Profile,
    stop: Option<&AtomicBool>,
) -> Result<String, String> {
    let mut command = profile.ssh()?;
    command.arg("-G").arg(&profile.host);
    resolved_routing_key(&run_interruptible(
        command,
        Stdio::null(),
        Duration::from_secs(20),
        stop,
    )?)
}

fn resolved_routing_key(output: &str) -> Result<String, String> {
    let mut route = std::collections::BTreeMap::new();
    for line in output.lines() {
        if let Some((key, value)) = line.split_once(char::is_whitespace) {
            if [
                "hostname",
                "user",
                "port",
                "proxycommand",
                "proxyjump",
                "hostkeyalias",
                "bindaddress",
                "bindinterface",
            ]
            .contains(&key)
            {
                route.insert(key, value.trim());
            }
        }
    }
    if !["hostname", "user", "port"]
        .iter()
        .all(|key| route.contains_key(key))
    {
        return Err("OpenSSH did not resolve the connection destination".into());
    }
    Ok(hex_hash(&serde_json::to_vec(&route).map_err(err)?))
}

pub fn inspect(profile: &Profile, operation: &str, root: Option<&str>) -> Result<Value, String> {
    if !matches!(operation, "probe" | "directories") {
        return Err("invalid SSH inspection".into());
    }
    let root = root.unwrap_or("~");
    validate_root(root)?;
    if root.contains(['\0', '\n', '\r']) {
        return Err("invalid remote directory".into());
    }
    let path = if root == "~" || root.is_empty() {
        "\"$HOME\"".into()
    } else if let Some(relative) = root.strip_prefix("~/") {
        format!("\"$HOME\"/{}", quote(relative))
    } else if root.starts_with('/') {
        quote(root)
    } else {
        return Err("remote directory must start with / or ~/".into());
    };
    let script = format!(
        r#"set -eu; cd {path}; p=$(pwd -P); printf 'HARNEX_FS_V1\000%s\000%s\000%s\000%s\000' "$p" "$HOME" "$(uname -s)" "$(uname -m)"; n=0; for d in ./* ./.[!.]* ./..?*; do if [ -d "$d" ]; then printf '%s\000' "${{d#./}}"; n=$((n+1)); if [ "$n" -ge 501 ]; then break; fi; fi; done"#
    );
    let mut command = profile.ssh()?;
    command.arg(&profile.host).arg(script);
    parse_listing(&run(command, Stdio::null(), Duration::from_secs(30))?)
}

fn parse_listing(output: &str) -> Result<Value, String> {
    let payload = output
        .split_once("HARNEX_FS_V1\0")
        .ok_or("SSH directory response was incomplete")?
        .1;
    let mut fields = payload.split('\0');
    let root = fields.next().ok_or("missing remote path")?;
    let home = fields.next().ok_or("missing remote home")?;
    let platform = fields.next().ok_or("missing remote platform")?;
    let arch = fields.next().ok_or("missing remote architecture")?;
    if !root.starts_with('/') || !home.starts_with('/') || !payload.ends_with('\0') {
        return Err("invalid remote directory response".into());
    }
    let mut names: Vec<_> = fields.filter(|name| !name.is_empty()).collect();
    names.sort();
    if names
        .iter()
        .any(|name| name.contains('/') || *name == "." || *name == "..")
    {
        return Err("invalid remote directory entry".into());
    }
    let truncated = names.len() > 500;
    let directories: Vec<_> = names
        .into_iter()
        .take(500)
        .map(|name| {
            json!({"name":name,
        "path":format!("{}/{name}", root.trim_end_matches('/')), "hidden":name.starts_with('.')})
        })
        .collect();
    let parent = if root == "/" {
        None
    } else {
        Some(
            root.rsplit_once('/')
                .map(|(parent, _)| if parent.is_empty() { "/" } else { parent })
                .unwrap_or("/"),
        )
    };
    Ok(
        json!({"root":root,"home":home,"platform":platform,"arch":arch,"parentPath":parent,"directories":directories,"truncated":truncated}),
    )
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn paste_does_not_allow_commands_or_options_that_execute_code() {
        for value in [
            "ssh host touch /tmp/pwn",
            "ssh -o ProxyCommand=evil host",
            "ssh $(whoami)",
            "ssh -p 0 host",
        ] {
            assert!(parse_destination(value).is_err(), "{value}");
        }
        assert_eq!(
            parse_destination("ssh -p 2202 -l dev example.org").unwrap()["host"],
            "dev@example.org"
        );
    }
    #[test]
    fn local_paths_are_rejected_before_preparing_an_ssh_project() {
        for root in [
            "C:\\code\\project",
            "D:/project",
            "relative/project",
            "",
            "~/bad\npath",
        ] {
            assert!(validate_root(root).is_err(), "{root}");
        }
        for root in ["/srv/项目", "~/project with spaces", "~", "/"] {
            assert!(validate_root(root).is_ok());
        }
    }
    #[test]
    fn alias_destination_changes_are_detected_without_rejecting_unrelated_settings() {
        let base = "hostname first.example\nuser dev\nport 22\nserveraliveinterval 15\n";
        assert_eq!(
            resolved_routing_key(base).unwrap(),
            resolved_routing_key(&base.replace("interval 15", "interval 30")).unwrap()
        );
        assert_ne!(
            resolved_routing_key(base).unwrap(),
            resolved_routing_key(&base.replace("first.example", "second.example")).unwrap()
        );
        assert_ne!(
            resolved_routing_key(base).unwrap(),
            resolved_routing_key(&format!("{base}proxyjump bastion\n")).unwrap()
        );
    }
    #[test]
    fn remote_listing_preserves_unicode_spaces_and_newlines() {
        let result = parse_listing(
            "banner\nHARNEX_FS_V1\0/srv/项目\0/home/dev\0Linux\0aarch64\0a b\0line\nbreak\0",
        )
        .unwrap();
        assert_eq!(result["directories"][0]["path"], "/srv/项目/a b");
        assert_eq!(result["parentPath"], "/srv");
        assert!(parse_listing("HARNEX_FS_V1\0/local\0/home\0Linux\0x86_64\0../oops\0").is_err());
    }
    #[test]
    fn discovery_reads_includes_and_ignores_patterns_and_remote_commands() {
        let base = std::env::temp_dir().join(format!("harnex-ssh-config-{}", Uuid::new_v4()));
        fs::create_dir_all(base.join("included configs")).unwrap();
        let include = base.join("included configs/host.conf");
        fs::write(
            &include,
            "Host staging backup\n HostName server.example\n ProxyCommand do-not-run\n",
        )
        .unwrap();
        let config = base.join("config");
        let pattern = base
            .join("included configs/*.conf")
            .to_string_lossy()
            .replace('\\', "/");
        fs::write(
            &config,
            format!("Host dev * !private *.example\nInclude \"{pattern}\"\n"),
        )
        .unwrap();
        let mut aliases = BTreeSet::new();
        read_config(&config, &mut aliases, &mut BTreeSet::new(), 0);
        assert_eq!(
            aliases,
            BTreeSet::from([
                "dev".to_string(),
                "staging".to_string(),
                "backup".to_string()
            ])
        );
        let checked = base.canonicalize().unwrap();
        assert!(checked.starts_with(std::env::temp_dir().canonicalize().unwrap()));
        fs::remove_dir_all(checked).unwrap();
    }
}
