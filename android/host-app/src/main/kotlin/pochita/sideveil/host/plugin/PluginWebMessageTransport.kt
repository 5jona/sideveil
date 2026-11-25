package pochita.sideveil.host.plugin

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.WebView
import androidx.core.net.toUri
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.ScriptHandler
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebMessagePortCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import pochita.sideveil.host.service.DaemonClient
import pochita.sideveil.protocol.RpcRequest
import pochita.sideveil.protocol.RpcResponse
import java.util.concurrent.atomic.AtomicBoolean

internal class BridgeRuntimeHooks(
    val postOnMain: (Runnable) -> Unit,
    val clearMainCallbacks: () -> Unit,
    val onWarnLog: (String) -> Unit,
    val forwardDaemonRpc: suspend (RpcRequest) -> RpcResponse,
) {
    companion object {
        fun forAndroid(
            daemonClient: DaemonClient,
            logTag: String,
        ): BridgeRuntimeHooks {
            val handler = Handler(Looper.getMainLooper())
            return BridgeRuntimeHooks(
                postOnMain = handler::post,
                clearMainCallbacks = { handler.removeCallbacksAndMessages(null) },
                onWarnLog = { msg -> Log.w(logTag, msg) },
                forwardDaemonRpc = daemonClient::forwardRpc,
            )
        }
    }
}

/**
 * Abstraction over low-level WebView message delivery so [PluginWebMessageBridge] can be tested
 * deterministically on the JVM without Android `WebView` or Robolectric dependencies.
 */
internal interface PluginMessageTransport {
    fun installListener(onMessage: (data: String?, sourceOrigin: String, isMainFrame: Boolean, activateReplyProxy: () -> Unit) -> Unit)

    fun clearReplyProxy()

    fun installWebMessagePort(
        onPortMessage: (String) -> Unit,
        onReady: (BridgeDeliveryChannel) -> Unit,
    )

    fun deliver(
        rawJson: String,
        channel: BridgeDeliveryChannel,
    )

    fun closeActivePort()

    fun destroy()
}

/**
 * Low-level Chromium `WebMessageListener`, document-start attach script, `WebMessagePort`,
 * and origin-pinned `postWebMessage` transport owned by [PluginWebMessageBridge].
 */
