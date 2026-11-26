//! Authoritative disk configuration store with atomic persistence and Linux `inotify` hot reload.

use std::fs;
use std::io;
use std::os::unix::ffi::OsStrExt;
use std::os::unix::fs::MetadataExt;
use std::path::{Path, PathBuf};
use std::sync::{Arc, PoisonError, RwLock};
use std::time::Duration;

use sideveil_protocol::config::{AppearancePatchParams, EdgePatchParams, SideVeilConfig};
use tokio::sync::{Mutex as AsyncMutex, broadcast};

use crate::fs_util::write_file_atomic;
use crate::inotify::{DIR_CHANGE_MASK, InotifyDirWatcher};
use crate::log_ring::LogRing;

/// Thread-safe authoritative store for `/data/system/sideveil/config.json`.
#[derive(Debug)]
pub struct ConfigStore {
    path: PathBuf,
    current: RwLock<SideVeilConfig>,
    persist_lock: AsyncMutex<()>,
    updates_tx: broadcast::Sender<SideVeilConfig>,
    logs: Arc<LogRing>,
}

impl ConfigStore {
    /// Opens `path`, loading and sanitizing existing JSON or seeding defaults on disk.
    pub fn open(path: PathBuf, logs: Arc<LogRing>) -> Arc<Self> {
        let (updates_tx, _) = broadcast::channel(16);
        let initial = load_from_disk(&path, &logs).unwrap_or_default();
        let store = Arc::new(Self {
            path,
            current: RwLock::new(initial.clone()),
            persist_lock: AsyncMutex::new(()),
            updates_tx,
            logs,
        });
        if !store.path.exists()
            && let Err(err) = persist_config_sync(&store.path, &initial)
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

    /// Applies a partial appearance patch, atomically persists it off-worker, and notifies subscribers.
    pub async fn patch_appearance(
        &self,
        patch: &AppearancePatchParams,
    ) -> io::Result<SideVeilConfig> {
        self.apply_and_persist(|cur| cur.apply_appearance_patch(patch))
            .await
    }

    /// Applies an edge handle position patch (side/topPercent), atomically persists it off-worker, and notifies subscribers.
    pub async fn patch_edge(&self, patch: &EdgePatchParams) -> io::Result<SideVeilConfig> {
        self.apply_and_persist(|cur| cur.apply_edge_patch(patch))
            .await
    }

    /// Updates `debug.webview_debugging_enabled`, atomically persists it off-worker, and notifies subscribers.
    /// Returns `(previous_enabled, updated_config)`.
    pub async fn set_webview_debugging(&self, enabled: bool) -> io::Result<(bool, SideVeilConfig)> {
        let _persist_guard = self.persist_lock.lock().await;
        let (prev, updated) = {
            let guard = self.current.read().unwrap_or_else(PoisonError::into_inner);
            let prev = guard.debug.webview_debugging_enabled;
            let mut next = guard.clone();
            next.debug.webview_debugging_enabled = enabled;
            (prev, next.sanitized())
        };
        let path = self.path.clone();
        let disk_cfg = updated.clone();
        tokio::task::spawn_blocking(move || persist_config_sync(&path, &disk_cfg))
            .await
            .map_err(|err| io::Error::other(format!("config persist task failed: {err}")))??;
        {
            let mut guard = self.current.write().unwrap_or_else(PoisonError::into_inner);
            *guard = updated.clone();
        }
        drop(_persist_guard);
        let _ = self.updates_tx.send(updated.clone());
        Ok((prev, updated))
    }

    async fn apply_and_persist<F>(&self, compute: F) -> io::Result<SideVeilConfig>
    where
        F: FnOnce(&SideVeilConfig) -> SideVeilConfig,
    {
        let _persist_guard = self.persist_lock.lock().await;
        let updated = {
            let guard = self.current.read().unwrap_or_else(PoisonError::into_inner);
            compute(&guard)
        };
        let path = self.path.clone();
        let disk_cfg = updated.clone();
        tokio::task::spawn_blocking(move || persist_config_sync(&path, &disk_cfg))
            .await
            .map_err(|err| io::Error::other(format!("config persist task failed: {err}")))??;
        {
            let mut guard = self.current.write().unwrap_or_else(PoisonError::into_inner);
            *guard = updated.clone();
        }
        drop(_persist_guard);
        let _ = self.updates_tx.send(updated.clone());
        Ok(updated)
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
}

fn persist_config_sync(path: &Path, config: &SideVeilConfig) -> io::Result<()> {
    let existing_owner = fs::metadata(path).ok().map(|m| (m.uid(), m.gid()));
    let mut serialized = serde_json::to_string_pretty(config)
        .map_err(|e| io::Error::new(io::ErrorKind::InvalidData, e))?;
    serialized.push('\n');
    write_file_atomic(path, serialized.as_bytes(), 0o644, existing_owner, true)
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

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn failed_persist_does_not_mutate_in_memory_config() {
        let dir = std::env::temp_dir().join(format!(
            "sideveil-cfg-fail-{}-{}",
            std::process::id(),
            std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .unwrap_or_default()
                .as_nanos()
        ));
        fs::create_dir_all(&dir).expect("create temp dir");
        // Point config path at an existing directory so atomic rename fails with EISDIR.
        let bad_target = dir.join("config_dir");
        fs::create_dir_all(&bad_target).expect("create dir at target");
        let logs = Arc::new(LogRing::new());
        let store = ConfigStore::open(bad_target, logs);
        let before = store.current();

        let err = store
            .patch_appearance(&AppearancePatchParams {
                color_mode: Some("light".to_owned()),
                ..Default::default()
            })
            .await;
        assert!(err.is_err());
        assert_eq!(store.current(), before);

        let _ = fs::remove_dir_all(&dir);
    }

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
            .await
            .expect("patch appearance");
        assert_eq!(patched.panel.color_mode, "light");
        assert_eq!(patched.panel.theme_id, "modern-saas-dark");

        watcher.abort();
        let _ = fs::remove_dir_all(&dir);
    }
}
