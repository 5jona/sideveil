package pochita.sideveil.host.plugin

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import pochita.sideveil.host.service.HostEventCallbacks
import pochita.sideveil.host.service.HostEventDispatcher
import pochita.sideveil.host.service.HostGestureEventCallbacks
import pochita.sideveil.host.service.HostOverlayEventCallbacks
import pochita.sideveil.protocol.ConfigUpdatedPayload
import pochita.sideveil.protocol.DebugConfig
import pochita.sideveil.protocol.DynamicThemePalette
import pochita.sideveil.protocol.EdgeRepositionPayload
import pochita.sideveil.protocol.GestureDragEndPayload
import pochita.sideveil.protocol.GestureDragMovePayload
import pochita.sideveil.protocol.GestureDragStartPayload
import pochita.sideveil.protocol.GestureFullscreenHintPayload
import pochita.sideveil.protocol.HostEventEnvelope
import pochita.sideveil.protocol.HudDismissPayload
import pochita.sideveil.protocol.NativeHudSceneHeader
import pochita.sideveil.protocol.PanelCommand
import pochita.sideveil.protocol.PanelConfig
import pochita.sideveil.protocol.PluginBackendStatePayload
import pochita.sideveil.protocol.PluginCapability
import pochita.sideveil.protocol.PluginDescriptor
import pochita.sideveil.protocol.PluginEmbedPrefs
import pochita.sideveil.protocol.PluginOrigin
import pochita.sideveil.protocol.PluginScopedEventPayload
import pochita.sideveil.protocol.PluginsSnapshotPayload
import pochita.sideveil.protocol.ScopeChangedPayload
import pochita.sideveil.protocol.ScopeConfig
import pochita.sideveil.protocol.SideVeilConfig
import pochita.sideveil.protocol.SideVeilEvents
import pochita.sideveil.protocol.SideVeilThemes
import pochita.sideveil.protocol.SideVeilWireJson

class PluginEventSecurityPolicyTest {
    @Test
    fun `restricts plugin busMessage delivery strictly to trusted plugins granted INTER_PLUGIN_BUS`() {
        val granted =
            PluginDescriptor(
                id = "com.example.bus",
                name = "Bus",
                version = "1.0.0",
                capabilities = listOf(PluginCapability.INTER_PLUGIN_BUS),
                grantedCapabilities = listOf(PluginCapability.INTER_PLUGIN_BUS),
            )
        val requestedOnly = granted.copy(grantedCapabilities = emptyList())
        val unrelatedCap =
            granted.copy(
                capabilities = listOf(PluginCapability.STORAGE),
                grantedCapabilities = listOf(PluginCapability.STORAGE),
            )
        val untrustedEmbed =
            granted.copy(
                id = "com.example.embed",
                embed = PluginEmbedPrefs(url = "https://example.com"),
            )

        assertTrue(PluginEventSecurityPolicy.shouldDeliverBusMessage(granted))
        assertFalse(PluginEventSecurityPolicy.shouldDeliverBusMessage(requestedOnly))
        assertFalse(PluginEventSecurityPolicy.shouldDeliverBusMessage(unrelatedCap))
        assertFalse(PluginEventSecurityPolicy.shouldDeliverBusMessage(untrustedEmbed))
    }

    @Test
    fun `HostEventDispatcher routes plugin busMessage explicitly and never falls back to global broadcast`() {
        val busEnvelopes = mutableListOf<HostEventEnvelope>()
        val fallbackEnvelopes = mutableListOf<HostEventEnvelope>()
        val dispatcher =
            HostEventDispatcher(
                callbacks =
                    object : HostEventCallbacks {
                        override fun onConfigUpdated(
                            decoded: ConfigUpdatedPayload,
                            rawEnvelope: HostEventEnvelope,
                        ) = Unit

                        override fun onScopeChanged(
                            decoded: ScopeChangedPayload,
                            rawEnvelope: HostEventEnvelope,
                        ) = Unit

                        override fun onPluginsUpdated(decoded: PluginsSnapshotPayload) = Unit

                        override fun onPluginActiveChanged(pluginId: String) = Unit

                        override fun onPluginBackendState(decoded: PluginBackendStatePayload) = Unit

                        override fun onPluginScopedEvent(decoded: PluginScopedEventPayload) = Unit

                        override fun onPluginBusMessage(rawEnvelope: HostEventEnvelope) {
                            busEnvelopes += rawEnvelope
                        }

                        override fun onNetworkContextChanged(rawEnvelope: HostEventEnvelope) = Unit

                        override fun onPanelCommand(command: PanelCommand) = Unit

                        override fun onReloadPluginWebView(pluginId: String) = Unit

                        override fun onFallbackGlobalEvent(envelope: HostEventEnvelope) {
                            fallbackEnvelopes += envelope
                        }
                    },
                gestureCallbacks =
                    object : HostGestureEventCallbacks {
                        override fun onGestureStart(decoded: GestureDragStartPayload) = Unit

                        override fun onGestureMove(decoded: GestureDragMovePayload) = Unit

                        override fun onGestureEnd(decoded: GestureDragEndPayload) = Unit

                        override fun onFullscreenHint(decoded: GestureFullscreenHintPayload) = Unit

                        override fun onEdgeReposition(decoded: EdgeRepositionPayload) = Unit
                    },
                overlayCallbacks =
                    object : HostOverlayEventCallbacks {
                        override fun onThemePaletteUpdated(
                            decoded: DynamicThemePalette,
                            rawEnvelope: HostEventEnvelope,
                        ) = Unit

                        override fun onHudSceneUpdated(
                            rawSceneJson: String,
                            header: NativeHudSceneHeader,
                        ) = Unit

                        override fun onHudDismissed(decoded: HudDismissPayload) = Unit
                    },
                onDecodeFailure = { _, _ -> },
            )

        val busEvent =
            HostEventEnvelope(
                event = SideVeilEvents.PLUGIN_BUS_MESSAGE,
                payload = buildJsonObject { put("topic", JsonPrimitive("secret.topic")) },
            )
        dispatcher.dispatch(busEvent)

        assertEquals(listOf(busEvent), busEnvelopes)
        assertTrue(fallbackEnvelopes.isEmpty())
    }

