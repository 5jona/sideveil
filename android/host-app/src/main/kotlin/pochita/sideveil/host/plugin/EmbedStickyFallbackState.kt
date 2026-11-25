package pochita.sideveil.host.plugin

import pochita.sideveil.protocol.PluginEmbedPrefs
import java.util.Locale

/**
 * Pure-Kotlin sticky fallback state machine for untrusted web-embeds.
 *
 * Remembers an unreachable origin for the active normalized `(transport, wifiSsid)` network key so
 * repeated `network.contextChanged` ticks on the same Wi-Fi SSID do not bounce back to a dead
 * `.local` mDNS URL until the network transport/SSID actually changes or the user reloads/homes.
 */
internal class EmbedStickyFallbackState(
    initialNetworkRoute: NetworkRouteContext = NetworkRouteContext(),
) {
    internal data class NetworkKey(
        val transport: String?,
        val wifiSsid: String?,
    )

    @Volatile
    var latestNetworkRoute: NetworkRouteContext = initialNetworkRoute
        private set

    @Volatile
    private var fallbackAttemptedForUrl: String? = null

    @Volatile
    private var attemptedFallbackOrigins: Set<ParsedOrigin> = emptySet()

    @Volatile
    private var stickyFailedOrigin: Pair<NetworkKey, ParsedOrigin>? = null

    fun reset() {
        fallbackAttemptedForUrl = null
        attemptedFallbackOrigins = emptySet()
        stickyFailedOrigin = null
    }

    fun onPageLoadSucceeded() {
        fallbackAttemptedForUrl = null
        attemptedFallbackOrigins = emptySet()
    }

    fun resolveNetworkRouteSwitch(
        embed: PluginEmbedPrefs,
        network: NetworkRouteContext,
        currentUrl: String?,
    ): String? {
        val prevKey = normalizeNetworkKey(latestNetworkRoute)
        val nextKey = normalizeNetworkKey(network)
        latestNetworkRoute = network
        if (nextKey != prevKey) {
            stickyFailedOrigin = null
            attemptedFallbackOrigins = emptySet()
        }
        val preferred = EmbedUrlRouter.preferredBaseUrl(embed, network)
        val preferredOrigin = NetworkOriginParser.parseOrigin(preferred)
        if (preferredOrigin != null && stickyFailedOrigin == (nextKey to preferredOrigin)) {
            return null
        }
        val switchUrl = EmbedUrlRouter.resolveSwitchUrl(currentUrl, preferred) ?: return null
        fallbackAttemptedForUrl = null
        attemptedFallbackOrigins = emptySet()
        return switchUrl
    }

    fun resolveFallbackAfterMainFrameError(
        embed: PluginEmbedPrefs,
        failedUrl: String?,
    ): String? {
        val fallback = EmbedUrlRouter.fallbackBaseUrl(embed, failedUrl) ?: return null
        val failedOrigin = failedUrl?.let(NetworkOriginParser::parseOrigin)
        val fallbackOrigin = NetworkOriginParser.parseOrigin(fallback)
        if (fallbackAttemptedForUrl == failedUrl) return null
        if (failedOrigin != null && failedOrigin in attemptedFallbackOrigins) return null
        if (fallbackOrigin != null && fallbackOrigin in attemptedFallbackOrigins) return null
        val switchUrl = EmbedUrlRouter.resolveSwitchUrl(failedUrl, fallback) ?: fallback
        fallbackAttemptedForUrl = failedUrl
        if (failedOrigin != null) {
            attemptedFallbackOrigins = attemptedFallbackOrigins + failedOrigin
            stickyFailedOrigin = normalizeNetworkKey(latestNetworkRoute) to failedOrigin
        }
        return switchUrl
    }

    companion object {
        internal fun normalizeNetworkKey(network: NetworkRouteContext): NetworkKey {
            val transport =
                network.transport
                    ?.trim()
                    ?.lowercase(Locale.ROOT)
                    ?.takeIf { it.isNotEmpty() }
            val ssid =
                network.wifiSsid
                    ?.trim()
                    ?.removeSurrounding("\"")
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
            return NetworkKey(transport = transport, wifiSsid = ssid)
        }
    }
}
