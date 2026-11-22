package pochita.sideveil.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ConfigUpdatedPayload(
    val config: SideVeilConfig,
    val scopeActive: Boolean,
    val foregroundPackage: String,
)

@Serializable
data class ScopeChangedPayload(
    val foregroundPackage: String,
    val foregroundActivity: String = "",
    val active: Boolean,
)

@Serializable
data class HostWebViewConsolePayload(
    val pluginId: String,
    val level: String,
    val message: String,
    val sourceId: String = "",
    val lineNumber: Int = 0,
)

@Serializable
data class HostWebViewErrorPayload(
    val pluginId: String,
    val kind: String,
    val code: Int,
    val description: String = "",
    val url: String = "",
    val isMainFrame: Boolean = false,
)

@Serializable
data class HostWebViewCrashPayload(
    val pluginId: String,
    val didCrash: Boolean,
    val rendererPriorityAtExit: Int,
    val crashCount: Int,
)

@Serializable
data class GestureDragStartPayload(
    val side: EdgeSide,
    val startYFraction: Float,
)

@Serializable
data class GestureDragMovePayload(
    val side: EdgeSide,
    val progress: Float,
    val rawX: Float,
    val rawY: Float,
    val deltaX: Float,
)

@Serializable
data class GestureDragEndPayload(
    val shouldOpen: Boolean,
    val velocityPxPerSec: Float,
    val canceled: Boolean = false,
)

@Serializable
data class GestureFullscreenHintPayload(
    val side: EdgeSide,
)

@Serializable
enum class EdgeRepositionPhase {
    @SerialName("start")
    START,

    @SerialName("move")
    MOVE,

    @SerialName("end")
    END,
}

@Serializable
data class EdgeRepositionPayload(
    val phase: EdgeRepositionPhase,
    val side: EdgeSide,
    val topPercent: Int,
    val committed: Boolean = false,
)

@Serializable
data class HostFullscreenChangedPayload(
    val fullscreen: Boolean,
)

@Serializable
data class PanelCommandPayload(
    val command: PanelCommand,
)

/**
 * Payload carried by `syncTheme` container actions.
 *
 * [wantsNativeBlur] and [cornerRadiusPx] are optional advisory fields sent by standalone or
 * external web clients, and are omitted (null) when the host or daemon is authoritative.
 */
@Serializable
data class ContainerThemePayload(
    val themeId: String = "coloros-glass",
    val colorScheme: String = "dark",
    val wantsNativeBlur: Boolean? = null,
    val cornerRadiusPx: Int? = null,
)

@Serializable
data class EdgeStatusPayload(
    val badgeText: String? = null,
    val accentColor: String? = null,
    val pulse: Boolean = false,
    val progress: Float? = null,
    val clear: Boolean = false,
)

@Serializable
data class ContainerActionPayload(
    val type: String,
    val focused: Boolean? = null,
    val widthDp: Int? = null,
    val heightDp: Int? = null,
    val heightPercent: Int? = null,
    val xPercent: Float? = null,
    val yPercent: Float? = null,
    val clickThrough: Boolean? = null,
    val enabled: Boolean? = null,
    val backdrop: String? = null,
    val mode: String? = null,
    val status: EdgeStatusPayload? = null,
    val payload: ContainerThemePayload? = null,
)

@Serializable
data class HostHelloPayload(
    val pid: Int,
    val version: String,
    val crossWindowBlurSupported: Boolean,
)

@Serializable
data class PanelStateChangedPayload(
    val state: PanelState,
)

@Serializable
data class PanelVisibilityPayload(
    val visible: Boolean,
    val state: PanelState,
)

@Serializable
data class PingResult(
    val pong: Boolean = true,
    val daemonUptimeMs: Long,
    val timestampMs: Long,
)

@Serializable
data class AppearancePatchParams(
    val themeId: String? = null,
    val colorMode: String? = null,
    val nativeBlurEnabled: Boolean? = null,
    val blurRadiusPx: Int? = null,
    val cornerRadiusDp: Int? = null,
)
