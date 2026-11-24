package pochita.sideveil.protocol

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pochita.sideveil.protocol.generated.SideVeilBuiltinDiagnostic

class PluginProtocolTest {
    @Test
    fun `decodes Rust PluginsSnapshotPayload JSON cleanly`() {
        val raw =
            """
            {
              "plugins": [
                {
                  "id": "com.example.alpha",
                  "name": "Alpha",
                  "version": "1.0.0",
                  "entry": "web/index.html",
                  "builtin": false,
                  "enabled": true,
                  "priority": 20,
                  "packages": ["com.example.game"],
                  "global": false,
                  "hasScopeOverride": false,
                  "backend": "uds-native",
                  "backendState": "online",
                  "capabilities": ["storage", "toast"],
                  "surface": { "backdrop": "transparent", "widthDp": 380, "heightPercent": 80 }
                }
              ],
              "matchedPluginIds": ["com.example.alpha"],
              "activePluginId": "com.example.alpha",
              "foregroundPackage": "com.example.game"
            }
            """.trimIndent()
        val parsed = SideVeilWireJson.decodeFromString<PluginsSnapshotPayload>(raw)
        assertEquals(1, parsed.plugins.size)
        val alpha = parsed.plugins.single()
        assertEquals("com.example.alpha", alpha.id)
        assertEquals(PluginBackendKind.UDS_NATIVE, alpha.backend)
        assertEquals(PluginBackendState.ONLINE, alpha.backendState)
        assertEquals(PluginSurfaceBackdrop.TRANSPARENT, alpha.surface.backdrop)
        assertEquals(380, alpha.surface.widthDp)
        assertTrue(alpha.capabilities.contains(PluginCapability.STORAGE))
        assertEquals("com.example.alpha", parsed.activePluginId)
    }

    @Test
    fun `RpcRequest encodes server-stamped pluginId`() {
        val req = RpcRequest(id = "r1", method = SideVeilMethods.PLUGIN_INVOKE, pluginId = "com.example.alpha")
        val encoded = SideVeilWireJson.encodeToString(req)
        assertTrue(encoded.contains("\"pluginId\":\"com.example.alpha\""))
    }

    @Test
    fun `SideVeilTimeouts clamps plugin invoke timeoutMs with wire margin`() {
        val plain = RpcRequest(id = "r1", method = SideVeilMethods.DAEMON_STATUS)
        assertEquals(8_000L, SideVeilTimeouts.resolveForwardTimeoutMs(plain))

        val defaultInvoke = RpcRequest(id = "r2", method = SideVeilMethods.PLUGIN_INVOKE)
        assertEquals(8_000L, SideVeilTimeouts.resolveForwardTimeoutMs(defaultInvoke))

        val customInvoke =
            RpcRequest(
                id = "r3",
                method = SideVeilMethods.PLUGIN_INVOKE,
                params =
                    buildJsonObject {
                        put("method", JsonPrimitive("maintenance.run"))
                        put("timeoutMs", JsonPrimitive(25_000L))
                    },
            )
        assertEquals(26_000L, SideVeilTimeouts.resolveForwardTimeoutMs(customInvoke))

        val cappedInvoke =
            RpcRequest(
                id = "r4",
                method = SideVeilMethods.PLUGIN_INVOKE,
                params = buildJsonObject { put("timeoutMs", JsonPrimitive(999_000L)) },
            )
        assertEquals(61_000L, SideVeilTimeouts.resolveForwardTimeoutMs(cappedInvoke))

        val flooredInvoke =
            RpcRequest(
                id = "r5",
                method = SideVeilMethods.PLUGIN_INVOKE,
                params = buildJsonObject { put("timeoutMs", JsonPrimitive(50L)) },
            )
        assertEquals(2_000L, SideVeilTimeouts.resolveForwardTimeoutMs(flooredInvoke))
    }

    @Test
    fun `decodes embed network keepAlive and network capabilities cleanly`() {
        val raw =
            """
            {
              "plugins": [
                {
                  "id": "homelab.router",
                  "name": "Router UI",
                  "version": "1.2.0",
                  "capabilities": ["network-loopback", "network-lan", "network-context"],
                  "surface": { "backdrop": "opaque", "keepAlive": true },
                  "embed": {
                    "url": "http://192.168.1.1:8080/ui",
                    "allowedOrigins": ["http://192.168.1.1:8080", "https://router.lan"],
                    "allowCleartext": true,
                    "wideViewport": true,
                    "allowZoom": true,
                    "webHistoryBack": false
                  },
                  "network": {
                    "allowedOrigins": ["http://127.0.0.1:9090"],
                    "allowCleartext": true,
                    "spaFallback": true
                  }
                }
              ],
              "matchedPluginIds": ["homelab.router"],
              "activePluginId": "homelab.router",
              "foregroundPackage": "com.example.app"
            }
            """.trimIndent()
        val parsed = SideVeilWireJson.decodeFromString<PluginsSnapshotPayload>(raw)
        val plugin = parsed.plugins.single()
        assertTrue(plugin.surface.keepAlive)
        assertTrue(plugin.capabilities.contains(PluginCapability.NETWORK_LOOPBACK))
        assertTrue(plugin.capabilities.contains(PluginCapability.NETWORK_LAN))
        assertTrue(plugin.capabilities.contains(PluginCapability.NETWORK_CONTEXT))

        val embed = plugin.embed
        assertNotNull(embed)
        assertEquals("http://192.168.1.1:8080/ui", embed?.url)
        assertEquals(listOf("http://192.168.1.1:8080", "https://router.lan"), embed?.allowedOrigins)
        assertTrue(embed?.allowCleartext == true)
        assertTrue(embed?.wideViewport == true)
        assertTrue(embed?.allowZoom == true)
        assertFalse(embed?.webHistoryBack == true)

        val network = plugin.network
        assertNotNull(network)
        assertEquals(listOf("http://127.0.0.1:9090"), network?.allowedOrigins)
        assertTrue(network?.allowCleartext == true)
        assertTrue(network?.spaFallback == true)

        assertEquals("network.contextChanged", SideVeilEvents.NETWORK_CONTEXT_CHANGED)
        assertEquals("network.context", SideVeilMethods.NETWORK_CONTEXT)
    }

