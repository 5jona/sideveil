package pochita.sideveil.host

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.WebView
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import pochita.sideveil.protocol.ContainerActionPayload
import pochita.sideveil.protocol.DebugWebViewSnapshot
import pochita.sideveil.protocol.HostEventEnvelope
import pochita.sideveil.protocol.HostWebViewCrashPayload
import pochita.sideveil.protocol.HostWebViewErrorPayload
import pochita.sideveil.protocol.PluginBackendKind
import pochita.sideveil.protocol.PluginBackendStatePayload
import pochita.sideveil.protocol.PluginDescriptor
import pochita.sideveil.protocol.PluginPresentationMode
import pochita.sideveil.protocol.PluginSurfaceBackdrop
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
)

/**
 * Isolated WebView sandbox instance for a single SideVeil plugin under
 * `https://<pluginId>.plugins.sideveil.local/` with `onRenderProcessGone` crash recovery,
 * per-plugin container preferences, and [PluginWebMessageBridge] IPC routing.
 */
internal class PluginWebViewInstance(
    context: Context,
    private val deps: PluginWebViewDeps,
    initialDescriptor: PluginDescriptor,
    private val callbacks: PluginContainerCallbacks,
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    val webView: WebView = WebView(context)
    private val dormancy = WebViewDormancyController.forWebView(webView, mainHandler)

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
    var presentationMode: PluginPresentationMode = initialDescriptor.surface.presentation
        private set

    @Volatile
    var sizeOverride: PluginSizeOverride =
        PluginSizeOverride(
            widthDp = initialDescriptor.surface.widthDp,
            heightPercent = initialDescriptor.surface.heightPercent,
        )
        private set

    @Volatile
    var foregroundPriority: Boolean = false
        private set

    val isUntrustedEmbed: Boolean = PluginNetworkPolicy.isUntrustedEmbed(initialDescriptor)

    // Untrusted web-embeds receive zero JS bridge or WebMessagePort privileges by construction.
    private val messageBridge: PluginWebMessageBridge? =
        if (isUntrustedEmbed) {
            null
        } else {
            PluginWebMessageBridge(
                scope = deps.scope,
                daemonClient = deps.daemonClient,
                localRpcHandler = deps.localRpcHandler,
                webView = webView,
                pluginOrigin = deps.assetFetcher.originFor(initialDescriptor.id),
                descriptorProvider = { descriptor },
                onContainerAction = ::applyContainerAction,
            )
        }

    init {
        PluginWebViewSettings.configureBaseView(context, webView, initialDescriptor, isUntrustedEmbed)
        messageBridge?.let { bridge ->
            webView.addJavascriptInterface(bridge.createJsHostObject(), "__POCHITA_HOST__")
        }
        webView.webViewClient =
            PluginWebViewClient(
                isUntrustedEmbed = isUntrustedEmbed,
                descriptorProvider = { descriptor },
                assetFetcher = deps.assetFetcher,
                messageBridge = messageBridge,
                dormancy = dormancy,
                onWebViewError = ::emitWebViewError,
                onRenderProcessGoneEvent = ::handleRenderProcessGone,
            )
        webView.webChromeClient = PluginWebChromeClient({ descriptor.id }, deps.daemonClient)
    }

    fun updateDescriptor(next: PluginDescriptor): DescriptorUpdateOutcome {
        if (isUntrustedEmbed != PluginNetworkPolicy.isUntrustedEmbed(next)) {
            return DescriptorUpdateOutcome.RECREATE_REQUIRED
        }
        val prev = descriptor
        descriptor = next
        if (prev.embed != next.embed || prev.network != next.network || prev.capabilities != next.capabilities) {
            PluginWebViewSettings.applyWebSettings(webView, next)
        }
        val surfaceChanged = syncSurfaceDefaults(prev, next)
        val needsReload =
            prev.entry != next.entry ||
                prev.devServerUrl != next.devServerUrl ||
                prev.embed != next.embed ||
                prev.network != next.network ||
                prev.surface.crossOriginIsolated != next.surface.crossOriginIsolated
        if (needsReload) {
            loadEntry()
        }
        if (prev.backendState != next.backendState || needsReload) {
            pushBackendState()
        }
        return if (surfaceChanged) DescriptorUpdateOutcome.SURFACE_UPDATED else DescriptorUpdateOutcome.UNCHANGED
    }

    fun pushBackendState() {
        if (isUntrustedEmbed || descriptor.backend == PluginBackendKind.NONE) return
        pushEvent(
            SideVeilEvents.PLUGIN_BACKEND_STATE,
            SideVeilWireJson.encodeToJsonElement(
                PluginBackendStatePayload(pluginId = descriptor.id, state = descriptor.backendState),
            ),
        )
    }

    fun setForegroundPriority(foreground: Boolean) {
        foregroundPriority = foreground
        val priority = if (foreground) WebView.RENDERER_PRIORITY_IMPORTANT else WebView.RENDERER_PRIORITY_BOUND
        webView.setRendererPriorityPolicy(priority, !foreground)
        dormancy.setForeground(foreground)
    }

    fun canConsumeBack(): Boolean = backInterceptEnabled || (descriptor.embed?.webHistoryBack == true && webView.canGoBack())

    fun consumeBackPress(): Boolean {
        if (backInterceptEnabled) {
            val payload = buildJsonObject { put("timestampMs", JsonPrimitive(System.currentTimeMillis())) }
            pushEvent(SideVeilEvents.PANEL_BACK_PRESSED, payload)
            return true
        }
        if (descriptor.embed?.webHistoryBack == true && webView.canGoBack()) {
            webView.goBack()
            return true
        }
        return false
    }

    fun flushCookies() = PluginWebViewSettings.flushCookies(descriptor.id)

    fun debugState(
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
            dormant = dormancy.isPaused,
            crashCount = deps.crashRegistry.countFor(descriptor.id),
            widthPx = webView.width,
            heightPx = webView.height,
        )

    fun loadEntry() {
        dormancy.onLoadStarted()
        messageBridge?.resetBeforeLoad()
        val embed = descriptor.embed
        if (embed != null) {
            if (PluginNetworkPolicy.shouldAllowNativeNetwork(descriptor, embed.url, isMainFrame = true)) {
                webView.loadUrl(embed.url)
            } else {
                Log.w(TAG, "Blocked disallowed embed entry URL for plugin=${descriptor.id}: ${embed.url}")
                webView.loadUrl("about:blank")
            }
        } else {
            webView.loadUrl(deps.assetFetcher.entryUrlFor(descriptor))
        }
    }

    fun pushEvent(
        event: String,
        payload: JsonElement?,
    ) {
        val bridge = messageBridge ?: return
        val raw = SideVeilWireJson.encodeToString(HostEventEnvelope(event = event, payload = payload))
        bridge.postRawAsync(raw)
    }

    fun pushEnvelope(envelope: HostEventEnvelope) {
        messageBridge?.postRawAsync(SideVeilWireJson.encodeToString(envelope))
    }

    fun resetPresentationToDrawer() {
        presentationMode = PluginPresentationMode.DRAWER
        pushEvent(
            SideVeilEvents.CONTAINER_PRESENTATION_CHANGED,
            buildJsonObject { put("mode", JsonPrimitive("drawer")) },
        )
        notifyClickThroughChanged(false)
    }

    fun notifyClickThroughChanged(clickThrough: Boolean) {
        pushEvent(
            SideVeilEvents.CONTAINER_CLICK_THROUGH_CHANGED,
            buildJsonObject { put("clickThrough", JsonPrimitive(clickThrough)) },
        )
    }

    fun destroy() {
        dormancy.destroy()
        flushCookies()
        messageBridge?.closeActivePort()
        webView.stopLoading()
        webView.destroy()
    }

    private fun emitWebViewError(payload: HostWebViewErrorPayload) {
        deps.daemonClient.sendEvent(
            SideVeilEvents.HOST_WEBVIEW_ERROR,
            SideVeilWireJson.encodeToJsonElement(payload),
        )
    }

    private fun handleRenderProcessGone(
        didCrash: Boolean,
        rendererPriorityAtExit: Int,
    ) {
        val pluginId = descriptor.id
        val nextCount = deps.crashRegistry.recordCrash(pluginId)
        deps.daemonClient.sendEvent(
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
        mainHandler.post { callbacks.onRenderProcessCrashed(pluginId) }
    }

    private fun syncSurfaceDefaults(
        prev: PluginDescriptor,
        next: PluginDescriptor,
    ): Boolean {
        var changed = false
        if (prev.surface.backdrop != next.surface.backdrop) {
            surfaceBackdrop = next.surface.backdrop
            changed = true
        }
        if (prev.surface.presentation != next.surface.presentation) {
            presentationMode = next.surface.presentation
            changed = true
        }
        if (prev.surface.widthDp != next.surface.widthDp || prev.surface.heightPercent != next.surface.heightPercent) {
            sizeOverride = PluginSizeOverride(widthDp = next.surface.widthDp, heightPercent = next.surface.heightPercent)
            changed = true
        }
        return changed
    }

    private fun applyContainerAction(action: ContainerActionPayload?) =
        PluginContainerActionRouter.dispatch(
            pluginId = descriptor.id,
            action = action,
            callbacks = callbacks,
            onSizeOverride = { sizeOverride = it },
            onBackIntercept = { backInterceptEnabled = it },
            onBackdrop = { surfaceBackdrop = it },
            onPresentationMode = { presentationMode = it },
        )

    companion object {
        private const val TAG = "SideVeilPluginWebView"
    }
}
