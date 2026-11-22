package pochita.sideveil.host

import android.net.Uri
import java.util.Locale

internal data class ParsedOrigin(
    val scheme: String,
    val host: String,
    val port: Int,
)

/**
 * Pure-Kotlin URI/origin parsing and RFC1918 / loopback / LAN host classification helpers
 * used by [PluginNetworkPolicy] (host-testable without Robolectric).
 */
internal object NetworkOriginParser {
    private val ALLOWED_SCHEMES = setOf("http", "https", "ws", "wss")
    private val INVALID_HOST_CHARS = setOf('\\', '/', '@')
    private val LOCAL_DOMAIN_LABEL_REGEX = Regex("^[a-z0-9](?:[a-z0-9-]*[a-z0-9])?$")

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
        return ParsedOrigin(scheme = scheme, host = host, port = port)
    }

    fun parseAndroidUri(uri: Uri?): ParsedOrigin? {
        if (uri == null || uri.userInfo != null) return null
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return null
        if (scheme !in ALLOWED_SCHEMES) return null
        val host = uri.host?.let(::normalizeHost) ?: return null
        val port = if (uri.port == -1) defaultPortFor(scheme) else uri.port
        if (port !in 1..65535) return null
        return ParsedOrigin(scheme = scheme, host = host, port = port)
    }

    fun isLoopbackHost(host: String): Boolean {
        val normalized = normalizeHost(host) ?: return false
        if (normalized == "localhost" || normalized == "127.0.0.1") return true
        val ipv6 = parseIpv6Groups(normalized) ?: return false
        return isLoopbackIpv6(ipv6)
    }

    fun isPrivateOrLoopbackHost(host: String): Boolean {
        val normalized = normalizeHost(host) ?: return false
        if (isReservedVirtualPluginHost(normalized)) return false
        if (normalized == "localhost") return true
        parseIpv4Octets(normalized)?.let { return isPrivateOrLoopbackIpv4(it) }
        parseIpv6Groups(normalized)?.let { return isPrivateOrLoopbackIpv6(it) }
        return isLocalDomainSuffix(normalized)
    }

    fun isReservedVirtualPluginHost(normalizedHost: String): Boolean =
        normalizedHost == PluginAssetFetcher.PLUGIN_ORIGIN_SUFFIX ||
            normalizedHost.endsWith(".${PluginAssetFetcher.PLUGIN_ORIGIN_SUFFIX}")

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
        (o[0] == 127 && o[1] == 0 && o[2] == 0 && o[3] == 1) ||
            o[0] == 10 ||
            (o[0] == 172 && o[1] in 16..31) ||
            (o[0] == 192 && o[1] == 168)

    private fun parseIpv6Groups(host: String): IntArray? {
        val zoneStripped = host.substringBefore('%')
        if (!zoneStripped.contains(':')) return null
        val split = zoneStripped.split("::")
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

    private fun isLoopbackIpv6(g: IntArray): Boolean = (0 until 7).all { g[it] == 0 } && g[7] == 1

    private fun isPrivateOrLoopbackIpv6(g: IntArray): Boolean =
        isLoopbackIpv6(g) ||
            (g[0] and 0xFE00) == 0xFC00 ||
            (g[0] and 0xFFC0) == 0xFE80

    private fun isLocalDomainSuffix(host: String): Boolean {
        val base =
            when {
                host.endsWith(".lan") -> host.removeSuffix(".lan")
                host.endsWith(".local") -> host.removeSuffix(".local")
                else -> return false
            }
        if (base.isEmpty()) return false
        return base.split('.').all { label -> LOCAL_DOMAIN_LABEL_REGEX.matches(label) }
    }
}
