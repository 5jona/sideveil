package pochita.sideveil.host

import android.annotation.SuppressLint
import android.content.Context
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
    private val stateMachine = PanelDragStateMachine(onStateChanged = onPanelStateChanged)
    val currentState: PanelState
        get() = stateMachine.currentState
    private val closingInProgress: Boolean
        get() = stateMachine.closingInProgress
    private var attached = false

    private val contentContainer = windowLayout.createContentContainer(themedContext)

    private val scheduler: PanelOverlayScheduler =
        PanelOverlayScheduler(
            stateProvider = { currentState },
            isClosing = { closingInProgress },
            isRailTouch = shouldIgnoreOutsideTouch,
            isInnerEdgeBufferTouch = { rawX, rawY -> motionDriver.isInnerEdgeBufferTouch(rawX, rawY, decorLocation) },
            onRequestClose = ::close,
            onWarmCollapse = ::collapseToWarmInvisible,
            onDragWatchdogTimeout = {
                val progress = motionDriver.currentProgress
                when (stateMachine.decideDragWatchdog(progress)) {
                    DragWatchdogAction.OPEN -> {
                        Log.w(TAG, "Drag watchdog settled stalled drag to OPEN (progress=$progress)")
                        open()
                    }

                    DragWatchdogAction.CLOSE -> {
                        Log.w(TAG, "Drag watchdog settled stalled drag to CLOSE (progress=$progress)")
                        close()
                    }

                    DragWatchdogAction.NONE -> {
                        return@PanelOverlayScheduler
                    }
                }
            },
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

    private val motionDriver: PanelMotionDriver =
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
        stateMachine.beginGestureDrag()
        scheduler.scheduleDragWatchdog(DRAG_WATCHDOG_TIMEOUT_MS)
    }

    fun onGestureMove(progress: Float) {
        if (stateMachine.shouldRestartDragOnMove()) {
            val fromClosed = currentState == PanelState.CLOSED
            prepareAndShow(resetOrigin = fromClosed, startYFraction = 0.5f, startProgress = progress, focusable = false)
            stateMachine.beginGestureDrag()
        }
        motionDriver.updateDrag(progress)
        scheduler.scheduleDragWatchdog(DRAG_WATCHDOG_TIMEOUT_MS)
    }

    fun onGestureEnd(
        shouldOpen: Boolean,
        velocityPxPerSec: Float,
    ) {
        scheduler.cancelDragWatchdog()
        if (shouldOpen) open(initialVelocityPx = velocityPxPerSec) else close(initialVelocityPx = velocityPxPerSec)
    }

    fun open(initialVelocityPx: Float = 0f) {
        val fromClosed = currentState == PanelState.CLOSED
        val startProgress = if (fromClosed) 0f else motionDriver.currentProgress
        prepareAndShow(resetOrigin = fromClosed, startYFraction = 0.5f, startProgress = startProgress, focusable = true)
        val token = stateMachine.beginOpen()
        motionDriver.animateOpen(startProgress, initialVelocityPx) {
            if (stateMachine.onOpenSettled(token)) refreshOpenGeometry()
        }
        activeWebView?.requestFocus()
    }

    fun close(
        initialVelocityPx: Float = 0f,
        forceImmediate: Boolean = false,
    ) {
        if (forceImmediate) {
            cancelPendingTimers()
            motionDriver.cancelActiveAnimations()
            if (attached) setImeFocused(false)
            stateMachine.forceCloseImmediate()
            motionDriver.resetProgressToClosed()
            if (attached) collapseToWarmInvisible()
            return
        }
        val token = stateMachine.beginClose(attached = attached) ?: return
        cancelPendingTimers()
        motionDriver.cancelActiveAnimations()
        setImeFocused(false)
        motionDriver.recomputeDimensions()
        motionDriver.animateClose(initialVelocityPx) {
            if (!stateMachine.onCloseSettled(token)) return@animateClose
            onRailMotionProgress(0f)
            scheduler.scheduleWarmCollapse()
        }
    }

    fun forceCloseImmediate() = close(forceImmediate = true)

    fun toggle() = if (stateMachine.shouldToggleClose()) close() else open()

    fun setImeFocused(focused: Boolean) {
        if (!attached || currentState == PanelState.CLOSED) return
        activeWebView?.let { windowLayout.applyImeFocus(dialog.window, it, surfaceState.panelConfig, focused) }
    }

    fun cancelPendingTimers(includeCollapse: Boolean = true) = scheduler.cancelPendingTimers(includeCollapse)

    fun detach() {
        cancelPendingTimers()
        motionDriver.cancelActiveAnimations()
        stateMachine.onDetach()
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
        motionDriver.cancelActiveAnimations()
        stateMachine.interruptClosing()
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

    private fun updateLeftEdgeGestureExclusion() = motionDriver.updateLeftEdgeGestureExclusion(activeEmbedToolbar != null)

    private fun collapseToWarmInvisible() {
        scheduler.resetPolicyOnCollapse()
        stateMachine.onWarmCollapsed(markClosed = motionDriver.collapseToWarmInvisible())
    }

    companion object {
        private const val TAG = "SideVeilPanelWindow"
        const val DRAG_WATCHDOG_TIMEOUT_MS = PanelDragStateMachine.DEFAULT_DRAG_WATCHDOG_TIMEOUT_MS
    }
}
