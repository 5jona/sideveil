package pochita.sideveil.host

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
import android.os.SystemClock
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import pochita.sideveil.protocol.DebugOverlayWindowSnapshot
import pochita.sideveil.protocol.EdgeSide
import pochita.sideveil.protocol.GlassTintOverride
import pochita.sideveil.protocol.PanelConfig
import pochita.sideveil.protocol.PluginDescriptor

/**
 * Compact native glass Plugin Rail (`50dp` wide) rendered beside the panel on the screen-edge side
 * ONLY when >= 2 plugins match the current foreground app. Synchronizes entry/exit with
 * [SidebarPanelWindow] via shared `railProgress`, delegates slot creation to [RailSlotFactory]
 * and window/backdrop layout to [PluginRailSurfaceController], and supports one-stroke blind scrubbing.
 */
@SuppressLint("RtlHardcoded", "ClickableViewAccessibility")
internal class PluginRailWindow(
    private val context: Context,
    windowManager: WindowManager,
    iconLoader: RailIconLoader,
    private val onSelectPlugin: (String) -> Unit,
    onQuickAction: (String, RailQuickAction) -> Unit = { _, _ -> },
    private val onTouchActivity: () -> Unit = {},
) {
    private val density: Float
        get() =
            context.resources.displayMetrics.density
                .coerceAtLeast(1f)
    private val themedContext = ContextThemeWrapper(context, R.style.Theme_SideVeil_OverlayDialog)
    private val surfaceController = PluginRailSurfaceController(context, windowManager) { density }
    private val slotFactory =
        RailSlotFactory(
            themedContext = themedContext,
            densityProvider = { density },
            iconLoader = iconLoader,
            onSelectPlugin = onSelectPlugin,
            onQuickAction = onQuickAction,
        )

    private val railContainer = FrameLayout(themedContext)
    private val itemColumn =
        LinearLayout(themedContext).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }

    private var attached = false
    private var motionProgress = 0f
    private var matchedPlugins: List<PluginDescriptor> = emptyList()
    private var activePluginId: String = ""
    private var scrubbedPluginId: String? = null
    private var touchInProgress: Boolean = false
    private var lastTouchUptimeMs: Long = 0L
    private var lastBuiltCompact: Boolean? = null

    private val dialog =
        object : Dialog(themedContext, R.style.Theme_SideVeil_OverlayDialog) {
            override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
                recordTouchEvent(ev)
                return super.dispatchTouchEvent(ev)
            }
        }

    init {
        val scroll =
            ScrollView(themedContext).apply {
                isVerticalScrollBarEnabled = false
                isVerticalFadingEdgeEnabled = true
                setFadingEdgeLength(dp(FADING_EDGE_LENGTH_DP))
                overScrollMode = View.OVER_SCROLL_NEVER
                addView(itemColumn, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }

        railContainer.background = surfaceController.backdropDrawable
        railContainer.clipToOutline = true
        railContainer.outlineProvider = GlassBackdropDrawable.createContentOutlineProvider { surfaceController.cornerPx() }
        railContainer.setPadding(dp(2f), dp(6f), dp(2f), dp(6f))
        railContainer.isClickable = true
        railContainer.setOnTouchListener { _, ev ->
            if (ev.actionMasked == MotionEvent.ACTION_UP && matchedPlugins.isNotEmpty()) {
                val slotPitch = dp(surfaceController.lastHorizontalMetrics.railSlotPitchDp.toFloat()).toFloat().coerceAtLeast(1f)
                val idx = ((ev.y - dp(6f)) / slotPitch).toInt().coerceIn(0, matchedPlugins.lastIndex)
                onSelectPlugin(matchedPlugins[idx].id)
            }
            true
        }
        railContainer.addView(scroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        dialog.setCanceledOnTouchOutside(false)
        dialog.setCancelable(false)
        dialog.setContentView(
            railContainer,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )

        val window = dialog.window ?: error("Rail overlay dialog window unavailable")
        surfaceController.configureWindow(window)
    }

    fun debugState(): DebugOverlayWindowSnapshot =
        buildOverlayWindowDebugSnapshot(
            name = "rail",
            attached = attached,
            visible = motionProgress > 0.01f && shouldShowRail(),
            window = dialog.window,
            alpha = railContainer.alpha,
            appliedBlurRadiusPx = surfaceController.appliedBlurRadiusPx(),
            clickThrough = false,
            imeFocused = false,
            activePluginId = activePluginId.ifBlank { null },
            agsl = surfaceController.agslDebugState(),
        )

    fun attachWarm() {
        if (attached) return
        runCatching {
            dialog.show()
            attached = true
            collapseHidden()
        }.onFailure { err -> Log.e(TAG, "Failed to attach PluginRailWindow", err) }
    }

    fun shouldShowRail(): Boolean = matchedPlugins.size >= 2

    fun railInsetDp(): Int = if (shouldShowRail()) surfaceController.currentHorizontalMetrics().railInsetDp else 0

    fun isTouchActiveOrRecent(nowUptimeMs: Long = SystemClock.uptimeMillis()): Boolean =
        touchInProgress || (nowUptimeMs - lastTouchUptimeMs) in 0..RAIL_TOUCH_GRACE_MS

    fun isScreenPointNearRail(
        rawX: Float,
        rawY: Float,
    ): Boolean {
        if (!attached || !shouldShowRail() || motionProgress <= 0.2f) return false
        if (rawX <= 1f && rawY <= 1f) return false
        val slop = dp(18f)
        val loc = IntArray(2)
        railContainer.getLocationOnScreen(loc)
        val w = railContainer.width.takeIf { it > 1 } ?: dp(surfaceController.lastHorizontalMetrics.railWidthDp.toFloat())
        val h = railContainer.height.takeIf { it > 1 } ?: surfaceController.lastDesiredHeightPx
        return rawX >= (loc[0] - slop) &&
            rawX <= (loc[0] + w + slop) &&
            rawY >= (loc[1] - slop) &&
            rawY <= (loc[1] + h + slop)
    }

    private fun recordTouchEvent(ev: MotionEvent) {
        lastTouchUptimeMs = SystemClock.uptimeMillis()
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_POINTER_DOWN -> {
                touchInProgress = true
                onTouchActivity()
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                touchInProgress = false
                onTouchActivity()
            }
        }
    }

    fun updatePlugins(
        plugins: List<PluginDescriptor>,
        activeId: String,
    ) {
        matchedPlugins = plugins
        activePluginId = activeId
        if (scrubbedPluginId != null && plugins.none { it.id == scrubbedPluginId }) {
            scrubbedPluginId = null
        }
        rebuildItems()
        refreshVisibility()
    }

    fun updateStyle(
        side: EdgeSide,
        config: PanelConfig,
        themeId: String,
        dark: Boolean,
        blurSupported: Boolean,
        tint: GlassTintOverride? = null,
    ) {
        surfaceController.updateStyle(side, config, themeId, dark, blurSupported, tint)
        rebuildItems()
        refreshVisibility()
    }

    fun setPanelOpen(open: Boolean) {
        updateMotionProgress(if (open) 1f else 0f)
    }

    /**
     * Synchronizes Rail slide-in/slide-out, opacity, and progressive cross-window blur directly
     * with [SidebarPanelWindow]'s shared normalized motion progress (`0f..1f`).
     */
    fun updateMotionProgress(railProgress: Float) {
        val clamped = railProgress.coerceIn(0f, 1f)
        if (clamped == motionProgress && (clamped == 0f || clamped == 1f)) return
        motionProgress = clamped
        refreshVisibility()
    }

    /**
     * One-stroke blind Rail scrubbing during an active edge swipe. Returns the newly highlighted
     * plugin ID whenever the hovered slot changes (so caller can play a subtle tick haptic).
     */
    fun updateScrubHighlight(
        rawX: Float,
        rawY: Float,
        progress: Float,
        screenWidthPx: Int,
        screenHeightPx: Int,
    ): String? {
        val distFromEdge = if (surfaceController.edgeSide == EdgeSide.LEFT) rawX else (screenWidthPx - rawX)
        if (!shouldShowRail() || progress < SCRUB_MIN_PROGRESS || distFromEdge > dp(RAIL_SCRUB_ZONE_DP.toFloat())) {
            if (scrubbedPluginId != null) {
                scrubbedPluginId = null
                rebuildItems()
            }
            return null
        }
        val railTop = (screenHeightPx - surfaceController.lastDesiredHeightPx) * 0.5f + dp(6f)
        val slotPitchPx = dp(surfaceController.lastHorizontalMetrics.railSlotPitchDp.toFloat()).toFloat().coerceAtLeast(1f)
        val idx = ((rawY - railTop) / slotPitchPx).toInt().coerceIn(0, matchedPlugins.lastIndex)
        val candidate = matchedPlugins[idx].id
        if (candidate != scrubbedPluginId) {
            scrubbedPluginId = candidate
            rebuildItems()
            return candidate
        }
        return null
    }

    fun consumeScrubTarget(): String? {
        val target = scrubbedPluginId
        if (target != null) {
            scrubbedPluginId = null
            rebuildItems()
        }
        return target
    }

    fun detach() {
        if (attached) {
            runCatching { dialog.dismiss() }
            attached = false
        }
    }

    private fun refreshVisibility() {
        if (!attached) return
        if (motionProgress <= 0.01f || !shouldShowRail()) {
            collapseHidden()
            return
        }
        val window = dialog.window ?: return
        if (lastBuiltCompact != surfaceController.currentHorizontalMetrics().compact) {
            rebuildItems()
        }
        surfaceController.showAnimated(window, railContainer, motionProgress, matchedPlugins.size)
    }

    private fun collapseHidden() {
        touchInProgress = false
        val window = dialog.window ?: return
        surfaceController.collapseHidden(window, railContainer)
    }

    private fun rebuildItems() {
        val metrics = surfaceController.currentHorizontalMetrics()
        lastBuiltCompact = metrics.compact
        itemColumn.removeAllViews()
        val highlightedId = scrubbedPluginId ?: activePluginId
        for (plugin in matchedPlugins) {
            val active = plugin.id == highlightedId
            val scrubbed = plugin.id == scrubbedPluginId
            itemColumn.addView(slotFactory.createButton(plugin, active, scrubbed, surfaceController.isDark, metrics))
        }
    }

    private fun dp(value: Float): Int = OverlayWindowFlags.dp(density, value)

    companion object {
        private const val TAG = "SideVeilPluginRail"
        const val RAIL_WIDTH_DP = 50
        const val RAIL_GAP_DP = 6
        internal const val SLOT_PITCH_DP = 48
        private const val FADING_EDGE_LENGTH_DP = 18f
        private const val RAIL_SCRUB_ZONE_DP = 86
        private const val SCRUB_MIN_PROGRESS = 0.35f
        private const val RAIL_TOUCH_GRACE_MS = 280L
    }
}
