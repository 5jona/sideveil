//! Kernel `SO_PEERCRED` authentication and Android package UID verification for UDS peers.

use std::fs;
use std::io;
use std::path::Path;

use sideveil_protocol::uds::UdsClientHello;
use tokio::net::UnixStream;

pub const ROOT_UID: u32 = 0;
pub const SYSTEM_UID: u32 = 1000;
pub const FIRST_APP_UID: u32 = 10_000;
const ANDROID_PACKAGES_LIST: &str = "/data/system/packages.list";

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct PeerCred {
    pub uid: u32,
    pub gid: u32,
    pub pid: Option<u32>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum SocketChannel {
    /// Privileged `hub.sock` for CLI, `:sensor`, and native KernelSU daemon plugins.
    Hub,
    /// Restricted `app.sock` for LSPosed target-app processes.
    App,
}

#[derive(Debug, Clone, Default)]
pub struct PeerAuthConfig {
    /// Extra UIDs permitted on `hub.sock` (used exclusively in non-root host tests).
    pub extra_privileged_uids: Vec<u32>,
    /// Optional override path for Android `packages.list` (used in tests).
    pub packages_list_path: Option<std::path::PathBuf>,
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
) -> Result<(), String> {
    let is_extra = auth.extra_privileged_uids.contains(&cred.uid);

    match channel {
        SocketChannel::Hub => match hello {
            UdsClientHello::Ctl => {
                if cred.uid == ROOT_UID || is_extra {
                    Ok(())
                } else {
                    Err(format!(
                        "ctl role requires uid 0 on hub.sock (got uid {})",
                        cred.uid
                    ))
                }
            }
            UdsClientHello::Sensor { .. } | UdsClientHello::PluginBackend { .. } => {
                if cred.uid == ROOT_UID || cred.uid == SYSTEM_UID || is_extra {
                    Ok(())
                } else {
                    Err(format!(
                        "privileged role requires uid 0 or 1000 on hub.sock (got uid {})",
                        cred.uid
                    ))
                }
            }
        },
        SocketChannel::App => match hello {
            UdsClientHello::PluginBackend {
                plugin_id,
                package_name,
            } => {
                sideveil_protocol::plugin::validate_plugin_id(plugin_id)
                    .map_err(|e| e.to_string())?;
                let Some(pkg) = package_name
                    .as_deref()
                    .map(str::trim)
                    .filter(|s| !s.is_empty())
                else {
                    return Err("app.sock pluginBackend requires non-empty packageName".to_owned());
                };
                if cred.uid == ROOT_UID || is_extra {
                    return Ok(());
                }
                if cred.uid < FIRST_APP_UID {
                    return Err(format!("unexpected non-app uid {} on app.sock", cred.uid));
                }
                let packages_path = auth
                    .packages_list_path
                    .as_deref()
                    .unwrap_or_else(|| Path::new(ANDROID_PACKAGES_LIST));
                let expected_uid = lookup_package_uid(packages_path, pkg).ok_or_else(|| {
                    format!("package '{pkg}' not found in {}", packages_path.display())
                })?;
                if expected_uid != cred.uid {
                    return Err(format!(
                        "peer uid {} does not match package '{pkg}' uid {expected_uid}",
                        cred.uid
                    ));
                }
                Ok(())
            }
            _ => Err("only pluginBackend role is allowed on app.sock".to_owned()),
        },
    }
}

/// Resolves an Android package's numeric UID from `/data/system/packages.list`.
#[must_use]
pub fn lookup_package_uid(packages_list: &Path, package_name: &str) -> Option<u32> {
    let content = fs::read_to_string(packages_list).ok()?;
    parse_package_uid(&content, package_name)
}

#[must_use]
pub fn parse_package_uid(content: &str, package_name: &str) -> Option<u32> {
    for line in content.lines() {
        let mut parts = line.split_whitespace();
        let pkg = parts.next()?;
        let uid_str = parts.next()?;
        if pkg == package_name {
            return uid_str.parse::<u32>().ok();
        }
    }
    None
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn enforces_uid_boundaries_on_hub_and_app_sockets() {
        let auth = PeerAuthConfig::default();
        let root = PeerCred {
            uid: 0,
            gid: 0,
            pid: Some(10),
        };
        let system = PeerCred {
            uid: 1000,
            gid: 1000,
            pid: Some(11),
        };
        let app = PeerCred {
            uid: 10234,
            gid: 10234,
            pid: Some(12),
        };

        assert!(authorize_peer(SocketChannel::Hub, root, &UdsClientHello::Ctl, &auth).is_ok());
        assert!(authorize_peer(SocketChannel::Hub, system, &UdsClientHello::Ctl, &auth).is_err());
        assert!(
            authorize_peer(
                SocketChannel::Hub,
                system,
                &UdsClientHello::Sensor {
                    pid: 11,
                    version: "0.1.0".to_owned()
                },
                &auth
            )
            .is_ok()
        );
        assert!(
            authorize_peer(
                SocketChannel::Hub,
                app,
                &UdsClientHello::Sensor {
                    pid: 12,
                    version: "0.1.0".to_owned()
                },
                &auth
            )
            .is_err()
        );
        assert!(authorize_peer(SocketChannel::App, app, &UdsClientHello::Ctl, &auth).is_err());
    }

    #[test]
    fn parses_packages_list_and_verifies_app_uid() {
        let sample = "\
me.weishu.kernelsu 10112 0 /data/user/0/me.weishu.kernelsu default:targetSdkVersion=35 none 0 1\n\
com.example.target 10234 0 /data/user/0/com.example.target default:targetSdkVersion=36 none 0 1\n";
        assert_eq!(parse_package_uid(sample, "com.example.target"), Some(10234));
        assert_eq!(parse_package_uid(sample, "com.missing.app"), None);

        let dir = std::env::temp_dir().join(format!("sideveil-peercred-{}", std::process::id()));
        let _ = fs::create_dir_all(&dir);
        let list_file = dir.join("packages.list");
        fs::write(&list_file, sample).expect("write packages.list");

        let auth = PeerAuthConfig {
            extra_privileged_uids: Vec::new(),
            packages_list_path: Some(list_file.clone()),
        };
        let hello = UdsClientHello::PluginBackend {
            plugin_id: "com.example.plugin".to_owned(),
            package_name: Some("com.example.target".to_owned()),
        };

        let matching = PeerCred {
            uid: 10234,
            gid: 10234,
            pid: Some(42),
        };
        let mismatched = PeerCred {
            uid: 10999,
            gid: 10999,
            pid: Some(43),
        };
        assert!(authorize_peer(SocketChannel::App, matching, &hello, &auth).is_ok());
        assert!(authorize_peer(SocketChannel::App, mismatched, &hello, &auth).is_err());

        let _ = fs::remove_dir_all(&dir);
    }
}
