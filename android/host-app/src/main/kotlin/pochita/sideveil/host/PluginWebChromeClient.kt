package pochita.sideveil.host

import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import kotlinx.serialization.json.encodeToJsonElement
import pochita.sideveil.protocol.HostWebViewConsolePayload
import pochita.sideveil.protocol.SideVeilEvents
import pochita.sideveil.protocol.SideVeilWireJson

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
        if (msg.messageLevel() == ConsoleMessage.MessageLevel.ERROR ||
            msg.messageLevel() == ConsoleMessage.MessageLevel.WARNING
        ) {
            Log.d(TAG, "[$pluginId][$level] $text")
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
