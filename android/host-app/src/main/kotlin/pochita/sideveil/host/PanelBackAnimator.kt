package pochita.sideveil.host

import android.view.View
import android.view.Window
import android.window.BackEvent
import android.window.OnBackAnimationCallback
import pochita.sideveil.protocol.EdgeSide
import pochita.sideveil.protocol.PanelState

/**
 * Android 16–17 predictive-back [OnBackAnimationCallback] controller for [SidebarPanelWindow].
 * Drives live shrink, edge-shift, and progressive blur preview during back gestures when the
 * active plugin has not opted into back interception.
 */
internal class PanelBackAnimator(
    private val contentContainer: View,
    private val windowProvider: () -> Window?,
    private val backdropController: PanelBackdropController,
    private val windowLayout: PanelWindowLayout,
    private val stateProvider: () -> PanelState,
    private val isClosing: () -> Boolean,
    private val edgeSideProvider: () -> EdgeSide,
    private val isBackIntercepted: () -> Boolean,
    private val onBackPressedIntercept: () -> Boolean,
    private val onCommitClose: (Float) -> Unit,
) : OnBackAnimationCallback {
    private var predictiveProgress: Float = 0f

    override fun onBackStarted(backEvent: BackEvent) {
        if (stateProvider() == PanelState.OPEN && !isBackIntercepted()) {
            predictiveProgress = 0f
        }
    }

    override fun onBackProgressed(backEvent: BackEvent) {
        if (stateProvider() != PanelState.OPEN || isClosing() || isBackIntercepted()) return
        val p = backEvent.progress.coerceIn(0f, 1f)
        predictiveProgress = p
        val scale = 1f - p * SCALE_DROP
        val shiftSign = if (edgeSideProvider() == EdgeSide.LEFT) -1f else 1f
        contentContainer.scaleX = scale
        contentContainer.scaleY = scale
        contentContainer.translationX = shiftSign * p * windowLayout.cachedWidthPx * SHIFT_FRACTION
        windowProvider()?.let { win ->
            val targetBlur = backdropController.targetBlurRadiusPx()
            val reducedBlur = PanelMotionChoreographer.computeProgressiveBlur(1f - p * 0.45f, targetBlur)
            backdropController.applyMotionFrame(win, reducedBlur, 1f - p * 0.12f, 1f - p * 0.2f, -p * 0.6f)
        }
    }

    override fun onBackCancelled() {
        predictiveProgress = 0f
        resetVisuals(animated = true)
    }

    override fun onBackInvoked() {
        val snapshot = predictiveProgress
        predictiveProgress = 0f
        resetVisuals(animated = false)
        if (stateProvider() == PanelState.OPEN && !onBackPressedIntercept()) {
            onCommitClose(snapshot)
        }
    }

    fun resetVisuals(animated: Boolean) {
        predictiveProgress = 0f
        if (animated) {
            contentContainer
                .animate()
                .scaleX(1f)
                .scaleY(1f)
                .translationX(0f)
                .setDuration(CANCEL_RESET_MS)
                .start()
            if (stateProvider() == PanelState.OPEN) {
                backdropController.apply()
            }
        } else {
            contentContainer.animate().cancel()
            contentContainer.scaleX = 1f
            contentContainer.scaleY = 1f
            contentContainer.translationX = 0f
        }
    }

    companion object {
        private const val SCALE_DROP = 0.07f
        private const val SHIFT_FRACTION = 0.08f
        private const val CANCEL_RESET_MS = 140L
    }
}
