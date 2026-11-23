package pochita.sideveil.host.panel

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Window
import android.view.WindowManager
import android.webkit.WebView
import android.widget.FrameLayout
import pochita.sideveil.host.plugin.EmbedQuickBarView
import pochita.sideveil.host.plugin.EmbedToolbarBinding
import pochita.sideveil.host.plugin.PluginSizeOverride
import pochita.sideveil.protocol.EdgeSide
import pochita.sideveil.protocol.PanelConfig
import pochita.sideveil.protocol.PanelState
import pochita.sideveil.protocol.PluginPresentationMode

/**
 * Coordinates mounting of the active plugin [WebView] and optional [EmbedQuickBarView] inside
 * the panel's content container, as well as rail-inset, size-override, and config state updates.
 */
internal class PanelContentMountController(
    themedContext: Context,
    private val contentContainer: FrameLayout,
    private val windowLayout: PanelWindowLayout,
    private val surfaceState: PanelSurfaceState,
) {
    var activeWebView: WebView? = null
        private set
    private var activeEmbedToolbar: EmbedToolbarBinding? = null
    private val embedQuickBar: EmbedQuickBarView by lazy { EmbedQuickBarView(themedContext) }

    val hasEmbedToolbar: Boolean
        get() = activeEmbedToolbar != null

    fun setContentAlpha(alpha: Float) {
        if (activeEmbedToolbar != null) {
            embedQuickBar.alpha = alpha
        }
    }

    fun requestFocusIfMounted() {
        activeWebView?.requestFocus()
    }

    fun applyImeFocus(
        window: Window?,
        focused: Boolean,
    ) {
        activeWebView?.let { windowLayout.applyImeFocus(window, it, surfaceState.panelConfig, focused) }
    }

    fun updateConfig(
        side: EdgeSide,
        config: PanelConfig,
    ) {
        surfaceState.edgeSide = side
        surfaceState.panelConfig = config
    }

    fun updateRailInsetDp(insetDp: Int): Boolean {
        val clamped = insetDp.coerceIn(0, 80)
        if (surfaceState.railInsetDp == clamped) return false
        surfaceState.railInsetDp = clamped
        return true
    }

    fun updatePluginSizeOverride(
        override: PluginSizeOverride,
        mode: PluginPresentationMode,
    ): Boolean {
        if (surfaceState.pluginSizeOverride == override && surfaceState.presentationMode == mode) return false
        surfaceState.pluginSizeOverride = override
        surfaceState.presentationMode = mode
        return true
    }

    fun mount(
        webView: WebView?,
        embedToolbar: EmbedToolbarBinding?,
        initialAlpha: Float,
        shouldRequestFocus: Boolean,
        onBeforeMount: () -> Unit,
        onAfterMount: () -> Unit,
    ) {
        activeEmbedToolbar?.bindStatusListener?.invoke(null)
        if (webView == null) {
            activeWebView = null
            activeEmbedToolbar = null
            contentContainer.removeAllViews()
            onAfterMount()
            return
        }
        onBeforeMount()
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
            onAfterMount()
            return
        }
        windowLayout.mountWebView(contentContainer, webView, initialAlpha, quickBar)
        activeWebView = webView
        onAfterMount()
        if (shouldRequestFocus) webView.requestFocus()
    }
}

/**
 * Schedules debounced outside-touch dismissal (backed by [PanelOutsideTouchPolicy]),
 * post-dismiss warm-retain collapse timers, live `WindowInsets` binding, and the `DRAGGING`
 * stall watchdog for [SidebarPanelWindow].
 */
