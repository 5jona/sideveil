package pochita.sideveil.host

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.net.toUri
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.JsonElement
import pochita.sideveil.protocol.ContainerActionPayload
import pochita.sideveil.protocol.HostEventEnvelope
import pochita.sideveil.protocol.PluginDescriptor
import pochita.sideveil.protocol.PluginSurfaceBackdrop
import pochita.sideveil.protocol.SideveilWireJson

/**
 * Isolated WebView sandbox instance for a single Sideveil plugin under
 * `https://<pluginId>.plugins.sideveil.local/` with `onRenderProcessGone` crash recovery,
 * per-plugin container preferences, and [PluginWebMessageBridge] IPC routing.
 */
@SuppressLint("SetJavaScriptEnabled")
class PluginWebViewInstance(
    context: Context,
    scope: CoroutineScope,
    daemonClient: DaemonClient,
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

    private val messageBridge =
        PluginWebMessageBridge(
            context = context,
            scope = scope,
            daemonClient = daemonClient,
            webView = webView,
            pluginOrigin = assetFetcher.originFor(initialDescriptor.id),
            descriptorProvider = { descriptor },
            onContainerAction = ::applyContainerAction,
        )

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

        webView.addJavascriptInterface(messageBridge.createJsHostObject(), "__POCHITA_HOST__")
        webView.webViewClient = createWebViewClient()
    }

    fun updateDescriptor(next: PluginDescriptor) {
        descriptor = next
    }

    fun loadEntry() {
        messageBridge.resetBeforeLoad()
        webView.loadUrl(assetFetcher.entryUrlFor(descriptor))
    }

    fun pushEvent(
        event: String,
        payload: JsonElement?,
    ) {
        val raw = SideveilWireJson.encodeToString(HostEventEnvelope(event = event, payload = payload))
        messageBridge.postRawAsync(raw)
    }

    fun pushEnvelope(envelope: HostEventEnvelope) {
        messageBridge.postRawAsync(SideveilWireJson.encodeToString(envelope))
    }

    fun destroy() {
        messageBridge.closeActivePort()
        webView.stopLoading()
        webView.destroy()
    }

    private fun createWebViewClient(): WebViewClient =
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
                    messageBridge.installChannel()
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

    companion object {
        private const val TAG = "SideveilPluginWebView"
    }
}
