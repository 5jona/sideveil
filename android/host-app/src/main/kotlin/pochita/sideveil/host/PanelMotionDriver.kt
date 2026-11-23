package pochita.sideveil.host

import android.app.Dialog
import android.view.View
import android.view.Window
import android.view.WindowInsets
import android.webkit.WebView
import pochita.sideveil.protocol.EdgeSide
import pochita.sideveil.protocol.PanelConfig
import pochita.sideveil.protocol.PanelState
import pochita.sideveil.protocol.PluginPresentationMode

/**
 * Mutable layout and inset inputs shared between [SidebarPanelWindow] and [PanelMotionDriver].
 */
internal class PanelSurfaceState {
    var edgeSide: EdgeSide = EdgeSide.RIGHT
    var railInsetDp: Int = 0
    var panelConfig: PanelConfig = PanelConfig()
    var pluginSizeOverride: PluginSizeOverride = PluginSizeOverride()
    var presentationMode: PluginPresentationMode = PluginPresentationMode.DRAWER
    var lastLiveInsets: WindowInsets? = null
    var effectiveImeBottomPx: Int = 0
}

/**
 * Coordinates spring physics, drag tracking, predictive-back preview ([PanelBackAnimator]),
 * and per-frame window/backdrop/rail motion visuals on behalf of [SidebarPanelWindow].
 */
