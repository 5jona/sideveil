package pochita.sideveil.host

import android.content.Context
import android.view.WindowInsets
import android.view.WindowManager
import pochita.sideveil.protocol.EdgeSide
import pochita.sideveil.protocol.PanelConfig
import kotlin.math.roundToInt

/**
 * Snapshot of display bounds, system bars/cutout safe areas, and live IME height.
 */
data class PanelSafeBounds(
    val screenWidthPx: Int,
    val screenHeightPx: Int,
    val safeTopPx: Int,
    val safeBottomPx: Int,
    val safeLeftPx: Int,
    val safeRightPx: Int,
    val imeBottomPx: Int,
    val isTabletLandscape: Boolean,
)

/**
 * Coordinates Android 16–17 `WindowInsets` (status/navigation bars, display cutouts, IME keyboard)
 * and tablet-landscape sizing rules for [PanelWindowLayout].
 */
internal object PanelInsetsCoordinator {
    private const val TABLET_LANDSCAPE_MIN_WIDTH_DP = 720f
    private const val TABLET_MAX_WIDTH_FRACTION = 0.52f
    private const val VERTICAL_MARGIN_DP = 12f
    private const val MIN_PANEL_WIDTH_DP = 240f
    private const val MIN_PANEL_HEIGHT_DP = 240f

    fun resolveBounds(
        context: Context,
        windowManager: WindowManager,
        liveInsets: WindowInsets? = null,
    ): PanelSafeBounds {
        val density =
            context.resources.displayMetrics.density
                .coerceAtLeast(1f)
        val metrics = runCatching { windowManager.currentWindowMetrics }.getOrNull()
        val screenW = metrics?.bounds?.width() ?: context.resources.displayMetrics.widthPixels
        val screenH = metrics?.bounds?.height() ?: context.resources.displayMetrics.heightPixels

        val insetsSource = liveInsets ?: metrics?.windowInsets
        val barsAndCutout =
            insetsSource?.getInsetsIgnoringVisibility(
                WindowInsets.Type.statusBars() or
                    WindowInsets.Type.navigationBars() or
                    WindowInsets.Type.displayCutout(),
            )
        val imeBottom = insetsSource?.getInsets(WindowInsets.Type.ime())?.bottom ?: 0
        val isTabletLandscape = (screenW / density) >= TABLET_LANDSCAPE_MIN_WIDTH_DP && screenW > screenH

        return PanelSafeBounds(
            screenWidthPx = screenW.coerceAtLeast(320),
            screenHeightPx = screenH.coerceAtLeast(480),
            safeTopPx = barsAndCutout?.top ?: 0,
            safeBottomPx = barsAndCutout?.bottom ?: 0,
            safeLeftPx = barsAndCutout?.left ?: 0,
            safeRightPx = barsAndCutout?.right ?: 0,
            imeBottomPx = imeBottom.coerceAtLeast(0),
            isTabletLandscape = isTabletLandscape,
        )
    }

    fun computePanelDimensions(
        safe: PanelSafeBounds,
        density: Float,
        edgeSide: EdgeSide,
        panelConfig: PanelConfig,
        railInsetDp: Int,
        sizeOverride: PluginSizeOverride,
    ): Pair<Int, Int> {
        val edgeCutout = if (edgeSide == EdgeSide.LEFT) safe.safeLeftPx else safe.safeRightPx
        val reservedHorizontal = dp(density, (24 + railInsetDp).toFloat()) + edgeCutout
        val screenLimitW = (safe.screenWidthPx - reservedHorizontal).coerceAtLeast(dp(density, MIN_PANEL_WIDTH_DP))
        val tabletCapW =
            if (safe.isTabletLandscape) {
                (safe.screenWidthPx * TABLET_MAX_WIDTH_FRACTION).roundToInt()
            } else {
                screenLimitW
            }
        val maxW = minOf(screenLimitW, tabletCapW).coerceAtLeast(dp(density, MIN_PANEL_WIDTH_DP))
        val targetWidthDp = (sizeOverride.widthDp ?: panelConfig.widthDp).coerceIn(260, 480)
        val widthPx = dp(density, targetWidthDp.toFloat()).coerceAtMost(maxW)

        val marginPx = dp(density, VERTICAL_MARGIN_DP)
        val bottomObstruction = maxOf(safe.safeBottomPx, safe.imeBottomPx)
        val availableH =
            (safe.screenHeightPx - safe.safeTopPx - bottomObstruction - marginPx * 2)
                .coerceAtLeast(dp(density, MIN_PANEL_HEIGHT_DP))
        val desiredH = resolveDesiredHeightPx(safe.screenHeightPx, density, panelConfig, sizeOverride)
        val heightPx = desiredH.coerceIn(dp(density, MIN_PANEL_HEIGHT_DP), availableH)

        return widthPx to heightPx
    }

    /**
     * Computes vertical center offset (`Gravity.CENTER_VERTICAL` `lp.y`) so the panel remains
     * visually centered inside the unobstructed region between status/cutout top and bottom/IME.
     */
    fun computeSafeCenterOffsetY(
        safe: PanelSafeBounds,
        density: Float,
        panelHeightPx: Int,
    ): Int {
        val marginPx = dp(density, VERTICAL_MARGIN_DP)
        val topBound = safe.safeTopPx + marginPx
        val bottomBound = safe.screenHeightPx - maxOf(safe.safeBottomPx, safe.imeBottomPx) - marginPx
        val screenCenterY = safe.screenHeightPx * 0.5f
        val safeCenterY = ((topBound + bottomBound) * 0.5f)
        val minCenterY = topBound + panelHeightPx * 0.5f
        val maxCenterY = (bottomBound - panelHeightPx * 0.5f).coerceAtLeast(minCenterY)
        val clampedCenterY = safeCenterY.coerceIn(minCenterY, maxCenterY)
        return (clampedCenterY - screenCenterY).roundToInt()
    }

    fun edgeCutoutInsetPx(
        safe: PanelSafeBounds,
        edgeSide: EdgeSide,
    ): Int = if (edgeSide == EdgeSide.LEFT) safe.safeLeftPx else safe.safeRightPx

    private fun resolveDesiredHeightPx(
        screenH: Int,
        density: Float,
        panelConfig: PanelConfig,
        sizeOverride: PluginSizeOverride,
    ): Int {
        val overrideHeightDp = sizeOverride.heightDp
        val overrideHeightPercent = sizeOverride.heightPercent
        return when {
            overrideHeightDp != null -> {
                dp(density, overrideHeightDp.coerceIn(240, 960).toFloat())
            }

            overrideHeightPercent != null -> {
                val pct = overrideHeightPercent.coerceIn(40, 92)
                (screenH * (pct / 100f)).toInt()
            }

            else -> {
                (screenH * (panelConfig.heightPercent / 100f)).toInt()
            }
        }
    }

    private fun dp(
        density: Float,
        value: Float,
    ): Int = (value * density + 0.5f).toInt().coerceAtLeast(1)
}
