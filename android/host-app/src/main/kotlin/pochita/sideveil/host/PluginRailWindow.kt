package pochita.sideveil.host

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.Log
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import pochita.sideveil.protocol.EdgeSide
import pochita.sideveil.protocol.PanelConfig
import pochita.sideveil.protocol.PluginBackendKind
import pochita.sideveil.protocol.PluginBackendState
import pochita.sideveil.protocol.PluginDescriptor

/**
 * Compact native glass Plugin Rail (`46dp` wide) rendered beside the panel on the screen-edge side
 * ONLY when >= 2 plugins match the current foreground app. Completely collapses to `1x1 INVISIBLE`
 * when <= 1 plugin matches or while the sidebar panel is closed.
 */
@SuppressLint("RtlHardcoded")
class PluginRailWindow(
    private val context: Context,
    private val windowManager: WindowManager,
    private val onSelectPlugin: (String) -> Unit,
) {
    private val density: Float
        get() = context.resources.displayMetrics.density
    private val themedContext = ContextThemeWrapper(context, R.style.Theme_Sideveil_OverlayDialog)
    private val backdropDrawable = GlassBackdropDrawable(density)

    private val railContainer = FrameLayout(themedContext)
    private val itemColumn =
        LinearLayout(themedContext).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }

    private var attached = false
    private var visible = false
    private var edgeSide = EdgeSide.RIGHT
    private var panelConfig = PanelConfig()
    private var syncedThemeId = "coloros-glass"
    private var isDark = true
    private var systemBlurSupported = runCatching { windowManager.isCrossWindowBlurEnabled }.getOrDefault(false)
    private var lastAppliedBlurRadius = -1
    private var matchedPlugins: List<PluginDescriptor> = emptyList()
    private var activePluginId: String = ""

    private val dialog = Dialog(themedContext, R.style.Theme_Sideveil_OverlayDialog)

    init {
        val scroll =
            ScrollView(themedContext).apply {
                isVerticalScrollBarEnabled = false
                overScrollMode = View.OVER_SCROLL_NEVER
                addView(
                    itemColumn,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ),
                )
            }

        railContainer.background = backdropDrawable
        railContainer.clipToOutline = true
        railContainer.outlineProvider = GlassBackdropDrawable.createContentOutlineProvider { cornerPx() }
        railContainer.setPadding(dp(4f), dp(6f), dp(4f), dp(6f))
        railContainer.addView(
            scroll,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )

        dialog.setCanceledOnTouchOutside(false)
        dialog.setCancelable(false)
        dialog.setContentView(
            railContainer,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )

        val window = dialog.window ?: error("Rail overlay dialog window unavailable")
        window.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        window.setFormat(PixelFormat.TRANSLUCENT)
        window.decorView.setPadding(0, 0, 0, 0)
        window.setBackgroundDrawable(BlurMaskDrawable(cornerPx()))

        val lp = window.attributes
        lp.title = "SideveilPluginRail"
        lp.gravity = Gravity.TOP or Gravity.LEFT
        lp.width = 1
        lp.height = 1
        lp.flags = INVISIBLE_FLAGS
        OverlayWindowFlags.disableMoveAnimation(lp)
        window.attributes = lp
    }

    fun attachWarm() {
        if (attached) return
        runCatching {
            dialog.show()
            attached = true
            collapseHidden()
        }.onFailure { err ->
            Log.e(TAG, "Failed to attach PluginRailWindow", err)
        }
    }

    fun shouldShowRail(): Boolean = matchedPlugins.size >= 2

    fun railInsetDp(): Int = if (shouldShowRail()) RAIL_WIDTH_DP + RAIL_GAP_DP else 0

    fun updatePlugins(
        plugins: List<PluginDescriptor>,
        activeId: String,
    ) {
        matchedPlugins = plugins
        activePluginId = activeId
        rebuildItems()
        refreshVisibility()
    }

    fun updateStyle(
        side: EdgeSide,
        config: PanelConfig,
        themeId: String,
        dark: Boolean,
        blurSupported: Boolean,
    ) {
        edgeSide = side
        panelConfig = config
        syncedThemeId = themeId
        isDark = dark
        systemBlurSupported = blurSupported
        backdropDrawable.updateDensity(density)
        rebuildItems()
        refreshVisibility()
    }

    fun setPanelOpen(open: Boolean) {
        visible = open
        refreshVisibility()
    }

    fun detach() {
        if (attached) {
            runCatching { dialog.dismiss() }
            attached = false
        }
    }

    private fun refreshVisibility() {
        if (!attached) return
        if (!visible || !shouldShowRail()) {
            collapseHidden()
            return
        }
        val window = dialog.window ?: return
        val maxHeight = (context.resources.displayMetrics.heightPixels * 0.65f).toInt().coerceAtLeast(dp(120f))
        val desiredHeight = (dp(12f) + matchedPlugins.size * dp(44f)).coerceAtMost(maxHeight)
        val lp = window.attributes
        val horiz = if (edgeSide == EdgeSide.LEFT) Gravity.LEFT else Gravity.RIGHT
        lp.gravity = Gravity.CENTER_VERTICAL or horiz
        lp.width = dp(RAIL_WIDTH_DP.toFloat())
        lp.height = desiredHeight
        lp.x = dp(EDGE_MARGIN_DP.toFloat())
        lp.y = 0
        lp.flags = VISIBLE_FLAGS
        OverlayWindowFlags.disableMoveAnimation(lp)
        window.attributes = lp
        window.decorView.visibility = View.VISIBLE
        applyBackdrop(window, isVisible = true)
    }

    private fun collapseHidden() {
        val window = dialog.window ?: return
        applyBackdrop(window, isVisible = false)
        window.decorView.visibility = View.INVISIBLE
        val lp = window.attributes
        lp.gravity = Gravity.TOP or Gravity.LEFT
        lp.width = 1
        lp.height = 1
        lp.x = 0
        lp.y = 0
        lp.flags = INVISIBLE_FLAGS
        window.attributes = lp
    }

    private fun isBlurActive(isVisible: Boolean): Boolean {
        val configEnabled = panelConfig.nativeBlurEnabled && panelConfig.blurRadiusPx > 0
        return isVisible && systemBlurSupported && configEnabled
    }

    private fun applyBackdrop(
        window: android.view.Window,
        isVisible: Boolean,
    ) {
        val radius = if (isBlurActive(isVisible)) panelConfig.blurRadiusPx else 0
        backdropDrawable.updateStyle(
            GlassBackdropStyle(
                themeId = syncedThemeId,
                isDark = isDark,
                blurActive = radius > 0,
                cornerRadiusPx = cornerPx(),
            ),
        )
        if (lastAppliedBlurRadius != radius) {
            lastAppliedBlurRadius = radius
            window.setBackgroundBlurRadius(radius)
        }
    }

    private fun rebuildItems() {
        itemColumn.removeAllViews()
        for (plugin in matchedPlugins) {
            val active = plugin.id == activePluginId
            itemColumn.addView(createRailButton(plugin, active))
        }
    }

    private fun createRailButton(
        plugin: PluginDescriptor,
        active: Boolean,
    ): View {
        val slot =
            FrameLayout(themedContext).apply {
                contentDescription = plugin.name
                isClickable = true
                isFocusable = true
                setOnClickListener { onSelectPlugin(plugin.id) }
            }
        val bg =
            GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(12f).toFloat()
                if (active) {
                    setColor(if (isDark) Color.argb(90, 96, 165, 250) else Color.argb(65, 37, 99, 235))
                    setStroke(dp(1.2f), if (isDark) Color.argb(200, 147, 197, 253) else Color.argb(180, 37, 99, 235))
                } else {
                    setColor(if (isDark) Color.argb(30, 255, 255, 255) else Color.argb(24, 15, 23, 42))
                }
            }
        slot.background = bg

        val glyph =
            TextView(themedContext).apply {
                text = badgeLabelFor(plugin)
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(
                    if (isDark) {
                        if (active) Color.WHITE else Color.argb(215, 235, 240, 250)
                    } else {
                        if (active) Color.rgb(15, 23, 42) else Color.argb(200, 30, 41, 59)
                    },
                )
            }
        slot.addView(
            glyph,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )

        if (plugin.backend != PluginBackendKind.NONE) {
            val dot =
                View(themedContext).apply {
                    background =
                        GradientDrawable().apply {
                            shape = GradientDrawable.OVAL
                            val dotColor =
                                if (plugin.backendState == PluginBackendState.ONLINE) {
                                    Color.rgb(34, 197, 94)
                                } else {
                                    Color.rgb(245, 158, 11)
                                }
                            setColor(dotColor)
                        }
                }
            val dotLp =
                FrameLayout.LayoutParams(dp(7f), dp(7f), Gravity.BOTTOM or Gravity.END).apply {
                    rightMargin = dp(3f)
                    bottomMargin = dp(3f)
                }
            slot.addView(dot, dotLp)
        }

        val lp =
            LinearLayout.LayoutParams(dp(36f), dp(36f)).apply {
                topMargin = dp(4f)
                bottomMargin = dp(4f)
            }
        slot.layoutParams = lp
        return slot
    }

    private fun badgeLabelFor(plugin: PluginDescriptor): String {
        val clean = plugin.name.trim()
        if (clean.isEmpty()) return plugin.id.take(2).uppercase()
        val first = clean.codePointAt(0)
        return if (first > 127) {
            String(Character.toChars(first))
        } else {
            clean.take(2).uppercase()
        }
    }

    private fun cornerPx(): Float = dp(20f).toFloat()

    private fun dp(value: Float): Int = (value * density + 0.5f).toInt().coerceAtLeast(1)

    companion object {
        private const val TAG = "SideveilPluginRail"
        const val RAIL_WIDTH_DP = 46
        const val RAIL_GAP_DP = 6
        private const val EDGE_MARGIN_DP = 12

        private const val INVISIBLE_FLAGS =
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED

        private const val VISIBLE_FLAGS =
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
    }
}
