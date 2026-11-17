use super::*;
use harnex_host_runtime::{Connection, Profile, ProfileStore};

fn store() -> ProfileStore {
    ProfileStore::new(
        non_blank_env("HARNEX_USER_DATA_DIR")
            .map(PathBuf::from)
            .unwrap_or_else(|| home_dir().join(".harnex")),
    )
}

pub(super) fn jar() -> Result<PathBuf, String> {
    if let Some(value) = non_blank_env("HARNEX_JAR") {
        let path = canonical(Path::new(&value))?;
        if !path.is_file() {
            return Err("HARNEX_JAR must identify a packaged Runtime".into());
        }
        return Ok(path);
    }
    Ok(RuntimeBundle::locate_and_verify()?.jar)
}

pub(super) fn connect(args: &[String], owner: &str) -> Result<Connection, String> {
    use std::io::IsTerminal;
    let store = store();
    let interactive = std::io::stdin().is_terminal() && std::io::stderr().is_terminal();
    let profile = match option(args, "--remote").filter(|id| !id.is_empty() && !id.starts_with('-')) {
        Some(id) => store.get(&id)?,
        None if interactive => terminal_host(&store)?,
        None => return Err("--remote requires a saved connection or SSH config alias; it never falls back to a local workspace".into()),
    };
    let root = if interactive {
        eprintln!(
            "SSH · {} ({}) — directories and project tools run on this remote host.",
            profile.name, profile.host
        );
        let listing = terminal_attempt(&profile, "connect", "~")?;
        let root = option(args, "--workspace")
            .map(Ok)
            .unwrap_or_else(|| terminal_directory(&profile, listing))?;
        terminal_attempt(&profile, "prepare", &root)?;
        root
    } else {
        option(args, "--workspace").ok_or("headless SSH connections require --workspace /absolute/remote/path; use harnex tui --remote <alias> to choose a remote directory")?
    };
    harnex_host_runtime::ssh::validate_root(&root)?;
    let lease = store.owner_lease(owner, &profile.id)?;
    let domain = store.owner_domain(owner, &profile.id)?;
    let runtime = jar()?;
    let port = free_port()?;
    let mut connection = if interactive {
        terminal_lifecycle(move || {
            Connection::connect(&profile, &runtime, &domain, &root, port, None)
        })?
    } else {
        Connection::connect(&profile, &runtime, &domain, &root, port, None)?
    };
    connection.owner_lease = Some(lease);
    Ok(connection)
}

pub(super) fn close(mut connection: Connection) -> Result<(), String> {
    use std::io::IsTerminal;
    if std::io::stdin().is_terminal() && std::io::stderr().is_terminal() {
        terminal_lifecycle(move || connection.close())
    } else {
        connection.close()
    }
}

fn terminal_lifecycle<T: Send + 'static>(
    action: impl FnOnce() -> Result<T, String> + Send + 'static,
) -> Result<T, String> {
    let hub = harnex_host_runtime::interaction::global()?;
    hub.observe_terminal(true);
    struct Observer(Arc<harnex_host_runtime::interaction::Hub>);
    impl Drop for Observer {
        fn drop(&mut self) {
            self.0.observe_terminal(false);
        }
    }
    let _observer = Observer(hub.clone());
    let (sender, receiver) = std::sync::mpsc::channel();
    thread::spawn(move || {
        let _ = sender.send(action());
    });
    let _terminal = TerminalMode::enter()?;
    let mut answered = String::new();
    loop {
        match receiver.try_recv() {
            Ok(result) => return result,
            Err(std::sync::mpsc::TryRecvError::Disconnected) => {
                return Err("SSH connection worker stopped".into())
            }
            Err(std::sync::mpsc::TryRecvError::Empty) => {}
        }
        let status = hub.request("pending", Value::Null, PathBuf::new(), None)?;
        if status["challenge"].is_object() && status["challenge"]["id"].as_str() != Some(&answered)
        {
            let challenge = &status["challenge"];
            answered = challenge["id"].as_str().unwrap_or("").into();
            let prompt: String = challenge["prompt"]
                .as_str()
                .unwrap_or("SSH authentication")
                .chars()
                .filter(|c| !c.is_control() || *c == '\n')
                .collect();
            match terminal_input(
                &format!(
                    "{prompt}\n{}",
                    if challenge["kind"] == "hostKey" {
                        "Type yes to trust (Esc cancels): "
                    } else {
                        "Credential (hidden, Esc cancels): "
                    }
                ),
                challenge["kind"] != "hostKey",
            ) {
                Ok(answer) => {
                    hub.request(
                        "reply",
                        json!({"id":status["id"],"challengeId":challenge["id"],"answer":answer}),
                        PathBuf::new(),
                        None,
                    )?;
                }
                Err(_) => {
                    let _ = hub.request("cancel", json!({"id":status["id"]}), PathBuf::new(), None);
                }
            }
        }
        if crossterm::event::poll(Duration::from_millis(150)).map_err(display)? {
            if let crossterm::event::Event::Key(key) = crossterm::event::read().map_err(display)? {
                use crossterm::event::{KeyCode, KeyModifiers};
                if key.code == KeyCode::Esc
                    || key.code == KeyCode::Char('c')
                        && key.modifiers.contains(KeyModifiers::CONTROL)
                {
                    let _ = hub.request("cancel", json!({"id":status["id"]}), PathBuf::new(), None);
                }
            }
        }
    }
}

