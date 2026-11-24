package pochita.sideveil.host.plugin

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.WebView
import androidx.webkit.JavaScriptReplyProxy
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
 * pre-ready event queuing, host-local ACL RPCs, container actions, and server-stamped Hub
 * JSON-RPC forwarding for a single [PluginWebViewInstance].
 */
internal class PluginWebMessageBridge(
    scope: CoroutineScope,
    private val daemonClient: DaemonClient,
    private val localRpcHandler: PluginLocalRpcHandler,
    webView: WebView,
    private val pluginOrigin: String,
    private val descriptorProvider: () -> PluginDescriptor,
    private val isForegroundVisible: () -> Boolean,
    private val onContainerAction: (ContainerActionPayload?) -> ContainerActionResult,
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val bridgeScope = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
    private val pendingQueue = PendingWebMessageQueue()
    private val channelState = PluginBridgeChannelState()
    private val destroyed = AtomicBoolean(false)
    private val transport =
        PluginWebMessageTransport(
            webView = webView,
            pluginOrigin = pluginOrigin,
            pluginIdProvider = { descriptorProvider().id },
            isDestroyed = destroyed,
            channelState = channelState,
        )

    @Volatile
    private var lastCommittedUrl: String? = null

    fun installWebMessageListener() {
        transport.installListener(::handleListenerMessage)
    }

    fun resetBeforeLoad() {
        if (destroyed.get()) return
        lastCommittedUrl = null
        transport.setReplyProxy(null)
        channelState.reset()
        transport.closeActivePort()
    }

    fun onNavigationCommitted(url: String?) {
        if (destroyed.get()) return
        lastCommittedUrl = url
        if (!PluginNetworkPolicy.isSameTrustedOrigin(url, pluginOrigin)) {
            transport.setReplyProxy(null)
            channelState.reset()
            transport.closeActivePort()
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
        mainHandler.post { sendToWebView(rawJson, isRpcResponse = false, targetChannel = null) }
    }

    fun destroy() {
        if (!destroyed.compareAndSet(false, true)) return
        channelState.reset()
        bridgeScope.cancel()
        mainHandler.removeCallbacksAndMessages(null)
        pendingQueue.clear()
        transport.destroy()
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
            Log.w(TAG, "Blocked WebMessagePort message for ${descriptor.id} on untrusted url=$lastCommittedUrl")
            return
        }
        handleMessageFromWebView(raw, BridgeDeliveryChannel.WEB_MESSAGE_PORT)
    }

    private fun handleListenerMessage(
        data: String?,
        sourceOrigin: String,
        isMainFrame: Boolean,
        replyProxy: JavaScriptReplyProxy,
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
            Log.w(TAG, "Blocked WebMessageListener message for ${descriptor.id}: origin=$sourceOrigin isMainFrame=$isMainFrame")
            return
        }
        val raw = data ?: return
        transport.setReplyProxy(replyProxy)
        val attach = PluginBridgePolicy.parseAttachSignal(raw)
        if (attach != null) {
            channelState.onReplyProxyAttached(attach.usesListenerChannel)
            sendHostReady(BridgeDeliveryChannel.REPLY_PROXY)
            if (attach.usesListenerChannel) {
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
                    Log.w(TAG, "Malformed WebView RPC for ${descriptorProvider().id}: ${err.message}")
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
            val response = daemonClient.forwardRpc(stamped)
            if (destroyed.get()) return@launch
            val encoded = SideVeilWireJson.encodeToString(response)
            mainHandler.post { sendRpcResponseToWebView(encoded, source) }
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
        mainHandler.post {
            if (destroyed.get()) return@post
            val response =
                when (val outcome = onContainerAction(action)) {
                    ContainerActionResult.Ok -> {
                        RpcResponse(id = req.id, result = buildJsonObject { put("ok", JsonPrimitive(true)) })
                    }

                    is ContainerActionResult.Denied -> {
                        Log.w(TAG, "Denied container.action for ${descriptorProvider().id}: ${outcome.message}")
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
        pendingQueue.drainAll { raw -> sendToWebView(raw, isRpcResponse = true, targetChannel = null) }
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
        transport.deliver(rawJson, resolved)
    }

    companion object {
        private const val TAG = "SideVeilPluginBridge"
    }
}
