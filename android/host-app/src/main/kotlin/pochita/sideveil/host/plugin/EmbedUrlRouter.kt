package pochita.sideveil.host.plugin

import pochita.sideveil.protocol.PluginEmbedPrefs
import java.util.Locale

internal typealias NetworkRouteContext = EmbedUrlRouter.NetworkRouteContext

/**
 * Pure-Kotlin LAN / remote URL selector, sub-route preservation switcher, and load-error fallback
 * resolver for untrusted WebView embeds (`[PluginEmbedPrefs]`).
 */
object EmbedUrlRouter {
    /** Active transport and Wi-Fi SSID snapshot used to select LAN vs. remote embed URLs. */
    data class NetworkRouteContext(
        val transport: String? = null,
        val wifiSsid: String? = null,
    )

    /** Resolves the preferred base URL (`url` vs `remoteUrl`) for [embed] under [network]. */
    fun preferredBaseUrl(
        embed: PluginEmbedPrefs,
        network: NetworkRouteContext = NetworkRouteContext(),
    ): String {
        val remoteUrl = embed.remoteUrl?.takeIf { it.isNotBlank() } ?: return embed.url
        val transport = network.transport?.trim()?.lowercase(Locale.ROOT)
        val wifiSsid = normalizeSsid(network.wifiSsid)
        return if (embed.lanSsids.isNotEmpty()) {
            val matchesConfiguredSsid =
                transport == "wifi" &&
                    wifiSsid != null &&
                    embed.lanSsids.any { configured -> normalizeSsid(configured) == wifiSsid }
            if (matchesConfiguredSsid) embed.url else remoteUrl
        } else {
            val isLanTransport =
                transport.isNullOrBlank() ||
                    transport == "wifi" ||
                    transport == "ethernet"
            if (isLanTransport) embed.url else remoteUrl
        }
    }

    /** Computes a switched URL preserving the path, query, and fragment of [currentUrl] onto [targetBaseUrl]. */
    fun resolveSwitchUrl(
        currentUrl: String?,
        targetBaseUrl: String,
    ): String? {
        val targetParts = parseUrlParts(targetBaseUrl) ?: return null
        if (currentUrl.isNullOrBlank()) return targetBaseUrl
        val currentParts = parseUrlParts(currentUrl) ?: return targetBaseUrl
        if (currentParts.origin == targetParts.origin) return null
        val hasCustomPath = currentParts.path.isNotEmpty() && currentParts.path != "/"
        if (!hasCustomPath && currentParts.queryAndFragment.isEmpty()) {
            return targetBaseUrl
        }
        val effectivePath =
            when {
                hasCustomPath -> currentParts.path
                targetParts.path.isNotEmpty() -> targetParts.path
                else -> "/"
            }
        return targetParts.schemeAndAuthority + effectivePath + currentParts.queryAndFragment
    }

    /** Returns the alternate fallback base URL when [failedUrl] fails to load. */
    fun fallbackBaseUrl(
        embed: PluginEmbedPrefs,
        failedUrl: String?,
    ): String? {
        val remoteUrl = embed.remoteUrl?.takeIf { it.isNotBlank() } ?: return null
        val failedOrigin = failedUrl?.let(NetworkOriginParser::parseOrigin) ?: return remoteUrl
        val lanOrigin = NetworkOriginParser.parseOrigin(embed.url)
        val remoteOrigin = NetworkOriginParser.parseOrigin(remoteUrl)
        return when (failedOrigin) {
            lanOrigin -> remoteUrl
            remoteOrigin -> embed.url
            else -> remoteUrl
        }
    }

    private fun normalizeSsid(raw: String?): String? {
        val trimmed = raw?.trim() ?: return null
        val unquoted =
            if (trimmed.length >= 2 && trimmed.startsWith('"') && trimmed.endsWith('"')) {
                trimmed.substring(1, trimmed.length - 1).trim()
            } else {
                trimmed
            }
        return unquoted.takeIf { it.isNotEmpty() }
    }

    private data class UrlParts(
        val origin: ParsedOrigin,
        val schemeAndAuthority: String,
        val path: String,
        val queryAndFragment: String,
    )

    private fun parseUrlParts(rawUrl: String): UrlParts? {
        val trimmed = rawUrl.trim()
        val origin = NetworkOriginParser.parseOrigin(trimmed) ?: return null
        val schemeEnd = trimmed.indexOf("://")
        if (schemeEnd <= 0) return null
        val authorityStart = schemeEnd + 3
        val afterScheme = trimmed.substring(authorityStart)
        val authorityLen =
            afterScheme.indexOfAny(charArrayOf('/', '?', '#')).let { if (it < 0) afterScheme.length else it }
        val authorityEnd = authorityStart + authorityLen
        val schemeAndAuthority = trimmed.substring(0, authorityEnd)
        val remainder = trimmed.substring(authorityEnd)
        val queryOrFragmentIdx = remainder.indexOfAny(charArrayOf('?', '#'))
        val path = if (queryOrFragmentIdx < 0) remainder else remainder.substring(0, queryOrFragmentIdx)
        val queryAndFragment = if (queryOrFragmentIdx < 0) "" else remainder.substring(queryOrFragmentIdx)
        return UrlParts(
            origin = origin,
            schemeAndAuthority = schemeAndAuthority,
            path = path,
            queryAndFragment = queryAndFragment,
        )
    }
}