fn terminal_host(store: &ProfileStore) -> Result<Profile, String> {
    let mut profiles = store.list()?;
    let discovered = harnex_host_runtime::ssh::discover();
    for alias in discovered["aliases"]
        .as_array()
        .into_iter()
        .flatten()
        .filter_map(Value::as_str)
    {
        if !profiles.iter().any(|p| p.host == alias) {
            profiles.push(store.get(alias)?);
        }
    }
    eprintln!("SSH remote project — uses this computer's SSH configuration and keys");
    for (index, profile) in profiles.iter().enumerate() {
        eprintln!("  {}. {} · {}", index + 1, profile.name, profile.host);
    }
    loop {
        let input = terminal_input(
            "Choose a host number, SSH alias or user@hostname (Esc cancels): ",
            false,
        )?;
        if let Ok(index) = input.parse::<usize>() {
            if let Some(profile) = profiles.get(index.wrapping_sub(1)) {
                return Ok(profile.clone());
            }
        } else if let Ok(profile) = store.get(&input) {
            return Ok(profile);
        } else if let Ok(parsed) = harnex_host_runtime::ssh::parse_destination(&input) {
            return Ok(Profile {
                id: Uuid::new_v4().to_string(),
                name: parsed["host"].as_str().unwrap_or("").into(),
                host: parsed["host"].as_str().unwrap_or("").into(),
                port: parsed["port"].as_u64().map(|p| p as u16),
                identity_file: parsed["identityFile"].as_str().map(PathBuf::from),
                java: "java".into(),
            });
        }
        eprintln!("Enter a listed host or a valid SSH destination.");
    }
}

