//! Background processes whose Windows process tree belongs to this host.
use std::io;
use std::ops::{Deref, DerefMut};
use std::process::{Child, Command};

pub struct OwnedChild {
    child: Child,
    // This handle is never inherited. Windows closes it even when the host is
    // terminated without running Rust destructors, killing the entire tree.
    #[cfg(windows)]
    _job: std::os::windows::io::OwnedHandle,
}

impl Deref for OwnedChild {
    type Target = Child;
    fn deref(&self) -> &Child {
        &self.child
    }
}

impl DerefMut for OwnedChild {
    fn deref_mut(&mut self) -> &mut Child {
        &mut self.child
    }
}

/// Spawn an owned background process. Existing/attached runtimes must never be
/// enrolled here. Unix callers retain their existing process-group lifecycle.
pub fn spawn(command: &mut Command) -> io::Result<OwnedChild> {
    #[cfg(windows)]
    {
        windows::spawn(command)
    }
    #[cfg(not(windows))]
    {
        command.spawn().map(|child| OwnedChild { child })
    }
}

#[cfg(windows)]
mod windows {
    use super::*;
    use std::mem::{size_of, zeroed};
    use std::os::windows::io::{AsRawHandle, FromRawHandle, OwnedHandle};
    use std::os::windows::process::CommandExt;
    use std::ptr::{null, null_mut};
    use windows_sys::Win32::{
        Foundation::INVALID_HANDLE_VALUE,
        System::{
            Diagnostics::ToolHelp::{
                CreateToolhelp32Snapshot, Thread32First, Thread32Next, TH32CS_SNAPTHREAD,
                THREADENTRY32,
            },
            JobObjects::{
                AssignProcessToJobObject, CreateJobObjectW, JobObjectExtendedLimitInformation,
                SetInformationJobObject, JOBOBJECT_EXTENDED_LIMIT_INFORMATION,
                JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE,
            },
            Threading::{
                OpenThread, ResumeThread, CREATE_NO_WINDOW, CREATE_SUSPENDED, THREAD_SUSPEND_RESUME,
            },
        },
    };

    pub(super) fn spawn(command: &mut Command) -> io::Result<OwnedChild> {
        // Null security attributes make the job handle non-inheritable.
        let raw = unsafe { CreateJobObjectW(null(), null()) };
        if raw.is_null() {
            return Err(io::Error::last_os_error());
        }
        let job = unsafe { OwnedHandle::from_raw_handle(raw) };
        let mut limits: JOBOBJECT_EXTENDED_LIMIT_INFORMATION = unsafe { zeroed() };
        limits.BasicLimitInformation.LimitFlags = JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE;
        if unsafe {
            SetInformationJobObject(
                job.as_raw_handle(),
                JobObjectExtendedLimitInformation,
                &limits as *const _ as *const _,
                size_of::<JOBOBJECT_EXTENDED_LIMIT_INFORMATION>() as u32,
            )
        } == 0
        {
            return Err(io::Error::last_os_error());
        }

        // Enrol before any child code runs, so even immediately spawned tool
        // processes inherit the job. Never fall back to an uncontained runtime.
        command.creation_flags(CREATE_NO_WINDOW | CREATE_SUSPENDED);
        let mut child = command.spawn()?;
        let enrolled =
            unsafe { AssignProcessToJobObject(job.as_raw_handle(), child.as_raw_handle()) };
        let result = if enrolled == 0 {
            Err(io::Error::last_os_error())
        } else {
            resume(child.id())
        };
        if let Err(error) = result {
            let _ = child.kill();
            let _ = child.wait();
            return Err(io::Error::new(
                error.kind(),
                format!("cannot bind background process to its host: {error}"),
            ));
        }
        Ok(OwnedChild { child, _job: job })
    }

    fn resume(pid: u32) -> io::Result<()> {
        // Stable std::process::Child exposes the process handle, but not its
        // primary thread. The suspended process has not run any user code yet.
        let raw = unsafe { CreateToolhelp32Snapshot(TH32CS_SNAPTHREAD, 0) };
        if raw == INVALID_HANDLE_VALUE {
            return Err(io::Error::last_os_error());
        }
        let snapshot = unsafe { OwnedHandle::from_raw_handle(raw) };
        let mut entry: THREADENTRY32 = unsafe { zeroed() };
        entry.dwSize = size_of::<THREADENTRY32>() as u32;
        let mut found = unsafe { Thread32First(snapshot.as_raw_handle(), &mut entry) };
        while found != 0 {
            if entry.th32OwnerProcessID == pid {
                let raw = unsafe { OpenThread(THREAD_SUSPEND_RESUME, 0, entry.th32ThreadID) };
                if raw == null_mut() {
                    return Err(io::Error::last_os_error());
                }
                let thread = unsafe { OwnedHandle::from_raw_handle(raw) };
                if unsafe { ResumeThread(thread.as_raw_handle()) } == u32::MAX {
                    return Err(io::Error::last_os_error());
                }
                return Ok(());
            }
            found = unsafe { Thread32Next(snapshot.as_raw_handle(), &mut entry) };
        }
        Err(io::Error::new(
            io::ErrorKind::NotFound,
            "background process primary thread is unavailable",
        ))
    }
}

