//! Open plugin registry with `inotify` discovery, atomic overrides/MRU persistence, and scope routing.

mod installer;
mod overrides;
mod query;
mod store;
mod web_embed;

use std::collections::{BTreeMap, HashMap};
use std::fs;
use std::io;
use std::path::{Path, PathBuf};
use std::sync::{Arc, PoisonError, RwLock};
use std::time::Duration;

use sideveil_protocol::plugin::{PluginCreateWebEmbedParams, PluginManifest, PluginOverridesFile};
use tokio::sync::broadcast;

use self::store::{load_overrides, scan_plugins_dir};
use crate::inotify::{DIR_CHANGE_MASK, InotifyDirWatcher};
use crate::log_ring::LogRing;

#[derive(Debug, Clone)]
pub(super) struct LoadedPlugin {
    pub(super) manifest: PluginManifest,
    pub(super) root_dir: Option<PathBuf>,
    pub(super) builtin: bool,
}

#[derive(Debug)]
pub(super) struct RegistryInner {
    pub(super) plugins: BTreeMap<String, LoadedPlugin>,
    pub(super) overrides: PluginOverridesFile,
}

#[derive(Debug)]
pub struct PluginRegistry {
    plugins_dir: PathBuf,
    pub(super) overrides_path: PathBuf,
    pub(super) inner: RwLock<RegistryInner>,
    pub(super) updates_tx: broadcast::Sender<()>,
    pub(super) logs: Arc<LogRing>,
}

impl PluginRegistry {
    pub fn open(plugins_dir: PathBuf, overrides_path: PathBuf, logs: Arc<LogRing>) -> Arc<Self> {
        let _ = fs::create_dir_all(&plugins_dir);
        let overrides = load_overrides(&overrides_path, &logs);
        let (updates_tx, _) = broadcast::channel(16);
        let registry = Arc::new(Self {
            plugins_dir,
            overrides_path,
            inner: RwLock::new(RegistryInner {
                plugins: BTreeMap::new(),
                overrides,
            }),
            updates_tx,
            logs,
        });
        let _ = registry.rescan();
        registry
    }

    #[must_use]
    pub fn subscribe(&self) -> broadcast::Receiver<()> {
        self.updates_tx.subscribe()
    }

    /// Rescans `/data/system/sideveil/plugins/<plugin_id>/manifest.json` and refreshes registry.
    pub fn rescan(&self) -> usize {
        let discovered = scan_plugins_dir(&self.plugins_dir, &self.logs);

        let count = discovered.len();
        let changed = {
            let mut guard = self.inner.write().unwrap_or_else(PoisonError::into_inner);
            let old_ids: Vec<(&String, &PluginManifest)> = guard
                .plugins
                .iter()
                .map(|(k, v)| (k, &v.manifest))
                .collect();
            let new_ids: Vec<(&String, &PluginManifest)> =
                discovered.iter().map(|(k, v)| (k, &v.manifest)).collect();
            let diff = old_ids != new_ids;
            guard.plugins = discovered;
            diff
        };
        if changed {
            self.logs.info(format!(
                "Plugin registry updated ({count} plugin(s) loaded)"
            ));
            let _ = self.updates_tx.send(());
        }
        count
    }

    /// Validates and atomically installs a plugin package directory, then rescans immediately.
    pub fn install_from_dir(&self, source_dir: &Path) -> Result<PluginManifest, String> {
        let manifest = installer::install_plugin_dir(&self.plugins_dir, source_dir)?;
        let _ = self.rescan();
        Ok(manifest)
    }

    /// Creates a zero-privilege `web-embed` plugin atomically, then rescans immediately.
    pub fn create_web_embed(
        &self,
        params: &PluginCreateWebEmbedParams,
    ) -> Result<PluginManifest, String> {
        let manifest = web_embed::create_web_embed_plugin(&self.plugins_dir, params)?;
        let _ = self.rescan();
        Ok(manifest)
    }

    /// Removes an external plugin directory, clears its persisted overrides/MRU and icon cache,
    /// and rescans the registry immediately.
    pub fn uninstall_plugin(&self, plugin_id: &str, cache_dir: &Path) -> Result<(), String> {
        web_embed::uninstall_plugin_dir(&self.plugins_dir, plugin_id)?;
        self.purge_plugin_overrides(plugin_id);
        prune_plugin_icon_cache(cache_dir, plugin_id);
        let _ = self.rescan();
        Ok(())
    }

    pub fn spawn_watcher(self: &Arc<Self>) -> io::Result<tokio::task::JoinHandle<()>> {
        fs::create_dir_all(&self.plugins_dir)?;
        let watcher = InotifyDirWatcher::new(&self.plugins_dir, DIR_CHANGE_MASK)?;
        let mut child_watches: HashMap<PathBuf, libc::c_int> = HashMap::new();
        watcher.sync_subdirectories(&self.plugins_dir, DIR_CHANGE_MASK, &mut child_watches);
        let registry = Arc::clone(self);
        Ok(tokio::spawn(async move {
            loop {
                match watcher.wait_any().await {
                    Ok(()) => {
                        tokio::time::sleep(Duration::from_millis(150)).await;
                        watcher.drain_pending();
                        watcher.sync_subdirectories(
                            &registry.plugins_dir,
                            DIR_CHANGE_MASK,
                            &mut child_watches,
                        );
                        let _ = registry.rescan();
                    }
                    Err(err) => {
                        registry
                            .logs
                            .warn(format!("Plugin directory inotify watcher stopped: {err}"));
                        break;
                    }
                }
            }
        }))
    }
}

fn prune_plugin_icon_cache(cache_dir: &Path, plugin_id: &str) {
    let icons_dir = cache_dir.join("icons");
    let prefix = format!("{plugin_id}_");
    if let Ok(entries) = fs::read_dir(&icons_dir) {
        for entry in entries.flatten() {
            if let Some(name) = entry.file_name().to_str()
                && name.starts_with(&prefix)
            {
                let _ = fs::remove_file(entry.path());
            }
        }
    }
}
