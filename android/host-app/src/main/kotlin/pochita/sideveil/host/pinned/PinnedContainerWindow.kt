package pochita.sideveil.host.pinned

import android.annotation.SuppressLint
import android.content.Context
import android.os.SystemClock
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowInsets
import android.view.WindowManager
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.LinearLayout
import pochita.sideveil.host.R
import pochita.sideveil.host.debug.buildOverlayWindowDebugSnapshot
import pochita.sideveil.host.overlay.GlassBackdropDrawable
import pochita.sideveil.host.overlay.OverlayWindowFlags
import pochita.sideveil.protocol.DebugOverlayWindowSnapshot
import pochita.sideveil.protocol.GlassTintOverride
import pochita.sideveil.protocol.PanelConfig
import pochita.sideveil.protocol.PluginSurfaceBackdrop
import kotlin.math.roundToInt

/**
 * Warm-resident draggable `TYPE_APPLICATION_OVERLAY` Dialog hosting plugins in
 * `PluginPresentationMode.PINNED` mode. Uses `BlurMaskDrawable` + `GlassBackdropDrawable` via
 * [PinnedBackdropController], delegates single-layer bottom drag handle and unlock chip chrome
 * to [PinnedChromeViews], keeps `FLAG_NOT_FOCUSABLE` by default, and temporarily clears it only
 * while the pinned plugin actively holds IME focus.
 */
