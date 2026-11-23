package pochita.sideveil.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

typealias PanelState = pochita.sideveil.protocol.generated.PanelState

@Serializable
enum class EdgeSide {
    @SerialName("left")
    LEFT,

    @SerialName("right")
    RIGHT,
}

@Serializable
enum class ScopeMode {
    @SerialName("allowlist")
    ALLOWLIST,

    @SerialName("global")
    GLOBAL,
}

@Serializable
data class EdgeConfig(
    val side: EdgeSide = EdgeSide.RIGHT,
    val topPercent: Int = SideVeilConfigDefaults.EDGE_TOP_PERCENT,
    val heightDp: Int = SideVeilConfigDefaults.EDGE_HEIGHT_DP,
    val hotzoneWidthDp: Int = SideVeilConfigDefaults.EDGE_HOTZONE_WIDTH_DP,
    val triggerDistanceDp: Int = SideVeilConfigDefaults.EDGE_TRIGGER_DISTANCE_DP,
    val edgeOffsetDp: Int = SideVeilConfigDefaults.EDGE_OFFSET_DP,
    val handleVisible: Boolean = SideVeilConfigDefaults.EDGE_HANDLE_VISIBLE,
    val handleOpacity: Float = SideVeilConfigDefaults.EDGE_HANDLE_OPACITY,
    val dimInFullscreen: Boolean = SideVeilConfigDefaults.EDGE_DIM_IN_FULLSCREEN,
    val doubleSwipeInFullscreen: Boolean = SideVeilConfigDefaults.EDGE_DOUBLE_SWIPE_IN_FULLSCREEN,
    val idleAutoDimMs: Int = SideVeilConfigDefaults.EDGE_IDLE_AUTO_DIM_MS,
) {
    fun sanitized(): EdgeConfig =
        copy(
            topPercent =
                topPercent.coerceIn(
                    SideVeilConfigBounds.EDGE_TOP_PERCENT_MIN,
                    SideVeilConfigBounds.EDGE_TOP_PERCENT_MAX,
                ),
            heightDp =
                heightDp.coerceIn(
                    SideVeilConfigBounds.EDGE_HEIGHT_DP_MIN,
                    SideVeilConfigBounds.EDGE_HEIGHT_DP_MAX,
                ),
            hotzoneWidthDp =
                hotzoneWidthDp.coerceIn(
                    SideVeilConfigBounds.EDGE_HOTZONE_WIDTH_DP_MIN,
                    SideVeilConfigBounds.EDGE_HOTZONE_WIDTH_DP_MAX,
                ),
            triggerDistanceDp =
                triggerDistanceDp.coerceIn(
                    SideVeilConfigBounds.EDGE_TRIGGER_DISTANCE_DP_MIN,
                    SideVeilConfigBounds.EDGE_TRIGGER_DISTANCE_DP_MAX,
                ),
            edgeOffsetDp =
                edgeOffsetDp.coerceIn(
                    SideVeilConfigBounds.EDGE_OFFSET_DP_MIN,
                    SideVeilConfigBounds.EDGE_OFFSET_DP_MAX,
                ),
            handleOpacity =
                if (handleOpacity.isFinite()) {
                    handleOpacity.coerceIn(
                        SideVeilConfigBounds.EDGE_HANDLE_OPACITY_MIN,
                        SideVeilConfigBounds.EDGE_HANDLE_OPACITY_MAX,
                    )
                } else {
                    SideVeilConfigDefaults.EDGE_HANDLE_OPACITY
                },
            idleAutoDimMs =
                idleAutoDimMs.coerceIn(
                    SideVeilConfigBounds.EDGE_IDLE_AUTO_DIM_MS_MIN,
                    SideVeilConfigBounds.EDGE_IDLE_AUTO_DIM_MS_MAX,
                ),
        )
}

