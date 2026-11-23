//! Unit tests for kernel `SO_PEERCRED` and multi-user Android package UID authorization.

use std::fs;

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
    assert_eq!(
        authorize_peer(SocketChannel::Hub, system, &UdsClientHello::Ctl, &auth),
        Err(PeerAuthError::CtlRequiresRoot { uid: 1000 })
    );
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
    assert_eq!(
        authorize_peer(
            SocketChannel::Hub,
            app,
            &UdsClientHello::Sensor {
                pid: 12,
                version: "0.1.0".to_owned()
            },
            &auth
        ),
        Err(PeerAuthError::PrivilegedRoleRequired { uid: 10234 })
    );
    assert_eq!(
        authorize_peer(SocketChannel::App, app, &UdsClientHello::Ctl, &auth),
        Err(PeerAuthError::AppSocketRoleForbidden)
    );
}

#[test]
fn parses_packages_list_and_verifies_primary_and_multiuser_app_uids() {
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
        packages_list_path: Some(list_file),
    };
    let hello = UdsClientHello::PluginBackend {
        plugin_id: "com.example.plugin".to_owned(),
        package_name: Some("com.example.target".to_owned()),
    };

    let primary = PeerCred {
        uid: 10234,
        gid: 10234,
        pid: Some(42),
    };
    let work_profile = PeerCred {
        uid: 10 * PER_USER_RANGE + 10234,
        gid: 10 * PER_USER_RANGE + 10234,
        pid: Some(43),
    };
    let parallel_app = PeerCred {
        uid: 999 * PER_USER_RANGE + 10234,
        gid: 999 * PER_USER_RANGE + 10234,
        pid: Some(44),
    };
    let secondary_system = PeerCred {
        uid: 10 * PER_USER_RANGE + 1000,
        gid: 10 * PER_USER_RANGE + 1000,
        pid: Some(45),
    };
    let mismatched = PeerCred {
        uid: 10 * PER_USER_RANGE + 10999,
        gid: 10 * PER_USER_RANGE + 10999,
        pid: Some(46),
    };
    assert!(authorize_peer(SocketChannel::App, primary, &hello, &auth).is_ok());
    assert!(authorize_peer(SocketChannel::App, work_profile, &hello, &auth).is_ok());
    assert!(authorize_peer(SocketChannel::App, parallel_app, &hello, &auth).is_ok());
    assert_eq!(
        authorize_peer(SocketChannel::App, secondary_system, &hello, &auth),
        Err(PeerAuthError::NonAppUid {
            uid: 1_001_000,
            app_id: 1000,
        })
    );
    assert!(matches!(
        authorize_peer(SocketChannel::App, mismatched, &hello, &auth),
        Err(PeerAuthError::UidMismatch {
            app_id: 10999,
            expected_app_id: 10234,
            ..
        })
    ));

    let _ = fs::remove_dir_all(&dir);
}
