package pochita.sideveil.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

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
enum class HudTextAlign {
    @SerialName("start")
    START,

    @SerialName("center")
    CENTER,

    @SerialName("end")
    END,
}

@Serializable
data class HudTextOverlay(
    val x: Float,
    val y: Float,
    val text: String,
    val sizeSp: Float = 12f,
    val colorArgb: Long = 0xFFF1F5F9L,
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
    val widthDp: Float = 200f,
    val heightDp: Float = 88f,
    val cornerRadiusDp: Float = 18f,
    val backdrop: HudBackdropKind = HudBackdropKind.NATIVE_GLASS,
    val clickThrough: Boolean = false,
    val anchorXPercent: Float = 4f,
    val anchorYPercent: Float = 12f,
    val texts: List<HudTextOverlay> = emptyList(),
)

@Serializable
data class HudDismissPayload(
    val pluginId: String? = null,
)
