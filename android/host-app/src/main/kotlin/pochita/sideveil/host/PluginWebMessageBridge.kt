package pochita.sideveil.host

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
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import org.json.JSONObject
import pochita.sideveil.protocol.ContainerActionPayload
import pochita.sideveil.protocol.PluginDescriptor
import pochita.sideveil.protocol.RpcRequest
import pochita.sideveil.protocol.RpcResponse
import pochita.sideveil.protocol.SideVeilMethods
import pochita.sideveil.protocol.SideVeilWireJson

/**
 * Manages the `WebMessagePort` / `window.__POCHITA_HOST__` channel, queued pre-ready
 * event flushing, host-local ACL RPCs, container action decoding, and server-stamped
 * JSON-RPC forwarding for a single [PluginWebViewInstance].
 */
internal class PluginWebMessageBridge(
    private val scope: CoroutineScope,
    private val daemonClient: DaemonClient,
    private val localRpcHandler: PluginLocalRpcHandler,
    private val webView: WebView,
    private val pluginOrigin: String,
    private val descriptorProvider: () -> PluginDescriptor,
    private val onContainerAction: (ContainerActionPayload?) -> Unit,
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val pendingEventsBeforeReady = ArrayDeque<String>(12)

    @Volatile
    private var activePort: WebMessagePortCompat? = null

    @Volatile
    private var pageReady: Boolean = false

    @Volatile
    private var lastCommittedUrl: String? = null

    fun createJsHostObject(): JsHostObject = JsHostObject()

    fun resetBeforeLoad() {
        pageReady = false
        lastCommittedUrl = null
        closeActivePort()
    }

    fun onNavigationCommitted(url: String?) {
        lastCommittedUrl = url
        if (!PluginNetworkPolicy.isSameTrustedOrigin(url, pluginOrigin)) {
            pageReady = false
            closeActivePort()
        }
    }

    fun installChannel() {
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
            activePort = hostPort
            if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_PORT_SET_MESSAGE_CALLBACK)) {
                hostPort.setWebMessageCallback(
                    object : WebMessagePortCompat.WebMessageCallbackCompat() {
                        override fun onMessage(
                            port: WebMessagePortCompat,
                            message: WebMessageCompat?,
                        ) {
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
            flushQueuedEvents()
        }.onFailure { err ->
            Log.w(TAG, "WebMessagePort setup failed for ${descriptorProvider().id}: ${err.message}")
            fallbackToEvaluateJavascript("WebMessagePort setup failed")
        }
    }

    fun postRawAsync(rawJson: String) {
        mainHandler.post { sendToWebView(rawJson) }
    }

    fun closeActivePort() {
        val port = activePort ?: return
        activePort = null
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_PORT_CLOSE)) {
            runCatching { port.close() }
        }
    }

    private fun fallbackToEvaluateJavascript(reason: String) {
        Log.w(TAG, "$reason for ${descriptorProvider().id}, using evaluateJavascript fallback")
        pageReady = true
        flushQueuedEvents()
    }

    private fun handleMessageFromWebView(raw: String) {
        val req = runCatching { SideVeilWireJson.decodeFromString<RpcRequest>(raw) }.getOrNull() ?: return
        if (req.method == SideVeilMethods.CONTAINER_ACTION) {
            handleContainerAction(req)
            return
        }
        if (localRpcHandler.tryHandle(req, descriptorProvider(), ::sendToWebView)) {
            return
        }
        forwardDaemonRpc(req)
    }

    private fun forwardDaemonRpc(req: RpcRequest) {
        // Stamp authenticated pluginId on the host side so WebView JS cannot spoof identity.
        val stamped = req.copy(pluginId = descriptorProvider().id)
        scope.launch {
            val response = daemonClient.forwardRpc(stamped)
            val encoded = SideVeilWireJson.encodeToString(response)
            mainHandler.post { sendToWebView(encoded) }
        }
    }

    private fun handleContainerAction(req: RpcRequest) {
        val action =
            req.params?.let { params ->
                runCatching { SideVeilWireJson.decodeFromJsonElement<ContainerActionPayload>(params) }.getOrNull()
            }
        mainHandler.post {
            onContainerAction(action)
            val ok = RpcResponse(id = req.id, result = buildJsonObject { put("ok", JsonPrimitive(true)) })
            sendToWebView(SideVeilWireJson.encodeToString(ok))
        }
    }

    private fun flushQueuedEvents() {
        while (pendingEventsBeforeReady.isNotEmpty()) {
            sendToWebView(pendingEventsBeforeReady.removeFirst())
        }
    }

    private fun sendToWebView(rawJson: String) {
        if (!pageReady) {
            if (pendingEventsBeforeReady.size >= MAX_PENDING_EVENTS) {
                pendingEventsBeforeReady.removeFirst()
            }
            pendingEventsBeforeReady.addLast(rawJson)
            return
        }

        val port = activePort
        if (port != null && WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_PORT_POST_MESSAGE)) {
            runCatching {
                port.postMessage(WebMessageCompat(rawJson))
                return
            }.onFailure { err ->
                Log.w(TAG, "WebMessagePort postMessage failed for ${descriptorProvider().id}", err)
            }
        }

        val quoted = JSONObject.quote(rawJson)
        val quotedOrigin = JSONObject.quote(pluginOrigin)
        webView.evaluateJavascript("window.postMessage($quoted, $quotedOrigin);", null)
    }

    inner class JsHostObject {
        @JavascriptInterface
        fun postMessage(message: String) {
            if (PluginNetworkPolicy.isUntrustedEmbed(descriptorProvider())) return
            mainHandler.post {
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
        private const val MAX_PENDING_EVENTS = 16
    }
}
