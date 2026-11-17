//! Reconnecting verifies existing artifacts in one SSH request; only missing resources take the install path.
use super::*;
use std::time::SystemTime;

#[derive(Clone, PartialEq, Eq)]
struct Stamp {
    length: u64,
    modified: Option<SystemTime>,
    created: Option<SystemTime>,
    #[cfg(unix)]
    identity: (u64, u64, i64, i64),
}
fn stamp(metadata: &fs::Metadata) -> Stamp {
    #[cfg(unix)]
    use std::os::unix::fs::MetadataExt;
    Stamp {
        length: metadata.len(),
        modified: metadata.modified().ok(),
        created: metadata.created().ok(),
        #[cfg(unix)]
        identity: (
            metadata.dev(),
            metadata.ino(),
            metadata.ctime(),
            metadata.ctime_nsec(),
        ),
    }
}
struct CachedJar {
    path: PathBuf,
    stamp: Stamp,
    hash: String,
}

/// Cache only in this process and only while the local file identity/metadata stays unchanged.
/// The remote copy is still fully checksummed on every connection.
pub(super) fn fingerprint(jar: &Path) -> Result<String, String> {
    static CACHE: std::sync::OnceLock<Mutex<Vec<CachedJar>>> = std::sync::OnceLock::new();
    interaction::check_cancelled()?;
    if !jar.is_file() {
        return Err("packaged Harnex Runtime JAR is required for remote connections".into());
    }
    let path = jar.canonicalize().map_err(err)?;
    let mut source = File::open(&path).map_err(err)?;
    let before = stamp(&source.metadata().map_err(err)?);
    let cache = CACHE.get_or_init(|| Mutex::new(Vec::new()));
    if before.modified.is_some() {
        if let Some(item) = cache
            .lock()
            .unwrap()
            .iter()
            .find(|item| item.path == path && item.stamp == before)
        {
            return Ok(item.hash.clone());
        }
    }
    const ENTRY: &[u8] = b"BOOT-INF/classes/com/harnex/remote/RemoteRuntimeBootstrap.class";
    let mut digest = Sha256::new();
    let mut block = [0_u8; 65_536];
    let mut tail = Vec::new();
    let mut supports_remote = false;
    loop {
        interaction::check_cancelled()?;
        let count = source.read(&mut block).map_err(err)?;
        if count == 0 {
            break;
        }
        digest.update(&block[..count]);
        if !supports_remote {
            tail.extend_from_slice(&block[..count]);
            supports_remote = tail.windows(ENTRY.len()).any(|bytes| bytes == ENTRY);
            if tail.len() >= ENTRY.len() {
                tail.drain(..tail.len() - ENTRY.len() + 1);
            }
        }
    }
    if !supports_remote {
        return Err("Runtime JAR predates the SSH bootstrap protocol; rebuild/update the Runtime Bundle before connecting".into());
    }
    if stamp(&source.metadata().map_err(err)?) != before
        || stamp(&fs::metadata(&path).map_err(err)?) != before
    {
        return Err("Runtime JAR changed while preparing the connection; wait for the build to finish and retry".into());
    }
    let hash = format!("{:x}", digest.finalize());
    let mut cache = cache.lock().unwrap();
    cache.retain(|item| item.path != path);
    if cache.len() >= 8 {
        cache.remove(0);
    }
    cache.push(CachedJar {
        path,
        stamp: before,
        hash: hash.clone(),
    });
    Ok(hash)
}

pub(super) fn prepare(profile: &Profile, jar: &Path) -> Result<(Profile, String), String> {
    interaction::progress("cache");
    let hash = fingerprint(jar)?;
    let target = format!(".harnex/runtime-cache/{hash}.jar");
    let mut command = profile.ssh()?;
    command
        .arg(&profile.host)
        .arg(pin_script(&target, &probe_script(profile, &target)));
    // Transport/authentication errors are not cache misses and must not trigger retries or uploads.
    let response = run(command, Stdio::null(), Duration::from_secs(30))?;
    if let Some(prepared) = reusable(profile, &hash, &response) {
        interaction::progress("reusing");
        return Ok((prepared, target));
    }
    let prepared = runtime_environment::prepare(profile)?;
    let remote_jar = prepare_jar(&prepared, jar)?;
    runtime_tools::prepare(&prepared, jar, &remote_jar)?;
    Ok((prepared, remote_jar))
}

