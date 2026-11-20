package pochita.sideveil.host

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.widget.Toast
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
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.json.JSONObject
import pochita.sideveil.protocol.ContainerActionPayload
import pochita.sideveil.protocol.PluginCapability
import pochita.sideveil.protocol.PluginDescriptor
import pochita.sideveil.protocol.RpcError
import pochita.sideveil.protocol.RpcRequest
import pochita.sideveil.protocol.RpcResponse
import pochita.sideveil.protocol.SideveilMethods
import pochita.sideveil.protocol.SideveilWireJson

/**
 * Manages the `WebMessagePort` / `window.__POCHITA_HOST__` channel, queued pre-ready
 * event flushing, `ui.toast` capability checks, container action decoding, and
 * server-stamped JSON-RPC forwarding for a single [PluginWebViewInstance].
 */
internal class PluginWebMessageBridge(
    private val context: Context,
    private val scope: CoroutineScope,
    private val daemonClient: DaemonClient,
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

    fun createJsHostObject(): JsHostObject = JsHostObject()

    fun resetBeforeLoad() {
        pageReady = false
        closeActivePort()
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
        val req = runCatching { SideveilWireJson.decodeFromString<RpcRequest>(raw) }.getOrNull() ?: return
        when (req.method) {
            SideveilMethods.CONTAINER_ACTION -> handleContainerAction(req)
            SideveilMethods.UI_TOAST -> handleToastRpc(req)
            else -> forwardDaemonRpc(req)
        }
    }

    private fun forwardDaemonRpc(req: RpcRequest) {
        // Stamp authenticated pluginId on the host side so WebView JS cannot spoof identity.
        val stamped = req.copy(pluginId = descriptorProvider().id)
        scope.launch {
            val response = daemonClient.forwardRpc(stamped)
            val encoded = SideveilWireJson.encodeToString(response)
            mainHandler.post { sendToWebView(encoded) }
        }
    }

    private fun handleToastRpc(req: RpcRequest) {
        val descriptor = descriptorProvider()
        val allowed = descriptor.builtin || descriptor.capabilities.contains(PluginCapability.TOAST)
        if (!allowed) {
            val denied =
                RpcResponse(
                    id = req.id,
                    error = RpcError(-32003, "Plugin '${descriptor.id}' lacks 'toast' capability"),
                )
            mainHandler.post { sendToWebView(SideveilWireJson.encodeToString(denied)) }
            return
        }
        val message =
            req.params
                ?.jsonObject
                ?.get("message")
                ?.jsonPrimitive
                ?.content
                .orEmpty()
        mainHandler.post {
            if (message.isNotBlank()) {
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            }
            val ok = RpcResponse(id = req.id, result = buildJsonObject { put("ok", JsonPrimitive(true)) })
            sendToWebView(SideveilWireJson.encodeToString(ok))
        }
    }

    private fun handleContainerAction(req: RpcRequest) {
        val action =
            req.params?.let { params ->
                runCatching { SideveilWireJson.decodeFromJsonElement<ContainerActionPayload>(params) }.getOrNull()
            }
        mainHandler.post {
            onContainerAction(action)
            val ok = RpcResponse(id = req.id, result = buildJsonObject { put("ok", JsonPrimitive(true)) })
            sendToWebView(SideveilWireJson.encodeToString(ok))
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
            handleMessageFromWebView(message)
        }

        @JavascriptInterface
        fun getVersion(): String = BuildConfig.VERSION_NAME

        @JavascriptInterface
        fun getPluginId(): String = descriptorProvider().id
    }

    companion object {
        private const val TAG = "SideveilPluginBridge"
        private const val MAX_PENDING_EVENTS = 16
    }
}
