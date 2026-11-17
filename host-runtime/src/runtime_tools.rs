//! Runtime-owned search tools. No executable is selected from the remote project or system PATH.
use super::*;
use std::io::Write;

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct Artifact {
    url: String,
    archive_sha256: String,
    binary_sha256: String,
    entry: String,
}
#[derive(Deserialize)]
struct Manifest {
    version: String,
    targets: std::collections::HashMap<String, Artifact>,
}

pub fn bin_directory(jar: &str) -> String {
    format!("{}/bin", jar.strip_suffix(".jar").unwrap_or(jar))
}
pub(super) fn binary_hash(architecture: &str) -> Result<String, String> {
    let target = match architecture {
        "x86_64" | "amd64" => "linux-x64", "aarch64" | "arm64" => "linux-arm64",
        _ => return Err("unsupported remote architecture".into()),
    };
    let manifest: Manifest = serde_json::from_str(include_str!("../../bin/assets/remote-tools.json")).map_err(err)?;
    manifest.targets.get(target).map(|artifact| artifact.binary_sha256.clone()).ok_or("Runtime search tool manifest is incomplete".into())
}

pub fn prepare(profile: &Profile, jar: &Path, remote_jar: &str) -> Result<(), String> {
    interaction::progress("cache");
    let mut arch = profile.ssh()?;
    arch.arg(&profile.host)
        .arg("printf 'HARNEX_TOOLS_V1\\n'; uname -m");
    let output = run(arch, Stdio::null(), Duration::from_secs(20))?;
    let architecture = output
        .split_once("HARNEX_TOOLS_V1\n")
        .map(|(_, arch)| arch.trim())
        .unwrap_or("");
    let target = match architecture {
        "x86_64" | "amd64" => "linux-x64",
        "aarch64" | "arm64" => "linux-arm64",
        _ => {
            return Err(format!(
                "Runtime search tools are unavailable for Linux {architecture}"
            ))
        }
    };
    let manifest: Manifest =
        serde_json::from_str(include_str!("../../bin/assets/remote-tools.json")).map_err(err)?;
    let artifact = manifest
        .targets
        .get(target)
        .ok_or("Runtime search tool manifest is incomplete")?;
    let directory = bin_directory(remote_jar);
    let destination = format!("{directory}/rg");
    let mut probe = profile.ssh()?;
    probe.arg(&profile.host).arg(format!(
        "cd \"$HOME\" && test -x {file} && sha256sum {file}",
        file = quote(&destination)
    ));
    if let Ok(output) = run(probe, Stdio::null(), Duration::from_secs(20)) {
        if output.split_whitespace().next() == Some(artifact.binary_sha256.as_str()) {
            return Ok(());
        }
    }
    interaction::check_cancelled()?;
    let binary = local_binary(jar, target, &manifest.version, artifact)?;
    interaction::progress("upload");
    let mut upload = profile.ssh()?;
    upload.arg(&profile.host).arg(install_script(
        &directory,
        &destination,
        &artifact.binary_sha256,
    ));
    run(
        upload,
        Stdio::from(File::open(binary).map_err(err)?),
        Duration::from_secs(90),
    )?;
    Ok(())
}

fn install_script(directory: &str, destination: &str, hash: &str) -> String {
    let temporary = format!("{destination}.{}.tmp", Uuid::new_v4());
    format!(
        r#"set -eu; cd "$HOME"; umask 077; mkdir -p {directory}; t={temporary}; trap 'rm -f -- "$t"' EXIT HUP INT TERM; cat > "$t"; test "$(sha256sum "$t" | cut -d ' ' -f 1)" = {hash}; chmod 700 "$t"; "$t" --version >/dev/null; mv -f -- "$t" {destination}"#,
        directory = quote(directory),
        temporary = quote(&temporary),
        hash = quote(hash),
        destination = quote(destination)
    )
}

