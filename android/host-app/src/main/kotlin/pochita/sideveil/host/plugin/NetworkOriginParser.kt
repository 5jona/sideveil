package pochita.sideveil.host.plugin

import android.net.Uri
import java.net.URI
import java.util.Locale

/**
 * Normalized `(scheme, host, port)` tuple representing a parsed network origin.
 */
data class ParsedOrigin(
    val scheme: String,
    val host: String,
    val port: Int,
)

/**
 * Pure-Kotlin URI/origin parsing, loopback Dev-Mode URL validation, and RFC1918 / loopback / LAN
 * host classification helpers used by [PluginNetworkPolicy] and [PluginAssetFetcher].
 */
object NetworkOriginParser {
    private val ALLOWED_SCHEMES = setOf("http", "https", "ws", "wss")
    private val INVALID_HOST_CHARS = setOf('\\', '/', '@')
    private val LOCAL_DOMAIN_LABEL_REGEX = Regex("^[a-z0-9](?:[a-z0-9-]*[a-z0-9])?$")
    private const val ASCII_PRINTABLE_MIN = 0x20
    private const val ASCII_DEL = 0x7F
    private const val MAX_PORT = 65_535
    private val FORBIDDEN_DEV_URL_CHARS = setOf('\\', '@', '?', '#')
    private val LOOPBACK_DEV_HOSTS = setOf("127.0.0.1", "localhost", "::1", "[::1]")

    /** Parses a URL string into its normalized [ParsedOrigin], or `null` if invalid. */
    fun parseOrigin(rawUrl: String): ParsedOrigin? {
        val trimmed = rawUrl.trim()
        val schemeEnd = trimmed.indexOf("://")
        if (schemeEnd <= 0) return null
        val scheme = trimmed.substring(0, schemeEnd).lowercase(Locale.ROOT)
        if (scheme !in ALLOWED_SCHEMES) return null
        val afterScheme = trimmed.substring(schemeEnd + 3)
        val authorityEnd =
            afterScheme.indexOfAny(charArrayOf('/', '?', '#')).let { if (it < 0) afterScheme.length else it }
        val authority = afterScheme.substring(0, authorityEnd)
        if (authority.isEmpty() || authority.contains('@')) return null
        val (host, port) = parseAuthority(authority, scheme) ?: return null
        if (isUnspecifiedHost(host)) return null
        return ParsedOrigin(scheme = scheme, host = host, port = port)
    }

    /** Parses an Android [Uri] into a normalized [ParsedOrigin], rejecting embedded userinfo. */
    fun parseAndroidUri(uri: Uri?): ParsedOrigin? {
        if (uri == null || uri.userInfo != null) return null
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return null
        if (scheme !in ALLOWED_SCHEMES) return null
        val host = uri.host?.let(::normalizeHost) ?: return null
        if (isUnspecifiedHost(host)) return null
        val port = if (uri.port == -1) defaultPortFor(scheme) else uri.port
        if (port !in 1..65535) return null
        return ParsedOrigin(scheme = scheme, host = host, port = port)
    }

