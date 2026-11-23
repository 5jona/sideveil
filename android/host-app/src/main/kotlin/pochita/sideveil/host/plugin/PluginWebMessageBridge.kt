package pochita.sideveil.host.plugin

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.core.net.toUri
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebMessagePortCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import org.json.JSONObject
import pochita.sideveil.host.BuildConfig
import pochita.sideveil.host.service.DaemonClient
import pochita.sideveil.protocol.ContainerActionPayload
import pochita.sideveil.protocol.PluginDescriptor
import pochita.sideveil.protocol.RpcRequest
import pochita.sideveil.protocol.RpcResponse
import pochita.sideveil.protocol.SideVeilMethods
import pochita.sideveil.protocol.SideVeilWireJson
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Manages the `WebMessagePort` / `window.__POCHITA_HOST__` channel, queued pre-ready
 * event flushing, host-local ACL RPCs, container action decoding, and server-stamped
 * JSON-RPC forwarding for a single [PluginWebViewInstance].
 */
internal class PluginWebMessageBridge(
    scope: CoroutineScope,
    private val daemonClient: DaemonClient,
    private val localRpcHandler: PluginLocalRpcHandler,
    private val webView: WebView,
    private val pluginOrigin: String,
    private val descriptorProvider: () -> PluginDescriptor,
    private val onContainerAction: (ContainerActionPayload?) -> Unit,
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val bridgeScope = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
    private val pendingQueue = PendingWebMessageQueue()
    private val destroyed = AtomicBoolean(false)

    @Volatile
    private var activePort: WebMessagePortCompat? = null

    @Volatile
    private var pageReady: Boolean = false

    @Volatile
    private var lastCommittedUrl: String? = null

    fun createJsHostObject(): JsHostObject = JsHostObject()

    fun resetBeforeLoad() {
        if (destroyed.get()) return
        pageReady = false
        lastCommittedUrl = null
        closeActivePort()
    }

    fun onNavigationCommitted(url: String?) {
        if (destroyed.get()) return
        lastCommittedUrl = url
        if (!PluginNetworkPolicy.isSameTrustedOrigin(url, pluginOrigin)) {
            pageReady = false
            closeActivePort()
        }
    }

    fun installChannel() {
        if (destroyed.get()) return
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.CREATE_WEB_MESSAGE_CHANNEL) ||
            !WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_PORT_SET_MESSAGE_CALLBACK) ||
            !WebViewFeature.isFeatureSupported(WebViewFeature.POST_WEB_MESSAGE)
        ) {
            fallbackToEvaluateJavascript("WebMessagePort APIs unavailable")
            return
        }

        runCatching {
            // Android Lint's RequiresFeature detector requires an enclosing single-feature guard per call.
            val channel =
                if (WebViewFeature.isFeatureSupported(WebViewFeature.CREATE_WEB_MESSAGE_CHANNEL)) {
                    WebViewCompat.createWebMessageChannel(webView)
                } else {
                    return@runCatching
                }
            val hostPort = channel[0]
            val pagePort = channel[1]

            closeActivePort()
            if (destroyed.get()) {
                runCatching {
                    if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_PORT_CLOSE)) {
                        hostPort.close()
                        pagePort.close()
                    }
                }
                return@runCatching
            }
            activePort = hostPort
            if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_PORT_SET_MESSAGE_CALLBACK)) {
                hostPort.setWebMessageCallback(
                    object : WebMessagePortCompat.WebMessageCallbackCompat() {
                        override fun onMessage(
                            port: WebMessagePortCompat,
                            message: WebMessageCompat?,
                        ) {
                            if (destroyed.get()) return
                            val data = message?.data ?: return
                            handleMessageFromWebView(data)
                        }
                    },
                )
            }
            if (WebViewFeature.isFeatureSupported(WebViewFeature.POST_WEB_MESSAGE)) {
                WebViewCompat.postWebMessage(
                    webView,
                    WebMessageCompat("", arrayOf(pagePort)),
                    pluginOrigin.toUri(),
                )
            }
            pageReady = true
            flushQueuedMessages()
        }.onFailure { err ->
            if (destroyed.get()) return
            Log.w(TAG, "WebMessagePort setup failed for ${descriptorProvider().id}: ${err.message}")
            fallbackToEvaluateJavascript("WebMessagePort setup failed")
        }
    }

    fun postRawAsync(rawJson: String) {
        if (destroyed.get()) return
        mainHandler.post { sendToWebView(rawJson, isRpcResponse = false) }
    }

    fun closeActivePort() {
        val port = activePort ?: return
        activePort = null
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_PORT_CLOSE)) {
            runCatching { port.close() }
        }
    }

    fun destroy() {
        if (!destroyed.compareAndSet(false, true)) return
        pageReady = false
        bridgeScope.cancel()
        mainHandler.removeCallbacksAndMessages(null)
        pendingQueue.clear()
        closeActivePort()
    }

    private fun fallbackToEvaluateJavascript(reason: String) {
        if (destroyed.get()) return
        Log.w(TAG, "$reason for ${descriptorProvider().id}, using evaluateJavascript fallback")
        pageReady = true
        flushQueuedMessages()
    }

    private fun handleMessageFromWebView(raw: String) {
        if (destroyed.get()) return
        val req = runCatching { SideVeilWireJson.decodeFromString<RpcRequest>(raw) }.getOrNull() ?: return
        if (req.method == SideVeilMethods.CONTAINER_ACTION) {
            handleContainerAction(req)
            return
        }
        if (localRpcHandler.tryHandle(req, descriptorProvider(), ::sendRpcResponseToWebView)) {
            return
        }
        forwardDaemonRpc(req)
    }

    private fun forwardDaemonRpc(req: RpcRequest) {
        if (destroyed.get()) return
        // Stamp authenticated pluginId on the host side so WebView JS cannot spoof identity.
        val stamped = req.copy(pluginId = descriptorProvider().id)
        bridgeScope.launch {
            val response = daemonClient.forwardRpc(stamped)
            if (destroyed.get()) return@launch
            val encoded = SideVeilWireJson.encodeToString(response)
            mainHandler.post { sendRpcResponseToWebView(encoded) }
        }
    }

    private fun handleContainerAction(req: RpcRequest) {
        if (destroyed.get()) return
        val action =
            req.params?.let { params ->
                runCatching { SideVeilWireJson.decodeFromJsonElement<ContainerActionPayload>(params) }.getOrNull()
            }
        mainHandler.post {
            if (destroyed.get()) return@post
            onContainerAction(action)
            val ok = RpcResponse(id = req.id, result = buildJsonObject { put("ok", JsonPrimitive(true)) })
            sendRpcResponseToWebView(SideVeilWireJson.encodeToString(ok))
        }
    }

    private fun flushQueuedMessages() {
        if (destroyed.get()) return
        pendingQueue.drainAll { raw -> sendToWebView(raw, isRpcResponse = true) }
    }

    private fun sendRpcResponseToWebView(rawJson: String) {
        sendToWebView(rawJson, isRpcResponse = true)
    }

    private fun sendToWebView(
        rawJson: String,
        isRpcResponse: Boolean,
    ) {
        if (destroyed.get()) return
        if (!pageReady) {
            if (isRpcResponse) {
                pendingQueue.enqueueResponse(rawJson)
            } else {
                pendingQueue.enqueueEvent(rawJson)
            }
            return
        }

        val port = activePort
        if (port != null && WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_PORT_POST_MESSAGE)) {
            runCatching {
                port.postMessage(WebMessageCompat(rawJson))
                return
            }.onFailure { err ->
                if (destroyed.get()) return
                Log.w(TAG, "WebMessagePort postMessage failed for ${descriptorProvider().id}", err)
            }
        }

        if (destroyed.get()) return
        val quoted = JSONObject.quote(rawJson)
        val quotedOrigin = JSONObject.quote(pluginOrigin)
        webView.evaluateJavascript("window.postMessage($quoted, $quotedOrigin);", null)
    }

    inner class JsHostObject {
        @JavascriptInterface
        fun postMessage(message: String) {
            if (destroyed.get() || PluginNetworkPolicy.isUntrustedEmbed(descriptorProvider())) return
            mainHandler.post {
                if (destroyed.get()) return@post
                val liveUrl = webView.url
                val committed = lastCommittedUrl
                val liveAllowed = PluginNetworkPolicy.isSameTrustedOrigin(liveUrl, pluginOrigin)
                val committedAllowed = committed == null || PluginNetworkPolicy.isSameTrustedOrigin(committed, pluginOrigin)
                if (!liveAllowed || !committedAllowed) {
                    Log.w(
                        TAG,
                        "Blocked JS bridge message for ${descriptorProvider().id} from untrusted origin: " +
                            "live=$liveUrl committed=$committed",
                    )
                    return@post
                }
                handleMessageFromWebView(message)
            }
        }

        @JavascriptInterface
        fun getVersion(): String = BuildConfig.VERSION_NAME

        @JavascriptInterface
        fun getPluginId(): String = descriptorProvider().id
    }

    companion object {
        private const val TAG = "SideVeilPluginBridge"
    }
}
