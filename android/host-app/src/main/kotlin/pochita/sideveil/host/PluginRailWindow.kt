package pochita.sideveil.host

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
import android.graphics.PixelFormat
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import pochita.sideveil.protocol.EdgeSide
import pochita.sideveil.protocol.PanelConfig
import pochita.sideveil.protocol.PluginDescriptor
import kotlin.math.roundToInt

enum class RailQuickAction {
    PIN_TOP,
    DISABLE,
    RELOAD,
}

/**
 * Compact native glass Plugin Rail (`46dp` wide) rendered beside the panel on the screen-edge side
 * ONLY when >= 2 plugins match the current foreground app. Synchronizes entry/exit with
 * [SidebarPanelWindow] via shared `railProgress`, delegates slot creation to [RailSlotFactory],
 * and supports one-stroke blind scrubbing.
 */
@SuppressLint("RtlHardcoded")
internal class PluginRailWindow(
    private val context: Context,
    private val windowManager: WindowManager,
    iconLoader: RailIconLoader,
    onSelectPlugin: (String) -> Unit,
    onQuickAction: (String, RailQuickAction) -> Unit = { _, _ -> },
) {
    private val density: Float
        get() =
            context.resources.displayMetrics.density
                .coerceAtLeast(1f)
    private val themedContext = ContextThemeWrapper(context, R.style.Theme_Sideveil_OverlayDialog)
    private val backdropDrawable = GlassBackdropDrawable(density)
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
    private var edgeSide = EdgeSide.RIGHT
    private var panelConfig = PanelConfig()
    private var syncedThemeId = "coloros-glass"
    private var isDark = true
    private var systemBlurSupported = runCatching { windowManager.isCrossWindowBlurEnabled }.getOrDefault(false)
    private var lastAppliedBlurRadius = -1
    private var matchedPlugins: List<PluginDescriptor> = emptyList()
    private var activePluginId: String = ""
    private var scrubbedPluginId: String? = null
    private var lastDesiredHeightPx: Int = dp(120f)

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
        if (!shouldShowRail() || progress < SCRUB_MIN_PROGRESS) {
            if (scrubbedPluginId != null) {
                scrubbedPluginId = null
                rebuildItems()
            }
            return null
        }
        val distFromEdge = if (edgeSide == EdgeSide.LEFT) rawX else (screenWidthPx - rawX)
        if (distFromEdge > dp(RAIL_SCRUB_ZONE_DP.toFloat())) {
            if (scrubbedPluginId != null) {
                scrubbedPluginId = null
                rebuildItems()
            }
            return null
        }
        val railTop = (screenHeightPx - lastDesiredHeightPx) * 0.5f + dp(6f)
        val slotPitch = dp(SLOT_PITCH_DP.toFloat()).toFloat().coerceAtLeast(1f)
        val idx = ((rawY - railTop) / slotPitch).toInt().coerceIn(0, matchedPlugins.lastIndex)
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
        val safe = PanelInsetsCoordinator.resolveBounds(context, windowManager)
        val maxHeight = (safe.screenHeightPx * 0.65f).toInt().coerceAtLeast(dp(120f))
        val desiredHeight = (dp(12f) + matchedPlugins.size * dp(SLOT_PITCH_DP.toFloat())).coerceAtMost(maxHeight)
        lastDesiredHeightPx = desiredHeight

        val hiddenX = -dp((RAIL_WIDTH_DP + 16).toFloat())
        val openX = dp(EDGE_MARGIN_DP.toFloat()) + PanelInsetsCoordinator.edgeCutoutInsetPx(safe, edgeSide)
        val currentX = (hiddenX + (openX - hiddenX) * motionProgress).roundToInt()

        val lp = window.attributes
        val horiz = if (edgeSide == EdgeSide.LEFT) Gravity.LEFT else Gravity.RIGHT
        lp.gravity = Gravity.CENTER_VERTICAL or horiz
        lp.width = dp(RAIL_WIDTH_DP.toFloat())
        lp.height = desiredHeight
        lp.x = currentX
        lp.y = 0
        lp.flags = if (motionProgress >= 0.98f) VISIBLE_FLAGS else ANIMATING_FLAGS
        OverlayWindowFlags.disableMoveAnimation(lp)
        window.attributes = lp
        window.decorView.visibility = View.VISIBLE
        railContainer.alpha = motionProgress
        applyBackdrop(window, progress = motionProgress)
    }

    private fun collapseHidden() {
        val window = dialog.window ?: return
        applyBackdrop(window, progress = 0f)
        window.decorView.visibility = View.INVISIBLE
        railContainer.alpha = 0f
        val lp = window.attributes
        lp.gravity = Gravity.TOP or Gravity.LEFT
        lp.width = 1
        lp.height = 1
        lp.x = 0
        lp.y = 0
        lp.flags = INVISIBLE_FLAGS
        window.attributes = lp
    }

    private fun applyBackdrop(
        window: android.view.Window,
        progress: Float,
    ) {
        val configBlur = if (panelConfig.nativeBlurEnabled && systemBlurSupported) panelConfig.blurRadiusPx else 0
        val radius = PanelMotionChoreographer.computeProgressiveBlur(progress, configBlur)
        backdropDrawable.updateStyle(
            GlassBackdropStyle(
                themeId = syncedThemeId,
                isDark = isDark,
                blurActive = radius > 0,
                cornerRadiusPx = cornerPx(),
            ),
        )
        backdropDrawable.updateMotion(progress, 0f)
        if (lastAppliedBlurRadius != radius) {
            lastAppliedBlurRadius = radius
            window.setBackgroundBlurRadius(radius)
        }
    }

    private fun rebuildItems() {
        itemColumn.removeAllViews()
        val highlightedId = scrubbedPluginId ?: activePluginId
        for (plugin in matchedPlugins) {
            val active = plugin.id == highlightedId
            val scrubbed = plugin.id == scrubbedPluginId
            itemColumn.addView(slotFactory.createButton(plugin, active, scrubbed, isDark))
        }
    }

    private fun cornerPx(): Float = dp(20f).toFloat()

    private fun dp(value: Float): Int = (value * density + 0.5f).toInt().coerceAtLeast(1)

    companion object {
        private const val TAG = "SideveilPluginRail"
        const val RAIL_WIDTH_DP = 46
        const val RAIL_GAP_DP = 6
        private const val EDGE_MARGIN_DP = 12
        private const val SLOT_PITCH_DP = 44
        private const val RAIL_SCRUB_ZONE_DP = 82
        private const val SCRUB_MIN_PROGRESS = 0.35f

        private const val INVISIBLE_FLAGS =
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED

        private const val ANIMATING_FLAGS =
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED

        private const val VISIBLE_FLAGS =
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
    }
}
