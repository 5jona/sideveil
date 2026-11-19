package pochita.sideveil.host

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebMessagePortCompat
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.json.JSONObject
import pochita.sideveil.shared.HostEventEnvelope
import pochita.sideveil.shared.RpcError
import pochita.sideveil.shared.RpcRequest
import pochita.sideveil.shared.RpcResponse
import pochita.sideveil.shared.SideveilWireJson
import java.io.ByteArrayInputStream
import java.io.IOException

interface PanelContainerDelegate {
    fun dismissPanel()

    fun setImeFocused(focused: Boolean)

    fun syncAppearance(
        themeId: String,
        colorScheme: String,
        wantsNativeBlur: Boolean,
        cornerRadiusPx: Int,
    )
}

/**
 * Owns the warm-resident transparent WebView serving local assets under assets/panel/
 * and implementing the @pochita/bridge HostTransport wire contract over WebMessagePort
 * and window.__POCHITA_HOST__.
 */
@SuppressLint("SetJavaScriptEnabled")
class PanelWebViewBridge(
    private val context: Context,
    private val scope: CoroutineScope,
    private val daemonClient: DaemonClient,
    private val containerDelegate: PanelContainerDelegate,
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    val webView: WebView = WebView(context)

    @Volatile
    private var activePort: WebMessagePortCompat? = null

    @Volatile
    private var pageReady: Boolean = false

    private val pendingEventsBeforeReady = ArrayDeque<String>(8)

    private val assetLoader =
        WebViewAssetLoader
            .Builder()
            .setDomain(APP_DOMAIN)
            .addPathHandler(PANEL_PREFIX) { path -> openPanelAsset(path) }
            .build()

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
                override fun shouldInterceptRequest(
                    view: WebView,
                    request: WebResourceRequest,
                ): WebResourceResponse? = assetLoader.shouldInterceptRequest(request.url)

                override fun onPageFinished(
                    view: WebView,
                    url: String?,
                ) {
                    super.onPageFinished(view, url)
                    installMessageChannel()
                }
            }
    }

    fun warmLoad() {
        webView.loadUrl(PANEL_ENTRY_URL)
    }

    fun pushDaemonEvent(envelope: HostEventEnvelope) {
        val raw = SideveilWireJson.encodeToString(envelope)
        mainHandler.post { sendToWebView(raw) }
    }

    fun pushEvent(
        event: String,
        payload: JsonElement?,
    ) {
        val raw = SideveilWireJson.encodeToString(HostEventEnvelope(event = event, payload = payload))
        mainHandler.post { sendToWebView(raw) }
    }

    fun destroy() {
        activePort?.close()
        activePort = null
        webView.destroy()
    }

    private fun installMessageChannel() {
        runCatching {
            val channel = WebViewCompat.createWebMessageChannel(webView)
            val hostPort = channel[0]
            val pagePort = channel[1]

            activePort?.close()
            activePort = hostPort
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

            WebViewCompat.postWebMessage(
                webView,
                WebMessageCompat("", arrayOf(pagePort)),
                Uri.parse(APP_ORIGIN),
            )
            pageReady = true
            flushQueuedEvents()
            Log.i(TAG, "Transferred dedicated WebMessagePort to panel WebView")
        }.onFailure { err ->
            Log.w(TAG, "WebMessagePort setup failed, using evaluateJavascript fallback", err)
            pageReady = true
            flushQueuedEvents()
        }
    }

    private fun handleMessageFromWebView(rawJson: String) {
        val req =
            runCatching { SideveilWireJson.decodeFromString<RpcRequest>(rawJson) }
                .getOrNull() ?: return

        if (req.method == "container.action") {
            handleContainerAction(req)
            return
        }

        scope.launch {
            val response = daemonClient.forwardRpc(rawJson)
            val wire = SideveilWireJson.encodeToString(response)
            mainHandler.post { sendToWebView(wire) }
        }
    }

    private fun handleContainerAction(req: RpcRequest) {
        val obj: JsonObject? = runCatching { req.params?.jsonObject }.getOrNull()
        val actionType = obj?.get("type")?.jsonPrimitive?.content
        mainHandler.post {
            when (actionType) {
                "dismiss" -> {
                    containerDelegate.dismissPanel()
                }

                "requestImeFocus" -> {
                    val focused = obj["focused"]?.jsonPrimitive?.booleanOrNull == true
                    containerDelegate.setImeFocused(focused)
                }

                "syncTheme" -> {
                    val payload = runCatching { obj["payload"]?.jsonObject }.getOrNull()
                    if (payload != null) {
                        containerDelegate.syncAppearance(
                            themeId = payload["themeId"]?.jsonPrimitive?.content ?: "coloros-glass",
                            colorScheme = payload["colorScheme"]?.jsonPrimitive?.content ?: "dark",
                            wantsNativeBlur = payload["wantsNativeBlur"]?.jsonPrimitive?.booleanOrNull != false,
                            cornerRadiusPx = payload["cornerRadiusPx"]?.jsonPrimitive?.intOrNull ?: 56,
                        )
                    }
                }

                "setPreferredSize" -> {
                    Unit
                }

                else -> {
                    Log.d(TAG, "Ignored unknown container.action type=$actionType")
                }
            }

            val ok =
                RpcResponse(
                    id = req.id,
                    result = buildJsonObject { put("ok", JsonPrimitive(true)) },
                )
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
            if (pendingEventsBeforeReady.size >= 16) {
                pendingEventsBeforeReady.removeFirst()
            }
            pendingEventsBeforeReady.addLast(rawJson)
            return
        }

        val port = activePort
        if (port != null) {
            runCatching {
                port.postMessage(WebMessageCompat(rawJson))
                return
            }.onFailure { err ->
                Log.w(TAG, "WebMessagePort postMessage failed, falling back to evaluateJavascript", err)
            }
        }

        val quoted = JSONObject.quote(rawJson)
        webView.evaluateJavascript("window.postMessage($quoted, '*');", null)
    }

    private fun openPanelAsset(relativePath: String): WebResourceResponse {
        val normalized =
            relativePath
                .trimStart('/')
                .ifEmpty { "index.html" }
                .takeIf { !it.contains("..") }
                ?: "index.html"

        val assetPath = "panel/$normalized"
        return try {
            val stream = context.assets.open(assetPath)
            WebResourceResponse(mimeTypeFor(normalized), "UTF-8", stream)
        } catch (_: IOException) {
            // Fall back cleanly to panel/index.html for non-file subpaths.
            runCatching {
                val indexStream = context.assets.open("panel/index.html")
                WebResourceResponse("text/html", "UTF-8", indexStream)
            }.getOrElse {
                val empty = ByteArrayInputStream("Not Found".toByteArray())
                WebResourceResponse("text/plain", "UTF-8", 404, "Not Found", emptyMap(), empty)
            }
        }
    }

    private fun mimeTypeFor(path: String): String =
        when {
            path.endsWith(".html") -> "text/html"
            path.endsWith(".js") || path.endsWith(".mjs") -> "application/javascript"
            path.endsWith(".css") -> "text/css"
            path.endsWith(".svg") -> "image/svg+xml"
            path.endsWith(".json") -> "application/json"
            path.endsWith(".png") -> "image/png"
            path.endsWith(".woff2") -> "font/woff2"
            else -> "application/octet-stream"
        }

    inner class JsHostObject {
        @JavascriptInterface
        fun postMessage(message: String) {
            handleMessageFromWebView(message)
        }

        @JavascriptInterface
        fun getVersion(): String = "0.1.0"

        @JavascriptInterface
        fun getPluginId(): String = "sideveil.diagnostic"
    }

    companion object {
        private const val TAG = "SideveilWebBridge"
        private const val APP_DOMAIN = "appassets.androidplatform.net"
        private const val APP_ORIGIN = "https://$APP_DOMAIN"
        private const val PANEL_PREFIX = "/panel/"
        private const val PANEL_ENTRY_URL = "$APP_ORIGIN${PANEL_PREFIX}index.html"
    }
}
