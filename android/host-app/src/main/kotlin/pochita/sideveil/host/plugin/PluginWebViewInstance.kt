package pochita.sideveil.host.plugin

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.WebView
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import pochita.sideveil.protocol.ContainerActionPayload
import pochita.sideveil.protocol.DebugWebViewSnapshot
import pochita.sideveil.protocol.HostEventEnvelope
import pochita.sideveil.protocol.HostWebViewErrorPayload
import pochita.sideveil.protocol.PluginBackendKind
import pochita.sideveil.protocol.PluginBackendStatePayload
import pochita.sideveil.protocol.PluginDescriptor
import pochita.sideveil.protocol.PluginPresentationMode
import pochita.sideveil.protocol.PluginSurfaceBackdrop
import pochita.sideveil.protocol.SideVeilEvents
import pochita.sideveil.protocol.SideVeilWireJson

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
    initialNetworkRoute: NetworkRouteContext = NetworkRouteContext(),
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    val webView: WebView = WebView(context)
    private val dormancy =
        WebViewDormancyController.forWebView(
            webView = webView,
            mainHandler = mainHandler,
            hidePauseDelayMs = WebViewDormancyController.resolveHidePauseDelayMs(initialDescriptor),
        )
    private val embedSession = EmbedSessionController(webView, mainHandler, initialNetworkRoute)

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
        deps.createMessageBridgeIfTrusted(
            isUntrustedEmbed = isUntrustedEmbed,
            webView = webView,
            initialPluginId = initialDescriptor.id,
            descriptorProvider = { descriptor },
            onContainerAction = ::applyContainerAction,
        )

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
                onMainPageFinished = ::handleMainPageFinished,
            )
        webView.webChromeClient = PluginWebChromeClient({ descriptor.id }, deps.daemonClient, isUntrustedEmbed)
    }

    fun updateDescriptor(next: PluginDescriptor): DescriptorUpdateOutcome {
        if (isUntrustedEmbed != PluginNetworkPolicy.isUntrustedEmbed(next)) {
            return DescriptorUpdateOutcome.RECREATE_REQUIRED
        }
        val prev = descriptor
        descriptor = next
        dormancy.updateHidePauseDelayMs(WebViewDormancyController.resolveHidePauseDelayMs(next))
        if (PluginWebViewSettings.didWebSettingsInputsChange(prev, next)) {
            PluginWebViewSettings.applyWebSettings(webView, next)
        }
        val toolbarOrTitleChanged =
            isUntrustedEmbed && (prev.embed?.showToolbar != next.embed?.showToolbar || prev.name != next.name)
        val surfaceChanged = syncSurfaceDefaults(prev, next) || toolbarOrTitleChanged
        val needsReload = PluginWebViewSettings.shouldReloadOnDescriptorChange(prev, next)
        if (needsReload) loadEntry()
        if (prev.backendState != next.backendState || needsReload) pushBackendState()
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
        val wasDormant = dormancy.isPaused
        foregroundPriority = foreground
        val priority = if (foreground) WebView.RENDERER_PRIORITY_IMPORTANT else WebView.RENDERER_PRIORITY_BOUND
        webView.setRendererPriorityPolicy(priority, !foreground)
        dormancy.setForeground(foreground)
        if (foreground && wasDormant && isUntrustedEmbed) {
            embedSession.triggerOnlineReconnect()
        }
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
        PluginWebViewSettings.buildWebViewDebugSnapshot(
            webView = webView,
            descriptor = descriptor,
            isUntrustedEmbed = isUntrustedEmbed,
            surfaceBackdrop = surfaceBackdrop,
            presentationMode = presentationMode,
            backInterceptEnabled = backInterceptEnabled,
            foregroundPriority = foregroundPriority,
            dormant = dormancy.isPaused,
            crashCount = deps.crashRegistry.countFor(descriptor.id),
            isActive = isActive,
            isPinned = isPinned,
            keepAlive = keepAlive,
        )

    fun loadEntry(restoreUrl: String? = null) {
        dormancy.onLoadStarted()
        messageBridge?.resetBeforeLoad()
        if (descriptor.embed != null) {
            embedSession.loadInitial(descriptor, restoreUrl)
        } else {
            webView.loadUrl(deps.assetFetcher.entryUrlFor(descriptor))
        }
    }

    fun updateNetworkRoute(network: NetworkRouteContext) {
        if (isUntrustedEmbed) embedSession.updateNetworkRoute(descriptor, network)
    }

    fun bindEmbedStatusListener(listener: ((String?, Boolean) -> Unit)?) = embedSession.bindStatusListener(listener)

    fun resolveEmbedToolbarBinding(
        context: Context,
        onDismissPanel: () -> Unit,
    ): EmbedToolbarBinding? = embedSession.resolveToolbarBinding(context, descriptor, isUntrustedEmbed, onDismissPanel)

    fun pushEvent(
        event: String,
        payload: JsonElement?,
    ) {
        val bridge = messageBridge ?: return
        bridge.postRawAsync(SideVeilWireJson.encodeToString(HostEventEnvelope(event = event, payload = payload)))
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
        messageBridge?.destroy()
        webView.stopLoading()
        webView.destroy()
    }

    private fun emitWebViewError(payload: HostWebViewErrorPayload) {
        if (isUntrustedEmbed && payload.isMainFrame && payload.kind == "network") {
            if (embedSession.handleMainFrameNetworkError(descriptor, payload.url)) {
                Log.i(TAG, "Auto-fell back untrusted embed plugin=${descriptor.id} from url=${payload.url}")
                return
            }
        }
        deps.emitWebViewError(payload)
    }

    private fun handleMainPageFinished(url: String?) {
        if (!isUntrustedEmbed) return
        flushCookies()
        embedSession.handleMainPageFinished(url)
    }

    private fun handleRenderProcessGone(
        didCrash: Boolean,
        rendererPriorityAtExit: Int,
    ) {
        val pluginId = descriptor.id
        deps.recordAndEmitCrash(pluginId, didCrash, rendererPriorityAtExit)
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
