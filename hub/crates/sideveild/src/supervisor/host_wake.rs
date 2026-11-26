//! HostService endpoint sync, SELinux relabeling, and `am` foreground service wake helpers.

use std::fs::{self, OpenOptions};
use std::io::{self, Write};
use std::os::unix::fs::{MetadataExt, OpenOptionsExt, PermissionsExt};
use std::path::{Path, PathBuf};
use std::process::Stdio;
use std::sync::Arc;

use sideveil_protocol::rpc::DaemonEndpoint;
use tokio::process::Command;

use crate::fs_util::{chown_path, unique_tmp_path};
use crate::state::HubState;

/// Outcome of attempting to sync `daemon_endpoint.json` and wake `SideVeilHost`.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum HostWakeOutcome {
    /// Endpoint synced and `am start-foreground-service` invoked.
    Woken,
    /// Host CE data directory is not mounted yet (e.g., Direct Boot prior to user-0 unlock).
    DataDirNotReady,
    /// Hub WS server port is not ready or endpoint sync failed with a non-NotFound I/O error.
    SyncFailed,
}

pub(crate) async fn force_stop_host(host_package: &str) {
    let _ = Command::new("/system/bin/am")
        .args(["force-stop", host_package])
        .stdin(Stdio::null())
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .status()
        .await;
}

pub(crate) async fn wake_host_service(
    host_package: &str,
    state: &Arc<HubState>,
    log_not_ready: bool,
) -> HostWakeOutcome {
    let port = state.ws_port();
    if port == 0 {
        return HostWakeOutcome::SyncFailed;
    }
    match sync_host_endpoint(host_package, port, state.ws_token()).await {
        Ok(()) => {}
        Err(err) if err.kind() == io::ErrorKind::NotFound => {
            if log_not_ready {
                state.logs().info(format!(
                    "Deferring SideVeilHost wake until user-0 CE data directory is mounted ({err})"
                ));
            }
            return HostWakeOutcome::DataDirNotReady;
        }
        Err(err) => {
            state
                .logs()
                .warn(format!("Failed syncing host endpoint: {err}"));
            return HostWakeOutcome::SyncFailed;
        }
    }
    let component = format!("{host_package}/.HostService");
    let action = format!("{host_package}.ACTION_SYNC_ENDPOINT");
    let _ = Command::new("/system/bin/am")
        .args([
            "start-foreground-service",
            "--user",
            "0",
            "-n",
            &component,
            "-a",
            &action,
        ])
        .stdin(Stdio::null())
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .status()
        .await;
    HostWakeOutcome::Woken
}

async fn sync_host_endpoint(host_package: &str, port: u16, token: &str) -> io::Result<()> {
    let primary = PathBuf::from(format!("/data/user/0/{host_package}"));
    let data_dir = if primary.is_dir() {
        primary
    } else {
        let legacy = PathBuf::from(format!("/data/data/{host_package}"));
        if legacy.is_dir() {
            legacy
        } else {
            return Err(io::Error::new(
                io::ErrorKind::NotFound,
                format!("host data dir missing for {host_package}"),
            ));
        }
    };

    let meta = fs::metadata(&data_dir)?;
    let (uid, gid) = (meta.uid(), meta.gid());
    let no_backup = data_dir.join("no_backup");
    fs::create_dir_all(&no_backup)?;
    let _ = chown_path(&no_backup, uid, gid);
    let _ = fs::set_permissions(&no_backup, fs::Permissions::from_mode(0o700));

    let payload = serde_json::to_string(&DaemonEndpoint {
        port,
        token: token.to_owned(),
    })
    .map_err(|e| io::Error::new(io::ErrorKind::InvalidData, e))?;

    let dst = no_backup.join("daemon_endpoint.json");
    let tmp = unique_tmp_path(&dst);
    let mut file = OpenOptions::new()
        .create_new(true)
        .write(true)
        .mode(0o600)
        .open(&tmp)?;
    file.write_all(payload.as_bytes())?;
    file.write_all(b"\n")?;
    file.sync_all()?;
    drop(file);

    let _ = chown_path(&tmp, uid, gid);
    let _ = fs::set_permissions(&tmp, fs::Permissions::from_mode(0o600));
    relabel_selinux(&data_dir, &no_backup, &tmp).await;
    if let Err(err) = fs::rename(&tmp, &dst) {
        let _ = fs::remove_file(&tmp);
        return Err(err);
    }
    Ok(())
}

async fn relabel_selinux(ref_dir: &Path, dir: &Path, file: &Path) {
    let ref_arg = format!("--reference={}", ref_dir.display());
    let ok = Command::new("chcon")
        .arg(&ref_arg)
        .arg(dir)
        .arg(file)
        .stdin(Stdio::null())
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .status()
        .await
        .is_ok_and(|s| s.success());
    if !ok {
        let _ = Command::new("restorecon")
            .arg("-F")
            .arg(dir)
            .arg(file)
            .stdin(Stdio::null())
            .stdout(Stdio::null())
            .stderr(Stdio::null())
            .status()
            .await;
    }
}
