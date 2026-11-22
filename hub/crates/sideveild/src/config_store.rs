//! Authoritative disk configuration store with atomic persistence and Linux `inotify` hot reload.

use std::ffi::CString;
use std::fs::{self, File, OpenOptions};
use std::io::{self, Write};
use std::os::unix::ffi::OsStrExt;
use std::os::unix::fs::{MetadataExt, PermissionsExt};
use std::path::{Path, PathBuf};
use std::sync::{Arc, PoisonError, RwLock};
use std::time::Duration;

use sideveil_protocol::config::{AppearancePatchParams, EdgePatchParams, SideVeilConfig};
use tokio::sync::broadcast;

use crate::inotify::{DIR_CHANGE_MASK, InotifyDirWatcher};
use crate::log_ring::LogRing;

#[derive(Debug)]
pub struct ConfigStore {
    path: PathBuf,
    current: RwLock<SideVeilConfig>,
    updates_tx: broadcast::Sender<SideVeilConfig>,
    logs: Arc<LogRing>,
}

impl ConfigStore {
    pub fn open(path: PathBuf, logs: Arc<LogRing>) -> Arc<Self> {
        let (updates_tx, _) = broadcast::channel(16);
        let initial = load_from_disk(&path, &logs).unwrap_or_default();
        let store = Arc::new(Self {
            path,
            current: RwLock::new(initial.clone()),
            updates_tx,
            logs,
        });
        if !store.path.exists()
            && let Err(err) = store.persist_config(&initial)
        {
            store
                .logs
                .warn(format!("Failed to seed initial config file: {err}"));
        }
        store
    }

    #[must_use]
    pub fn current(&self) -> SideVeilConfig {
        self.current
            .read()
            .unwrap_or_else(PoisonError::into_inner)
            .clone()
    }

    #[must_use]
    pub fn subscribe(&self) -> broadcast::Receiver<SideVeilConfig> {
        self.updates_tx.subscribe()
    }

    /// Re-reads and validates `config.json` from disk; preserves last known good config on error.
    pub fn reload(&self) -> SideVeilConfig {
        let Some(next) = load_from_disk(&self.path, &self.logs) else {
            return self.current();
        };
        let changed = {
            let mut guard = self.current.write().unwrap_or_else(PoisonError::into_inner);
            if *guard == next {
                false
            } else {
                *guard = next.clone();
                true
            }
        };
        if changed {
            self.logs.info(format!(
                "Reloaded config (enabled={}, mode={:?}, theme={}, blur={})",
                next.enabled, next.scope.mode, next.panel.theme_id, next.panel.native_blur_enabled
            ));
            let _ = self.updates_tx.send(next.clone());
        }
        next
    }

    /// Applies a partial appearance patch, atomically persists it, and notifies subscribers.
    pub fn patch_appearance(&self, patch: &AppearancePatchParams) -> io::Result<SideVeilConfig> {
        let next = {
            let mut guard = self.current.write().unwrap_or_else(PoisonError::into_inner);
            let updated = guard.apply_appearance_patch(patch);
            *guard = updated.clone();
            updated
        };
        self.persist_config(&next)?;
        let _ = self.updates_tx.send(next.clone());
        Ok(next)
    }

    /// Applies an edge handle position patch (side/topPercent), atomically persists it, and notifies subscribers.
    pub fn patch_edge(&self, patch: &EdgePatchParams) -> io::Result<SideVeilConfig> {
        let next = {
            let mut guard = self.current.write().unwrap_or_else(PoisonError::into_inner);
            let updated = guard.apply_edge_patch(patch);
            *guard = updated.clone();
            updated
        };
        self.persist_config(&next)?;
        let _ = self.updates_tx.send(next.clone());
        Ok(next)
    }

    /// Updates `debug.webview_debugging_enabled`, atomically persists it, and notifies subscribers.
    /// Returns `(previous_enabled, updated_config)`.
    pub fn set_webview_debugging(&self, enabled: bool) -> io::Result<(bool, SideVeilConfig)> {
        let (prev, next) = {
            let mut guard = self.current.write().unwrap_or_else(PoisonError::into_inner);
            let prev = guard.debug.webview_debugging_enabled;
            let mut updated = guard.clone();
            updated.debug.webview_debugging_enabled = enabled;
            let updated = updated.sanitized();
            *guard = updated.clone();
            (prev, updated)
        };
        self.persist_config(&next)?;
        let _ = self.updates_tx.send(next.clone());
        Ok((prev, next))
    }

