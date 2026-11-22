package pochita.sideveil.host

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.webkit.WebView
import kotlin.math.roundToInt

data class PinnedBoundsSpec(
    val widthDp: Int? = null,
    val heightDp: Int? = null,
    val xPercent: Float? = null,
    val yPercent: Float? = null,
    val clickThrough: Boolean? = null,
)

/**
 * Geometry, safe-area clamping, overlay [Dialog] setup, window-flag computation, and transient
 * IME focus mechanics for [PinnedContainerWindow].
 */
@SuppressLint("RtlHardcoded")
internal class PinnedWindowLayout(
    private val context: Context,
    private val windowManager: WindowManager,
) {
    private val density: Float
        get() =
            context.resources.displayMetrics.density
                .coerceAtLeast(1f)

    var widthPx: Int = dp(DEFAULT_WIDTH_DP)
        private set
    var heightPx: Int = dp(DEFAULT_HEIGHT_DP)
        private set
    var x: Int = dp(16f)
        private set
    var y: Int = dp(96f)
        private set

    fun createOverlayDialog(
        themedContext: Context,
        contentView: View,
        initialCornerPx: Float,
        isImeFocused: () -> Boolean,
        onReleaseImeFocus: () -> Unit,
        onOutsideTouchWhileFocused: () -> Unit,
    ): Dialog {
        val dialog =
            object : Dialog(themedContext, R.style.Theme_SideVeil_OverlayDialog) {
                override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                    val isBackUp = event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP
                    if (isBackUp && isImeFocused()) {
                        onReleaseImeFocus()
                        return true
                    }
                    return super.dispatchKeyEvent(event)
                }

                override fun onTouchEvent(event: MotionEvent): Boolean {
                    if (event.actionMasked == MotionEvent.ACTION_OUTSIDE && isImeFocused()) {
                        onOutsideTouchWhileFocused()
                        return true
                    }
                    return super.onTouchEvent(event)
                }
            }
        dialog.setCanceledOnTouchOutside(false)
        dialog.setCancelable(false)
        dialog.setContentView(
            contentView,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
        val window = dialog.window ?: error("Pinned overlay dialog window unavailable")
        window.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        window.setFormat(PixelFormat.TRANSLUCENT)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN)
        window.decorView.setPadding(0, 0, 0, 0)
        window.setBackgroundDrawable(BlurMaskDrawable(initialCornerPx))
        collapse(window, contentView)
        return dialog
    }

    fun applyBounds(bounds: PinnedBoundsSpec) {
        val safe = PanelInsetsCoordinator.resolveBounds(context, windowManager)
        bounds.widthDp?.let { widthPx = dp(it.coerceIn(180, 420).toFloat()) }
        bounds.heightDp?.let { heightPx = dp(it.coerceIn(140, 560).toFloat()) }
        bounds.xPercent?.let {
            val pct = it.coerceIn(0f, 100f) / 100f
            x = ((safe.screenWidthPx - widthPx).coerceAtLeast(0) * pct).roundToInt()
        }
        bounds.yPercent?.let {
            val pct = it.coerceIn(0f, 100f) / 100f
            val minY = safe.safeTopPx
            val maxY = (safe.screenHeightPx - safe.safeBottomPx - heightPx).coerceAtLeast(minY)
            y = (minY + (maxY - minY) * pct).roundToInt()
        }
        clampPosition()
    }

    fun setPositionClamped(
        nextX: Int,
        nextY: Int,
    ) {
        x = nextX
        y = nextY
        clampPosition()
    }

    fun clampPosition() {
        val safe = PanelInsetsCoordinator.resolveBounds(context, windowManager)
        val maxX = (safe.screenWidthPx - widthPx).coerceAtLeast(0)
        val minY = safe.safeTopPx
        val maxY = (safe.screenHeightPx - safe.safeBottomPx - heightPx).coerceAtLeast(minY)
        x = x.coerceIn(0, maxX)
        y = y.coerceIn(minY, maxY)
    }

    fun syncWindowAttributes(
        window: Window,
        clickThrough: Boolean,
        imeFocused: Boolean,
    ) {
        val lp = window.attributes
        lp.title = WINDOW_TITLE
        lp.gravity = Gravity.TOP or Gravity.LEFT
        lp.width = widthPx
        lp.height = heightPx
        lp.x = x
        lp.y = y
        lp.flags = computeFlags(clickThrough = clickThrough, imeFocused = imeFocused)
        OverlayWindowFlags.applyPreferredMaxRefreshRate(context, lp, window.decorView.display)
        OverlayWindowFlags.disableMoveAnimation(lp)
        window.attributes = lp
    }

    fun collapse(
        window: Window,
        rootContainer: View,
    ) {
        OverlayWindowFlags.clearHighFrameRate(rootContainer)
        window.decorView.visibility = View.INVISIBLE
        val lp = window.attributes
        lp.title = WINDOW_TITLE
        lp.gravity = Gravity.TOP or Gravity.LEFT
        lp.width = 1
        lp.height = 1
        lp.x = 0
        lp.y = 0
        lp.flags = INVISIBLE_FLAGS
        OverlayWindowFlags.clearPreferredRefreshRate(lp)
        OverlayWindowFlags.disableMoveAnimation(lp)
        window.attributes = lp
    }

    fun showSoftInput(
        webView: WebView,
        stillFocused: () -> Boolean,
    ) {
        val imm = context.getSystemService(InputMethodManager::class.java)
        webView.requestFocus()
        @Suppress("DEPRECATION")
        imm?.showSoftInput(webView, InputMethodManager.SHOW_IMPLICIT)
        // Re-request on the next frame after WindowManager clears FLAG_NOT_FOCUSABLE.
        webView.post {
            if (stillFocused()) {
                webView.requestFocus()
                @Suppress("DEPRECATION")
                imm?.showSoftInput(webView, InputMethodManager.SHOW_IMPLICIT)
            }
        }
    }

    fun hideSoftInput(
        webView: WebView,
        clearFocus: Boolean,
    ) {
        val imm = context.getSystemService(InputMethodManager::class.java)
        imm?.hideSoftInputFromWindow(webView.windowToken, 0)
        if (clearFocus) {
            webView.clearFocus()
        }
    }

    private fun dp(value: Float): Int = OverlayWindowFlags.dp(density, value)

    companion object {
        private const val WINDOW_TITLE = "SideVeilPinnedPlugin"
        private const val DEFAULT_WIDTH_DP = 316f
        private const val DEFAULT_HEIGHT_DP = 348f

        internal const val INVISIBLE_FLAGS =
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED

        internal const val INTERACTIVE_FLAGS =
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED

        internal const val IME_FOCUSED_FLAGS =
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED

        internal const val CLICK_THROUGH_FLAGS =
            INTERACTIVE_FLAGS or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE

        fun computeFlags(
            clickThrough: Boolean,
            imeFocused: Boolean,
        ): Int =
            when {
                clickThrough -> CLICK_THROUGH_FLAGS
                imeFocused -> IME_FOCUSED_FLAGS
                else -> INTERACTIVE_FLAGS
            }
    }
}
