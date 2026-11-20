package pochita.sideveil.host

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
import android.graphics.PixelFormat
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.webkit.WebView
import android.widget.FrameLayout
import android.window.OnBackInvokedCallback
import pochita.sideveil.protocol.EdgeSide
import pochita.sideveil.protocol.PanelConfig
import pochita.sideveil.protocol.PanelState
import pochita.sideveil.protocol.PluginSurfaceBackdrop

/**
 * Warm-resident `TYPE_APPLICATION_OVERLAY` Dialog container hosting the active plugin
 * WebView over a two-layer rendering architecture coordinated by [PanelBackdropController]
 * and [PanelWindowLayout], supporting Android 16–17 [OnBackInvokedCallback] back interception.
 */
@SuppressLint("RtlHardcoded")
class SidebarPanelWindow(
    private val context: Context,
    windowManager: WindowManager,
    private val onPanelStateChanged: (PanelState) -> Unit,
    private val onBackPressedIntercept: () -> Boolean = { false },
) {
    private val themedContext = ContextThemeWrapper(context, R.style.Theme_Sideveil_OverlayDialog)
    private val springController = PanelSpringController()
    private val windowLayout = PanelWindowLayout(context, windowManager)

    private var activeWebView: WebView? = null
    private var currentState: PanelState = PanelState.CLOSED
    private var closingInProgress = false
    private var attached = false
    private var backCallbackRegistered = false
    private var edgeSide = EdgeSide.RIGHT
    private var railInsetDp = 0
    private var panelConfig = PanelConfig()
    private var pluginSizeOverride = PluginSizeOverride()

    private val contentContainer = FrameLayout(themedContext)

    private val backInvokedCallback =
        OnBackInvokedCallback {
            handleBackNavigation()
        }

    private val dialog =
        object : Dialog(themedContext, R.style.Theme_Sideveil_OverlayDialog) {
            override fun cancel() {
                close()
            }

            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                val isBackUp = event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP
                if (isBackUp && currentState == PanelState.OPEN) {
                    handleBackNavigation()
                    return true
                }
                return super.dispatchKeyEvent(event)
            }

            override fun onTouchEvent(event: MotionEvent): Boolean {
                if (event.action == MotionEvent.ACTION_OUTSIDE && currentState == PanelState.OPEN) {
                    close()
                    return true
                }
                return super.onTouchEvent(event)
            }
        }

    private val backdropController =
        PanelBackdropController(
            context = context,
            windowManager = windowManager,
            contentContainer = contentContainer,
            cornerRadiusProvider = { windowLayout.cornerPx(panelConfig) },
            windowProvider = { dialog.window },
        )

    init {
        OverlayWindowFlags.preloadHardwareRenderer()

        dialog.setCanceledOnTouchOutside(true)
        dialog.setCancelable(true)
        dialog.setContentView(
            contentContainer,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )

        val window = dialog.window ?: error("Overlay dialog window unavailable")
        window.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        window.setFormat(PixelFormat.TRANSLUCENT)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        window.decorView.setPadding(0, 0, 0, 0)
        backdropController.attachToWindow(window)
        windowLayout.configureInitialWindow(window)
    }

    fun isSystemBlurSupported(): Boolean = backdropController.systemBlurSupported

    fun isDarkSurfaceActive(): Boolean = backdropController.colorScheme != "light"

    fun getCurrentState(): PanelState = currentState

    fun setContentWebView(webView: WebView) {
        if (activeWebView === webView && webView.parent === contentContainer) return
        (webView.parent as? ViewGroup)?.removeView(webView)
        contentContainer.removeAllViews()
        contentContainer.addView(
            webView,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT),
        )
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

    fun setPluginBackdrop(backdrop: PluginSurfaceBackdrop) {
        backdropController.setPluginBackdrop(backdrop)
    }

    fun attachWarm() {
        if (attached) return
        runCatching {
            dialog.show()
            attached = true
            if (!backCallbackRegistered) {
                backCallbackRegistered = windowLayout.registerBackCallback(dialog, backInvokedCallback)
            }
            collapseToWarmInvisible()
            backdropController.registerBlurListener()
            backdropController.apply()
        }.onFailure { err ->
            Log.e(TAG, "Failed to attach warm panel dialog overlay", err)
        }
    }

    fun onSystemConfigurationChanged() {
        windowLayout.recomputeDimensions(panelConfig, railInsetDp, pluginSizeOverride)
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
        windowLayout.recomputeDimensions(panelConfig, railInsetDp, pluginSizeOverride)
        backdropController.updateConfig(config)
        if (currentState == PanelState.OPEN && attached) {
            applyOpenLayout(settled = true)
        }
    }

    fun syncRuntimeAppearance(
        themeId: String,
        colorScheme: String,
    ) {
        backdropController.syncRuntimeAppearance(themeId, colorScheme)
    }

    fun onGestureStart() {
        if (!attached) attachWarm()
        springController.cancel()
        closingInProgress = false
        windowLayout.recomputeDimensions(panelConfig, railInsetDp, pluginSizeOverride)
        val startX = windowLayout.hiddenOffsetX().toFloat()
        springController.snapTo(startX)
        showInteractiveWindow(xPx = startX.toInt(), focusable = false, allowOffscreen = true)
        setState(PanelState.DRAGGING)
    }

    fun onGestureMove(progress: Float) {
        if (currentState != PanelState.DRAGGING) {
            onGestureStart()
        }
        val nextX =
            springController.updateDrag(
                progress = progress,
                hiddenX = windowLayout.hiddenOffsetX().toFloat(),
                openX = windowLayout.openMarginPx(railInsetDp).toFloat(),
            )
        dialog.window?.let { win ->
            windowLayout.updatePosition(win, panelConfig, nextX.toInt(), focusable = false, allowOffscreen = true)
        }
    }

    fun onGestureEnd(
        shouldOpen: Boolean,
        velocityPxPerSec: Float,
    ) {
        if (shouldOpen) {
            open(initialVelocityPx = velocityPxPerSec)
        } else {
            close(initialVelocityPx = velocityPxPerSec)
        }
    }

    fun open(initialVelocityPx: Float = 0f) {
        if (!attached) attachWarm()
        springController.cancel()
        closingInProgress = false
        windowLayout.recomputeDimensions(panelConfig, railInsetDp, pluginSizeOverride)

        val startX =
            if (currentState == PanelState.CLOSED) windowLayout.hiddenOffsetX().toFloat() else springController.currentX
        val endX = windowLayout.openMarginPx(railInsetDp).toFloat()
        springController.snapTo(startX)
        showInteractiveWindow(xPx = startX.toInt(), focusable = true, allowOffscreen = true)
        setState(PanelState.OPEN)

        springController.animateTo(
            startX = startX,
            endX = endX,
            startVelocityPx = initialVelocityPx,
            onUpdate = { value ->
                dialog.window?.let { win ->
                    windowLayout.updatePosition(win, panelConfig, value.toInt(), focusable = true, allowOffscreen = true)
                }
            },
            onSettled = {
                if (currentState == PanelState.OPEN && !closingInProgress) {
                    applyOpenLayout(settled = true)
                }
            },
        )
        activeWebView?.requestFocus()
    }

    fun close(initialVelocityPx: Float = 0f) {
        if (!attached || currentState == PanelState.CLOSED || closingInProgress) return
        springController.cancel()
        closingInProgress = true
        setImeFocused(false)
        windowLayout.recomputeDimensions(panelConfig, railInsetDp, pluginSizeOverride)

        val closeVelocityPx = if (initialVelocityPx <= 0f) initialVelocityPx else -initialVelocityPx
        springController.animateTo(
            startX = springController.currentX,
            endX = windowLayout.hiddenOffsetX().toFloat(),
            startVelocityPx = closeVelocityPx,
            onUpdate = { value ->
                dialog.window?.let { win ->
                    windowLayout.updatePosition(win, panelConfig, value.toInt(), focusable = false, allowOffscreen = true)
                }
            },
            onSettled = { collapseToWarmInvisible() },
        )
    }

    fun toggle() {
        if (currentState == PanelState.OPEN && !closingInProgress) close() else open()
    }

    fun setImeFocused(focused: Boolean) {
        if (!attached || currentState == PanelState.CLOSED) return
        val webView = activeWebView ?: return
        val imm = context.getSystemService(InputMethodManager::class.java)
        if (focused) {
            dialog.window?.let { win ->
                windowLayout.updateFlags(win, panelConfig, focusable = true, allowOffscreen = false)
            }
            webView.requestFocus()
            imm?.showSoftInput(webView, InputMethodManager.SHOW_IMPLICIT)
        } else {
            imm?.hideSoftInputFromWindow(webView.windowToken, 0)
        }
    }

    fun detach() {
        springController.cancel()
        backdropController.unregisterBlurListener()
        if (backCallbackRegistered) {
            windowLayout.unregisterBackCallback(dialog, backInvokedCallback)
            backCallbackRegistered = false
        }
        if (attached) {
            runCatching { dialog.dismiss() }
            attached = false
        }
    }

    private fun handleBackNavigation() {
        if (currentState == PanelState.OPEN && !onBackPressedIntercept()) {
            close()
        }
    }

    private fun refreshOpenGeometry() {
        windowLayout.recomputeDimensions(panelConfig, railInsetDp, pluginSizeOverride)
        if (currentState == PanelState.OPEN && attached) {
            applyOpenLayout(settled = true)
        }
    }

    private fun showInteractiveWindow(
        xPx: Int,
        focusable: Boolean,
        allowOffscreen: Boolean,
    ) {
        val window = dialog.window ?: return
        windowLayout.showInteractive(window, edgeSide, panelConfig, xPx, focusable, allowOffscreen)
        backdropController.apply()
    }

    private fun applyOpenLayout(settled: Boolean) {
        val window = dialog.window ?: return
        windowLayout.updatePosition(
            window = window,
            panelConfig = panelConfig,
            xPx = windowLayout.openMarginPx(railInsetDp),
            focusable = true,
            allowOffscreen = !settled,
        )
    }

    private fun collapseToWarmInvisible() {
        closingInProgress = false
        val window = dialog.window ?: return
        backdropController.clearWindowBlur(window)
        windowLayout.collapseToWarmInvisible(window)
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
    }
}
