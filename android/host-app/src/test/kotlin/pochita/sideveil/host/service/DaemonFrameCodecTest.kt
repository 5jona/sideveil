package pochita.sideveil.host.service

import android.view.WindowManager
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pochita.sideveil.host.debug.WebViewDebuggingController
import pochita.sideveil.host.pinned.PinnedWindowLayout
import pochita.sideveil.protocol.DebugInspectUiParams
import pochita.sideveil.protocol.RpcRequest
import pochita.sideveil.protocol.RpcResponse
import pochita.sideveil.protocol.SideVeilEvents
import pochita.sideveil.protocol.SideVeilMethods
import pochita.sideveil.protocol.SideVeilWireJson

class DaemonFrameCodecTest {
    @Test
    fun `decodes host event, rpc reply, and inbound host rpc messages distinctly`() {
        val eventWire =
            DaemonFrameCodec.encodeEvent(
                SideVeilEvents.PANEL_COMMAND,
                buildJsonObject { put("command", JsonPrimitive("open")) },
            )
        val eventMsg = DaemonFrameCodec.decodeIncomingText(eventWire)
        assertTrue(eventMsg is DaemonInboundMessage.Event)
        assertEquals(SideVeilEvents.PANEL_COMMAND, (eventMsg as DaemonInboundMessage.Event).envelope.event)

        val replyWire =
            DaemonFrameCodec.encodeRpcResponse(
                RpcResponse(id = "h1_42", result = buildJsonObject { put("ok", JsonPrimitive(true)) }),
            )
        val replyMsg = DaemonFrameCodec.decodeIncomingText(replyWire)
        assertTrue(replyMsg is DaemonInboundMessage.RpcReply)
        assertEquals("h1_42", (replyMsg as DaemonInboundMessage.RpcReply).response.id)

        val inboundRpcWire =
            DaemonFrameCodec.encodeRpcRequest(
                RpcRequest(id = "srv-9", method = SideVeilMethods.DEBUG_INSPECT_UI),
            )
        val inboundMsg = DaemonFrameCodec.decodeIncomingText(inboundRpcWire)
        assertTrue(inboundMsg is DaemonInboundMessage.InboundRpc)
        assertEquals(SideVeilMethods.DEBUG_INSPECT_UI, (inboundMsg as DaemonInboundMessage.InboundRpc).request.method)

        assertNull(DaemonFrameCodec.decodeIncomingText("not-json"))
        assertNull(DaemonFrameCodec.decodeIncomingText("{}"))
    }

    @Test
    fun `classifies hub-initiated debug reverse RPC frames and rejects null method`() {
        val inspectRaw = """{"jsonrpc":"2.0","id":"hub-rpc-0","method":"debug.inspectUi","params":{"timeoutMs":12000}}"""
        val inspectMsg = DaemonFrameCodec.decodeIncomingText(inspectRaw) as DaemonInboundMessage.InboundRpc
        assertEquals("hub-rpc-0", inspectMsg.request.id)
        assertEquals(SideVeilMethods.DEBUG_INSPECT_UI, inspectMsg.request.method)
        val decodedInspect = SideVeilWireJson.decodeFromJsonElement<DebugInspectUiParams>(inspectMsg.request.params!!)
        assertEquals(12_000L, decodedInspect.timeoutMs)

        val resetRaw = """{"jsonrpc":"2.0","id":"hub-rpc-1","method":"debug.resetFrameMetrics","params":null}"""
        val resetMsg = DaemonFrameCodec.decodeIncomingText(resetRaw) as DaemonInboundMessage.InboundRpc
        assertEquals(SideVeilMethods.DEBUG_RESET_FRAME_METRICS, resetMsg.request.method)
        assertNull(resetMsg.request.params)

        val nullMethodRaw = """{"jsonrpc":"2.0","id":"hub-rpc-2","method":null,"result":{"ok":true}}"""
        assertNull(DaemonFrameCodec.decodeIncomingText(nullMethodRaw))
    }

    @Test
    fun `WebViewDebuggingController activates on config or devServerUrl and avoids duplicate transitions`() {
        val transitions = mutableListOf<Boolean>()
        val controller =
            WebViewDebuggingController(
                applyDebugging = { transitions += it },
                runOnMain = { block -> block() },
            )
        assertFalse(controller.effectiveEnabled)
        assertTrue(transitions.isEmpty())

        assertTrue(controller.setHasDevServer(true))
        assertEquals(listOf(true), transitions)

        // Enabling config while devServer is already active must not trigger duplicate apply.
        assertTrue(controller.setConfigEnabled(true))
        assertEquals(listOf(true), transitions)

        // Clearing devServer keeps debugging enabled because config is still true.
        assertTrue(controller.setHasDevServer(false))
        assertEquals(listOf(true), transitions)

        // Disabling config turns WebView debugging off once neither condition holds.
        assertFalse(controller.setConfigEnabled(false))
        assertEquals(listOf(true, false), transitions)
    }

    @Test
    fun `pinned window flags default to non-focusable and clear focus flag only for ime`() {
        val normalFlags = PinnedWindowLayout.computeFlags(clickThrough = false, imeFocused = false)
        assertTrue((normalFlags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) != 0)
        assertTrue((normalFlags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) == 0)

        val imeFlags = PinnedWindowLayout.computeFlags(clickThrough = false, imeFocused = true)
        assertTrue((imeFlags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) == 0)
        assertTrue((imeFlags and WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL) != 0)
        assertTrue((imeFlags and WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH) != 0)

        val clickThroughFlags = PinnedWindowLayout.computeFlags(clickThrough = true, imeFocused = true)
        assertTrue((clickThroughFlags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) != 0)
        assertTrue((clickThroughFlags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) != 0)
    }
}
