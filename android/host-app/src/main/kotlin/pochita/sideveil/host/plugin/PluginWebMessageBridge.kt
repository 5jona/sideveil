package pochita.sideveil.host.plugin

import android.webkit.WebView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import pochita.sideveil.host.service.DaemonClient
import pochita.sideveil.protocol.ContainerActionPayload
import pochita.sideveil.protocol.PluginDescriptor
import pochita.sideveil.protocol.RpcError
import pochita.sideveil.protocol.RpcRequest
import pochita.sideveil.protocol.RpcResponse
import pochita.sideveil.protocol.SideVeilErrorCodes
import pochita.sideveil.protocol.SideVeilMethods
import pochita.sideveil.protocol.SideVeilWireJson
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Coordinates origin- and main-frame-verified IPC, `host.attach` / `host.ready` handshakes,
 * pre-ready event queuing, speculative port replay on listener upgrade, host-local ACL RPCs,
 * container actions, and server-stamped Hub JSON-RPC forwarding for a single
 * [PluginWebViewInstance].
 */
internal class PluginWebMessageBridge internal constructor(
    scope: CoroutineScope,
    private val localRpcHandler: PluginLocalRpcHandler,
    private val pluginOrigin: String,
    private val descriptorProvider: () -> PluginDescriptor,
    private val isForegroundVisible: () -> Boolean,
    private val onContainerAction: (ContainerActionPayload?) -> ContainerActionResult,
    private val runtime: BridgeRuntimeHooks,
    private val channelState: PluginBridgeChannelState = PluginBridgeChannelState(),
    private val destroyed: AtomicBoolean = AtomicBoolean(false),
    transportFactory: (AtomicBoolean, PluginBridgeChannelState) -> PluginMessageTransport,
) {
    constructor(
        scope: CoroutineScope,
        daemonClient: DaemonClient,
        localRpcHandler: PluginLocalRpcHandler,
        webView: WebView,
        pluginOrigin: String,
        descriptorProvider: () -> PluginDescriptor,
        isForegroundVisible: () -> Boolean,
        onContainerAction: (ContainerActionPayload?) -> ContainerActionResult,
    ) : this(
        scope = scope,
        localRpcHandler = localRpcHandler,
        pluginOrigin = pluginOrigin,
        descriptorProvider = descriptorProvider,
        isForegroundVisible = isForegroundVisible,
        onContainerAction = onContainerAction,
        runtime = BridgeRuntimeHooks.forAndroid(daemonClient, TAG),
        transportFactory = { isDestroyed, state ->
            PluginWebMessageTransport(
                webView = webView,
                pluginOrigin = pluginOrigin,
                pluginIdProvider = { descriptorProvider().id },
                isDestroyed = isDestroyed,
                channelState = state,
            )
        },
    )

    private val bridgeScope = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
    private val pendingQueue = PendingWebMessageQueue()
    private val unconfirmedPortQueue = PendingWebMessageQueue()
    private val transport = transportFactory(destroyed, channelState)

    @Volatile
    private var lastCommittedUrl: String? = null

    @Volatile
    private var portConfirmedByClient: Boolean = false

    fun installWebMessageListener() {
        transport.installListener(::handleListenerMessage)
    }

    fun resetBeforeLoad() {
        if (destroyed.get()) return
        lastCommittedUrl = null
        resetChannelBuffers()
    }

    fun onNavigationCommitted(url: String?) {
        if (destroyed.get()) return
        lastCommittedUrl = url
        if (!PluginNetworkPolicy.isSameTrustedOrigin(url, pluginOrigin)) {
            resetChannelBuffers()
        }
    }

    fun installChannel() {
        transport.installWebMessagePort(
            onPortMessage = ::handlePortMessage,
            onReady = { channel ->
                sendHostReady(channel)
                flushQueuedMessages()
            },
        )
    }

    fun postRawAsync(rawJson: String) {
        if (destroyed.get()) return
        runtime.postOnMain { sendToWebView(rawJson, isRpcResponse = false, targetChannel = null) }
    }

    fun destroy() {
        if (!destroyed.compareAndSet(false, true)) return
        channelState.reset()
        portConfirmedByClient = false
        bridgeScope.cancel()
        runtime.clearMainCallbacks()
        pendingQueue.clear()
        unconfirmedPortQueue.clear()
        transport.destroy()
    }

    private fun resetChannelBuffers() {
        portConfirmedByClient = false
        pendingQueue.clear()
        unconfirmedPortQueue.clear()
        transport.clearReplyProxy()
        channelState.reset()
        transport.closeActivePort()
    }

    private fun handlePortMessage(raw: String) {
        val descriptor = descriptorProvider()
        val trusted =
            PluginBridgePolicy.isCommittedMainFrameTrusted(
                lastCommittedUrl = lastCommittedUrl,
                expectedPluginOrigin = pluginOrigin,
                isUntrustedEmbed = PluginNetworkPolicy.isUntrustedEmbed(descriptor),
            )
        if (!trusted) {
            runtime.onWarnLog("Blocked WebMessagePort message for ${descriptor.id} on untrusted url=$lastCommittedUrl")
            return
        }
        portConfirmedByClient = true
        unconfirmedPortQueue.clear()
        handleMessageFromWebView(raw, BridgeDeliveryChannel.WEB_MESSAGE_PORT)
    }

    private fun handleListenerMessage(
        data: String?,
        sourceOrigin: String,
        isMainFrame: Boolean,
        activateReplyProxy: () -> Unit,
    ) {
        if (destroyed.get()) return
        val descriptor = descriptorProvider()
        val allowed =
            PluginBridgePolicy.isTrustedListenerMessage(
                sourceOrigin = sourceOrigin,
                isMainFrame = isMainFrame,
                expectedPluginOrigin = pluginOrigin,
                isUntrustedEmbed = PluginNetworkPolicy.isUntrustedEmbed(descriptor),
            )
        if (!allowed) {
            runtime.onWarnLog(
                "Blocked WebMessageListener message for ${descriptor.id}: origin=$sourceOrigin isMainFrame=$isMainFrame",
            )
            return
        }
        val raw = data ?: return
        activateReplyProxy()
        val attach = PluginBridgePolicy.parseAttachSignal(raw)
        if (attach != null) {
            channelState.onReplyProxyAttached(attach.usesListenerChannel)
            sendHostReady(BridgeDeliveryChannel.REPLY_PROXY)
            if (attach.usesListenerChannel) {
                portConfirmedByClient = false
                transport.closeActivePort()
                flushQueuedMessages()
            }
            return
        }
        handleMessageFromWebView(raw, BridgeDeliveryChannel.REPLY_PROXY)
    }

    private fun handleMessageFromWebView(
        raw: String,
        source: BridgeDeliveryChannel,
    ) {
        if (destroyed.get()) return
        val req =
            runCatching { SideVeilWireJson.decodeFromString<RpcRequest>(raw) }
                .onFailure { err ->
                    runtime.onWarnLog("Malformed WebView RPC for ${descriptorProvider().id}: ${err.message}")
                    val id = PluginBridgePolicy.extractRpcId(raw) ?: return@onFailure
                    val errResp = RpcResponse(id = id, error = RpcError(SideVeilErrorCodes.INVALID_REQUEST, "Malformed JSON-RPC request"))
                    sendRpcResponseToWebView(SideVeilWireJson.encodeToString(errResp), source)
                }.getOrNull() ?: return
        if (req.method == SideVeilMethods.CONTAINER_ACTION) {
            handleContainerAction(req, source)
            return
        }
        val reply: (String) -> Unit = { encoded -> sendRpcResponseToWebView(encoded, source) }
        if (localRpcHandler.tryHandle(req, descriptorProvider(), isForegroundVisible(), reply)) return
        forwardDaemonRpc(req, source)
    }

    private fun forwardDaemonRpc(
        req: RpcRequest,
        source: BridgeDeliveryChannel,
    ) {
        if (destroyed.get()) return
        val stamped = req.copy(pluginId = descriptorProvider().id)
        bridgeScope.launch {
            val response = runtime.forwardDaemonRpc(stamped)
            if (destroyed.get()) return@launch
            val encoded = SideVeilWireJson.encodeToString(response)
            runtime.postOnMain { sendRpcResponseToWebView(encoded, source) }
        }
    }

    private fun handleContainerAction(
        req: RpcRequest,
        source: BridgeDeliveryChannel,
    ) {
        if (destroyed.get()) return
        val action =
            req.params?.let { params ->
                runCatching { SideVeilWireJson.decodeFromJsonElement<ContainerActionPayload>(params) }.getOrNull()
            }
        runtime.postOnMain {
            if (destroyed.get()) return@postOnMain
            val response =
                when (val outcome = onContainerAction(action)) {
                    ContainerActionResult.Ok -> {
                        RpcResponse(id = req.id, result = buildJsonObject { put("ok", JsonPrimitive(true)) })
                    }

                    is ContainerActionResult.Denied -> {
                        runtime.onWarnLog("Denied container.action for ${descriptorProvider().id}: ${outcome.message}")
                        RpcResponse(id = req.id, error = RpcError(SideVeilErrorCodes.PERMISSION_DENIED, outcome.message))
                    }

                    is ContainerActionResult.Invalid -> {
                        RpcResponse(id = req.id, error = RpcError(SideVeilErrorCodes.INVALID_PARAMS, outcome.message))
                    }
                }
            sendRpcResponseToWebView(SideVeilWireJson.encodeToString(response), source)
        }
    }

    private fun sendHostReady(channel: BridgeDeliveryChannel) =
        transport.deliver(PluginBridgePolicy.buildHostReadyEnvelope(descriptorProvider()), channel)

    private fun flushQueuedMessages() {
        if (destroyed.get()) return
        if (channelState.listenerChannelReady) {
            unconfirmedPortQueue.drainAllTyped { raw, isRpcResponse ->
                sendToWebView(raw, isRpcResponse = isRpcResponse, targetChannel = null)
            }
        }
        pendingQueue.drainAllTyped { raw, isRpcResponse ->
            sendToWebView(raw, isRpcResponse = isRpcResponse, targetChannel = null)
        }
    }

    private fun sendRpcResponseToWebView(
        rawJson: String,
        source: BridgeDeliveryChannel,
    ) = sendToWebView(rawJson, isRpcResponse = true, targetChannel = channelState.resolveReplyChannel(source))

    private fun sendToWebView(
        rawJson: String,
        isRpcResponse: Boolean,
        targetChannel: BridgeDeliveryChannel?,
    ) {
        if (destroyed.get()) return
        val resolved = targetChannel ?: channelState.resolveEventChannel()
        if (resolved == null) {
            if (isRpcResponse) pendingQueue.enqueueResponse(rawJson) else pendingQueue.enqueueEvent(rawJson)
            return
        }
        if (shouldBufferUnconfirmedDelivery(resolved)) {
            if (isRpcResponse) unconfirmedPortQueue.enqueueResponse(rawJson) else unconfirmedPortQueue.enqueueEvent(rawJson)
        }
        transport.deliver(rawJson, resolved)
    }

    private fun shouldBufferUnconfirmedDelivery(channel: BridgeDeliveryChannel): Boolean =
        channel != BridgeDeliveryChannel.REPLY_PROXY &&
            !channelState.listenerChannelReady &&
            !portConfirmedByClient

    companion object {
        private const val TAG = "SideVeilPluginBridge"
    }
}