#[cfg(all(test, windows))]
mod tests {
    use super::*;
    use std::fs;
    use std::os::windows::io::{AsRawHandle, FromRawHandle, OwnedHandle};
    use std::os::windows::process::CommandExt;
    use std::path::Path;
    use std::process::Stdio;
    use std::thread;
    use std::time::{Duration, Instant};
    use windows_sys::Win32::System::Threading::{
        OpenProcess, WaitForSingleObject, CREATE_NO_WINDOW, PROCESS_SYNCHRONIZE,
    };

    fn fixture(role: &str, directory: &Path) -> Command {
        let mut command = Command::new(std::env::current_exe().unwrap());
        command
            .args([
                "--exact",
                "owned_process::tests::process_fixture",
                "--ignored",
            ])
            .env("HARNEX_PROCESS_FIXTURE_ROLE", role)
            .env("HARNEX_PROCESS_FIXTURE_DIR", directory)
            .stdin(Stdio::null())
            .stdout(Stdio::null())
            .stderr(Stdio::null())
            .creation_flags(CREATE_NO_WINDOW);
        command
    }

    #[test]
    #[ignore = "subprocess fixture for owned process lifecycle test"]
    fn process_fixture() {
        let Ok(role) = std::env::var("HARNEX_PROCESS_FIXTURE_ROLE") else {
            return;
        };
        let directory =
            std::path::PathBuf::from(std::env::var_os("HARNEX_PROCESS_FIXTURE_DIR").unwrap());
        let _owned;
        let _descendant;
        match role.as_str() {
            "owner" => {
                _owned = spawn(&mut fixture("tree", &directory)).unwrap();
            }
            "tree" => {
                _descendant = fixture("leaf", &directory).spawn().unwrap();
            }
            _ => {}
        }
        fs::write(
            directory.join(format!("{role}.pid")),
            std::process::id().to_string(),
        )
        .unwrap();
        loop {
            thread::sleep(Duration::from_secs(1));
        }
    }

    struct Cleanup(Child);
    impl Drop for Cleanup {
        fn drop(&mut self) {
            let _ = self.0.kill();
            let _ = self.0.wait();
        }
    }

    fn process_handle(directory: &Path, role: &str) -> OwnedHandle {
        let deadline = Instant::now() + Duration::from_secs(15);
        loop {
            if let Ok(value) = fs::read_to_string(directory.join(format!("{role}.pid"))) {
                if let Ok(pid) = value.parse::<u32>() {
                    let handle = unsafe { OpenProcess(PROCESS_SYNCHRONIZE, 0, pid) };
                    assert!(
                        !handle.is_null(),
                        "fixture {role} exited before observation"
                    );
                    return unsafe { OwnedHandle::from_raw_handle(handle) };
                }
            }
            assert!(Instant::now() < deadline, "fixture {role} did not start");
            thread::sleep(Duration::from_millis(25));
        }
    }

    #[test]
    fn killing_owner_reaps_owned_tree_and_preserves_unrelated_process() {
        let directory =
            std::env::temp_dir().join(format!("harnex-owned-process-{}", uuid::Uuid::new_v4()));
        fs::create_dir_all(&directory).unwrap();
        let mut unrelated = Cleanup(fixture("unrelated", &directory).spawn().unwrap());
        let mut owner = Cleanup(fixture("owner", &directory).spawn().unwrap());
        let tree = process_handle(&directory, "tree");
        let leaf = process_handle(&directory, "leaf");
        // TerminateProcess kills only this owner, bypassing every Rust Drop.
        owner.0.kill().unwrap();
        owner.0.wait().unwrap();
        assert_eq!(
            unsafe { WaitForSingleObject(tree.as_raw_handle(), 10_000) },
            0,
            "owned child survived"
        );
        assert_eq!(
            unsafe { WaitForSingleObject(leaf.as_raw_handle(), 10_000) },
            0,
            "owned grandchild survived"
        );
        assert!(
            unrelated.0.try_wait().unwrap().is_none(),
            "unrelated process was terminated"
        );
        drop(unrelated);
        fs::remove_dir_all(directory).unwrap();
    }
}
