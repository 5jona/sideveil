//! Plugin manifest, ID, asset path, and RFC1918/LAN network URL validation.

use std::net::{Ipv4Addr, Ipv6Addr};

use thiserror::Error;

use super::{PluginBackendKind, PluginCapability, PluginManifest};

#[derive(Debug, Error, PartialEq, Eq)]
pub enum ManifestValidationError {
    #[error(
        "invalid plugin id '{0}': must be 2..=64 ASCII [a-z0-9.-] chars starting with a letter"
    )]
    InvalidId(String),
    #[error("plugin name must not be blank")]
    BlankName,
    #[error("plugin version must not be blank")]
    BlankVersion,
    #[error("plugin entry '{0}' must be a relative path without '..' or leading '/'")]
    InvalidEntry(String),
    #[error("plugin icon '{0}' must be a relative path without '..' or leading '/'")]
    InvalidIcon(String),
    #[error("invalid network URL '{url}': {reason}")]
    InvalidUrl { url: String, reason: String },
    #[error("cleartext HTTP URL '{0}' requires allowCleartext=true")]
    CleartextNotAllowed(String),
    #[error("cleartext HTTP URL '{0}' is restricted to loopback, RFC1918, or .lan/.local hosts")]
    PublicCleartextForbidden(String),
    #[error("web-embed plugins must declare backend=none")]
    EmbedBackendForbidden,
    #[error("web-embed plugins cannot declare privileged capability '{0:?}'")]
    EmbedPrivilegedCapabilityForbidden(PluginCapability),
    #[error("invalid embed parameter '{field}': {reason}")]
    InvalidEmbedParam { field: String, reason: String },
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

/// Validates an HTTP(S) URL for web-embed targets or network allowlists.
///
/// Public cleartext `http://` URLs are always rejected. Loopback, RFC1918, link-local,
/// ULA IPv6, and `.lan`/`.local` cleartext `http://` URLs require `allow_cleartext == true`.
pub fn validate_network_url(
    raw: &str,
    allow_cleartext: bool,
) -> Result<String, ManifestValidationError> {
    let trimmed = raw.trim();
    if trimmed.is_empty()
        || trimmed
            .chars()
            .any(|c| c.is_whitespace() || c.is_ascii_control())
    {
        return Err(ManifestValidationError::InvalidUrl {
            url: raw.to_owned(),
            reason: "URL must be non-empty and contain no whitespace or control characters"
                .to_owned(),
        });
    }

    let (is_http, rest) = if let Some(r) = trimmed.strip_prefix("https://") {
        (false, r)
    } else if let Some(r) = trimmed.strip_prefix("http://") {
        (true, r)
    } else {
        return Err(ManifestValidationError::InvalidUrl {
            url: trimmed.to_owned(),
            reason: "URL scheme must be http:// or https://".to_owned(),
        });
    };

    let authority_end = rest.find(['/', '?', '#']).unwrap_or(rest.len());
    let authority = &rest[..authority_end];
    if authority.is_empty() || authority.contains('@') {
        return Err(ManifestValidationError::InvalidUrl {
            url: trimmed.to_owned(),
            reason: "URL authority must be non-empty and must not contain userinfo".to_owned(),
        });
    }

    let host = extract_and_validate_host(authority, trimmed)?;

    if is_http {
        if !is_private_or_lan_host(host) {
            return Err(ManifestValidationError::PublicCleartextForbidden(
                trimmed.to_owned(),
            ));
        }
        if !allow_cleartext {
            return Err(ManifestValidationError::CleartextNotAllowed(
                trimmed.to_owned(),
            ));
        }
    }

    Ok(trimmed.to_owned())
}

/// Returns `true` when `host` is a loopback, RFC1918 private IPv4, private/link-local IPv6,
/// or local mDNS/LAN hostname (`.lan`, `.local`, `.localhost`, `.home.arpa`).
#[must_use]
pub fn is_private_or_lan_host(host: &str) -> bool {
    let lower = host.to_ascii_lowercase();
    if lower == "localhost"
        || has_nonempty_label_before_suffix(&lower, ".localhost")
        || has_nonempty_label_before_suffix(&lower, ".lan")
        || has_nonempty_label_before_suffix(&lower, ".local")
        || has_nonempty_label_before_suffix(&lower, ".internal")
        || has_nonempty_label_before_suffix(&lower, ".home.arpa")
    {
        return true;
    }

    if let Ok(ip) = lower.parse::<Ipv4Addr>() {
        let [a, b, _, _] = ip.octets();
        return a == 127
            || a == 10
            || (a == 172 && (16..=31).contains(&b))
            || (a == 192 && b == 168)
            || (a == 169 && b == 254);
    }

    if let Ok(ip) = lower.parse::<Ipv6Addr>() {
        let seg0 = ip.segments()[0];
        return ip.is_loopback() || (seg0 & 0xfe00) == 0xfc00 || (seg0 & 0xffc0) == 0xfe80;
    }

    false
}

fn has_nonempty_label_before_suffix(host: &str, suffix: &str) -> bool {
    host.strip_suffix(suffix)
        .is_some_and(|prefix| !prefix.is_empty() && !prefix.ends_with('.'))
}

fn extract_and_validate_host<'a>(
    authority: &'a str,
    full_url: &str,
) -> Result<&'a str, ManifestValidationError> {
    let (host, port_part) = if let Some(after_open) = authority.strip_prefix('[') {
        let Some(close_idx) = after_open.find(']') else {
            return Err(ManifestValidationError::InvalidUrl {
                url: full_url.to_owned(),
                reason: "unclosed IPv6 bracket in host".to_owned(),
            });
        };
        let ipv6_str = &after_open[..close_idx];
        if ipv6_str.parse::<Ipv6Addr>().is_err() {
            return Err(ManifestValidationError::InvalidUrl {
                url: full_url.to_owned(),
                reason: "invalid IPv6 literal".to_owned(),
            });
        }
        let remainder = &after_open[close_idx + 1..];
        let port = if remainder.is_empty() {
            None
        } else if let Some(p) = remainder.strip_prefix(':') {
            Some(p)
        } else {
            return Err(ManifestValidationError::InvalidUrl {
                url: full_url.to_owned(),
                reason: "unexpected characters after IPv6 host".to_owned(),
            });
        };
        (ipv6_str, port)
    } else {
        match authority.split_once(':') {
            Some((h, p)) => (h, Some(p)),
            None => (authority, None),
        }
    };

    if host.is_empty()
        || (!authority.starts_with('[')
            && (host.starts_with('.')
                || host.ends_with('.')
                || host.starts_with('-')
                || host.ends_with('-')
                || !host
                    .bytes()
                    .all(|b| b.is_ascii_alphanumeric() || b == b'.' || b == b'-')))
    {
        return Err(ManifestValidationError::InvalidUrl {
            url: full_url.to_owned(),
            reason: "invalid host name".to_owned(),
        });
    }

    if let Some(p) = port_part {
        let parsed = p
            .parse::<u16>()
            .map_err(|_| ManifestValidationError::InvalidUrl {
                url: full_url.to_owned(),
                reason: "invalid port number".to_owned(),
            })?;
        if parsed == 0 {
            return Err(ManifestValidationError::InvalidUrl {
                url: full_url.to_owned(),
                reason: "port must be 1..=65535".to_owned(),
            });
        }
    }

    Ok(host)
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
