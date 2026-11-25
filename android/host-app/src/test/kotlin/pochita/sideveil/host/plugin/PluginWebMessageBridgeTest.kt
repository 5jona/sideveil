package pochita.sideveil.host.plugin

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import pochita.sideveil.protocol.PluginBackendKind
import pochita.sideveil.protocol.PluginBackendState
import pochita.sideveil.protocol.PluginDescriptor
import pochita.sideveil.protocol.RpcResponse
import pochita.sideveil.protocol.SideVeilEvents
import pochita.sideveil.protocol.SideVeilWireJson
import java.util.concurrent.atomic.AtomicBoolean

class PluginWebMessageBridgeTest {
    private class NoopLocalActions : LocalRpcPlatformActions {
        override fun showToast(message: String) = Unit

        override fun readClipboardText(): String? = null

        override fun writeClipboardText(text: String) = Unit

        override fun triggerHaptics(kind: String) = Unit
    }

    private class FakeTransport(
        private val isDestroyed: AtomicBoolean,
        private val channelState: PluginBridgeChannelState,
    ) : PluginMessageTransport {
        val deliveries = mutableListOf<Pair<BridgeDeliveryChannel, String>>()
        var portInstalled = false
        var portClosedCount = 0
        var replyProxyActive = false
        private var listenerCallback: ((String?, String, Boolean, () -> Unit) -> Unit)? = null
        private var portCallback: ((String) -> Unit)? = null

        override fun installListener(
            onMessage: (data: String?, sourceOrigin: String, isMainFrame: Boolean, activateReplyProxy: () -> Unit) -> Unit,
        ) {
            listenerCallback = onMessage
        }

        override fun clearReplyProxy() {
            replyProxyActive = false
        }

        override fun installWebMessagePort(
            onPortMessage: (String) -> Unit,
            onReady: (BridgeDeliveryChannel) -> Unit,
        ) {
            if (isDestroyed.get() || channelState.listenerChannelReady) return
            portInstalled = true
            portCallback = onPortMessage
            channelState.onWebMessagePortAttached()
            onReady(BridgeDeliveryChannel.WEB_MESSAGE_PORT)
        }

        override fun deliver(
            rawJson: String,
            channel: BridgeDeliveryChannel,
        ) {
            deliveries += channel to rawJson
        }

        override fun closeActivePort() {
            if (!portInstalled) return
            portInstalled = false
            portClosedCount += 1
            channelState.onWebMessagePortClosed()
        }

        override fun destroy() {
            clearReplyProxy()
            closeActivePort()
            listenerCallback = null
            portCallback = null
        }

        fun sendFromListener(
            rawJson: String,
            origin: String,
        ) {
            listenerCallback?.invoke(rawJson, origin, true) {
                replyProxyActive = true
            }
        }

        fun sendFromPort(rawJson: String) {
            portCallback?.invoke(rawJson)
        }
    }

    private fun testDescriptor(): PluginDescriptor =
        PluginDescriptor(
            id = "strata.manager",
            name = "Strata",
            version = "0.2.0",
            backend = PluginBackendKind.UDS_NATIVE,
            backendState = PluginBackendState.ONLINE,
        )

    private fun eventEnvelope(
        event: String,
        body: String = "{}",
    ): String = """{"event":"$event","payload":$body}"""

    private fun extractEventName(rawJson: String): String? =
        SideVeilWireJson
            .parseToJsonElement(rawJson)
            .jsonObject["event"]
            ?.jsonPrimitive
            ?.content