    /// Spawns a Linux `inotify` async watcher on the config parent directory for zero-poll reload.
    pub fn spawn_watcher(self: &Arc<Self>) -> io::Result<tokio::task::JoinHandle<()>> {
        let parent = self
            .path
            .parent()
            .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidInput, "config has no parent"))?;
        fs::create_dir_all(parent)?;
        let watcher = InotifyDirWatcher::new(parent, DIR_CHANGE_MASK)?;
        let target_name = self
            .path
            .file_name()
            .map(|n| n.as_bytes().to_vec())
            .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidInput, "config has no filename"))?;
        let store = Arc::clone(self);

        Ok(tokio::spawn(async move {
            loop {
                match watcher.wait_for_name(&target_name).await {
                    Ok(()) => {
                        tokio::time::sleep(Duration::from_millis(60)).await;
                        let _ = store.reload();
                    }
                    Err(err) => {
                        store
                            .logs
                            .warn(format!("Config inotify watcher stopped: {err}"));
                        break;
                    }
                }
            }
        }))
    }

    fn persist_config(&self, config: &SideVeilConfig) -> io::Result<()> {
        let parent = self
            .path
            .parent()
            .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidInput, "config has no parent"))?;
        fs::create_dir_all(parent)?;

        let existing_owner = fs::metadata(&self.path).ok().map(|m| (m.uid(), m.gid()));
        let serialized = serde_json::to_string_pretty(config)
            .map_err(|e| io::Error::new(io::ErrorKind::InvalidData, e))?;

        let tmp_name = format!(
            ".{}.tmp.{}",
            self.path
                .file_name()
                .and_then(|n| n.to_str())
                .unwrap_or("config.json"),
            std::process::id()
        );
        let tmp_path = parent.join(tmp_name);

        let mut file = OpenOptions::new()
            .create(true)
            .write(true)
            .truncate(true)
            .open(&tmp_path)?;
        file.write_all(serialized.as_bytes())?;
        file.write_all(b"\n")?;
        file.sync_all()?;
        fs::set_permissions(&tmp_path, fs::Permissions::from_mode(0o644))?;
        if let Some((uid, gid)) = existing_owner {
            let _ = chown_path(&tmp_path, uid, gid);
        }
        drop(file);
        fs::rename(&tmp_path, &self.path)?;
        if let Ok(dir) = File::open(parent) {
            let _ = dir.sync_all();
        }
        Ok(())
    }
}

fn load_from_disk(path: &Path, logs: &LogRing) -> Option<SideVeilConfig> {
    let raw = match fs::read_to_string(path) {
        Ok(content) => content,
        Err(err) if err.kind() == io::ErrorKind::NotFound => return Some(SideVeilConfig::default()),
        Err(err) => {
            logs.warn(format!("Failed to read {}: {err}", path.display()));
            return None;
        }
    };
    match serde_json::from_str::<SideVeilConfig>(&raw) {
        Ok(cfg) => Some(cfg.sanitized()),
        Err(err) => {
            logs.warn(format!(
                "Invalid JSON in {} (keeping previous config): {err}",
                path.display()
            ));
            None
        }
    }
}

fn chown_path(path: &Path, uid: u32, gid: u32) -> io::Result<()> {
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

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn atomic_patch_and_inotify_reload_work_end_to_end() {
        let dir = std::env::temp_dir().join(format!(
            "sideveil-cfg-test-{}-{}",
            std::process::id(),
            std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .unwrap_or_default()
                .as_nanos()
        ));
        fs::create_dir_all(&dir).expect("create temp dir");
        let cfg_path = dir.join("config.json");
        let logs = Arc::new(LogRing::new());
        let store = ConfigStore::open(cfg_path.clone(), Arc::clone(&logs));
        let mut rx = store.subscribe();
        let watcher = store.spawn_watcher().expect("spawn inotify watcher");

        // Atomic write via rename (simulating KernelSU WebUI writeFileAtomic).
        let mut modified = SideVeilConfig::default();
        modified.panel.theme_id = "modern-saas-dark".to_owned();
        modified.panel.corner_radius_dp = 30;
        let tmp = dir.join("config.json.external");
        fs::write(&tmp, serde_json::to_string_pretty(&modified).unwrap()).expect("write tmp");
        fs::rename(&tmp, &cfg_path).expect("atomic rename");

        let updated = tokio::time::timeout(Duration::from_secs(2), rx.recv())
            .await
            .expect("inotify notification timed out")
            .expect("broadcast recv");
        assert_eq!(updated.panel.theme_id, "modern-saas-dark");
        assert_eq!(updated.panel.corner_radius_dp, 30);

        // Corrupt JSON must not clobber last known good config.
        fs::write(&cfg_path, "{broken json").expect("write corrupted");
        assert_eq!(store.reload().panel.theme_id, "modern-saas-dark");

        // Partial appearance patch persists cleanly.
        let patched = store
            .patch_appearance(&AppearancePatchParams {
                color_mode: Some("light".to_owned()),
                ..Default::default()
            })
            .expect("patch appearance");
        assert_eq!(patched.panel.color_mode, "light");
        assert_eq!(patched.panel.theme_id, "modern-saas-dark");

        watcher.abort();
        let _ = fs::remove_dir_all(&dir);
    }
}
