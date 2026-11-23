package pochita.sideveil.host.pinned

import android.content.Context
import android.view.View
import android.view.Window
import android.view.WindowManager
import pochita.sideveil.host.overlay.BlurMaskDrawable
import pochita.sideveil.host.overlay.GlassBackdropDrawable
import pochita.sideveil.host.overlay.GlassBackdropStyle
import pochita.sideveil.host.overlay.OverlayWindowFlags
import pochita.sideveil.protocol.DebugAgslSnapshot
import pochita.sideveil.protocol.GlassTintOverride
import pochita.sideveil.protocol.PanelConfig
import pochita.sideveil.protocol.PluginSurfaceBackdrop
import java.util.function.Consumer

/**
 * Coordinates two-layer hardware cross-window blur ([BlurMaskDrawable] on [Window]) and
 * optical frosted-glass rendering ([GlassBackdropDrawable] on the pinned root container),
 * honoring per-plugin [PluginSurfaceBackdrop] overrides for [PinnedContainerWindow].
 */
internal class PinnedBackdropController(
    private val context: Context,
    private val windowManager: WindowManager,
    private val onBlurCapabilityChanged: () -> Unit,
) {
    private val density: Float
        get() =
            context.resources.displayMetrics.density
                .coerceAtLeast(1f)

    val backdropDrawable = GlassBackdropDrawable(density)

    private var panelConfig = PanelConfig()
    private var pluginBackdrop = PluginSurfaceBackdrop.NATIVE_GLASS
    private var themeId = GlassBackdropStyle.THEME_COLOROS_GLASS
    private var isDark = true
    private var dynamicTint: GlassTintOverride? = null
    private var systemBlurSupported = runCatching { windowManager.isCrossWindowBlurEnabled }.getOrDefault(false)
    private var lastAppliedBlur = -1
    private var lastAppliedCornerPx = -1f

    private val blurEnabledListener =
        Consumer<Boolean> { enabled ->
            if (systemBlurSupported != enabled) {
                systemBlurSupported = enabled
                onBlurCapabilityChanged()
            }
        }

    fun initialCornerPx(): Float = cornerPx().also { lastAppliedCornerPx = it }

    fun cornerPx(): Float = OverlayWindowFlags.dp(density, panelConfig.cornerRadiusDp.coerceIn(12, 24).toFloat()).toFloat()

    fun registerBlurListener() {
        runCatching { windowManager.addCrossWindowBlurEnabledListener(context.mainExecutor, blurEnabledListener) }
    }

    fun unregisterBlurListener() {
        runCatching { windowManager.removeCrossWindowBlurEnabledListener(blurEnabledListener) }
    }

    fun setBackdrop(backdrop: PluginSurfaceBackdrop) {
        pluginBackdrop = backdrop
    }

    fun updateStyle(
        config: PanelConfig,
        nextThemeId: String,
        dark: Boolean,
        tint: GlassTintOverride?,
        chrome: PinnedChromeViews,
    ) {
        panelConfig = config
        themeId = nextThemeId
        isDark = dark
        dynamicTint = tint
        backdropDrawable.updateDensity(density)
        chrome.updateStyle(isDark, pluginBackdrop, dynamicTint)
    }

    fun syncChromeStyle(chrome: PinnedChromeViews) {
        chrome.updateStyle(isDark, pluginBackdrop, dynamicTint)
    }

    fun apply(
        window: Window,
        rootContainer: View,
        chrome: PinnedChromeViews,
    ) {
        chrome.updateStyle(isDark, pluginBackdrop, dynamicTint)
        if (pluginBackdrop == PluginSurfaceBackdrop.TRANSPARENT) {
            rootContainer.background = null
            rootContainer.clipToOutline = false
            applyBlur(window, 0)
            return
        }
        if (rootContainer.background !== backdropDrawable) {
            rootContainer.background = backdropDrawable
        }
        rootContainer.clipToOutline = true
        val corner = cornerPx()
        if (lastAppliedCornerPx != corner) {
            lastAppliedCornerPx = corner
            window.setBackgroundDrawable(BlurMaskDrawable(corner))
            rootContainer.invalidateOutline()
        }
        val blurAllowed =
            pluginBackdrop == PluginSurfaceBackdrop.NATIVE_GLASS &&
                systemBlurSupported &&
                panelConfig.nativeBlurEnabled
        val radius = if (blurAllowed) panelConfig.blurRadiusPx.coerceIn(0, 180) else 0
        backdropDrawable.updateStyle(
            GlassBackdropStyle(themeId, isDark, blurActive = radius > 0, cornerRadiusPx = corner, dynamicOverride = dynamicTint),
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
}