@SuppressLint("RtlHardcoded")
class PinnedContainerWindow(
    private val context: Context,
    windowManager: WindowManager,
    private val onClickThroughChanged: (Boolean) -> Unit,
) {
    private val themedContext = ContextThemeWrapper(context, R.style.Theme_SideVeil_OverlayDialog)
    private val windowLayout = PinnedWindowLayout(context, windowManager)
    private val backdropController =
        PinnedBackdropController(context, windowManager) {
            if (visible) applyVisualStyle()
        }
    private val webSlot = FrameLayout(themedContext)
    private val chrome =
        PinnedChromeViews(
            context = context,
            themedContext = themedContext,
            windowManager = windowManager,
            onDragEvent = ::handleDrag,
            onUnlockClickThrough = { applyClickThroughState(enabled = false, notify = true) },
        )
    private val rootContainer =
        LinearLayout(themedContext).apply {
            orientation = LinearLayout.VERTICAL
            background = backdropController.backdropDrawable
            clipToOutline = true
            outlineProvider = GlassBackdropDrawable.createContentOutlineProvider { backdropController.cornerPx() }
        }

    private var attached = false
    private var visible = false
    private var clickThrough = false
    private var imeFocused = false
    private var lastImeBottomPx = 0
    private var imeFocusRequestedAtMs = 0L
    private var mountedWebView: WebView? = null
    private var dragStartRawX = 0f
    private var dragStartRawY = 0f
    private var dragStartWinX = 0
    private var dragStartWinY = 0

    private val dialog =
        windowLayout.createOverlayDialog(
            themedContext = themedContext,
            contentView = rootContainer,
            initialCornerPx = backdropController.initialCornerPx(),
            isImeFocused = { imeFocused },
            onReleaseImeFocus = { setImeFocused(false) },
            onOutsideTouchWhileFocused = ::handleOutsideTouchWhileFocused,
        )

    /** Whether a pinned plugin surface is currently visible on screen. */
    val isVisible: Boolean
        get() = visible

    internal val overlayWindow: Window?
        get() = dialog.window

    /** Captures a diagnostic snapshot of the pinned overlay window. */
    fun debugState(activePluginId: String? = null): DebugOverlayWindowSnapshot =
        buildOverlayWindowDebugSnapshot(
            name = "pinned",
            attached = attached,
            visible = visible,
            window = dialog.window,
            alpha = rootContainer.alpha,
            appliedBlurRadiusPx = backdropController.appliedBlurRadiusPx(),
            clickThrough = clickThrough,
            imeFocused = imeFocused,
            activePluginId = if (visible) activePluginId else null,
            agsl = backdropController.agslDebugState(),
        )

    init {
        backdropController.syncChromeStyle(chrome)
        rootContainer.addView(webSlot, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        rootContainer.addView(
            chrome.bottomHandleBar,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, chrome.handleBarHeightPx()),
        )
        rootContainer.setOnApplyWindowInsetsListener { _, insets ->
            val nextIme = insets.getInsets(WindowInsets.Type.ime()).bottom
            val wasImeVisible = lastImeBottomPx > 0
            lastImeBottomPx = nextIme
            if (wasImeVisible && nextIme == 0 && imeFocused) {
                rootContainer.post {
                    val active = attached && visible
                    if (active && imeFocused && lastImeBottomPx == 0) {
                        setImeFocused(false)
                    }
                }
            }
            insets
        }
    }

    /** Attaches the warm-invisible `1x1` dialog and registers the cross-window blur listener. */
    fun attachWarm() {
        if (attached) return
        runCatching {
            dialog.show()
            attached = true
            backdropController.registerBlurListener()
            dialog.window?.let(::collapseWindow)
        }.onFailure { err -> Log.w(TAG, "Failed to attach PinnedContainerWindow", err) }
    }

    /** Mounts [webView] inside the pinned container and reveals the floating window. */
    fun show(
        webView: WebView,
        backdrop: PluginSurfaceBackdrop,
        bounds: PinnedBoundsSpec? = null,
    ) {
        if (!attached) attachWarm()
        if (!attached) return
        backdropController.setBackdrop(backdrop)
        visible = true
        imeFocused = false
        lastImeBottomPx = 0
        (webView.parent as? ViewGroup)?.removeView(webView)
        webSlot.removeAllViews()
        webSlot.addView(
            webView,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT),
        )
        webView.alpha = 1f
        mountedWebView = webView

        bounds?.let(windowLayout::applyBounds)
        windowLayout.clampPosition()
        val desiredClickThrough = bounds?.clickThrough ?: false
        applyClickThroughState(enabled = desiredClickThrough, notify = desiredClickThrough)
        applyVisualStyle()
        syncWindowLayout()
        dialog.window?.let { win ->
            win.decorView.visibility = View.VISIBLE
            OverlayWindowFlags.applyHighFrameRate(rootContainer, context, win.decorView.display)
            OverlayWindowFlags.applyHighFrameRate(webView, context, win.decorView.display)
        }
    }

    /** Requests or releases transient IME focus for the pinned WebView. */
    fun setImeFocused(focused: Boolean) {
        if (focused) {
            if (!attached || !visible || clickThrough) return
            imeFocused = true
            imeFocusRequestedAtMs = SystemClock.uptimeMillis()
            syncWindowLayout()
            mountedWebView?.let { wv ->
                windowLayout.showSoftInput(wv) { imeFocused && visible && !clickThrough }
            }
        } else {
            val wasFocused = imeFocused
            imeFocused = false
            lastImeBottomPx = 0
            mountedWebView?.let { wv -> windowLayout.hideSoftInput(wv, clearFocus = wasFocused) }
            if (wasFocused && attached && visible) {
                syncWindowLayout()
            }
        }
    }

    /** Updates the pinned surface backdrop mode (`native-glass`, `transparent`, `opaque`). */
    fun updateBackdrop(backdrop: PluginSurfaceBackdrop) {
        backdropController.setBackdrop(backdrop)
        if (visible) applyVisualStyle()
    }

    /** Updates the pinned window dimensions, position, and optional click-through flag. */
    fun updateBounds(bounds: PinnedBoundsSpec) {
        windowLayout.applyBounds(bounds)
        bounds.clickThrough?.let { applyClickThroughState(enabled = it, notify = false) }
        if (visible) syncWindowLayout()
    }

    /** Updates theme, dark mode, and dynamic glass tint on the pinned window. */
    fun updateStyle(
        config: PanelConfig,
        nextThemeId: String,
        dark: Boolean,
        tint: GlassTintOverride?,
    ) {
        backdropController.updateStyle(config, nextThemeId, dark, tint, chrome)
        if (visible) applyVisualStyle()
    }

    /** Unmounts the WebView and collapses the pinned window back to warm-invisible `1x1`. */
    fun hide() {
        setImeFocused(false)
        visible = false
        applyClickThroughState(enabled = false, notify = false)
        mountedWebView?.let(OverlayWindowFlags::clearHighFrameRate)
        mountedWebView = null
        webSlot.removeAllViews()
        dialog.window?.let(::collapseWindow)
    }

    /** Hides the pinned container, unregisters the blur listener, and dismisses the dialog. */
    fun detach() {
        hide()
        backdropController.unregisterBlurListener()
        if (attached) {
            runCatching { dialog.dismiss() }
            attached = false
        }
    }

    private fun handleOutsideTouchWhileFocused() {
        if (!imeFocused || lastImeBottomPx > 0) return
        if (SystemClock.uptimeMillis() - imeFocusRequestedAtMs < IME_OPEN_GRACE_MS) return
        setImeFocused(false)
    }

    private fun handleDrag(event: MotionEvent): Boolean {
        if (!visible || clickThrough) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragStartRawX = event.rawX
                dragStartRawY = event.rawY
                dragStartWinX = windowLayout.x
                dragStartWinY = windowLayout.y
                return true
            }

            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                windowLayout.setPositionClamped(
                    nextX = dragStartWinX + (event.rawX - dragStartRawX).roundToInt(),
                    nextY = dragStartWinY + (event.rawY - dragStartRawY).roundToInt(),
                )
                syncWindowLayout()
                return true
            }
        }
        return false
    }

    private fun applyClickThroughState(
        enabled: Boolean,
        notify: Boolean,
    ) {
        if (enabled && imeFocused) setImeFocused(false)
        val changed = clickThrough != enabled
        clickThrough = enabled
        chrome.setHandleVisible(!enabled)
        if (visible) syncWindowLayout() else chrome.detachUnlockChip()
        if (notify && changed) onClickThroughChanged(enabled)
    }

    private fun applyVisualStyle() {
        dialog.window?.let { backdropController.apply(it, rootContainer, chrome) }
    }

    private fun syncWindowLayout() {
        val window = dialog.window ?: return
        if (!attached || !visible) return
        windowLayout.syncWindowAttributes(window, clickThrough = clickThrough, imeFocused = imeFocused)
        chrome.syncUnlockChip(clickThrough, windowLayout.x, windowLayout.y, windowLayout.widthPx, windowLayout.heightPx)
    }

    private fun collapseWindow(window: Window) {
        backdropController.clearBlur(window)
        windowLayout.collapse(window, rootContainer)
    }

    companion object {
        private const val TAG = "SideVeilPinnedWindow"
        private const val IME_OPEN_GRACE_MS = 350L
    }
}