@Serializable
data class PanelConfig(
    val widthDp: Int = SideVeilConfigDefaults.PANEL_WIDTH_DP,
    val heightPercent: Int = SideVeilConfigDefaults.PANEL_HEIGHT_PERCENT,
    val cornerRadiusDp: Int = SideVeilConfigDefaults.PANEL_CORNER_RADIUS_DP,
    val themeId: String = SideVeilThemes.COLOROS_GLASS,
    val colorMode: String = SideVeilConfigDefaults.PANEL_COLOR_MODE,
    val nativeBlurEnabled: Boolean = SideVeilConfigDefaults.PANEL_NATIVE_BLUR_ENABLED,
    val blurRadiusPx: Int = SideVeilConfigDefaults.PANEL_BLUR_RADIUS_PX,
    val scrimAlpha: Float = SideVeilConfigDefaults.PANEL_SCRIM_ALPHA,
) {
    fun sanitized(): PanelConfig {
        val validTheme =
            when (themeId) {
                SideVeilThemes.COLOROS_GLASS, SideVeilThemes.MODERN_SAAS_DARK -> themeId
                else -> SideVeilThemes.COLOROS_GLASS
            }
        val validColorMode =
            when (colorMode) {
                "light", "dark", "system" -> colorMode
                else -> SideVeilConfigDefaults.PANEL_COLOR_MODE
            }
        return copy(
            widthDp =
                widthDp.coerceIn(
                    SideVeilConfigBounds.PANEL_WIDTH_DP_MIN,
                    SideVeilConfigBounds.PANEL_WIDTH_DP_MAX,
                ),
            heightPercent =
                heightPercent.coerceIn(
                    SideVeilConfigBounds.PANEL_HEIGHT_PERCENT_MIN,
                    SideVeilConfigBounds.PANEL_HEIGHT_PERCENT_MAX,
                ),
            cornerRadiusDp =
                cornerRadiusDp.coerceIn(
                    SideVeilConfigBounds.PANEL_CORNER_RADIUS_DP_MIN,
                    SideVeilConfigBounds.PANEL_CORNER_RADIUS_DP_MAX,
                ),
            themeId = validTheme,
            colorMode = validColorMode,
            blurRadiusPx =
                blurRadiusPx.coerceIn(
                    SideVeilConfigBounds.PANEL_BLUR_RADIUS_PX_MIN,
                    SideVeilConfigBounds.PANEL_BLUR_RADIUS_PX_MAX,
                ),
            scrimAlpha =
                if (scrimAlpha.isFinite()) {
                    scrimAlpha.coerceIn(
                        SideVeilConfigBounds.PANEL_SCRIM_ALPHA_MIN,
                        SideVeilConfigBounds.PANEL_SCRIM_ALPHA_MAX,
                    )
                } else {
                    SideVeilConfigDefaults.PANEL_SCRIM_ALPHA
                },
        )
    }
}

@Serializable
data class ScopeConfig(
    val mode: ScopeMode = ScopeMode.ALLOWLIST,
    val allowlist: List<String> = listOf(SideVeilConfigDefaults.SCOPE_ALLOWLIST_DEFAULT_PACKAGE),
    val denylist: List<String> = listOf(SideVeilConfigDefaults.SCOPE_DENYLIST_DEFAULT_PACKAGE),
) {
    fun sanitized(): ScopeConfig =
        copy(
            allowlist = allowlist.map { it.trim() }.filter { it.isNotEmpty() }.distinct(),
            denylist = denylist.map { it.trim() }.filter { it.isNotEmpty() }.distinct(),
        )
}

@Serializable
data class DebugConfig(
    val webviewDebuggingEnabled: Boolean = SideVeilConfigDefaults.DEBUG_WEBVIEW_DEBUGGING_ENABLED,
)

@Serializable
data class SideVeilConfig(
    val version: Int = SideVeilConfigDefaults.VERSION,
    val enabled: Boolean = SideVeilConfigDefaults.ENABLED,
    val edge: EdgeConfig = EdgeConfig(),
    val panel: PanelConfig = PanelConfig(),
    val scope: ScopeConfig = ScopeConfig(),
    val debug: DebugConfig = DebugConfig(),
) {
    fun sanitized(): SideVeilConfig =
        copy(
            version = version.coerceAtLeast(SideVeilConfigBounds.MIN_VERSION),
            edge = edge.sanitized(),
            panel = panel.sanitized(),
            scope = scope.sanitized(),
            debug = debug,
        )

    fun matchesPackage(packageName: String): Boolean {
        val pkg = packageName.trim()
        if (!enabled || pkg.isEmpty()) {
            return false
        }
        val cleanScope = scope.sanitized()
        if (pkg in cleanScope.denylist) {
            return false
        }
        return when (cleanScope.mode) {
            ScopeMode.GLOBAL -> true
            ScopeMode.ALLOWLIST -> pkg in cleanScope.allowlist
        }
    }

    companion object {
        val DEFAULT = SideVeilConfig()
    }
}

val SideVeilPrettyJson: Json =
    Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

val SideVeilWireJson: Json =
    Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }
