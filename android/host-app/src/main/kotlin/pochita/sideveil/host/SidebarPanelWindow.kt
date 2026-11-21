package pochita.sideveil.host

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.ViewGroup
import android.view.Window
import android.view.WindowInsets
import android.view.WindowManager
import android.webkit.WebView
import android.widget.FrameLayout
import pochita.sideveil.protocol.EdgeSide
import pochita.sideveil.protocol.PanelConfig
import pochita.sideveil.protocol.PanelState
import pochita.sideveil.protocol.PluginSurfaceBackdrop

/**
 * Warm-resident `TYPE_APPLICATION_OVERLAY` Dialog container hosting the active plugin WebView
 * with zero-reflow origin-anchored spring motion, progressive cross-window blur, dynamic AGSL
 * specular flow, `WindowInsets` (IME/cutout) avoidance, and Android 16–17 predictive back preview.
 */
@SuppressLint("RtlHardcoded")
class SidebarPanelWindow(
    private val context: Context,
    windowManager: WindowManager,
    private val onPanelStateChanged: (PanelState) -> Unit,
    private val onBackPressedIntercept: () -> Boolean = { false },
    private val isBackIntercepted: () -> Boolean = { false },
    private val onRailMotionProgress: (Float) -> Unit = {},
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val themedContext = ContextThemeWrapper(context, R.style.Theme_Sideveil_OverlayDialog)
    private val springController = PanelSpringController()
    private val windowLayout = PanelWindowLayout(context, windowManager)

    private var activeWebView: WebView? = null
    var currentState: PanelState = PanelState.CLOSED
        private set
    private var closingInProgress = false
    private var attached = false
    private var backCallbackRegistered = false
    private var edgeSide = EdgeSide.RIGHT
    private var railInsetDp = 0
    private var panelConfig = PanelConfig()
    private var pluginSizeOverride = PluginSizeOverride()
    private var gestureStartYFraction = 0.5f
    private var lastLiveInsets: WindowInsets? = null
    private var pendingCollapse: Runnable? = null

    private val contentContainer = FrameLayout(themedContext)

    private val dialog =
        windowLayout.createOverlayDialog(
            themedContext = themedContext,
            contentView = contentContainer,
            stateProvider = { currentState },
            onBackPressedIntercept = onBackPressedIntercept,
            onCloseRequested = ::close,
        )

    private val backdropController =
        PanelBackdropController(
            context = context,
            windowManager = windowManager,
            contentContainer = contentContainer,
            cornerRadiusProvider = { windowLayout.cornerPx(panelConfig) },
            windowProvider = { dialog.window },
        )

    private val backAnimator =
        PanelBackAnimator(
            contentContainer = contentContainer,
            windowProvider = { dialog.window },
            backdropController = backdropController,
            windowLayout = windowLayout,
            stateProvider = { currentState },
            isClosing = { closingInProgress },
            edgeSideProvider = { edgeSide },
            isBackIntercepted = isBackIntercepted,
            onBackPressedIntercept = onBackPressedIntercept,
            onCommitClose = { backProgress ->
                if (backProgress > 0.05f) {
                    springController.snapTo((1f - backProgress * 0.35f).coerceIn(0.4f, 1f))
                }
                close()
            },
        )

    init {
        OverlayWindowFlags.preloadHardwareRenderer()
        dialog.window?.let { backdropController.attachToWindow(it) }
        contentContainer.setOnApplyWindowInsetsListener { _, insets ->
            val prevIme = lastLiveInsets?.getInsets(WindowInsets.Type.ime())?.bottom ?: 0
            val nextIme = insets.getInsets(WindowInsets.Type.ime()).bottom
            lastLiveInsets = insets
            if (prevIme != nextIme && currentState == PanelState.OPEN && !closingInProgress) {
                refreshOpenGeometry()
            }
            insets
        }
    }

    val isSystemBlurSupported: Boolean
        get() = backdropController.systemBlurSupported

    fun setContentWebView(webView: WebView) {
        if (activeWebView === webView && webView.parent === contentContainer) return
        (webView.parent as? ViewGroup)?.removeView(webView)
        contentContainer.removeAllViews()
        contentContainer.addView(
            webView,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT),
        )
        webView.alpha = if (currentState == PanelState.OPEN) 1f else springController.currentProgress
        activeWebView = webView
        if (currentState == PanelState.OPEN && attached) {
            webView.requestFocus()
        }
    }

    fun setRailInsetDp(insetDp: Int) {
        val clamped = insetDp.coerceIn(0, 80)
        if (railInsetDp == clamped) return
        railInsetDp = clamped
        refreshOpenGeometry()
    }

    fun setPluginSizeOverride(override: PluginSizeOverride) {
        if (pluginSizeOverride == override) return
        pluginSizeOverride = override
        refreshOpenGeometry()
    }

    fun setPluginBackdrop(backdrop: PluginSurfaceBackdrop) = backdropController.setPluginBackdrop(backdrop)

    fun attachWarm() {
        if (attached) return
        runCatching {
            dialog.show()
            attached = true
            if (!backCallbackRegistered) {
                backCallbackRegistered = windowLayout.registerBackCallback(dialog, backAnimator)
            }
            collapseToWarmInvisible()
            backdropController.registerBlurListener()
            backdropController.apply()
        }.onFailure { err ->
            Log.e(TAG, "Failed to attach warm panel dialog overlay", err)
        }
    }

    fun onSystemConfigurationChanged() {
        windowLayout.recomputeDimensions(edgeSide, panelConfig, railInsetDp, pluginSizeOverride, lastLiveInsets)
        backdropController.onSystemConfigurationChanged()
        if (currentState == PanelState.OPEN && attached) {
            applyOpenLayout(settled = true)
        }
    }

    fun updateConfig(
        side: EdgeSide,
        config: PanelConfig,
    ) {
        edgeSide = side
        panelConfig = config
        windowLayout.recomputeDimensions(edgeSide, panelConfig, railInsetDp, pluginSizeOverride, lastLiveInsets)
        backdropController.updateConfig(config)
        if (currentState == PanelState.OPEN && attached) {
            applyOpenLayout(settled = true)
        }
    }

    fun syncRuntimeAppearance(
        themeId: String,
        colorScheme: String,
    ) = backdropController.syncRuntimeAppearance(themeId, colorScheme)

    fun onGestureStart(startYFraction: Float = 0.5f) {
        prepareAndShow(resetOrigin = true, startYFraction = startYFraction, startProgress = 0f, focusable = false)
        setState(PanelState.DRAGGING)
    }

    fun onGestureMove(progress: Float) {
        if (currentState != PanelState.DRAGGING) {
            onGestureStart()
        }
        val prev = springController.currentProgress
        val next = springController.updateDrag(progress)
        val approxVel = ((next - prev) * 60f).coerceIn(-6f, 6f)
        val frame = computeMotionFrame(progress = next, velocityPerSec = approxVel)
        dialog.window?.let { win -> applyMotionVisuals(win, frame, focusable = false, allowOffscreen = true) }
    }

    fun onGestureEnd(
        shouldOpen: Boolean,
        velocityPxPerSec: Float,
    ) {
        if (shouldOpen) open(initialVelocityPx = velocityPxPerSec) else close(initialVelocityPx = velocityPxPerSec)
    }

    fun open(initialVelocityPx: Float = 0f) {
        val fromClosed = currentState == PanelState.CLOSED
        val startProgress = if (fromClosed) 0f else springController.currentProgress
        prepareAndShow(resetOrigin = fromClosed, startYFraction = 0.5f, startProgress = startProgress, focusable = true)
        setState(PanelState.OPEN)

        val velocityNorm = (initialVelocityPx / windowLayout.cachedWidthPx.coerceAtLeast(1).toFloat()).coerceIn(-12f, 12f)
        springController.animateTo(
            startProgress = startProgress,
            endProgress = 1f,
            startVelocityPerSec = velocityNorm,
            opening = true,
            onUpdate = { progress, velocity ->
                val frame = computeMotionFrame(progress, velocity)
                dialog.window?.let { win -> applyMotionVisuals(win, frame, focusable = true, allowOffscreen = true) }
            },
            onSettled = {
                if (currentState == PanelState.OPEN && !closingInProgress) applyOpenLayout(settled = true)
            },
        )
        activeWebView?.requestFocus()
    }

    fun close(initialVelocityPx: Float = 0f) {
        if (!attached || currentState == PanelState.CLOSED || closingInProgress) return
        cancelPendingCollapse()
        springController.cancel()
        backAnimator.resetVisuals(animated = false)
        closingInProgress = true
        setImeFocused(false)
        windowLayout.recomputeDimensions(edgeSide, panelConfig, railInsetDp, pluginSizeOverride, lastLiveInsets)

        val outwardPx = if (initialVelocityPx <= 0f) initialVelocityPx else -initialVelocityPx
        val velocityNorm = (outwardPx / windowLayout.cachedWidthPx.coerceAtLeast(1).toFloat()).coerceIn(-12f, 0f)
        springController.animateTo(
            startProgress = springController.currentProgress,
            endProgress = 0f,
            startVelocityPerSec = velocityNorm,
            opening = false,
            onUpdate = { progress, velocity ->
                val frame = computeMotionFrame(progress, velocity)
                dialog.window?.let { win -> applyMotionVisuals(win, frame, focusable = false, allowOffscreen = true) }
            },
            onSettled = { scheduleWarmCollapse() },
        )
    }

    private fun prepareAndShow(
        resetOrigin: Boolean,
        startYFraction: Float,
        startProgress: Float,
        focusable: Boolean,
    ) {
        if (!attached) attachWarm()
        cancelPendingCollapse()
        springController.cancel()
        backAnimator.resetVisuals(animated = false)
        closingInProgress = false
        if (resetOrigin) gestureStartYFraction = startYFraction.coerceIn(0f, 1f)
        windowLayout.recomputeDimensions(edgeSide, panelConfig, railInsetDp, pluginSizeOverride, lastLiveInsets)
        springController.snapTo(startProgress)
        val frame = computeMotionFrame(startProgress, 0f)
        dialog.window?.let { win ->
            windowLayout.showInteractive(
                window = win,
                edgeSide = edgeSide,
                panelConfig = panelConfig,
                xPx = frame.windowX,
                yPx = frame.windowY + windowLayout.safeCenterOffsetY,
                focusable = focusable,
                allowOffscreen = true,
            )
            backdropController.apply()
            applyMotionVisuals(win, frame, focusable = focusable, allowOffscreen = true)
        }
    }

    fun toggle() {
        if (currentState == PanelState.OPEN && !closingInProgress) close() else open()
    }

    fun setImeFocused(focused: Boolean) {
        if (!attached || currentState == PanelState.CLOSED) return
        val webView = activeWebView ?: return
        windowLayout.applyImeFocus(dialog.window, webView, panelConfig, focused)
    }

    fun detach() {
        cancelPendingCollapse()
        springController.cancel()
        backdropController.unregisterBlurListener()
        if (backCallbackRegistered) {
            windowLayout.unregisterBackCallback(dialog, backAnimator)
            backCallbackRegistered = false
        }
        if (attached) {
            runCatching { dialog.dismiss() }
            attached = false
        }
    }

    private fun computeMotionFrame(
        progress: Float,
        velocityPerSec: Float,
    ): PanelMotionFrame =
        PanelMotionChoreographer.computeFrame(
            rawProgress = progress,
            velocityPerSec = velocityPerSec,
            startYFraction = gestureStartYFraction,
            geometry =
                windowLayout.buildMotionGeometry(
                    edgeSide = edgeSide,
                    railInsetDp = railInsetDp,
                    targetBlurRadiusPx = backdropController.targetBlurRadiusPx(),
                ),
        )

    private fun applyMotionVisuals(
        window: Window,
        frame: PanelMotionFrame,
        focusable: Boolean,
        allowOffscreen: Boolean,
    ) {
        windowLayout.updatePosition(
            window = window,
            panelConfig = panelConfig,
            xPx = frame.windowX,
            yPx = frame.windowY + windowLayout.safeCenterOffsetY,
            focusable = focusable,
            allowOffscreen = allowOffscreen,
        )
        activeWebView?.alpha = frame.contentAlpha
        backdropController.applyMotionFrame(
            window = window,
            blurRadiusPx = frame.blurRadiusPx,
            backdropAlpha = frame.backdropAlpha,
            motionProgress = frame.shaderMotionProgress,
            velocityShift = frame.shaderVelocityShift,
        )
        onRailMotionProgress(frame.railProgress)
    }

    private fun refreshOpenGeometry() {
        windowLayout.recomputeDimensions(edgeSide, panelConfig, railInsetDp, pluginSizeOverride, lastLiveInsets)
        if (currentState == PanelState.OPEN && attached) applyOpenLayout(settled = true)
    }

    private fun applyOpenLayout(settled: Boolean) {
        val window = dialog.window ?: return
        applyMotionVisuals(window, computeMotionFrame(1f, 0f), focusable = true, allowOffscreen = !settled)
        backdropController.apply()
    }

    private fun scheduleWarmCollapse() {
        cancelPendingCollapse()
        closingInProgress = false
        onRailMotionProgress(0f)
        setState(PanelState.CLOSED)
        val task =
            Runnable {
                pendingCollapse = null
                if (currentState == PanelState.CLOSED) collapseToWarmInvisible()
            }
        pendingCollapse = task
        mainHandler.postDelayed(task, POST_DISMISS_WARM_RETAIN_MS)
    }

    private fun cancelPendingCollapse() {
        pendingCollapse?.let(mainHandler::removeCallbacks)
        pendingCollapse = null
    }

    private fun collapseToWarmInvisible() {
        closingInProgress = false
        val window = dialog.window ?: return
        backdropController.clearWindowBlur(window)
        windowLayout.collapseToWarmInvisible(window)
        activeWebView?.alpha = 0f
        onRailMotionProgress(0f)
        setState(PanelState.CLOSED)
    }

    private fun setState(next: PanelState) {
        if (currentState != next) {
            currentState = next
            onPanelStateChanged(next)
        }
    }

    companion object {
        private const val TAG = "SideveilPanelWindow"
        private const val POST_DISMISS_WARM_RETAIN_MS = 150L
    }
}