    @Test
    fun `builtinDiagnostic descriptor matches unified manifest contract`() {
        val diag = PluginDescriptor.builtinDiagnostic()
        assertEquals(BUILTIN_DIAG_PLUGIN_ID, diag.id)
        assertEquals("内置诊断", diag.name)
        assertEquals(SideVeilBuiltinDiagnostic.VERSION, diag.version)
        assertEquals(PluginOrigin.BUILTIN, diag.origin)
        assertFalse(diag.global)
        assertEquals(20, diag.priority)
        assertEquals(listOf(PluginCapability.STORAGE, PluginCapability.TOAST), diag.capabilities)
        assertEquals(listOf(PluginCapability.STORAGE, PluginCapability.TOAST), diag.grantedCapabilities)
    }

    @Test
    fun `decodes future unknown capabilities and enum tokens without dropping snapshot`() {
        val warnings = mutableListOf<Pair<String, String>>()
        val prevHook = PluginProtocolDiagnostics.onUnknownToken
        PluginProtocolDiagnostics.onUnknownToken = { field, token -> warnings += field to token }
        try {
            val futureJson =
                """
                {
                  "plugins": [
                    {
                      "id": "com.example.future",
                      "name": "Future Plugin",
                      "version": "2.0.0",
                      "origin": "marketplace-v2",
                      "backend": "wasm-wasi",
                      "backendState": "warming",
                      "approvalStatus": "future-state",
                      "daemonStatus": "restarting-v2",
                      "capabilities": ["storage", "future-cap-v2", "network-internet"],
                      "grantedCapabilities": ["storage", "future-cap-v2"],
                      "surface": { "backdrop": "acrylic-v2", "presentation": "split-view" },
                      "brandNewFutureField": { "nested": true }
                    }
                  ],
                  "matchedPluginIds": ["com.example.future"],
                  "activePluginId": "com.example.future",
                  "foregroundPackage": "com.example.app"
                }
                """.trimIndent()
            val snapshot = SideVeilWireJson.decodeFromString<PluginsSnapshotPayload>(futureJson)
            assertEquals(1, snapshot.plugins.size)
            val plugin = snapshot.plugins.single()
            assertEquals("com.example.future", plugin.id)
            assertEquals(PluginOrigin.INSTALLED, plugin.origin)
            assertEquals(PluginBackendKind.NONE, plugin.backend)
            assertEquals(PluginBackendState.NONE, plugin.backendState)
            assertEquals(PluginApprovalStatus.PENDING_REVIEW, plugin.approvalStatus)
            assertEquals(PluginDaemonStatus.NOT_CONFIGURED, plugin.daemonStatus)
            assertEquals(PluginSurfaceBackdrop.NATIVE_GLASS, plugin.surface.backdrop)
            assertEquals(PluginPresentationMode.DRAWER, plugin.surface.presentation)
            assertEquals(listOf(PluginCapability.STORAGE, PluginCapability.NETWORK_INTERNET), plugin.capabilities)
            assertEquals(listOf(PluginCapability.STORAGE), plugin.grantedCapabilities)
            assertTrue(warnings.any { (f, t) -> f == "capability" && t == "future-cap-v2" })
            assertTrue(warnings.any { (f, t) -> f == "origin" && t == "marketplace-v2" })
        } finally {
            PluginProtocolDiagnostics.onUnknownToken = prevHook
        }
    }

    @Test
    fun `PanelCommandPayload decodes both legacy command and reloadPlugin action`() {
        val actionJson = """{"action":"reloadPlugin","pluginId":"com.example.dev"}"""
        val decodedAction = SideVeilWireJson.decodeFromString<PanelCommandPayload>(actionJson)
        assertEquals("com.example.dev", decodedAction.reloadPluginId())

        val commandJson = """{"command":"reloadPlugin","pluginId":"com.example.dev"}"""
        val decodedCommand = SideVeilWireJson.decodeFromString<PanelCommandPayload>(commandJson)
        assertEquals("com.example.dev", decodedCommand.reloadPluginId())

        val openDecoded = SideVeilWireJson.decodeFromString<PanelCommandPayload>("""{"command":"open"}""")
        assertEquals(PanelCommand.OPEN, openDecoded.command)
        assertEquals(null, openDecoded.reloadPluginId())
    }
}
