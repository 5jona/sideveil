package pochita.sideveil.host.plugin

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.util.Log
import android.webkit.WebView
import androidx.core.net.toUri
import pochita.sideveil.protocol.PluginDescriptor
import pochita.sideveil.protocol.PluginEmbedPrefs

/**
 * Minimal WebView + main-thread port used by [EmbedSessionController] so routing and reload
 * behavior can be verified in pure-Kotlin JVM unit tests without Robolectric.
 */
internal interface EmbedWebViewPort {
    val currentUrl: String?

    fun loadUrl(url: String)

    fun reload()

    fun evaluateJavascript(script: String)

    fun postToMain(action: () -> Unit)
}

/**
 * Coordinates untrusted web-embed URL routing (LAN vs remote fallback), single-shot error
 * recovery, quick-bar actions, and status notifications on behalf of [PluginWebViewInstance].
 */
internal class EmbedSessionController(
    private val webViewPort: EmbedWebViewPort,
    initialNetworkRoute: NetworkRouteContext = NetworkRouteContext(),
) {
    constructor(
        webView: WebView,
        mainHandler: Handler,
        initialNetworkRoute: NetworkRouteContext = NetworkRouteContext(),
    ) : this(
        webViewPort =
            object : EmbedWebViewPort {
                override val currentUrl: String?
                    get() = webView.url

                override fun loadUrl(url: String) = webView.loadUrl(url)

                override fun reload() = webView.reload()

                override fun evaluateJavascript(script: String) {
                    webView.evaluateJavascript(script, null)
                }

                override fun postToMain(action: () -> Unit) {
                    mainHandler.post(action)
                }
            },
        initialNetworkRoute = initialNetworkRoute,
    )

    private val routingState = EmbedStickyFallbackState(initialNetworkRoute)

    @Volatile
    private var statusListener: ((String?, Boolean) -> Unit)? = null

    fun bindStatusListener(listener: ((String?, Boolean) -> Unit)?) {
        statusListener = listener
    }

    fun resolveToolbarBinding(
        context: Context,
        descriptor: PluginDescriptor,
        isUntrustedEmbed: Boolean,
        onDismissPanel: () -> Unit,
    ): EmbedToolbarBinding? {
        val embed = descriptor.embed
        return if (isUntrustedEmbed && embed != null && embed.showToolbar) {
            EmbedToolbarBinding(
                title = descriptor.name,
                onReload = { reload(descriptor) },
                onHome = { navigateHome(descriptor) },
                onOpenExternal = {
                    openExternalBrowserUrl(context, descriptor.id, currentExternalUrl(descriptor), onDismissPanel)
                },
                bindStatusListener = ::bindStatusListener,
            )
        } else {
            bindStatusListener(null)
            null
        }
    }

    fun loadInitial(
        descriptor: PluginDescriptor,
        restoreUrl: String? = null,
    ) {
        val embed = descriptor.embed ?: return
        routingState.reset()
        notifyStatus(null, isError = false)
        val preferred = EmbedUrlRouter.preferredBaseUrl(embed, routingState.latestNetworkRoute)
        val alignedRestore =
            restoreUrl
                ?.takeIf { PluginNetworkPolicy.shouldAllowNativeNetwork(descriptor, it, isMainFrame = true) }
                ?.let { allowed -> EmbedUrlRouter.resolveSwitchUrl(allowed, preferred) ?: allowed }
        val targetUrl = alignedRestore ?: preferred
        if (PluginNetworkPolicy.shouldAllowNativeNetwork(descriptor, targetUrl, isMainFrame = true)) {
            webViewPort.loadUrl(targetUrl)
        } else {
            Log.w(TAG, "Blocked disallowed embed entry URL for plugin=${descriptor.id}: $targetUrl")
            webViewPort.loadUrl("about:blank")
        }
    }

    fun updateNetworkRoute(
        descriptor: PluginDescriptor,
        network: NetworkRouteContext,
    ) {
        val embed = descriptor.embed ?: return
        val switchUrl = routingState.resolveNetworkRouteSwitch(embed, network, webViewPort.currentUrl) ?: return
        if (!PluginNetworkPolicy.shouldAllowNativeNetwork(descriptor, switchUrl, isMainFrame = true)) return
        notifyStatus("网络切换，正在重连…", isError = false)
        webViewPort.loadUrl(switchUrl)
    }

    fun handleMainFrameNetworkError(
        descriptor: PluginDescriptor,
        failedUrl: String?,
    ): Boolean {
        val embed = descriptor.embed ?: return false
        val switchUrl = routingState.resolveFallbackAfterMainFrameError(embed, failedUrl)
        if (switchUrl != null && PluginNetworkPolicy.shouldAllowNativeNetwork(descriptor, switchUrl, isMainFrame = true)) {
            webViewPort.postToMain {
                notifyStatus("主地址不可达，已切换备选地址", isError = false)
                webViewPort.loadUrl(switchUrl)
            }
            return true
        }
        notifyStatus("连接异常，点击刷新重试", isError = true)
        return false
    }

    fun handleMainPageFinished(url: String?) {
        if (!url.isNullOrBlank() && url != "about:blank") {
            routingState.onPageLoadSucceeded()
            notifyStatus(null, isError = false)
        }
    }

    fun reload(descriptor: PluginDescriptor) {
        routingState.reset()
        notifyStatus(null, isError = false)
        val current = webViewPort.currentUrl
        if (current.isNullOrBlank() || current == "about:blank") {
            loadInitial(descriptor)
            return
        }
        val embed = descriptor.embed
        if (embed == null) {
            webViewPort.reload()
            return
        }
        val preferred = EmbedUrlRouter.preferredBaseUrl(embed, routingState.latestNetworkRoute)
        val currentOrigin = NetworkOriginParser.parseOrigin(current)
        val preferredOrigin = NetworkOriginParser.parseOrigin(preferred)
        if (currentOrigin != null && preferredOrigin != null && currentOrigin == preferredOrigin) {
            webViewPort.reload()
            return
        }
        val target = EmbedUrlRouter.resolveSwitchUrl(current, preferred) ?: preferred
        if (PluginNetworkPolicy.shouldAllowNativeNetwork(descriptor, target, isMainFrame = true)) {
            webViewPort.loadUrl(target)
        }
    }

    fun navigateHome(descriptor: PluginDescriptor) {
        val embed = descriptor.embed ?: return
        routingState.reset()
        notifyStatus(null, isError = false)
        val target = EmbedUrlRouter.preferredBaseUrl(embed, routingState.latestNetworkRoute)
        if (PluginNetworkPolicy.shouldAllowNativeNetwork(descriptor, target, isMainFrame = true)) {
            webViewPort.loadUrl(target)
        }
    }

    fun currentExternalUrl(descriptor: PluginDescriptor): String? {
        val live = webViewPort.currentUrl?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
        return live ?: descriptor.embed?.let { EmbedUrlRouter.preferredBaseUrl(it, routingState.latestNetworkRoute) }
    }

    fun triggerOnlineReconnect() {
        webViewPort.postToMain {
            runCatching {
                webViewPort.evaluateJavascript("window.dispatchEvent(new Event('online'));")
            }
        }
    }

    private fun notifyStatus(
        message: String?,
        isError: Boolean,
    ) {
        webViewPort.postToMain { statusListener?.invoke(message, isError) }
    }

    companion object {
        private const val TAG = "SideVeilEmbedSession"

        fun didEmbedRoutingChange(
            prev: PluginEmbedPrefs?,
            next: PluginEmbedPrefs?,
        ): Boolean =
            prev?.url != next?.url ||
                prev?.remoteUrl != next?.remoteUrl ||
                prev?.lanSsids != next?.lanSsids ||
                prev?.allowedOrigins != next?.allowedOrigins ||
                prev?.allowCleartext != next?.allowCleartext

        fun openExternalBrowserUrl(
            context: Context,
            pluginId: String,
            targetUrl: String?,
            onLaunched: () -> Unit,
        ) {
            if (targetUrl.isNullOrBlank()) return
            runCatching {
                val intent =
                    Intent(Intent.ACTION_VIEW, targetUrl.toUri()).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                context.startActivity(intent)
                onLaunched()
            }.onFailure { err ->
                Log.w(TAG, "Failed to open external browser for plugin=$pluginId: ${err.message}")
            }
        }
    }
}
