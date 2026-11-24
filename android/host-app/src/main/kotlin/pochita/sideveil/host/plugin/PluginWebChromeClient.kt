package pochita.sideveil.host.plugin

import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.GeolocationPermissions
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebView
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.encodeToJsonElement
import pochita.sideveil.host.service.DaemonClient
import pochita.sideveil.protocol.ContainerActionPayload
import pochita.sideveil.protocol.HostWebViewConsolePayload
import pochita.sideveil.protocol.HostWebViewCrashPayload
import pochita.sideveil.protocol.HostWebViewErrorPayload
import pochita.sideveil.protocol.PluginDescriptor
import pochita.sideveil.protocol.SideVeilEvents
import pochita.sideveil.protocol.SideVeilWireJson

/**
 * Immutable host runtime dependencies shared across [PluginWebViewInstance] sandboxes.
 */
internal class PluginWebViewDeps(
    val scope: CoroutineScope,
    val daemonClient: DaemonClient,
    val localRpcHandler: PluginLocalRpcHandler,
    val assetFetcher: PluginAssetFetcher,
    val crashRegistry: WebViewCrashRegistry,
) {
    fun createMessageBridgeIfTrusted(
        isUntrustedEmbed: Boolean,
        webView: WebView,
        initialPluginId: String,
        descriptorProvider: () -> PluginDescriptor,
        isForegroundVisible: () -> Boolean,
        onContainerAction: (ContainerActionPayload?) -> ContainerActionResult,
    ): PluginWebMessageBridge? {
        if (isUntrustedEmbed) return null
        return PluginWebMessageBridge(
            scope = scope,
            daemonClient = daemonClient,
            localRpcHandler = localRpcHandler,
            webView = webView,
            pluginOrigin = assetFetcher.originFor(initialPluginId),
            descriptorProvider = descriptorProvider,
            isForegroundVisible = isForegroundVisible,
            onContainerAction = onContainerAction,
        )
    }

    fun emitWebViewError(payload: HostWebViewErrorPayload) {
        daemonClient.sendEvent(
            SideVeilEvents.HOST_WEBVIEW_ERROR,
            SideVeilWireJson.encodeToJsonElement(payload),
        )
    }

    fun recordAndEmitCrash(
        pluginId: String,
        didCrash: Boolean,
        rendererPriorityAtExit: Int,
    ): Int {
        val nextCount = crashRegistry.recordCrash(pluginId)
        daemonClient.sendEvent(
            SideVeilEvents.HOST_WEBVIEW_CRASH,
            SideVeilWireJson.encodeToJsonElement(
                HostWebViewCrashPayload(
                    pluginId = pluginId,
                    didCrash = didCrash,
                    rendererPriorityAtExit = rendererPriorityAtExit,
                    crashCount = nextCount,
                ),
            ),
        )
        return nextCount
    }
}

/**
 * Sandboxed [WebChromeClient] for plugin WebViews forwarding JS/WebGPU console diagnostics to
 * `sideveild` (`host.webviewConsole`), canceling modal JS dialogs safely in a headless `Service`
 * context, and enforcing least-privilege hardware/geolocation denial.
 */
internal class PluginWebChromeClient(
    private val pluginIdProvider: () -> String,
    private val daemonClient: DaemonClient,
    private val isUntrustedEmbed: Boolean = false,
) : WebChromeClient() {
    override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
        val msg = consoleMessage ?: return false
        val level = msg.messageLevel()?.name?.lowercase() ?: "info"
        val pluginId = pluginIdProvider()
        val text = msg.message().orEmpty().take(MAX_MESSAGE_LEN)
        if (!WebViewErrorReportPolicy.shouldForwardConsoleToDaemon(isUntrustedEmbed)) {
            when (msg.messageLevel()) {
                ConsoleMessage.MessageLevel.ERROR, ConsoleMessage.MessageLevel.WARNING -> {
                    Log.w(TAG, "[embed:$pluginId][$level] $text")
                }

                else -> {
                    Log.d(TAG, "[embed:$pluginId][$level] $text")
                }
            }
            return true
        }
        when (msg.messageLevel()) {
            ConsoleMessage.MessageLevel.ERROR -> Log.e(TAG, "[$pluginId][$level] $text")
            ConsoleMessage.MessageLevel.WARNING -> Log.w(TAG, "[$pluginId][$level] $text")
            else -> Unit
        }
        daemonClient.sendEvent(
            SideVeilEvents.HOST_WEBVIEW_CONSOLE,
            SideVeilWireJson.encodeToJsonElement(
                HostWebViewConsolePayload(
                    pluginId = pluginId,
                    level = level,
                    message = text,
                    sourceId = msg.sourceId().orEmpty().take(MAX_SOURCE_ID_LEN),
                    lineNumber = msg.lineNumber(),
                ),
            ),
        )
        return true
    }

    override fun onPermissionRequest(request: PermissionRequest?) {
        request?.deny()
    }

    override fun onGeolocationPermissionsShowPrompt(
        origin: String?,
        callback: GeolocationPermissions.Callback?,
    ) {
        callback?.invoke(origin, false, false)
    }

    override fun onJsAlert(
        view: WebView?,
        url: String?,
        message: String?,
        result: JsResult?,
    ): Boolean {
        Log.d(TAG, "Suppressed window.alert from plugin=${pluginIdProvider()}")
        result?.cancel()
        return true
    }

    override fun onJsConfirm(
        view: WebView?,
        url: String?,
        message: String?,
        result: JsResult?,
    ): Boolean {
        Log.d(TAG, "Suppressed window.confirm from plugin=${pluginIdProvider()}")
        result?.cancel()
        return true
    }

    override fun onJsPrompt(
        view: WebView?,
        url: String?,
        message: String?,
        defaultValue: String?,
        result: JsPromptResult?,
    ): Boolean {
        Log.d(TAG, "Suppressed window.prompt from plugin=${pluginIdProvider()}")
        result?.cancel()
        return true
    }

    override fun onJsBeforeUnload(
        view: WebView?,
        url: String?,
        message: String?,
        result: JsResult?,
    ): Boolean {
        result?.cancel()
        return true
    }

    companion object {
        private const val TAG = "SideVeilWebConsole"
        private const val MAX_MESSAGE_LEN = 1_024
        private const val MAX_SOURCE_ID_LEN = 240
    }
}
