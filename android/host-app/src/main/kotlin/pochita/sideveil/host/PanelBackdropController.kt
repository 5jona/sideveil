package pochita.sideveil.host

import android.content.Context
import android.content.res.Configuration
import android.view.View
import android.view.Window
import android.view.WindowManager
import androidx.core.view.isVisible
import pochita.sideveil.protocol.GlassTintOverride
import pochita.sideveil.protocol.PanelConfig
import pochita.sideveil.protocol.PluginSurfaceBackdrop
import java.util.function.Consumer

/**
 * Coordinates two-layer hardware window blur ([BlurMaskDrawable] on [Window]) and
 * optical frosted-glass rendering ([GlassBackdropDrawable] on the content container),
 * honoring per-plugin [PluginSurfaceBackdrop] overrides (`native-glass`, `transparent`, `opaque`).
 */
internal class PanelBackdropController(
    private val context: Context,
    private val windowManager: WindowManager,
    private val contentContainer: View,
    private val cornerRadiusProvider: () -> Float,
    private val windowProvider: () -> Window?,
) {
    private val density: Float
        get() = context.resources.displayMetrics.density
    private val backdropDrawable = GlassBackdropDrawable(density)

    var themeId: String = "coloros-glass"
        private set
    var colorScheme: String = resolveSystemScheme()
        private set
    var systemBlurSupported: Boolean = queryCrossWindowBlurEnabled()
        private set

    private var panelConfig: PanelConfig = PanelConfig()
    private var pluginBackdrop: PluginSurfaceBackdrop = PluginSurfaceBackdrop.NATIVE_GLASS
    private var dynamicTint: GlassTintOverride? = null
    private var lastAppliedBlurRadius: Int = -1
    private var lastAppliedCornerPx: Float = -1f

    private val blurEnabledListener =
        Consumer<Boolean> { enabled ->
            if (systemBlurSupported != enabled) {
                systemBlurSupported = enabled
                apply()
            }
        }

    fun attachToWindow(window: Window) {
        contentContainer.background = backdropDrawable
        contentContainer.clipToOutline = true
        contentContainer.outlineProvider = GlassBackdropDrawable.createContentOutlineProvider(cornerRadiusProvider)

        val initialCornerPx = cornerRadiusProvider()
        lastAppliedCornerPx = initialCornerPx
        window.setBackgroundDrawable(BlurMaskDrawable(initialCornerPx))
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

    fun updateConfig(config: PanelConfig) {
        panelConfig = config
        themeId = config.themeId
        colorScheme =
            if (config.colorMode == "light" || config.colorMode == "dark") {
                config.colorMode
            } else {
                resolveSystemScheme()
            }
        backdropDrawable.updateDensity(density)
        contentContainer.invalidateOutline()
        apply()
    }

    fun onSystemConfigurationChanged() {
        backdropDrawable.updateDensity(density)
        if (panelConfig.colorMode != "light" && panelConfig.colorMode != "dark") {
            colorScheme = resolveSystemScheme()
        }
        contentContainer.invalidateOutline()
        apply()
    }

    fun syncRuntimeAppearance(
        nextThemeId: String,
        nextColorScheme: String,
    ) {
        themeId = nextThemeId
        colorScheme = nextColorScheme
        contentContainer.invalidateOutline()
        apply()
    }

    fun setPluginBackdrop(backdrop: PluginSurfaceBackdrop) {
        if (pluginBackdrop == backdrop) return
        pluginBackdrop = backdrop
        apply()
    }

    fun setDynamicTint(tint: GlassTintOverride?) {
        if (dynamicTint == tint) return
        dynamicTint = tint
        apply()
    }

    fun clearWindowBlur(window: Window) {
        backdropDrawable.alpha = 255
        backdropDrawable.updateMotion(1f, 0f)
        applyBlurRadius(window, 0)
    }

    fun targetBlurRadiusPx(): Int {
        if (pluginBackdrop != PluginSurfaceBackdrop.NATIVE_GLASS || !systemBlurSupported) {
            return 0
        }
        return if (panelConfig.nativeBlurEnabled) panelConfig.blurRadiusPx.coerceAtLeast(0) else 0
    }

    fun debugState(): PanelBackdropDebugState =
        PanelBackdropDebugState(
            themeId = themeId,
            colorScheme = colorScheme,
            pluginBackdrop = pluginBackdrop,
            systemBlurSupported = systemBlurSupported,
            appliedBlurRadiusPx = lastAppliedBlurRadius.coerceAtLeast(0),
            targetBlurRadiusPx = targetBlurRadiusPx(),
            agsl = backdropDrawable.debugState(),
        )

    /**
     * Applies per-frame progressive cross-window blur, optical glass alpha, and AGSL specular
     * motion shift without re-creating [BlurMaskDrawable].
     */
    fun applyMotionFrame(
        window: Window,
        frame: PanelMotionFrame,
    ) = applyMotionFrame(window, frame.blurRadiusPx, frame.backdropAlpha, frame.shaderMotionProgress, frame.shaderVelocityShift)

    fun applyMotionFrame(
        window: Window,
        blurRadiusPx: Int,
        backdropAlpha: Float,
        motionProgress: Float,
        velocityShift: Float,
    ) {
        if (pluginBackdrop == PluginSurfaceBackdrop.TRANSPARENT) {
            applyBlurRadius(window, 0)
            return
        }
        val clampedBlur = blurRadiusPx.coerceIn(0, targetBlurRadiusPx())
        applyBlurRadius(window, clampedBlur)
        backdropDrawable.alpha = (backdropAlpha.coerceIn(0f, 1f) * 255f).toInt().coerceIn(0, 255)
        backdropDrawable.updateMotion(motionProgress, velocityShift)
    }

    fun apply() {
        val window = windowProvider() ?: return
        val isVisible = window.decorView.isVisible
        if (pluginBackdrop == PluginSurfaceBackdrop.TRANSPARENT) {
            contentContainer.background = null
            contentContainer.clipToOutline = false
            applyBlurRadius(window, 0)
            return
        }

        val blurConfigured = panelConfig.nativeBlurEnabled && panelConfig.blurRadiusPx > 0
        val blurAllowed = pluginBackdrop == PluginSurfaceBackdrop.NATIVE_GLASS && systemBlurSupported
        val effectiveBlurRadius = if (isVisible && blurConfigured && blurAllowed) panelConfig.blurRadiusPx else 0

        if (contentContainer.background !== backdropDrawable) {
            contentContainer.background = backdropDrawable
        }
        contentContainer.clipToOutline = true

        val cornerRadiusPx = cornerRadiusProvider()
        backdropDrawable.updateStyle(
            GlassBackdropStyle(
                themeId = themeId,
                isDark = colorScheme != "light",
                blurActive = effectiveBlurRadius > 0,
                cornerRadiusPx = cornerRadiusPx,
                dynamicOverride = dynamicTint,
            ),
        )
        applyBlurRadius(window, effectiveBlurRadius)
        if (lastAppliedCornerPx != cornerRadiusPx) {
            lastAppliedCornerPx = cornerRadiusPx
            window.setBackgroundDrawable(BlurMaskDrawable(cornerRadiusPx))
            contentContainer.invalidateOutline()
        }
    }

    private fun applyBlurRadius(
        window: Window,
        radiusPx: Int,
    ) {
        if (lastAppliedBlurRadius != radiusPx) {
            lastAppliedBlurRadius = radiusPx
            window.setBackgroundBlurRadius(radiusPx)
        }
    }

    private fun queryCrossWindowBlurEnabled(): Boolean = runCatching { windowManager.isCrossWindowBlurEnabled }.getOrDefault(false)

    private fun resolveSystemScheme(): String {
        val nightMask = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return if (nightMask == Configuration.UI_MODE_NIGHT_YES) "dark" else "light"
    }
}
