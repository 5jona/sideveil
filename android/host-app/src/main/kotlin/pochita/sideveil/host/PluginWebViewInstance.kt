package pochita.sideveil.host

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.core.net.toUri
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebMessagePortCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.json.JSONObject
import pochita.sideveil.protocol.ContainerActionPayload
import pochita.sideveil.protocol.HostEventEnvelope
import pochita.sideveil.protocol.PluginCapability
import pochita.sideveil.protocol.PluginDescriptor
import pochita.sideveil.protocol.PluginSurfaceBackdrop
import pochita.sideveil.protocol.RpcError
import pochita.sideveil.protocol.RpcRequest
import pochita.sideveil.protocol.RpcResponse
import pochita.sideveil.protocol.SideveilMethods
import pochita.sideveil.protocol.SideveilWireJson

/**
 * Isolated WebView sandbox instance for a single Sideveil plugin under
 * `https://<pluginId>.plugins.sideveil.local/` with `onRenderProcessGone` crash recovery,
 * dedicated `WebMessagePort`, per-plugin container preferences, and server-stamped RPC identity.
 */
@SuppressLint("SetJavaScriptEnabled")
class PluginWebViewInstance(
    private val context: Context,
    private val scope: CoroutineScope,
    private val daemonClient: DaemonClient,
    private val assetFetcher: PluginAssetFetcher,
    initialDescriptor: PluginDescriptor,
    private val callbacks: PluginContainerCallbacks,
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    val webView: WebView = WebView(context)

    @Volatile
    var descriptor: PluginDescriptor = initialDescriptor
        private set

    @Volatile
    var backInterceptEnabled: Boolean = false
        private set

    @Volatile
    var surfaceBackdrop: PluginSurfaceBackdrop = initialDescriptor.surface.backdrop
        private set

    @Volatile
    var sizeOverride: PluginSizeOverride =
        PluginSizeOverride(
            widthDp = initialDescriptor.surface.widthDp,
            heightPercent = initialDescriptor.surface.heightPercent,
        )
        private set

    @Volatile
    private var activePort: WebMessagePortCompat? = null

    @Volatile
    private var pageReady: Boolean = false

    private val pendingEventsBeforeReady = ArrayDeque<String>(12)
    private val pluginOrigin: String = assetFetcher.originFor(initialDescriptor.id)

    init {
        webView.setBackgroundColor(Color.TRANSPARENT)
        webView.isFocusable = true
        webView.isFocusableInTouchMode = true
        webView.overScrollMode = WebView.OVER_SCROLL_NEVER
        webView.isVerticalScrollBarEnabled = false
        webView.isHorizontalScrollBarEnabled = false

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            useWideViewPort = true
            loadWithOverviewMode = false
        }

        webView.addJavascriptInterface(JsHostObject(), "__POCHITA_HOST__")
        webView.webViewClient =
            object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest,
                ): Boolean = !assetFetcher.isAllowedPluginUri(request.url, descriptor.id)

                override fun shouldInterceptRequest(
                    view: WebView,
                    request: WebResourceRequest,
                ): WebResourceResponse = assetFetcher.fetch(descriptor, request.url)

                override fun onPageFinished(
                    view: WebView,
                    url: String?,
                ) {
                    super.onPageFinished(view, url)
                    if (url != null && assetFetcher.isAllowedPluginUri(url.toUri(), descriptor.id)) {
                        installMessageChannel()
                    }
                }

                override fun onRenderProcessGone(
                    view: WebView,
                    detail: RenderProcessGoneDetail,
                ): Boolean {
                    Log.e(
                        TAG,
                        "WebView render process gone for plugin=${descriptor.id} " +
                            "didCrash=${detail.didCrash()} priority=${detail.rendererPriorityAtExit()}",
                    )
                    mainHandler.post { callbacks.onRenderProcessCrashed(descriptor.id) }
                    return true
                }
            }
    }

    fun updateDescriptor(next: PluginDescriptor) {
        descriptor = next
    }

    fun loadEntry() {
        pageReady = false
        closeActivePort()
        webView.loadUrl(assetFetcher.entryUrlFor(descriptor))
    }

    fun pushEvent(
        event: String,
        payload: JsonElement?,
    ) {
        val raw = SideveilWireJson.encodeToString(HostEventEnvelope(event = event, payload = payload))
        mainHandler.post { sendToWebView(raw) }
    }

    fun pushEnvelope(envelope: HostEventEnvelope) {
        val raw = SideveilWireJson.encodeToString(envelope)
        mainHandler.post { sendToWebView(raw) }
    }

    fun destroy() {
        closeActivePort()
        webView.stopLoading()
        webView.destroy()
    }

    private fun closeActivePort() {
        val port = activePort ?: return
        activePort = null
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_PORT_CLOSE)) {
            runCatching { port.close() }
        }
    }

    private fun installMessageChannel() {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.CREATE_WEB_MESSAGE_CHANNEL)) {
            fallbackToEvaluateJavascript("CREATE_WEB_MESSAGE_CHANNEL unavailable")
            return
        }
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_PORT_SET_MESSAGE_CALLBACK)) {
            fallbackToEvaluateJavascript("WEB_MESSAGE_PORT_SET_MESSAGE_CALLBACK unavailable")
            return
        }
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.POST_WEB_MESSAGE)) {
            fallbackToEvaluateJavascript("POST_WEB_MESSAGE unavailable")
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
            Log.w(TAG, "WebMessagePort setup failed for ${descriptor.id}: ${err.message}")
            fallbackToEvaluateJavascript("WebMessagePort setup failed")
        }
    }

    private fun fallbackToEvaluateJavascript(reason: String) {
        Log.w(TAG, "$reason for ${descriptor.id}, using evaluateJavascript fallback")
        pageReady = true
        flushQueuedEvents()
    }

    private fun handleMessageFromWebView(raw: String) {
        val req =
            runCatching { SideveilWireJson.decodeFromString<RpcRequest>(raw) }.getOrNull()
                ?: return

        when (req.method) {
            SideveilMethods.CONTAINER_ACTION -> {
                handleContainerAction(req)
            }

            SideveilMethods.UI_TOAST -> {
                handleToastRpc(req)
            }

            else -> {
                // Stamp authenticated pluginId on the host side so WebView JS cannot spoof identity.
                val stamped = req.copy(pluginId = descriptor.id)
                scope.launch {
                    val response = daemonClient.forwardRpc(stamped)
                    val encoded = SideveilWireJson.encodeToString(response)
                    mainHandler.post { sendToWebView(encoded) }
                }
            }
        }
    }

    private fun handleToastRpc(req: RpcRequest) {
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
            applyContainerAction(action)
            val ok = RpcResponse(id = req.id, result = buildJsonObject { put("ok", JsonPrimitive(true)) })
            sendToWebView(SideveilWireJson.encodeToString(ok))
        }
    }

    private fun applyContainerAction(action: ContainerActionPayload?) {
        val pluginId = descriptor.id
        when (action?.type) {
            "dismiss" -> {
                callbacks.onDismissRequested(pluginId)
            }

            "requestImeFocus" -> {
                callbacks.onImeFocusRequested(pluginId, action.focused == true)
            }

            "syncTheme" -> {
                val payload = action.payload ?: return
                callbacks.onAppearanceSynced(pluginId, payload.themeId, payload.colorScheme)
            }

            "setPreferredSize" -> {
                val next =
                    PluginSizeOverride(
                        widthDp = action.widthDp,
                        heightDp = action.heightDp,
                        heightPercent = action.heightPercent,
                    )
                sizeOverride = next
                callbacks.onPreferredSizeChanged(pluginId, next)
            }

            "setBackIntercept" -> {
                backInterceptEnabled = action.enabled == true
            }

            "setSurfaceBackdrop" -> {
                val parsed = parseBackdrop(action.backdrop) ?: return
                surfaceBackdrop = parsed
                callbacks.onBackdropChanged(pluginId, parsed)
            }

            else -> {
                Log.d(TAG, "Ignored unknown container.action type=${action?.type}")
            }
        }
    }

    private fun parseBackdrop(raw: String?): PluginSurfaceBackdrop? =
        when (raw) {
            "native-glass" -> PluginSurfaceBackdrop.NATIVE_GLASS
            "transparent" -> PluginSurfaceBackdrop.TRANSPARENT
            "opaque" -> PluginSurfaceBackdrop.OPAQUE
            else -> null
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
                Log.w(TAG, "WebMessagePort postMessage failed for ${descriptor.id}", err)
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
        fun getVersion(): String = HOST_VERSION

        @JavascriptInterface
        fun getPluginId(): String = descriptor.id
    }

    companion object {
        private const val TAG = "SideveilPluginWebView"
        private const val HOST_VERSION = "0.1.0"
        private const val MAX_PENDING_EVENTS = 16
    }
}
