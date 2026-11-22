package pochita.sideveil.host

import android.net.Uri
import pochita.sideveil.protocol.PluginCapability
import pochita.sideveil.protocol.PluginDescriptor
import pochita.sideveil.protocol.PluginEmbedPrefs
import pochita.sideveil.protocol.PluginNetworkPrefs

/**
 * Pure-Kotlin network isolation and Scheme B RFC1918 cleartext triple-lock policy for plugin
 * WebViews (host-testable without Robolectric).
 */
internal object PluginNetworkPolicy {
    fun isUntrustedEmbed(descriptor: PluginDescriptor): Boolean = descriptor.embed != null

    fun isLoopbackHost(host: String): Boolean = NetworkOriginParser.isLoopbackHost(host)

    fun isPrivateOrLoopbackHost(host: String): Boolean = NetworkOriginParser.isPrivateOrLoopbackHost(host)

    fun parseOrigin(rawUrl: String): ParsedOrigin? = NetworkOriginParser.parseOrigin(rawUrl)

    fun shouldAllowNativeNetwork(
        descriptor: PluginDescriptor,
        uri: Uri?,
        isMainFrame: Boolean,
    ): Boolean {
        val target = NetworkOriginParser.parseAndroidUri(uri) ?: return false
        return shouldAllowOrigin(descriptor, target, isMainFrame)
    }

    fun shouldAllowNativeNetwork(
        descriptor: PluginDescriptor,
        rawUrl: String,
        isMainFrame: Boolean,
    ): Boolean {
        val target = NetworkOriginParser.parseOrigin(rawUrl) ?: return false
        return shouldAllowOrigin(descriptor, target, isMainFrame)
    }

    fun shouldAllowMixedContent(descriptor: PluginDescriptor): Boolean {
        if (isUntrustedEmbed(descriptor)) return false
        val network = descriptor.network ?: return false
        if (!network.allowCleartext) return false
        return descriptor.capabilities.contains(PluginCapability.NETWORK_LOOPBACK) ||
            descriptor.capabilities.contains(PluginCapability.NETWORK_LAN)
    }

    fun isSameTrustedOrigin(
        url: String?,
        expectedOrigin: String,
    ): Boolean {
        val actual = url?.let(NetworkOriginParser::parseOrigin) ?: return false
        val expected = NetworkOriginParser.parseOrigin(expectedOrigin) ?: return false
        return actual == expected
    }

    private fun shouldAllowOrigin(
        descriptor: PluginDescriptor,
        target: ParsedOrigin,
        isMainFrame: Boolean,
    ): Boolean {
        if (NetworkOriginParser.isReservedVirtualPluginHost(target.host)) return false
        val embed = descriptor.embed
        if (embed != null) {
            return shouldAllowUntrustedEmbed(embed, target, isMainFrame)
        }
        val network = descriptor.network ?: return false
        return shouldAllowTrustedPlugin(descriptor, network, target)
    }

    private fun shouldAllowUntrustedEmbed(
        embed: PluginEmbedPrefs,
        target: ParsedOrigin,
        isMainFrame: Boolean,
    ): Boolean {
        val allowedOrigins =
            buildList {
                NetworkOriginParser.parseOrigin(embed.url)?.let(::add)
                embed.allowedOrigins.mapNotNullTo(this, NetworkOriginParser::parseOrigin)
            }
        return when (target.scheme) {
            "https" -> {
                !isMainFrame || matchesAnyOrigin(target, allowedOrigins)
            }

            "wss" -> {
                matchesAnyOrigin(target, allowedOrigins)
            }

            "http", "ws" -> {
                embed.allowCleartext &&
                    NetworkOriginParser.isPrivateOrLoopbackHost(target.host) &&
                    matchesAnyOrigin(target, allowedOrigins)
            }

            else -> {
                false
            }
        }
    }

    private fun shouldAllowTrustedPlugin(
        descriptor: PluginDescriptor,
        network: PluginNetworkPrefs,
        target: ParsedOrigin,
    ): Boolean {
        val allowedOrigins = network.allowedOrigins.mapNotNull(NetworkOriginParser::parseOrigin)
        if (!matchesAnyOrigin(target, allowedOrigins)) return false
        val loopback = NetworkOriginParser.isLoopbackHost(target.host)
        val hasLoopback = descriptor.capabilities.contains(PluginCapability.NETWORK_LOOPBACK)
        val hasLan = descriptor.capabilities.contains(PluginCapability.NETWORK_LAN)
        return when (target.scheme) {
            "https", "wss" -> {
                if (loopback) hasLoopback else hasLan
            }

            "http", "ws" -> {
                if (!network.allowCleartext || !NetworkOriginParser.isPrivateOrLoopbackHost(target.host)) {
                    false
                } else if (loopback) {
                    hasLoopback
                } else {
                    hasLan
                }
            }

            else -> {
                false
            }
        }
    }

    private fun matchesAnyOrigin(
        target: ParsedOrigin,
        allowed: List<ParsedOrigin>,
    ): Boolean =
        allowed.any { origin ->
            origin.host == target.host &&
                origin.port == target.port &&
                isSchemeCompatible(targetScheme = target.scheme, allowedScheme = origin.scheme)
        }

    private fun isSchemeCompatible(
        targetScheme: String,
        allowedScheme: String,
    ): Boolean =
        when (targetScheme) {
            "http" -> allowedScheme == "http"
            "https" -> allowedScheme == "https"
            "ws" -> allowedScheme == "ws" || allowedScheme == "http"
            "wss" -> allowedScheme == "wss" || allowedScheme == "https"
            else -> false
        }
}
