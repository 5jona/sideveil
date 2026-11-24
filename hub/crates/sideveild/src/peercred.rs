//! Kernel `SO_PEERCRED` authentication and Android package UID verification for UDS peers.

use std::fs;
use std::io;
use std::path::{Path, PathBuf};

use sideveil_protocol::plugin::ManifestValidationError;
use sideveil_protocol::uds::UdsClientHello;
use thiserror::Error;
use tokio::net::UnixStream;

/// Linux root user ID (`uid 0`).
pub const ROOT_UID: u32 = 0;
/// Android `system_server` / `:sensor` user ID (`uid 1000`).
pub const SYSTEM_UID: u32 = 1000;
/// First Android application ID (`Process.FIRST_APPLICATION_UID`).
pub const FIRST_APP_UID: u32 = 10_000;
/// Android per-user UID stride (`UserHandle.PER_USER_RANGE`: `uid = userId * 100_000 + appId`).
pub const PER_USER_RANGE: u32 = 100_000;
const ANDROID_PACKAGES_LIST: &str = "/data/system/packages.list";

/// Kernel-verified `SO_PEERCRED` identity for a connected Unix domain socket peer.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct PeerCred {
    /// Effective peer user ID.
    pub uid: u32,
    /// Effective peer group ID.
    pub gid: u32,
    /// Optional peer process ID.
    pub pid: Option<u32>,
}

/// Logical UDS listener channel distinguishing privileged hub clients from app backends.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum SocketChannel {
    /// Privileged `hub.sock` for CLI, `:sensor`, and native KernelSU daemon plugins.
    Hub,
    /// Restricted `app.sock` for LSPosed target-app processes.
    App,
}

/// Configuration knobs for `SO_PEERCRED` peer authorization (primarily test overrides).
#[derive(Debug, Clone, Default)]
pub struct PeerAuthConfig {
    /// Extra UIDs permitted on `hub.sock` (used exclusively in non-root host tests).
    pub extra_privileged_uids: Vec<u32>,
    /// Optional override path for Android `packages.list` (used in tests).
    pub packages_list_path: Option<PathBuf>,
}

/// Authorization failure returned by [`authorize_peer`].
#[derive(Debug, Error, PartialEq, Eq)]
pub enum PeerAuthError {
    /// Non-root caller attempted to claim the `ctl` role on `hub.sock`.
    #[error("ctl role requires uid 0 on hub.sock (got uid {uid})")]
    CtlRequiresRoot {
        /// Connecting peer UID.
        uid: u32,
    },
    /// Unprivileged caller attempted to claim `:sensor` or native backend role on `hub.sock`.
    #[error("privileged role requires uid 0 or 1000 on hub.sock (got uid {uid})")]
    PrivilegedRoleRequired {
        /// Connecting peer UID.
        uid: u32,
    },
    /// Non-backend role attempted to connect on `app.sock`.
    #[error("only pluginBackend role is allowed on app.sock")]
    AppSocketRoleForbidden,
    /// Invalid plugin identifier supplied in `app.sock` handshake.
    #[error(transparent)]
    InvalidPluginId(#[from] ManifestValidationError),
    /// `app.sock` plugin backend omitted or left `packageName` blank.
    #[error("app.sock pluginBackend requires non-empty packageName")]
    MissingPackageName,
    /// Connecting peer `appId` is below [`FIRST_APP_UID`].
    #[error("unexpected non-app uid {uid} (appId {app_id}) on app.sock")]
    NonAppUid {
        /// Raw kernel peer UID.
        uid: u32,
        /// Extracted Android application ID (`uid % PER_USER_RANGE`).
        app_id: u32,
    },
    /// Declared package name was not found in `packages.list`.
    #[error("package '{package}' not found in {}", path.display())]
    PackageNotFound {
        /// Claimed Android package name.
        package: String,
        /// Path to `packages.list` consulted.
        path: PathBuf,
    },
    /// Connecting peer `appId` did not match the UID recorded for the declared package.
    #[error(
        "peer uid {uid} (appId {app_id}) does not match package '{package}' appId {expected_app_id}"
    )]
    UidMismatch {
        /// Raw kernel peer UID.
        uid: u32,
        /// Extracted Android application ID (`uid % PER_USER_RANGE`).
        app_id: u32,
        /// Claimed Android package name.
        package: String,
        /// Expected application ID from `packages.list`.
        expected_app_id: u32,
    },
}

