package pochita.sideveil.host.plugin

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pochita.sideveil.protocol.PluginCapability
import pochita.sideveil.protocol.PluginDescriptor
import pochita.sideveil.protocol.PluginNetworkPrefs
import pochita.sideveil.protocol.SideVeilEvents
import pochita.sideveil.protocol.SideVeilProtocolVersions
import pochita.sideveil.protocol.SideVeilWireJson

class PluginCspAndBridgePolicyTest {
    @Test
    fun `PluginCspBuilder blocks all external connect targets when plugin lacks network grants`() {
        val plugin =
            PluginDescriptor(
                id = "com.example.offline",
                name = "Offline",
                version = "1.0.0",
                capabilities = listOf(PluginCapability.NETWORK_INTERNET),
                grantedCapabilities = emptyList(),
                network = PluginNetworkPrefs(allowedOrigins = listOf("https://evil.example.com")),
            )
        val csp = PluginCspBuilder.buildDocumentCsp(plugin)
        assertTrue(csp.contains("worker-src 'self';"))
        assertTrue(csp.contains("connect-src 'self';"))
        assertTrue(csp.contains("frame-src 'none';"))
        assertTrue(csp.contains("object-src 'none';"))
        assertTrue(csp.contains("base-uri 'self'"))
        assertFalse(csp.contains("evil.example.com"))

        // W3C CSP3 §4.2.2: Web Workers / Service Workers and SVG/XML assets require CSP on all 2xx responses.
        val jsWorkerCsp = PluginAssetFetcher.resolveCspForStatus(plugin, statusCode = 200)
        assertEquals(csp, jsWorkerCsp)
        assertEquals(csp, PluginAssetFetcher.resolveCspForStatus(plugin, statusCode = 206))
        assertNull(PluginAssetFetcher.resolveCspForStatus(plugin, statusCode = 404))
        assertNull(PluginAssetFetcher.resolveCspForStatus(plugin, statusCode = 500))
    }

    @Test
    fun `PluginCspBuilder derives connect-src strictly from granted loopback lan internet and devServer`() {
        val loopback =
            PluginDescriptor(
                id = "com.example.loop",
                name = "Loop",
                version = "1.0.0",
                devServerUrl = "http://127.0.0.1:5173",
                grantedCapabilities = listOf(PluginCapability.NETWORK_LOOPBACK),
                network =
                    PluginNetworkPrefs(
                        allowedOrigins = listOf("http://127.0.0.2:8080", "http://192.168.1.2:8080", "https://public.example.com"),
                        allowCleartext = true,
                    ),
            )
        val loopCsp = PluginCspBuilder.buildDocumentCsp(loopback)
        assertTrue(loopCsp.contains("http://127.0.0.1:5173"))
        assertTrue(loopCsp.contains("ws://127.0.0.1:5173"))
        assertTrue(loopCsp.contains("http://127.0.0.2:8080"))
        assertTrue(loopCsp.contains("ws://127.0.0.2:8080"))
        assertFalse(loopCsp.contains("192.168.1.2"))
        assertFalse(loopCsp.contains("public.example.com"))

        val lan =
            PluginDescriptor(
                id = "com.example.lan",
                name = "LAN",
                version = "1.0.0",
                grantedCapabilities = listOf(PluginCapability.NETWORK_LAN),
                network =
                    PluginNetworkPrefs(
                        allowedOrigins = listOf("https://ha.local:8123", "https://public.example.com"),
                        allowCleartext = false,
                    ),
            )
        val lanCsp = PluginCspBuilder.buildDocumentCsp(lan)
        assertTrue(lanCsp.contains("https://ha.local:8123"))
        assertTrue(lanCsp.contains("wss://ha.local:8123"))
        assertFalse(lanCsp.contains("public.example.com"))

        val internet =
            PluginDescriptor(
                id = "com.example.net",
                name = "Net",
                version = "1.0.0",
                grantedCapabilities = listOf(PluginCapability.NETWORK_INTERNET),
                network =
                    PluginNetworkPrefs(
                        allowedOrigins = listOf("https://api.example.com", "http://api.example.com"),
                        allowCleartext = true,
                    ),
            )
        val netCsp = PluginCspBuilder.buildDocumentCsp(internet)
        assertTrue(netCsp.contains("https://api.example.com"))
        assertTrue(netCsp.contains("wss://api.example.com"))
        assertFalse(netCsp.contains("http://api.example.com"))
        assertFalse(netCsp.contains("ws://api.example.com"))
    }

