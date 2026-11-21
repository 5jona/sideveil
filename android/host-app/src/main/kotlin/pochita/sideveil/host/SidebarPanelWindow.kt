package pochita.sideveil.host

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.Window
import android.view.WindowInsets
import android.view.WindowManager
import android.webkit.WebView
import pochita.sideveil.protocol.EdgeSide
import pochita.sideveil.protocol.GlassTintOverride
import pochita.sideveil.protocol.PanelConfig
import pochita.sideveil.protocol.PanelState
import pochita.sideveil.protocol.PluginPresentationMode
import pochita.sideveil.protocol.PluginSurfaceBackdrop

/**
 * Warm-resident `TYPE_APPLICATION_OVERLAY` Dialog container hosting the active plugin WebView
 * with zero-reflow origin-anchored spring motion, progressive cross-window blur, dynamic AGSL
 * specular flow, `WindowInsets` (IME/cutout) avoidance, and Android 16+ predictive back preview.
 */
@SuppressLint("RtlHardcoded")
class SidebarPanelWindow(
    private val context: Context,
    windowManager: WindowManager,
    private val onPanelStateChanged: (PanelState) -> Unit,
    private val onBackPressedIntercept: () -> Boolean = { false },
    private val isBackIntercepted: () -> Boolean = { false },
    private val onRailMotionProgress: (Float) -> Unit = {},
    private val shouldIgnoreOutsideTouch: (Float, Float) -> Boolean = { _, _ -> false },
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val themedContext = ContextThemeWrapper(context, R.style.Theme_SideVeil_OverlayDialog)
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
    private var presentationMode = PluginPresentationMode.DRAWER
    private var gestureStartYFraction = 0.5f
    private var lastLiveInsets: WindowInsets? = null
    private var pendingCollapse: Runnable? = null
    private var pendingOutsideClose: Runnable? = null

    private val contentContainer = windowLayout.createContentContainer(themedContext)

    private val dialog =
        windowLayout.createOverlayDialog(
            themedContext = themedContext,
            contentView = contentContainer,
            stateProvider = { currentState },
            onBackPressedIntercept = onBackPressedIntercept,
            onCloseRequested = ::close,
            onOutsideTouchRequested = ::handleOutsideTouch,
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

    fun setContentWebView(webView: WebView?) {
        if (webView == null) {
            activeWebView = null
            contentContainer.removeAllViews()
            return
        }
        windowLayout.recomputeDimensions(edgeSide, panelConfig, railInsetDp, pluginSizeOverride, lastLiveInsets, presentationMode)
        if (activeWebView === webView && webView.parent === contentContainer) return
        val initialAlpha = if (currentState == PanelState.OPEN) 1f else springController.currentProgress
        windowLayout.mountWebView(contentContainer, webView, initialAlpha)
        activeWebView = webView
        if (currentState == PanelState.OPEN && attached) webView.requestFocus()
    }

    fun setRailInsetDp(insetDp: Int) {
        val clamped = insetDp.coerceIn(0, 80)
        if (railInsetDp == clamped) return
        railInsetDp = clamped
        refreshOpenGeometry()
    }

    fun setPluginSizeOverride(
        override: PluginSizeOverride,
        mode: PluginPresentationMode = presentationMode,
    ) {
        if (pluginSizeOverride == override && presentationMode == mode) return
        pluginSizeOverride = override
        presentationMode = mode
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
        refreshOpenGeometry(reapplyBackdrop = false)
        backdropController.onSystemConfigurationChanged()
    }

    fun updateConfig(
        side: EdgeSide,
        config: PanelConfig,
    ) {
        edgeSide = side
        panelConfig = config
        refreshOpenGeometry(reapplyBackdrop = false)
        backdropController.updateConfig(config)
    }

    fun syncRuntimeAppearance(
        themeId: String,
        colorScheme: String,
        dynamicTint: GlassTintOverride? = null,
    ) {
        if (dynamicTint != null) {
            backdropController.setDynamicTint(dynamicTint)
        }
        backdropController.syncRuntimeAppearance(themeId, colorScheme)
    }

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
                dialog.window?.let { applyMotionVisuals(it, computeMotionFrame(progress, velocity), true, true) }
            },
            onSettled = { if (currentState == PanelState.OPEN && !closingInProgress) refreshOpenGeometry() },
        )
        activeWebView?.requestFocus()
    }

    fun close(initialVelocityPx: Float = 0f) {
        if (!attached || currentState == PanelState.CLOSED || closingInProgress) return
        cancelPendingTimers()
        springController.cancel()
        backAnimator.resetVisuals(animated = false)
        closingInProgress = true
        setImeFocused(false)
        windowLayout.recomputeDimensions(edgeSide, panelConfig, railInsetDp, pluginSizeOverride, lastLiveInsets, presentationMode)
        val outwardPx = if (initialVelocityPx <= 0f) initialVelocityPx else -initialVelocityPx
        val velocityNorm = (outwardPx / windowLayout.cachedWidthPx.coerceAtLeast(1).toFloat()).coerceIn(-12f, 0f)
        springController.animateTo(
            startProgress = springController.currentProgress,
            endProgress = 0f,
            startVelocityPerSec = velocityNorm,
            opening = false,
            onUpdate = { progress, velocity ->
                dialog.window?.let { applyMotionVisuals(it, computeMotionFrame(progress, velocity), false, true) }
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
        cancelPendingTimers()
        springController.cancel()
        backAnimator.resetVisuals(animated = false)
        closingInProgress = false
        if (resetOrigin) gestureStartYFraction = startYFraction.coerceIn(0f, 1f)
        windowLayout.recomputeDimensions(edgeSide, panelConfig, railInsetDp, pluginSizeOverride, lastLiveInsets, presentationMode)
        springController.snapTo(startProgress)
        val frame = computeMotionFrame(startProgress, 0f)
        dialog.window?.let { win ->
            windowLayout.showInteractive(win, edgeSide, panelConfig, frame, focusable, allowOffscreen = true)
            backdropController.apply()
            applyMotionVisuals(win, frame, focusable = focusable, allowOffscreen = true)
        }
    }

    fun toggle() = if (currentState == PanelState.OPEN && !closingInProgress) close() else open()

    fun setImeFocused(focused: Boolean) {
        if (!attached || currentState == PanelState.CLOSED) return
        activeWebView?.let { windowLayout.applyImeFocus(dialog.window, it, panelConfig, focused) }
    }

    fun cancelPendingTimers(includeCollapse: Boolean = true) {
        pendingOutsideClose?.let(mainHandler::removeCallbacks)
        pendingOutsideClose = null
        if (includeCollapse) {
            pendingCollapse?.let(mainHandler::removeCallbacks)
            pendingCollapse = null
        }
    }

    private fun handleOutsideTouch(
        rawX: Float,
        rawY: Float,
    ) {
        if (currentState != PanelState.OPEN || closingInProgress || shouldIgnoreOutsideTouch(rawX, rawY)) return
        cancelPendingTimers(includeCollapse = false)
        val task =
            Runnable {
                pendingOutsideClose = null
                if (currentState == PanelState.OPEN && !closingInProgress && !shouldIgnoreOutsideTouch(rawX, rawY)) close()
            }
        pendingOutsideClose = task
        mainHandler.postDelayed(task, OUTSIDE_CLOSE_DEBOUNCE_MS)
    }

    fun detach() {
        cancelPendingTimers()
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
            progress,
            velocityPerSec,
            gestureStartYFraction,
            windowLayout.buildMotionGeometry(edgeSide, railInsetDp, backdropController.targetBlurRadiusPx()),
        )

    private fun applyMotionVisuals(
        window: Window,
        frame: PanelMotionFrame,
        focusable: Boolean,
        allowOffscreen: Boolean,
    ) {
        windowLayout.updatePosition(window, panelConfig, frame, focusable, allowOffscreen)
        activeWebView?.alpha = frame.contentAlpha
        backdropController.applyMotionFrame(window, frame)
        onRailMotionProgress(frame.railProgress)
    }

    private fun refreshOpenGeometry(reapplyBackdrop: Boolean = true) {
        windowLayout.recomputeDimensions(edgeSide, panelConfig, railInsetDp, pluginSizeOverride, lastLiveInsets, presentationMode)
        if (currentState == PanelState.OPEN && attached) {
            val window = dialog.window ?: return
            applyMotionVisuals(window, computeMotionFrame(1f, 0f), focusable = true, allowOffscreen = false)
            if (reapplyBackdrop) backdropController.apply()
        }
    }

    private fun scheduleWarmCollapse() {
        cancelPendingTimers()
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

    private fun collapseToWarmInvisible() {
        closingInProgress = false
        val window = dialog.window ?: return
        backdropController.clearWindowBlur(window)
        windowLayout.collapseToWarmInvisible(window)
        activeWebView?.let {
            it.alpha = 0f
            OverlayWindowFlags.clearHighFrameRate(it)
        }
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
        private const val TAG = "SideVeilPanelWindow"
        private const val POST_DISMISS_WARM_RETAIN_MS = 150L
        private const val OUTSIDE_CLOSE_DEBOUNCE_MS = 72L
    }
}
