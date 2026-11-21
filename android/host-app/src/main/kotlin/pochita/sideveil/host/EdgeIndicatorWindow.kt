package pochita.sideveil.host

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
import androidx.core.graphics.toColorInt
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
                title = "SideveilEdgeIndicator"
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

        rootView.setOnApplyWindowInsetsListener { _, insets ->
            val hidden = !insets.isVisible(WindowInsets.Type.statusBars())
            if (hidden != fullscreenBarsHidden) {
                fullscreenBarsHidden = hidden
                onFullscreenChanged(hidden)
                applyAppearance()
            }
            insets
        }
    }

    fun isFullscreenActive(): Boolean = fullscreenBarsHidden

    fun attach() {
        if (attached) return
        runCatching {
            windowManager.addView(rootView, layoutParams)
            attached = true
            applyAppearance()
        }.onFailure { err ->
            Log.w(TAG, "Failed to attach edge indicator overlay", err)
        }
    }

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

    fun setDragPulse(progress: Float) {
        val clamped = progress.coerceIn(0f, 1f)
        dragPulseProgress = clamped
        if (!attached) return
        if (clamped > 0f) {
            cancelIdleDim(restoreActive = true)
            pillView.animate().cancel()
            pillView.scaleX = 1f + clamped * 0.45f
            pillView.scaleY = 1f + clamped * 0.08f
        } else {
            pillView
                .animate()
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(140L)
                .start()
            scheduleIdleDim()
        }
    }

    /**
     * Visual cue when the first swipe in fullscreen anti-accidental mode wakes the edge handle.
     */
    fun pulseFullscreenHint() {
        if (!attached) return
        cancelIdleDim(restoreActive = true)
        rootView.animate().cancel()
        rootView.alpha = 1f
        pillView.animate().cancel()
        pillView
            .animate()
            .scaleX(1.65f)
            .scaleY(1.12f)
            .setDuration(140L)
            .withEndAction {
                pillView
                    .animate()
                    .scaleX(1.15f)
                    .scaleY(1.03f)
                    .setDuration(260L)
                    .start()
            }.start()
        scheduleIdleDim(extraDelayMs = FULLSCREEN_HINT_HOLD_MS)
    }

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

    fun setEdgeStatus(status: EdgeStatusPayload?) {
        activeEdgeStatus = if (status == null || status.clear) null else status
        if (activeEdgeStatus != null) cancelIdleDim(restoreActive = true) else scheduleIdleDim()
        applyAppearance()
    }

    fun detach() {
        mainHandler.removeCallbacks(idleDimRunnable)
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

        val activeSide = if (repositionActive) repositionSide else latestEdge.side
        val activeTopPercent = if (repositionActive) repositionTopPercent else latestEdge.topPercent
        val screenHeight =
            runCatching { windowManager.currentWindowMetrics.bounds.height() }
                .getOrDefault(context.resources.displayMetrics.heightPixels)
                .coerceAtLeast(480)
        val heightPx = dp(latestEdge.heightDp.toFloat()).coerceAtMost(screenHeight)
        val topPx = ((activeTopPercent / 100f) * screenHeight).toInt().coerceIn(0, (screenHeight - heightPx).coerceAtLeast(0))

        val badgeText =
            if (repositionActive) {
                null
            } else {
                activeEdgeStatus
                    ?.badgeText
                    ?.trim()
                    ?.take(4)
                    ?.takeIf { it.isNotEmpty() }
            }
        val horiz = if (activeSide == EdgeSide.LEFT) Gravity.LEFT else Gravity.RIGHT
        layoutParams.gravity = Gravity.TOP or horiz
        layoutParams.width =
            dp(
                when {
                    badgeText != null -> 30f
                    repositionActive -> 10f
                    else -> 8f
                },
            )
        layoutParams.height = heightPx
        layoutParams.x = dpOrZero(latestEdge.edgeOffsetDp.toFloat())
        layoutParams.y = topPx

        applyBadgeOrPill(badgeText, horiz, heightPx)
        applyPillColors()
        applyTransformAndAlpha(activeSide)
        runCatching { windowManager.updateViewLayout(rootView, layoutParams) }
    }

    private fun applyBadgeOrPill(
        badgeText: String?,
        gravityHorizontal: Int,
        heightPx: Int,
    ) {
        val pillParams = pillView.layoutParams as FrameLayout.LayoutParams
        pillParams.width = dp(if (repositionActive) 6f else 4f)
        val progressFactor = activeEdgeStatus?.progress?.coerceIn(0.15f, 1f) ?: 1f
        pillParams.height = (heightPx * 0.64f * progressFactor).toInt().coerceAtLeast(dp(36f))
        pillParams.gravity = Gravity.CENTER_VERTICAL or gravityHorizontal
        pillView.layoutParams = pillParams
        if (badgeText != null) {
            badgeView.text = badgeText
            badgeView.visibility = View.VISIBLE
            (badgeView.layoutParams as FrameLayout.LayoutParams).gravity = Gravity.CENTER_VERTICAL or gravityHorizontal
            badgeView.background =
                GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(9f).toFloat()
                    setColor(parseStatusAccent() ?: Color.argb(230, 37, 99, 235))
                }
            pillView.visibility = View.GONE
        } else {
            badgeView.visibility = View.GONE
            pillView.visibility = View.VISIBLE
        }
    }

    private fun applyTransformAndAlpha(activeSide: EdgeSide) {
        val activeAlpha = computeActiveAlpha()
        val dimmed = idleDimmed && !repositionActive
        rootView.alpha = if (dimmed) activeAlpha * IDLE_ALPHA_FACTOR else activeAlpha
        pillView.translationX = if (dimmed) tuckTranslationPx(activeSide) else 0f
        pillView.scaleX = if (repositionActive) 1.25f else 1f + dragPulseProgress * 0.45f
        pillView.scaleY = if (repositionActive) 1.04f else 1f + dragPulseProgress * 0.08f
    }

    private fun applyPillColors() {
        val statusAccent = parseStatusAccent()
        val fillColor =
            when {
                repositionActive -> Color.argb(240, 56, 189, 248)
                statusAccent != null -> statusAccent
                latestDarkSurface -> Color.argb(215, 240, 246, 255)
                else -> Color.argb(190, 24, 34, 48)
            }
        val strokeColor =
            when {
                repositionActive || statusAccent != null -> Color.argb(235, 255, 255, 255)
                latestDarkSurface -> Color.argb(110, 96, 165, 250)
                else -> Color.argb(90, 255, 255, 255)
            }
        pillDrawable.cornerRadius = dp(4f).toFloat()
        pillDrawable.setColor(fillColor)
        pillDrawable.setStroke(dp(1f), strokeColor)
    }

    private fun parseStatusAccent(): Int? {
        val raw = activeEdgeStatus?.accentColor?.trim().orEmpty()
        if (raw.isEmpty()) return null
        return runCatching { raw.toColorInt() }.getOrNull()
    }

    private fun computeActiveAlpha(): Float {
        if (repositionActive) return 1f
        val baseOpacity = latestEdge.handleOpacity.coerceIn(0.1f, 1.0f)
        return if (latestEdge.dimInFullscreen && fullscreenBarsHidden) {
            (baseOpacity * 0.35f).coerceAtLeast(0.15f)
        } else {
            baseOpacity
        }
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

    private fun animateIdleState(dimmed: Boolean) {
        val activeAlpha = computeActiveAlpha()
        val targetAlpha = if (dimmed) (activeAlpha * IDLE_ALPHA_FACTOR).coerceAtLeast(0.12f) else activeAlpha
        val targetTx = if (dimmed) tuckTranslationPx(latestEdge.side) else 0f
        rootView
            .animate()
            .alpha(targetAlpha)
            .setDuration(IDLE_ANIM_MS)
            .start()
        pillView
            .animate()
            .translationX(targetTx)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(IDLE_ANIM_MS)
            .start()
    }

    private fun tuckTranslationPx(side: EdgeSide): Float {
        val shift = 1.6f * currentDensity
        return if (side == EdgeSide.LEFT) -shift else shift
    }

    private fun dp(value: Float): Int = (value * currentDensity + 0.5f).toInt().coerceAtLeast(1)

    private fun dpOrZero(value: Float): Int = (value * currentDensity + 0.5f).toInt().coerceAtLeast(0)

    companion object {
        private const val TAG = "SideveilEdgeIndicator"
        private const val IDLE_ALPHA_FACTOR = 0.38f
        private const val IDLE_ANIM_MS = 280L
        private const val FULLSCREEN_HINT_HOLD_MS = 1_500L
    }
}