fn local_binary(
    jar: &Path,
    target: &str,
    version: &str,
    artifact: &Artifact,
) -> Result<PathBuf, String> {
    let mut roots = Vec::new();
    if let Some(bundle) = std::env::var_os("HARNEX_RUNTIME_BUNDLE") {
        roots.push(PathBuf::from(bundle));
    }
    if let Some(parent) = jar.parent() {
        roots.push(parent.to_path_buf());
    }
    if let Ok(executable) = std::env::current_exe() {
        if let Some(parent) = executable.parent().and_then(Path::parent) {
            roots.push(parent.to_path_buf());
        }
    }
    for root in roots {
        let binary = root.join("remote-tools").join(target).join("rg");
        if runtime_environment::verified(&binary, &artifact.binary_sha256)? {
            return Ok(binary);
        }
    }
    let cache = std::env::var_os("HARNEX_USER_DATA_DIR")
        .map(PathBuf::from)
        .unwrap_or_else(|| ssh::home_directory().join(".harnex"))
        .join("runtime-downloads");
    private_directory(&cache)?;
    let binary = cache.join(format!("rg-{version}-{target}"));
    if runtime_environment::verified(&binary, &artifact.binary_sha256)? {
        return Ok(binary);
    }
    interaction::progress("download");
    let client = Client::builder()
        .connect_timeout(Duration::from_secs(15))
        .timeout(Duration::from_secs(120))
        .build()
        .map_err(err)?;
    let mut response = client.get(&artifact.url).send().and_then(reqwest::blocking::Response::error_for_status)
        .map_err(|error| format!("Runtime search tools could not be downloaded: {error}. Check the connecting machine's network and retry."))?;
    let mut archive = Vec::new();
    let mut block = [0_u8; 65_536];
    loop {
        interaction::check_cancelled()?;
        let length = response.read(&mut block).map_err(err)?;
        if length == 0 {
            break;
        }
        archive.extend_from_slice(&block[..length]);
        if archive.len() > 32 * 1024 * 1024 {
            return Err("Runtime search tool download exceeded its limit".into());
        }
    }
    let bytes = extract(&archive, artifact)?;
    let temporary = cache.join(format!(".rg-{}", Uuid::new_v4()));
    let result = (|| {
        let mut file = OpenOptions::new()
            .write(true)
            .create_new(true)
            .open(&temporary)
            .map_err(err)?;
        file.write_all(&bytes).map_err(err)?;
        file.sync_all().map_err(err)?;
        drop(file);
        interaction::check_cancelled()?;
        if runtime_environment::verified(&binary, &artifact.binary_sha256)? {
            return Ok(());
        }
        if binary.exists() {
            fs::remove_file(&binary).map_err(err)?;
        }
        fs::rename(&temporary, &binary).map_err(err)
    })();
    let _ = fs::remove_file(&temporary);
    result?;
    Ok(binary)
}

fn extract(bytes: &[u8], artifact: &Artifact) -> Result<Vec<u8>, String> {
    if format!("{:x}", Sha256::digest(bytes)) != artifact.archive_sha256 {
        return Err("Runtime search tool archive checksum mismatch".into());
    }
    let decoder = flate2::read::GzDecoder::new(bytes);
    let mut archive = tar::Archive::new(decoder);
    for entry in archive.entries().map_err(err)? {
        interaction::check_cancelled()?;
        let entry = entry.map_err(err)?;
        if entry.path().map_err(err)?.as_ref() != Path::new(&artifact.entry) {
            continue;
        }
        if !entry.header().entry_type().is_file() || entry.size() > 16 * 1024 * 1024 {
            return Err("invalid Runtime search tool archive entry".into());
        }
        let mut binary = Vec::new();
        entry
            .take(16 * 1024 * 1024 + 1)
            .read_to_end(&mut binary)
            .map_err(err)?;
        if format!("{:x}", Sha256::digest(&binary)) != artifact.binary_sha256 {
            return Err("Runtime search tool binary checksum mismatch".into());
        }
        return Ok(binary);
    }
    Err("Runtime search tool is missing from its archive".into())
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn bundled_search_tool_works_offline_and_only_expected_archive_entry_is_read() {
        let base = std::env::temp_dir().join(format!("harnex-rg-{}", Uuid::new_v4()));
        let directory = base.join("remote-tools/linux-x64");
        fs::create_dir_all(&directory).unwrap();
        let data = b"fixture executable";
        fs::write(directory.join("rg"), data).unwrap();
        let mut tar = tar::Builder::new(Vec::new());
        let mut header = tar::Header::new_gnu();
        header.set_size(data.len() as u64);
        header.set_mode(0o755);
        header.set_cksum();
        tar.append_data(&mut header, "release/rg", &data[..])
            .unwrap();
        let bytes = tar.into_inner().unwrap();
        let mut gzip = flate2::write::GzEncoder::new(Vec::new(), flate2::Compression::default());
        gzip.write_all(&bytes).unwrap();
        let archive = gzip.finish().unwrap();
        let mut artifact = Artifact {
            url: "https://unreachable.invalid/rg".into(),
            entry: "release/rg".into(),
            archive_sha256: format!("{:x}", Sha256::digest(&archive)),
            binary_sha256: format!("{:x}", Sha256::digest(data)),
        };
        assert_eq!(
            local_binary(&base.join("harnex.jar"), "linux-x64", "fixture", &artifact).unwrap(),
            directory.join("rg")
        );
        assert_eq!(extract(&archive, &artifact).unwrap(), data);
        artifact.entry = "unexpected/rg".into();
        assert!(extract(&archive, &artifact)
            .unwrap_err()
            .contains("missing"));
        artifact.entry = "release/rg".into();
        artifact.binary_sha256 = "0".repeat(64);
        assert!(extract(&archive, &artifact)
            .unwrap_err()
            .contains("binary checksum mismatch"));
        fs::remove_dir_all(base).unwrap();
    }
    #[test]
    fn rejects_corrupt_archive_before_extraction() {
        let manifest: Manifest =
            serde_json::from_str(include_str!("../../bin/assets/remote-tools.json")).unwrap();
        for artifact in manifest.targets.values() {
            assert!(extract(b"corrupt", artifact)
                .unwrap_err()
                .contains("checksum mismatch"));
        }
    }
}
