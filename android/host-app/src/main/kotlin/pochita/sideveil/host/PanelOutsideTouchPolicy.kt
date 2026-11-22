package pochita.sideveil.host

/**
 * Pure policy governing whether `MotionEvent.ACTION_OUTSIDE` should dismiss [SidebarPanelWindow].
 *
 * Ignores outside touches whenever the soft keyboard (IME) is visible (`imeBottomPx > 0`) or
 * within [postImeHideGraceMs] after IME dismissal so soft-keyboard taps (which often report
 * `ACTION_OUTSIDE` at `(0, 0)` on Android) never inadvertently close the sidebar panel.
 */
internal class PanelOutsideTouchPolicy(
    private val postImeHideGraceMs: Long = DEFAULT_POST_IME_HIDE_GRACE_MS,
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    private var imeBottomPx: Int = 0
    private var imeHiddenAtMs: Long? = null

    val isImeVisible: Boolean
        get() = imeBottomPx > 0

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
    }
}
