package pochita.sideveil.host.plugin

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.view.View
import android.webkit.WebSettings
import android.webkit.WebView
import pochita.sideveil.host.overlay.OverlayWindowFlags
import pochita.sideveil.host.panel.PanelInsetsCoordinator
import pochita.sideveil.protocol.DebugWebViewSnapshot
import pochita.sideveil.protocol.HostWebViewErrorPayload
import pochita.sideveil.protocol.PluginDescriptor
import pochita.sideveil.protocol.PluginPresentationMode
import pochita.sideveil.protocol.PluginSurfaceBackdrop

/**
 * Pure-Kotlin snapshot of WebView viewport and zoom settings derived from a [PluginDescriptor].
 */
data class WebViewSettingsSpec(
    val useWideViewPort: Boolean,
    val loadWithOverviewMode: Boolean,
    val textZoom: Int,
    val allowZoom: Boolean,
)

/**
 * Shared WebView configuration, cookie policy, descriptor diff helpers, and initial offscreen
 * layout helpers for [PluginWebViewInstance].
 */
object PluginWebViewSettings {
    private const val TAG = "SideVeilPluginWebView"

    /** Resolves pure-Kotlin [WebViewSettingsSpec] from [target]'s embed preferences. */
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

    /** Resolves initial offscreen WebView layout width capped by panel horizontal margins. */
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

    internal fun configureBaseView(
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
        PluginWebViewProfileManager.configureProfileAndCookies(webView, descriptor.id, isUntrustedEmbed)
        applyWebSettings(webView, descriptor)
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
    internal fun applyWebSettings(
        webView: WebView,
        target: PluginDescriptor,
    ) {
        val spec = resolveWebSettingsSpec(target)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            setGeolocationEnabled(false)
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
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

    internal fun didWebSettingsInputsChange(
        prev: PluginDescriptor,
        next: PluginDescriptor,
    ): Boolean =
        prev.embed != next.embed ||
            prev.network != next.network ||
            prev.capabilities != next.capabilities ||
            prev.grantedCapabilities != next.grantedCapabilities

    internal fun shouldReloadOnDescriptorChange(
        prev: PluginDescriptor,
        next: PluginDescriptor,
    ): Boolean =
        prev.entry != next.entry ||
            prev.version != next.version ||
            prev.assetRevision != next.assetRevision ||
            prev.devServerUrl != next.devServerUrl ||
            EmbedSessionController.didEmbedRoutingChange(prev.embed, next.embed) ||
            prev.network != next.network ||
            prev.grantedCapabilities != next.grantedCapabilities ||
            prev.surface.crossOriginIsolated != next.surface.crossOriginIsolated

    internal fun buildWebViewDebugSnapshot(
        webView: WebView,
        descriptor: PluginDescriptor,
        isUntrustedEmbed: Boolean,
        surfaceBackdrop: PluginSurfaceBackdrop,
        presentationMode: PluginPresentationMode,
        backInterceptEnabled: Boolean,
        foregroundPriority: Boolean,
        dormant: Boolean,
        crashCount: Int,
        isActive: Boolean,
        isPinned: Boolean,
        keepAlive: Boolean,
    ): DebugWebViewSnapshot =
        DebugWebViewSnapshot(
            pluginId = descriptor.id,
            url = webView.url,
            title = webView.title,
            canGoBack = webView.canGoBack(),
            attachedToParent = webView.parent != null,
            isActive = isActive,
            isPinned = isPinned,
            keepAlive = keepAlive,
            trustMode = if (isUntrustedEmbed) "untrusted-embed" else "trusted",
            crossOriginIsolated = descriptor.surface.crossOriginIsolated,
            devServerUrl = descriptor.devServerUrl,
            backdrop = surfaceBackdrop,
            presentationMode = presentationMode,
            backInterceptEnabled = backInterceptEnabled,
            rendererPriority = if (foregroundPriority) "important" else "bound",
            rendererWaivedWhenNotVisible = !foregroundPriority,
            dormant = dormant,
            crashCount = crashCount,
            widthPx = webView.width,
            heightPx = webView.height,
        )

    internal fun flushCookies(
        webView: WebView?,
        pluginId: String,
        isUntrustedEmbed: Boolean,
    ) = PluginWebViewProfileManager.flushCookies(webView, pluginId, isUntrustedEmbed)

    internal fun formatMainFrameErrorDetail(payload: HostWebViewErrorPayload): String {
        val codeLabel =
            when (payload.kind) {
                "http", "loopback" -> "HTTP ${payload.code}"
                "ssl" -> "SSL 错误 (${payload.code})"
                else -> "网络错误 (${payload.code})"
            }
        val desc = payload.description.trim()
        return if (desc.isNotEmpty()) "$codeLabel · $desc" else codeLabel
    }
}