    @Test
    fun `sanitizes config updated for non-builtin plugins while preserving full config for builtin plugins`() {
        val decoded =
            ConfigUpdatedPayload(
                config =
                    SideVeilConfig(
                        scope =
                            ScopeConfig(
                                allowlist = listOf("com.bank.secret", "com.work.vault"),
                                denylist = listOf("com.hidden.app"),
                            ),
                        panel =
                            PanelConfig(
                                themeId = SideVeilThemes.MODERN_SAAS_DARK,
                                colorMode = "dark",
                                nativeBlurEnabled = false,
                            ),
                        debug = DebugConfig(webviewDebuggingEnabled = true),
                    ),
                scopeActive = true,
                foregroundPackage = "com.bank.secret",
            )
        val fullEnvelope =
            HostEventEnvelope(
                event = SideVeilEvents.CONFIG_UPDATED,
                payload = SideVeilWireJson.encodeToJsonElement(ConfigUpdatedPayload.serializer(), decoded),
            )
        val sanitized = PluginEventSecurityPolicy.sanitizeConfigUpdatedEnvelope(decoded)

        assertEquals(SideVeilEvents.CONFIG_UPDATED, sanitized.event)
        val payloadObj = sanitized.payload?.jsonObject
        assertNotNull(payloadObj)
        assertNull(payloadObj!!["scopeActive"])
        assertNull(payloadObj["foregroundPackage"])
        val configObj = payloadObj["config"]!!.jsonObject
        assertNull(configObj["scope"])
        assertNull(configObj["debug"])
        assertNull(configObj["edge"])
        val panelObj = configObj["panel"]!!.jsonObject
        assertEquals(SideVeilThemes.MODERN_SAAS_DARK, panelObj["themeId"]?.jsonPrimitive?.content)
        assertEquals("dark", panelObj["colorMode"]?.jsonPrimitive?.content)
        assertEquals("false", panelObj["nativeBlurEnabled"]?.jsonPrimitive?.content)

        val rawSanitizedJson = SideVeilWireJson.encodeToString(HostEventEnvelope.serializer(), sanitized)
        assertFalse(rawSanitizedJson.contains("com.bank.secret"))
        assertFalse(rawSanitizedJson.contains("com.hidden.app"))
        assertFalse(rawSanitizedJson.contains("allowlist"))
        assertFalse(rawSanitizedJson.contains("denylist"))
        assertFalse(rawSanitizedJson.contains("webviewDebuggingEnabled"))

        val builtin = PluginDescriptor.builtinDiagnostic()
        val installed =
            PluginDescriptor(
                id = "com.thirdparty.widget",
                name = "Widget",
                version = "1.0.0",
                origin = PluginOrigin.INSTALLED,
            )
        val bundled = installed.copy(origin = PluginOrigin.BUNDLED)
        val embed =
            builtin.copy(
                id = "com.example.embed",
                origin = PluginOrigin.WEB_EMBED,
                embed = PluginEmbedPrefs(url = "https://example.com"),
            )

        assertSame(fullEnvelope, PluginEventSecurityPolicy.selectConfigEnvelope(builtin, fullEnvelope, sanitized))
        assertSame(sanitized, PluginEventSecurityPolicy.selectConfigEnvelope(installed, fullEnvelope, sanitized))
        assertSame(sanitized, PluginEventSecurityPolicy.selectConfigEnvelope(bundled, fullEnvelope, sanitized))
        assertFalse(PluginEventSecurityPolicy.canReceiveFullConfig(embed))
    }
}
