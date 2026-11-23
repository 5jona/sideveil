package pochita.sideveil.protocol

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class ContractFixturesTest {
    private fun fixturesRoot(): File {
        val candidates =
            listOf(
                File("../../contracts/fixtures"),
                File("../contracts/fixtures"),
                File("contracts/fixtures"),
            )
        return candidates.firstOrNull { it.isDirectory }
            ?: error("Could not locate contracts/fixtures from ${File(".").absolutePath}")
    }

    private fun readFixture(rel: String): String = File(fixturesRoot(), rel).readText(Charsets.UTF_8)

    private fun readInvalidData(rel: String): String {
        val obj = SideVeilWireJson.decodeFromString<JsonObject>(readFixture(rel))
        assertTrue(obj["invalid"]?.jsonPrimitive?.boolean == true)
        return obj["data"].toString()
    }

    private inline fun <reified T> assertRoundTrip(rel: String): T {
        val parsed = SideVeilWireJson.decodeFromString<T>(readFixture(rel))
        val reencoded = SideVeilWireJson.encodeToString(parsed)
        val reparsed = SideVeilWireJson.decodeFromString<T>(reencoded)
        assertEquals("round-trip mismatch for $rel", parsed, reparsed)
        return parsed
    }

    private inline fun <reified T> assertInvalidRejects(rel: String) {
        val invalidJson = readInvalidData(rel)
        try {
            SideVeilWireJson.decodeFromString<T>(invalidJson)
            fail("Expected SerializationException for invalid fixture $rel")
        } catch (_: SerializationException) {
            // Expected schema violation
        }
    }

    @Test
    fun `config golden fixtures match defaults and reject invalid enum`() {
        val defaultCfg = assertRoundTrip<SideVeilConfig>("config/SideVeilConfig.default.json")
        assertEquals(SideVeilConfig.DEFAULT, defaultCfg)
        assertEquals(defaultCfg, defaultCfg.sanitized())

        val withDebug = assertRoundTrip<SideVeilConfig>("config/SideVeilConfig.with-debug.json")
        assertTrue(withDebug.debug.webviewDebuggingEnabled)
        assertEquals(SideVeilThemes.MODERN_SAAS_DARK, withDebug.panel.themeId)

        assertInvalidRejects<SideVeilConfig>("config/SideVeilConfig.invalid-mode.json")
    }

    @Test
    fun `status health and ping golden fixtures round-trip cleanly`() {
        val full = assertRoundTrip<DaemonStatus>("status/DaemonStatus.full.json")
        assertEquals(PanelState.OPEN, full.panelState)
        assertEquals(SideVeilBuiltinDiagnostic.ID, full.activePluginId)
        assertEquals(2L, full.matchedPluginsCount)
        assertNotNull(full.gestureStats)

        val minimal = assertRoundTrip<DaemonStatus>("status/DaemonStatus.minimal-nulls.json")
        assertEquals(PanelState.CLOSED, minimal.panelState)
        assertNull(minimal.hostPid)
        assertNull(minimal.activePluginId)
        assertEquals(0L, minimal.matchedPluginsCount)

        assertInvalidRejects<DaemonStatus>("status/DaemonStatus.invalid-panel-state.json")

        val health = assertRoundTrip<DaemonHealth>("status/DaemonHealth.ok.json")
        assertTrue(health.ok && health.sensorConnected && health.hostConnected)

        val ping = assertRoundTrip<PingResult>("status/PingResult.ok.json")
        assertTrue(ping.pong)
    }

    @Test
    fun `scope panel and host diagnostics golden fixtures round-trip cleanly`() {
        val scope = assertRoundTrip<ScopeChangedPayload>("events/ScopeChangedPayload.active.json")
        assertTrue(scope.active)
        assertEquals(".ui.MainActivity", scope.foregroundActivity)
        assertInvalidRejects<ScopeChangedPayload>("events/ScopeChangedPayload.invalid-missing-active.json")

        val vis = assertRoundTrip<PanelVisibilityPayload>("events/PanelVisibilityPayload.open.json")
        assertTrue(vis.visible)
        assertEquals(PanelState.OPEN, vis.state)
        assertInvalidRejects<PanelVisibilityPayload>("events/PanelVisibilityPayload.invalid-state.json")

        val state = assertRoundTrip<PanelStateChangedPayload>("events/PanelStateChangedPayload.dragging.json")
        assertEquals(PanelState.DRAGGING, state.state)

        val diag = assertRoundTrip<HostPluginDiagnosticsPayload>("events/HostPluginDiagnosticsPayload.full.json")
        assertEquals(2, diag.webviewCrashCount)
        assertEquals(2, diag.warmPluginIds.size)
        assertInvalidRejects<HostPluginDiagnosticsPayload>("events/HostPluginDiagnosticsPayload.invalid-crash-type.json")
    }

    @Test
    fun `theme and hud golden fixtures share identical defaults with Rust`() {
        val palette = assertRoundTrip<DynamicThemePalette>("theme-hud/DynamicThemePalette.dark.json")
        assertEquals(SideVeilThemes.COLOROS_GLASS, palette.themeId)
        assertEquals(palette.seedArgb, palette.glass.accentArgb)
        assertEquals("#38bdf8", palette.cssTokens["--accent"])
        assertInvalidRejects<DynamicThemePalette>("theme-hud/DynamicThemePalette.invalid-missing-glass.json")

        val seed = assertRoundTrip<HostDynamicColorsPayload>("theme-hud/HostDynamicColorsPayload.seed.json")
        assertTrue(seed.isDark)

        val fullText = assertRoundTrip<HudTextOverlay>("theme-hud/HudTextOverlay.full.json")
        assertEquals(SideVeilHudDefaults.DEFAULT_TEXT_COLOR_ARGB, fullText.colorArgb)
        assertEquals(HudTextAlign.START, fullText.align)

        val minimalText = assertRoundTrip<HudTextOverlay>("theme-hud/HudTextOverlay.minimal.json")
        assertEquals(SideVeilHudDefaults.DEFAULT_TEXT_COLOR_ARGB, minimalText.colorArgb)
        assertEquals(SideVeilHudDefaults.DEFAULT_TEXT_SIZE_SP, minimalText.sizeSp)
        assertEquals(HudTextAlign.START, minimalText.align)
        assertFalse(minimalText.bold)
        assertTrue(minimalText.tabularNums)
        assertInvalidRejects<HudTextOverlay>("theme-hud/HudTextOverlay.invalid-align.json")

        val dismiss = assertRoundTrip<HudDismissPayload>("theme-hud/HudDismissPayload.targeted.json")
        assertEquals("example.native-perf-hud", dismiss.pluginId)
    }

    @Test
    fun `generated constants align complete method and event registries`() {
        assertEquals("daemon.health", SideVeilMethods.DAEMON_HEALTH)
        assertEquals("plugins.setScopeOverride", SideVeilMethods.PLUGINS_SET_SCOPE_OVERRIDE)
        assertEquals("fs.writeAtomic", SideVeilMethods.FS_WRITE_ATOMIC)
        assertEquals("panel.visibilityChanged", SideVeilEvents.PANEL_VISIBILITY_CHANGED)
        assertEquals("appearance.changed", SideVeilEvents.APPEARANCE_CHANGED)
        assertEquals("container.presentationChanged", SideVeilEvents.CONTAINER_PRESENTATION_CHANGED)
        assertEquals("container.clickThroughChanged", SideVeilEvents.CONTAINER_CLICK_THROUGH_CHANGED)
    }
}
