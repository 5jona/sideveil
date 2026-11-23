package pochita.sideveil.host

import android.graphics.Bitmap
import android.net.http.SslError
import android.util.Log
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.net.toUri
import pochita.sideveil.protocol.HostWebViewErrorPayload
import pochita.sideveil.protocol.PluginDescriptor
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Pure-Kotlin diagnostic URL sanitizer stripping query parameters, fragments, and userinfo
 * so error events carry only safe `scheme://host[:port]/path` locations.
 */
internal object WebViewDiagnosticsSanitizer {
    private const val MAX_URL_LEN = 240
    const val MAX_DESCRIPTION_LEN = 240

    fun sanitizeUrl(rawUrl: String?): String {
        val trimmed = rawUrl?.trim().orEmpty()
        if (trimmed.isEmpty()) return ""
        val withoutQuery = trimmed.substringBefore('?').substringBefore('#')
        val parsed = runCatching { URI(withoutQuery) }.getOrNull()
        if (parsed != null && !parsed.scheme.isNullOrBlank() && !parsed.host.isNullOrBlank()) {
            val portPart = if (parsed.port > 0) ":${parsed.port}" else ""
            val pathPart = parsed.rawPath.orEmpty()
            return "${parsed.scheme}://${parsed.host}$portPart$pathPart".take(MAX_URL_LEN)
        }
        return withoutQuery.take(MAX_URL_LEN)
    }
}

/**
 * Thread-safe cumulative WebView render-process crash counter shared across recovery cycles.
 */
internal class WebViewCrashRegistry {
    private val countsByPlugin = ConcurrentHashMap<String, AtomicInteger>()

    fun recordCrash(pluginId: String): Int = countsByPlugin.computeIfAbsent(pluginId) { AtomicInteger(0) }.incrementAndGet()

    fun countFor(pluginId: String): Int = countsByPlugin[pluginId]?.get() ?: 0

    fun totalCrashes(): Int = countsByPlugin.values.sumOf { it.get() }
}

/**
 * Isolated [WebViewClient] for a single plugin sandbox enforcing virtual origin / network ACLs,
 * reporting network/HTTP/SSL errors via `host.webviewError` (canceling SSL errors safely),
 * and delegating render-process crash reporting and recovery.
 */