internal class PanelMotionDriver(
    contentContainer: View,
    private val surfaceState: PanelSurfaceState,
    private val windowLayout: PanelWindowLayout,
    private val backdropController: PanelBackdropController,
    private val windowProvider: () -> Window?,
    private val activeWebViewProvider: () -> WebView?,
    private val stateProvider: () -> PanelState,
    private val isClosing: () -> Boolean,
    isBackIntercepted: () -> Boolean,
    onBackPressedIntercept: () -> Boolean,
    onCommitBackClose: () -> Unit,
    private val onRailMotionProgress: (Float) -> Unit,
    private val onContentAlphaChanged: (Float) -> Unit = {},
) {
    private val springController = PanelSpringController()
    private var gestureStartYFraction = 0.5f
    private var backCallbackRegistered = false

    private val backAnimator =
        PanelBackAnimator(
            contentContainer = contentContainer,
            windowProvider = windowProvider,
            backdropController = backdropController,
            windowLayout = windowLayout,
            stateProvider = stateProvider,
            isClosing = isClosing,
            edgeSideProvider = { surfaceState.edgeSide },
            isBackIntercepted = isBackIntercepted,
            onBackPressedIntercept = onBackPressedIntercept,
            onCommitClose = { backProgress ->
                if (backProgress > 0.05f) {
                    springController.snapTo((1f - backProgress * 0.35f).coerceIn(0.4f, 1f))
                }
                onCommitBackClose()
            },
        )

    val currentProgress: Float
        get() = springController.currentProgress

    fun registerBackCallback(dialog: Dialog) {
        if (!backCallbackRegistered) {
            backCallbackRegistered = windowLayout.registerBackCallback(dialog, backAnimator)
        }
    }

    fun unregisterBackCallback(dialog: Dialog) {
        if (backCallbackRegistered) {
            windowLayout.unregisterBackCallback(dialog, backAnimator)
            backCallbackRegistered = false
        }
    }

    fun cancelAndResetBackVisuals() {
        springController.cancel()
        backAnimator.resetVisuals(animated = false)
    }

    fun cancel() = springController.cancel()

    fun recomputeDimensions() =
        windowLayout.recomputeDimensions(
            surfaceState.edgeSide,
            surfaceState.panelConfig,
            surfaceState.railInsetDp,
            surfaceState.pluginSizeOverride,
            surfaceState.lastLiveInsets,
            surfaceState.presentationMode,
            surfaceState.effectiveImeBottomPx,
        )

    fun showPreparedFrame(
        resetOrigin: Boolean,
        startYFraction: Float,
        startProgress: Float,
        focusable: Boolean,
    ) {
        cancelAndResetBackVisuals()
        if (resetOrigin) gestureStartYFraction = startYFraction.coerceIn(0f, 1f)
        recomputeDimensions()
        springController.snapTo(startProgress)
        val frame = computeFrame(startProgress, 0f)
        windowProvider()?.let { win ->
            windowLayout.showInteractive(win, surfaceState.edgeSide, surfaceState.panelConfig, frame, focusable, allowOffscreen = true)
            backdropController.apply()
            applyMotionVisuals(win, frame, focusable = focusable, allowOffscreen = true)
        }
    }

    fun refreshOpenFrame(reapplyBackdrop: Boolean) {
        recomputeDimensions()
        val window = windowProvider() ?: return
        applyMotionVisuals(window, computeFrame(1f, 0f), focusable = true, allowOffscreen = false)
        if (reapplyBackdrop) backdropController.apply()
    }

    fun collapseToWarmInvisible(): Boolean {
        surfaceState.lastLiveInsets = null
        surfaceState.effectiveImeBottomPx = 0
        val window = windowProvider() ?: return false
        backdropController.clearWindowBlur(window)
        windowLayout.collapseToWarmInvisible(window)
        activeWebViewProvider()?.let {
            it.alpha = 0f
            OverlayWindowFlags.clearHighFrameRate(it)
        }
        onContentAlphaChanged(0f)
        onRailMotionProgress(0f)
        return true
    }

    fun updateDrag(progress: Float) {
        val prev = springController.currentProgress
        val next = springController.updateDrag(progress)
        val approxVel = ((next - prev) * 60f).coerceIn(-6f, 6f)
        val frame = computeFrame(progress = next, velocityPerSec = approxVel)
        windowProvider()?.let { win ->
            applyMotionVisuals(win, frame, focusable = false, allowOffscreen = true)
        }
    }

    fun animateOpen(
        startProgress: Float,
        initialVelocityPx: Float,
        onSettled: () -> Unit,
    ) {
        val width = windowLayout.cachedWidthPx.coerceAtLeast(1).toFloat()
        val velocityNorm = (initialVelocityPx / width).coerceIn(-12f, 12f)
        springController.animateTo(
            startProgress = startProgress,
            endProgress = 1f,
            startVelocityPerSec = velocityNorm,
            opening = true,
            onUpdate = { progress, velocity ->
                windowProvider()?.let { win ->
                    applyMotionVisuals(win, computeFrame(progress, velocity), focusable = true, allowOffscreen = true)
                }
            },
            onSettled = onSettled,
        )
    }

    fun animateClose(
        initialVelocityPx: Float,
        onSettled: () -> Unit,
    ) {
        val outwardPx = if (initialVelocityPx <= 0f) initialVelocityPx else -initialVelocityPx
        val width = windowLayout.cachedWidthPx.coerceAtLeast(1).toFloat()
        val velocityNorm = (outwardPx / width).coerceIn(-12f, 0f)
        springController.animateTo(
            startProgress = springController.currentProgress,
            endProgress = 0f,
            startVelocityPerSec = velocityNorm,
            opening = false,
            onUpdate = { progress, velocity ->
                windowProvider()?.let { win ->
                    applyMotionVisuals(win, computeFrame(progress, velocity), focusable = false, allowOffscreen = true)
                }
            },
            onSettled = onSettled,
        )
    }

    private fun computeFrame(
        progress: Float,
        velocityPerSec: Float,
    ): PanelMotionFrame =
        PanelMotionChoreographer.computeFrame(
            progress,
            velocityPerSec,
            gestureStartYFraction,
            windowLayout.buildMotionGeometry(
                surfaceState.edgeSide,
                surfaceState.railInsetDp,
                backdropController.targetBlurRadiusPx(),
            ),
        )

    private fun applyMotionVisuals(
        window: Window,
        frame: PanelMotionFrame,
        focusable: Boolean,
        allowOffscreen: Boolean,
    ) {
        windowLayout.updatePosition(window, surfaceState.panelConfig, frame, focusable, allowOffscreen)
        activeWebViewProvider()?.alpha = frame.contentAlpha
        onContentAlphaChanged(frame.contentAlpha)
        backdropController.applyMotionFrame(window, frame)
        onRailMotionProgress(frame.railProgress)
    }
}
