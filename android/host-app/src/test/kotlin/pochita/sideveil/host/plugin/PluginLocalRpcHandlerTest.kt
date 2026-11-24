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
import pochita.sideveil.protocol.HostLocalAuditPayload
import pochita.sideveil.protocol.PluginCapability
import pochita.sideveil.protocol.PluginDescriptor
import pochita.sideveil.protocol.PluginOrigin
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

    private fun pluginWith(
        vararg granted: PluginCapability,
        requested: List<PluginCapability> = granted.toList(),
        builtin: Boolean = false,
    ): PluginDescriptor =
        PluginDescriptor(
            id = if (builtin) "sideveil.diagnostic" else "example.test",
            name = "Test Plugin",
            version = "0.1.0",
            origin = if (builtin) PluginOrigin.BUILTIN else PluginOrigin.INSTALLED,
            builtin = builtin,
            capabilities = requested,
            grantedCapabilities = granted.toList(),
        )

    private fun invoke(
        handler: PluginLocalRpcHandler,
        req: RpcRequest,
        descriptor: PluginDescriptor,
        isForegroundVisible: Boolean = true,
    ): RpcResponse {
        var rawReply: String? = null
        assertTrue(handler.tryHandle(req, descriptor, isForegroundVisible) { rawReply = it })
        assertNotNull(rawReply)
        return SideVeilWireJson.decodeFromString(rawReply!!)
    }

    @Test
    fun `returns INVALID_PARAMS instead of throwing on non-object params or wrong field types`() {
        val actions = FakeActions()
        val handler = PluginLocalRpcHandler(actions = actions, postOnMain = { it.run() }, onWarnLog = {})
        val allCaps = pluginWith(PluginCapability.TOAST, PluginCapability.CLIPBOARD, PluginCapability.HAPTICS)

        val malformedParams =
            listOf(
                SideVeilMethods.UI_TOAST to JsonArray(listOf(JsonPrimitive("hi"))),
                SideVeilMethods.UI_TOAST to JsonPrimitive("hi"),
                SideVeilMethods.UI_TOAST to buildJsonObject { put("message", JsonPrimitive(123)) },
                SideVeilMethods.UI_TOAST to buildJsonObject { put("message", JsonObject(emptyMap())) },
                SideVeilMethods.UI_TOAST to buildJsonObject { put("message", JsonPrimitive("a".repeat(241))) },
                SideVeilMethods.CLIPBOARD_GET to JsonArray(emptyList()),
                SideVeilMethods.CLIPBOARD_SET to JsonPrimitive(42),
                SideVeilMethods.CLIPBOARD_SET to buildJsonObject { put("text", JsonArray(emptyList())) },
                SideVeilMethods.CLIPBOARD_SET to buildJsonObject { put("text", JsonPrimitive("x".repeat(65 * 1024))) },
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
    fun `denies ungranted capabilities and enforces no builtin wildcard bypass with audit trail`() {
        val actions = FakeActions()
        val audits = mutableListOf<HostLocalAuditPayload>()
        val warnings = mutableListOf<String>()
        val handler =
            PluginLocalRpcHandler(
                actions = actions,
                postOnMain = { it.run() },
                onAudit = { audits += it },
                onWarnLog = { warnings += it },
            )

        val requestedButNotGranted =
            pluginWith(
                requested = listOf(PluginCapability.TOAST, PluginCapability.CLIPBOARD, PluginCapability.HAPTICS),
            )
        for (method in listOf(
            SideVeilMethods.UI_TOAST,
            SideVeilMethods.CLIPBOARD_GET,
            SideVeilMethods.CLIPBOARD_SET,
            SideVeilMethods.HAPTICS_TRIGGER,
        )) {
            val resp = invoke(handler, RpcRequest(id = "deny-$method", method = method), requestedButNotGranted)
            assertEquals(SideVeilErrorCodes.PERMISSION_DENIED, resp.error?.code)
        }
        assertEquals(4, audits.count { !it.allowed })
        assertEquals(4, warnings.size)

        // Builtin diagnostic only declares/gets STORAGE + TOAST; CLIPBOARD and HAPTICS must be denied.
        val builtinDiag = PluginDescriptor.builtinDiagnostic()
        val clipDeny = invoke(handler, RpcRequest(id = "diag-clip", method = SideVeilMethods.CLIPBOARD_GET), builtinDiag)
        assertEquals(SideVeilErrorCodes.PERMISSION_DENIED, clipDeny.error?.code)
        val hapticDeny = invoke(handler, RpcRequest(id = "diag-hap", method = SideVeilMethods.HAPTICS_TRIGGER), builtinDiag)
        assertEquals(SideVeilErrorCodes.PERMISSION_DENIED, hapticDeny.error?.code)
        val toastOk =
            invoke(
                handler,
                RpcRequest(
                    id = "diag-toast",
                    method = SideVeilMethods.UI_TOAST,
                    params = buildJsonObject { put("message", JsonPrimitive("ok")) },
                ),
                builtinDiag,
            )
        assertNull(toastOk.error)
    }

    @Test
    fun `blocks all background local RPCs when plugin is not foreground visible`() {
        val actions = FakeActions()
        val audits = mutableListOf<HostLocalAuditPayload>()
        val handler =
            PluginLocalRpcHandler(
                actions = actions,
                postOnMain = { it.run() },
                onAudit = { audits += it },
                onWarnLog = {},
            )
        val allCapsPlugin = pluginWith(PluginCapability.CLIPBOARD, PluginCapability.TOAST, PluginCapability.HAPTICS)
        val bgCalls =
            listOf(
                RpcRequest(id = "bg-clip-get", method = SideVeilMethods.CLIPBOARD_GET),
                RpcRequest(
                    id = "bg-clip-set",
                    method = SideVeilMethods.CLIPBOARD_SET,
                    params = buildJsonObject { put("text", JsonPrimitive("poison")) },
                ),
                RpcRequest(
                    id = "bg-toast",
                    method = SideVeilMethods.UI_TOAST,
                    params = buildJsonObject { put("message", JsonPrimitive("spam")) },
                ),
                RpcRequest(
                    id = "bg-haptics",
                    method = SideVeilMethods.HAPTICS_TRIGGER,
                    params = buildJsonObject { put("kind", JsonPrimitive("click")) },
                ),
            )

        for (req in bgCalls) {
            val denied =
                invoke(
                    handler = handler,
                    req = req,
                    descriptor = allCapsPlugin,
                    isForegroundVisible = false,
                )
            assertEquals("expected PERMISSION_DENIED for ${req.method}", SideVeilErrorCodes.PERMISSION_DENIED, denied.error?.code)
            assertEquals("not-visible", audits.last().detail)
            assertEquals(req.method, audits.last().method)
            assertFalse(audits.last().allowed)
        }
        assertEquals("initial-clip", actions.clipboardText)
        assertTrue(actions.toasts.isEmpty())
        assertTrue(actions.haptics.isEmpty())
    }

    @Test
    fun `enforces per-method rate limits and resets after sliding window expires`() {
        var nowMs = 1_000L
        val actions = FakeActions()
        val audits = mutableListOf<HostLocalAuditPayload>()
        val handler =
            PluginLocalRpcHandler(
                actions = actions,
                postOnMain = { it.run() },
                onAudit = { audits += it },
                clockMs = { nowMs },
                onWarnLog = {},
            )
        val toastPlugin = pluginWith(PluginCapability.TOAST)
        val params = buildJsonObject { put("message", JsonPrimitive("ping")) }

        assertNull(invoke(handler, RpcRequest(id = "t1", method = SideVeilMethods.UI_TOAST, params = params), toastPlugin).error)
        assertNull(invoke(handler, RpcRequest(id = "t2", method = SideVeilMethods.UI_TOAST, params = params), toastPlugin).error)
        val limited = invoke(handler, RpcRequest(id = "t3", method = SideVeilMethods.UI_TOAST, params = params), toastPlugin)
        assertEquals(SideVeilErrorCodes.RATE_LIMITED, limited.error?.code)
        assertEquals("rate-limited", audits.last().detail)

        nowMs += 1_050L
        assertNull(invoke(handler, RpcRequest(id = "t4", method = SideVeilMethods.UI_TOAST, params = params), toastPlugin).error)
    }

    @Test
    fun `executes valid local RPC calls cleanly and emits allowed audit entries`() {
        val actions = FakeActions()
        val audits = mutableListOf<HostLocalAuditPayload>()
        val handler =
            PluginLocalRpcHandler(
                actions = actions,
                postOnMain = { it.run() },
                onAudit = { audits += it },
                onWarnLog = {},
            )
        val allCaps = pluginWith(PluginCapability.TOAST, PluginCapability.CLIPBOARD, PluginCapability.HAPTICS)

        assertFalse(handler.tryHandle(RpcRequest(id = "other", method = SideVeilMethods.DAEMON_PING), allCaps, true) {})

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
        assertEquals(4, audits.size)
        assertTrue(audits.all { it.allowed })
    }
}
