//! A private Linux JRE is prepared only when opening a remote project.
use super::*;
use std::io::Write;

pub fn prepare(profile: &Profile) -> Result<Profile, String> {
    interaction::progress("environment");
    let mut command = profile.ssh()?;
    command.arg(&profile.host).arg(format!(r#"set -eu; cd "$HOME"; printf 'HARNEX_JAVA_V1\n'; uname -s; uname -m; command -v setsid >/dev/null || {{ echo 'setsid is required (install util-linux)' >&2; exit 1; }}; command -v sha256sum >/dev/null || {{ echo 'sha256sum is required' >&2; exit 1; }}; {} -version 2>&1 || true; printf '\nHARNEX_PRIVATE_JAVA\n'; .harnex/jre-cache/current/bin/java -version 2>&1 || true"#, quote(&profile.java)));
    let output = run(command, Stdio::null(), Duration::from_secs(30))?;
    let output = output
        .split_once("HARNEX_JAVA_V1\n")
        .ok_or("remote environment response missing")?
        .1;
    let mut lines = output.lines();
    if lines.next() != Some("Linux") {
        return Err(
            "remote projects currently require Linux; SSH browsing is still available".into(),
        );
    }
    let arch = lines.next().ok_or("missing remote architecture")?;
    let (_, versions) = output
        .split_once('\n')
        .and_then(|(_, rest)| rest.split_once('\n'))
        .ok_or("missing Java version")?;
    let (system, private) = versions
        .split_once("HARNEX_PRIVATE_JAVA")
        .unwrap_or((versions, ""));
    if java_major(system).is_some_and(|major| major >= 17) {
        return Ok(profile.clone());
    }
    if profile.java != "java" {
        return Err("the configured remote Java must be version 17 or newer".into());
    }
    let mut prepared = profile.clone();
    prepared.java = ".harnex/jre-cache/current/bin/java".into();
    if java_major(private).is_some_and(|major| major >= 17) {
        return Ok(prepared);
    }
    let architecture = match arch { "x86_64" | "amd64" => "x64", "aarch64" | "arm64" => "aarch64",
        _ => return Err(format!("automatic Java setup is unavailable for Linux {arch}; install Java 17 or newer and retry")) };
    // musl distributions require a different JRE. Detect them before choosing a vendor artifact.
    let mut libc = profile.ssh()?;
    libc.arg(&profile.host).arg("if ls /lib/ld-musl-*.so.1 >/dev/null 2>&1; then printf alpine-linux; else printf linux; fi");
    let os = run(libc, Stdio::null(), Duration::from_secs(20))?;
    let os = if os.trim_end().ends_with("alpine-linux") {
        "alpine-linux"
    } else {
        "linux"
    };
    interaction::progress("download");
    let (archive, hash) = download(architecture, os)?;
    interaction::progress("upload");
    let target = format!(".harnex/jre-cache/{hash}");
    let staging = format!(".harnex/jre-cache/.install-{}", Uuid::new_v4());
    let mut upload = profile.ssh()?;
    upload.arg(&profile.host).arg(format!(r#"set -eu; cd "$HOME"; umask 077; mkdir -p .harnex/jre-cache; d={staging}; mkdir "$d"; trap 'rm -rf -- "$d"' EXIT HUP INT TERM; cat > "$d/archive.tar.gz"; test "$(sha256sum "$d/archive.tar.gz" | cut -d ' ' -f 1)" = {hash}; mkdir "$d/jre"; tar -xzf "$d/archive.tar.gz" --strip-components=1 -C "$d/jre"; "$d/jre/bin/java" -version >/dev/null 2>&1; if [ ! -d {target} ]; then mv "$d/jre" {target}; fi; ln -s {hash} "$d/current"; mv -Tf "$d/current" .harnex/jre-cache/current"#,
        staging=quote(&staging), hash=quote(&hash), target=quote(&target)));
    run(
        upload,
        Stdio::from(File::open(archive).map_err(err)?),
        Duration::from_secs(180),
    )
    .map_err(|error| format!("could not prepare the private remote Java runtime: {error}"))?;
    Ok(prepared)
}

pub(super) fn java_major(output: &str) -> Option<u32> {
    output.lines().find_map(|line| {
        let lower = line.to_ascii_lowercase();
        if !lower.starts_with("openjdk version ")
            && !lower.starts_with("java version ")
            && !lower.starts_with("openjdk ")
        {
            return None;
        }
        let version = line
            .split('"')
            .nth(1)
            .or_else(|| line.split_whitespace().nth(1))?;
        let mut parts = version.split(['.', '-', '+']);
        let first = parts.next()?.parse::<u32>().ok()?;
        if first == 1 {
            parts.next()?.parse().ok()
        } else {
            Some(first)
        }
    })
}

fn download(architecture: &str, os: &str) -> Result<(PathBuf, String), String> {
    // This cache belongs to the connecting machine. It never refers to the remote project root.
    let cache = std::env::var_os("HARNEX_USER_DATA_DIR")
        .map(PathBuf::from)
        .unwrap_or_else(|| ssh::home_directory().join(".harnex"))
        .join("runtime-downloads");
    private_directory(&cache)?;
    let manifest_path = cache.join(format!("temurin-17-{os}-{architecture}.json"));
    if let Ok(bytes) = fs::read(&manifest_path) {
        if let Ok(manifest) = serde_json::from_slice::<Value>(&bytes) {
            if let Some(hash) = manifest["sha256"].as_str().filter(|hash| valid_hash(hash)) {
                let path = cache.join(format!("{hash}.tar.gz"));
                if verified(&path, hash)? {
                    return Ok((path, hash.to_string()));
                }
            }
        }
    }
    let client = Client::builder()
        .connect_timeout(Duration::from_secs(10))
        .timeout(Duration::from_secs(180))
        .https_only(true)
        .user_agent("Harnex-remote-runtime")
        .build()
        .map_err(err)?;
    interaction::check_cancelled()?;
    let metadata = client.get(format!("https://api.adoptium.net/v3/assets/latest/17/hotspot?architecture={architecture}&image_type=jre&os={os}&vendor=eclipse"))
        .timeout(Duration::from_secs(25)).send().map_err(download_error)?.error_for_status().map_err(download_error)?;
    let metadata: Value = serde_json::from_reader(metadata.take(2_000_000))
        .map_err(|error| format!("Java release metadata was invalid: {error}"))?;
    let package = &metadata[0]["binary"]["package"];
    let hash = package["checksum"]
        .as_str()
        .filter(|hash| valid_hash(hash))
        .ok_or("Java download checksum is unavailable")?;
    let url = package["link"]
        .as_str()
        .filter(|url| {
            url.starts_with("https://github.com/adoptium/temurin17-binaries/releases/download/")
        })
        .ok_or("unrecognized Java runtime download source")?;
    let path = cache.join(format!("{hash}.tar.gz"));
    if !verified(&path, hash)? {
        interaction::check_cancelled()?;
        let temporary = cache.join(format!(".download-{}", Uuid::new_v4()));
        let result = (|| {
            let mut source = client
                .get(url)
                .send()
                .map_err(download_error)?
                .error_for_status()
                .map_err(download_error)?;
            let mut file = OpenOptions::new()
                .write(true)
                .create_new(true)
                .open(&temporary)
                .map_err(err)?;
            let mut digest = Sha256::new();
            let mut count = 0_usize;
            let mut block = [0_u8; 65_536];
            loop {
                interaction::check_cancelled()?;
                let length = source.read(&mut block).map_err(err)?;
                if length == 0 {
                    break;
                }
                count += length;
                if count > 128 * 1024 * 1024 {
                    return Err("Java archive exceeded its download limit".into());
                }
                digest.update(&block[..length]);
                file.write_all(&block[..length]).map_err(err)?;
            }
            if format!("{:x}", digest.finalize()) != hash {
                return Err("Java download checksum mismatch; retry the download".into());
            }
            file.sync_all().map_err(err)?;
            drop(file);
            interaction::check_cancelled()?;
            if verified(&path, hash)? {
                fs::remove_file(&temporary).map_err(err)?;
                return Ok(());
            }
            if path.exists() {
                fs::remove_file(&path).map_err(err)?;
            }
            fs::rename(&temporary, &path).map_err(err)
        })();
        if result.is_err() {
            let _ = fs::remove_file(temporary);
        }
        result?;
    }
    write_private(&manifest_path, &json!({"sha256":hash,"source":url}))?;
    Ok((path, hash.to_string()))
}
fn download_error(error: impl std::fmt::Display) -> String {
    format!("Java could not be downloaded: {error}. Check the connecting machine's network/proxy, or install Java 17+ on the remote host and retry.")
}
fn valid_hash(hash: &str) -> bool {
    hash.len() == 64 && hash.bytes().all(|byte| byte.is_ascii_hexdigit())
}
pub(super) fn verified(path: &Path, expected: &str) -> Result<bool, String> {
    if !path.is_file() {
        return Ok(false);
    }
    let mut file = File::open(path).map_err(err)?;
    let mut digest = Sha256::new();
    let mut block = [0_u8; 65_536];
    loop {
        interaction::check_cancelled()?;
        let length = file.read(&mut block).map_err(err)?;
        if length == 0 {
            break;
        }
        digest.update(&block[..length]);
    }
    Ok(format!("{:x}", digest.finalize()) == expected)
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn accepts_supported_java_versions_and_rejects_missing_or_obsolete_java() {
        assert_eq!(
            java_major("openjdk version \"17.0.16\" 2025-07-15\nOpenJDK Runtime"),
            Some(17)
        );
        assert_eq!(java_major("java version \"1.8.0_392\""), Some(8));
        assert_eq!(java_major("openjdk 21.0.1 2023-10-17"), Some(21));
        assert_eq!(java_major("sh: java: not found"), None);
    }
}
