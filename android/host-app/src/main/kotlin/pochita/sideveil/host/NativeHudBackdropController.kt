package pochita.sideveil.host

import android.view.View
import android.view.Window
import pochita.sideveil.protocol.DebugAgslSnapshot
import pochita.sideveil.protocol.GlassTintOverride
import pochita.sideveil.protocol.HudBackdropKind
import pochita.sideveil.protocol.PanelConfig

/**
 * Coordinates hardware cross-window blur ([BlurMaskDrawable] on [Window]) and
 * optical frosted-glass rendering ([GlassBackdropDrawable] on the HUD container)
 * for [NativeHudWindow].
 */
internal class NativeHudBackdropController(
    private val densityProvider: () -> Float,
    initialBlurSupported: Boolean,
) {
    val backdropDrawable = GlassBackdropDrawable(densityProvider())

    private var panelConfig = PanelConfig()
    private var themeId = "coloros-glass"
    private var isDark = true
    private var systemBlurSupported = initialBlurSupported
    private var dynamicTint: GlassTintOverride? = null
    private var lastAppliedBlur = -1
    private var lastCornerPx = -1f

    fun attachInitialMask(
        window: Window,
        cornerPx: Float,
    ) {
        lastCornerPx = cornerPx
        window.setBackgroundDrawable(BlurMaskDrawable(cornerPx))
    }

    fun updateStyle(
        config: PanelConfig,
        nextThemeId: String,
        dark: Boolean,
        blurSupported: Boolean,
        tint: GlassTintOverride?,
    ) {
        panelConfig = config
        themeId = nextThemeId
        isDark = dark
        systemBlurSupported = blurSupported
        dynamicTint = tint
        backdropDrawable.updateDensity(densityProvider())
    }

    fun apply(
        window: Window,
        container: View,
        backdrop: HudBackdropKind,
        cornerPx: Float,
    ) {
        if (backdrop == HudBackdropKind.TRANSPARENT) {
            container.background = null
            container.clipToOutline = false
            applyBlur(window, 0)
            return
        }

        if (container.background !== backdropDrawable) {
            container.background = backdropDrawable
        }
        container.clipToOutline = true
        if (lastCornerPx != cornerPx) {
            lastCornerPx = cornerPx
            window.setBackgroundDrawable(BlurMaskDrawable(cornerPx))
            container.invalidateOutline()
        }
        val blurAllowed =
            backdrop == HudBackdropKind.NATIVE_GLASS &&
                systemBlurSupported &&
                panelConfig.nativeBlurEnabled
        val radius = if (blurAllowed) panelConfig.blurRadiusPx.coerceIn(0, MAX_HUD_BLUR_PX) else 0
        backdropDrawable.updateStyle(
            GlassBackdropStyle(
                themeId = themeId,
                isDark = isDark,
                blurActive = radius > 0,
                cornerRadiusPx = cornerPx,
                dynamicOverride = dynamicTint,
            ),
        )
        applyBlur(window, radius)
    }

    fun clearBlur(window: Window) = applyBlur(window, 0)

    fun appliedBlurRadiusPx(): Int = lastAppliedBlur.coerceAtLeast(0)

    fun agslDebugState(): DebugAgslSnapshot = backdropDrawable.debugState()

    private fun applyBlur(
        window: Window,
        radius: Int,
    ) {
        if (lastAppliedBlur != radius) {
            lastAppliedBlur = radius
            window.setBackgroundBlurRadius(radius)
        }
    }

    companion object {
        private const val MAX_HUD_BLUR_PX = 180
    }
}
