//! Shared desktop state and owned Runtime resources.

use serde_json::Value;
use std::path::PathBuf;
use std::process::Child;
use std::sync::Mutex;

/// Holds the spawned Runtime child and its per-instance internal lifecycle credential.
pub(crate) struct RuntimeProcess {
    pub(crate) child: RuntimeChild,
    pub(crate) supervisor_token: String,
    pub(crate) instance_id: String,
    pub(crate) remote: Option<harnex_host_runtime::RemoteControl>,
}

pub(crate) enum RuntimeChild {
    Local(Child),
    Ssh(harnex_host_runtime::Tunnel),
}

impl RuntimeChild {
    pub(crate) fn id(&self) -> u32 {
        match self {
            Self::Local(child) => child.id(),
            Self::Ssh(tunnel) => tunnel.id(),
        }
    }
    pub(crate) fn try_wait(&mut self) -> std::io::Result<Option<std::process::ExitStatus>> {
        match self {
            Self::Local(child) => child.try_wait(),
            Self::Ssh(tunnel) => tunnel.try_wait(),
        }
    }
}

pub(crate) struct ServerProcess(pub(crate) Mutex<Option<RuntimeProcess>>);

pub(crate) struct PersonalProcess(
    pub(crate) Mutex<Option<harnex_host_runtime::personal::PersonalRuntime>>,
);

pub(crate) struct PersonalState(pub(crate) Mutex<Value>);

/// The currently active workspace root (for the menu/title).
pub(crate) struct CurrentWorkspace(pub(crate) Mutex<Option<PathBuf>>);

pub(crate) struct CurrentRemote(pub(crate) Mutex<Option<(String, String)>>);
// profile id, workspace id
/// Desktop projection of the same lifecycle phases exposed by the Node Supervisor.
pub(crate) struct DesktopLifecycle(pub(crate) Mutex<&'static str>);

/// Serialises destructive Runtime transitions initiated by GUI commands.
pub(crate) struct WorkspaceTransition(pub(crate) Mutex<()>);

pub(crate) struct WorkspaceControl(
    pub(crate) Mutex<Option<harnex_host_runtime::workspace_control::WorkspaceControl>>,
);

pub(crate) struct HostClosing(pub(crate) std::sync::atomic::AtomicBool);