fn terminal_attempt(profile: &Profile, operation: &str, root: &str) -> Result<Value, String> {
    let hub = harnex_host_runtime::interaction::global()?;
    let data = non_blank_env("HARNEX_USER_DATA_DIR")
        .map(PathBuf::from)
        .unwrap_or_else(|| home_dir().join(".harnex"));
    let runtime = if operation == "prepare" {
        Some(jar()?)
    } else {
        None
    };
    let started = hub.request(
        "start",
        json!({"profile":profile,"operation":operation,"root":root}),
        data.clone(),
        runtime,
    )?;
    let id = started["id"].as_str().ok_or("SSH attempt id missing")?;
    let _terminal = TerminalMode::enter()?;
    let mut phase = String::new();
    let mut last_challenge = String::new();
    loop {
        let status = hub.request("status", json!({"id":id}), data.clone(), None)?;
        if status["phase"].as_str() != Some(&phase) {
            phase = status["phase"].as_str().unwrap_or("connecting").into();
            eprintln!(
                "SSH · {}\r",
                match phase.as_str() {
                    "environment" => "Checking the remote environment…",
                    "download" => "Downloading a private Java runtime…",
                    "upload" => "Preparing remote Runtime files…",
                    "runtime" => "Checking the remote project…",
                    _ => "Connecting…",
                }
            );
        }
        match status["state"].as_str() {
            Some("succeeded") => return Ok(status["result"].clone()),
            Some("failed") => {
                return Err(status["error"]
                    .as_str()
                    .unwrap_or("SSH connection failed")
                    .into())
            }
            Some("cancelled") => return Err("SSH connection cancelled".into()),
            _ => {}
        }
        if status["challenge"].is_object()
            && status["challenge"]["id"].as_str() != Some(&last_challenge)
        {
            let challenge = &status["challenge"];
            last_challenge = challenge["id"].as_str().unwrap_or("").into();
            let prompt: String = challenge["prompt"]
                .as_str()
                .unwrap_or("SSH authentication")
                .chars()
                .filter(|c| !c.is_control() || *c == '\n')
                .collect();
            let result = terminal_input(
                &format!(
                    "{prompt}\n{}",
                    if challenge["kind"] == "hostKey" {
                        "Type yes to trust this host (Esc cancels): "
                    } else {
                        "Credential (hidden, Esc cancels): "
                    }
                ),
                challenge["kind"] != "hostKey",
            );
            match result {
                Ok(answer) => {
                    hub.request(
                        "reply",
                        json!({"id":id,"challengeId":challenge["id"],"answer":answer}),
                        data.clone(),
                        None,
                    )?;
                }
                Err(error) => {
                    let _ = hub.request("cancel", json!({"id":id}), data.clone(), None);
                    return Err(error);
                }
            }
        }
        if crossterm::event::poll(Duration::from_millis(100)).map_err(display)? {
            if let crossterm::event::Event::Key(key) = crossterm::event::read().map_err(display)? {
                use crossterm::event::{KeyCode, KeyModifiers};
                if key.code == KeyCode::Esc
                    || key.code == KeyCode::Char('c')
                        && key.modifiers.contains(KeyModifiers::CONTROL)
                {
                    let _ = hub.request("cancel", json!({"id":id}), data.clone(), None);
                    return Err("SSH connection cancelled".into());
                }
            }
        }
        thread::sleep(Duration::from_millis(150));
    }
}

fn terminal_directory(profile: &Profile, mut listing: Value) -> Result<String, String> {
    loop {
        let current = listing["root"].as_str().ok_or("remote directory missing")?;
        eprintln!("\nSSH · {} · {current}", profile.host);
        let directories: Vec<_> = listing["directories"]
            .as_array()
            .into_iter()
            .flatten()
            .filter(|d| d["hidden"] != true)
            .take(20)
            .collect();
        for (index, directory) in directories.iter().enumerate() {
            eprintln!(
                "  {}. {}/",
                index + 1,
                directory["name"].as_str().unwrap_or("")
            );
        }
        let answer = terminal_input("Enter opens this remote directory; number enters a folder; .. goes up; or enter /absolute/path (Esc cancels): ", false)?;
        if answer.is_empty() {
            return Ok(current.to_string());
        }
        let target = if answer == ".." {
            listing["parentPath"].as_str().unwrap_or("/").to_string()
        } else if let Ok(index) = answer.parse::<usize>() {
            directories
                .get(index.wrapping_sub(1))
                .and_then(|d| d["path"].as_str())
                .ok_or("choose a listed directory number")?
                .into()
        } else {
            answer
        };
        match terminal_attempt(profile, "directories", &target) {
            Ok(result) => listing = result,
            Err(error) => eprintln!("{error}"),
        }
    }
}

struct TerminalMode(bool);
impl TerminalMode {
    fn enter() -> Result<Self, String> {
        let was_raw = crossterm::terminal::is_raw_mode_enabled().map_err(display)?;
        if !was_raw {
            crossterm::terminal::enable_raw_mode().map_err(display)?;
        }
        Ok(Self(was_raw))
    }
}
impl Drop for TerminalMode {
    fn drop(&mut self) {
        if !self.0 {
            let _ = crossterm::terminal::disable_raw_mode();
        }
    }
}

