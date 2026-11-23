package pochita.sideveil.protocol

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

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
        assertEquals("0.1.0", diag.version)
        assertFalse(diag.global)
        assertEquals(20, diag.priority)
        assertEquals(listOf(PluginCapability.STORAGE, PluginCapability.TOAST), diag.capabilities)
    }
}
