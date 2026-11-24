//! Pre-install inspection for `.svp` archives, base64 payloads, and plugin directories.

use std::fs;
use std::path::Path;
use std::sync::PoisonError;

use sideveil_protocol::plugin::{
    PluginBackendKind, PluginCapability, PluginCapabilityReviewItem, PluginInspectArchiveParams,
    PluginInspectArchiveResult, PluginManifest,
};
use sideveil_protocol::svp::{
    decode_archive_base64, inspect_svp_bytes, is_archive_path, read_svp_file,
};

use super::PluginRegistry;
use super::error::PluginRegistryError;
use super::grants::compute_effective_grants;

impl PluginRegistry {
    /// Inspects a `.svp` archive (via local path or base64) or plugin directory without installing it.
    pub fn inspect_archive(
        &self,
        params: &PluginInspectArchiveParams,
    ) -> Result<PluginInspectArchiveResult, PluginRegistryError> {
        let (manifest, mut warnings) = if let Some(b64) = params
            .archive_base64
            .as_deref()
            .map(str::trim)
            .filter(|s| !s.is_empty())
        {
            let bytes = decode_archive_base64(b64)
                .map_err(|err| PluginRegistryError::InstallConstraint(err.to_string()))?;
            let archive = inspect_svp_bytes(&bytes)
                .map_err(|err| PluginRegistryError::InstallConstraint(err.to_string()))?;
            (archive.manifest, archive.warnings)
        } else if let Some(raw_path) = params
            .path
            .as_deref()
            .map(str::trim)
            .filter(|s| !s.is_empty())
        {
            let path = Path::new(raw_path);
            if is_archive_path(path) {
                let archive = read_svp_file(path)
                    .map_err(|err| PluginRegistryError::InstallConstraint(err.to_string()))?;
                (archive.manifest, archive.warnings)
            } else if path.is_dir() {
                inspect_plugin_directory(path)?
            } else {
                return Err(PluginRegistryError::InvalidSourceDir(path.to_path_buf()));
            }
        } else {
            return Err(PluginRegistryError::InstallConstraint(
                "specify either 'path' or 'archiveBase64'".to_owned(),
            ));
        };

        let (existing_version, prev_caps, prev_granted, prev_daemon_approved) = {
            let guard = self.inner.read().unwrap_or_else(PoisonError::into_inner);
            match guard.plugins.get(&manifest.id) {
                Some(loaded) => {
                    let ov = guard.overrides.overrides.get(&manifest.id);
                    let grants = compute_effective_grants(loaded, ov);
                    (
                        Some(loaded.manifest.version.clone()),
                        loaded.manifest.effective_capabilities().to_vec(),
                        grants.granted,
                        grants.daemon_approved,
                    )
                }
                None => (None, Vec::new(), Vec::new(), false),
            }
        };

        let is_upgrade = existing_version.is_some();
        let has_daemon = manifest.daemon.is_some()
            && manifest.effective_backend() == PluginBackendKind::UdsNative;
        if has_daemon {
            warnings.push(
                "Plugin declares a native uds-native daemon that requires explicit approval before running."
                    .to_owned(),
            );
        }

        let capabilities: Vec<PluginCapabilityReviewItem> = manifest
            .effective_capabilities()
            .iter()
            .copied()
            .map(|cap: PluginCapability| PluginCapabilityReviewItem {
                capability: cap,
                risk: cap.risk_tier(),
                requires_approval: cap.requires_explicit_grant(),
                is_new: !is_upgrade || !prev_caps.contains(&cap),
                already_granted: is_upgrade && prev_granted.contains(&cap),
            })
            .collect();

        Ok(PluginInspectArchiveResult {
            manifest,
            existing_version,
            is_upgrade,
            capabilities,
            has_daemon,
            daemon_already_approved: is_upgrade && has_daemon && prev_daemon_approved,
            warnings,
        })
    }
}

fn inspect_plugin_directory(
    dir: &Path,
) -> Result<(PluginManifest, Vec<String>), PluginRegistryError> {
    let manifest_path = dir.join("manifest.json");
    let raw = fs::read_to_string(&manifest_path).map_err(|source| PluginRegistryError::Io {
        context: format!("failed to read '{}'", manifest_path.display()),
        source,
    })?;
    let manifest: PluginManifest =
        serde_json::from_str(&raw).map_err(|source| PluginRegistryError::Json {
            context: format!("invalid manifest JSON in '{}'", manifest_path.display()),
            source,
        })?;
    manifest.validate()?;

    let mut warnings = Vec::new();
    if manifest.embed.is_none() {
        let entry = manifest.effective_entry();
        if !dir.join(entry).is_file() {
            return Err(PluginRegistryError::InstallConstraint(format!(
                "manifest entry '{entry}' missing in source directory"
            )));
        }
        if !entry.starts_with("web/") {
            warnings.push(format!(
                "entry '{entry}' does not reside under 'web/'; asset server restricts assets to web/"
            ));
        }
    }
    if let Some(daemon) = &manifest.daemon
        && !dir.join(&daemon.binary).is_file()
    {
        return Err(PluginRegistryError::InstallConstraint(format!(
            "declared daemon binary '{}' missing in source directory",
            daemon.binary
        )));
    }
    Ok((manifest, warnings))
}
