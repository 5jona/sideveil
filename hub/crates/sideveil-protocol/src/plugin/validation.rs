//! Plugin manifest, ID, and relative asset path validation.

use thiserror::Error;

use super::url_validation::validate_network_url;
use super::{PluginBackendKind, PluginCapability, PluginManifest};

/// Validation failures returned by [`validate_plugin_id`], [`validate_network_url`],
/// and [`PluginManifest::validate`].
#[derive(Debug, Error, PartialEq, Eq)]
pub enum ManifestValidationError {
    /// Plugin identifier does not satisfy `[a-z][a-z0-9.-]{0,62}[a-z0-9]`.
    #[error(
        "invalid plugin id '{0}': must be 2..=64 ASCII [a-z0-9.-] chars starting with a letter"
    )]
    InvalidId(String),
    /// Manifest display name is empty or whitespace-only.
    #[error("plugin name must not be blank")]
    BlankName,
    /// Manifest semantic version string is empty or whitespace-only.
    #[error("plugin version must not be blank")]
    BlankVersion,
    /// Manifest HTML entry path is empty, absolute, or attempts traversal.
    #[error("plugin entry '{0}' must be a relative path without '..' or leading '/'")]
    InvalidEntry(String),
    /// Manifest icon path is empty, absolute, or attempts traversal.
    #[error("plugin icon '{0}' must be a relative path without '..' or leading '/'")]
    InvalidIcon(String),
    /// Network or embed URL failed scheme, authority, host, or port validation.
    #[error("invalid network URL '{url}': {reason}")]
    InvalidUrl {
        /// Original rejected URL string.
        url: String,
        /// Specific syntax or policy violation reason.
        reason: String,
    },
    /// Cleartext `http://` URL was supplied without `allowCleartext = true`.
    #[error("cleartext HTTP URL '{0}' requires allowCleartext=true")]
    CleartextNotAllowed(String),
    /// Cleartext `http://` URL targets a public host outside loopback/RFC1918/LAN ranges.
    #[error("cleartext HTTP URL '{0}' is restricted to loopback, RFC1918, or .lan/.local hosts")]
    PublicCleartextForbidden(String),
    /// Web-embed manifest attempted to attach a UDS backend.
    #[error("web-embed plugins must declare backend=none")]
    EmbedBackendForbidden,
    /// Web-embed manifest declared a capability beyond loopback/LAN network access.
    #[error("web-embed plugins cannot declare privileged capability '{0:?}'")]
    EmbedPrivilegedCapabilityForbidden(PluginCapability),
    /// Web-embed tuning parameter fell outside its permitted bounds.
    #[error("invalid embed parameter '{field}': {reason}")]
    InvalidEmbedParam {
        /// Camel-case JSON parameter name.
        field: String,
        /// Expected range or constraint description.
        reason: String,
    },
}

/// Validates a plugin identifier for safe use in directory names and isolated virtual origins.
pub fn validate_plugin_id(id: &str) -> Result<(), ManifestValidationError> {
    let bytes = id.as_bytes();
    if bytes.len() < 2 || bytes.len() > 64 {
        return Err(ManifestValidationError::InvalidId(id.to_owned()));
    }
    if !bytes[0].is_ascii_lowercase() || !bytes[bytes.len() - 1].is_ascii_alphanumeric() {
        return Err(ManifestValidationError::InvalidId(id.to_owned()));
    }
    let mut prev_sep = false;
    for &b in bytes {
        let is_sep = b == b'.' || b == b'-';
        if !(b.is_ascii_lowercase() || b.is_ascii_digit() || is_sep) {
            return Err(ManifestValidationError::InvalidId(id.to_owned()));
        }
        if is_sep && prev_sep {
            return Err(ManifestValidationError::InvalidId(id.to_owned()));
        }
        prev_sep = is_sep;
    }
    Ok(())
}

pub(super) fn is_safe_relative_asset_path(raw: &str) -> bool {
    let trimmed = raw.trim();
    !trimmed.is_empty()
        && !trimmed.starts_with('/')
        && !trimmed.contains('\\')
        && !trimmed
            .split('/')
            .any(|seg| seg.is_empty() || seg == "." || seg == ".." || seg.starts_with('.'))
}

impl PluginManifest {
    /// Validates manifest fields, asset paths, capability boundaries, and network URLs.
    pub fn validate(&self) -> Result<(), ManifestValidationError> {
        validate_plugin_id(&self.id)?;
        if self.name.trim().is_empty() {
            return Err(ManifestValidationError::BlankName);
        }
        if self.version.trim().is_empty() {
            return Err(ManifestValidationError::BlankVersion);
        }
        if let Some(icon) = &self.icon
            && !is_safe_relative_asset_path(icon)
        {
            return Err(ManifestValidationError::InvalidIcon(icon.clone()));
        }

        if let Some(embed) = &self.embed {
            if self.backend != PluginBackendKind::None {
                return Err(ManifestValidationError::EmbedBackendForbidden);
            }
            for &cap in &self.capabilities {
                if !matches!(
                    cap,
                    PluginCapability::NetworkLoopback | PluginCapability::NetworkLan
                ) {
                    return Err(ManifestValidationError::EmbedPrivilegedCapabilityForbidden(
                        cap,
                    ));
                }
            }
            validate_network_url(&embed.url, embed.allow_cleartext)?;
            if let Some(remote_url) = &embed.remote_url {
                validate_network_url(remote_url, embed.allow_cleartext)?;
            }
            for origin in &embed.allowed_origins {
                validate_network_url(origin, embed.allow_cleartext)?;
            }
            if let Some(text_zoom) = embed.text_zoom
                && !(50..=200).contains(&text_zoom)
            {
                return Err(ManifestValidationError::InvalidEmbedParam {
                    field: "textZoom".to_owned(),
                    reason: "must be within 50..=200".to_owned(),
                });
            }
            if let Some(grace) = embed.keep_alive_grace_sec
                && !(0..=1800).contains(&grace)
            {
                return Err(ManifestValidationError::InvalidEmbedParam {
                    field: "keepAliveGraceSec".to_owned(),
                    reason: "must be within 0..=1800".to_owned(),
                });
            }
            if !self.entry.trim().is_empty() && !is_safe_relative_asset_path(&self.entry) {
                return Err(ManifestValidationError::InvalidEntry(self.entry.clone()));
            }
        } else {
            if !is_safe_relative_asset_path(&self.entry) {
                return Err(ManifestValidationError::InvalidEntry(self.entry.clone()));
            }
            if let Some(network) = &self.network {
                for origin in &network.allowed_origins {
                    validate_network_url(origin, network.allow_cleartext)?;
                }
            }
        }

        Ok(())
    }
}
