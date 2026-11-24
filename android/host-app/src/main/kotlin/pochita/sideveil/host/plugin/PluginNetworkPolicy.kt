package pochita.sideveil.host.plugin

import android.net.Uri
import pochita.sideveil.protocol.PluginCapability
import pochita.sideveil.protocol.PluginDescriptor
import pochita.sideveil.protocol.PluginEmbedPrefs
import pochita.sideveil.protocol.PluginNetworkPrefs

/**
 * Pure-Kotlin network isolation and Scheme B RFC1918 cleartext triple-lock policy for plugin
 * WebViews (host-testable without Robolectric).
 */
object PluginNetworkPolicy {
    /** Returns `true` if [descriptor] is configured as an untrusted web-embed surface. */
    fun isUntrustedEmbed(descriptor: PluginDescriptor): Boolean = descriptor.embed != null

    /** Returns `true` if [host] is a loopback literal or `localhost`. */
    fun isLoopbackHost(host: String): Boolean = NetworkOriginParser.isLoopbackHost(host)

    /** Returns `true` if [host] is a loopback or private RFC1918 / IPv6 ULA / local mDNS host. */
    fun isPrivateOrLoopbackHost(host: String): Boolean = NetworkOriginParser.isPrivateOrLoopbackHost(host)

    /** Parses [rawUrl] into a normalized [ParsedOrigin], or `null` if invalid. */
    fun parseOrigin(rawUrl: String): ParsedOrigin? = NetworkOriginParser.parseOrigin(rawUrl)

    /** Evaluates whether WebView native networking should permit [uri] for [descriptor]. */
    fun shouldAllowNativeNetwork(
        descriptor: PluginDescriptor,
        uri: Uri?,
        isMainFrame: Boolean,
    ): Boolean {
        val target = NetworkOriginParser.parseAndroidUri(uri) ?: return false
        return shouldAllowOrigin(descriptor, target, isMainFrame)
    }

    /** Evaluates whether WebView native networking should permit [rawUrl] for [descriptor]. */
    fun shouldAllowNativeNetwork(
        descriptor: PluginDescriptor,
        rawUrl: String,
        isMainFrame: Boolean,
    ): Boolean {
        val target = NetworkOriginParser.parseOrigin(rawUrl) ?: return false
        return shouldAllowOrigin(descriptor, target, isMainFrame)
    }

    /** Returns `true` when mixed HTTP subresources inside an HTTPS plugin origin are permitted. */
    fun shouldAllowMixedContent(descriptor: PluginDescriptor): Boolean {
        if (isUntrustedEmbed(descriptor)) return false
        val network = descriptor.network ?: return false
        if (!network.allowCleartext) return false
        return descriptor.hasGrantedCapability(PluginCapability.NETWORK_LOOPBACK) ||
            descriptor.hasGrantedCapability(PluginCapability.NETWORK_LAN)
    }

    /** Verifies that [url] belongs to the exact same origin as [expectedOrigin]. */
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
        if (NetworkOriginParser.isUnspecifiedHost(target.host)) return false
        val embed = descriptor.embed
        if (embed != null) {
            return shouldAllowUntrustedEmbed(embed, target, isMainFrame)
        }
        // Trusted plugins live exclusively on their virtual origin and may never navigate their
        // top-level frame to an external origin (which would escape Host-injected CSP).
        if (isMainFrame) return false
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
                embed.remoteUrl
                    ?.takeIf { it.isNotBlank() }
                    ?.let(NetworkOriginParser::parseOrigin)
                    ?.let(::add)
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
        val privateOrLoopback = NetworkOriginParser.isPrivateOrLoopbackHost(target.host)
        val hasLoopback = descriptor.hasGrantedCapability(PluginCapability.NETWORK_LOOPBACK)
        val hasLan = descriptor.hasGrantedCapability(PluginCapability.NETWORK_LAN)
        val hasInternet = descriptor.hasGrantedCapability(PluginCapability.NETWORK_INTERNET)
        return when (target.scheme) {
            "https", "wss" -> {
                when {
                    loopback -> hasLoopback || hasLan
                    privateOrLoopback -> hasLan
                    else -> hasInternet
                }
            }

            "http", "ws" -> {
                if (!network.allowCleartext || !privateOrLoopback) {
                    false
                } else if (loopback) {
                    hasLoopback || hasLan
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
