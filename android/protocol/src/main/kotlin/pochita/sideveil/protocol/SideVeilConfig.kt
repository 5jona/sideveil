package pochita.sideveil.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

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
enum class PanelState {
    @SerialName("closed")
    CLOSED,

    @SerialName("dragging")
    DRAGGING,

    @SerialName("open")
    OPEN,
}

@Serializable
data class EdgeConfig(
    val side: EdgeSide = EdgeSide.RIGHT,
    val topPercent: Int = 32,
    val heightDp: Int = 168,
    val hotzoneWidthDp: Int = 24,
    val triggerDistanceDp: Int = 36,
    val edgeOffsetDp: Int = 1,
    val handleVisible: Boolean = true,
    val handleOpacity: Float = 0.62f,
    val dimInFullscreen: Boolean = true,
    val doubleSwipeInFullscreen: Boolean = false,
    val idleAutoDimMs: Int = 3500,
) {
    fun sanitized(): EdgeConfig =
        copy(
            topPercent = topPercent.coerceIn(5, 85),
            heightDp = heightDp.coerceIn(64, 360),
            hotzoneWidthDp = hotzoneWidthDp.coerceIn(12, 48),
            triggerDistanceDp = triggerDistanceDp.coerceIn(18, 96),
            edgeOffsetDp = edgeOffsetDp.coerceIn(0, 24),
            handleOpacity = handleOpacity.coerceIn(0.1f, 1.0f),
            idleAutoDimMs = idleAutoDimMs.coerceIn(0, 30_000),
        )
}

@Serializable
data class PanelConfig(
    val widthDp: Int = 340,
    val heightPercent: Int = 72,
    val cornerRadiusDp: Int = 22,
    val themeId: String = "coloros-glass",
    val colorMode: String = "system",
    val nativeBlurEnabled: Boolean = true,
    val blurRadiusPx: Int = 160,
    val scrimAlpha: Float = 0.0f,
) {
    fun sanitized(): PanelConfig {
        val validTheme =
            when (themeId) {
                "coloros-glass", "modern-saas-dark" -> themeId
                else -> "coloros-glass"
            }
        val validColorMode =
            when (colorMode) {
                "light", "dark", "system" -> colorMode
                else -> "system"
            }
        return copy(
            widthDp = widthDp.coerceIn(260, 480),
            heightPercent = heightPercent.coerceIn(40, 92),
            cornerRadiusDp = cornerRadiusDp.coerceIn(8, 36),
            themeId = validTheme,
            colorMode = validColorMode,
            blurRadiusPx = blurRadiusPx.coerceIn(0, 300),
            scrimAlpha = scrimAlpha.coerceIn(0.0f, 0.75f),
        )
    }
}

@Serializable
data class ScopeConfig(
    val mode: ScopeMode = ScopeMode.ALLOWLIST,
    val allowlist: List<String> = listOf("me.weishu.kernelsu"),
    val denylist: List<String> = listOf("com.android.systemui"),
) {
    fun sanitized(): ScopeConfig =
        copy(
            allowlist = allowlist.map { it.trim() }.filter { it.isNotEmpty() }.distinct(),
            denylist = denylist.map { it.trim() }.filter { it.isNotEmpty() }.distinct(),
        )
}

@Serializable
data class SideVeilConfig(
    val version: Int = 1,
    val enabled: Boolean = true,
    val edge: EdgeConfig = EdgeConfig(),
    val panel: PanelConfig = PanelConfig(),
    val scope: ScopeConfig = ScopeConfig(),
) {
    fun sanitized(): SideVeilConfig =
        copy(
            version = if (version < 1) 1 else version,
            edge = edge.sanitized(),
            panel = panel.sanitized(),
            scope = scope.sanitized(),
        )

    fun matchesPackage(packageName: String): Boolean {
        if (!enabled || packageName.isBlank()) {
            return false
        }
        val cleanScope = scope.sanitized()
        if (packageName in cleanScope.denylist) {
            return false
        }
        return when (cleanScope.mode) {
            ScopeMode.GLOBAL -> true
            ScopeMode.ALLOWLIST -> packageName in cleanScope.allowlist
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
