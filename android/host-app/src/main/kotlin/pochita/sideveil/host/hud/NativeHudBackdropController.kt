package pochita.sideveil.host.hud

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.WindowManager
import pochita.sideveil.host.overlay.BlurMaskDrawable
import pochita.sideveil.host.overlay.BlurRadiusCommitTracker
import pochita.sideveil.host.overlay.GlassBackdropDrawable
import pochita.sideveil.host.overlay.GlassBackdropStyle
import pochita.sideveil.host.overlay.OverlayWindowFlags
import pochita.sideveil.protocol.DebugAgslSnapshot
import pochita.sideveil.protocol.GlassTintOverride
import pochita.sideveil.protocol.HudBackdropKind
import pochita.sideveil.protocol.PanelConfig
import java.util.function.Consumer

/**
 * Coordinates hardware cross-window blur ([BlurMaskDrawable] on [Window]), live
 * [WindowManager.addCrossWindowBlurEnabledListener] lifecycle, and optical frosted-glass
 * rendering ([GlassBackdropDrawable] on the HUD container) for [NativeHudWindow].
 */
@SuppressLint("RtlHardcoded")
internal class NativeHudBackdropController(
    private val context: Context,
    private val windowManager: WindowManager,
    private val densityProvider: () -> Float,
    private val onBlurCapabilityChanged: () -> Unit = {},
) {
    val backdropDrawable = GlassBackdropDrawable(densityProvider())

    private var panelConfig = PanelConfig()
    private var themeId = GlassBackdropStyle.THEME_COLOROS_GLASS
    private var isDark = true
    private var systemBlurSupported = runCatching { windowManager.isCrossWindowBlurEnabled }.getOrDefault(false)
    private var dynamicTint: GlassTintOverride? = null
    private val blurCommitTracker = BlurRadiusCommitTracker()
    private var lastCornerPx = -1f

    private val blurEnabledListener =
        Consumer<Boolean> { enabled ->
            if (systemBlurSupported != enabled) {
                systemBlurSupported = enabled
                onBlurCapabilityChanged()
            }
        }

    fun registerBlurListener() {
        runCatching {
            windowManager.addCrossWindowBlurEnabledListener(context.mainExecutor, blurEnabledListener)
        }
    }

    fun unregisterBlurListener() {
        runCatching {
            windowManager.removeCrossWindowBlurEnabledListener(blurEnabledListener)
        }
    }

    fun configureInitialWindow(
        window: Window,
        cornerPx: Float,
    ) {
        window.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        window.setFormat(PixelFormat.TRANSLUCENT)
        window.decorView.setPadding(0, 0, 0, 0)
        lastCornerPx = cornerPx
        window.setBackgroundDrawable(BlurMaskDrawable(cornerPx))

        val lp = window.attributes
        lp.title = "SideVeilNativeHud"
        lp.gravity = Gravity.TOP or Gravity.LEFT
        lp.width = 1
        lp.height = 1
        lp.flags = INVISIBLE_FLAGS
        OverlayWindowFlags.applyTouchSafeAlpha(lp)
        OverlayWindowFlags.disableMoveAnimation(lp)
        window.attributes = lp
    }

    fun collapseWindow(
        window: Window,
        hudView: View,
    ) {
        clearBlur(window)
        OverlayWindowFlags.clearHighFrameRate(hudView)
        window.decorView.visibility = View.INVISIBLE
        val lp = window.attributes
        lp.gravity = Gravity.TOP or Gravity.LEFT
        lp.width = 1
        lp.height = 1
        lp.x = 0
        lp.y = 0
        lp.flags = INVISIBLE_FLAGS
        OverlayWindowFlags.applyTouchSafeAlpha(lp)
        OverlayWindowFlags.clearPreferredRefreshRate(lp)
        window.attributes = lp
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
            themeId = themeId,
            isDark = isDark,
            blurActive = radius > 0,
            cornerRadiusPx = cornerPx,
            dynamicOverride = dynamicTint,
        )
        applyBlur(window, radius)
    }

    fun clearBlur(window: Window) = applyBlur(window, 0)

    fun appliedBlurRadiusPx(): Int = blurCommitTracker.appliedRadiusPx

    fun agslDebugState(): DebugAgslSnapshot = backdropDrawable.debugState()

    private fun applyBlur(
        window: Window,
        radius: Int,
    ) {
        blurCommitTracker.commitIfChanged(radius) { committed ->
            window.setBackgroundBlurRadius(committed)
        }
    }

    companion object {
        private const val MAX_HUD_BLUR_PX = 180

        internal const val INVISIBLE_FLAGS =
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
    }
}
