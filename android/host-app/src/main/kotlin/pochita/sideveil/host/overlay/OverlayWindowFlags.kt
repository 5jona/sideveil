package pochita.sideveil.host.overlay

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Handler
import android.view.Display
import android.view.View
import android.view.WindowManager
import pochita.sideveil.host.panel.PanelInsetsCoordinator

/**
 * Shared overlay window utilities: hidden `WindowManager.LayoutParams` private flags,
 * `HardwareRenderer.preload()`, uncapped adaptive display refresh-rate voting, and dp scaling.
 */
object OverlayWindowFlags {
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

    /** Applies [resolveWindowAlpha] directly to [lp]. */
    fun applyTouchSafeAlpha(
        lp: WindowManager.LayoutParams,
        requestedAlpha: Float = 1.0f,
    ) {
        lp.alpha = resolveWindowAlpha(lp.flags, requestedAlpha)
    }

    /** Sets `PRIVATE_FLAG_NO_MOVE_ANIMATION` on [lp] to suppress stock WindowManager move animations. */
    fun disableMoveAnimation(lp: WindowManager.LayoutParams) {
        val field = privateFlagsField ?: return
        runCatching {
            field.setInt(lp, field.getInt(lp) or PRIVATE_FLAG_NO_MOVE_ANIMATION)
        }
    }

    /** Pre-warms `HardwareRenderer` to eliminate first-frame RenderThread initialization latency. */
    fun preloadHardwareRenderer() {
        runCatching {
            Class.forName("android.graphics.HardwareRenderer").getMethod("preload").invoke(null)
        }
    }

    @Volatile
    private var cachedDisplayId: Int = Display.INVALID_DISPLAY

    @Volatile
    private var cachedMaxMode: Display.Mode? = null

    private var registeredDisplayListener: DisplayManager.DisplayListener? = null

    /** Registers a DisplayListener that invalidates cached refresh modes and safe bounds on display changes. */
    fun registerDisplayCacheInvalidator(
        context: Context,
        handler: Handler,
    ) {
        if (registeredDisplayListener != null) return
        val dm = context.getSystemService(DisplayManager::class.java) ?: return
        val listener =
            object : DisplayManager.DisplayListener {
                override fun onDisplayAdded(displayId: Int) = invalidateDisplayCaches()

                override fun onDisplayRemoved(displayId: Int) = invalidateDisplayCaches()

                override fun onDisplayChanged(displayId: Int) = invalidateDisplayCaches()
            }
        runCatching {
            dm.registerDisplayListener(listener, handler)
            registeredDisplayListener = listener
        }
    }

    /** Unregisters the DisplayListener registered by [registerDisplayCacheInvalidator]. */
    fun unregisterDisplayCacheInvalidator(context: Context) {
        val listener = registeredDisplayListener ?: return
        registeredDisplayListener = null
        runCatching {
            context.getSystemService(DisplayManager::class.java)?.unregisterDisplayListener(listener)
        }
        invalidateDisplayCaches()
    }

    /** Clears cached max refresh mode and default window bounds (call on Display or Configuration changes). */
    fun invalidateDisplayCaches() {
        cachedDisplayId = Display.INVALID_DISPLAY
        cachedMaxMode = null
        PanelInsetsCoordinator.invalidateCachedBounds()
    }

    /**
     * Resolves the highest supported [Display.Mode] matching the active physical display
     * resolution without imposing any hardcoded refresh-rate ceiling. Cached until display
     * or configuration changes invalidate it.
     */
    fun resolveMaxRefreshMode(
        context: Context,
        preferredDisplay: Display? = null,
    ): Display.Mode? {
        val requestedId = preferredDisplay?.displayId ?: Display.DEFAULT_DISPLAY
        val cached = cachedMaxMode
        if (cached != null && cachedDisplayId == requestedId) {
            return cached
        }
        val display =
            preferredDisplay
                ?: runCatching { context.display }.getOrNull()
                ?: context.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)
                ?: return null
        val active = display.mode
        var best = active
        val modes = display.supportedModes
        for (i in modes.indices) {
            val mode = modes[i]
            if (mode.physicalWidth == active.physicalWidth &&
                mode.physicalHeight == active.physicalHeight &&
                mode.refreshRate > best.refreshRate
            ) {
                best = mode
            }
        }
        cachedDisplayId = display.displayId
        cachedMaxMode = best
        return best
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

    /** Votes for the display's highest supported frame rate on [view]. */
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

    /** Resets [view]'s frame rate vote back to default. */
    fun clearHighFrameRate(view: View) {
        view.setRequestedFrameRate(View.REQUESTED_FRAME_RATE_CATEGORY_DEFAULT)
    }

    /** Converts [value] in dp to pixels using [density] (`>= 1`). */
    fun dp(
        density: Float,
        value: Float,
    ): Int = (value * density.coerceAtLeast(1f) + 0.5f).toInt().coerceAtLeast(1)

    /** Converts [value] in dp to pixels using [context] display metrics. */
    fun dp(
        context: Context,
        value: Float,
    ): Int = dp(context.resources.displayMetrics.density, value)
}

/**
 * Comparable snapshot of mutable [WindowManager.LayoutParams] fields managed by SideVeil overlays,
 * used to skip redundant `window.attributes = lp` (`WindowManager.updateViewLayout`) calls when
 * geometry, flags, opacity, dim amount, and refresh-rate vote are unchanged.
 */
internal data class OverlayWindowLayoutSnapshot(
    val gravity: Int,
    val width: Int,
    val height: Int,
    val x: Int,
    val y: Int,
    val flags: Int,
    val alpha: Float,
    val dimAmount: Float = 0f,
    val preferredDisplayModeId: Int = 0,
    val preferredRefreshRate: Float = 0f,
) {
    companion object {
        fun from(lp: WindowManager.LayoutParams): OverlayWindowLayoutSnapshot =
            OverlayWindowLayoutSnapshot(
                gravity = lp.gravity,
                width = lp.width,
                height = lp.height,
                x = lp.x,
                y = lp.y,
                flags = lp.flags,
                alpha = lp.alpha,
                dimAmount = lp.dimAmount,
                preferredDisplayModeId = lp.preferredDisplayModeId,
                preferredRefreshRate = lp.preferredRefreshRate,
            )
    }
}

/**
 * Tracks the last committed [OverlayWindowLayoutSnapshot] and only invokes `apply` when a
 * [WindowManager.LayoutParams] field actually changed, avoiding redundant `relayoutWindow` IPCs.
 */
internal class WindowLayoutCommitTracker {
    private var lastApplied: OverlayWindowLayoutSnapshot? = null

    fun commitIfChanged(
        snapshot: OverlayWindowLayoutSnapshot,
        apply: () -> Unit,
    ): Boolean {
        if (lastApplied == snapshot) return false
        lastApplied = snapshot
        apply()
        return true
    }

    fun reset() {
        lastApplied = null
    }
}