fn terminal_input(prompt: &str, hidden: bool) -> Result<String, String> {
    use crossterm::event::{self, Event, KeyCode, KeyEventKind, KeyModifiers};
    eprint!("{}", prompt.replace('\n', "\r\n"));
    std::io::stderr().flush().map_err(display)?;
    let _terminal = TerminalMode::enter()?;
    struct Newline;
    impl Drop for Newline {
        fn drop(&mut self) {
            eprint!("\r\n");
        }
    }
    let _newline = Newline;
    let mut value = String::new();
    loop {
        if let Event::Key(key) = event::read().map_err(display)? {
            if key.kind != KeyEventKind::Press {
                continue;
            }
            match key.code {
                KeyCode::Esc => return Err("SSH connection cancelled".into()),
                KeyCode::Char('c') if key.modifiers.contains(KeyModifiers::CONTROL) => {
                    return Err("SSH connection cancelled".into())
                }
                KeyCode::Enter => return Ok(value),
                KeyCode::Backspace => {
                    if value.pop().is_some() && !hidden {
                        eprint!("\u{8} \u{8}");
                    }
                }
                KeyCode::Char(ch) if !ch.is_control() && value.len() < 16_384 => {
                    value.push(ch);
                    if !hidden {
                        eprint!("{ch}");
                    }
                }
                _ => {}
            }
            std::io::stderr().flush().map_err(display)?;
        }
    }
}

