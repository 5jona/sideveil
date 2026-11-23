package pochita.sideveil.host

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.Window
import android.view.WindowInsets
import android.view.WindowManager
import android.webkit.WebView
import pochita.sideveil.protocol.DebugPanelWindowSnapshot
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
    context: Context,
    windowManager: WindowManager,
    private val onPanelStateChanged: (PanelState) -> Unit,
    onBackPressedIntercept: () -> Boolean = { false },
    isBackIntercepted: () -> Boolean = { false },
    private val onRailMotionProgress: (Float) -> Unit = {},
    shouldIgnoreOutsideTouch: (Float, Float) -> Boolean = { _, _ -> false },
) {
    private val themedContext = ContextThemeWrapper(context, R.style.Theme_SideVeil_OverlayDialog)
    private val windowLayout = PanelWindowLayout(context, windowManager)
    private val surfaceState = PanelSurfaceState()

    private var activeWebView: WebView? = null
    private var activeEmbedToolbar: EmbedToolbarBinding? = null
    private val embedQuickBar: EmbedQuickBarView by lazy { EmbedQuickBarView(themedContext) }
    private val decorLocation = IntArray(2)
    var currentState: PanelState = PanelState.CLOSED
        private set
    private var closingInProgress = false
    private var attached = false

    private val contentContainer = windowLayout.createContentContainer(themedContext)

    private val scheduler =
        PanelOverlayScheduler(
            stateProvider = { currentState },
            isClosing = { closingInProgress },
            isRailTouch = shouldIgnoreOutsideTouch,
            isInnerEdgeBufferTouch = ::isInnerEdgeBufferTouch,
            onRequestClose = ::close,
            onWarmCollapse = ::collapseToWarmInvisible,
        )

    private val dialog =
        windowLayout.createOverlayDialog(
            themedContext = themedContext,
            contentView = contentContainer,
            stateProvider = { currentState },
            onBackPressedIntercept = onBackPressedIntercept,
            onCloseRequested = ::close,
            onOutsideTouchRequested = scheduler::onOutsideTouch,
        )

    private val backdropController =
        PanelBackdropController(
            context = context,
            windowManager = windowManager,
            contentContainer = contentContainer,
            cornerRadiusProvider = { windowLayout.cornerPx(surfaceState.panelConfig) },
            windowProvider = { dialog.window },
        )

    private val motionDriver =
        PanelMotionDriver(
            contentContainer = contentContainer,
            surfaceState = surfaceState,
            windowLayout = windowLayout,
            backdropController = backdropController,
            windowProvider = { dialog.window },
            activeWebViewProvider = { activeWebView },
            stateProvider = { currentState },
            isClosing = { closingInProgress },
            isBackIntercepted = isBackIntercepted,
            onBackPressedIntercept = onBackPressedIntercept,
            onCommitBackClose = ::close,
            onRailMotionProgress = onRailMotionProgress,
            onContentAlphaChanged = { alpha ->
                if (activeEmbedToolbar != null) {
                    embedQuickBar.alpha = alpha
                }
            },
        )

    init {
        OverlayWindowFlags.preloadHardwareRenderer()
        dialog.window?.let(backdropController::attachToWindow)
        contentContainer.setOnApplyWindowInsetsListener { view, insets ->
            val prevIme = surfaceState.effectiveImeBottomPx
            val nextIme =
                PanelInsetsCoordinator.resolveEffectiveImeFromWindow(
                    context = themedContext,
                    windowManager = windowManager,
                    decorView = dialog.window?.decorView,
                    decorLocation = decorLocation,
                    insets = insets,
                    previousEffectiveImeBottomPx = prevIme,
                )
            surfaceState.lastLiveInsets = insets
            surfaceState.effectiveImeBottomPx = nextIme
            scheduler.onImeInsetsChanged(nextIme)
            if (prevIme != nextIme && currentState == PanelState.OPEN && !closingInProgress) {
                view.post {
                    if (currentState == PanelState.OPEN && !closingInProgress) {
                        refreshOpenGeometry()
                    }
                }
            }
            insets
        }
    }

    val isSystemBlurSupported: Boolean
        get() = backdropController.systemBlurSupported

    internal val overlayWindow: Window?
        get() = dialog.window

    internal val currentSafeBounds: PanelSafeBounds
        get() = windowLayout.safeBounds

    fun debugState(): DebugPanelWindowSnapshot =
        buildPanelDebugSnapshot(
            attached = attached,
            window = dialog.window,
            state = currentState,
            closingInProgress = closingInProgress,
            surfaceState = surfaceState,
            backdrop = backdropController.debugState(),
            motionProgress = motionDriver.currentProgress,
        )

    fun setContentWebView(
        webView: WebView?,
        embedToolbar: EmbedToolbarBinding? = null,
    ) {
        activeEmbedToolbar?.bindStatusListener?.invoke(null)
        if (webView == null) {
            activeWebView = null
            activeEmbedToolbar = null
            contentContainer.removeAllViews()
            updateLeftEdgeGestureExclusion()
            return
        }
        motionDriver.recomputeDimensions()
        val quickBar =
            if (embedToolbar != null) {
                embedQuickBar.bind(
                    title = embedToolbar.title,
                    onReload = embedToolbar.onReload,
                    onHome = embedToolbar.onHome,
                    onOpenExternal = embedToolbar.onOpenExternal,
                )
                embedQuickBar.setStatusMessage(null, isError = false)
                embedToolbar.bindStatusListener { msg, isError ->
                    embedQuickBar.setStatusMessage(msg, isError)
                }
                embedQuickBar
            } else {
                null
            }
        val toolbarChanged = (activeEmbedToolbar != null) != (embedToolbar != null)
        activeEmbedToolbar = embedToolbar
        if (activeWebView === webView && webView.parent === contentContainer && !toolbarChanged) {
            updateLeftEdgeGestureExclusion()
            return
        }
        val initialAlpha = if (currentState == PanelState.OPEN) 1f else motionDriver.currentProgress
        windowLayout.mountWebView(contentContainer, webView, initialAlpha, quickBar)
        activeWebView = webView
        updateLeftEdgeGestureExclusion()
        if (currentState == PanelState.OPEN && attached) webView.requestFocus()
    }

    fun setRailInsetDp(insetDp: Int) {
        val clamped = insetDp.coerceIn(0, 80)
        if (surfaceState.railInsetDp == clamped) return
        surfaceState.railInsetDp = clamped
        refreshOpenGeometry()
    }

    fun setPluginSizeOverride(
        override: PluginSizeOverride,
        mode: PluginPresentationMode = surfaceState.presentationMode,
    ) {
        if (surfaceState.pluginSizeOverride == override && surfaceState.presentationMode == mode) return
        surfaceState.pluginSizeOverride = override
        surfaceState.presentationMode = mode
        refreshOpenGeometry()
    }

    fun setPluginBackdrop(backdrop: PluginSurfaceBackdrop) = backdropController.setPluginBackdrop(backdrop)

    fun attachWarm() {
        if (attached) return
        runCatching {
            dialog.show()
            attached = true
            motionDriver.registerBackCallback(dialog)
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
        surfaceState.edgeSide = side
        surfaceState.panelConfig = config
        refreshOpenGeometry(reapplyBackdrop = false)
        backdropController.updateConfig(config)
    }

    fun syncRuntimeAppearance(
        themeId: String,
        colorScheme: String,
        dynamicTint: GlassTintOverride? = null,
    ) {
        dynamicTint?.let(backdropController::setDynamicTint)
        backdropController.syncRuntimeAppearance(themeId, colorScheme)
    }

    fun onGestureStart(startYFraction: Float = 0.5f) {
        prepareAndShow(resetOrigin = true, startYFraction = startYFraction, startProgress = 0f, focusable = false)
        setState(PanelState.DRAGGING)
    }

    fun onGestureMove(progress: Float) {
        if (currentState != PanelState.DRAGGING) onGestureStart()
        motionDriver.updateDrag(progress)
    }

    fun onGestureEnd(
        shouldOpen: Boolean,
        velocityPxPerSec: Float,
    ) = if (shouldOpen) open(initialVelocityPx = velocityPxPerSec) else close(initialVelocityPx = velocityPxPerSec)

    fun open(initialVelocityPx: Float = 0f) {
        val fromClosed = currentState == PanelState.CLOSED
        val startProgress = if (fromClosed) 0f else motionDriver.currentProgress
        prepareAndShow(resetOrigin = fromClosed, startYFraction = 0.5f, startProgress = startProgress, focusable = true)
        setState(PanelState.OPEN)
        motionDriver.animateOpen(startProgress, initialVelocityPx) {
            if (currentState == PanelState.OPEN && !closingInProgress) refreshOpenGeometry()
        }
        activeWebView?.requestFocus()
    }

    fun close(initialVelocityPx: Float = 0f) {
        if (!attached || currentState == PanelState.CLOSED || closingInProgress) return
        cancelPendingTimers()
        motionDriver.cancelAndResetBackVisuals()
        closingInProgress = true
        setImeFocused(false)
        motionDriver.recomputeDimensions()
        motionDriver.animateClose(initialVelocityPx) {
            closingInProgress = false
            onRailMotionProgress(0f)
            setState(PanelState.CLOSED)
            scheduler.scheduleWarmCollapse()
        }
    }

    fun toggle() = if (currentState == PanelState.OPEN && !closingInProgress) close() else open()

    fun setImeFocused(focused: Boolean) {
        if (!attached || currentState == PanelState.CLOSED) return
        activeWebView?.let { windowLayout.applyImeFocus(dialog.window, it, surfaceState.panelConfig, focused) }
    }

    fun cancelPendingTimers(includeCollapse: Boolean = true) = scheduler.cancelPendingTimers(includeCollapse)

    fun detach() {
        cancelPendingTimers()
        motionDriver.cancel()
        backdropController.unregisterBlurListener()
        motionDriver.unregisterBackCallback(dialog)
        if (attached) {
            runCatching { dialog.dismiss() }
            attached = false
        }
    }

    private fun prepareAndShow(
        resetOrigin: Boolean,
        startYFraction: Float,
        startProgress: Float,
        focusable: Boolean,
    ) {
        if (!attached) attachWarm()
        cancelPendingTimers()
        closingInProgress = false
        motionDriver.showPreparedFrame(resetOrigin, startYFraction, startProgress, focusable)
    }

    private fun refreshOpenGeometry(reapplyBackdrop: Boolean = true) {
        if (currentState == PanelState.OPEN && attached) {
            motionDriver.refreshOpenFrame(reapplyBackdrop)
        } else {
            motionDriver.recomputeDimensions()
        }
        updateLeftEdgeGestureExclusion()
    }

    private fun isInnerEdgeBufferTouch(
        rawX: Float,
        rawY: Float,
    ): Boolean {
        val decor = dialog.window?.decorView ?: return false
        decor.getLocationOnScreen(decorLocation)
        val left = decorLocation[0]
        val top = decorLocation[1]
        val right = left + windowLayout.cachedWidthPx
        val bottom = top + windowLayout.cachedHeightPx
        val density =
            themedContext.resources.displayMetrics.density
                .coerceAtLeast(1f)
        return PanelOutsideTouchPolicy.shouldIgnoreInnerEdgeOutsideTouch(
            edge = surfaceState.edgeSide,
            rawX = rawX,
            rawY = rawY,
            panelBoundsLeft = left,
            panelBoundsTop = top,
            panelBoundsRight = right,
            panelBoundsBottom = bottom,
            density = density,
        )
    }

    private fun updateLeftEdgeGestureExclusion() {
        val shouldExclude = surfaceState.edgeSide == EdgeSide.LEFT && activeEmbedToolbar != null
        if (!shouldExclude || windowLayout.cachedHeightPx <= 2) {
            contentContainer.systemGestureExclusionRects = emptyList()
            return
        }
        val density =
            themedContext.resources.displayMetrics.density
                .coerceAtLeast(1f)
        val edgeWidthPx = OverlayWindowFlags.dp(density, 28f)
        val maxBandPx = OverlayWindowFlags.dp(density, 180f)
        val height = windowLayout.cachedHeightPx
        val bandHeight = minOf(maxBandPx, (height * 0.45f).toInt().coerceAtLeast(1))
        val top = ((height - bandHeight) / 2).coerceAtLeast(0)
        contentContainer.systemGestureExclusionRects = listOf(Rect(0, top, edgeWidthPx, top + bandHeight))
    }

    private fun collapseToWarmInvisible() {
        closingInProgress = false
        scheduler.resetPolicyOnCollapse()
        if (motionDriver.collapseToWarmInvisible()) {
            setState(PanelState.CLOSED)
        }
    }

    private fun setState(next: PanelState) {
        if (currentState != next) {
            currentState = next
            onPanelStateChanged(next)
        }
    }

    companion object {
        private const val TAG = "SideVeilPanelWindow"
    }
}
