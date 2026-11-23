//! Loopback-only Dev Mode server URL validation for `PluginRegistry`.

use super::error::PluginRegistryError;

/// Validates that a Dev Mode server URL strictly targets loopback (`localhost`, `127.0.0.1`, `[::1]`)
/// with `http://` or `https://`, no userinfo (`@`), no control/whitespace chars, and a valid numeric port.
pub(super) fn validate_loopback_dev_url(raw: &str) -> Result<String, PluginRegistryError> {
    let trimmed = raw.trim().trim_end_matches('/');
    if trimmed.is_empty()
        || trimmed
            .chars()
            .any(|c| c.is_control() || c.is_whitespace() || c == '\\')
    {
        return Err(PluginRegistryError::InvalidDevUrl(
            "devServerUrl must not be empty or contain whitespace, control chars, or backslashes",
        ));
    }
    let Some((scheme, rest)) = trimmed.split_once("://") else {
        return Err(PluginRegistryError::InvalidDevUrl(
            "devServerUrl must use http:// or https://",
        ));
    };
    if !scheme.eq_ignore_ascii_case("http") && !scheme.eq_ignore_ascii_case("https") {
        return Err(PluginRegistryError::InvalidDevUrl(
            "devServerUrl must use http:// or https://",
        ));
    }
    if rest.contains('?') || rest.contains('#') {
        return Err(PluginRegistryError::InvalidDevUrl(
            "devServerUrl must not contain query or fragment components",
        ));
    }
    let authority = rest.split('/').next().unwrap_or_default();
    if authority.is_empty() || authority.contains('@') {
        return Err(PluginRegistryError::InvalidDevUrl(
            "devServerUrl authority must not be empty or contain userinfo (@)",
        ));
    }
    let (host, port_part) = if authority.starts_with('[') {
        let Some(end) = authority.find(']') else {
            return Err(PluginRegistryError::InvalidDevUrl(
                "devServerUrl has malformed IPv6 host",
            ));
        };
        (&authority[..=end], &authority[end + 1..])
    } else if let Some((h, p)) = authority.split_once(':') {
        (h, &authority[h.len()..h.len() + 1 + p.len()])
    } else {
        (authority, "")
    };
    let is_loopback =
        host.eq_ignore_ascii_case("localhost") || host == "127.0.0.1" || host == "[::1]";
    if !is_loopback {
        return Err(PluginRegistryError::InvalidDevUrl(
            "devServerUrl is restricted strictly to loopback (localhost, 127.0.0.1, [::1])",
        ));
    }
    if !port_part.is_empty() {
        let Some(digits) = port_part.strip_prefix(':') else {
            return Err(PluginRegistryError::InvalidDevUrl(
                "devServerUrl has invalid port syntax",
            ));
        };
        if digits.is_empty()
            || !digits.bytes().all(|b| b.is_ascii_digit())
            || digits.parse::<u16>().ok().is_none_or(|p| p == 0)
        {
            return Err(PluginRegistryError::InvalidDevUrl(
                "devServerUrl port must be a valid integer in 1..=65535",
            ));
        }
    }
    Ok(trimmed.to_owned())
}
