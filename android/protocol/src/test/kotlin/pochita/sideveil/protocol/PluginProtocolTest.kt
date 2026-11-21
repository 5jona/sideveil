package pochita.sideveil.protocol

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
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
}
