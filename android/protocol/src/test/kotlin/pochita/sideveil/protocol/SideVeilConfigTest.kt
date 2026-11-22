package pochita.sideveil.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SideVeilConfigTest {
    @Test
    fun `default config allows KernelSU manager and denies SystemUI`() {
        val cfg = SideVeilConfig.DEFAULT
        assertTrue(cfg.matchesPackage("me.weishu.kernelsu"))
        assertFalse(cfg.matchesPackage("com.android.systemui"))
        assertFalse(cfg.matchesPackage("com.example.unlisted"))
    }

    @Test
    fun `global mode matches unlisted apps except denylist`() {
        val cfg =
            SideVeilConfig.DEFAULT.copy(
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
        val cfg = SideVeilConfig.DEFAULT.copy(enabled = false)
        assertFalse(cfg.matchesPackage("me.weishu.kernelsu"))
    }

    @Test
    fun `sanitized clamps out of bounds values and normalizes themes`() {
        val dirty =
            SideVeilConfig(
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
        assertEquals(0.0f, SideVeilConfig.DEFAULT.panel.scrimAlpha)
        assertEquals(160, SideVeilConfig.DEFAULT.panel.blurRadiusPx)
        assertEquals(listOf("a.b.c"), dirty.scope.allowlist)
        assertEquals(emptyList<String>(), dirty.scope.denylist)
    }

    @Test
    fun `json round-trip preserves structure and defaults`() {
        val encoded = SideVeilPrettyJson.encodeToString(SideVeilConfig.DEFAULT)
        val decoded = SideVeilPrettyJson.decodeFromString<SideVeilConfig>(encoded)
        assertEquals(SideVeilConfig.DEFAULT, decoded)
        assertFalse(decoded.debug.webviewDebuggingEnabled)
    }

    @Test
    fun `debug config round-trips and legacy config JSON defaults webviewDebuggingEnabled to false`() {
        val enabledCfg = SideVeilConfig.DEFAULT.copy(debug = DebugConfig(webviewDebuggingEnabled = true))
        val sanitized = enabledCfg.sanitized()
        assertTrue(sanitized.debug.webviewDebuggingEnabled)

        val wire = SideVeilWireJson.encodeToString(sanitized)
        assertTrue(wire.contains("\"webviewDebuggingEnabled\":true"))
        val decoded = SideVeilWireJson.decodeFromString<SideVeilConfig>(wire)
        assertEquals(sanitized, decoded)

        val legacyJson =
            """
            {
              "version": 1,
              "enabled": true,
              "edge": { "side": "left", "topPercent": 40 },
              "panel": { "widthDp": 360, "themeId": "coloros-glass" },
              "scope": { "mode": "allowlist", "allowlist": ["me.weishu.kernelsu"] }
            }
            """.trimIndent()
        val legacyDecoded = SideVeilPrettyJson.decodeFromString<SideVeilConfig>(legacyJson)
        assertFalse(legacyDecoded.debug.webviewDebuggingEnabled)
        assertEquals(EdgeSide.LEFT, legacyDecoded.edge.side)
    }

    @Test
    fun `protocol DTOs serialize and deserialize panel commands, endpoints, and container actions`() {
        val cmdWire = SideVeilWireJson.encodeToString(PanelCommandPayload(PanelCommand.OPEN))
        assertTrue(cmdWire.contains("\"command\":\"open\""))
        val cmdDecoded = SideVeilWireJson.decodeFromString<PanelCommandPayload>(cmdWire)
        assertEquals(PanelCommand.OPEN, cmdDecoded.command)

        val endpoint = DaemonEndpoint(port = 54321, token = "a".repeat(64))
        val endpointDecoded = SideVeilWireJson.decodeFromString<DaemonEndpoint>(SideVeilWireJson.encodeToString(endpoint))
        assertEquals(endpoint, endpointDecoded)

        val minimalSyncJson =
            """
            {"type":"syncTheme","payload":{"themeId":"coloros-glass","colorScheme":"light"}}
            """.trimIndent()
        val minimalActionDecoded = SideVeilWireJson.decodeFromString<ContainerActionPayload>(minimalSyncJson)
        assertEquals("syncTheme", minimalActionDecoded.type)
        assertEquals("coloros-glass", minimalActionDecoded.payload?.themeId)
        assertEquals("light", minimalActionDecoded.payload?.colorScheme)
        assertNull(minimalActionDecoded.payload?.wantsNativeBlur)
        assertNull(minimalActionDecoded.payload?.cornerRadiusPx)

        val fullSyncJson =
            """
            {"type":"syncTheme","payload":{"themeId":"modern-saas-dark","colorScheme":"dark","wantsNativeBlur":false,"cornerRadiusPx":48}}
            """.trimIndent()
        val fullActionDecoded = SideVeilWireJson.decodeFromString<ContainerActionPayload>(fullSyncJson)
        assertEquals("syncTheme", fullActionDecoded.type)
        assertEquals("modern-saas-dark", fullActionDecoded.payload?.themeId)
        assertEquals("dark", fullActionDecoded.payload?.colorScheme)
        assertEquals(false, fullActionDecoded.payload?.wantsNativeBlur)
        assertEquals(48, fullActionDecoded.payload?.cornerRadiusPx)
    }
}
