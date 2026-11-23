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
 * Coordinates untrusted web-embed URL routing (LAN vs remote fallback), single-shot error
 * recovery, quick-bar actions, and status notifications on behalf of [PluginWebViewInstance].
 */
internal class EmbedSessionController(
    private val webView: WebView,
    private val mainHandler: Handler,
    initialNetworkRoute: NetworkRouteContext = NetworkRouteContext(),
) {
    @Volatile
    private var latestNetworkRoute: NetworkRouteContext = initialNetworkRoute

    @Volatile
    private var fallbackAttemptedForUrl: String? = null

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
        fallbackAttemptedForUrl = null
        notifyStatus(null, isError = false)
        val preferred = EmbedUrlRouter.preferredBaseUrl(embed, latestNetworkRoute)
        val targetUrl =
            restoreUrl
                ?.takeIf { PluginNetworkPolicy.shouldAllowNativeNetwork(descriptor, it, isMainFrame = true) }
                ?: preferred
        if (PluginNetworkPolicy.shouldAllowNativeNetwork(descriptor, targetUrl, isMainFrame = true)) {
            webView.loadUrl(targetUrl)
        } else {
            Log.w(TAG, "Blocked disallowed embed entry URL for plugin=${descriptor.id}: $targetUrl")
            webView.loadUrl("about:blank")
        }
    }

    fun updateNetworkRoute(
        descriptor: PluginDescriptor,
        network: NetworkRouteContext,
    ) {
        latestNetworkRoute = network
        val embed = descriptor.embed ?: return
        val preferred = EmbedUrlRouter.preferredBaseUrl(embed, network)
        val switchUrl = EmbedUrlRouter.resolveSwitchUrl(webView.url, preferred) ?: return
        if (!PluginNetworkPolicy.shouldAllowNativeNetwork(descriptor, switchUrl, isMainFrame = true)) return
        fallbackAttemptedForUrl = null
        notifyStatus("网络切换，正在重连…", isError = false)
        webView.loadUrl(switchUrl)
    }

    fun handleMainFrameNetworkError(
        descriptor: PluginDescriptor,
        failedUrl: String?,
    ) {
        val embed = descriptor.embed ?: return
        val fallback = EmbedUrlRouter.fallbackBaseUrl(embed, failedUrl)
        if (fallback != null && fallbackAttemptedForUrl != failedUrl) {
            fallbackAttemptedForUrl = failedUrl
            val switchUrl = EmbedUrlRouter.resolveSwitchUrl(failedUrl, fallback) ?: fallback
            if (PluginNetworkPolicy.shouldAllowNativeNetwork(descriptor, switchUrl, isMainFrame = true)) {
                mainHandler.post {
                    notifyStatus("主地址不可达，已切换备选地址", isError = false)
                    webView.loadUrl(switchUrl)
                }
                return
            }
        }
        notifyStatus("连接异常，点击刷新重试", isError = true)
    }

    fun handleMainPageFinished(url: String?) {
        if (!url.isNullOrBlank() && url != "about:blank") {
            notifyStatus(null, isError = false)
        }
    }

    fun reload(descriptor: PluginDescriptor) {
        fallbackAttemptedForUrl = null
        notifyStatus(null, isError = false)
        val current = webView.url
        if (current.isNullOrBlank() || current == "about:blank") {
            loadInitial(descriptor)
        } else {
            webView.reload()
        }
    }

    fun navigateHome(descriptor: PluginDescriptor) {
        val embed = descriptor.embed ?: return
        fallbackAttemptedForUrl = null
        notifyStatus(null, isError = false)
        val target = EmbedUrlRouter.preferredBaseUrl(embed, latestNetworkRoute)
        if (PluginNetworkPolicy.shouldAllowNativeNetwork(descriptor, target, isMainFrame = true)) {
            webView.loadUrl(target)
        }
    }

    fun currentExternalUrl(descriptor: PluginDescriptor): String? {
        val live = webView.url?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
        return live ?: descriptor.embed?.let { EmbedUrlRouter.preferredBaseUrl(it, latestNetworkRoute) }
    }

    fun triggerOnlineReconnect() {
        mainHandler.post {
            runCatching {
                webView.evaluateJavascript("window.dispatchEvent(new Event('online'));", null)
            }
        }
    }

    private fun notifyStatus(
        message: String?,
        isError: Boolean,
    ) {
        mainHandler.post { statusListener?.invoke(message, isError) }
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
