//! Open plugin registry with `inotify` discovery, tiered capability grants, atomic persistence, and scope routing.

mod archive;
mod dev_url;
mod embed_override;
mod error;
mod grants;
mod installer;
mod overrides;
mod query;
mod store;
#[cfg(test)]
mod store_tests;
mod web_embed;

use std::collections::{BTreeMap, HashMap};
use std::fs;
use std::io;
use std::path::{Path, PathBuf};
use std::sync::{Arc, PoisonError, RwLock};
use std::time::Duration;

use sideveil_protocol::plugin::{
    PluginCreateWebEmbedParams, PluginInstallParams, PluginManifest, PluginOrigin,
    PluginOverridesFile,
};
use sideveil_protocol::svp::{
    decode_archive_base64, inspect_svp_bytes, is_archive_path, read_svp_file,
};
use tokio::sync::broadcast;

pub use self::error::PluginRegistryError;
pub use self::query::PluginDaemonCandidate;
use self::store::{load_overrides, scan_plugins_dirs};
use crate::inotify::{DIR_CHANGE_MASK, InotifyDirWatcher};
use crate::log_ring::LogRing;

#[derive(Debug, Clone)]
pub(super) struct LoadedPlugin {
    pub(super) manifest: PluginManifest,
    pub(super) root_dir: Option<PathBuf>,
    pub(super) asset_revision: Option<String>,
    pub(super) origin: PluginOrigin,
    pub(super) daemon_binary_fingerprint: Option<String>,
}

#[derive(Debug)]
pub(super) struct RegistryInner {
    pub(super) plugins: BTreeMap<String, LoadedPlugin>,
    pub(super) overrides: PluginOverridesFile,
}

/// Authoritative in-memory and on-disk registry of built-in, bundled, and installed SideVeil plugins.
#[derive(Debug)]
pub struct PluginRegistry {
    plugins_dir: PathBuf,
    bundled_dir: Option<PathBuf>,
    pub(super) overrides_path: PathBuf,
    pub(super) inner: RwLock<RegistryInner>,
    pub(super) updates_tx: broadcast::Sender<()>,
    pub(super) logs: Arc<LogRing>,
}

impl PluginRegistry {
    /// Opens the plugin registry without a separate bundled plugins directory.
    pub fn open(plugins_dir: PathBuf, overrides_path: PathBuf, logs: Arc<LogRing>) -> Arc<Self> {
        Self::open_with_bundled(plugins_dir, None, overrides_path, logs)
    }

