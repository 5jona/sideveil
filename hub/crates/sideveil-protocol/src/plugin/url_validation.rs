//! RFC1918/LAN network URL validation for web-embed targets and plugin allowlists.

use std::net::{Ipv4Addr, Ipv6Addr};

use super::validation::ManifestValidationError;

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
