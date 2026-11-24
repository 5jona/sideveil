//! Network URL and capability-scoped origin validation for web-embeds and plugin allowlists.

use std::net::{Ipv4Addr, Ipv6Addr};

use super::PluginCapability;
use super::validation::ManifestValidationError;

/// Validates an HTTP(S) URL for web-embed targets or network allowlists.
///
/// Public cleartext `http://` URLs are always rejected. Loopback, RFC1918, link-local,
/// ULA IPv6, and `.lan`/`.local` cleartext `http://` URLs require `allow_cleartext == true`.
pub fn validate_network_url(
    raw: &str,
    allow_cleartext: bool,
) -> Result<String, ManifestValidationError> {
    let (trimmed, _host) = parse_and_validate_url(raw, allow_cleartext, false)?;
    Ok(trimmed)
}

/// Validates a `network.allowedOrigins` entry (`http(s)://` or `ws(s)://`) against declared
/// `network-loopback`, `network-lan`, and `network-internet` capabilities.
pub fn validate_origin_for_capabilities(
    raw: &str,
    allow_cleartext: bool,
    capabilities: &[PluginCapability],
) -> Result<String, ManifestValidationError> {
    let (trimmed, host) = parse_and_validate_url(raw, allow_cleartext, true)?;
    let has_loopback = capabilities.contains(&PluginCapability::NetworkLoopback);
    let has_lan = capabilities.contains(&PluginCapability::NetworkLan);
    let has_internet = capabilities.contains(&PluginCapability::NetworkInternet);

    if is_loopback_host(&host) {
        if !(has_loopback || has_lan) {
            return Err(ManifestValidationError::OriginCapabilityMismatch {
                url: trimmed,
                required: "network-loopback (or network-lan)",
            });
        }
    } else if is_private_or_lan_host(&host) {
        if !has_lan {
            return Err(ManifestValidationError::OriginCapabilityMismatch {
                url: trimmed,
                required: "network-lan",
            });
        }
    } else if !has_internet {
        return Err(ManifestValidationError::OriginCapabilityMismatch {
            url: trimmed,
            required: "network-internet",
        });
    }

    Ok(trimmed)
}

fn parse_and_validate_url(
    raw: &str,
    allow_cleartext: bool,
    allow_ws_schemes: bool,
) -> Result<(String, String), ManifestValidationError> {
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

    let (is_cleartext, rest) = if let Some(r) = trimmed.strip_prefix("https://") {
        (false, r)
    } else if let Some(r) = trimmed.strip_prefix("http://") {
        (true, r)
    } else if allow_ws_schemes && let Some(r) = trimmed.strip_prefix("wss://") {
        (false, r)
    } else if allow_ws_schemes && let Some(r) = trimmed.strip_prefix("ws://") {
        (true, r)
    } else {
        let expected = if allow_ws_schemes {
            "http://, https://, ws://, or wss://"
        } else {
            "http:// or https://"
        };
        return Err(ManifestValidationError::InvalidUrl {
            url: trimmed.to_owned(),
            reason: format!("URL scheme must be {expected}"),
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

    if is_cleartext {
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

    Ok((trimmed.to_owned(), host.to_owned()))
}

/// Returns `true` when `host` is strictly loopback (`localhost`, `*.localhost`, `127.0.0.0/8`, `::1`,
/// or IPv4-mapped loopback `::ffff:127.0.0.0/104`).
#[must_use]
pub fn is_loopback_host(host: &str) -> bool {
    let lower = host.to_ascii_lowercase();
    if lower == "localhost" || has_nonempty_label_before_suffix(&lower, ".localhost") {
        return true;
    }
    if let Ok(ip) = lower.parse::<Ipv4Addr>() {
        return ip.is_loopback();
    }
    if let Ok(ip) = lower.parse::<Ipv6Addr>() {
        return ip.is_loopback() || ip.to_ipv4_mapped().is_some_and(|v4| v4.is_loopback());
    }
    false
}

/// Returns `true` when `host` is a loopback, RFC1918 private IPv4, IPv4-mapped private IPv6,
/// ULA/link-local IPv6, or local mDNS/LAN hostname (`.lan`, `.local`, `.localhost`, `.internal`, `.home.arpa`).
#[must_use]
pub fn is_private_or_lan_host(host: &str) -> bool {
    if is_loopback_host(host) {
        return true;
    }
    let lower = host.to_ascii_lowercase();
    if has_nonempty_label_before_suffix(&lower, ".lan")
        || has_nonempty_label_before_suffix(&lower, ".local")
        || has_nonempty_label_before_suffix(&lower, ".internal")
        || has_nonempty_label_before_suffix(&lower, ".home.arpa")
    {
        return true;
    }

    if let Ok(ip) = lower.parse::<Ipv4Addr>() {
        return is_private_or_special_ipv4(ip);
    }

    if let Ok(ip) = lower.parse::<Ipv6Addr>() {
        if let Some(v4) = ip.to_ipv4_mapped() {
            return is_private_or_special_ipv4(v4);
        }
        let seg0 = ip.segments()[0];
        return ip.is_unspecified() || (seg0 & 0xfe00) == 0xfc00 || (seg0 & 0xffc0) == 0xfe80;
    }

    false
}

fn is_private_or_special_ipv4(ip: Ipv4Addr) -> bool {
    let [a, b, _, _] = ip.octets();
    ip.is_loopback()
        || ip.is_unspecified()
        || a == 0
        || a == 10
        || (a == 172 && (16..=31).contains(&b))
        || (a == 192 && b == 168)
        || (a == 169 && b == 254)
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

    if let Ok(v4) = host.parse::<Ipv4Addr>()
        && v4.is_unspecified()
    {
        return Err(ManifestValidationError::InvalidUrl {
            url: full_url.to_owned(),
            reason: "unspecified IPv4 address (0.0.0.0) is forbidden".to_owned(),
        });
    }
    if let Ok(v6) = host.parse::<Ipv6Addr>()
        && (v6.is_unspecified() || v6.to_ipv4_mapped().is_some_and(|v4| v4.is_unspecified()))
    {
        return Err(ManifestValidationError::InvalidUrl {
            url: full_url.to_owned(),
            reason: "unspecified IPv6 address (::) is forbidden".to_owned(),
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