/// Publish recent use under the same flock used by server-side collection, within the existing SSH request.
/// The lock is released before Java starts so detached Runtime children never inherit a cache lock.
pub(super) fn pin_script(target: &str, operation: &str) -> String {
    let hash = target
        .strip_prefix(".harnex/runtime-cache/")
        .and_then(|value| value.strip_suffix(".jar"));
    let Some(hash) = hash
        .filter(|value| value.len() == 64 && value.bytes().all(|byte| byte.is_ascii_hexdigit()))
    else {
        return operation.into();
    };
    format!(
        r#"(set -eu; cd "$HOME"; umask 077
test ! -L .harnex/runtime-cache; mkdir -p .harnex/runtime-cache
test ! -L .harnex/runtime-cache/.maintenance.lock
if command -v flock >/dev/null 2>&1; then exec 9>.harnex/runtime-cache/.maintenance.lock; flock -s -w 15 9; fi
test ! -L .harnex/runtime-cache/.usage; mkdir -p .harnex/runtime-cache/.usage
test ! -L .harnex/runtime-cache/.usage/{hash}; touch .harnex/runtime-cache/.usage/{hash}
) || exit 1
{operation}"#
    )
}

fn probe_script(profile: &Profile, target: &str) -> String {
    format!(
        r#"cd "$HOME" || exit 1
printf 'HARNEX_RUNTIME_CACHE_V1\n'; uname -s; uname -m
if command -v setsid >/dev/null && command -v sha256sum >/dev/null; then echo ready; else echo missing; fi
if test -f {jar}; then sha256sum {jar} 2>/dev/null || echo missing; else echo missing; fi
if test -x {rg}; then sha256sum {rg} 2>/dev/null || echo missing; else echo missing; fi
printf 'HARNEX_SYSTEM_JAVA\n'; {java} -version 2>&1 || true
printf '\nHARNEX_PRIVATE_JAVA\n'; .harnex/jre-cache/current/bin/java -version 2>&1 || true
"#,
        jar = quote(target),
        rg = quote(&format!("{}/rg", runtime_tools::bin_directory(target))),
        java = quote(&profile.java)
    )
}

