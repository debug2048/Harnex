//! Packaged Runtime discovery, verification and warmup.

use crate::host::{env_or, shell_log, strip_verbatim};
use serde_json::Value;
use sha2::Digest;
use sha2::Sha256;
use std::collections::BTreeMap;
use std::io::Read;
use std::path::Component;
use std::path::Path;
use std::path::PathBuf;
use std::sync::OnceLock;
use std::time::Instant;
use tauri::Manager;

#[derive(Clone)]
pub(crate) struct BundledRuntime {
    pub(crate) java: PathBuf,
    pub(crate) jar: PathBuf,
    pub(crate) jar_sha256: String,
}

/// Roots that may contain the bundled `resources/` dir. We check `resource_dir()` first
/// (correct for installed/NSIS builds) and then the executable's own directory (correct for
/// the raw `target/release` exe, and a robust fallback when `resource_dir()` resolves wrong).
pub(crate) fn resource_roots(app: &tauri::AppHandle) -> Vec<PathBuf> {
    let mut roots: Vec<PathBuf> = Vec::new();
    if let Ok(res) = app.path().resource_dir() {
        roots.push(strip_verbatim(res));
    }
    if let Ok(exe) = std::env::current_exe() {
        if let Some(parent) = exe.parent() {
            let p = strip_verbatim(parent.to_path_buf());
            if !roots.contains(&p) {
                roots.push(p);
            }
        }
    }
    roots
}

/// Resolve and verify the same immutable Runtime Bundle consumed by the VS Code companion.
/// A partial, modified or wrong-platform resource set fails closed; jar and JRE are always
/// selected from one verified directory instead of being discovered independently.
pub(crate) fn bundled_runtime(
    app: &tauri::AppHandle,
) -> Result<Option<BundledRuntime>, String> {
    static RESOLVED: OnceLock<Result<Option<BundledRuntime>, String>> = OnceLock::new();
    RESOLVED
        .get_or_init(|| {
            for root in resource_roots(app) {
                let directory = root.join("resources");
                if directory.join("runtime-bundle.json").exists() {
                    return verify_runtime_bundle(&directory).map(Some);
                }
            }
            Ok(None)
        })
        .clone()
}

fn verify_runtime_bundle(directory: &Path) -> Result<BundledRuntime, String> {
    let manifest_file = directory.join("runtime-bundle.json");
    let bytes = std::fs::read(&manifest_file)
        .map_err(|error| format!("cannot read {}: {error}", manifest_file.display()))?;
    let manifest: Value = serde_json::from_slice(&bytes)
        .map_err(|error| format!("invalid Runtime Bundle manifest: {error}"))?;
    if manifest.get("schemaVersion").and_then(Value::as_u64) != Some(1)
        || manifest.get("protocolVersion").and_then(Value::as_str) != Some("1.0")
    {
        return Err("unsupported Runtime Bundle schema/protocol".to_string());
    }
    if manifest.get("target").and_then(Value::as_str) != Some(runtime_target()) {
        return Err(format!(
            "Runtime Bundle target {:?} does not match {}",
            manifest.get("target"),
            runtime_target()
        ));
    }
    if manifest.get("runtimeVersion").and_then(Value::as_str) != Some(env!("CARGO_PKG_VERSION")) {
        return Err(format!(
            "Runtime Bundle version {:?} does not match desktop {}",
            manifest.get("runtimeVersion"),
            env!("CARGO_PKG_VERSION")
        ));
    }

    let declared = manifest
        .get("files")
        .and_then(Value::as_object)
        .filter(|files| !files.is_empty())
        .ok_or_else(|| "Runtime Bundle file manifest is empty".to_string())?;
    let mut verified = BTreeMap::<String, String>::new();
    for (name, expected) in declared {
        let relative = Path::new(name);
        if relative.is_absolute()
            || relative
                .components()
                .any(|component| !matches!(component, Component::Normal(_)))
        {
            return Err(format!("unsafe Runtime Bundle path: {name}"));
        }
        let expected = expected
            .as_str()
            .ok_or_else(|| format!("invalid hash for Runtime Bundle path: {name}"))?;
        let file = directory.join(relative);
        if std::fs::symlink_metadata(&file)
            .map_err(|error| error.to_string())?
            .file_type()
            .is_symlink()
        {
            return Err(format!(
                "Runtime Bundle must not contain symbolic links: {name}"
            ));
        }
        let content = std::fs::read(&file).map_err(|error| {
            format!(
                "cannot read Runtime Bundle file {}: {error}",
                file.display()
            )
        })?;
        let actual = format!("{:x}", Sha256::digest(content));
        if actual != expected {
            return Err(format!("Runtime Bundle hash mismatch: {name}"));
        }
        verified.insert(name.clone(), actual);
    }
    let actual_files = runtime_bundle_file_names(directory)?;
    let declared_files: Vec<String> = verified.keys().cloned().collect();
    if actual_files != declared_files {
        let extra = actual_files
            .iter()
            .filter(|name| !verified.contains_key(name.as_str()))
            .cloned()
            .collect::<Vec<_>>();
        let missing = declared_files
            .iter()
            .filter(|name| !actual_files.contains(name))
            .cloned()
            .collect::<Vec<_>>();
        return Err(format!(
            "Runtime Bundle contains undeclared or missing files (extra: {}; missing: {})",
            if extra.is_empty() {
                "none".to_string()
            } else {
                extra.join(", ")
            },
            if missing.is_empty() {
                "none".to_string()
            } else {
                missing.join(", ")
            }
        ));
    }
    let encoded = serde_json::to_vec(&verified)
        .map_err(|error| format!("cannot encode Runtime Bundle digest input: {error}"))?;
    let digest = format!("{:x}", Sha256::digest(encoded));
    if manifest.get("bundleDigest").and_then(Value::as_str) != Some(digest.as_str()) {
        return Err("Runtime Bundle digest mismatch".to_string());
    }

    let java_relative = manifest
        .get("javaExecutable")
        .and_then(Value::as_str)
        .ok_or_else(|| "Runtime Bundle javaExecutable is missing".to_string())?;
    let jar_relative = manifest
        .get("entrypoint")
        .and_then(Value::as_str)
        .ok_or_else(|| "Runtime Bundle entrypoint is missing".to_string())?;
    for name in [java_relative, jar_relative] {
        let candidate = Path::new(name);
        if candidate.is_absolute()
            || candidate
                .components()
                .any(|component| !matches!(component, Component::Normal(_)))
        {
            return Err(format!("unsafe Runtime Bundle entrypoint: {name}"));
        }
    }
    let java = directory.join(java_relative);
    let jar = directory.join(jar_relative);
    if !java.is_file() || !jar.is_file() {
        return Err("Runtime Bundle entrypoints are missing".to_string());
    }
    let jar_sha256 = verified.get(jar_relative)
        .ok_or("Runtime Bundle JAR must be part of the verified file set")?.clone();
    Ok(BundledRuntime { java, jar, jar_sha256 })
}

