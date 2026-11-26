package pochita.sideveil.host.rail

import android.annotation.SuppressLint
import android.content.Context
import android.os.SystemClock
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import pochita.sideveil.host.R
import pochita.sideveil.host.debug.buildOverlayWindowDebugSnapshot
import pochita.sideveil.protocol.DebugOverlayWindowSnapshot
import pochita.sideveil.protocol.EdgeSide
import pochita.sideveil.protocol.GlassTintOverride
import pochita.sideveil.protocol.PanelConfig
import pochita.sideveil.protocol.PluginDescriptor

/**
 * Compact native glass Plugin Rail (`50dp` wide) rendered beside the panel on the screen-edge side
 * ONLY when >= 2 plugins match the current foreground app. Synchronizes entry/exit with
 * the sidebar panel via shared `railProgress`, delegates slot creation to [RailSlotFactory]
 * and window/backdrop layout to [PluginRailSurfaceController], and supports one-stroke blind scrubbing.
 */
@SuppressLint("RtlHardcoded", "ClickableViewAccessibility")
class PluginRailWindow(
    private val context: Context,
    windowManager: WindowManager,
    iconLoader: RailIconLoader,
    private val onSelectPlugin: (String) -> Unit,
    onQuickAction: (String, RailQuickAction) -> Unit = { _, _ -> },
    onTouchActivity: () -> Unit = {},
) {
    private val density: Float
        get() =
            context.resources.displayMetrics.density
                .coerceAtLeast(1f)
    private val themedContext = ContextThemeWrapper(context, R.style.Theme_SideVeil_OverlayDialog)
    private val touchTracker = RailTouchTracker(onTouchActivity)
    private val surfaceController =
        PluginRailSurfaceController(context, windowManager, { density }) {
            refreshVisibility()
        }
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
    private var lastBuiltCompact: Boolean? = null

    private val dialog =
        touchTracker.createDialogAndBindContainer(
            themedContext = themedContext,
            density = density,
            railContainer = railContainer,
            itemColumn = itemColumn,
            surfaceController = surfaceController,
            onTapY = { tapY ->
                if (matchedPlugins.isNotEmpty()) {
                    val idx = surfaceController.resolveTappedSlotIndex(tapY, matchedPlugins.size)
                    onSelectPlugin(matchedPlugins[idx].id)
                }
            },
        )

    /** Returns a diagnostic snapshot of the plugin rail overlay window. */
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

    /** Attaches the warm-invisible `1x1` rail dialog and registers live blur capability tracking. */
    fun attachWarm() {
        if (attached) return
        runCatching {
            dialog.show()
            attached = true
            surfaceController.registerBlurListener()
            collapseHidden()
        }.onFailure { err -> Log.e(TAG, "Failed to attach PluginRailWindow", err) }
    }

    /** Returns `true` when at least 2 plugins match the foreground scope. */
    fun shouldShowRail(): Boolean = matchedPlugins.size >= 2

    /** Returns the horizontal inset in dp occupied by the rail when visible, or `0`. */
    fun railInsetDp(): Int = if (shouldShowRail()) surfaceController.currentHorizontalMetrics().railInsetDp else 0

    /** Returns `true` while a touch on the rail is active or within the post-touch grace window. */
    fun isTouchActiveOrRecent(nowUptimeMs: Long = SystemClock.uptimeMillis()): Boolean = touchTracker.isTouchActiveOrRecent(nowUptimeMs)

    /** Returns `true` if screen coordinates `([rawX], [rawY])` fall within or near the visible rail bounds. */
    fun isScreenPointNearRail(
        rawX: Float,
        rawY: Float,
    ): Boolean {
        if (!attached || !shouldShowRail() || motionProgress <= 0.2f) return false
        return surfaceController.isScreenPointNearRail(railContainer, rawX, rawY)
    }

    /** Updates the list of scope-matched plugins and active plugin selection. */
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

    /** Updates rail edge side, glass theme, blur capability, and dynamic tint override. */
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

    /** Sets the rail motion progress to fully open (`1f`) or closed (`0f`). */
    fun setPanelOpen(open: Boolean) {
        updateMotionProgress(if (open) 1f else 0f)
    }

    /**
     * Synchronizes Rail slide-in/slide-out, opacity, and progressive cross-window blur directly
     * with the sidebar panel's shared normalized motion progress (`0f..1f`).
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
        val idx =
            surfaceController.resolveScrubbedSlotIndex(
                rawX = rawX,
                rawY = rawY,
                progress = progress,
                screenWidthPx = screenWidthPx,
                screenHeightPx = screenHeightPx,
                pluginCount = matchedPlugins.size,
            )
        if (idx == null) {
            if (scrubbedPluginId != null) {
                scrubbedPluginId = null
                rebuildItems()
            }
            return null
        }
        val candidate = matchedPlugins[idx].id
        if (candidate != scrubbedPluginId) {
            scrubbedPluginId = candidate
            rebuildItems()
            return candidate
        }
        return null
    }

    /** Consumes and clears the currently scrubbed plugin ID when the edge gesture releases. */
    fun consumeScrubTarget(): String? {
        val target = scrubbedPluginId
        if (target != null) {
            scrubbedPluginId = null
            rebuildItems()
        }
        return target
    }

    /** Unregisters the blur listener and dismisses the rail dialog window. */
    fun detach() {
        surfaceController.unregisterBlurListener()
        if (attached) {
            runCatching { dialog.dismiss() }
            attached = false
        }
        surfaceController.resetAppliedLayoutSnapshot()
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
        touchTracker.reset()
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

    companion object {
        private const val TAG = "SideVeilPluginRail"
        const val RAIL_WIDTH_DP = 50
        const val RAIL_GAP_DP = 6
        internal const val SLOT_PITCH_DP = 48
    }
}
