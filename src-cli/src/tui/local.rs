//! Local UI preferences and explicitly requested desktop actions.
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::fs::{self, OpenOptions};
use std::io::{self, Write};
use std::path::{Path, PathBuf};
use std::process::Command;

pub trait Clipboard: Send + Sync {
    fn copy(&self, text: &str) -> io::Result<()>;
    fn paste(&self) -> io::Result<String>;
}

pub struct SystemClipboard;
impl Clipboard for SystemClipboard {
    fn copy(&self, text: &str) -> io::Result<()> {
        copy(text)
    }
    fn paste(&self) -> io::Result<String> {
        paste()
    }
}

#[cfg(windows)]
fn paste() -> io::Result<String> {
    use windows_sys::Win32::System::{
        DataExchange::{CloseClipboard, GetClipboardData, OpenClipboard},
        Memory::{GlobalLock, GlobalSize, GlobalUnlock},
    };
    unsafe {
        let mut opened = false;
        for _ in 0..10 {
            if OpenClipboard(std::ptr::null_mut()) != 0 {
                opened = true;
                break;
            }
            std::thread::sleep(std::time::Duration::from_millis(20));
        }
        if !opened {
            return Err(io::Error::last_os_error());
        }
        let result = (|| {
            let memory = GetClipboardData(13);
            if memory.is_null() {
                return Err(io::Error::other("Clipboard has no text"));
            }
            let size = GlobalSize(memory) / 2;
            let buffer = GlobalLock(memory) as *const u16;
            if buffer.is_null() {
                return Err(io::Error::last_os_error());
            }
            let units = std::slice::from_raw_parts(buffer, size);
            let length = units.iter().position(|unit| *unit == 0).unwrap_or(size);
            let text = String::from_utf16_lossy(&units[..length]);
            GlobalUnlock(memory);
            Ok(text)
        })();
        CloseClipboard();
        result
    }
}

#[cfg(not(windows))]
fn paste() -> io::Result<String> {
    let choices: &[(&str, &[&str])] = if cfg!(target_os = "macos") {
        &[("pbpaste", &[])]
    } else {
        &[
            ("wl-paste", &["--no-newline"]),
            ("xclip", &["-selection", "clipboard", "-o"]),
            ("xsel", &["--clipboard", "--output"]),
        ]
    };
    let mut last = io::Error::other("No clipboard helper found");
    for (program, arguments) in choices {
        match Command::new(program).args(*arguments).output() {
            Ok(output) if output.status.success() => {
                return String::from_utf8(output.stdout).map_err(io::Error::other)
            }
            Ok(_) => last = io::Error::other("Clipboard helper failed"),
            Err(error) => last = error,
        }
    }
    Err(last)
}

