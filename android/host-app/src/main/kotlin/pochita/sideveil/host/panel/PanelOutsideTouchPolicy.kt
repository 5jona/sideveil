package pochita.sideveil.host.panel

import pochita.sideveil.protocol.EdgeSide

/**
 * Pure policy governing whether `MotionEvent.ACTION_OUTSIDE` should dismiss [SidebarPanelWindow].
 *
 * Ignores outside touches whenever the soft keyboard (IME) is visible (`imeBottomPx > 0`) or
 * within [postImeHideGraceMs] after IME dismissal so soft-keyboard taps (which often report
 * `ACTION_OUTSIDE` at `(0, 0)` on Android) never inadvertently close the sidebar panel.
 */
class PanelOutsideTouchPolicy(
    private val postImeHideGraceMs: Long = DEFAULT_POST_IME_HIDE_GRACE_MS,
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    private var imeBottomPx: Int = 0
    private var imeHiddenAtMs: Long? = null

    /** Whether the soft keyboard is currently reported as visible (`imeBottomPx > 0`). */
    val isImeVisible: Boolean
        get() = imeBottomPx > 0

    /** Updates tracked IME height and records the hide timestamp when transitioning to `0`. */
    fun onImeInsetsChanged(nextImeBottomPx: Int) {
        val clamped = nextImeBottomPx.coerceAtLeast(0)
        val wasVisible = imeBottomPx > 0
        imeBottomPx = clamped
        when {
            clamped > 0 -> imeHiddenAtMs = null
            wasVisible -> imeHiddenAtMs = nowMs()
        }
    }

    /**
     * Clears tracked IME visibility and any pending post-hide grace timestamp when the panel
     * collapses to warm-invisible so stale insets never linger across reopen cycles.
     */
    fun reset() {
        imeBottomPx = 0
        imeHiddenAtMs = null
    }

    /** Returns `true` while IME is visible or within [postImeHideGraceMs] of hiding. */
    fun isImeProtected(): Boolean {
        if (imeBottomPx > 0) return true
        val hiddenAt = imeHiddenAtMs ?: return false
        val elapsed = nowMs() - hiddenAt
        if (elapsed in 0L until postImeHideGraceMs) {
            return true
        }
        imeHiddenAtMs = null
        return false
    }

    /** Returns `true` when an outside-touch event should be ignored instead of closing the panel. */
    fun shouldIgnoreOutsideTouch(
        panelOpen: Boolean,
        closingInProgress: Boolean,
        isRailTouch: Boolean,
    ): Boolean {
        if (!panelOpen || closingInProgress) return true
        if (isImeProtected()) return true
        return isRailTouch
    }

    companion object {
        const val DEFAULT_POST_IME_HIDE_GRACE_MS = 250L
        const val DEFAULT_INNER_EDGE_BUFFER_DP = 12f

        /** Returns `true` if `([rawX], [rawY])` lies within the [bufferDp] inner-edge tolerance strip. */
        fun shouldIgnoreInnerEdgeOutsideTouch(
            edge: EdgeSide,
            rawX: Float,
            rawY: Float,
            panelBoundsLeft: Int,
            panelBoundsTop: Int,
            panelBoundsRight: Int,
            panelBoundsBottom: Int,
            density: Float,
            bufferDp: Float = DEFAULT_INNER_EDGE_BUFFER_DP,
        ): Boolean {
            if (bufferDp <= 0f || density <= 0f) return false
            if (panelBoundsBottom < panelBoundsTop) return false
            if (rawY !in panelBoundsTop.toFloat()..panelBoundsBottom.toFloat()) return false
            val bufferPx = bufferDp * density
            return when (edge) {
                EdgeSide.RIGHT -> {
                    val innerEdge = panelBoundsLeft.toFloat()
                    rawX in (innerEdge - bufferPx)..innerEdge
                }

                EdgeSide.LEFT -> {
                    val innerEdge = panelBoundsRight.toFloat()
                    rawX in innerEdge..(innerEdge + bufferPx)
                }
            }
        }
    }
}