pub(super) fn command(args: &[String]) -> Result<(), String> {
    use std::io::IsTerminal;
    let operation = args.first().map(String::as_str).unwrap_or("list");
    let id = args.get(1).map(String::as_str).unwrap_or("");
    let store = store();
    match operation {
        "capabilities" => println!(
            "{}",
            json!({"protocolVersion":1,"capabilities":["ssh.interaction","ssh.workspace-owners","ssh.runtime-reuse","ssh.services","ssh.upgrade","ssh.cache-gc"]})
        ),
        "service" => {
            use std::io::IsTerminal;
            if std::io::stdout().is_terminal() {
                return Err("remote service is a private host protocol".into());
            }
            let hub = harnex_host_runtime::interaction::global()?;
            println!(
                "{}",
                json!({"ready":true,"protocolVersion":1,"environment":hub.environment()})
            );
            std::io::stdout().flush().map_err(display)?;
            for line in std::io::stdin().lock().lines() {
                let line = line.map_err(display)?;
                if line.len() > 65_536 {
                    return Err("connection request too large".into());
                }
                let request: Value = serde_json::from_str(&line).map_err(display)?;
                let root = non_blank_env("HARNEX_USER_DATA_DIR")
                    .map(PathBuf::from)
                    .unwrap_or_else(|| home_dir().join(".harnex"));
                let runtime = if request["args"]["operation"] == "prepare" {
                    request["jar"]
                        .as_str()
                        .map(PathBuf::from)
                        .or_else(|| jar().ok())
                } else {
                    None
                };
                let result = hub.request(
                    request["action"].as_str().unwrap_or(""),
                    request["args"].clone(),
                    root,
                    runtime,
                );
                let reply = match result {
                    Ok(result) => json!({"id":request["id"],"result":result}),
                    Err(error) => json!({"id":request["id"],"error":error}),
                };
                println!("{reply}");
                std::io::stdout().flush().map_err(display)?;
            }
        }
        "discover" => println!("{}", harnex_host_runtime::ssh::discover()),
        "close-bound" | "upgrade-bound" => {
            use std::io::Read;
            let control: harnex_host_runtime::RemoteControl =
                serde_json::from_reader(std::io::stdin().lock().take(65_536)).map_err(display)?;
            if operation == "upgrade-bound" {
                control.upgrade(&jar()?)?;
                println!("{{\"updated\":true}}");
            } else {
                control.close()?;
                println!("{{\"closed\":true}}");
            }
        }
        "list" => println!(
            "{}",
            serde_json::to_string(&store.list()?).map_err(display)?
        ),
        "add" => {
            let profile = Profile {
                id: id.into(),
                name: option(args, "--name").unwrap_or_else(|| id.into()),
                host: option(args, "--host")
                    .ok_or("remote add requires --host alias or user@hostname")?,
                port: option(args, "--port")
                    .map(|v| v.parse::<u16>().map_err(display))
                    .transpose()?,
                identity_file: option(args, "--identity-file").map(PathBuf::from),
                java: option(args, "--java").unwrap_or_else(|| "java".into()),
            };
            store.save(profile)?;
            println!("{{\"saved\":true}}");
        }
        "save" => {
            let raw =
                option(args, "--profile-json").ok_or("remote save requires --profile-json")?;
            store.save(serde_json::from_str(&raw).map_err(display)?)?;
            println!("{{\"saved\":true}}");
        }
        "remove" => {
            store.remove(id)?;
            println!("{{\"removed\":true}}");
        }
        "close" => {
            let profile = store.get(id)?;
            let owner = option(args, "--owner").ok_or("remote close requires --owner")?;
            let root = option(args, "--workspace").ok_or("remote close requires --workspace")?;
            let instance = option(args, "--instance").ok_or("remote close requires --instance")?;
            harnex_host_runtime::close_owned(
                &profile,
                &jar()?,
                &store.owner_domain(&owner, &profile.id)?,
                &root,
                &instance,
            )?;
            println!("{{\"closed\":true}}");
        }
        "test" | "describe" | "directories" => {
            let profile = store.get(id)?;
            let operation = if operation == "test" {
                "probe"
            } else {
                operation
            };
            let root = option(args, "--workspace");
            let result = if std::io::stdin().is_terminal() && std::io::stderr().is_terminal() {
                terminal_attempt(
                    &profile,
                    if operation == "describe" {
                        "prepare"
                    } else if operation == "probe" {
                        "connect"
                    } else {
                        "directories"
                    },
                    root.as_deref().unwrap_or("~"),
                )?
            } else if operation == "describe" {
                harnex_host_runtime::inspect(&profile, &jar()?, operation, root.as_deref())?
            } else {
                harnex_host_runtime::ssh::inspect(&profile, operation, root.as_deref())?
            };
            println!("{}", serde_json::to_string(&result).map_err(display)?);
        }
        "bridge" => {
            use std::io::IsTerminal;
            if std::io::stdout().is_terminal() {
                return Err("remote bridge is a private host protocol; use tui --remote for interactive work".into());
            }
            let profile = store.get(id)?;
            let root = option(args, "--workspace").ok_or("remote bridge requires --workspace")?;
            let owner = option(args, "--owner").ok_or("remote bridge requires --owner")?;
            let expected =
                option(args, "--workspace-id").ok_or("remote bridge requires --workspace-id")?;
            let domain = store.workspace_domain(&owner, &profile.id, &expected)?;
            let _lease = store.owner_lease(&owner, &profile.id)?;
            let port = option(args, "--port")
                .ok_or("remote bridge requires --port")?
                .parse::<u16>()
                .map_err(display)?;
            let mut connection = if let Some(raw) = option(args, "--service-json") {
                let reference: harnex_host_runtime::services::ServiceRef =
                    serde_json::from_str(&raw).map_err(display)?;
                if reference.root != root || reference.scope_id != expected {
                    return Err("existing service selection changed".into());
                }
                harnex_host_runtime::services::connect(&profile, &reference, port)?
            } else {
                Connection::connect(&profile, &jar()?, &domain, &root, port, Some(&expected))?
            };
            println!(
                "{}",
                serde_json::to_string(&json!({"ready":true,"endpoint":connection.endpoint,
                "remote":connection.control.descriptor,"control":connection.control}))
                .map_err(display)?
            );
            std::io::stdout().flush().map_err(display)?;
            // EOF is a lost owner connection, not proof that remote work was cancelled.
            for line in std::io::stdin().lock().lines() {
                match line.map_err(display)?.trim() {
                    "close" => {
                        connection.close()?;
                        println!("{{\"closed\":true}}");
                        return Ok(());
                    }
                    "status" => {
                        let connected = connection
                            .tunnel
                            .as_ref()
                            .is_some_and(|tunnel| tunnel.connected());
                        println!("{}", json!({"connected":connected}));
                        std::io::stdout().flush().map_err(display)?;
                    }
                    _ => return Err("unknown remote bridge command".into()),
                }
            }
        }
        _ => {
            return Err(
                "remote requires list, add, save, remove, test, describe or directories".into(),
            )
        }
    }
    Ok(())
}
