package pochita.sideveil.host.plugin

import pochita.sideveil.protocol.PluginDescriptor

/**
 * Pure-Kotlin Content-Security-Policy builder for trusted plugin HTML documents served from
 * `https://<pluginId>.plugins.sideveil.local/`.
 *
 * Closes WebView's `shouldInterceptRequest` blind spots (WebSocket upgrades, WebRTC, inline frames,
 * objects) by enforcing a strict Blink-level policy derived exclusively from each plugin's
 * Hub-granted network capabilities (`grantedCapabilities`) and validated `network.allowedOrigins`.
 */
object PluginCspBuilder {
    /** Builds the HTTP `Content-Security-Policy` header value for [descriptor]. */
    fun buildDocumentCsp(descriptor: PluginDescriptor): String {
        val scriptSources = linkedSetOf("'self'", "'unsafe-inline'", "'wasm-unsafe-eval'")
        val imgSources = linkedSetOf("'self'", "data:", "blob:")
        val connectSources = linkedSetOf("'self'")

        appendDevServerSources(descriptor.devServerUrl, scriptSources, imgSources, connectSources)
        for (rawOrigin in descriptor.network?.allowedOrigins.orEmpty()) {
            appendGrantedNetworkOrigin(descriptor, rawOrigin, imgSources, connectSources)
        }

        return buildString {
            append("default-src 'self' data: blob:; ")
            append("script-src ").append(scriptSources.joinToString(" ")).append("; ")
            append("worker-src 'self'; ")
            append("style-src 'self' 'unsafe-inline'; ")
            append("img-src ").append(imgSources.joinToString(" ")).append("; ")
            append("font-src 'self' data: blob:; ")
            append("connect-src ").append(connectSources.joinToString(" ")).append("; ")
            append("frame-src 'none'; ")
            append("object-src 'none'; ")
            append("base-uri 'self'")
        }
    }

    private fun appendDevServerSources(
        rawDevUrl: String?,
        scriptSources: MutableSet<String>,
        imgSources: MutableSet<String>,
        connectSources: MutableSet<String>,
    ) {
        val devBase = rawDevUrl?.trim()?.trimEnd('/') ?: return
        if (!NetworkOriginParser.isValidLoopbackDevUrl(devBase)) return
        val devOrigin = NetworkOriginParser.parseOrigin(devBase) ?: return
        val formatted = NetworkOriginParser.formatOrigin(devOrigin)
        scriptSources += formatted
        imgSources += formatted
        connectSources += formatted
        wsCounterpart(devOrigin)?.let { wsOrigin ->
            connectSources += NetworkOriginParser.formatOrigin(wsOrigin)
        }
    }

    private fun appendGrantedNetworkOrigin(
        descriptor: PluginDescriptor,
        rawOrigin: String,
        imgSources: MutableSet<String>,
        connectSources: MutableSet<String>,
    ) {
        val parsed = NetworkOriginParser.parseOrigin(rawOrigin) ?: return
        if (!PluginNetworkPolicy.shouldAllowNativeNetwork(descriptor, rawOrigin, isMainFrame = false)) return
        val formatted = NetworkOriginParser.formatOrigin(parsed)
        connectSources += formatted
        if (parsed.scheme == "http" || parsed.scheme == "https") {
            imgSources += formatted
            wsCounterpart(parsed)?.let { wsOrigin ->
                val wsUrl = NetworkOriginParser.formatOrigin(wsOrigin)
                if (PluginNetworkPolicy.shouldAllowNativeNetwork(descriptor, wsUrl, isMainFrame = false)) {
                    connectSources += wsUrl
                }
            }
        }
    }

    private fun wsCounterpart(origin: ParsedOrigin): ParsedOrigin? =
        when (origin.scheme) {
            "http" -> origin.copy(scheme = "ws")
            "https" -> origin.copy(scheme = "wss")
            else -> null
        }
}
