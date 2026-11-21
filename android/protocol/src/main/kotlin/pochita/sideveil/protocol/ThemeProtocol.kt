package pochita.sideveil.protocol

import kotlinx.serialization.Serializable

@Serializable
data class HostDynamicColorsPayload(
    val seedArgb: Long,
    val lumaHint: Float = 0.5f,
    val isDark: Boolean,
)

@Serializable
data class GlassTintOverride(
    val tintTopArgb: Long,
    val tintBottomArgb: Long,
    val rimArgb: Long,
    val accentArgb: Long,
    val fallbackFillArgb: Long,
    val fallbackStrokeArgb: Long,
)

@Serializable
data class DynamicThemePalette(
    val themeId: String,
    val isDark: Boolean,
    val seedArgb: Long,
    val lumaHint: Float,
    val glass: GlassTintOverride,
    val cssTokens: Map<String, String> = emptyMap(),
)