fn runtime_bundle_file_names(directory: &Path) -> Result<Vec<String>, String> {
    fn visit(
        root: &Path,
        current: &Path,
        files: &mut Vec<String>,
        collect_runtime_files: bool,
    ) -> Result<(), String> {
        if !current.exists() {
            return Ok(());
        }
        for entry in std::fs::read_dir(current).map_err(|error| error.to_string())? {
            let entry = entry.map_err(|error| error.to_string())?;
            let path = entry.path();
            let file_type = entry.file_type().map_err(|error| error.to_string())?;
            if file_type.is_symlink() {
                return Err(format!(
                    "Runtime Bundle must not contain symbolic links: {}",
                    path.display()
                ));
            }
            if file_type.is_dir() {
                // `resources/ui` is the separately built SPA served only for
                // attach/browser diagnostics. The Runtime Bundle builder
                // deliberately preserves it while restaging the signed JVM
                // payload, so it is outside runtime-bundle.json's file set.
                // Still walk it to reject symlinks and unsupported entries.
                let collect_child =
                    collect_runtime_files && !(current == root && entry.file_name() == "ui");
                visit(root, &path, files, collect_child)?;
            } else if file_type.is_file() {
                if collect_runtime_files {
                    let name = path
                        .strip_prefix(root)
                        .map_err(|error| error.to_string())?
                        .to_string_lossy()
                        .replace('\\', "/");
                    if name != "runtime-bundle.json" && name != "SHA256SUMS" {
                        files.push(name);
                    }
                }
            } else {
                return Err(format!(
                    "unsupported Runtime Bundle entry: {}",
                    path.display()
                ));
            }
        }
        Ok(())
    }
    let mut files = Vec::new();
    visit(directory, directory, &mut files, true)?;
    files.sort();
    Ok(files)
}

fn runtime_target() -> &'static str {
    if cfg!(all(target_os = "windows", target_arch = "x86_64")) {
        "win32-x64"
    } else if cfg!(all(target_os = "windows", target_arch = "aarch64")) {
        "win32-arm64"
    } else if cfg!(all(target_os = "linux", target_arch = "x86_64")) {
        "linux-x64"
    } else if cfg!(all(target_os = "linux", target_arch = "aarch64")) {
        "linux-arm64"
    } else if cfg!(all(target_os = "macos", target_arch = "x86_64")) {
        "darwin-x64"
    } else if cfg!(all(target_os = "macos", target_arch = "aarch64")) {
        "darwin-arm64"
    } else {
        "unsupported"
    }
}

