package pochita.sideveil.host.panel

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.Window
import android.view.WindowManager
import android.webkit.WebView
import pochita.sideveil.host.R
import pochita.sideveil.host.debug.buildPanelDebugSnapshot
import pochita.sideveil.host.plugin.EmbedToolbarBinding
import pochita.sideveil.host.plugin.PluginPanelOverlayState
import pochita.sideveil.host.plugin.PluginSizeOverride
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
    onPanelStateChanged: (PanelState) -> Unit,
    onBackPressedIntercept: () -> Boolean = { false },
    isBackIntercepted: () -> Boolean = { false },
    private val onRailMotionProgress: (Float) -> Unit = {},
    shouldIgnoreOutsideTouch: (Float, Float) -> Boolean = { _, _ -> false },
) {
    private val themedContext = ContextThemeWrapper(context, R.style.Theme_SideVeil_OverlayDialog)
    private val windowLayout = PanelWindowLayout(context, windowManager)
    private val surfaceState = PanelSurfaceState()
    private val decorLocation = IntArray(2)
    private val stateMachine = PanelDragStateMachine(onStateChanged = onPanelStateChanged)
    val currentState: PanelState
        get() = stateMachine.currentState
    private val closingInProgress: Boolean
        get() = stateMachine.closingInProgress
    private var attached = false
    private val contentContainer = windowLayout.createContentContainer(themedContext)
    private val contentMount = PanelContentMountController(themedContext, contentContainer, windowLayout, surfaceState)
    private val scheduler: PanelOverlayScheduler =
        PanelOverlayScheduler(
            { currentState },
            { closingInProgress },
            shouldIgnoreOutsideTouch,
            { rawX, rawY -> motionDriver.isInnerEdgeBufferTouch(rawX, rawY, decorLocation) },
            ::close,
            ::collapseToWarmInvisible,
            ::handleDragWatchdogTimeout,
        )
    private val dialog =
        windowLayout.createOverlayDialog(
            themedContext,
            contentContainer,
            { currentState },
            onBackPressedIntercept,
            ::close,
            scheduler::onOutsideTouch,
        )
    private val backdropController: PanelBackdropController =
        PanelDialogFactory.createBackdropController(
            context,
            themedContext,
            windowManager,
            contentContainer,
            windowLayout,
            surfaceState,
            decorLocation,
            scheduler,
            dialog,
            ::refreshOpenGeometry,
        )
    private val motionDriver: PanelMotionDriver =
        PanelDialogFactory.createMotionDriver(
            contentContainer,
            surfaceState,
            windowLayout,
            backdropController,
            { dialog },
            contentMount,
            stateMachine,
            isBackIntercepted,
            onBackPressedIntercept,
            ::close,
            onRailMotionProgress,
        )

    val isSystemBlurSupported: Boolean
        get() = backdropController.systemBlurSupported
    internal val overlayWindow: Window?
        get() = dialog.window
    internal val currentSafeBounds: PanelSafeBounds
        get() = windowLayout.safeBounds

    fun debugState(): DebugPanelWindowSnapshot =
        buildPanelDebugSnapshot(
            attached,
            dialog.window,
            currentState,
            closingInProgress,
            surfaceState,
            backdropController.debugState(),
            motionDriver.currentProgress,
        )

    fun setContentWebView(
        webView: WebView?,
        embedToolbar: EmbedToolbarBinding? = null,
    ) {
        val alpha = if (currentState == PanelState.OPEN) 1f else motionDriver.currentProgress
        val focusable = currentState == PanelState.OPEN && attached
        contentMount.mount(webView, embedToolbar, alpha, focusable, motionDriver::recomputeDimensions) {
            motionDriver.updateLeftEdgeGestureExclusion(contentMount.hasEmbedToolbar)
        }
    }

    fun setPanelOverlayState(state: PluginPanelOverlayState) {
        val alpha = if (currentState == PanelState.OPEN) 1f else motionDriver.currentProgress
        contentMount.setOverlayState(state, alpha, motionDriver::recomputeDimensions) {
            motionDriver.updateLeftEdgeGestureExclusion(false)
        }
    }

    fun setRailInsetDp(insetDp: Int) {
        if (contentMount.updateRailInsetDp(insetDp)) refreshOpenGeometry()
    }

    fun setPluginSizeOverride(
        override: PluginSizeOverride,
        mode: PluginPresentationMode = surfaceState.presentationMode,
    ) {
        if (contentMount.updatePluginSizeOverride(override, mode)) refreshOpenGeometry()
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
        }.onFailure { err -> Log.e(TAG, "Failed to attach warm panel dialog overlay", err) }
    }

    fun onSystemConfigurationChanged() {
        refreshOpenGeometry(reapplyBackdrop = false)
        backdropController.onSystemConfigurationChanged()
    }

    fun updateConfig(
        side: EdgeSide,
        config: PanelConfig,
    ) {
        contentMount.updateConfig(side, config)
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
        contentMount.requestFocusIfMounted()
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
        if (attached && currentState != PanelState.CLOSED) contentMount.applyImeFocus(dialog.window, focused)
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

    private fun handleDragWatchdogTimeout() {
        when (stateMachine.decideDragWatchdog(motionDriver.currentProgress)) {
            DragWatchdogAction.OPEN -> open()
            DragWatchdogAction.CLOSE -> close()
            DragWatchdogAction.NONE -> return
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
        val openAttached = currentState == PanelState.OPEN && attached
        if (openAttached) motionDriver.refreshOpenFrame(reapplyBackdrop) else motionDriver.recomputeDimensions()
        motionDriver.updateLeftEdgeGestureExclusion(contentMount.hasEmbedToolbar)
    }

    private fun collapseToWarmInvisible() {
        scheduler.resetPolicyOnCollapse()
        stateMachine.onWarmCollapsed(markClosed = motionDriver.collapseToWarmInvisible())
    }

    companion object {
        private const val TAG = "SideVeilPanelWindow"
        const val DRAG_WATCHDOG_TIMEOUT_MS = PanelDragStateMachine.DEFAULT_DRAG_WATCHDOG_TIMEOUT_MS
    }
}
