//! Atomic staged plugin directory installer validating manifests and refusing symlink escapes.

use std::ffi::CString;
use std::fs;
use std::io;
use std::os::unix::ffi::OsStrExt;
use std::os::unix::fs::PermissionsExt;
use std::path::Path;
use std::sync::atomic::{AtomicU64, Ordering};

use sideveil_protocol::plugin::{BUILTIN_DIAG_PLUGIN_ID, PluginManifest};

static INSTALL_SEQ: AtomicU64 = AtomicU64::new(1);

/// Validates `source_dir/manifest.json` and installs the plugin package atomically into
/// `<plugins_dir>/<manifest.id>` using a dot-prefixed staging directory swap.
pub(super) fn install_plugin_dir(
    plugins_dir: &Path,
    source_dir: &Path,
) -> Result<PluginManifest, String> {
    if !source_dir.is_dir() {
        return Err(format!(
            "source directory '{}' does not exist or is not a directory",
            source_dir.display()
        ));
    }

    fs::create_dir_all(plugins_dir).map_err(|e| {
        format!(
            "failed to create plugins dir '{}': {e}",
            plugins_dir.display()
        )
    })?;

    let src_canon = fs::canonicalize(source_dir)
        .map_err(|e| format!("failed to canonicalize '{}': {e}", source_dir.display()))?;
    let plugins_canon = fs::canonicalize(plugins_dir)
        .map_err(|e| format!("failed to canonicalize '{}': {e}", plugins_dir.display()))?;
    if src_canon.starts_with(&plugins_canon) {
        return Err(
            "source directory must lie outside the live SideVeil plugins directory".to_owned(),
        );
    }

    let manifest_path = src_canon.join("manifest.json");
    let manifest_raw = fs::read_to_string(&manifest_path)
        .map_err(|e| format!("failed to read '{}': {e}", manifest_path.display()))?;
    let manifest: PluginManifest = serde_json::from_str(&manifest_raw).map_err(|e| {
        format!(
            "invalid manifest JSON in '{}': {e}",
            manifest_path.display()
        )
    })?;
    manifest
        .validate()
        .map_err(|e| format!("invalid plugin manifest: {e}"))?;

    if manifest.id == BUILTIN_DIAG_PLUGIN_ID {
        return Err(format!(
            "cannot overwrite built-in plugin '{BUILTIN_DIAG_PLUGIN_ID}'"
        ));
    }

    let entry_path = src_canon.join(&manifest.entry);
    let entry_meta = fs::symlink_metadata(&entry_path).map_err(|e| {
        format!(
            "manifest entry '{}' missing in source directory: {e}",
            manifest.entry
        )
    })?;
    if !entry_meta.file_type().is_file() {
        return Err(format!(
            "manifest entry '{}' must be a regular file",
            manifest.entry
        ));
    }

    let seq = INSTALL_SEQ.fetch_add(1, Ordering::Relaxed);
    let pid = std::process::id();
    let staging_dir = plugins_dir.join(format!(".{}.staging.{pid}.{seq}", manifest.id));
    let backup_dir = plugins_dir.join(format!(".{}.old.{pid}.{seq}", manifest.id));
    let target_dir = plugins_dir.join(&manifest.id);

    let _ = fs::remove_dir_all(&staging_dir);
    let _ = fs::remove_dir_all(&backup_dir);

    if let Err(err) = copy_dir_no_symlinks(&src_canon, &staging_dir) {
        let _ = fs::remove_dir_all(&staging_dir);
        return Err(format!("failed staging plugin files: {err}"));
    }

    let had_existing = target_dir.exists();
    if had_existing && let Err(err) = fs::rename(&target_dir, &backup_dir) {
        let _ = fs::remove_dir_all(&staging_dir);
        return Err(format!("failed moving previous plugin dir aside: {err}"));
    }

    if let Err(err) = fs::rename(&staging_dir, &target_dir) {
        let _ = fs::remove_dir_all(&staging_dir);
        if had_existing {
            let _ = fs::rename(&backup_dir, &target_dir);
        }
        return Err(format!("failed activating staged plugin dir: {err}"));
    }

    let _ = fs::remove_dir_all(&backup_dir);
    Ok(manifest)
}