/// Sequentially touch the fat JAR before every spawn. This mirrors the Web Supervisor's
/// cold-start optimization: Windows/AV otherwise faults the nested archive in through many
/// small Spring Boot reads. Failure remains advisory; the actual JVM spawn is authoritative.
pub(crate) fn prefetch_runtime_artifact(jar: &Path, log: &Path) {
    if env_or("HARNEX_SUPERVISOR_PREFETCH_JAR").as_deref() == Some("0") {
        shell_log(log, "[prefetch] disabled=true");
        return;
    }
    let started = Instant::now();
    let result = (|| -> std::io::Result<u64> {
        let mut file = std::fs::File::open(jar)?;
        let mut buffer = vec![0_u8; 8 * 1024 * 1024];
        let mut bytes = 0_u64;
        loop {
            let read = file.read(&mut buffer)?;
            if read == 0 {
                break;
            }
            bytes += read as u64;
        }
        Ok(bytes)
    })();
    match result {
        Ok(bytes) => shell_log(
            log,
            &format!(
                "[prefetch] bytes={bytes} durationMs={}",
                started.elapsed().as_millis()
            ),
        ),
        Err(error) => shell_log(
            log,
            &format!(
                "[prefetch] error={error} durationMs={}",
                started.elapsed().as_millis()
            ),
        ),
    }
}

/// Bundled `<root>/resources/ui` (the built SPA), searching every candidate root. Only
/// returned when it actually contains an `index.html`, so the backend is told to serve a
/// real front-end and never points at an empty dir.
pub(crate) fn bundled_ui(app: &tauri::AppHandle) -> Option<PathBuf> {
    resource_roots(app)
        .into_iter()
        .map(|r| r.join("resources").join("ui"))
        .find(|p| p.join("index.html").exists())
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::time::{SystemTime, UNIX_EPOCH};

    #[test]
    fn shared_jar_digest_requires_verified_bytes_and_a_verified_entrypoint() {
        let directory = tempfile::tempdir().unwrap();
        let root = directory.path();
        std::fs::create_dir_all(root.join("jre/bin")).unwrap();
        std::fs::write(root.join("harnex.jar"), "original").unwrap();
        std::fs::write(root.join("jre/bin/java"), "java").unwrap();
        let files = BTreeMap::from([
            ("harnex.jar", format!("{:x}", Sha256::digest(b"original"))),
            ("jre/bin/java", format!("{:x}", Sha256::digest(b"java"))),
        ]);
        let mut manifest = serde_json::json!({
            "schemaVersion":1, "protocolVersion":"1.0", "runtimeVersion":env!("CARGO_PKG_VERSION"),
            "target":runtime_target(), "javaExecutable":"jre/bin/java", "entrypoint":"harnex.jar",
            "files":files, "bundleDigest":format!("{:x}", Sha256::digest(serde_json::to_vec(&files).unwrap()))
        });
        let write_manifest = |value: &Value| std::fs::write(root.join("runtime-bundle.json"), serde_json::to_vec(value).unwrap()).unwrap();
        write_manifest(&manifest);
        assert_eq!(verify_runtime_bundle(root).unwrap().jar_sha256, files["harnex.jar"]);
        std::fs::write(root.join("harnex.jar"), "modified").unwrap();
        assert!(matches!(verify_runtime_bundle(root), Err(error) if error.contains("hash mismatch")));
        std::fs::write(root.join("harnex.jar"), "original").unwrap();
        std::fs::create_dir_all(root.join("ui")).unwrap();
        std::fs::write(root.join("ui/unchecked.jar"), "unchecked").unwrap();
        manifest["entrypoint"] = serde_json::json!("ui/unchecked.jar");
        write_manifest(&manifest);
        assert!(matches!(verify_runtime_bundle(root), Err(error) if error.contains("verified file set")));
    }

    #[test]
    fn runtime_bundle_file_set_ignores_ui_but_includes_undeclared_top_level_files() {
        let nonce = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap()
            .as_nanos();
        let root = std::env::temp_dir().join(format!(
            "harnex-runtime-files-{}-{nonce}",
            std::process::id()
        ));
        std::fs::create_dir_all(root.join("jre/bin")).unwrap();
        std::fs::write(root.join("harnex.jar"), "jar").unwrap();
        std::fs::write(root.join("jre/bin/java"), "java").unwrap();
        std::fs::write(root.join("undeclared.dll"), "unexpected").unwrap();
        std::fs::create_dir_all(root.join("ui/assets")).unwrap();
        std::fs::write(root.join("ui/index.html"), "ui").unwrap();
        std::fs::write(root.join("ui/assets/app.js"), "js").unwrap();
        std::fs::write(root.join("runtime-bundle.json"), "{}").unwrap();
        std::fs::write(root.join("SHA256SUMS"), "").unwrap();

        let files = runtime_bundle_file_names(&root).unwrap();
        assert_eq!(files, ["harnex.jar", "jre/bin/java", "undeclared.dll"]);

        std::fs::remove_dir_all(root).unwrap();
    }
}
