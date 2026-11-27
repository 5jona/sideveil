package pochita.sideveil.host.overlay

import android.view.WindowManager
import kotlin.math.roundToInt

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
    fun matches(lp: WindowManager.LayoutParams): Boolean = matchesGeometry(lp) && matchesPresentation(lp)

    private fun matchesGeometry(lp: WindowManager.LayoutParams): Boolean {
        if (gravity != lp.gravity || width != lp.width || height != lp.height) return false
        return x == lp.x && y == lp.y
    }

    private fun matchesPresentation(lp: WindowManager.LayoutParams): Boolean {
        if (flags != lp.flags || alpha != lp.alpha || dimAmount != lp.dimAmount) return false
        return preferredDisplayModeId == lp.preferredDisplayModeId &&
            preferredRefreshRate == lp.preferredRefreshRate
    }

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

    /**
     * Zero-allocation fast path comparing [lp] directly against the last committed snapshot
     * before allocating a new [OverlayWindowLayoutSnapshot].
     */
    fun commitIfChanged(
        lp: WindowManager.LayoutParams,
        apply: () -> Unit,
    ): Boolean {
        if (lastApplied?.matches(lp) == true) {
            return false
        }
        lastApplied = OverlayWindowLayoutSnapshot.from(lp)
        apply()
        return true
    }

    fun reset() {
        lastApplied = null
    }
}

/**
 * Tracks the last committed integer cross-window blur radius (`>= 0`) and invokes `apply`
 * only when the rounded integer pixel value changes, preventing redundant WM Binder IPCs.
 */
internal class BlurRadiusCommitTracker {
    private var lastApplied: Int = -1

    val appliedRadiusPx: Int
        get() = lastApplied.coerceAtLeast(0)

    fun commitIfChanged(
        radiusPx: Int,
        apply: (Int) -> Unit,
    ): Boolean {
        val clamped = radiusPx.coerceAtLeast(0)
        if (lastApplied == clamped) return false
        lastApplied = clamped
        apply(clamped)
        return true
    }

    fun commitIfChanged(
        radiusPx: Float,
        apply: (Int) -> Unit,
    ): Boolean = commitIfChanged(radiusPx.roundToInt(), apply)

    fun reset() {
        lastApplied = -1
    }
}
