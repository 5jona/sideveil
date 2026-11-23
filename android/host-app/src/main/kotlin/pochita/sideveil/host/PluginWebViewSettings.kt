package pochita.sideveil.host

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.util.Log
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import pochita.sideveil.protocol.PluginDescriptor

internal data class WebViewSettingsSpec(
    val useWideViewPort: Boolean,
    val loadWithOverviewMode: Boolean,
    val textZoom: Int,
    val allowZoom: Boolean,
)

/**
 * Shared WebView configuration, cookie policy, and initial offscreen layout helpers
 * for [PluginWebViewInstance].
 */
internal object PluginWebViewSettings {
    private const val TAG = "SideVeilPluginWebView"

    fun resolveWebSettingsSpec(target: PluginDescriptor): WebViewSettingsSpec {
        val embed = target.embed
        val wideViewport = embed?.wideViewport ?: false
        return WebViewSettingsSpec(
            useWideViewPort = wideViewport,
            loadWithOverviewMode = wideViewport,
            textZoom = (embed?.textZoom ?: 100).coerceIn(50, 200),
            allowZoom = embed?.allowZoom ?: false,
        )
    }

    fun resolveInitialWidthPx(
        screenWidthPx: Int,
        density: Float,
        surfaceWidthDp: Int?,
    ): Int {
        val safeDensity = density.coerceAtLeast(1f)
        val requestedW = ((surfaceWidthDp ?: 340) * safeDensity).toInt().coerceAtLeast(320)
        val metrics = PanelInsetsCoordinator.resolveHorizontalMetrics(screenWidthPx, safeDensity)
        val reservedPx = OverlayWindowFlags.dp(safeDensity, (metrics.outerHorizontalMarginTotalDp + metrics.railInsetDp).toFloat())
        val compactCapW = (screenWidthPx - reservedPx).coerceAtLeast(320)
        return minOf(requestedW, compactCapW)
    }

    fun configureBaseView(
        context: Context,
        webView: WebView,
        descriptor: PluginDescriptor,
        isUntrustedEmbed: Boolean,
    ) {
        webView.setBackgroundColor(Color.TRANSPARENT)
        webView.isFocusable = true
        webView.isFocusableInTouchMode = true
        webView.overScrollMode = WebView.OVER_SCROLL_NEVER
        webView.isVerticalScrollBarEnabled = false
        webView.isHorizontalScrollBarEnabled = false
        applyWebSettings(webView, descriptor)
        configureCookies(webView, descriptor.id, isUntrustedEmbed)
        webView.setInitialScale(0)

        val dm = context.resources.displayMetrics
        val initW = resolveInitialWidthPx(dm.widthPixels, dm.density, descriptor.surface.widthDp)
        val initH = (dm.heightPixels * ((descriptor.surface.heightPercent ?: 68) / 100f)).toInt().coerceAtLeast(360)
        webView.measure(
            View.MeasureSpec.makeMeasureSpec(initW, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(initH, View.MeasureSpec.EXACTLY),
        )
        webView.layout(0, 0, initW, initH)
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun applyWebSettings(
        webView: WebView,
        target: PluginDescriptor,
    ) {
        val spec = resolveWebSettingsSpec(target)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode =
                if (PluginNetworkPolicy.shouldAllowMixedContent(target)) {
                    WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                } else {
                    WebSettings.MIXED_CONTENT_NEVER_ALLOW
                }
            useWideViewPort = spec.useWideViewPort
            loadWithOverviewMode = spec.loadWithOverviewMode
            textZoom = spec.textZoom
            setSupportZoom(spec.allowZoom)
            builtInZoomControls = spec.allowZoom
            displayZoomControls = false
        }
    }

    fun configureCookies(
        webView: WebView,
        pluginId: String,
        isUntrustedEmbed: Boolean,
    ) {
        runCatching {
            val cookies = CookieManager.getInstance()
            cookies.setAcceptCookie(true)
            cookies.setAcceptThirdPartyCookies(webView, isUntrustedEmbed)
        }.onFailure { err ->
            Log.w(TAG, "CookieManager setup failed for $pluginId: ${err.message}")
        }
    }

    fun flushCookies(pluginId: String) {
        runCatching { CookieManager.getInstance().flush() }
            .onFailure { err -> Log.w(TAG, "CookieManager.flush failed for $pluginId: ${err.message}") }
    }
}
