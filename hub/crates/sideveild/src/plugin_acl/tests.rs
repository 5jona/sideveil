use std::fs;
use std::os::unix::fs::{PermissionsExt, symlink};
use std::path::PathBuf;
use std::time::{SystemTime, UNIX_EPOCH};

use super::harden_data_dir_permissions;

fn make_temp_dir(label: &str) -> PathBuf {
    let nanos = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_nanos();
    let dir = std::env::temp_dir().join(format!(
        "sideveil-plugin-acl-{label}-{}-{nanos}",
        std::process::id()
    ));
    fs::create_dir_all(&dir).expect("create temp test dir");
    dir
}

#[test]
fn harden_data_dir_tightens_legacy_subdirs_and_skips_symlinks_and_files() {
    let root = make_temp_dir("harden");
    let data_dir = root.join("data");
    let external_dir = root.join("external-target");

    let legacy_perf = data_dir.join("example.native-perf-hud");
    let legacy_diag = data_dir.join("sideveil.diagnostic");
    let already_tight = data_dir.join("already.tight");
    let stray_file = data_dir.join("notes.txt");
    let symlink_dir = data_dir.join("linked-dir");

    fs::create_dir_all(&legacy_perf).expect("create legacy perf dir");
    fs::create_dir_all(&legacy_diag).expect("create legacy diag dir");
    fs::create_dir_all(&already_tight).expect("create tight dir");
    fs::create_dir_all(&external_dir).expect("create external target dir");
    fs::write(&stray_file, b"hello").expect("write stray file");
    symlink(&external_dir, &symlink_dir).expect("create symlink inside data_dir");

    fs::set_permissions(&data_dir, fs::Permissions::from_mode(0o755)).expect("chmod data_dir 0755");
    fs::set_permissions(&legacy_perf, fs::Permissions::from_mode(0o755))
        .expect("chmod legacy_perf 0755");
    fs::set_permissions(&legacy_diag, fs::Permissions::from_mode(0o755))
        .expect("chmod legacy_diag 0755");
    fs::set_permissions(&already_tight, fs::Permissions::from_mode(0o700))
        .expect("chmod already_tight 0700");
    fs::set_permissions(&external_dir, fs::Permissions::from_mode(0o755))
        .expect("chmod external_dir 0755");
    fs::set_permissions(&stray_file, fs::Permissions::from_mode(0o644))
        .expect("chmod stray_file 0644");

    harden_data_dir_permissions(&data_dir).expect("harden_data_dir_permissions");

    assert_eq!(
        fs::symlink_metadata(&data_dir)
            .expect("stat data_dir")
            .permissions()
            .mode()
            & 0o777,
        0o700
    );
    assert_eq!(
        fs::symlink_metadata(&legacy_perf)
            .expect("stat legacy_perf")
            .permissions()
            .mode()
            & 0o777,
        0o700
    );
    assert_eq!(
        fs::symlink_metadata(&legacy_diag)
            .expect("stat legacy_diag")
            .permissions()
            .mode()
            & 0o777,
        0o700
    );
    assert_eq!(
        fs::symlink_metadata(&already_tight)
            .expect("stat already_tight")
            .permissions()
            .mode()
            & 0o777,
        0o700
    );
    // Symlink target outside data_dir and regular files must remain untouched.
    assert_eq!(
        fs::symlink_metadata(&external_dir)
            .expect("stat external_dir")
            .permissions()
            .mode()
            & 0o777,
        0o755
    );
    assert_eq!(
        fs::symlink_metadata(&stray_file)
            .expect("stat stray_file")
            .permissions()
            .mode()
            & 0o777,
        0o644
    );

    let _ = fs::remove_dir_all(&root);
}
