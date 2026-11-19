package pochita.sideveil.shared

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SideveilConfigTest {
    @Test
    fun `default config allows KernelSU manager and denies SystemUI`() {
        val cfg = SideveilConfig.DEFAULT
        assertTrue(cfg.matchesPackage("me.weishu.kernelsu"))
        assertFalse(cfg.matchesPackage("com.android.systemui"))
        assertFalse(cfg.matchesPackage("com.example.unlisted"))
    }

    @Test
    fun `global mode matches unlisted apps except denylist`() {
        val cfg =
            SideveilConfig.DEFAULT.copy(
                scope =
                    ScopeConfig(
                        mode = ScopeMode.GLOBAL,
                        allowlist = emptyList(),
                        denylist = listOf("com.android.systemui"),
                    ),
            )
        assertTrue(cfg.matchesPackage("com.example.app"))
        assertFalse(cfg.matchesPackage("com.android.systemui"))
        assertFalse(cfg.matchesPackage(""))
    }

    @Test
    fun `disabled config rejects all packages`() {
        val cfg = SideveilConfig.DEFAULT.copy(enabled = false)
        assertFalse(cfg.matchesPackage("me.weishu.kernelsu"))
    }

    @Test
    fun `sanitized clamps out of bounds values and normalizes themes`() {
        val dirty =
            SideveilConfig(
                version = 0,
                edge =
                    EdgeConfig(
                        topPercent = 999,
                        heightDp = 10,
                        hotzoneWidthDp = 200,
                        triggerDistanceDp = 2,
                        edgeOffsetDp = 99,
                        handleOpacity = 5.0f,
                    ),
                panel =
                    PanelConfig(
                        widthDp = 900,
                        heightPercent = 5,
                        cornerRadiusDp = 100,
                        themeId = "unknown-theme",
                        colorMode = "invalid",
                        blurRadiusPx = 999,
                        scrimAlpha = 2.0f,
                    ),
                scope =
                    ScopeConfig(
                        allowlist = listOf(" a.b.c ", "", "a.b.c"),
                        denylist = listOf("  "),
                    ),
            ).sanitized()

        assertEquals(1, dirty.version)
        assertEquals(85, dirty.edge.topPercent)
        assertEquals(64, dirty.edge.heightDp)
        assertEquals(48, dirty.edge.hotzoneWidthDp)
        assertEquals(18, dirty.edge.triggerDistanceDp)
        assertEquals(24, dirty.edge.edgeOffsetDp)
        assertEquals(1.0f, dirty.edge.handleOpacity)
        assertEquals(480, dirty.panel.widthDp)
        assertEquals(40, dirty.panel.heightPercent)
        assertEquals(36, dirty.panel.cornerRadiusDp)
        assertEquals("coloros-glass", dirty.panel.themeId)
        assertEquals("system", dirty.panel.colorMode)
        assertEquals(300, dirty.panel.blurRadiusPx)
        assertEquals(0.75f, dirty.panel.scrimAlpha)
        assertEquals(0.0f, SideveilConfig.DEFAULT.panel.scrimAlpha)
        assertEquals(160, SideveilConfig.DEFAULT.panel.blurRadiusPx)
        assertEquals(listOf("a.b.c"), dirty.scope.allowlist)
        assertEquals(emptyList<String>(), dirty.scope.denylist)
    }

    @Test
    fun `json round-trip preserves structure and defaults`() {
        val encoded = SideveilPrettyJson.encodeToString(SideveilConfig.DEFAULT)
        val decoded = SideveilPrettyJson.decodeFromString<SideveilConfig>(encoded)
        assertEquals(SideveilConfig.DEFAULT, decoded)
    }

    @Test
    fun `protocol DTOs serialize and deserialize panel commands, endpoints, and container actions`() {
        val cmdWire = SideveilWireJson.encodeToString(PanelCommandPayload(PanelCommand.OPEN))
        assertTrue(cmdWire.contains("\"command\":\"open\""))
        val cmdDecoded = SideveilWireJson.decodeFromString<PanelCommandPayload>(cmdWire)
        assertEquals(PanelCommand.OPEN, cmdDecoded.command)

        val endpoint = DaemonEndpoint(port = 54321, token = "a".repeat(64))
        val endpointDecoded = SideveilWireJson.decodeFromString<DaemonEndpoint>(SideveilWireJson.encodeToString(endpoint))
        assertEquals(endpoint, endpointDecoded)

        val syncJson =
            """
            {"type":"syncTheme","payload":{"themeId":"modern-saas-dark","colorScheme":"dark","wantsNativeBlur":false,"cornerRadiusPx":48}}
            """.trimIndent()
        val actionDecoded = SideveilWireJson.decodeFromString<ContainerActionPayload>(syncJson)
        assertEquals("syncTheme", actionDecoded.type)
        assertEquals("modern-saas-dark", actionDecoded.payload?.themeId)
        assertEquals(false, actionDecoded.payload?.wantsNativeBlur)
    }
}
