package pochita.sideveil.host.plugin

import android.util.Log
import android.webkit.ConsoleMessage
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
        onContainerAction: (ContainerActionPayload?) -> Unit,
    ): PluginWebMessageBridge? {
        if (isUntrustedEmbed) return null
        return PluginWebMessageBridge(
            scope = scope,
            daemonClient = daemonClient,
            localRpcHandler = localRpcHandler,
            webView = webView,
            pluginOrigin = assetFetcher.originFor(initialPluginId),
            descriptorProvider = descriptorProvider,
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
 * Sandboxed [WebChromeClient] for plugin WebViews forwarding JS/WebGPU console diagnostics
 * to `sideveild` (`host.webviewConsole`) and enforcing least-privilege media/device permission denial.
 */
internal class PluginWebChromeClient(
    private val pluginIdProvider: () -> String,
    private val daemonClient: DaemonClient,
) : WebChromeClient() {
    override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
        val msg = consoleMessage ?: return false
        val level = msg.messageLevel()?.name?.lowercase() ?: "info"
        val pluginId = pluginIdProvider()
        val text = msg.message().orEmpty().take(MAX_MESSAGE_LEN)
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
                    sourceId = msg.sourceId().orEmpty().take(120),
                    lineNumber = msg.lineNumber(),
                ),
            ),
        )
        return true
    }

    override fun onPermissionRequest(request: PermissionRequest?) {
        // Deny sensitive camera/microphone/MIDI hardware requests inside untrusted plugin sandboxes.
        request?.deny()
    }

    companion object {
        private const val TAG = "SideVeilWebConsole"
        private const val MAX_MESSAGE_LEN = 320
    }
}