internal class PluginWebMessageTransport(
    private val webView: WebView,
    private val pluginOrigin: String,
    private val pluginIdProvider: () -> String,
    private val isDestroyed: AtomicBoolean,
    private val channelState: PluginBridgeChannelState,
) : PluginMessageTransport {
    @Volatile
    private var activePort: WebMessagePortCompat? = null

    @Volatile
    private var activeReplyProxy: JavaScriptReplyProxy? = null

    @Volatile
    private var documentStartScript: ScriptHandler? = null

    @Volatile
    private var listenerRegistered: Boolean = false

    override fun installListener(
        onMessage: (data: String?, sourceOrigin: String, isMainFrame: Boolean, activateReplyProxy: () -> Unit) -> Unit,
    ) {
        if (isDestroyed.get() || listenerRegistered) return
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            runCatching {
                WebViewCompat.addWebMessageListener(
                    webView,
                    PluginBridgePolicy.HOST_OBJECT_NAME,
                    setOf(pluginOrigin),
                ) { _, message, sourceOrigin, isMainFrame, replyProxy ->
                    onMessage(message.data, sourceOrigin.toString(), isMainFrame) {
                        activeReplyProxy = replyProxy
                    }
                }
                listenerRegistered = true
            }.onFailure { err ->
                Log.w(TAG, "addWebMessageListener failed for ${pluginIdProvider()}: ${err.message}")
            }
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            runCatching {
                documentStartScript =
                    WebViewCompat.addDocumentStartJavaScript(
                        webView,
                        PluginBridgePolicy.DOCUMENT_START_ATTACH_SCRIPT,
                        setOf(pluginOrigin),
                    )
            }.onFailure { err ->
                Log.w(TAG, "addDocumentStartJavaScript failed for ${pluginIdProvider()}: ${err.message}")
            }
        }
    }

    override fun clearReplyProxy() {
        activeReplyProxy = null
    }

    override fun installWebMessagePort(
        onPortMessage: (String) -> Unit,
        onReady: (BridgeDeliveryChannel) -> Unit,
    ) {
        if (isDestroyed.get() || channelState.listenerChannelReady) return
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.CREATE_WEB_MESSAGE_CHANNEL) ||
            !WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_PORT_SET_MESSAGE_CALLBACK) ||
            !WebViewFeature.isFeatureSupported(WebViewFeature.POST_WEB_MESSAGE)
        ) {
            channelState.onFallbackWindowPostReady()
            onReady(BridgeDeliveryChannel.POST_WEB_MESSAGE)
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
            if (isDestroyed.get()) {
                closePortsQuietly(hostPort, pagePort)
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
                            if (isDestroyed.get()) return
                            val data = message?.data ?: return
                            onPortMessage(data)
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
            channelState.onWebMessagePortAttached()
            onReady(BridgeDeliveryChannel.WEB_MESSAGE_PORT)
        }.onFailure { err ->
            if (isDestroyed.get()) return
            Log.w(TAG, "WebMessagePort setup failed for ${pluginIdProvider()}: ${err.message}")
            channelState.onFallbackWindowPostReady()
            onReady(BridgeDeliveryChannel.POST_WEB_MESSAGE)
        }
    }

    override fun deliver(
        rawJson: String,
        channel: BridgeDeliveryChannel,
    ) {
        if (isDestroyed.get()) return
        when (channel) {
            BridgeDeliveryChannel.REPLY_PROXY -> {
                val proxy = activeReplyProxy
                if (proxy != null && WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
                    runCatching {
                        proxy.postMessage(rawJson)
                        return
                    }.onFailure { err ->
                        Log.w(TAG, "JavaScriptReplyProxy postMessage failed for ${pluginIdProvider()}", err)
                    }
                }
                deliverOriginPinnedWebMessage(rawJson)
            }

            BridgeDeliveryChannel.WEB_MESSAGE_PORT -> {
                val port = activePort
                if (port != null && WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_PORT_POST_MESSAGE)) {
                    runCatching {
                        port.postMessage(WebMessageCompat(rawJson))
                        return
                    }.onFailure { err ->
                        if (isDestroyed.get()) return
                        Log.w(TAG, "WebMessagePort postMessage failed for ${pluginIdProvider()}", err)
                    }
                }
                deliverOriginPinnedWebMessage(rawJson)
            }

            BridgeDeliveryChannel.POST_WEB_MESSAGE -> {
                deliverOriginPinnedWebMessage(rawJson)
            }
        }
    }

    override fun closeActivePort() {
        val port = activePort ?: return
        activePort = null
        channelState.onWebMessagePortClosed()
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_PORT_CLOSE)) {
            runCatching { port.close() }
        }
    }

    override fun destroy() {
        activeReplyProxy = null
        closeActivePort()
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            documentStartScript?.let { handler -> runCatching { handler.remove() } }
        }
        documentStartScript = null
        if (listenerRegistered && WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            runCatching { WebViewCompat.removeWebMessageListener(webView, PluginBridgePolicy.HOST_OBJECT_NAME) }
            listenerRegistered = false
        }
    }

    private fun deliverOriginPinnedWebMessage(rawJson: String) {
        if (isDestroyed.get()) return
        if (WebViewFeature.isFeatureSupported(WebViewFeature.POST_WEB_MESSAGE)) {
            runCatching {
                WebViewCompat.postWebMessage(webView, WebMessageCompat(rawJson), pluginOrigin.toUri())
            }.onFailure { err ->
                Log.w(TAG, "Origin-pinned postWebMessage failed for ${pluginIdProvider()}: ${err.message}")
            }
        }
    }

    private fun closePortsQuietly(
        hostPort: WebMessagePortCompat,
        pagePort: WebMessagePortCompat,
    ) {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_PORT_CLOSE)) {
            runCatching {
                hostPort.close()
                pagePort.close()
            }
        }
    }

    companion object {
        private const val TAG = "SideVeilPluginBridge"
    }
}
