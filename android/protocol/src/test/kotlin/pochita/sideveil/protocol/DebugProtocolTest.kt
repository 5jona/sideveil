package pochita.sideveil.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DebugProtocolTest {
    @Test
    fun `debug method constants match frozen wire contract`() {
        assertEquals("debug.inspectUi", SideVeilMethods.DEBUG_INSPECT_UI)
        assertEquals("debug.setWebViewDebugging", SideVeilMethods.DEBUG_SET_WEBVIEW_DEBUGGING)
    }

    @Test
    fun `DebugSetWebViewDebuggingParams and DebugCdpStatusResult round-trip cleanly`() {
        val paramsJson = SideVeilWireJson.encodeToString(DebugSetWebViewDebuggingParams(enabled = true))
        assertEquals("""{"enabled":true}""", paramsJson)
        val decodedParams = SideVeilWireJson.decodeFromString<DebugSetWebViewDebuggingParams>(paramsJson)
        assertTrue(decodedParams.enabled)

        val status =
            DebugCdpStatusResult(
                ok = true,
                webviewDebuggingEnabled = true,
                hostPid = 4242,
                cdpSocket = "webview_devtools_remote_4242",
                restartRecommended = false,
            )
        val statusJson = SideVeilWireJson.encodeToString(status)
        assertTrue(statusJson.contains("\"cdpSocket\":\"webview_devtools_remote_4242\""))
        assertEquals(status, SideVeilWireJson.decodeFromString<DebugCdpStatusResult>(statusJson))
    }

    @Test
    fun `DebugInspectUiSnapshot round-trips with display, panel, agsl, webViews, and frameMetrics`() {
        val snapshot = sampleInspectUiSnapshot()
        val encoded = SideVeilWireJson.encodeToString(snapshot)
        assertTrue(encoded.contains("\"hostPid\":9001"))
        assertTrue(encoded.contains("\"cdpSocket\":\"webview_devtools_remote_9001\""))
        assertTrue(encoded.contains("\"display\":{"))
        assertTrue(encoded.contains("\"refreshRateHz\":120.0"))
        assertTrue(encoded.contains("\"agsl\":{"))
        assertTrue(encoded.contains("\"shaderCompiled\":true"))
        assertTrue(encoded.contains("\"panel\":{"))
        assertTrue(encoded.contains("\"frameMetrics\":{"))

        val decoded = SideVeilWireJson.decodeFromString<DebugInspectUiSnapshot>(encoded)
        assertEquals(snapshot, decoded)
        assertNull(decoded.pinnedPluginId)
        assertFalse(decoded.webViews.first().canGoBack)
    }

    private fun sampleInspectUiSnapshot(): DebugInspectUiSnapshot {
        val agsl = sampleAgslSnapshot()
        val frameMetrics =
            DebugFrameMetricsSnapshot(
                sampleCount = 64,
                p50Ms = 4.2,
                p95Ms = 7.8,
                p99Ms = 8.1,
                maxMs = 9.4,
                drawP95Ms = 2.1,
                gpuP95Ms = 3.5,
                jankCount = 1,
                deadlineMs = 8.33,
            )
        return DebugInspectUiSnapshot(
            hostPid = 9001,
            webviewDebuggingEnabled = true,
            cdpSocket = "webview_devtools_remote_9001",
            activePluginId = "sideveil.diagnostic",
            pinnedPluginId = null,
            activeHudPluginId = "sideveil.perf-hud",
            matchedPluginIds = listOf("sideveil.diagnostic", "strata.manager"),
            display =
                DebugDisplaySnapshot(
                    widthPx = 1440,
                    heightPx = 3168,
                    density = 3.5f,
                    rotation = 0,
                    refreshRateHz = 120f,
                    activeModeId = 1,
                    maxRefreshRateHz = 120f,
                    maxSupportedModeId = 1,
                    crossWindowBlurSupported = true,
                ),
            safeBounds =
                DebugSafeBoundsSnapshot(
                    screenWidthPx = 1440,
                    screenHeightPx = 3168,
                    safeTopPx = 112,
                    safeBottomPx = 64,
                ),
            agsl = agsl,
            panel = samplePanelSnapshot(agsl),
            pinned = DebugOverlayWindowSnapshot(name = "pinned", attached = true, visible = false),
            hud =
                DebugOverlayWindowSnapshot(
                    name = "hud",
                    attached = true,
                    visible = true,
                    activePluginId = "sideveil.perf-hud",
                    clickThrough = true,
                ),
            rail = DebugOverlayWindowSnapshot(name = "rail", attached = true, visible = true),
            edge = DebugOverlayWindowSnapshot(name = "edge", attached = true, visible = false, clickThrough = true),
            webViews =
                listOf(
                    DebugWebViewSnapshot(
                        pluginId = "sideveil.diagnostic",
                        url = "https://sideveil.diagnostic.plugins.sideveil.local/index.html",
                        attachedToParent = true,
                        isActive = true,
                        rendererPriority = "important",
                        rendererWaivedWhenNotVisible = false,
                        widthPx = 1020,
                        heightPx = 1800,
                    ),
                ),
            frameMetrics = frameMetrics,
            windowFrameMetrics = mapOf("panel" to frameMetrics),
        )
    }

    private fun sampleAgslSnapshot(): DebugAgslSnapshot =
        DebugAgslSnapshot(
            shaderCompiled = true,
            fallbackActive = false,
            themeId = "coloros-glass",
            isDark = true,
            blurActive = true,
            cornerRadiusPx = 66f,
            drawableAlpha = 255,
            motionProgress = 1f,
            velocityShift = 0f,
            hasDynamicOverride = true,
            boundsWidthPx = 1020,
            boundsHeightPx = 1800,
        )

    private fun samplePanelSnapshot(agsl: DebugAgslSnapshot): DebugPanelWindowSnapshot =
        DebugPanelWindowSnapshot(
            attached = true,
            visible = true,
            state = PanelState.OPEN,
            edgeSide = EdgeSide.RIGHT,
            presentationMode = PluginPresentationMode.DRAWER,
            pluginBackdrop = PluginSurfaceBackdrop.NATIVE_GLASS,
            railInsetDp = 56,
            x = 196,
            width = 1020,
            height = 1800,
            gravity = 21,
            flags = 0x1040020,
            preferredRefreshRateHz = 120f,
            preferredDisplayModeId = 1,
            appliedBlurRadiusPx = 160,
            targetBlurRadiusPx = 160,
            systemBlurSupported = true,
            motionProgress = 1f,
            agsl = agsl,
        )
}