fn copy_dir_no_symlinks(src: &Path, dst: &Path) -> io::Result<()> {
    fs::create_dir_all(dst)?;
    fs::set_permissions(dst, fs::Permissions::from_mode(0o755))?;
    try_chown_root_system(dst);

    for entry in fs::read_dir(src)? {
        let entry = entry?;
        let name = entry.file_name();
        if name.to_str().is_some_and(|n| n.starts_with('.')) {
            continue;
        }
        let src_path = entry.path();
        let dst_path = dst.join(&name);
        let meta = fs::symlink_metadata(&src_path)?;
        let ftype = meta.file_type();
        if ftype.is_symlink() {
            return Err(io::Error::new(
                io::ErrorKind::InvalidInput,
                format!(
                    "symlinks are disallowed in plugin packages: {}",
                    src_path.display()
                ),
            ));
        }
        if ftype.is_dir() {
            copy_dir_no_symlinks(&src_path, &dst_path)?;
        } else if ftype.is_file() {
            fs::copy(&src_path, &dst_path)?;
            fs::set_permissions(&dst_path, fs::Permissions::from_mode(0o644))?;
            try_chown_root_system(&dst_path);
        }
    }
    Ok(())
}

fn try_chown_root_system(path: &Path) {
    let Ok(c_path) = CString::new(path.as_os_str().as_bytes()) else {
        return;
    };
    // SAFETY: `c_path` is NUL-terminated; failure on non-root host tests is intentionally ignored.
    let _ = unsafe { libc::chown(c_path.as_ptr(), 0, 1000) };
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn installs_atomically_and_rejects_invalid_or_symlink_packages() {
        let base = std::env::temp_dir().join(format!("sideveil-inst-test-{}", std::process::id()));
        let _ = fs::remove_dir_all(&base);
        let plugins_dir = base.join("plugins");
        let src_dir = base.join("src_pkg");
        fs::create_dir_all(src_dir.join("web")).expect("mkdir src web");

        let manifest_v1 = r#"{
            "id": "demo.pkg",
            "name": "Demo",
            "version": "0.1.0",
            "entry": "web/index.html",
            "backend": "none"
        }"#;
        fs::write(src_dir.join("manifest.json"), manifest_v1).expect("write manifest");
        fs::write(src_dir.join("web/index.html"), "<html>v1</html>").expect("write html");

        let m1 = install_plugin_dir(&plugins_dir, &src_dir).expect("install v1");
        assert_eq!(m1.id, "demo.pkg");
        assert_eq!(
            fs::read_to_string(plugins_dir.join("demo.pkg/web/index.html")).expect("read v1"),
            "<html>v1</html>"
        );

        // Upgrade in place via atomic swap
        fs::write(src_dir.join("web/index.html"), "<html>v2</html>").expect("write html v2");
        let m2 = install_plugin_dir(&plugins_dir, &src_dir).expect("install v2");
        assert_eq!(m2.id, "demo.pkg");
        assert_eq!(
            fs::read_to_string(plugins_dir.join("demo.pkg/web/index.html")).expect("read v2"),
            "<html>v2</html>"
        );

        // Reject symlink inside package
        std::os::unix::fs::symlink("/etc/hosts", src_dir.join("web/evil")).expect("symlink");
        assert!(install_plugin_dir(&plugins_dir, &src_dir).is_err());

        // Reject installing from inside plugins_dir
        assert!(install_plugin_dir(&plugins_dir, &plugins_dir.join("demo.pkg")).is_err());

        let _ = fs::remove_dir_all(&base);
    }
}
