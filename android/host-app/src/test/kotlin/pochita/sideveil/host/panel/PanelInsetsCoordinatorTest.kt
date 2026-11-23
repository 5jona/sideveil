package pochita.sideveil.host.panel

import android.view.WindowManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import pochita.sideveil.host.overlay.OverlayWindowFlags
import pochita.sideveil.host.plugin.PluginSizeOverride
import pochita.sideveil.host.plugin.PluginWebViewSettings
import pochita.sideveil.protocol.EdgeSide
import pochita.sideveil.protocol.PanelConfig
import pochita.sideveil.protocol.PluginDescriptor
import pochita.sideveil.protocol.PluginEmbedPrefs

class PanelInsetsCoordinatorTest {
    @Test
    fun compactPortraitPhoneReleasesDrawerWidthToAtLeast307Dp() {
        val density = 3f
        val screenWidthPx = (363 * density).toInt()
        val metrics = PanelInsetsCoordinator.resolveHorizontalMetrics(screenWidthPx, density)
        assertTrue(metrics.compact)
        assertEquals(40, metrics.railWidthDp)
        assertEquals(6, metrics.edgeMarginDp)
        assertEquals(4, metrics.railGapDp)
        assertEquals(44, metrics.railInsetDp)

        val safe =
            PanelSafeBounds(
                screenWidthPx = screenWidthPx,
                screenHeightPx = (800 * density).toInt(),
                safeTopPx = 96,
                safeBottomPx = 72,
                safeLeftPx = 0,
                safeRightPx = 0,
                imeBottomPx = 0,
                isTabletLandscape = false,
            )
        val (widthPx, _) =
            PanelInsetsCoordinator.computePanelDimensions(
                safe = safe,
                density = density,
                edgeSide = EdgeSide.RIGHT,
                panelConfig = PanelConfig(widthDp = 340),
                railInsetDp = metrics.railInsetDp,
                sizeOverride = PluginSizeOverride(),
            )
        assertEquals(307, (widthPx / density).toInt())
    }

    @Test
    fun regularAndTabletViewportsPreserveSpaciousRailAndMargins() {
        val density = 3f
        val regularMetrics = PanelInsetsCoordinator.resolveHorizontalMetrics((412 * density).toInt(), density)
        assertFalse(regularMetrics.compact)
        assertEquals(50, regularMetrics.railWidthDp)
        assertEquals(12, regularMetrics.edgeMarginDp)
        assertEquals(6, regularMetrics.railGapDp)
        assertEquals(56, regularMetrics.railInsetDp)

        val safe =
            PanelSafeBounds(
                screenWidthPx = (412 * density).toInt(),
                screenHeightPx = (915 * density).toInt(),
                safeTopPx = 96,
                safeBottomPx = 72,
                safeLeftPx = 0,
                safeRightPx = 0,
                imeBottomPx = 0,
                isTabletLandscape = false,
            )
        val (widthPx, _) =
            PanelInsetsCoordinator.computePanelDimensions(
                safe = safe,
                density = density,
                edgeSide = EdgeSide.RIGHT,
                panelConfig = PanelConfig(widthDp = 340),
                railInsetDp = regularMetrics.railInsetDp,
                sizeOverride = PluginSizeOverride(),
            )
        assertEquals(332, (widthPx / density).toInt())
    }

    @Test
    fun untouchableOverlayWindowAlphaIsCappedAndRestoredWhenInteractive() {
        val untouchable =
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        val interactive = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL

        assertEquals(0.80f, OverlayWindowFlags.resolveWindowAlpha(untouchable, 1.0f), 0.0001f)
        assertEquals(0.55f, OverlayWindowFlags.resolveWindowAlpha(untouchable, 0.55f), 0.0001f)
        assertEquals(1.0f, OverlayWindowFlags.resolveWindowAlpha(interactive, 1.0f), 0.0001f)
    }

    @Test
    fun embedWideViewportEnablesOverviewModeAndClampsNarrowInitialWidth() {
        val embedPlugin =
            PluginDescriptor(
                id = "lan.homeassistant",
                name = "Home Assistant",
                version = "1.0.0",
                entry = "https://ha.example.com",
                embed = PluginEmbedPrefs(url = "https://ha.example.com", wideViewport = true, textZoom = 100),
            )
        val spec = PluginWebViewSettings.resolveWebSettingsSpec(embedPlugin)
        assertTrue(spec.useWideViewPort)
        assertTrue(spec.loadWithOverviewMode)

        val initW = PluginWebViewSettings.resolveInitialWidthPx(screenWidthPx = 1089, density = 3f, surfaceWidthDp = 340)
        assertEquals(307, (initW / 3f).toInt())
    }

    @Test
    fun resolveScreenImeBottomPxConvertsWindowLocalInsetToScreenHeight() {
        val imeHeight =
            PanelInsetsCoordinator.resolveScreenImeBottomPx(
                imeVisible = true,
                windowLocalImeBottomPx = 564,
                windowBottomOnScreenPx = 2064,
                screenHeightPx = 2400,
                metricsImeBottomPx = 0,
                previousEffectiveImeBottomPx = 0,
            )
        assertEquals(900, imeHeight)
    }

    @Test
    fun resolveScreenImeBottomPxRetainsHeightAfterPanelMovesAboveIme() {
        val stabilized =
            PanelInsetsCoordinator.resolveScreenImeBottomPx(
                imeVisible = true,
                windowLocalImeBottomPx = 0,
                windowBottomOnScreenPx = 1468,
                screenHeightPx = 2400,
                metricsImeBottomPx = 0,
                previousEffectiveImeBottomPx = 900,
            )
        assertEquals(900, stabilized)
    }

    @Test
    fun resolveScreenImeBottomPxUpdatesWhenImeGrowsAndResetsOnDismiss() {
        val grown =
            PanelInsetsCoordinator.resolveScreenImeBottomPx(
                imeVisible = true,
                windowLocalImeBottomPx = 168,
                windowBottomOnScreenPx = 1468,
                screenHeightPx = 2400,
                metricsImeBottomPx = 0,
                previousEffectiveImeBottomPx = 900,
            )
        assertEquals(1100, grown)

        val dismissed =
            PanelInsetsCoordinator.resolveScreenImeBottomPx(
                imeVisible = false,
                windowLocalImeBottomPx = 0,
                windowBottomOnScreenPx = 1268,
                screenHeightPx = 2400,
                metricsImeBottomPx = 0,
                previousEffectiveImeBottomPx = 1100,
            )
        assertEquals(0, dismissed)
    }
}