    /// Opens the plugin registry with optional read-only bundled plugins directory (`<module_dir>/plugins`),
    /// loads persisted overrides, and performs an initial scan.
    pub fn open_with_bundled(
        plugins_dir: PathBuf,
        bundled_dir: Option<PathBuf>,
        overrides_path: PathBuf,
        logs: Arc<LogRing>,
    ) -> Arc<Self> {
        let _ = fs::create_dir_all(&plugins_dir);
        let overrides = load_overrides(&overrides_path, &logs);
        let (updates_tx, _) = broadcast::channel(16);
        let registry = Arc::new(Self {
            plugins_dir,
            bundled_dir,
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

    /// Subscribes to broadcast notifications fired whenever discovered plugins or overrides change.
    #[must_use]
    pub fn subscribe(&self) -> broadcast::Receiver<()> {
        self.updates_tx.subscribe()
    }

    /// Rescans bundled and installed plugin directories and refreshes the in-memory registry.
    pub fn rescan(&self) -> usize {
        let discovered =
            scan_plugins_dirs(&self.plugins_dir, self.bundled_dir.as_deref(), &self.logs);

        let count = discovered.len();
        let changed = {
            let mut guard = self.inner.write().unwrap_or_else(PoisonError::into_inner);
            let old_state: Vec<_> = guard
                .plugins
                .iter()
                .map(|(k, v)| {
                    (
                        k,
                        &v.manifest,
                        &v.asset_revision,
                        v.origin,
                        &v.daemon_binary_fingerprint,
                    )
                })
                .collect();
            let new_state: Vec<_> = discovered
                .iter()
                .map(|(k, v)| {
                    (
                        k,
                        &v.manifest,
                        &v.asset_revision,
                        v.origin,
                        &v.daemon_binary_fingerprint,
                    )
                })
                .collect();
            let diff = old_state != new_state;
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

    /// Validates and atomically installs a plugin package directory, reconciles grants, and rescans.
    pub fn install_from_dir(
        &self,
        source_dir: &Path,
    ) -> Result<PluginManifest, PluginRegistryError> {
        let (manifest, fingerprint) = installer::install_plugin_dir(
            &self.plugins_dir,
            self.bundled_dir.as_deref(),
            source_dir,
        )?;
        self.reconcile_install_grants(&manifest.id, &manifest, fingerprint, None, false)?;
        let _ = self.rescan();
        let _ = self.updates_tx.send(());
        Ok(manifest)
    }

    /// Installs a plugin from a local directory, `.svp` archive path, or base64-encoded `.svp` payload.
    pub fn install_from_params(
        &self,
        params: &PluginInstallParams,
    ) -> Result<PluginManifest, PluginRegistryError> {
        let bundled = self.bundled_dir.as_deref();
        let (manifest, fingerprint) = if let Some(b64) = params
            .archive_base64
            .as_deref()
            .map(str::trim)
            .filter(|s| !s.is_empty())
        {
            let bytes = decode_archive_base64(b64)
                .map_err(|err| PluginRegistryError::InstallConstraint(err.to_string()))?;
            let archive = inspect_svp_bytes(&bytes)
                .map_err(|err| PluginRegistryError::InstallConstraint(err.to_string()))?;
            installer::install_plugin_archive(&self.plugins_dir, bundled, &archive)?
        } else {
            let raw_path = params
                .source_path
                .as_deref()
                .or(params.source_dir.as_deref())
                .map(str::trim)
                .filter(|s| !s.is_empty())
                .ok_or_else(|| {
                    PluginRegistryError::InstallConstraint(
                        "specify 'sourcePath', 'sourceDir', or 'archiveBase64'".to_owned(),
                    )
                })?;
            let path = Path::new(raw_path);
            if is_archive_path(path) {
                let archive = read_svp_file(path)
                    .map_err(|err| PluginRegistryError::InstallConstraint(err.to_string()))?;
                installer::install_plugin_archive(&self.plugins_dir, bundled, &archive)?
            } else {
                installer::install_plugin_dir(&self.plugins_dir, bundled, path)?
            }
        };

        self.reconcile_install_grants(
            &manifest.id,
            &manifest,
            fingerprint,
            params.granted_capabilities.as_deref(),
            params.approve_daemon,
        )?;
        let _ = self.rescan();
        let _ = self.updates_tx.send(());
        Ok(manifest)
    }

    /// Creates a zero-privilege `web-embed` plugin atomically, then rescans immediately.
    pub fn create_web_embed(
        &self,
        params: &PluginCreateWebEmbedParams,
    ) -> Result<PluginManifest, PluginRegistryError> {
        let manifest = web_embed::create_web_embed_plugin(
            &self.plugins_dir,
            self.bundled_dir.as_deref(),
            params,
        )?;
        let _ = self.rescan();
        Ok(manifest)
    }

    /// Removes an installed plugin directory, clears its data directory, persisted overrides/MRU,
    /// and icon cache, then rescans the registry immediately.
    pub fn uninstall_plugin(
        &self,
        plugin_id: &str,
        cache_dir: &Path,
        data_dir: Option<&Path>,
    ) -> Result<(), PluginRegistryError> {
        web_embed::uninstall_plugin_dir(&self.plugins_dir, plugin_id)?;
        if let Some(dir) = data_dir {
            web_embed::remove_plugin_data_dir(dir, plugin_id);
        }
        self.purge_plugin_overrides(plugin_id);
        prune_plugin_icon_cache(cache_dir, plugin_id);
        let _ = self.rescan();
        Ok(())
    }

    /// Spawns a zero-polling Linux `inotify` watcher monitoring the installed plugins directory tree.
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
