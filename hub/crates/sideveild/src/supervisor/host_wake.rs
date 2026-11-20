//! HostService endpoint sync, SELinux relabeling, and `am` foreground service wake helpers.

use std::ffi::CString;
use std::fs::{self, OpenOptions};
use std::io::{self, Write};
use std::os::unix::ffi::OsStrExt;
use std::os::unix::fs::{MetadataExt, PermissionsExt};
use std::path::{Path, PathBuf};
use std::process::Stdio;
use std::sync::Arc;

use sideveil_protocol::rpc::DaemonEndpoint;
use tokio::process::Command;

use crate::state::HubState;

pub(crate) async fn force_stop_host(host_package: &str) {
    let _ = Command::new("/system/bin/am")
        .args(["force-stop", host_package])
        .stdin(Stdio::null())
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .status()
        .await;
}

pub(crate) async fn wake_host_service(host_package: &str, state: &Arc<HubState>) {
    let port = state.ws_port();
    if port == 0 {
        return;
    }
    if let Err(err) = sync_host_endpoint(host_package, port, state.ws_token()).await {
        state
            .logs()
            .warn(format!("Failed syncing host endpoint: {err}"));
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

    let tmp = no_backup.join(format!("daemon_endpoint.json.tmp.{}", std::process::id()));
    let dst = no_backup.join("daemon_endpoint.json");
    let mut file = OpenOptions::new()
        .create(true)
        .write(true)
        .truncate(true)
        .open(&tmp)?;
    file.write_all(payload.as_bytes())?;
    file.write_all(b"\n")?;
    file.sync_all()?;
    drop(file);

    let _ = chown_path(&tmp, uid, gid);
    let _ = fs::set_permissions(&tmp, fs::Permissions::from_mode(0o600));
    relabel_selinux(&data_dir, &no_backup, &tmp).await;
    fs::rename(&tmp, &dst)?;
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

pub(crate) fn chown_path(path: &Path, uid: u32, gid: u32) -> io::Result<()> {
    let c_path = CString::new(path.as_os_str().as_bytes())
        .map_err(|_| io::Error::new(io::ErrorKind::InvalidInput, "path contains interior NUL"))?;
    let rc = unsafe {
        // SAFETY: `c_path` is a valid NUL-terminated C string.
        libc::chown(c_path.as_ptr(), uid, gid)
    };
    if rc == 0 {
        Ok(())
    } else {
        Err(io::Error::last_os_error())
    }
}
