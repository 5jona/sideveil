package pochita.sideveil.host

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import pochita.sideveil.shared.EdgeConfig
import pochita.sideveil.shared.EdgeSide
import pochita.sideveil.shared.PanelState

/**
 * Click-through TYPE_APPLICATION_OVERLAY capsule indicator positioned flush along the
 * configured screen edge trigger zone. Never intercepts touch events.
 */
@SuppressLint("RtlHardcoded")
class EdgeIndicatorWindow(
    private val context: Context,
    private val windowManager: WindowManager,
) {
    private val currentDensity: Float
        get() = context.resources.displayMetrics.density

    private val rootView = FrameLayout(context)
    private val pillView = View(context)
    private val pillDrawable = GradientDrawable()

    private val layoutParams =
        WindowManager
            .LayoutParams(
                dp(6f),
                dp(168f),
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT,
            ).apply {
                title = "SideveilEdgeIndicator"
                applyNoMoveAnimationFlag(this)
            }

    private var attached = false
    private var fullscreenBarsHidden = false
    private var latestEdge = EdgeConfig()
    private var latestScopeActive = false
    private var latestPanelState = PanelState.CLOSED
    private var latestDarkSurface = false

    init {
        pillDrawable.shape = GradientDrawable.RECTANGLE
        pillView.background = pillDrawable

        val pillParams =
            FrameLayout.LayoutParams(dp(4f), dp(120f)).apply {
                gravity = Gravity.CENTER_VERTICAL or Gravity.RIGHT
            }
        rootView.addView(pillView, pillParams)
        rootView.visibility = View.GONE

        rootView.setOnApplyWindowInsetsListener { _, insets ->
            val hidden = !insets.isVisible(WindowInsets.Type.statusBars())
            if (hidden != fullscreenBarsHidden) {
                fullscreenBarsHidden = hidden
                applyAppearance()
            }
            insets
        }
    }

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
        applyAppearance()
    }

    fun setDragPulse(progress: Float) {
        if (!attached) return
        val clamped = progress.coerceIn(0f, 1f)
        pillView.scaleX = 1f + clamped * 0.45f
        pillView.scaleY = 1f + clamped * 0.08f
    }

    fun detach() {
        if (!attached) return
        runCatching { windowManager.removeViewImmediate(rootView) }
        attached = false
    }

    private fun applyAppearance() {
        if (!attached) return

        val shouldShow =
            latestScopeActive &&
                latestEdge.handleVisible &&
                latestPanelState == PanelState.CLOSED

        rootView.visibility = if (shouldShow) View.VISIBLE else View.GONE
        if (!shouldShow) {
            return
        }

        val screenHeight =
            runCatching { windowManager.currentWindowMetrics.bounds.height() }
                .getOrDefault(context.resources.displayMetrics.heightPixels)
                .coerceAtLeast(480)
        val heightPx = dp(latestEdge.heightDp.toFloat()).coerceAtMost(screenHeight)
        val maxTop = (screenHeight - heightPx).coerceAtLeast(0)
        val topPx = ((latestEdge.topPercent / 100f) * screenHeight).toInt().coerceIn(0, maxTop)

        val gravityHorizontal = if (latestEdge.side == EdgeSide.LEFT) Gravity.LEFT else Gravity.RIGHT
        layoutParams.gravity = Gravity.TOP or gravityHorizontal
        layoutParams.width = dp(6f)
        layoutParams.height = heightPx
        layoutParams.x = dpOrZero(latestEdge.edgeOffsetDp.toFloat())
        layoutParams.y = topPx

        val pillParams = pillView.layoutParams as FrameLayout.LayoutParams
        pillParams.width = dp(4f)
        pillParams.height = (heightPx * 0.64f).toInt().coerceAtLeast(dp(44f))
        pillParams.gravity = Gravity.CENTER_VERTICAL or gravityHorizontal
        pillView.layoutParams = pillParams

        val fillColor =
            if (latestDarkSurface) {
                Color.argb(215, 240, 246, 255)
            } else {
                Color.argb(190, 24, 34, 48)
            }
        val strokeColor =
            if (latestDarkSurface) {
                Color.argb(110, 96, 165, 250)
            } else {
                Color.argb(90, 255, 255, 255)
            }
        pillDrawable.cornerRadius = dp(4f).toFloat()
        pillDrawable.setColor(fillColor)
        pillDrawable.setStroke(dp(1f), strokeColor)

        val baseOpacity = latestEdge.handleOpacity.coerceIn(0.1f, 1.0f)
        val effectiveAlpha =
            if (latestEdge.dimInFullscreen && fullscreenBarsHidden) {
                (baseOpacity * 0.35f).coerceAtLeast(0.15f)
            } else {
                baseOpacity
            }
        rootView.alpha = effectiveAlpha
        pillView.scaleX = 1f
        pillView.scaleY = 1f

        runCatching { windowManager.updateViewLayout(rootView, layoutParams) }
    }

    private fun dp(value: Float): Int = (value * currentDensity + 0.5f).toInt().coerceAtLeast(1)

    private fun dpOrZero(value: Float): Int = (value * currentDensity + 0.5f).toInt().coerceAtLeast(0)

    private fun applyNoMoveAnimationFlag(lp: WindowManager.LayoutParams) {
        runCatching {
            val field = WindowManager.LayoutParams::class.java.getField("privateFlags")
            val current = field.getInt(lp)
            field.setInt(lp, current or PRIVATE_FLAG_NO_MOVE_ANIMATION)
        }
    }

    companion object {
        private const val TAG = "SideveilEdgeIndicator"
        private const val PRIVATE_FLAG_NO_MOVE_ANIMATION = 0x00000040
    }
}
