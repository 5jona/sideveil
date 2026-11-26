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
 * `https://<pluginId>.plugins.sideveil.local/` with multi-profile storage isolation,
 * origin-verified `WebMessageListener` IPC, `onRenderProcessGone` crash recovery,
 * and per-plugin container preferences.
 */
internal class PluginWebViewInstance(
    context: Context,
    private val deps: PluginWebViewDeps,
    initialDescriptor: PluginDescriptor,
    private val callbacks: PluginContainerCallbacks,
    initialNetworkRoute: NetworkRouteContext = NetworkRouteContext(),
) : PluginEventTarget {
    private val mainHandler = Handler(Looper.getMainLooper())
    val webView: WebView = WebView(context)
    override val visibilityTracker: PluginVisibilityTracker = PluginVisibilityTracker()
    private val dormancy =
        WebViewDormancyController.forWebView(webView, mainHandler, WebViewDormancyController.resolveHidePauseDelayMs(initialDescriptor))
    private val embedSession = EmbedSessionController(webView, mainHandler, initialNetworkRoute)

    @Volatile var descriptor: PluginDescriptor = initialDescriptor
        private set

    @Volatile var backInterceptEnabled: Boolean = false
        private set

    @Volatile var surfaceBackdrop: PluginSurfaceBackdrop = initialDescriptor.surface.backdrop
        private set

    @Volatile var presentationMode: PluginPresentationMode = initialDescriptor.surface.presentation
        private set

    @Volatile var sizeOverride: PluginSizeOverride =
        PluginSizeOverride(widthDp = initialDescriptor.surface.widthDp, heightPercent = initialDescriptor.surface.heightPercent)
        private set

    @Volatile var foregroundPriority: Boolean = false
        private set

    @Volatile private var hadMainFrameErrorThisLoad: Boolean = false

    override val isUntrustedEmbed: Boolean = PluginNetworkPolicy.isUntrustedEmbed(initialDescriptor)

    private val messageBridge: PluginWebMessageBridge? =
        deps.createMessageBridgeIfTrusted(
            isUntrustedEmbed,
            webView,
            initialDescriptor.id,
            { descriptor },
            { foregroundPriority },
            ::applyContainerAction,
        )

    init {
        PluginWebViewSettings.configureBaseView(context, webView, initialDescriptor, isUntrustedEmbed)
        messageBridge?.installWebMessageListener()
        webView.webViewClient =
            PluginWebViewClient(
                isUntrustedEmbed,
                { descriptor },
                deps.assetFetcher,
                messageBridge,
                dormancy,
                ::emitWebViewError,
                ::handleRenderProcessGone,
                ::handleMainPageFinished,
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
        if (needsReload) {
            if (prev.devServerUrl != next.devServerUrl) {
                runCatching { webView.clearCache(true) }
            }
            loadEntry()
        }
        if (prev.backendState != next.backendState || needsReload) pushBackendState()
        return if (surfaceChanged) DescriptorUpdateOutcome.SURFACE_UPDATED else DescriptorUpdateOutcome.UNCHANGED
    }

    fun reloadWithCacheClear() {
        runCatching { webView.clearCache(true) }
        if (isUntrustedEmbed) embedSession.reload(descriptor) else loadEntry()
    }

    override fun pushBackendState() {
        if (isUntrustedEmbed || descriptor.backend == PluginBackendKind.NONE) return
        val payload = PluginBackendStatePayload(pluginId = descriptor.id, state = descriptor.backendState)
        pushEvent(SideVeilEvents.PLUGIN_BACKEND_STATE, SideVeilWireJson.encodeToJsonElement(payload))
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

    fun flushCookies() = PluginWebViewSettings.flushCookies(webView, descriptor.id, isUntrustedEmbed)

    fun debugState(
        isActive: Boolean,
        isPinned: Boolean,
        keepAlive: Boolean,
    ): DebugWebViewSnapshot =
        PluginWebViewSettings.buildWebViewDebugSnapshot(
            webView,
            descriptor,
            isUntrustedEmbed,
            surfaceBackdrop,
            presentationMode,
            backInterceptEnabled,
            foregroundPriority,
            dormancy.isPaused,
            deps.crashRegistry.countFor(descriptor.id),
            isActive,
            isPinned,
            keepAlive,
        )

    override fun loadEntry(restoreUrl: String?) {
        hadMainFrameErrorThisLoad = false
        callbacks.onMainFrameStatusChanged(descriptor.id, errorTitle = null, errorDetail = null)
        dormancy.onLoadStarted()
        visibilityTracker.reset()
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

    override fun pushEvent(
        event: String,
        payload: JsonElement?,
    ) {
        messageBridge?.postRawAsync(SideVeilWireJson.encodeToString(HostEventEnvelope(event = event, payload = payload)))
    }

    override fun pushEnvelope(envelope: HostEventEnvelope) {
        messageBridge?.postRawAsync(SideVeilWireJson.encodeToString(envelope))
    }

    fun resetPresentationToDrawer() {
        presentationMode = PluginPresentationMode.DRAWER
        pushEvent(SideVeilEvents.CONTAINER_PRESENTATION_CHANGED, buildJsonObject { put("mode", JsonPrimitive("drawer")) })
        notifyClickThroughChanged(false)
    }

    fun notifyClickThroughChanged(clickThrough: Boolean) =
        pushEvent(SideVeilEvents.CONTAINER_CLICK_THROUGH_CHANGED, buildJsonObject { put("clickThrough", JsonPrimitive(clickThrough)) })

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
        if (!isUntrustedEmbed && payload.isMainFrame) {
            hadMainFrameErrorThisLoad = true
            val title = if (!descriptor.devServerUrl.isNullOrBlank()) "开发服务器不可达" else "插件加载失败"
            val detail = PluginWebViewSettings.formatMainFrameErrorDetail(payload)
            mainHandler.post { callbacks.onMainFrameStatusChanged(descriptor.id, title, detail) }
        }
        deps.emitWebViewError(payload)
    }

    private fun handleMainPageFinished(url: String?) {
        if (isUntrustedEmbed) {
            flushCookies()
            embedSession.handleMainPageFinished(url)
            return
        }
        if (!hadMainFrameErrorThisLoad && !url.isNullOrBlank() && url != "about:blank") {
            callbacks.onMainFrameStatusChanged(descriptor.id, errorTitle = null, errorDetail = null)
        }
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

    private fun applyContainerAction(action: ContainerActionPayload?): ContainerActionResult {
        val own = callbacks.pluginOwnership(descriptor.id)
        return PluginContainerActionRouter.dispatch(
            descriptor,
            action,
            callbacks,
            own.active,
            own.pinned,
            { sizeOverride = it },
            { backInterceptEnabled = it },
            { surfaceBackdrop = it },
            { presentationMode = it },
        )
    }

    companion object {
        private const val TAG = "SideVeilPluginWebView"
    }
}