fn reusable(profile: &Profile, hash: &str, response: &str) -> Option<Profile> {
    let body = response.split_once("HARNEX_RUNTIME_CACHE_V1\n")?.1;
    let (files, java) = body.split_once("HARNEX_SYSTEM_JAVA\n")?;
    let mut lines = files.lines();
    if lines.next()? != "Linux" {
        return None;
    }
    let expected_tool = runtime_tools::binary_hash(lines.next()?).ok()?;
    if lines.next()? != "ready"
        || lines.next()?.split_whitespace().next()? != hash
        || lines.next()?.split_whitespace().next()? != expected_tool
    {
        return None;
    }
    let (system, private) = java.split_once("HARNEX_PRIVATE_JAVA")?;
    if runtime_environment::java_major(system).is_some_and(|version| version >= 17) {
        return Some(profile.clone());
    }
    if profile.java == "java"
        && runtime_environment::java_major(private).is_some_and(|version| version >= 17)
    {
        let mut prepared = profile.clone();
        prepared.java = ".harnex/jre-cache/current/bin/java".into();
        return Some(prepared);
    }
    None
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn recent_use_pin_runs_in_the_existing_request_without_leaking_the_lock_descriptor() {
        let shell = if cfg!(windows) {
            PathBuf::from("C:/Program Files/Git/bin/bash.exe")
        } else {
            PathBuf::from("/bin/sh")
        };
        if !shell.is_file() {
            return;
        }
        let root = std::env::temp_dir().join(format!("harnex cache pin's {}", Uuid::new_v4()));
        fs::create_dir_all(&root).unwrap();
        let hash = "a".repeat(64);
        let target = format!(".harnex/runtime-cache/{hash}.jar");
        let script = pin_script(
            &target,
            "test ! -e /proc/$$/fd/9 && printf 'operation-ran\\n'",
        );
        let output = Command::new(shell)
            .args(["-c", &script])
            .current_dir(&root)
            .env("HOME", root.to_string_lossy().replace('\\', "/"))
            .output()
            .unwrap();
        assert!(
            output.status.success(),
            "{}",
            String::from_utf8_lossy(&output.stderr)
        );
        assert_eq!(String::from_utf8(output.stdout).unwrap(), "operation-ran\n");
        assert!(root
            .join(format!(".harnex/runtime-cache/.usage/{hash}"))
            .is_file());
        assert!(
            !root.join(target).exists(),
            "a missing JAR must never be replaced with an empty file"
        );
        let checked = root.canonicalize().unwrap();
        assert!(checked.starts_with(std::env::temp_dir().canonicalize().unwrap()));
        fs::remove_dir_all(checked).unwrap();
    }
    fn profile() -> Profile {
        serde_json::from_value(json!({"id":"fixture","name":"Fixture","host":"unused.invalid"}))
            .unwrap()
    }
    #[test]
    fn replacing_a_jar_invalidates_the_cached_hash_and_protocol_support() {
        let root = std::env::temp_dir().join(format!("harnex-jar-cache-{}", Uuid::new_v4()));
        fs::create_dir_all(&root).unwrap();
        let jar = root.join("runtime.jar");
        let mut bytes =
            b"BOOT-INF/classes/com/harnex/remote/RemoteRuntimeBootstrap.class-v1".to_vec();
        fs::write(&jar, &bytes).unwrap();
        let first = fingerprint(&jar).unwrap();
        assert_eq!(fingerprint(&jar).unwrap(), first);
        *bytes.last_mut().unwrap() = b'2';
        fs::write(&jar, &bytes).unwrap();
        File::options()
            .write(true)
            .open(&jar)
            .unwrap()
            .set_times(
                fs::FileTimes::new().set_modified(SystemTime::now() + Duration::from_secs(2)),
            )
            .unwrap();
        assert_ne!(fingerprint(&jar).unwrap(), first);
        fs::write(&jar, vec![b'x'; bytes.len()]).unwrap();
        File::options()
            .write(true)
            .open(&jar)
            .unwrap()
            .set_times(
                fs::FileTimes::new().set_modified(SystemTime::now() + Duration::from_secs(4)),
            )
            .unwrap();
        assert!(fingerprint(&jar).unwrap_err().contains("predates"));
        let checked = root.canonicalize().unwrap();
        assert!(checked.starts_with(std::env::temp_dir().canonicalize().unwrap()));
        fs::remove_dir_all(checked).unwrap();
    }
    #[test]
    fn one_probe_requires_matching_jar_tool_and_java_and_preserves_explicit_java_choice() {
        let jar = "a".repeat(64);
        let tool = runtime_tools::binary_hash("x86_64").unwrap();
        let response = format!("login banner\nHARNEX_RUNTIME_CACHE_V1\nLinux\nx86_64\nready\n{jar}  cache.jar\n{tool}  bin/rg\nHARNEX_SYSTEM_JAVA\nopenjdk version \"17.0.16\"\nHARNEX_PRIVATE_JAVA\nmissing\n");
        assert_eq!(reusable(&profile(), &jar, &response).unwrap().java, "java");
        for bad in [
            response.replace(&jar, "missing"),
            response.replace(&tool, "badhash"),
            response.replace("17.0.16", "1.8.0"),
            response.replace("Linux", "Darwin"),
        ] {
            assert!(reusable(&profile(), &jar, &bad).is_none());
        }
        let private = response.replace(
            "openjdk version \"17.0.16\"\nHARNEX_PRIVATE_JAVA\nmissing",
            "missing\nHARNEX_PRIVATE_JAVA\nopenjdk version \"21.0.1\"",
        );
        assert_eq!(
            reusable(&profile(), &jar, &private).unwrap().java,
            ".harnex/jre-cache/current/bin/java"
        );
        let mut explicit = profile();
        explicit.java = "/opt/custom java/bin/java".into();
        assert!(
            reusable(&explicit, &jar, &private).is_none(),
            "an invalid explicit Java must not silently fall back"
        );
    }
    #[test]
    fn probe_executes_against_real_files_with_quoted_paths_and_reports_corruption() {
        let shell = if cfg!(windows) {
            PathBuf::from("C:/Program Files/Git/bin/bash.exe")
        } else {
            PathBuf::from("/bin/sh")
        };
        if !shell.is_file() {
            eprintln!("shell probe test requires a POSIX shell");
            return;
        }
        let root = std::env::temp_dir().join(format!("harnex probe's files {}", Uuid::new_v4()));
        let target = ".harnex/runtime-cache/runtime's file.jar";
        let rg = format!("{}/rg", runtime_tools::bin_directory(target));
        fs::create_dir_all(root.join(runtime_tools::bin_directory(target))).unwrap();
        fs::create_dir_all(root.join("tools")).unwrap();
        fs::write(root.join(target), b"jar bytes").unwrap();
        fs::write(root.join(&rg), b"#!/bin/sh\necho tool\n").unwrap();
        fs::write(
            root.join("tools/java"),
            b"#!/bin/sh\necho 'openjdk version \"17.0.16\"' >&2\n",
        )
        .unwrap();
        let mut configured = profile();
        configured.java = "./tools/java".into();
        let script = format!(
            "chmod +x {} ./tools/java\n{}",
            quote(&rg),
            probe_script(&configured, target)
        );
        let probe = || {
            let out = Command::new(&shell)
                .args(["-c", &script])
                .current_dir(&root)
                .env("HOME", root.to_string_lossy().replace('\\', "/"))
                .output()
                .unwrap();
            assert!(
                out.status.success(),
                "{}",
                String::from_utf8_lossy(&out.stderr)
            );
            String::from_utf8(out.stdout).unwrap()
        };
        let first = probe();
        assert!(
            first.contains(&hex_hash(b"jar bytes"))
                && first.contains("openjdk version \"17.0.16\"")
        );
        fs::write(root.join(target), b"corrupted").unwrap();
        assert!(!probe().contains(&hex_hash(b"jar bytes")));
        fs::remove_file(root.join(&rg)).unwrap();
        assert!(probe().contains("missing"));
        let checked = root.canonicalize().unwrap();
        assert!(checked.starts_with(std::env::temp_dir().canonicalize().unwrap()));
        fs::remove_dir_all(checked).unwrap();
    }
}