internal class PluginWebViewClient(
    private val isUntrustedEmbed: Boolean,
    private val descriptorProvider: () -> PluginDescriptor,
    private val assetFetcher: PluginAssetFetcher,
    private val messageBridge: PluginWebMessageBridge?,
    private val dormancy: WebViewDormancyController,
    private val onWebViewError: (HostWebViewErrorPayload) -> Unit,
    private val onRenderProcessGoneEvent: (didCrash: Boolean, rendererPriorityAtExit: Int) -> Unit,
    private val onMainPageFinished: (url: String?) -> Unit = {},
) : WebViewClient() {
    override fun shouldOverrideUrlLoading(
        view: WebView,
        request: WebResourceRequest,
    ): Boolean {
        val descriptor = descriptorProvider()
        val allowed =
            (!isUntrustedEmbed && assetFetcher.isAllowedPluginUri(request.url, descriptor.id)) ||
                PluginNetworkPolicy.shouldAllowNativeNetwork(descriptor, request.url, isMainFrame = true)
        return !allowed
    }

    override fun shouldInterceptRequest(
        view: WebView,
        request: WebResourceRequest,
    ): WebResourceResponse? {
        val descriptor = descriptorProvider()
        return when {
            !isUntrustedEmbed && assetFetcher.isAllowedPluginUri(request.url, descriptor.id) -> {
                assetFetcher.fetch(descriptor, request.url, isMainFrame = request.isForMainFrame)
            }

            PluginNetworkPolicy.shouldAllowNativeNetwork(descriptor, request.url, request.isForMainFrame) -> {
                null
            }

            else -> {
                assetFetcher.forbiddenResponse()
            }
        }
    }

    override fun onPageStarted(
        view: WebView,
        url: String?,
        favicon: Bitmap?,
    ) {
        super.onPageStarted(view, url, favicon)
        messageBridge?.onNavigationCommitted(url)
    }

    override fun onPageFinished(
        view: WebView,
        url: String?,
    ) {
        super.onPageFinished(view, url)
        val descriptor = descriptorProvider()
        messageBridge?.let { bridge ->
            bridge.onNavigationCommitted(url)
            if (url != null && assetFetcher.isAllowedPluginUri(url.toUri(), descriptor.id)) {
                bridge.installChannel()
            }
        }
        dormancy.onPageFinished()
        onMainPageFinished(url)
    }

    override fun onReceivedError(
        view: WebView,
        request: WebResourceRequest,
        error: WebResourceError,
    ) {
        super.onReceivedError(view, request, error)
        val descriptor = descriptorProvider()
        val sanitizedUrl = WebViewDiagnosticsSanitizer.sanitizeUrl(request.url?.toString())
        val desc =
            error.description
                ?.toString()
                .orEmpty()
                .take(WebViewDiagnosticsSanitizer.MAX_DESCRIPTION_LEN)
        Log.w(
            TAG,
            "WebView network error plugin=${descriptor.id} code=${error.errorCode} " +
                "mainFrame=${request.isForMainFrame} url=$sanitizedUrl desc=$desc",
        )
        onWebViewError(
            HostWebViewErrorPayload(
                pluginId = descriptor.id,
                kind = "network",
                code = error.errorCode,
                description = desc,
                url = sanitizedUrl,
                isMainFrame = request.isForMainFrame,
            ),
        )
    }

    override fun onReceivedHttpError(
        view: WebView,
        request: WebResourceRequest,
        errorResponse: WebResourceResponse,
    ) {
        super.onReceivedHttpError(view, request, errorResponse)
        val descriptor = descriptorProvider()
        val sanitizedUrl = WebViewDiagnosticsSanitizer.sanitizeUrl(request.url?.toString())
        val desc = errorResponse.reasonPhrase.orEmpty().take(WebViewDiagnosticsSanitizer.MAX_DESCRIPTION_LEN)
        Log.w(
            TAG,
            "WebView HTTP error plugin=${descriptor.id} status=${errorResponse.statusCode} " +
                "mainFrame=${request.isForMainFrame} url=$sanitizedUrl reason=$desc",
        )
        onWebViewError(
            HostWebViewErrorPayload(
                pluginId = descriptor.id,
                kind = "http",
                code = errorResponse.statusCode,
                description = desc,
                url = sanitizedUrl,
                isMainFrame = request.isForMainFrame,
            ),
        )
    }

    override fun onReceivedSslError(
        view: WebView,
        handler: SslErrorHandler,
        error: SslError,
    ) {
        // Always cancel SSL errors safely; never proceed past TLS validation failures.
        handler.cancel()
        val descriptor = descriptorProvider()
        val sanitizedUrl = WebViewDiagnosticsSanitizer.sanitizeUrl(error.url)
        val desc = "SSL primaryError=${error.primaryError}"
        val isMain = view.url == null || error.url == view.url
        Log.w(TAG, "WebView SSL error plugin=${descriptor.id} $desc mainFrame=$isMain url=$sanitizedUrl")
        onWebViewError(
            HostWebViewErrorPayload(
                pluginId = descriptor.id,
                kind = "ssl",
                code = error.primaryError,
                description = desc,
                url = sanitizedUrl,
                isMainFrame = isMain,
            ),
        )
    }

    override fun onRenderProcessGone(
        view: WebView,
        detail: RenderProcessGoneDetail,
    ): Boolean {
        val descriptor = descriptorProvider()
        Log.e(
            TAG,
            "WebView render process gone for plugin=${descriptor.id} " +
                "didCrash=${detail.didCrash()} priority=${detail.rendererPriorityAtExit()}",
        )
        onRenderProcessGoneEvent(detail.didCrash(), detail.rendererPriorityAtExit())
        return true
    }

    companion object {
        private const val TAG = "SideVeilPluginWebView"
    }
}
