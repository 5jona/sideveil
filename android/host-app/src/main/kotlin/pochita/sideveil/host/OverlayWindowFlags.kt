package pochita.sideveil.host

import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display
import android.view.View
import android.view.WindowManager

/**
 * Shared overlay window utilities: hidden `WindowManager.LayoutParams` private flags,
 * `HardwareRenderer.preload()`, uncapped adaptive display refresh-rate voting, and dp scaling.
 */
internal object OverlayWindowFlags {
    private const val PRIVATE_FLAG_NO_MOVE_ANIMATION = 0x00000040
    const val MAX_UNTOUCHABLE_OVERLAY_ALPHA = 0.80f

    private val privateFlagsField by lazy {
        runCatching {
            WindowManager.LayoutParams::class.java.getField("privateFlags")
        }.getOrNull()
    }

    /**
     * Android 12+ untrusted touch occlusion limit: any `TYPE_APPLICATION_OVERLAY` window with
     * `FLAG_NOT_TOUCHABLE` and `alpha > 0.80f` triggers continuous InputDispatcher warnings.
     * Cap `alpha` at `0.80f` when `FLAG_NOT_TOUCHABLE` is set, and restore [requestedAlpha]
     * (default `1.0f`) once interactive.
     */
    fun resolveWindowAlpha(
        flags: Int,
        requestedAlpha: Float = 1.0f,
    ): Float {
        val clamped = requestedAlpha.coerceIn(0f, 1f)
        val notTouchable = (flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) != 0
        return if (notTouchable) minOf(clamped, MAX_UNTOUCHABLE_OVERLAY_ALPHA) else clamped
    }

    fun applyTouchSafeAlpha(
        lp: WindowManager.LayoutParams,
        requestedAlpha: Float = 1.0f,
    ) {
        lp.alpha = resolveWindowAlpha(lp.flags, requestedAlpha)
    }

    fun disableMoveAnimation(lp: WindowManager.LayoutParams) {
        val field = privateFlagsField ?: return
        runCatching {
            field.setInt(lp, field.getInt(lp) or PRIVATE_FLAG_NO_MOVE_ANIMATION)
        }
    }

    fun preloadHardwareRenderer() {
        runCatching {
            Class.forName("android.graphics.HardwareRenderer").getMethod("preload").invoke(null)
        }
    }

    /**
     * Resolves the highest supported [Display.Mode] matching the active physical display
     * resolution without imposing any hardcoded refresh-rate ceiling.
     */
    fun resolveMaxRefreshMode(
        context: Context,
        preferredDisplay: Display? = null,
    ): Display.Mode? {
        val display =
            preferredDisplay
                ?: runCatching { context.display }.getOrNull()
                ?: context.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)
                ?: return null
        val active = display.mode
        return display.supportedModes
            .filter { mode ->
                mode.physicalWidth == active.physicalWidth && mode.physicalHeight == active.physicalHeight
            }.maxByOrNull { it.refreshRate } ?: active
    }

    /**
     * Requests the display's highest supported refresh rate on [lp] while an overlay is visible.
     */
    fun applyPreferredMaxRefreshRate(
        context: Context,
        lp: WindowManager.LayoutParams,
        preferredDisplay: Display? = null,
    ) {
        val bestMode = resolveMaxRefreshMode(context, preferredDisplay) ?: return
        lp.preferredDisplayModeId = bestMode.modeId
        lp.preferredRefreshRate = bestMode.refreshRate
    }

    /**
     * Clears explicit refresh-rate votes whenever an overlay hides or collapses to warm-invisible
     * so LTPO displays can drop back to power-saving rates.
     */
    fun clearPreferredRefreshRate(lp: WindowManager.LayoutParams) {
        lp.preferredDisplayModeId = 0
        lp.preferredRefreshRate = 0f
    }

    fun applyHighFrameRate(
        view: View,
        context: Context,
        preferredDisplay: Display? = view.display,
    ) {
        val maxHz = resolveMaxRefreshMode(context, preferredDisplay)?.refreshRate
        view.setRequestedFrameRate(
            maxHz?.takeIf { it > 0f } ?: View.REQUESTED_FRAME_RATE_CATEGORY_HIGH,
        )
    }

    fun clearHighFrameRate(view: View) {
        view.setRequestedFrameRate(View.REQUESTED_FRAME_RATE_CATEGORY_DEFAULT)
    }

    fun dp(
        density: Float,
        value: Float,
    ): Int = (value * density.coerceAtLeast(1f) + 0.5f).toInt().coerceAtLeast(1)

    fun dp(
        context: Context,
        value: Float,
    ): Int = dp(context.resources.displayMetrics.density, value)
}
