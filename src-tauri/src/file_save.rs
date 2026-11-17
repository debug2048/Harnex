//! Writes only to destinations explicitly selected in the native Save As dialog.
use std::{collections::BTreeMap, io::Write, path::PathBuf, sync::Mutex};
use tauri::{AppHandle, Manager};
use tauri_plugin_dialog::DialogExt;

#[derive(Default)]
struct Pending {
    sequence: u64,
    paths: BTreeMap<String, PathBuf>,
}

#[derive(Default)]
pub struct SaveTargets(Mutex<Pending>);

impl SaveTargets {
    fn select(&self, path: PathBuf) -> String {
        let mut pending = self.0.lock().unwrap();
        pending.sequence += 1;
        let id = pending.sequence.to_string();
        pending.paths.insert(id.clone(), path);
        id
    }

    fn cancel(&self, id: &str) {
        self.0.lock().unwrap().paths.remove(id);
    }

    fn write(&self, id: &str, bytes: &[u8]) -> Result<(), String> {
        let path = self
            .0
            .lock()
            .unwrap()
            .paths
            .get(id)
            .cloned()
            .ok_or("save was cancelled")?;
        let parent = path.parent().ok_or("save directory is unavailable")?;
        let mut temporary =
            tempfile::NamedTempFile::new_in(parent).map_err(|error| error.to_string())?;
        temporary
            .write_all(bytes)
            .map_err(|error| error.to_string())?;
        temporary
            .as_file()
            .sync_all()
            .map_err(|error| error.to_string())?;
        // Cancellation wins until the atomic replacement. Failed/cancelled writes
        // leave an existing destination intact, including on Windows.
        let mut pending = self.0.lock().unwrap();
        if pending.paths.remove(id).is_none() {
            return Err("save was cancelled".into());
        }
        temporary
            .persist(path)
            .map_err(|error| error.error.to_string())?;
        Ok(())
    }
}

#[tauri::command]
pub async fn file_save_pick(
    app: AppHandle,
    suggested_name: String,
) -> Result<Option<String>, String> {
    tauri::async_runtime::spawn_blocking(move || {
        let name = suggested_name
            .rsplit(['/', '\\'])
            .next()
            .unwrap_or("download");
        let name: String = name
            .chars()
            .map(|ch| {
                if ch.is_control() || "<>:\"|?*".contains(ch) {
                    '_'
                } else {
                    ch
                }
            })
            .collect();
        let mut dialog =
            app.dialog()
                .file()
                .set_file_name(if name.is_empty() { "download" } else { &name });
        if let Some(window) = app.get_webview_window("main") {
            dialog = dialog.set_parent(&window);
        }
        let Some(selected) = dialog.blocking_save_file() else {
            return Ok(None);
        };
        let path = selected.into_path().map_err(|error| error.to_string())?;
        Ok(Some(app.state::<SaveTargets>().select(path)))
    })
    .await
    .map_err(|error| error.to_string())?
}

#[tauri::command]
pub fn file_save_cancel(app: AppHandle, id: String) {
    app.state::<SaveTargets>().cancel(&id);
}

#[tauri::command]
pub async fn file_save_write(
    app: AppHandle,
    request: tauri::ipc::Request<'_>,
) -> Result<(), String> {
    let id = request
        .headers()
        .get("x-harnex-save-id")
        .and_then(|value| value.to_str().ok())
        .ok_or("save destination is required")?
        .to_string();
    let tauri::ipc::InvokeBody::Raw(bytes) = request.body() else {
        return Err("binary download data is required".into());
    };
    let bytes = bytes.clone();
    tauri::async_runtime::spawn_blocking(move || app.state::<SaveTargets>().write(&id, &bytes))
        .await
        .map_err(|error| error.to_string())?
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn saves_only_to_a_selected_destination_and_consumes_the_choice() {
        let directory = tempfile::tempdir().unwrap();
        let path = directory.path().join("下载 file.bin");
        std::fs::write(&path, b"existing").unwrap();
        let targets = SaveTargets::default();
        let id = targets.select(path.clone());
        targets.write(&id, &[0, 255, 10, 42]).unwrap();
        assert_eq!(std::fs::read(&path).unwrap(), [0, 255, 10, 42]);
        assert!(targets.write(&id, b"second write").is_err());
    }
    #[test]
    fn cancellation_preserves_existing_files_and_rejects_unknown_destinations() {
        let directory = tempfile::tempdir().unwrap();
        let path = directory.path().join("existing.txt");
        std::fs::write(&path, b"keep").unwrap();
        let targets = SaveTargets::default();
        let id = targets.select(path.clone());
        targets.cancel(&id);
        assert!(targets.write(&id, b"discard").is_err());
        assert!(targets.write("unknown", b"discard").is_err());
        assert_eq!(std::fs::read(path).unwrap(), b"keep");
    }
}
