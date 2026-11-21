package pochita.sideveil.host

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import pochita.sideveil.protocol.GlassTintOverride
import pochita.sideveil.protocol.PluginSurfaceBackdrop

/**
 * Owns the single-layer chrome for [PinnedContainerWindow]: a minimal centered bottom drag
 * handle bar (hidden during click-through) and a themed, safe-area-clamped unlock chip window
 * that exits click-through mode cleanly.
 */
@SuppressLint("RtlHardcoded", "ClickableViewAccessibility")
internal class PinnedChromeViews(
    private val context: Context,
    themedContext: Context,
    private val windowManager: WindowManager,
    private val onDragEvent: (MotionEvent) -> Boolean,
    private val onUnlockClickThrough: () -> Unit,
) {
    private val density: Float
        get() =
            context.resources.displayMetrics.density
                .coerceAtLeast(1f)

    private val dragGripPill = View(themedContext)
    private val dragGripBackdrop = FrameLayout(themedContext)
    val bottomHandleBar = FrameLayout(themedContext)

    private val unlockChip = FrameLayout(themedContext)
    private val unlockLabel = TextView(themedContext)
    private val unlockLp =
        WindowManager
            .LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                INTERACTIVE_FLAGS,
                PixelFormat.TRANSLUCENT,
            ).apply {
                title = "SideVeilPinnedUnlockChip"
                gravity = Gravity.TOP or Gravity.LEFT
                OverlayWindowFlags.disableMoveAnimation(this)
            }

    private var chipAttached = false

    init {
        bottomHandleBar.contentDescription = "拖动悬浮窗"
        dragGripBackdrop.addView(dragGripPill, FrameLayout.LayoutParams(dp(38f), dp(4f), Gravity.CENTER))
        bottomHandleBar.addView(dragGripBackdrop, FrameLayout.LayoutParams(dp(64f), dp(14f), Gravity.CENTER))
        bottomHandleBar.setOnTouchListener { v, ev ->
            if (ev.actionMasked == MotionEvent.ACTION_UP) v.performClick()
            onDragEvent(ev)
        }

        unlockLabel.text = "解除穿透"
        unlockLabel.contentDescription = "解除穿透"
        unlockLabel.gravity = Gravity.CENTER
        unlockLabel.textSize = 11f
        unlockLabel.setPadding(dp(12f), dp(5f), dp(12f), dp(5f))
        unlockChip.addView(
            unlockLabel,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        unlockChip.setOnClickListener { onUnlockClickThrough() }
    }

    fun handleBarHeightPx(): Int = dp(22f)

    fun setHandleVisible(visible: Boolean) {
        bottomHandleBar.visibility = if (visible) View.VISIBLE else View.GONE
    }

    fun updateStyle(
        isDark: Boolean,
        backdrop: PluginSurfaceBackdrop,
        dynamicTint: GlassTintOverride?,
    ) {
        val accentColor = dynamicTint?.accentArgb?.toInt() ?: Color.rgb(56, 189, 248)
        dragGripPill.background =
            GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(2f).toFloat()
                setColor(if (isDark) Color.argb(175, 226, 232, 240) else Color.argb(165, 30, 41, 59))
            }
        dragGripBackdrop.background =
            if (backdrop == PluginSurfaceBackdrop.TRANSPARENT) {
                GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(7f).toFloat()
                    setColor(if (isDark) Color.argb(150, 15, 23, 42) else Color.argb(185, 248, 250, 252))
                    setStroke(dp(1f), if (isDark) Color.argb(56, 148, 163, 184) else Color.argb(50, 15, 23, 42))
                }
            } else {
                null
            }
        unlockChip.background =
            GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(14f).toFloat()
                setColor(if (isDark) Color.argb(232, 15, 23, 42) else Color.argb(238, 248, 250, 252))
                setStroke(dp(1.2f), accentColor)
            }
        unlockLabel.setTextColor(if (isDark) Color.WHITE else Color.rgb(15, 23, 42))
    }

    fun syncUnlockChip(
        show: Boolean,
        anchorX: Int,
        anchorY: Int,
        anchorWidthPx: Int,
        anchorHeightPx: Int,
    ) {
        if (!show) {
            detachUnlockChip()
            return
        }
        val safe = PanelInsetsCoordinator.resolveBounds(context, windowManager)
        val unspec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        unlockChip.measure(unspec, unspec)
        val chipW = unlockChip.measuredWidth.coerceAtLeast(dp(64f))
        val chipH = unlockChip.measuredHeight.coerceAtLeast(dp(28f))
        val minX = safe.safeLeftPx + dp(8f)
        val maxX = (safe.screenWidthPx - safe.safeRightPx - chipW - dp(8f)).coerceAtLeast(minX)
        val minY = safe.safeTopPx
        val maxY = (safe.screenHeightPx - safe.safeBottomPx - chipH).coerceAtLeast(minY)
        unlockLp.x = (anchorX + (anchorWidthPx - chipW) / 2).coerceIn(minX, maxX)
        unlockLp.y = (anchorY + anchorHeightPx - chipH - dp(2f)).coerceIn(minY, maxY)
        if (!chipAttached) {
            runCatching {
                windowManager.addView(unlockChip, unlockLp)
                chipAttached = true
            }
        } else {
            runCatching { windowManager.updateViewLayout(unlockChip, unlockLp) }
        }
    }

    fun detachUnlockChip() {
        if (chipAttached) {
            runCatching { windowManager.removeViewImmediate(unlockChip) }
            chipAttached = false
        }
    }

    private fun dp(value: Float): Int = OverlayWindowFlags.dp(density, value)

    companion object {
        private const val INTERACTIVE_FLAGS =
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
    }
}
