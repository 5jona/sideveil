package pochita.sideveil.host.panel

import android.app.Dialog
import android.graphics.Rect
import android.view.View
import android.view.Window
import android.view.WindowInsets
import android.webkit.WebView
import pochita.sideveil.host.overlay.OverlayWindowFlags
import pochita.sideveil.host.plugin.PluginSizeOverride
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
    private val contentContainer: View,
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
    private val animationGuard = PanelAnimationGuard()
    private var gestureStartYFraction = 0.5f

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
                    animationGuard.invalidate()
                    springController.snapTo((1f - backProgress * 0.35f).coerceIn(0.4f, 1f))
                }
                onCommitBackClose()
            },
        )

    val currentProgress: Float
        get() = springController.currentProgress

    fun registerBackCallback(dialog: Dialog) = backAnimator.register(dialog)

    fun unregisterBackCallback(dialog: Dialog) = backAnimator.unregister(dialog)

    /**
     * Cancels all active open/close spring animations and predictive-back reset animations while
     * invalidating the motion generation token so stale callbacks cannot mutate panel state.
     */
    fun cancelActiveAnimations() {
        animationGuard.invalidate()
        springController.cancel()
        backAnimator.resetVisuals(animated = false)
    }

    fun resetProgressToClosed() {
        cancelActiveAnimations()
        springController.snapTo(0f)
        onRailMotionProgress(0f)
    }

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
        cancelActiveAnimations()
        if (resetOrigin) gestureStartYFraction = startYFraction.coerceIn(0f, 1f)
        recomputeDimensions()
        springController.snapTo(startProgress)
        val frame = computeFrame(startProgress, 0f)
        windowProvider()?.let { win ->
            windowLayout.showInteractive(
                win,
                surfaceState.edgeSide,
                surfaceState.panelConfig,
                frame,
                focusable,
                allowOffscreen = true,
            )
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
        cancelActiveAnimations()
        springController.snapTo(0f)
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
        animationGuard.invalidate()
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
        val token = animationGuard.nextToken()
        val width = windowLayout.cachedWidthPx.coerceAtLeast(1).toFloat()
        val velocityNorm = (initialVelocityPx / width).coerceIn(-12f, 12f)
        springController.animateTo(
            startProgress = startProgress,
            endProgress = 1f,
            startVelocityPerSec = velocityNorm,
            opening = true,
            onUpdate = { progress, velocity ->
                animationGuard.runIfActive(token) {
                    windowProvider()?.let { win ->
                        applyMotionVisuals(win, computeFrame(progress, velocity), focusable = true, allowOffscreen = true)
                    }
                }
            },
            onSettled = { animationGuard.runIfActive(token, onSettled) },
        )
    }

    fun animateClose(
        initialVelocityPx: Float,
        onSettled: () -> Unit,
    ) {
        val token = animationGuard.nextToken()
        val outwardPx = if (initialVelocityPx <= 0f) initialVelocityPx else -initialVelocityPx
        val width = windowLayout.cachedWidthPx.coerceAtLeast(1).toFloat()
        val velocityNorm = (outwardPx / width).coerceIn(-12f, 0f)
        springController.animateTo(
            startProgress = springController.currentProgress,
            endProgress = 0f,
            startVelocityPerSec = velocityNorm,
            opening = false,
            onUpdate = { progress, velocity ->
                animationGuard.runIfActive(token) {
                    windowProvider()?.let { win ->
                        applyMotionVisuals(win, computeFrame(progress, velocity), focusable = false, allowOffscreen = true)
                    }
                }
            },
            onSettled = { animationGuard.runIfActive(token, onSettled) },
        )
    }

    fun isInnerEdgeBufferTouch(
        rawX: Float,
        rawY: Float,
        decorLocation: IntArray,
    ): Boolean {
        val decor = windowProvider()?.decorView ?: return false
        decor.getLocationOnScreen(decorLocation)
        val left = decorLocation[0]
        val top = decorLocation[1]
        val density =
            contentContainer.resources.displayMetrics.density
                .coerceAtLeast(1f)
        return PanelOutsideTouchPolicy.shouldIgnoreInnerEdgeOutsideTouch(
            edge = surfaceState.edgeSide,
            rawX = rawX,
            rawY = rawY,
            panelBoundsLeft = left,
            panelBoundsTop = top,
            panelBoundsRight = left + windowLayout.cachedWidthPx,
            panelBoundsBottom = top + windowLayout.cachedHeightPx,
            density = density,
        )
    }

    fun updateLeftEdgeGestureExclusion(hasEmbedToolbar: Boolean) {
        val shouldExclude = surfaceState.edgeSide == EdgeSide.LEFT && hasEmbedToolbar
        if (!shouldExclude || windowLayout.cachedHeightPx <= 2) {
            contentContainer.systemGestureExclusionRects = emptyList()
            return
        }
        val density =
            contentContainer.resources.displayMetrics.density
                .coerceAtLeast(1f)
        val edgeWidthPx = OverlayWindowFlags.dp(density, 28f)
        val maxBandPx = OverlayWindowFlags.dp(density, 180f)
        val height = windowLayout.cachedHeightPx
        val bandHeight = minOf(maxBandPx, (height * 0.45f).toInt().coerceAtLeast(1))
        val top = ((height - bandHeight) / 2).coerceAtLeast(0)
        contentContainer.systemGestureExclusionRects = listOf(Rect(0, top, edgeWidthPx, top + bandHeight))
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
