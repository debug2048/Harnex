//! Small rotating copies of child stdout/stderr. Canonical application logs are owned by the Runtime.
use std::fs::{self, File, OpenOptions};
use std::io::{self, Read, Seek, SeekFrom, Write};
use std::path::{Path, PathBuf};
use std::process::Child;

const MAX_BYTES: usize = 2 * 1024 * 1024;

/// Keep startup diagnostics specific to this process; previous errors must not classify a new startup failure.
pub fn prepare(directory: &Path) -> io::Result<()> {
    for name in ["runtime-out.log", "runtime-err.log"] {
        let file = directory.join(name);
        regular_or_missing(&file)?;
        if let Ok(metadata) = fs::metadata(&file) {
            if metadata.len() > 0 {
                let backup = file.with_file_name(format!("{name}.1"));
                regular_or_missing(&backup)?;
                let length = metadata.len().min(MAX_BYTES as u64) as usize;
                let mut input = File::open(&file)?;
                input.seek(SeekFrom::End(-(length as i64)))?;
                let mut tail = vec![0; length];
                input.read_exact(&mut tail)?;
                fs::write(backup, tail)?;
            }
        }
        fs::write(file, [])?;
    }
    Ok(())
}

pub fn capture_output(child: &mut Child, directory: &Path) {
    if let Some(stdout) = child.stdout.take() {
        drain(stdout, directory.join("runtime-out.log"));
    }
    if let Some(stderr) = child.stderr.take() {
        drain(stderr, directory.join("runtime-err.log"));
    }
}

fn drain(mut input: impl Read + Send + 'static, file: PathBuf) {
    let _ = std::thread::Builder::new().name("runtime-log".into()).spawn(move || {
        let mut bytes = [0u8; 8192];
        loop {
            match input.read(&mut bytes) {
                Ok(0) => break,
                Ok(count) => { let _ = append(&file, &bytes[..count], MAX_BYTES); }
                Err(error) if error.kind() == io::ErrorKind::Interrupted => continue,
                Err(_) => break,
            }
        }
    });
}

fn regular_or_missing(file: &Path) -> io::Result<()> {
    match fs::symlink_metadata(file) {
        Ok(metadata) if metadata.is_file() => Ok(()),
        Ok(_) => Err(io::Error::other("Diagnostic log must be a regular file")),
        Err(error) if error.kind() == io::ErrorKind::NotFound => Ok(()),
        Err(error) => Err(error),
    }
}

fn append(file: &Path, bytes: &[u8], limit: usize) -> io::Result<()> {
    regular_or_missing(file)?;
    let bytes = &bytes[bytes.len().saturating_sub(limit)..];
    let size = fs::metadata(file).map(|metadata| metadata.len()).unwrap_or(0);
    if size.saturating_add(bytes.len() as u64) > limit as u64 {
        let backup = file.with_file_name(format!("{}.1", file.file_name().unwrap().to_string_lossy()));
        regular_or_missing(&backup)?;
        if size > limit as u64 {
            let mut input = File::open(file)?;
            input.seek(SeekFrom::End(-(limit as i64)))?;
            let mut tail = vec![0; limit];
            input.read_exact(&mut tail)?;
            drop(input);
            fs::write(&backup, tail)?;
            fs::write(file, [])?;
        } else if size > 0 {
            if backup.exists() { fs::remove_file(&backup)?; }
            fs::rename(file, backup)?;
        }
    }
    OpenOptions::new().create(true).append(true).open(file)?.write_all(bytes)
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn output_retains_a_bounded_tail_and_one_backup() {
        let directory = std::env::temp_dir().join(format!("harnex-log-{}", uuid::Uuid::new_v4()));
        fs::create_dir(&directory).unwrap();
        let file = directory.join("runtime-out.log");
        fs::write(&file, vec![b'x'; 1024]).unwrap();
        append(&file, b"first", 32).unwrap();
        assert_eq!(fs::metadata(directory.join("runtime-out.log.1")).unwrap().len(), 32);
        for _ in 0..20 { append(&file, b"another line\n", 32).unwrap(); }
        assert!(fs::metadata(&file).unwrap().len() <= 32);
        assert!(fs::metadata(directory.join("runtime-out.log.1")).unwrap().len() <= 32);
        assert_eq!(fs::read_dir(&directory).unwrap().count(), 2);
        append(&file, &[b'y'; 100], 32).unwrap();
        assert_eq!(fs::read(&file).unwrap(), vec![b'y'; 32]);
        prepare(&directory).unwrap();
        assert!(fs::read(&file).unwrap().is_empty());
        assert_eq!(fs::read(directory.join("runtime-out.log.1")).unwrap(), vec![b'y'; 32]);
        fs::remove_dir_all(directory).unwrap();
    }
}
