package pochita.sideveil.host.plugin

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import pochita.sideveil.host.service.DaemonClient
import pochita.sideveil.protocol.HostEventEnvelope
import pochita.sideveil.protocol.PanelState
import pochita.sideveil.protocol.PanelVisibilityPayload
import pochita.sideveil.protocol.PluginDescriptor
import pochita.sideveil.protocol.SideVeilEvents
import pochita.sideveil.protocol.SideVeilWireJson

class PluginVisibilityDispatchTest {
    private class RecordingEventTarget(
        override val isUntrustedEmbed: Boolean = false,
    ) : PluginEventTarget {
        override val visibilityTracker: PluginVisibilityTracker = PluginVisibilityTracker()
        val events = mutableListOf<Pair<String, JsonElement?>>()
        var loadCount = 0

        override fun loadEntry(restoreUrl: String?) {
            loadCount += 1
            visibilityTracker.reset()
        }

        override fun pushBackendState() = Unit

        override fun pushEvent(
            event: String,
            payload: JsonElement?,
        ) {
            events += event to payload
        }

        override fun pushEnvelope(envelope: HostEventEnvelope) {
            events += envelope.event to envelope.payload
        }

        fun visibilityPayloads(): List<PanelVisibilityPayload> =
            events
                .filter { it.first == SideVeilEvents.PANEL_VISIBILITY }
                .mapNotNull { (_, payload) ->
                    payload?.let { SideVeilWireJson.decodeFromJsonElement<PanelVisibilityPayload>(it) }
                }
    }

    @Test
    fun `PluginVisibilityTracker deduplicates unchanged payloads while preserving transitions force and reset`() {
        val tracker = PluginVisibilityTracker()

        assertTrue(tracker.shouldDispatch(visible = true, state = PanelState.OPEN))
        assertFalse(tracker.shouldDispatch(visible = true, state = PanelState.OPEN))

        assertTrue(tracker.shouldDispatch(visible = false, state = PanelState.CLOSED))
        assertFalse(tracker.shouldDispatch(visible = false, state = PanelState.CLOSED))

        assertTrue(tracker.shouldDispatch(visible = true, state = PanelState.DRAGGING))
        assertTrue(tracker.shouldDispatch(visible = true, state = PanelState.OPEN))

        assertTrue(tracker.shouldDispatch(visible = true, state = PanelState.OPEN, force = true))
        assertFalse(tracker.shouldDispatch(visible = true, state = PanelState.OPEN))

        tracker.reset()
        assertTrue(tracker.shouldDispatch(visible = true, state = PanelState.OPEN))
        assertFalse(tracker.shouldDispatch(visible = true, state = PanelState.OPEN))
    }

    @Test
    fun `first activation while panel is open emits panel visibility open exactly once`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val daemonClient = DaemonClient(scope, onConnected = {}, onDisconnected = {}, onEvent = {})
        val bridge = PluginWebViewEventBridge(daemonClient, WebViewCrashRegistry())
        val target = RecordingEventTarget()
        val descriptor = PluginDescriptor.builtinDiagnostic()

        try {
            bridge.hydrateNewInstance(
                instance = target,
                descriptor = descriptor,
                panelState = PanelState.OPEN,
                initiallyVisible = true,
            )
            assertFalse(bridge.syncActivatedVisibility(target, PanelState.OPEN, newlyCreated = true))
            assertFalse(bridge.pushVisibility(target, visible = true, state = PanelState.OPEN))

            assertEquals(
                listOf(PanelVisibilityPayload(visible = true, state = PanelState.OPEN)),
                target.visibilityPayloads(),
            )
        } finally {
            daemonClient.close()
            scope.cancel()
        }
    }

    @Test
    fun `warm reactivation state transitions and forced reload emit expected visibility sequence`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val daemonClient = DaemonClient(scope, onConnected = {}, onDisconnected = {}, onEvent = {})
        val bridge = PluginWebViewEventBridge(daemonClient, WebViewCrashRegistry())
        val target = RecordingEventTarget()
        val descriptor = PluginDescriptor.builtinDiagnostic()

        try {
            bridge.hydrateNewInstance(
                instance = target,
                descriptor = descriptor,
                panelState = PanelState.CLOSED,
                initiallyVisible = false,
            )
            assertTrue(bridge.pushVisibility(target, visible = true, state = PanelState.DRAGGING))
            assertTrue(bridge.pushVisibility(target, visible = true, state = PanelState.OPEN))
            assertTrue(bridge.pushVisibility(target, visible = false, state = PanelState.CLOSED))

            // Warm cache hit switching back while open must deliver open visibility once.
            assertTrue(bridge.syncActivatedVisibility(target, PanelState.OPEN, newlyCreated = false))
            assertFalse(bridge.syncActivatedVisibility(target, PanelState.OPEN, newlyCreated = false))

            // Reload resets tracker and force=true delivers visibility cleanly to the fresh page.
            target.loadEntry()
            assertTrue(bridge.pushVisibility(target, visible = true, state = PanelState.OPEN, force = true))

            assertEquals(
                listOf(
                    PanelVisibilityPayload(visible = false, state = PanelState.CLOSED),
                    PanelVisibilityPayload(visible = true, state = PanelState.DRAGGING),
                    PanelVisibilityPayload(visible = true, state = PanelState.OPEN),
                    PanelVisibilityPayload(visible = false, state = PanelState.CLOSED),
                    PanelVisibilityPayload(visible = true, state = PanelState.OPEN),
                    PanelVisibilityPayload(visible = true, state = PanelState.OPEN),
                ),
                target.visibilityPayloads(),
            )
        } finally {
            daemonClient.close()
            scope.cancel()
        }
    }
}
