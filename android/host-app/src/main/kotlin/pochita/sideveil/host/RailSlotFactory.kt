package pochita.sideveil.host

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.Menu
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import pochita.sideveil.protocol.PluginBackendKind
import pochita.sideveil.protocol.PluginBackendState
import pochita.sideveil.protocol.PluginDescriptor

/**
 * Constructs individual plugin slot views, async SVG/raster icon overlays, backend status dots,
 * and long-press quick menus for [PluginRailWindow].
 */
@SuppressLint("ClickableViewAccessibility")
internal class RailSlotFactory(
    private val themedContext: Context,
    private val densityProvider: () -> Float,
    private val iconLoader: RailIconLoader,
    private val onSelectPlugin: (String) -> Unit,
    private val onQuickAction: (String, RailQuickAction) -> Unit,
) {
    fun createButton(
        plugin: PluginDescriptor,
        active: Boolean,
        scrubbed: Boolean,
        isDark: Boolean,
    ): View {
        val pill =
            FrameLayout(themedContext).apply {
                background = createSlotBackground(active, scrubbed, isDark)
                isClickable = false
                isFocusable = false
            }

        val glyph =
            TextView(themedContext).apply {
                text = badgeLabelFor(plugin)
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(resolveGlyphColor(active, isDark))
            }
        pill.addView(
            glyph,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )

        if (!plugin.builtin && !plugin.icon.isNullOrBlank()) {
            val iconSizePx = dp(ICON_SIZE_DP.toFloat())
            val iconView =
                ImageView(themedContext).apply {
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    visibility = View.GONE
                }
            pill.addView(iconView, FrameLayout.LayoutParams(iconSizePx, iconSizePx, Gravity.CENTER))
            iconLoader.load(plugin, sizePx = (iconSizePx * 2).coerceAtLeast(64), darkSurface = isDark) { bitmap ->
                if (bitmap != null) {
                    iconView.setImageBitmap(bitmap)
                    iconView.visibility = View.VISIBLE
                    glyph.visibility = View.GONE
                }
            }
        }

        if (plugin.backend != PluginBackendKind.NONE) {
            pill.addView(createBackendDot(plugin.backendState), createBackendDotLayout())
        }

        val slot =
            FrameLayout(themedContext).apply {
                contentDescription = plugin.name
                isClickable = true
                isFocusable = true
                setOnClickListener { onSelectPlugin(plugin.id) }
                setOnLongClickListener {
                    showQuickMenu(pill, plugin)
                    true
                }
                setOnTouchListener { _, ev ->
                    when (ev.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            pill.scaleX = 0.91f
                            pill.scaleY = 0.91f
                        }

                        MotionEvent.ACTION_UP,
                        MotionEvent.ACTION_CANCEL,
                        -> {
                            pill.scaleX = 1f
                            pill.scaleY = 1f
                        }
                    }
                    false
                }
            }
        slot.addView(
            pill,
            FrameLayout.LayoutParams(dp(PILL_SIZE_DP.toFloat()), dp(PILL_SIZE_DP.toFloat()), Gravity.CENTER),
        )
        slot.layoutParams =
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(SLOT_HEIGHT_DP.toFloat()),
            )
        return slot
    }

    private fun createSlotBackground(
        active: Boolean,
        scrubbed: Boolean,
        isDark: Boolean,
    ): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(12f).toFloat()
            when {
                scrubbed -> {
                    setColor(if (isDark) Color.argb(145, 56, 189, 248) else Color.argb(110, 37, 99, 235))
                    setStroke(dp(1.6f), if (isDark) Color.WHITE else Color.rgb(37, 99, 235))
                }

                active -> {
                    setColor(if (isDark) Color.argb(90, 96, 165, 250) else Color.argb(65, 37, 99, 235))
                    setStroke(dp(1.2f), if (isDark) Color.argb(200, 147, 197, 253) else Color.argb(180, 37, 99, 235))
                }

                else -> {
                    setColor(if (isDark) Color.argb(30, 255, 255, 255) else Color.argb(24, 15, 23, 42))
                }
            }
        }

    private fun resolveGlyphColor(
        active: Boolean,
        isDark: Boolean,
    ): Int =
        if (isDark) {
            if (active) Color.WHITE else Color.argb(215, 235, 240, 250)
        } else {
            if (active) Color.rgb(15, 23, 42) else Color.argb(200, 30, 41, 59)
        }

    private fun createBackendDot(state: PluginBackendState): View =
        View(themedContext).apply {
            background =
                GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    val dotColor =
                        if (state == PluginBackendState.ONLINE) {
                            Color.rgb(34, 197, 94)
                        } else {
                            Color.rgb(245, 158, 11)
                        }
                    setColor(dotColor)
                }
        }

    private fun createBackendDotLayout(): FrameLayout.LayoutParams =
        FrameLayout.LayoutParams(dp(7f), dp(7f), Gravity.BOTTOM or Gravity.END).apply {
            rightMargin = dp(3f)
            bottomMargin = dp(3f)
        }

    private fun showQuickMenu(
        anchor: View,
        plugin: PluginDescriptor,
    ) {
        val menu = PopupMenu(themedContext, anchor)
        menu.menu.add(Menu.NONE, MENU_PIN_TOP, 0, "置顶该插件")
        if (!plugin.builtin) {
            menu.menu.add(Menu.NONE, MENU_DISABLE, 1, "停用此插件")
        }
        menu.menu.add(Menu.NONE, MENU_RELOAD, 2, "重新扫描插件")
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_PIN_TOP -> onQuickAction(plugin.id, RailQuickAction.PIN_TOP)
                MENU_DISABLE -> onQuickAction(plugin.id, RailQuickAction.DISABLE)
                MENU_RELOAD -> onQuickAction(plugin.id, RailQuickAction.RELOAD)
                else -> return@setOnMenuItemClickListener false
            }
            true
        }
        runCatching { menu.show() }
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

    private fun dp(value: Float): Int = OverlayWindowFlags.dp(densityProvider(), value)

    companion object {
        private const val ICON_SIZE_DP = 22
        private const val PILL_SIZE_DP = 40
        private const val SLOT_HEIGHT_DP = 48
        private const val MENU_PIN_TOP = 1
        private const val MENU_DISABLE = 2
        private const val MENU_RELOAD = 3
    }
}
