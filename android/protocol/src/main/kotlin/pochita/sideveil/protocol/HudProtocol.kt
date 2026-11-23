package pochita.sideveil.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

typealias HudTextAlign = pochita.sideveil.protocol.generated.HudTextAlign
typealias HudDismissPayload = pochita.sideveil.protocol.generated.HudDismissPayload

@Serializable
enum class HudBackdropKind {
    @SerialName("native-glass")
    NATIVE_GLASS,

    @SerialName("transparent")
    TRANSPARENT,

    @SerialName("opaque")
    OPAQUE,
}

@Serializable
data class HudTextOverlay(
    val x: Float,
    val y: Float,
    val text: String,
    val sizeSp: Float = SideVeilHudDefaults.DEFAULT_TEXT_SIZE_SP,
    val colorArgb: Long = SideVeilHudDefaults.DEFAULT_TEXT_COLOR_ARGB,
    val align: HudTextAlign = HudTextAlign.START,
    val bold: Boolean = false,
    val tabularNums: Boolean = true,
    val maxWidthDp: Float? = null,
)

/**
 * Lightweight host-side envelope for a declarative [NativeHudScene].
 * Vector primitives are passed straight as raw JSON to Rust `libsideveil_hud.so` (`vello_cpu` NEON)
 * so Kotlin never allocates intermediate primitive AST objects on high-frequency updates.
 */
@Serializable
data class NativeHudSceneHeader(
    val pluginId: String = "",
    val widthDp: Float = SideVeilHudDefaults.DEFAULT_WIDTH_DP,
    val heightDp: Float = SideVeilHudDefaults.DEFAULT_HEIGHT_DP,
    val cornerRadiusDp: Float = SideVeilHudDefaults.DEFAULT_CORNER_RADIUS_DP,
    val backdrop: HudBackdropKind = HudBackdropKind.NATIVE_GLASS,
    val clickThrough: Boolean = false,
    val anchorXPercent: Float = SideVeilHudDefaults.DEFAULT_ANCHOR_X_PERCENT,
    val anchorYPercent: Float = SideVeilHudDefaults.DEFAULT_ANCHOR_Y_PERCENT,
    val texts: List<HudTextOverlay> = emptyList(),
)