internal class PanelOverlayScheduler(
    private val stateProvider: () -> PanelState,
    private val isClosing: () -> Boolean,
    private val isRailTouch: (Float, Float) -> Boolean,
    private val isInnerEdgeBufferTouch: (Float, Float) -> Boolean = { _, _ -> false },
    private val onRequestClose: () -> Unit,
    private val onWarmCollapse: () -> Unit,
    private val onDragWatchdogTimeout: () -> Unit = {},
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val outsideTouchPolicy = PanelOutsideTouchPolicy(nowMs = SystemClock::uptimeMillis)
    private var pendingCollapse: Runnable? = null
    private var pendingOutsideClose: Runnable? = null
    private var pendingDragWatchdog: Runnable? = null

    fun bindWindowInsetsListener(
        contentContainer: FrameLayout,
        themedContext: Context,
        windowManager: WindowManager,
        windowProvider: () -> Window?,
        surfaceState: PanelSurfaceState,
        decorLocation: IntArray,
        onImeChangedWhileOpen: () -> Unit,
    ) {
        contentContainer.setOnApplyWindowInsetsListener { view, insets ->
            val prevIme = surfaceState.effectiveImeBottomPx
            val nextIme =
                PanelInsetsCoordinator.resolveEffectiveImeFromWindow(
                    context = themedContext,
                    windowManager = windowManager,
                    decorView = windowProvider()?.decorView,
                    decorLocation = decorLocation,
                    insets = insets,
                    previousEffectiveImeBottomPx = prevIme,
                )
            surfaceState.lastLiveInsets = insets
            surfaceState.effectiveImeBottomPx = nextIme
            onImeInsetsChanged(nextIme)
            if (prevIme != nextIme && stateProvider() == PanelState.OPEN && !isClosing()) {
                view.post {
                    if (stateProvider() == PanelState.OPEN && !isClosing()) onImeChangedWhileOpen()
                }
            }
            insets
        }
    }

    fun onImeInsetsChanged(nextImeBottomPx: Int) {
        outsideTouchPolicy.onImeInsetsChanged(nextImeBottomPx)
        if (nextImeBottomPx > 0) {
            cancelPendingTimers(includeCollapse = false)
        }
    }

    fun onOutsideTouch(
        rawX: Float,
        rawY: Float,
    ) {
        if (shouldIgnore(rawX, rawY)) return
        cancelPendingTimers(includeCollapse = false)
        val task =
            Runnable {
                pendingOutsideClose = null
                if (!shouldIgnore(rawX, rawY)) onRequestClose()
            }
        pendingOutsideClose = task
        mainHandler.postDelayed(task, OUTSIDE_CLOSE_DEBOUNCE_MS)
    }

    fun scheduleWarmCollapse() {
        cancelPendingTimers()
        val task =
            Runnable {
                pendingCollapse = null
                if (stateProvider() == PanelState.CLOSED) onWarmCollapse()
            }
        pendingCollapse = task
        mainHandler.postDelayed(task, POST_DISMISS_WARM_RETAIN_MS)
    }

    fun scheduleDragWatchdog(timeoutMs: Long = DRAG_WATCHDOG_TIMEOUT_MS) {
        cancelDragWatchdog()
        val task =
            Runnable {
                pendingDragWatchdog = null
                onDragWatchdogTimeout()
            }
        pendingDragWatchdog = task
        mainHandler.postDelayed(task, timeoutMs)
    }

    fun cancelDragWatchdog() {
        pendingDragWatchdog?.let(mainHandler::removeCallbacks)
        pendingDragWatchdog = null
    }

    fun cancelPendingTimers(
        includeCollapse: Boolean = true,
        includeDragWatchdog: Boolean = includeCollapse,
    ) {
        pendingOutsideClose?.let(mainHandler::removeCallbacks)
        pendingOutsideClose = null
        if (includeCollapse) {
            pendingCollapse?.let(mainHandler::removeCallbacks)
            pendingCollapse = null
        }
        if (includeDragWatchdog) {
            cancelDragWatchdog()
        }
    }

    fun resetPolicyOnCollapse() {
        outsideTouchPolicy.reset()
    }

    private fun shouldIgnore(
        rawX: Float,
        rawY: Float,
    ): Boolean =
        outsideTouchPolicy.shouldIgnoreOutsideTouch(
            panelOpen = stateProvider() == PanelState.OPEN,
            closingInProgress = isClosing(),
            isRailTouch = isRailTouch(rawX, rawY) || isInnerEdgeBufferTouch(rawX, rawY),
        )

    companion object {
        private const val POST_DISMISS_WARM_RETAIN_MS = 150L
        private const val OUTSIDE_CLOSE_DEBOUNCE_MS = 72L
        const val DRAG_WATCHDOG_TIMEOUT_MS = PanelDragStateMachine.DEFAULT_DRAG_WATCHDOG_TIMEOUT_MS
    }
}
