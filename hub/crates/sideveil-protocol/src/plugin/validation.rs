//! Plugin manifest, ID, daemon specification, package allowlist, and asset path validation.

use thiserror::Error;

use super::url_validation::{validate_network_url, validate_origin_for_capabilities};
use super::{PluginBackendKind, PluginCapability, PluginManifest};
use crate::{MANIFEST_SCHEMA_VERSION, PLUGIN_API_VERSION};

/// Validation failures returned by [`validate_plugin_id`], [`validate_network_url`],
/// [`validate_daemon_binary_path`], [`validate_android_package_name`], and [`PluginManifest::validate`].
#[derive(Debug, Error, PartialEq, Eq)]
pub enum ManifestValidationError {
    /// Plugin identifier does not satisfy `[a-z][a-z0-9.-]{0,62}[a-z0-9]`.
    #[error(
        "invalid plugin id '{0}': must be 2..=64 ASCII [a-z0-9.-] chars starting with a letter"
    )]
    InvalidId(String),
    /// Manifest `schemaVersion` is 0 or exceeds supported [`MANIFEST_SCHEMA_VERSION`].
    #[error(
        "unsupported manifest schemaVersion {declared} (supported: 1..={max_supported}); upgrade SideVeil or fix manifest.json"
    )]
    UnsupportedSchemaVersion { declared: u32, max_supported: u32 },
    /// Manifest `apiVersion` is 0 or exceeds supported [`PLUGIN_API_VERSION`].
    #[error(
        "unsupported plugin apiVersion {declared} (supported: 1..={max_supported}); upgrade SideVeil or lower apiVersion"
    )]
    UnsupportedApiVersion { declared: u32, max_supported: u32 },
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
    /// Manifest `daemon.binary` is empty, absolute, contains NUL, or attempts traversal.
    #[error("invalid daemon.binary '{0}': must be a safe relative path inside the plugin root")]
    InvalidDaemonBinary(String),
    /// Manifest `daemon.env` attempted to set a forbidden loader or system environment variable.
    #[error(
        "forbidden daemon.env variable '{0}': loader and system variables cannot be overridden"
    )]
    ForbiddenDaemonEnvVar(String),
    /// Manifest declared `daemon` without `backend = "uds-native"`.
    #[error("daemon specification is only valid when backend is 'uds-native'")]
    DaemonRequiresUdsNative,
    /// Manifest `backendPackages` entry is not a valid Android package identifier.
    #[error("invalid Android package name '{0}' in backendPackages")]
    InvalidBackendPackage(String),
    /// Network or embed URL failed scheme, authority, host, or port validation.
    #[error("invalid network URL '{url}': {reason}")]
    InvalidUrl {
        /// Original rejected URL string.
        url: String,
        /// Specific syntax or policy violation reason.
        reason: String,
    },
    /// Cleartext `http://` or `ws://` URL was supplied without `allowCleartext = true`.
    #[error("cleartext URL '{0}' requires allowCleartext=true")]
    CleartextNotAllowed(String),
    /// Cleartext `http://` or `ws://` URL targets a public host outside loopback/RFC1918/LAN ranges.
    #[error("cleartext URL '{0}' is restricted to loopback, RFC1918, or .lan/.local hosts")]
    PublicCleartextForbidden(String),
    /// Declared `network.allowedOrigins` entry is not covered by declared network capabilities.
    #[error("network origin '{url}' requires capability {required}")]
    OriginCapabilityMismatch {
        /// Origin string declared in `network.allowedOrigins`.
        url: String,
        /// Required capability description.
        required: &'static str,
    },
    /// Web-embed manifest attempted to attach a UDS backend.
    #[error("web-embed plugins must declare backend=none")]
    EmbedBackendForbidden,
    /// Web-embed manifest declared a capability beyond loopback/LAN/internet network access.
    #[error("web-embed plugins cannot declare privileged capability '{0:?}'")]
    EmbedPrivilegedCapabilityForbidden(PluginCapability),
    /// Manifest tuning parameter fell outside its permitted bounds.
    #[error("invalid parameter '{field}': {reason}")]
    InvalidParam {
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

/// Validates a `daemon.binary` relative path inside a plugin package.
pub fn validate_daemon_binary_path(raw: &str) -> Result<(), ManifestValidationError> {
    if raw.contains('\0') || !is_safe_relative_asset_path(raw) {
        return Err(ManifestValidationError::InvalidDaemonBinary(raw.to_owned()));
    }
    Ok(())
}

/// Returns `true` when `key` is an invalid or security-sensitive environment variable name
/// forbidden inside `manifest.daemon.env`.
#[must_use]
pub fn is_forbidden_daemon_env_key(key: &str) -> bool {
    let trimmed = key.trim();
    if trimmed.is_empty() || trimmed.len() > 128 {
        return true;
    }
    let bytes = trimmed.as_bytes();
    if !(bytes[0].is_ascii_alphabetic() || bytes[0] == b'_') {
        return true;
    }
    if !bytes
        .iter()
        .all(|b| b.is_ascii_alphanumeric() || *b == b'_')
    {
        return true;
    }
    let upper = trimmed.to_ascii_uppercase();
    upper.starts_with("LD_")
        || upper.starts_with("ANDROID_")
        || upper.starts_with("SIDEVEIL_")
        || matches!(
            upper.as_str(),
            "PATH"
                | "HOME"
                | "SHELL"
                | "USER"
                | "LOGNAME"
                | "TMPDIR"
                | "IFS"
                | "ENV"
                | "BASH_ENV"
                | "CLASSPATH"
                | "BOOTCLASSPATH"
                | "DEX2OATBOOTCLASSPATH"
        )
}

/// Validates an Android package identifier (e.g. `com.example.app`) for `backendPackages`.
pub fn validate_android_package_name(pkg: &str) -> Result<(), ManifestValidationError> {
    let trimmed = pkg.trim();
    if trimmed.is_empty() || trimmed.len() > 255 {
        return Err(ManifestValidationError::InvalidBackendPackage(
            pkg.to_owned(),
        ));
    }
    let segments: Vec<&str> = trimmed.split('.').collect();
    if segments.len() < 2 {
        return Err(ManifestValidationError::InvalidBackendPackage(
            pkg.to_owned(),
        ));
    }
    for seg in segments {
        let bytes = seg.as_bytes();
        if bytes.is_empty()
            || !bytes[0].is_ascii_alphabetic()
            || !bytes
                .iter()
                .all(|b| b.is_ascii_alphanumeric() || *b == b'_')
        {
            return Err(ManifestValidationError::InvalidBackendPackage(
                pkg.to_owned(),
            ));
        }
    }
    Ok(())
}

pub(super) fn is_safe_relative_asset_path(raw: &str) -> bool {
    let trimmed = raw.trim();
    !trimmed.is_empty()
        && !trimmed.starts_with('/')
        && !trimmed.contains('\\')
        && !trimmed.contains('\0')
        && !trimmed
            .split('/')
            .any(|seg| seg.is_empty() || seg == "." || seg == ".." || seg.starts_with('.'))
}

impl PluginManifest {
    /// Validates manifest schema/API versions, asset paths, daemon spec, capabilities, and URLs.
    pub fn validate(&self) -> Result<(), ManifestValidationError> {
        validate_plugin_id(&self.id)?;
        let schema_ver = self.effective_schema_version();
        if schema_ver == 0 || schema_ver > MANIFEST_SCHEMA_VERSION {
            return Err(ManifestValidationError::UnsupportedSchemaVersion {
                declared: schema_ver,
                max_supported: MANIFEST_SCHEMA_VERSION,
            });
        }
        let api_ver = self.effective_api_version();
        if api_ver == 0 || api_ver > PLUGIN_API_VERSION {
            return Err(ManifestValidationError::UnsupportedApiVersion {
                declared: api_ver,
                max_supported: PLUGIN_API_VERSION,
            });
        }
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

        self.validate_surface_and_triggers()?;
        self.validate_backend_and_daemon()?;
        self.validate_entry_embed_and_network()
    }

    fn validate_surface_and_triggers(&self) -> Result<(), ManifestValidationError> {
        if let Some(surface) = &self.surface {
            if let Some(w) = surface.width_dp
                && !(240..=720).contains(&w)
            {
                return Err(ManifestValidationError::InvalidParam {
                    field: "surface.widthDp".to_owned(),
                    reason: "must be within 240..=720".to_owned(),
                });
            }
            if let Some(h) = surface.height_percent
                && !(30..=100).contains(&h)
            {
                return Err(ManifestValidationError::InvalidParam {
                    field: "surface.heightPercent".to_owned(),
                    reason: "must be within 30..=100".to_owned(),
                });
            }
        }
        Ok(())
    }

    fn validate_backend_and_daemon(&self) -> Result<(), ManifestValidationError> {
        let backend = self.effective_backend();
        for pkg in self.effective_backend_packages() {
            validate_android_package_name(pkg)?;
        }
        if let Some(daemon) = &self.daemon {
            if backend != PluginBackendKind::UdsNative {
                return Err(ManifestValidationError::DaemonRequiresUdsNative);
            }
            validate_daemon_binary_path(&daemon.binary)?;
            if let Some(env) = &daemon.env {
                for key in env.keys() {
                    if is_forbidden_daemon_env_key(key) {
                        return Err(ManifestValidationError::ForbiddenDaemonEnvVar(key.clone()));
                    }
                }
            }
        }
        Ok(())
    }

    fn validate_entry_embed_and_network(&self) -> Result<(), ManifestValidationError> {
        let entry = self.effective_entry();
        let caps = self.effective_capabilities();
        if let Some(embed) = &self.embed {
            if self.effective_backend() != PluginBackendKind::None {
                return Err(ManifestValidationError::EmbedBackendForbidden);
            }
            for &cap in caps {
                if !matches!(
                    cap,
                    PluginCapability::NetworkLoopback
                        | PluginCapability::NetworkLan
                        | PluginCapability::NetworkInternet
                ) {
                    return Err(ManifestValidationError::EmbedPrivilegedCapabilityForbidden(
                        cap,
                    ));
                }
            }
            let allow_cleartext = embed.allow_cleartext.unwrap_or(false);
            validate_network_url(&embed.url, allow_cleartext)?;
            if let Some(remote_url) = &embed.remote_url {
                validate_network_url(remote_url, allow_cleartext)?;
            }
            for origin in embed.allowed_origins.as_deref().unwrap_or(&[]) {
                validate_network_url(origin, allow_cleartext)?;
            }
            if let Some(text_zoom) = embed.text_zoom
                && !(50..=200).contains(&text_zoom)
            {
                return Err(ManifestValidationError::InvalidParam {
                    field: "textZoom".to_owned(),
                    reason: "must be within 50..=200".to_owned(),
                });
            }
            if let Some(grace) = embed.keep_alive_grace_sec
                && !(0..=1800).contains(&grace)
            {
                return Err(ManifestValidationError::InvalidParam {
                    field: "keepAliveGraceSec".to_owned(),
                    reason: "must be within 0..=1800".to_owned(),
                });
            }
            if !entry.trim().is_empty() && !is_safe_relative_asset_path(entry) {
                return Err(ManifestValidationError::InvalidEntry(entry.to_owned()));
            }
        } else {
            if !is_safe_relative_asset_path(entry) {
                return Err(ManifestValidationError::InvalidEntry(entry.to_owned()));
            }
            if let Some(network) = &self.network {
                let allow_cleartext = network.allow_cleartext.unwrap_or(false);
                for origin in network.allowed_origins.as_deref().unwrap_or(&[]) {
                    validate_origin_for_capabilities(origin, allow_cleartext, caps)?;
                }
            }
        }
        Ok(())
    }
}
