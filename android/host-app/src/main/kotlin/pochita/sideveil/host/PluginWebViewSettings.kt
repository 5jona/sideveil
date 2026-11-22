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

/**
 * Shared WebView configuration, cookie policy, and initial offscreen layout helpers
 * for [PluginWebViewInstance].
 */
internal object PluginWebViewSettings {
    private const val TAG = "SideVeilPluginWebView"

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
        val initW = ((descriptor.surface.widthDp ?: 340) * dm.density).toInt().coerceAtLeast(320)
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
        val embed = target.embed
        val allowZoom = embed?.allowZoom ?: false
        val wideViewport = embed?.wideViewport ?: false
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
            useWideViewPort = wideViewport
            loadWithOverviewMode = wideViewport
            textZoom = 100
            setSupportZoom(allowZoom)
            builtInZoomControls = allowZoom
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
