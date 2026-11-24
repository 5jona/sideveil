package pochita.sideveil.host.panel

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import pochita.sideveil.host.overlay.OverlayWindowFlags
import pochita.sideveil.host.plugin.PluginSurfaceErrorState

/**
 * Lightweight zero-WebView native state view rendered inside [SidebarPanelWindow] for empty
 * matched plugin catalogs, main-frame load failures, or tripped renderer crash circuit breakers.
 */
internal class PluginPanelStateView(
    context: Context,
) : LinearLayout(context) {
    private val density: Float
        get() =
            context.resources.displayMetrics.density
                .coerceAtLeast(1f)

    private val titleView = TextView(context)
    private val pluginLabelView = TextView(context)
    private val detailView = TextView(context)
    private val retryButton = TextView(context)
    private var retryAction: (() -> Unit)? = null

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER
        val padH = dp(24f)
        val padV = dp(28f)
        setPadding(padH, padV, padH, padV)
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        isClickable = true
        isFocusable = true

        titleView.apply {
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(TITLE_COLOR)
        }
        pluginLabelView.apply {
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(PLUGIN_COLOR)
        }
        detailView.apply {
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setLineSpacing(dp(3f).toFloat(), 1f)
            setTextColor(DETAIL_COLOR)
        }
        retryButton.apply {
            text = LABEL_RETRY
            contentDescription = LABEL_RETRY
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(BUTTON_TEXT_COLOR)
            setPadding(dp(20f), dp(9f), dp(20f), dp(9f))
            background = createButtonBackground()
            setOnClickListener { retryAction?.invoke() }
        }

        addView(titleView, wrapLp(bottomMarginDp = 6f))
        addView(pluginLabelView, wrapLp(bottomMarginDp = 8f))
        addView(detailView, wrapLp(bottomMarginDp = 18f))
        addView(retryButton, wrapLp(bottomMarginDp = 0f))
    }

    /** Configures the view as a calm empty-state placeholder when no plugins match the foreground app. */
    fun bindEmptyState() {
        retryAction = null
        setBackgroundColor(Color.TRANSPARENT)
        titleView.text = EMPTY_TITLE
        pluginLabelView.visibility = View.GONE
        detailView.text = EMPTY_SUBTITLE
        retryButton.visibility = View.GONE
    }

    /** Configures the view as an actionable error/crash recovery overlay with a reload button. */
    fun bindErrorState(state: PluginSurfaceErrorState) {
        retryAction = state.onRetry
        setBackgroundColor(ERROR_SCRIM_COLOR)
        titleView.text = state.title
        val pluginDisplay =
            if (state.pluginName.isNotBlank() && state.pluginName != state.pluginId) {
                "${state.pluginName} (${state.pluginId})"
            } else {
                state.pluginId
            }
        pluginLabelView.text = pluginDisplay
        pluginLabelView.visibility = View.VISIBLE
        detailView.text = state.detail
        retryButton.visibility = View.VISIBLE
    }

    private fun wrapLp(bottomMarginDp: Float): LayoutParams =
        LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            if (bottomMarginDp > 0f) {
                bottomMargin = dp(bottomMarginDp)
            }
        }

    private fun createButtonBackground(): RippleDrawable {
        val cornerPx = dp(18f).toFloat()
        val content =
            GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = cornerPx
                setColor(BUTTON_BG_COLOR)
                setStroke(dp(1f), BUTTON_BORDER_COLOR)
            }
        val mask =
            GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = cornerPx
                setColor(Color.WHITE)
            }
        return RippleDrawable(ColorStateList.valueOf(BUTTON_RIPPLE_COLOR), content, mask)
    }

    private fun dp(value: Float): Int = OverlayWindowFlags.dp(density, value)

    companion object {
        private const val EMPTY_TITLE = "当前应用暂无已启用的侧边栏插件"
        private const val EMPTY_SUBTITLE = "可在 SideVeil 控制中心中启用插件或调整生效范围"
        private const val LABEL_RETRY = "重新加载"

        private val ERROR_SCRIM_COLOR = Color.argb(214, 15, 23, 42)
        private val TITLE_COLOR = Color.argb(245, 241, 245, 249)
        private val PLUGIN_COLOR = Color.argb(220, 125, 211, 252)
        private val DETAIL_COLOR = Color.argb(215, 203, 213, 225)
        private val BUTTON_TEXT_COLOR = Color.argb(250, 248, 250, 252)
        private val BUTTON_BG_COLOR = Color.argb(220, 37, 99, 235)
        private val BUTTON_BORDER_COLOR = Color.argb(120, 147, 197, 253)
        private val BUTTON_RIPPLE_COLOR = Color.argb(96, 191, 219, 254)
    }
}