/// Reads kernel-verified `SO_PEERCRED` credentials from a connected Unix stream socket.
pub fn read_peer_cred(stream: &UnixStream) -> io::Result<PeerCred> {
    let cred = stream.peer_cred()?;
    let pid = cred.pid().and_then(|p| u32::try_from(p).ok());
    Ok(PeerCred {
        uid: cred.uid(),
        gid: cred.gid(),
        pid,
    })
}

/// Validates whether a connected UDS peer is authorized to claim the requested role.
pub fn authorize_peer(
    channel: SocketChannel,
    cred: PeerCred,
    hello: &UdsClientHello,
    auth: &PeerAuthConfig,
) -> Result<(), PeerAuthError> {
    let is_extra = auth.extra_privileged_uids.contains(&cred.uid);

    match channel {
        SocketChannel::Hub => match hello {
            UdsClientHello::Ctl => {
                if cred.uid == ROOT_UID || is_extra {
                    Ok(())
                } else {
                    Err(PeerAuthError::CtlRequiresRoot { uid: cred.uid })
                }
            }
            UdsClientHello::Sensor { .. } | UdsClientHello::PluginBackend { .. } => {
                if cred.uid == ROOT_UID || cred.uid == SYSTEM_UID || is_extra {
                    Ok(())
                } else {
                    Err(PeerAuthError::PrivilegedRoleRequired { uid: cred.uid })
                }
            }
        },
        SocketChannel::App => match hello {
            UdsClientHello::PluginBackend {
                plugin_id,
                package_name,
                ..
            } => {
                sideveil_protocol::plugin::validate_plugin_id(plugin_id)?;
                let Some(pkg) = package_name
                    .as_deref()
                    .map(str::trim)
                    .filter(|s| !s.is_empty())
                else {
                    return Err(PeerAuthError::MissingPackageName);
                };
                if cred.uid == ROOT_UID || is_extra {
                    return Ok(());
                }
                let app_id = cred.uid % PER_USER_RANGE;
                if !(FIRST_APP_UID..90_000).contains(&app_id) {
                    return Err(PeerAuthError::NonAppUid {
                        uid: cred.uid,
                        app_id,
                    });
                }
                let packages_path = auth
                    .packages_list_path
                    .as_deref()
                    .unwrap_or_else(|| Path::new(ANDROID_PACKAGES_LIST));
                let expected_uid = lookup_package_uid(packages_path, pkg).ok_or_else(|| {
                    PeerAuthError::PackageNotFound {
                        package: pkg.to_owned(),
                        path: packages_path.to_path_buf(),
                    }
                })?;
                let expected_app_id = expected_uid % PER_USER_RANGE;
                if expected_app_id != app_id {
                    return Err(PeerAuthError::UidMismatch {
                        uid: cred.uid,
                        app_id,
                        package: pkg.to_owned(),
                        expected_app_id,
                    });
                }
                Ok(())
            }
            _ => Err(PeerAuthError::AppSocketRoleForbidden),
        },
    }
}

/// Resolves an Android package's numeric UID from `/data/system/packages.list`.
#[must_use]
pub fn lookup_package_uid(packages_list: &Path, package_name: &str) -> Option<u32> {
    let content = fs::read_to_string(packages_list).ok()?;
    parse_package_uid(&content, package_name)
}

/// Parses an Android `packages.list` snapshot and returns the numeric UID for `package_name`.
/// Safely skips blank lines, comments, and malformed rows.
#[must_use]
pub fn parse_package_uid(content: &str, package_name: &str) -> Option<u32> {
    for line in content.lines() {
        let trimmed = line.trim();
        if trimmed.is_empty() || trimmed.starts_with('#') {
            continue;
        }
        let mut parts = trimmed.split_whitespace();
        let (Some(pkg), Some(uid_str)) = (parts.next(), parts.next()) else {
            continue;
        };
        if pkg == package_name
            && let Ok(uid) = uid_str.parse::<u32>()
        {
            return Some(uid);
        }
    }
    None
}

#[cfg(test)]
mod tests;