    /** Returns `true` if [raw] is a strict loopback HTTP/HTTPS dev-server base URL. */
    fun isValidLoopbackDevUrl(raw: String): Boolean {
        val trimmed = raw.trim().trimEnd('/')
        if (trimmed.isEmpty() || trimmed.any(::hasForbiddenDevUrlChar)) return false
        val uri = runCatching { URI(trimmed) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        val schemeOk = scheme == "http" || scheme == "https"
        return schemeOk && hasValidLoopbackAuthority(uri)
    }

    /** Returns `true` if [host] is `localhost`, any `127.0.0.0/8` IPv4 loopback literal, or IPv6 `::1`. */
    fun isLoopbackHost(host: String): Boolean {
        val normalized = normalizeHost(host) ?: return false
        if (normalized == "localhost") return true
        parseIpv4Octets(normalized)?.let { return it[0] == 127 }
        val ipv6 = parseIpv6Groups(normalized) ?: return false
        return isLoopbackIpv6(ipv6)
    }

    /** Formats [origin] into its canonical `scheme://host[:port]` representation for CSP directives. */
    fun formatOrigin(origin: ParsedOrigin): String {
        val hostPart = if (origin.host.contains(':')) "[${origin.host}]" else origin.host
        val isDefault = origin.port == defaultPortFor(origin.scheme)
        return if (isDefault) "${origin.scheme}://$hostPart" else "${origin.scheme}://$hostPart:${origin.port}"
    }

    /** Returns `true` if [host] is a loopback, RFC1918 private IPv4, IPv6 ULA/link-local, or local mDNS domain. */
    fun isPrivateOrLoopbackHost(host: String): Boolean {
        val normalized = normalizeHost(host) ?: return false
        if (isReservedVirtualPluginHost(normalized)) return false
        if (normalized == "localhost") return true
        parseIpv4Octets(normalized)?.let { return isPrivateOrLoopbackIpv4(it) }
        parseIpv6Groups(normalized)?.let { return isPrivateOrLoopbackIpv6(it) }
        return isLocalDomainSuffix(normalized)
    }

    /** Returns `true` if [host] is an unspecified IPv4/IPv6 literal (`0.0.0.0`, `::`, or `::ffff:0.0.0.0`). */
    fun isUnspecifiedHost(host: String): Boolean {
        val normalized = normalizeHost(host) ?: return false
        parseIpv4Octets(normalized)?.let { return it.all { octet -> octet == 0 } }
        val ipv6 = parseIpv6Groups(normalized) ?: return false
        extractIpv4MappedOctets(ipv6)?.let { return it.all { octet -> octet == 0 } }
        return ipv6.all { group -> group == 0 }
    }

    /** Returns `true` if [normalizedHost] collides with SideVeil's internal virtual plugin domain. */
    fun isReservedVirtualPluginHost(normalizedHost: String): Boolean =
        normalizedHost == PluginAssetFetcher.PLUGIN_ORIGIN_SUFFIX ||
            normalizedHost.endsWith(".${PluginAssetFetcher.PLUGIN_ORIGIN_SUFFIX}")

    private fun hasForbiddenDevUrlChar(ch: Char): Boolean =
        ch.isWhitespace() || ch in FORBIDDEN_DEV_URL_CHARS || ch.code < ASCII_PRINTABLE_MIN || ch.code == ASCII_DEL

    private fun hasValidLoopbackAuthority(uri: URI): Boolean {
        if (uri.rawUserInfo != null || uri.userInfo != null) return false
        if (uri.rawQuery != null || uri.rawFragment != null) return false
        val authority = uri.rawAuthority
        if (authority.isNullOrEmpty() || authority.contains('@') || authority.endsWith(':')) return false
        val host = uri.host?.lowercase(Locale.ROOT) ?: return false
        val portOk = uri.port == -1 || uri.port in 1..MAX_PORT
        return host in LOOPBACK_DEV_HOSTS && portOk
    }

    private fun parseAuthority(
        authority: String,
        scheme: String,
    ): Pair<String, Int>? =
        if (authority.startsWith('[')) {
            parseBracketedAuthority(authority, scheme)
        } else {
            parseHostPortAuthority(authority, scheme)
        }

    private fun parseBracketedAuthority(
        authority: String,
        scheme: String,
    ): Pair<String, Int>? {
        val closeIdx = authority.indexOf(']')
        if (closeIdx <= 1) return null
        val host = normalizeHost(authority.substring(1, closeIdx))
        val port = parseOptionalPort(authority.substring(closeIdx + 1), scheme)
        return if (host != null && port != null) host to port else null
    }

    private fun parseHostPortAuthority(
        authority: String,
        scheme: String,
    ): Pair<String, Int>? {
        val colonIdx = authority.indexOf(':')
        if (colonIdx < 0) {
            return normalizeHost(authority)?.let { it to defaultPortFor(scheme) }
        }
        if (authority.lastIndexOf(':') != colonIdx) return null
        val host = normalizeHost(authority.substring(0, colonIdx))
        val port = parseOptionalPort(authority.substring(colonIdx), scheme)
        return if (host != null && port != null) host to port else null
    }

    private fun parseOptionalPort(
        portSuffix: String,
        scheme: String,
    ): Int? {
        if (portSuffix.isEmpty()) return defaultPortFor(scheme)
        if (!portSuffix.startsWith(':')) return null
        val digits = portSuffix.substring(1)
        if (digits.isEmpty() || !digits.all(Char::isDigit)) return null
        return digits.toIntOrNull()?.takeIf { it in 1..65535 }
    }

    private fun defaultPortFor(scheme: String): Int =
        when (scheme) {
            "https", "wss" -> 443
            else -> 80
        }

    private fun normalizeHost(raw: String): String? {
        val stripped =
            raw
                .trim()
                .lowercase(Locale.ROOT)
                .removePrefix("[")
                .removeSuffix("]")
        if (stripped.isEmpty()) return null
        if (stripped.any { it.isWhitespace() || it in INVALID_HOST_CHARS }) return null
        return stripped
    }

    private fun parseIpv4Octets(host: String): IntArray? {
        val parts = host.split('.')
        if (parts.size != 4) return null
        val octets = IntArray(4)
        for (i in 0 until 4) {
            val part = parts[i]
            if (part.isEmpty() || part.length > 3 || !part.all(Char::isDigit)) return null
            if (part.length > 1 && part[0] == '0') return null
            val value = part.toIntOrNull() ?: return null
            if (value !in 0..255) return null
            octets[i] = value
        }
        return octets
    }

    private fun isPrivateOrLoopbackIpv4(o: IntArray): Boolean =
        o[0] == 127 ||
            o[0] == 10 ||
            (o[0] == 172 && o[1] in 16..31) ||
            (o[0] == 192 && o[1] == 168) ||
            (o[0] == 169 && o[1] == 254)

    private fun parseIpv6Groups(host: String): IntArray? {
        val zoneStripped = host.substringBefore('%')
        if (!zoneStripped.contains(':')) return null
        val normalized = rewriteTrailingIpv4(zoneStripped) ?: return null
        val split = normalized.split("::")
        if (split.size > 2) return null
        val tokens =
            if (split.size == 1) {
                split[0].split(':')
            } else {
                val left = if (split[0].isEmpty()) emptyList() else split[0].split(':')
                val right = if (split[1].isEmpty()) emptyList() else split[1].split(':')
                val missing = 8 - left.size - right.size
                if (missing < 1) return null
                left + List(missing) { "0" } + right
            }
        if (tokens.size != 8) return null
        val groups = IntArray(8)
        for (i in 0 until 8) {
            val token = tokens[i]
            if (token.isEmpty() || token.length > 4) return null
            val parsed = token.toIntOrNull(16) ?: return null
            groups[i] = parsed
        }
        return groups
    }

    private fun rewriteTrailingIpv4(zoneStripped: String): String? {
        val lastColon = zoneStripped.lastIndexOf(':')
        val tail = zoneStripped.substring(lastColon + 1)
        if (!tail.contains('.')) return zoneStripped
        val octets = parseIpv4Octets(tail) ?: return null
        val hi = ((octets[0] shl 8) or octets[1]).toString(16)
        val lo = ((octets[2] shl 8) or octets[3]).toString(16)
        return "${zoneStripped.substring(0, lastColon + 1)}$hi:$lo"
    }

    private fun extractIpv4MappedOctets(g: IntArray): IntArray? {
        if (!(0 until 5).all { g[it] == 0 } || g[5] != 0xFFFF) return null
        return intArrayOf(
            (g[6] ushr 8) and 0xFF,
            g[6] and 0xFF,
            (g[7] ushr 8) and 0xFF,
            g[7] and 0xFF,
        )
    }

    private fun isLoopbackIpv6(g: IntArray): Boolean {
        extractIpv4MappedOctets(g)?.let { return it[0] == 127 }
        return (0 until 7).all { g[it] == 0 } && g[7] == 1
    }

    private fun isPrivateOrLoopbackIpv6(g: IntArray): Boolean {
        extractIpv4MappedOctets(g)?.let { return isPrivateOrLoopbackIpv4(it) }
        return isLoopbackIpv6(g) ||
            (g[0] and 0xFE00) == 0xFC00 ||
            (g[0] and 0xFFC0) == 0xFE80
    }

    private fun isLocalDomainSuffix(host: String): Boolean {
        val base =
            when {
                host.endsWith(".home.arpa") -> host.removeSuffix(".home.arpa")
                host.endsWith(".internal") -> host.removeSuffix(".internal")
                host.endsWith(".localhost") -> host.removeSuffix(".localhost")
                host.endsWith(".lan") -> host.removeSuffix(".lan")
                host.endsWith(".local") -> host.removeSuffix(".local")
                else -> return false
            }
        if (base.isEmpty()) return false
        return base.split('.').all { label -> LOCAL_DOMAIN_LABEL_REGEX.matches(label) }
    }
}