    private fun newHarness(): Triple<PluginWebMessageBridge, FakeTransport, CoroutineScope> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val origin = "https://strata.manager.plugins.sideveil.local"
        val descriptor = testDescriptor()
        lateinit var transport: FakeTransport
        val localRpc = PluginLocalRpcHandler(actions = NoopLocalActions(), postOnMain = { it.run() }, onWarnLog = {})
        val runtime =
            BridgeRuntimeHooks(
                postOnMain = { it.run() },
                clearMainCallbacks = {},
                onWarnLog = {},
                forwardDaemonRpc = { req -> RpcResponse(id = req.id) },
            )
        val bridge =
            PluginWebMessageBridge(
                scope = scope,
                localRpcHandler = localRpc,
                pluginOrigin = origin,
                descriptorProvider = { descriptor },
                isForegroundVisible = { true },
                onContainerAction = { ContainerActionResult.Ok },
                runtime = runtime,
                transportFactory = { destroyed, state ->
                    FakeTransport(destroyed, state).also { transport = it }
                },
            )
        bridge.installWebMessageListener()
        return Triple(bridge, transport, scope)
    }

    @Test
    fun `replays initial hydration events over REPLY_PROXY when installChannel runs before listener host attach`() {
        val (bridge, transport, scope) = newHarness()
        val origin = "https://strata.manager.plugins.sideveil.local"
        try {
            bridge.resetBeforeLoad()
            val configFrame = eventEnvelope(SideVeilEvents.CONFIG_UPDATED, """{"config":{"panel":{"themeId":"coloros-glass"}}}""")
            val paletteFrame = eventEnvelope(SideVeilEvents.THEME_PALETTE_UPDATED, """{"cssTokens":{"--accent":"oklch(0.6 0.2 250)"}}""")
            val networkFrame = eventEnvelope(SideVeilEvents.NETWORK_CONTEXT_CHANGED, """{"transport":"wifi"}""")
            val backendFrame = eventEnvelope(SideVeilEvents.PLUGIN_BACKEND_STATE, """{"pluginId":"strata.manager","state":"online"}""")
            val visibilityFrame = eventEnvelope(SideVeilEvents.PANEL_VISIBILITY, """{"visible":true,"state":"open"}""")

            // 1. hydrateNewInstance queues initial state frames before any JS channel is ready.
            listOf(configFrame, paletteFrame, networkFrame, backendFrame, visibilityFrame).forEach(bridge::postRawAsync)
            assertTrue(transport.deliveries.isEmpty())

            // 2. Document-start attach sets replyProxy without usesListenerChannel=true.
            transport.sendFromListener("""{"event":"host.attach","protocolVersion":1}""", origin)
            assertEquals(1, transport.deliveries.size)
            assertEquals(BridgeDeliveryChannel.REPLY_PROXY, transport.deliveries[0].first)
            assertEquals(SideVeilEvents.HOST_READY, extractEventName(transport.deliveries[0].second))

            // 3. onPageFinished fires before ES module HostTransport runs, draining to legacy WebMessagePort.
            bridge.onNavigationCommitted("$origin/web/index.html")
            bridge.installChannel()
            assertTrue(transport.portInstalled)

            // Extra state pushed while WebMessagePort is speculatively active must also survive upgrade.
            val lateVisibilityFrame = eventEnvelope(SideVeilEvents.PANEL_VISIBILITY, """{"visible":false,"state":"closed"}""")
            bridge.postRawAsync(lateVisibilityFrame)

            // 4. HostTransport attaches listener channel; port closes and all initial hydration replays via REPLY_PROXY.
            val beforeListenerAttachCount = transport.deliveries.size
            transport.sendFromListener("""{"event":"host.attach","channel":"listener","protocolVersion":1}""", origin)
            assertFalse(transport.portInstalled)
            assertEquals(1, transport.portClosedCount)

            val replyDeliveries =
                transport.deliveries
                    .drop(beforeListenerAttachCount)
                    .filter { it.first == BridgeDeliveryChannel.REPLY_PROXY }
                    .map { it.second }
            assertEquals(7, replyDeliveries.size)
            assertEquals(SideVeilEvents.HOST_READY, extractEventName(replyDeliveries[0]))
            assertEquals(
                listOf(configFrame, paletteFrame, networkFrame, backendFrame, visibilityFrame, lateVisibilityFrame),
                replyDeliveries.drop(1),
            )

            // Subsequent listener attach must not duplicate already-replayed hydration frames.
            val countAfterFirstAttach = transport.deliveries.size
            transport.sendFromListener("""{"event":"host.attach","channel":"listener","protocolVersion":1}""", origin)
            val secondAttachDeliveries = transport.deliveries.drop(countAfterFirstAttach)
            assertEquals(1, secondAttachDeliveries.size)
            assertEquals(SideVeilEvents.HOST_READY, extractEventName(secondAttachDeliveries[0].second))
        } finally {
            bridge.destroy()
            scope.cancel()
        }
    }

    @Test
    fun `delivers initial hydration directly over REPLY_PROXY when listener attach precedes onPageFinished`() {
        val (bridge, transport, scope) = newHarness()
        val origin = "https://strata.manager.plugins.sideveil.local"
        try {
            bridge.resetBeforeLoad()
            val paletteFrame = eventEnvelope(SideVeilEvents.THEME_PALETTE_UPDATED)
            val backendFrame = eventEnvelope(SideVeilEvents.PLUGIN_BACKEND_STATE, """{"pluginId":"strata.manager","state":"online"}""")
            bridge.postRawAsync(paletteFrame)
            bridge.postRawAsync(backendFrame)

            transport.sendFromListener("""{"event":"host.attach","channel":"listener","protocolVersion":1}""", origin)
            bridge.onNavigationCommitted("$origin/web/index.html")
            bridge.installChannel()

            assertFalse(transport.portInstalled)
            assertEquals(3, transport.deliveries.size)
            assertTrue(transport.deliveries.all { it.first == BridgeDeliveryChannel.REPLY_PROXY })
            assertEquals(SideVeilEvents.HOST_READY, extractEventName(transport.deliveries[0].second))
            assertEquals(listOf(paletteFrame, backendFrame), transport.deliveries.drop(1).map { it.second })
        } finally {
            bridge.destroy()
            scope.cancel()
        }
    }

    @Test
    fun `confirms legacy WebMessagePort on inbound traffic and clears speculative replay buffer`() {
        val (bridge, transport, scope) = newHarness()
        val origin = "https://strata.manager.plugins.sideveil.local"
        try {
            bridge.resetBeforeLoad()
            val backendFrame = eventEnvelope(SideVeilEvents.PLUGIN_BACKEND_STATE, """{"pluginId":"strata.manager","state":"online"}""")
            bridge.postRawAsync(backendFrame)

            bridge.onNavigationCommitted("$origin/web/index.html")
            bridge.installChannel()
            assertTrue(transport.portInstalled)
            assertEquals(2, transport.deliveries.size)
            assertTrue(transport.deliveries.all { it.first == BridgeDeliveryChannel.WEB_MESSAGE_PORT })

            // Client confirms legacy WebMessagePort by sending an RPC frame over it.
            transport.sendFromPort("""{"jsonrpc":"2.0","id":"rpc-1","method":"daemon.ping"}""")
            val rpcReply = transport.deliveries.last()
            assertEquals(BridgeDeliveryChannel.WEB_MESSAGE_PORT, rpcReply.first)
            assertTrue(rpcReply.second.contains("rpc-1"))

            // Reset clears pending buffers cleanly before the next page load.
            bridge.resetBeforeLoad()
            assertFalse(transport.portInstalled)
        } finally {
            bridge.destroy()
            scope.cancel()
        }
    }
}
