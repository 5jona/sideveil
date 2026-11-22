package pochita.sideveil.protocol

import kotlinx.serialization.Serializable

@Serializable
data class DebugInspectUiParams(
    val timeoutMs: Long? = null,
)

@Serializable
data class DebugSetWebViewDebuggingParams(
    val enabled: Boolean,
)

@Serializable
data class DebugCdpStatusResult(
    val ok: Boolean = true,
    val webviewDebuggingEnabled: Boolean = false,
    val effectiveEnabled: Boolean = false,
    val devServerActive: Boolean = false,
    val hostPid: Int? = null,
    val cdpSocket: String? = null,
    val restartRecommended: Boolean = false,
)

@Serializable
data class DaemonLogsParams(
    val sinceSeq: Long? = null,
    val limit: Int? = null,
    val level: String? = null,
    val source: String? = null,
)

@Serializable
data class DaemonLogsResult(
    val lines: List<String> = emptyList(),
    val nextSeq: Long = 0L,
    val totalDropped: Long = 0L,
)

@Serializable
data class DebugDisplaySnapshot(
    val widthPx: Int = 0,
    val heightPx: Int = 0,
    val density: Float = 1f,
    val rotation: Int = 0,
    val refreshRateHz: Float = 0f,
    val activeModeId: Int = 0,
    val maxRefreshRateHz: Float = 0f,
    val maxSupportedModeId: Int = 0,
    val crossWindowBlurSupported: Boolean = false,
)

@Serializable
data class DebugSafeBoundsSnapshot(
    val screenWidthPx: Int = 0,
    val screenHeightPx: Int = 0,
    val safeTopPx: Int = 0,
    val safeBottomPx: Int = 0,
    val safeLeftPx: Int = 0,
    val safeRightPx: Int = 0,
    val imeBottomPx: Int = 0,
    val isTabletLandscape: Boolean = false,
)

@Serializable
data class DebugAgslSnapshot(
    val shaderCompiled: Boolean = false,
    val fallbackActive: Boolean = true,
    val themeId: String = "coloros-glass",
    val isDark: Boolean = true,
    val blurActive: Boolean = false,
    val cornerRadiusPx: Float = 0f,
    val drawableAlpha: Int = 255,
    val motionProgress: Float = 1f,
    val velocityShift: Float = 0f,
    val hasDynamicOverride: Boolean = false,
    val boundsWidthPx: Int = 0,
    val boundsHeightPx: Int = 0,
)

@Serializable
data class DebugPanelWindowSnapshot(
    val attached: Boolean = false,
    val visible: Boolean = false,
    val state: PanelState = PanelState.CLOSED,
    val closingInProgress: Boolean = false,
    val edgeSide: EdgeSide = EdgeSide.RIGHT,
    val presentationMode: PluginPresentationMode = PluginPresentationMode.DRAWER,
    val pluginBackdrop: PluginSurfaceBackdrop = PluginSurfaceBackdrop.NATIVE_GLASS,
    val railInsetDp: Int = 0,
    val x: Int = 0,
    val y: Int = 0,
    val width: Int = 0,
    val height: Int = 0,
    val gravity: Int = 0,
    val flags: Int = 0,
    val dimAmount: Float = 0f,
    val preferredRefreshRateHz: Float = 0f,
    val preferredDisplayModeId: Int = 0,
    val appliedBlurRadiusPx: Int = 0,
    val targetBlurRadiusPx: Int = 0,
    val systemBlurSupported: Boolean = false,
    val themeId: String = "coloros-glass",
    val colorScheme: String = "dark",
    val motionProgress: Float = 0f,
    val agsl: DebugAgslSnapshot = DebugAgslSnapshot(),
)

@Serializable
data class DebugOverlayWindowSnapshot(
    val name: String = "",
    val attached: Boolean = false,
    val visible: Boolean = false,
    val x: Int = 0,
    val y: Int = 0,
    val width: Int = 0,
    val height: Int = 0,
    val gravity: Int = 0,
    val flags: Int = 0,
    val alpha: Float = 1f,
    val preferredRefreshRateHz: Float = 0f,
    val preferredDisplayModeId: Int = 0,
    val appliedBlurRadiusPx: Int = 0,
    val clickThrough: Boolean = false,
    val imeFocused: Boolean = false,
    val activePluginId: String? = null,
    val agsl: DebugAgslSnapshot? = null,
)

@Serializable
data class DebugWebViewSnapshot(
    val pluginId: String,
    val url: String? = null,
    val title: String? = null,
    val canGoBack: Boolean = false,
    val attachedToParent: Boolean = false,
    val isActive: Boolean = false,
    val isPinned: Boolean = false,
    val keepAlive: Boolean = false,
    val trustMode: String = "trusted",
    val crossOriginIsolated: Boolean = false,
    val devServerUrl: String? = null,
    val backdrop: PluginSurfaceBackdrop = PluginSurfaceBackdrop.NATIVE_GLASS,
    val presentationMode: PluginPresentationMode = PluginPresentationMode.DRAWER,
    val backInterceptEnabled: Boolean = false,
    val rendererPriority: String = "bound",
    val rendererWaivedWhenNotVisible: Boolean = true,
    val dormant: Boolean = false,
    val crashCount: Int = 0,
    val widthPx: Int = 0,
    val heightPx: Int = 0,
)

@Serializable
data class DebugFrameMetricsSnapshot(
    val sampleCount: Int = 0,
    val p50Ms: Double = 0.0,
    val p95Ms: Double = 0.0,
    val p99Ms: Double = 0.0,
    val maxMs: Double = 0.0,
    val drawP95Ms: Double = 0.0,
    val gpuP95Ms: Double = 0.0,
    val jankCount: Int = 0,
    val deadlineMs: Double = 0.0,
)

@Serializable
data class DebugInspectUiSnapshot(
    val hostPid: Int = 0,
    val webviewDebuggingEnabled: Boolean = false,
    val cdpSocket: String? = null,
    val activePluginId: String = "",
    val pinnedPluginId: String? = null,
    val activeHudPluginId: String? = null,
    val matchedPluginIds: List<String> = emptyList(),
    val display: DebugDisplaySnapshot = DebugDisplaySnapshot(),
    val safeBounds: DebugSafeBoundsSnapshot = DebugSafeBoundsSnapshot(),
    val agsl: DebugAgslSnapshot = DebugAgslSnapshot(),
    val panel: DebugPanelWindowSnapshot = DebugPanelWindowSnapshot(),
    val pinned: DebugOverlayWindowSnapshot = DebugOverlayWindowSnapshot(name = "pinned"),
    val hud: DebugOverlayWindowSnapshot = DebugOverlayWindowSnapshot(name = "hud"),
    val rail: DebugOverlayWindowSnapshot = DebugOverlayWindowSnapshot(name = "rail"),
    val edge: DebugOverlayWindowSnapshot = DebugOverlayWindowSnapshot(name = "edge"),
    val webViews: List<DebugWebViewSnapshot> = emptyList(),
    val frameMetrics: DebugFrameMetricsSnapshot = DebugFrameMetricsSnapshot(),
    val windowFrameMetrics: Map<String, DebugFrameMetricsSnapshot> = emptyMap(),
    val gestureStats: DebugGestureStats? = null,
)