    @Test
    fun `PluginBridgePolicy enforces isMainFrame and exact origin on WebMessageListener messages`() {
        val origin = "https://demo.plugins.sideveil.local"
        assertTrue(PluginBridgePolicy.isTrustedListenerMessage(origin, isMainFrame = true, origin, isUntrustedEmbed = false))
        assertFalse(PluginBridgePolicy.isTrustedListenerMessage(origin, isMainFrame = false, origin, isUntrustedEmbed = false))
        assertFalse(
            PluginBridgePolicy.isTrustedListenerMessage(
                "https://other.plugins.sideveil.local",
                isMainFrame = true,
                origin,
                isUntrustedEmbed = false,
            ),
        )
        assertFalse(
            PluginBridgePolicy.isTrustedListenerMessage(
                "https://192.168.1.1:8080",
                isMainFrame = true,
                origin,
                isUntrustedEmbed = false,
            ),
        )
        assertFalse(PluginBridgePolicy.isTrustedListenerMessage(origin, isMainFrame = true, origin, isUntrustedEmbed = true))
        assertFalse(PluginBridgePolicy.isCommittedMainFrameTrusted(null, origin, isUntrustedEmbed = false))
        assertTrue(PluginBridgePolicy.isCommittedMainFrameTrusted("$origin/index.html", origin, isUntrustedEmbed = false))
    }

    @Test
    fun `PluginBridgePolicy parses host attach and builds host ready with grantedCapabilities`() {
        val docStartAttach = PluginBridgePolicy.parseAttachSignal("""{"event":"host.attach","protocolVersion":1}""")
        assertNotNull(docStartAttach)
        assertFalse(docStartAttach!!.usesListenerChannel)
        assertEquals(1L, docStartAttach.protocolVersion)

        val listenerAttach =
            PluginBridgePolicy.parseAttachSignal("""{"event":"host.attach","channel":"listener","protocolVersion":1}""")
        assertNotNull(listenerAttach)
        assertTrue(listenerAttach!!.usesListenerChannel)

        assertNull(PluginBridgePolicy.parseAttachSignal("""{"jsonrpc":"2.0","id":"1","method":"daemon.ping"}"""))

        val desc =
            PluginDescriptor(
                id = "com.example.demo",
                name = "Demo",
                version = "1.0.0",
                capabilities = listOf(PluginCapability.STORAGE, PluginCapability.SYSTEM_EXEC),
                grantedCapabilities = listOf(PluginCapability.STORAGE),
            )
        val readyRaw = PluginBridgePolicy.buildHostReadyEnvelope(desc)
        val obj = SideVeilWireJson.parseToJsonElement(readyRaw).jsonObject
        assertEquals(SideVeilEvents.HOST_READY, obj["event"]?.jsonPrimitive?.content)
        val payload = obj["payload"]!!.jsonObject
        assertEquals(SideVeilProtocolVersions.PROTOCOL_VERSION.toString(), payload["protocolVersion"]?.jsonPrimitive?.content)
        assertEquals(SideVeilProtocolVersions.PLUGIN_API_VERSION.toString(), payload["apiVersion"]?.jsonPrimitive?.content)
        assertEquals("com.example.demo", payload["pluginId"]?.jsonPrimitive?.content)
        assertEquals(listOf("storage"), payload["grantedCapabilities"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun `PluginBridgeChannelState routes replies without double delivery across listener and legacy port`() {
        val state = PluginBridgeChannelState()
        assertNull(state.resolveEventChannel())

        // Document-start attach registers replyProxy, but legacy client still relies on WebMessagePort.
        state.onReplyProxyAttached(usesListenerChannel = false)
        assertNull(state.resolveEventChannel())
        state.onWebMessagePortAttached()
        assertEquals(BridgeDeliveryChannel.WEB_MESSAGE_PORT, state.resolveEventChannel())
        assertEquals(BridgeDeliveryChannel.WEB_MESSAGE_PORT, state.resolveReplyChannel(BridgeDeliveryChannel.REPLY_PROXY))

        // Listener-aware client upgrades exclusively to REPLY_PROXY.
        state.onReplyProxyAttached(usesListenerChannel = true)
        state.onWebMessagePortClosed()
        assertEquals(BridgeDeliveryChannel.REPLY_PROXY, state.resolveEventChannel())
        assertEquals(BridgeDeliveryChannel.REPLY_PROXY, state.resolveReplyChannel(BridgeDeliveryChannel.REPLY_PROXY))
    }

    @Test
    fun `PluginWebViewProfileManager produces distinct deterministic profile names`() {
        assertEquals("plugin_com.example.alpha", PluginWebViewProfileManager.profileNameFor("com.example.alpha", false))
        assertEquals("embed_com.example.alpha", PluginWebViewProfileManager.profileNameFor("com.example.alpha", true))
        assertEquals("plugin_bad_id_name", PluginWebViewProfileManager.profileNameFor("bad/id:name", false))
    }
}
