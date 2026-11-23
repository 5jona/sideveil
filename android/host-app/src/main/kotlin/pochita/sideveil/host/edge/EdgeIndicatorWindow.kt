package pochita.sideveil.host.edge

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import pochita.sideveil.host.debug.buildEdgeWindowDebugSnapshot
import pochita.sideveil.host.overlay.OverlayWindowFlags
import pochita.sideveil.protocol.DebugOverlayWindowSnapshot
import pochita.sideveil.protocol.EdgeConfig
import pochita.sideveil.protocol.EdgeSide
import pochita.sideveil.protocol.EdgeStatusPayload
import pochita.sideveil.protocol.PanelState

/**
 * Click-through `TYPE_APPLICATION_OVERLAY` capsule indicator positioned flush along the
 * configured screen edge trigger zone. Supports ColorOS-style `delayDoDark` idle auto-dim
 * & edge half-tuck, fullscreen double-swipe hint pulsing, and live long-press reposition preview.
 */
@SuppressLint("RtlHardcoded")
class EdgeIndicatorWindow(
    private val context: Context,
    private val windowManager: WindowManager,
    private val onFullscreenChanged: (Boolean) -> Unit = {},
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val currentDensity: Float
        get() =
            context.resources.displayMetrics.density
                .coerceAtLeast(1f)

    private val rootView = FrameLayout(context)
    private val pillView = View(context)
    private val badgeView =
        TextView(context).apply {
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
            typeface = Typeface.DEFAULT_BOLD
            fontFeatureSettings = "tnum"
            setTextColor(Color.WHITE)
            visibility = View.GONE
        }
    private val pillDrawable = GradientDrawable()
    private val appearance = EdgeIndicatorAppearance({ currentDensity }, rootView, pillView, badgeView, pillDrawable)

    private val layoutParams =
        WindowManager
            .LayoutParams(
                dp(8f),
                dp(168f),
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT,
            ).apply {
                title = "SideVeilEdgeIndicator"
                OverlayWindowFlags.applyTouchSafeAlpha(this)
                OverlayWindowFlags.disableMoveAnimation(this)
            }

    private var attached = false
    private var fullscreenBarsHidden = false
    private var idleDimmed = false
    private var dragPulseProgress = 0f
    private var repositionActive = false
    private var repositionSide = EdgeSide.RIGHT
    private var repositionTopPercent = 32
    private var latestEdge = EdgeConfig()
    private var latestScopeActive = false
    private var latestPanelState = PanelState.CLOSED
    private var latestDarkSurface = false
    private var activeEdgeStatus: EdgeStatusPayload? = null

    private val idleDimRunnable =
        Runnable {
            if (attached && !repositionActive && dragPulseProgress == 0f) {
                idleDimmed = true
                animateIdleState(dimmed = true)
            }
        }

    private val windowInsetsListener =
        View.OnApplyWindowInsetsListener { _, insets ->
            val hidden = !insets.isVisible(WindowInsets.Type.statusBars())
            if (hidden != fullscreenBarsHidden) {
                fullscreenBarsHidden = hidden
                onFullscreenChanged(hidden)
                applyAppearance()
            }
            insets
        }

    init {
        pillDrawable.shape = GradientDrawable.RECTANGLE
        pillView.background = pillDrawable

        val pillParams =
            FrameLayout.LayoutParams(dp(4f), dp(120f)).apply {
                gravity = Gravity.CENTER_VERTICAL or Gravity.RIGHT
            }
        rootView.addView(pillView, pillParams)
        rootView.addView(
            badgeView,
            FrameLayout.LayoutParams(dp(26f), dp(18f)).apply {
                gravity = Gravity.CENTER_VERTICAL or Gravity.RIGHT
            },
        )
        rootView.visibility = View.GONE
        rootView.setOnApplyWindowInsetsListener(windowInsetsListener)
    }

    /** Returns `true` when the system status bar is currently hidden (fullscreen mode). */
    fun isFullscreenActive(): Boolean = fullscreenBarsHidden

    /** Returns a diagnostic snapshot of the edge handle overlay window. */
    fun debugState(): DebugOverlayWindowSnapshot = buildEdgeWindowDebugSnapshot(attached, rootView, layoutParams)

    /** Attaches the edge indicator overlay view to [WindowManager]. */
    fun attach() {
        if (attached) return
        rootView.setOnApplyWindowInsetsListener(windowInsetsListener)
        runCatching {
            windowManager.addView(rootView, layoutParams)
            attached = true
            applyAppearance()
        }.onFailure { err ->
            Log.w(TAG, "Failed to attach edge indicator overlay", err)
        }
    }

    /** Updates the edge indicator geometry, visibility, and theme colors. */
    fun update(
        edge: EdgeConfig,
        scopeActive: Boolean,
        panelState: PanelState,
        isDarkSurface: Boolean,
    ) {
        latestEdge = edge
        latestScopeActive = scopeActive
        latestPanelState = panelState
        latestDarkSurface = isDarkSurface
        wakeAndRescheduleIdle()
        applyAppearance()
    }

    /** Updates the drag-progress scale pulse on the edge pill. */
    fun setDragPulse(progress: Float) {
        val clamped = progress.coerceIn(0f, 1f)
        dragPulseProgress = clamped
        if (!attached) return
        if (clamped > 0f) {
            cancelIdleDim(restoreActive = true)
            appearance.animateDragPulse(clamped)
        } else {
            appearance.animateDragPulse(0f)
            scheduleIdleDim()
        }
    }

    /** Visual cue when the first swipe in fullscreen anti-accidental mode wakes the edge handle. */
    fun pulseFullscreenHint() {
        if (!attached) return
        cancelIdleDim(restoreActive = true)
        appearance.animateFullscreenHintPulse()
        scheduleIdleDim(extraDelayMs = FULLSCREEN_HINT_HOLD_MS)
    }

    /** Updates live preview state while the user long-presses to reposition the edge handle. */
    fun setRepositionPreview(
        active: Boolean,
        side: EdgeSide,
        topPercent: Int,
    ) {
        repositionActive = active
        repositionSide = side
        repositionTopPercent = topPercent.coerceIn(5, 85)
        if (active) cancelIdleDim(restoreActive = true) else scheduleIdleDim()
        applyAppearance()
    }

    /** Applies or clears a plugin-driven ambient status badge on the edge handle. */
    fun setEdgeStatus(status: EdgeStatusPayload?) {
        activeEdgeStatus = if (status == null || status.clear) null else status
        if (activeEdgeStatus != null) cancelIdleDim(restoreActive = true) else scheduleIdleDim()
        applyAppearance()
    }

    /** Cancels active animators, clears the insets listener, and removes the overlay view. */
    fun detach() {
        mainHandler.removeCallbacks(idleDimRunnable)
        appearance.cancelAnimations()
        rootView.setOnApplyWindowInsetsListener(null)
        if (!attached) return
        runCatching { windowManager.removeViewImmediate(rootView) }
        attached = false
    }

    private fun applyAppearance() {
        if (!attached) return
        val shouldShow =
            repositionActive ||
                (latestScopeActive && latestEdge.handleVisible && latestPanelState == PanelState.CLOSED)
        rootView.visibility = if (shouldShow) View.VISIBLE else View.GONE
        if (!shouldShow) {
            mainHandler.removeCallbacks(idleDimRunnable)
            return
        }

        val screenHeight =
            runCatching { windowManager.currentWindowMetrics.bounds.height() }
                .getOrDefault(context.resources.displayMetrics.heightPixels)
                .coerceAtLeast(480)
        appearance.applyLayoutAndVisuals(
            layoutParams = layoutParams,
            screenHeightPx = screenHeight,
            edge = latestEdge,
            repositionActive = repositionActive,
            repositionSide = repositionSide,
            repositionTopPercent = repositionTopPercent,
            fullscreenBarsHidden = fullscreenBarsHidden,
            idleDimmed = idleDimmed,
            darkSurface = latestDarkSurface,
            dragPulseProgress = dragPulseProgress,
            activeEdgeStatus = activeEdgeStatus,
        )
        runCatching { windowManager.updateViewLayout(rootView, layoutParams) }
    }

    private fun wakeAndRescheduleIdle() {
        cancelIdleDim(restoreActive = true)
        scheduleIdleDim()
    }

    private fun cancelIdleDim(restoreActive: Boolean) {
        mainHandler.removeCallbacks(idleDimRunnable)
        if (idleDimmed) {
            idleDimmed = false
            if (restoreActive && attached) {
                animateIdleState(dimmed = false)
            }
        }
    }

    private fun scheduleIdleDim(extraDelayMs: Long = 0L) {
        mainHandler.removeCallbacks(idleDimRunnable)
        val delayMs = latestEdge.idleAutoDimMs.toLong()
        if (delayMs <= 0L || repositionActive) return
        mainHandler.postDelayed(idleDimRunnable, delayMs + extraDelayMs)
    }

    private fun animateIdleState(dimmed: Boolean) = appearance.animateIdleState(dimmed, latestEdge, fullscreenBarsHidden, repositionActive)

    private fun dp(value: Float): Int = OverlayWindowFlags.dp(currentDensity, value)

    companion object {
        private const val TAG = "SideVeilEdgeIndicator"
        private const val FULLSCREEN_HINT_HOLD_MS = 1_500L
    }
}
