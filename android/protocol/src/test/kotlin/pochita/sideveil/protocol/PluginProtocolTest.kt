package pochita.sideveil.protocol

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
        val parsed = SideveilWireJson.decodeFromString<PluginsSnapshotPayload>(raw)
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
        val req = RpcRequest(id = "r1", method = SideveilMethods.PLUGIN_INVOKE, pluginId = "com.example.alpha")
        val encoded = SideveilWireJson.encodeToString(req)
        assertTrue(encoded.contains("\"pluginId\":\"com.example.alpha\""))
    }
}
