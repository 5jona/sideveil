package pochita.sideveil.host.debug

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pochita.sideveil.host.plugin.WebViewCrashRegistry
import pochita.sideveil.host.plugin.WebViewDiagnosticsSanitizer
import pochita.sideveil.protocol.DebugInspectUiSnapshot
import pochita.sideveil.protocol.DebugWebViewSnapshot
import pochita.sideveil.protocol.RpcRequest
import pochita.sideveil.protocol.SideVeilErrorCodes
import pochita.sideveil.protocol.SideVeilMethods
import pochita.sideveil.protocol.SideVeilTimeouts

class HostDebugInspectorTest {
    @Test
    fun `resolveInspectTimeoutMs clamps to host bounds and falls back to default`() {
        assertEquals(
            HostDebugRpcRouter.DEFAULT_INSPECT_TIMEOUT_MS,
            HostDebugRpcRouter.resolveInspectTimeoutMs(null),
        )
        assertEquals(
            HostDebugRpcRouter.MIN_INSPECT_TIMEOUT_MS,
            HostDebugRpcRouter.resolveInspectTimeoutMs(buildJsonObject { put("timeoutMs", JsonPrimitive(50)) }),
        )
        assertEquals(
            HostDebugRpcRouter.MAX_INSPECT_TIMEOUT_MS,
            HostDebugRpcRouter.resolveInspectTimeoutMs(buildJsonObject { put("timeoutMs", JsonPrimitive(120_000)) }),
        )
        assertEquals(
            14_500L,
            HostDebugRpcRouter.resolveInspectTimeoutMs(buildJsonObject { put("timeoutMs", JsonPrimitive(14_500)) }),
        )
    }

    @Test
    fun `router dispatches inspectUi with bounded timeout, resetFrameMetrics, and rejects removed setWebViewDebugging`() =
        runBlocking {
            val observedTimeouts = mutableListOf<Long>()
            val buffer = FrameMetricsRingBuffer(capacity = 8)
            buffer.record(totalMs = 10.0, drawMs = 2.0, gpuMs = 4.0, deadlineMs = 8.33)
            assertEquals(1, buffer.snapshot().sampleCount)

            val router =
                HostDebugRpcRouter(
                    inspectUi = { timeoutMs ->
                        observedTimeouts += timeoutMs
                        DebugInspectUiSnapshot(
                            hostPid = 31337,
                            webViews =
                                listOf(
                                    DebugWebViewSnapshot(
                                        pluginId = "sideveil.diagnostic",
                                        dormant = true,
                                        crashCount = 2,
                                    ),
                                ),
                        )
                    },
                    resetFrameMetrics = buffer::reset,
                )

            val inspectResp =
                router.handleRpcRequest(
                    RpcRequest(
                        id = "req-1",
                        method = SideVeilMethods.DEBUG_INSPECT_UI,
                        params = buildJsonObject { put("timeoutMs", JsonPrimitive(18_000)) },
                    ),
                )
            assertNull(inspectResp.error)
            assertEquals(listOf(18_000L), observedTimeouts)
            val resultObj = inspectResp.result!!.jsonObject
            assertEquals("31337", resultObj["hostPid"]?.jsonPrimitive?.content)

            val resetResp =
                router.handleRpcRequest(
                    RpcRequest(id = "req-2", method = SideVeilMethods.DEBUG_RESET_FRAME_METRICS),
                )
            assertNull(resetResp.error)
            assertEquals(
                "true",
                resetResp.result!!
                    .jsonObject["ok"]
                    ?.jsonPrimitive
                    ?.content,
            )
            assertEquals(0, buffer.snapshot().sampleCount)

            val removedResp =
                router.handleRpcRequest(
                    RpcRequest(
                        id = "req-3",
                        method = SideVeilMethods.DEBUG_SET_WEBVIEW_DEBUGGING,
                        params = buildJsonObject { put("enabled", JsonPrimitive(true)) },
                    ),
                )
            assertNotNull(removedResp.error)
            assertEquals(SideVeilErrorCodes.METHOD_NOT_FOUND, removedResp.error?.code)
        }

    @Test
    fun `WebViewDiagnosticsSanitizer strips query fragments and userinfo while preserving origin and path`() {
        assertEquals(
            "https://sideveil.diagnostic.plugins.sideveil.local/assets/app.js",
            WebViewDiagnosticsSanitizer.sanitizeUrl(
                "https://user:pass@sideveil.diagnostic.plugins.sideveil.local/assets/app.js?token=abc#frag",
            ),
        )
        assertEquals(
            "http://127.0.0.1:5175/index.html",
            WebViewDiagnosticsSanitizer.sanitizeUrl("http://127.0.0.1:5175/index.html?v=42"),
        )
        assertEquals("", WebViewDiagnosticsSanitizer.sanitizeUrl("   "))
        assertEquals("", WebViewDiagnosticsSanitizer.sanitizeUrl(null))
    }

    @Test
    fun `WebViewCrashRegistry tracks per-plugin and aggregate crash counts across recoveries`() {
        val registry = WebViewCrashRegistry()
        assertEquals(0, registry.countFor("com.example.a"))
        assertEquals(0, registry.totalCrashes())

        assertEquals(1, registry.recordCrash("com.example.a"))
        assertEquals(2, registry.recordCrash("com.example.a"))
        assertEquals(1, registry.recordCrash("com.example.b"))

        assertEquals(2, registry.countFor("com.example.a"))
        assertEquals(1, registry.countFor("com.example.b"))
        assertEquals(3, registry.totalCrashes())
    }

    @Test
    fun `SideVeilTimeouts extends forward timeout for debug inspectUi with wire margin`() {
        val req =
            RpcRequest(
                id = "t1",
                method = SideVeilMethods.DEBUG_INSPECT_UI,
                params = buildJsonObject { put("timeoutMs", JsonPrimitive(20_000)) },
            )
        assertTrue(SideVeilTimeouts.resolveForwardTimeoutMs(req) >= 22_000L)
    }
}
