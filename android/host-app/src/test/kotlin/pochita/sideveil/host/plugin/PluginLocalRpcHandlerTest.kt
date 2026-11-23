package pochita.sideveil.host.plugin

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
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
import pochita.sideveil.protocol.RpcRequest
import pochita.sideveil.protocol.RpcResponse
import pochita.sideveil.protocol.SideVeilErrorCodes
import pochita.sideveil.protocol.SideVeilMethods
import pochita.sideveil.protocol.SideVeilWireJson

class PluginLocalRpcHandlerTest {
    private class FakeActions : LocalRpcPlatformActions {
        val toasts = mutableListOf<String>()
        var clipboardText: String? = "initial-clip"
        val haptics = mutableListOf<String>()

        override fun showToast(message: String) {
            toasts += message
        }

        override fun readClipboardText(): String? = clipboardText

        override fun writeClipboardText(text: String) {
            clipboardText = text
        }

        override fun triggerHaptics(kind: String) {
            haptics += kind
        }
    }

    private fun pluginWith(vararg caps: PluginCapability): PluginDescriptor =
        PluginDescriptor(
            id = "example.test",
            name = "Test Plugin",
            version = "0.1.0",
            builtin = false,
            capabilities = caps.toList(),
        )

    private fun invoke(
        handler: PluginLocalRpcHandler,
        req: RpcRequest,
        descriptor: PluginDescriptor,
    ): RpcResponse {
        var rawReply: String? = null
        assertTrue(handler.tryHandle(req, descriptor) { rawReply = it })
        assertNotNull(rawReply)
        return SideVeilWireJson.decodeFromString(rawReply!!)
    }

    @Test
    fun `returns INVALID_PARAMS instead of throwing on non-object params or wrong field types`() {
        val actions = FakeActions()
        val handler = PluginLocalRpcHandler(actions = actions, postOnMain = { it.run() })
        val allCaps = pluginWith(PluginCapability.TOAST, PluginCapability.CLIPBOARD, PluginCapability.HAPTICS)

        val malformedParams =
            listOf(
                SideVeilMethods.UI_TOAST to JsonArray(listOf(JsonPrimitive("hi"))),
                SideVeilMethods.UI_TOAST to JsonPrimitive("hi"),
                SideVeilMethods.UI_TOAST to buildJsonObject { put("message", JsonPrimitive(123)) },
                SideVeilMethods.UI_TOAST to buildJsonObject { put("message", JsonObject(emptyMap())) },
                SideVeilMethods.CLIPBOARD_GET to JsonArray(emptyList()),
                SideVeilMethods.CLIPBOARD_SET to JsonPrimitive(42),
                SideVeilMethods.CLIPBOARD_SET to buildJsonObject { put("text", JsonArray(emptyList())) },
                SideVeilMethods.HAPTICS_TRIGGER to JsonPrimitive(true),
                SideVeilMethods.HAPTICS_TRIGGER to buildJsonObject { put("kind", JsonPrimitive(false)) },
            )

        for ((index, pair) in malformedParams.withIndex()) {
            val (method, params) = pair
            val resp = invoke(handler, RpcRequest(id = "bad-$index", method = method, params = params), allCaps)
            assertEquals("method=$method params=$params", SideVeilErrorCodes.INVALID_PARAMS, resp.error?.code)
            assertNull(resp.result)
        }
        assertTrue(actions.toasts.isEmpty())
        assertTrue(actions.haptics.isEmpty())
    }

    @Test
    fun `returns PERMISSION_DENIED constant when plugin lacks required capability`() {
        val actions = FakeActions()
        val handler = PluginLocalRpcHandler(actions = actions, postOnMain = { it.run() })
        val noCaps = pluginWith()

        for (method in listOf(
            SideVeilMethods.UI_TOAST,
            SideVeilMethods.CLIPBOARD_GET,
            SideVeilMethods.CLIPBOARD_SET,
            SideVeilMethods.HAPTICS_TRIGGER,
        )) {
            val resp = invoke(handler, RpcRequest(id = "deny-$method", method = method), noCaps)
            assertEquals(SideVeilErrorCodes.PERMISSION_DENIED, resp.error?.code)
        }
    }

    @Test
    fun `executes valid local RPC calls cleanly and ignores unrelated methods`() {
        val actions = FakeActions()
        val handler = PluginLocalRpcHandler(actions = actions, postOnMain = { it.run() })
        val allCaps = pluginWith(PluginCapability.TOAST, PluginCapability.CLIPBOARD, PluginCapability.HAPTICS)

        assertFalse(handler.tryHandle(RpcRequest(id = "other", method = SideVeilMethods.DAEMON_PING), allCaps) {})

        val toastResp =
            invoke(
                handler,
                RpcRequest(
                    id = "r1",
                    method = SideVeilMethods.UI_TOAST,
                    params = buildJsonObject { put("message", JsonPrimitive("Hello")) },
                ),
                allCaps,
            )
        assertNull(toastResp.error)
        assertEquals(listOf("Hello"), actions.toasts)

        val clipSet =
            invoke(
                handler,
                RpcRequest(
                    id = "r2",
                    method = SideVeilMethods.CLIPBOARD_SET,
                    params = buildJsonObject { put("text", JsonPrimitive("copied")) },
                ),
                allCaps,
            )
        assertNull(clipSet.error)
        assertEquals("copied", actions.clipboardText)

        val clipGet =
            invoke(
                handler,
                RpcRequest(id = "r3", method = SideVeilMethods.CLIPBOARD_GET, params = JsonNull),
                allCaps,
            )
        assertEquals(
            "copied",
            clipGet.result
                ?.jsonObject
                ?.get("text")
                ?.jsonPrimitive
                ?.content,
        )

        val hapticResp =
            invoke(
                handler,
                RpcRequest(
                    id = "r4",
                    method = SideVeilMethods.HAPTICS_TRIGGER,
                    params = buildJsonObject { put("kind", JsonPrimitive("click")) },
                ),
                allCaps,
            )
        assertNull(hapticResp.error)
        assertEquals(listOf("click"), actions.haptics)
    }
}