#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(default, rename_all = "camelCase")]
pub struct Memory {
    pub draft: String,
    pub history: Vec<String>,
    pub recent_models: Vec<String>,
    pub favorite_models: Vec<String>,
    pub model_selection: Option<ModelSelection>,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct ModelSelection {
    pub provider: Option<String>,
    pub model: Option<String>,
    pub effort: String,
}

pub fn user_directory() -> PathBuf {
    crate::non_blank_env("HARNEX_USER_DATA_DIR")
        .map(PathBuf::from)
        .unwrap_or_else(|| crate::home_dir().join(".harnex"))
}

pub fn memory_path(profile: &str, scope: &str, remote_host: &str) -> PathBuf {
    let key = format!("{profile}\0{scope}\0{remote_host}");
    let digest = format!("{:x}", Sha256::digest(key.as_bytes()));
    user_directory()
        .join("tui")
        .join("workspaces")
        .join(format!("{digest}.json"))
}

pub fn load(path: &Path) -> io::Result<Memory> {
    match fs::read(path) {
        Ok(bytes) => serde_json::from_slice(&bytes).map_err(io::Error::other),
        Err(error) if error.kind() == io::ErrorKind::NotFound => Ok(Memory::default()),
        Err(error) => Err(error),
    }
}

fn private_file(path: &Path) -> io::Result<std::fs::File> {
    let mut options = OpenOptions::new();
    options.write(true).create_new(true);
    #[cfg(unix)]
    {
        use std::os::unix::fs::OpenOptionsExt;
        options.mode(0o600);
    }
    options.open(path)
}

pub fn save(path: &Path, memory: &Memory) -> io::Result<()> {
    let parent = path
        .parent()
        .ok_or_else(|| io::Error::other("Missing settings directory"))?;
    fs::create_dir_all(parent)?;
    let temporary = parent.join(format!(".{}.tmp", uuid::Uuid::new_v4()));
    let result = (|| {
        let mut file = private_file(&temporary)?;
        serde_json::to_writer(&mut file, memory).map_err(io::Error::other)?;
        file.flush()?;
        drop(file);
        fs::rename(&temporary, path)
    })();
    if result.is_err() {
        let _ = fs::remove_file(&temporary);
    }
    result
}

pub fn export(text: &str) -> io::Result<PathBuf> {
    let directory = user_directory().join("exports");
    fs::create_dir_all(&directory)?;
    let path = directory.join(format!(
        "conversation-{}-{}.md",
        chrono::Local::now().format("%Y%m%d-%H%M%S"),
        uuid::Uuid::new_v4().simple()
    ));
    private_file(&path)?.write_all(text.as_bytes())?;
    Ok(path)
}

#[cfg(windows)]
pub fn copy(text: &str) -> io::Result<()> {
    use std::ptr::null_mut;
    use windows_sys::Win32::{
        Foundation::GlobalFree,
        System::{
            DataExchange::{CloseClipboard, EmptyClipboard, OpenClipboard, SetClipboardData},
            Memory::{GlobalAlloc, GlobalLock, GlobalUnlock, GMEM_MOVEABLE},
        },
        UI::WindowsAndMessaging::{CreateWindowExW, DestroyWindow},
    };
    unsafe {
        let text = text.replace("\r\n", "\n").replace('\n', "\r\n");
        let units = text.encode_utf16().chain(Some(0)).collect::<Vec<_>>();
        let memory = GlobalAlloc(GMEM_MOVEABLE, units.len() * 2);
        if memory.is_null() {
            return Err(io::Error::last_os_error());
        }
        let buffer = GlobalLock(memory) as *mut u16;
        if buffer.is_null() {
            let error = io::Error::last_os_error();
            GlobalFree(memory);
            return Err(error);
        }
        std::ptr::copy_nonoverlapping(units.as_ptr(), buffer, units.len());
        GlobalUnlock(memory);
        // A message-only window owns this clipboard operation; it is never shown.
        let class = "STATIC\0".encode_utf16().collect::<Vec<_>>();
        let window = CreateWindowExW(
            0,
            class.as_ptr(),
            class.as_ptr(),
            0,
            0,
            0,
            0,
            0,
            -3_isize as _,
            null_mut(),
            null_mut(),
            null_mut(),
        );
        if window.is_null() {
            let error = io::Error::last_os_error();
            GlobalFree(memory);
            return Err(error);
        }
        let mut opened = false;
        for _ in 0..10 {
            if OpenClipboard(window) != 0 {
                opened = true;
                break;
            }
            std::thread::sleep(std::time::Duration::from_millis(20));
        }
        let result = if !opened || EmptyClipboard() == 0 || SetClipboardData(13, memory).is_null() {
            Err(io::Error::last_os_error())
        } else {
            Ok(())
        };
        if opened {
            CloseClipboard();
        }
        DestroyWindow(window);
        if result.is_err() {
            GlobalFree(memory);
        }
        result
    }
}

#[cfg(not(windows))]
pub fn copy(text: &str) -> io::Result<()> {
    use std::process::Stdio;
    let choices: &[(&str, &[&str])] = if cfg!(target_os = "macos") {
        &[("pbcopy", &[])]
    } else {
        &[
            ("wl-copy", &[]),
            ("xclip", &["-selection", "clipboard"]),
            ("xsel", &["--clipboard", "--input"]),
        ]
    };
    let mut last = io::Error::other("No clipboard helper found");
    for (program, arguments) in choices {
        match Command::new(program)
            .args(*arguments)
            .stdin(Stdio::piped())
            .stdout(Stdio::null())
            .stderr(Stdio::null())
            .spawn()
        {
            Ok(mut child) => {
                let written = child.stdin.take().unwrap().write_all(text.as_bytes());
                let status = child.wait()?;
                if written.is_ok() && status.success() {
                    return Ok(());
                }
                last = written
                    .err()
                    .unwrap_or_else(|| io::Error::other("Clipboard helper failed"));
            }
            Err(error) => last = error,
        }
    }
    Err(last)
}

/// Split an editor command without invoking a shell or expanding prompt text.
fn editor_arguments(value: &str) -> io::Result<Vec<String>> {
    let mut arguments = Vec::new();
    let mut word = String::new();
    let mut quote = None;
    for ch in value.chars() {
        if quote == Some(ch) {
            quote = None;
        } else if quote.is_none() && matches!(ch, '\'' | '"') {
            quote = Some(ch);
        } else if quote.is_none() && ch.is_whitespace() {
            if !word.is_empty() {
                arguments.push(std::mem::take(&mut word));
            }
        } else {
            word.push(ch);
        }
    }
    if quote.is_some() {
        return Err(io::Error::other("Unclosed quote in VISUAL/EDITOR"));
    }
    if !word.is_empty() {
        arguments.push(word);
    }
    if arguments.is_empty() {
        return Err(io::Error::other("VISUAL/EDITOR is empty"));
    }
    Ok(arguments)
}

pub fn edit_draft(text: &str) -> io::Result<String> {
    let directory = user_directory().join("tui").join("drafts");
    fs::create_dir_all(&directory)?;
    let path = directory.join(format!("draft-{}.txt", uuid::Uuid::new_v4()));
    private_file(&path)?.write_all(text.as_bytes())?;
    let result = (|| {
        let editor = crate::non_blank_env("VISUAL")
            .or_else(|| crate::non_blank_env("EDITOR"))
            .unwrap_or_else(|| if cfg!(windows) { "notepad.exe" } else { "vi" }.into());
        let arguments = editor_arguments(&editor)?;
        let status = Command::new(&arguments[0])
            .args(&arguments[1..])
            .arg(&path)
            .status()?;
        if !status.success() {
            return Err(io::Error::other(format!("Editor exited with {status}")));
        }
        decode_editor_text(fs::read(&path)?)
    })();
    // On failure keep the editor's file so edits can be recovered.
    match result {
        Ok(text) => {
            let _ = fs::remove_file(path);
            Ok(text)
        }
        Err(error) => Err(io::Error::other(format!("{error} | {}", path.display()))),
    }
}

fn decode_editor_text(bytes: Vec<u8>) -> io::Result<String> {
    let little_endian = bytes.starts_with(&[0xff, 0xfe]);
    let text = if little_endian || bytes.starts_with(&[0xfe, 0xff]) {
        if bytes.len() % 2 != 0 {
            return Err(io::Error::other("Incomplete UTF-16 editor file"));
        }
        let units = bytes[2..]
            .chunks_exact(2)
            .map(|pair| {
                if little_endian {
                    u16::from_le_bytes([pair[0], pair[1]])
                } else {
                    u16::from_be_bytes([pair[0], pair[1]])
                }
            })
            .collect::<Vec<_>>();
        String::from_utf16(&units).map_err(io::Error::other)?
    } else {
        String::from_utf8(bytes)
            .map_err(io::Error::other)?
            .trim_start_matches('\u{feff}')
            .to_string()
    };
    Ok(text.replace("\r\n", "\n").replace('\r', "\n"))
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn memory_replaces_atomically_and_keeps_multiline_drafts() {
        let directory =
            std::env::temp_dir().join(format!("harnex-tui-memory-{}", uuid::Uuid::new_v4()));
        let path = directory.join("state.json");
        let mut state = Memory {
            draft: "中文😀\nlast\n".into(),
            history: vec!["prior".into()],
            ..Default::default()
        };
        save(&path, &state).unwrap();
        assert_eq!(load(&path).unwrap(), state);
        state.draft.clear();
        save(&path, &state).unwrap();
        assert_eq!(load(&path).unwrap(), state);
        fs::remove_file(path).unwrap();
        fs::remove_dir(directory).unwrap();
    }
    #[test]
    fn editor_command_preserves_quoted_paths_without_shell_expansion() {
        assert_eq!(
            editor_arguments(r#""C:\Program Files\Editor\edit.exe" --wait"#).unwrap(),
            [r"C:\Program Files\Editor\edit.exe", "--wait"]
        );
        assert_eq!(editor_arguments("code --wait").unwrap(), ["code", "--wait"]);
        assert!(editor_arguments("'broken").is_err());
    }

    #[test]
    fn notepad_utf16_preserves_chinese_and_emoji_and_rejects_partial_units() {
        for little in [true, false] {
            let mut bytes = if little {
                vec![0xff, 0xfe]
            } else {
                vec![0xfe, 0xff]
            };
            for unit in "中文😀\r\nend\r\n".encode_utf16() {
                bytes.extend(if little {
                    unit.to_le_bytes()
                } else {
                    unit.to_be_bytes()
                });
            }
            assert_eq!(decode_editor_text(bytes.clone()).unwrap(), "中文😀\nend\n");
            bytes.pop();
            assert!(decode_editor_text(bytes).is_err());
        }
    }
}
