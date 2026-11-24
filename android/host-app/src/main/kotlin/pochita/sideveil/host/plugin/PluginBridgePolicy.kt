package pochita.sideveil.host.plugin

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import pochita.sideveil.protocol.HostEventEnvelope
import pochita.sideveil.protocol.HostReadyPayload
import pochita.sideveil.protocol.PluginDescriptor
import pochita.sideveil.protocol.SideVeilEvents
import pochita.sideveil.protocol.SideVeilProtocolVersions
import pochita.sideveil.protocol.SideVeilWireJson

/** Transport channel used to deliver messages from Render Host to a trusted plugin WebView. */
internal enum class BridgeDeliveryChannel {
    REPLY_PROXY,
    WEB_MESSAGE_PORT,
    POST_WEB_MESSAGE,
}

/** Parsed `host.attach` handshake frame sent by a trusted plugin main frame. */
internal data class HostAttachSignal(
    val usesListenerChannel: Boolean,
    val protocolVersion: Long,
)

/**
 * Pure-Kotlin origin enforcement, `host.attach` / `host.ready` handshake codec, and delivery
 * channel selection state machine for [PluginWebMessageBridge].
 */
internal object PluginBridgePolicy {
    const val HOST_OBJECT_NAME = "__POCHITA_HOST__"
    const val ATTACH_EVENT_NAME = "host.attach"
    const val LISTENER_CHANNEL_TOKEN = "listener"

    /**
     * Injected into the plugin's exact virtual origin at document start so Chromium delivers an
     * origin- and frame-verified [androidx.webkit.JavaScriptReplyProxy] immediately on boot.
     */
    const val DOCUMENT_START_ATTACH_SCRIPT =
        "try{window.__POCHITA_HOST__&&window.__POCHITA_HOST__.postMessage(" +
            "JSON.stringify({event:'host.attach',protocolVersion:1}));}catch(_){}"

    /**
     * Enforces Chromium-verified main-frame + exact virtual origin isolation for
     * `WebViewCompat.addWebMessageListener` messages. Rejects cross-origin iframes and web-embeds.
     */
    fun isTrustedListenerMessage(
        sourceOrigin: String?,
        isMainFrame: Boolean,
        expectedPluginOrigin: String,
        isUntrustedEmbed: Boolean,
    ): Boolean {
        if (isUntrustedEmbed || !isMainFrame || sourceOrigin.isNullOrBlank()) return false
        return PluginNetworkPolicy.isSameTrustedOrigin(sourceOrigin, expectedPluginOrigin)
    }

    /** Verifies that the currently committed main-frame URL matches [expectedPluginOrigin]. */
    fun isCommittedMainFrameTrusted(
        lastCommittedUrl: String?,
        expectedPluginOrigin: String,
        isUntrustedEmbed: Boolean,
    ): Boolean {
        if (isUntrustedEmbed || lastCommittedUrl.isNullOrBlank()) return false
        return PluginNetworkPolicy.isSameTrustedOrigin(lastCommittedUrl, expectedPluginOrigin)
    }

    /** Parses a `host.attach` handshake JSON payload, or returns `null` for normal RPC messages. */
    fun parseAttachSignal(rawJson: String): HostAttachSignal? {
        val obj = runCatching { SideVeilWireJson.parseToJsonElement(rawJson).jsonObject }.getOrNull() ?: return null
        val eventName = (obj["event"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        if (eventName != ATTACH_EVENT_NAME) return null
        val channel = (obj["channel"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val version =
            (obj["protocolVersion"] as? JsonPrimitive)?.longOrNull
                ?: SideVeilProtocolVersions.PROTOCOL_VERSION
        return HostAttachSignal(
            usesListenerChannel = channel == LISTENER_CHANNEL_TOKEN,
            protocolVersion = version,
        )
    }

    /** Builds the canonical `host.ready` JSON envelope advertising negotiated versions and grants. */
    fun buildHostReadyEnvelope(descriptor: PluginDescriptor): String {
        val payload =
            HostReadyPayload(
                protocolVersion = SideVeilProtocolVersions.PROTOCOL_VERSION,
                apiVersion = SideVeilProtocolVersions.PLUGIN_API_VERSION,
                pluginId = descriptor.id,
                grantedCapabilities = descriptor.grantedCapabilityWireTokens(),
            )
        val envelope =
            HostEventEnvelope(
                event = SideVeilEvents.HOST_READY,
                payload = SideVeilWireJson.encodeToJsonElement(payload),
            )
        return SideVeilWireJson.encodeToString(envelope)
    }

    /** Extracts a string `id` from a raw JSON object when returning parse/request errors. */
    fun extractRpcId(rawJson: String): String? {
        val obj = runCatching { SideVeilWireJson.parseToJsonElement(rawJson) as? JsonObject }.getOrNull() ?: return null
        val idPrimitive = obj["id"] as? JsonPrimitive ?: return null
        return idPrimitive.content.takeIf { it.isNotEmpty() }
    }
}

/**
 * Pure-Kotlin channel state tracking whether a trusted plugin consumes messages over
 * `window.__POCHITA_HOST__` (`REPLY_PROXY`) or legacy transferred `WebMessagePort`.
 */
internal class PluginBridgeChannelState {
    var listenerChannelReady: Boolean = false
        private set
    var replyProxyAttached: Boolean = false
        private set
    var portAttached: Boolean = false
        private set
    var pageReady: Boolean = false
        private set

    fun reset() {
        listenerChannelReady = false
        replyProxyAttached = false
        portAttached = false
        pageReady = false
    }

    fun onReplyProxyAttached(usesListenerChannel: Boolean) {
        replyProxyAttached = true
        if (usesListenerChannel) {
            listenerChannelReady = true
            pageReady = true
        }
    }

    fun onWebMessagePortAttached() {
        portAttached = true
        pageReady = true
    }

    fun onWebMessagePortClosed() {
        portAttached = false
        if (!listenerChannelReady) {
            pageReady = false
        }
    }

    fun onFallbackWindowPostReady() {
        pageReady = true
    }

    /** Resolves which transport channel should carry an RPC reply for a request from [source]. */
    fun resolveReplyChannel(source: BridgeDeliveryChannel): BridgeDeliveryChannel =
        when {
            source == BridgeDeliveryChannel.WEB_MESSAGE_PORT && portAttached -> BridgeDeliveryChannel.WEB_MESSAGE_PORT
            listenerChannelReady && replyProxyAttached -> BridgeDeliveryChannel.REPLY_PROXY
            portAttached -> BridgeDeliveryChannel.WEB_MESSAGE_PORT
            else -> BridgeDeliveryChannel.POST_WEB_MESSAGE
        }

    /** Resolves which transport channel should carry an outbound host event without double-delivery. */
    fun resolveEventChannel(): BridgeDeliveryChannel? =
        when {
            !pageReady -> null
            listenerChannelReady && replyProxyAttached -> BridgeDeliveryChannel.REPLY_PROXY
            portAttached -> BridgeDeliveryChannel.WEB_MESSAGE_PORT
            else -> BridgeDeliveryChannel.POST_WEB_MESSAGE
        }
}
