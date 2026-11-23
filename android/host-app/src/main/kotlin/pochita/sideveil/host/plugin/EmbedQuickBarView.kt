package pochita.sideveil.host.plugin

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import pochita.sideveil.host.overlay.OverlayWindowFlags

/**
 * Compact 36dp top utility bar for untrusted WebView embeds (`PluginEmbedPrefs.showToolbar`).
 *
 * Provides one-line title / fallback status display alongside quick actions (`刷新`, `首页`, `浏览器`)
 * so embedded dashboards (such as Home Assistant) remain navigable even when hiding their own
 * header chrome or switching between LAN and remote origins.
 */
class EmbedQuickBarView(
    context: Context,
) : LinearLayout(context) {
    /** Action callbacks wired to the quick bar's buttons. */
    data class Callbacks(
        val onReload: () -> Unit = {},
        val onHome: () -> Unit = {},
        val onOpenExternal: () -> Unit = {},
    )

    private val density: Float
        get() =
            context.resources.displayMetrics.density
                .coerceAtLeast(1f)

    private val titleView = TextView(context)
    private val reloadButton = createActionButton(LABEL_RELOAD)
    private val homeButton = createActionButton(LABEL_HOME)
    private val externalButton = createActionButton(LABEL_EXTERNAL)

    private var boundTitle: String = ""
    private var statusMessage: String? = null
    private var statusIsError: Boolean = false
    private var callbacks: Callbacks = Callbacks()

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val heightPx = dp(BAR_HEIGHT_DP)
        val horizontalPadPx = dp(HORIZONTAL_PADDING_DP)
        val verticalPadPx = dp(4f)
        minimumHeight = heightPx
        setPadding(horizontalPadPx, verticalPadPx, horizontalPadPx, verticalPadPx)
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, heightPx)
        background =
            GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(BAR_BG_COLOR)
            }

        titleView.apply {
            isSingleLine = true
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(TITLE_TEXT_COLOR)
            setPadding(dp(4f), 0, dp(4f), 0)
        }
        addView(
            titleView,
            LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(6f)
            },
        )

        reloadButton.setOnClickListener { callbacks.onReload() }
        homeButton.setOnClickListener { callbacks.onHome() }
        externalButton.setOnClickListener { callbacks.onOpenExternal() }

        addView(reloadButton, createButtonLayoutParams(marginEndDp = 4f))
        addView(homeButton, createButtonLayoutParams(marginEndDp = 4f))
        addView(externalButton, createButtonLayoutParams(marginEndDp = 0f))
    }

    /** Returns the fixed bar height in pixels. */
    fun barHeightPx(): Int = dp(BAR_HEIGHT_DP)

    /** Binds the bar title and button [callbacks]. */
    fun bind(
        title: String,
        callbacks: Callbacks,
    ) {
        this.boundTitle = title
        this.callbacks = callbacks
        refreshLabel()
    }

    /** Binds the bar title and action lambdas. */
    fun bind(
        title: String,
        onReload: () -> Unit,
        onHome: () -> Unit,
        onOpenExternal: () -> Unit,
    ) = bind(
        title = title,
        callbacks =
            Callbacks(
                onReload = onReload,
                onHome = onHome,
                onOpenExternal = onOpenExternal,
            ),
    )

    /** Displays or clears a transient status/error message in place of the title. */
    fun setStatusMessage(
        message: String?,
        isError: Boolean = false,
    ) {
        this.statusMessage = message?.trim()?.takeIf { it.isNotEmpty() }
        this.statusIsError = isError
        refreshLabel()
    }

    private fun refreshLabel() {
        val activeStatus = statusMessage
        if (activeStatus != null) {
            titleView.text = activeStatus
            titleView.setTextColor(if (statusIsError) ERROR_TEXT_COLOR else STATUS_TEXT_COLOR)
        } else {
            titleView.text = boundTitle
            titleView.setTextColor(TITLE_TEXT_COLOR)
        }
    }

    private fun createActionButton(label: String): TextView =
        TextView(context).apply {
            text = label
            contentDescription = label
            gravity = Gravity.CENTER
            isSingleLine = true
            isClickable = true
            isFocusable = true
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(BUTTON_TEXT_COLOR)
            setPadding(dp(8f), dp(3f), dp(8f), dp(3f))
            background = createPillRippleDrawable()
        }

    private fun createButtonLayoutParams(marginEndDp: Float): LayoutParams =
        LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(26f)).apply {
            if (marginEndDp > 0f) {
                marginEnd = dp(marginEndDp)
            }
        }

    private fun createPillRippleDrawable(): RippleDrawable {
        val cornerPx = dp(13f).toFloat()
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
        const val BAR_HEIGHT_DP = 36f
        const val HORIZONTAL_PADDING_DP = 8f

        private const val LABEL_RELOAD = "刷新"
        private const val LABEL_HOME = "首页"
        private const val LABEL_EXTERNAL = "浏览器"

        private val BAR_BG_COLOR = Color.argb(176, 15, 23, 42)
        private val TITLE_TEXT_COLOR = Color.argb(235, 226, 232, 240)
        private val STATUS_TEXT_COLOR = Color.argb(240, 125, 211, 252)
        private val ERROR_TEXT_COLOR = Color.argb(245, 252, 165, 165)
        private val BUTTON_TEXT_COLOR = Color.argb(240, 241, 245, 249)
        private val BUTTON_BG_COLOR = Color.argb(150, 30, 41, 59)
        private val BUTTON_BORDER_COLOR = Color.argb(72, 148, 163, 184)
        private val BUTTON_RIPPLE_COLOR = Color.argb(88, 148, 163, 184)
    }
}
