package pochita.sideveil.host.plugin

import android.content.Context
import pochita.sideveil.host.pinned.PinnedBoundsSpec
import pochita.sideveil.protocol.BUILTIN_DIAG_PLUGIN_ID
import pochita.sideveil.protocol.EdgeStatusPayload
import pochita.sideveil.protocol.PluginDescriptor
import pochita.sideveil.protocol.PluginPresentationMode
import pochita.sideveil.protocol.PluginSurfaceBackdrop

/**
 * Routes [PluginContainerCallbacks] emitted by individual [PluginWebViewInstance] sandboxes
 * to [PluginHostSurfaceDelegate], gating actions by active or pinned plugin ownership.
 */
internal class PluginSurfaceCallbackRouter(
    private val context: Context,
    private val surfaceDelegate: PluginHostSurfaceDelegate,
    private val activePluginIdProvider: () -> String,
    private val pinnedPluginIdProvider: () -> String?,
    private val instanceLookup: (String) -> PluginWebViewInstance?,
    private val onCrash: (String) -> Unit,
) : PluginContainerCallbacks {
    fun mountInstance(instance: PluginWebViewInstance) {
        val toolbar = instance.resolveEmbedToolbarBinding(context) { surfaceDelegate.onDismissPanel() }
        surfaceDelegate.mountPluginWebView(
            webView = instance.webView,
            backdrop = instance.surfaceBackdrop,
            presentationMode = instance.presentationMode,
            sizeOverride = instance.sizeOverride,
            embedToolbar = toolbar,
        )
    }

    fun applyDescriptorUpdates(
        plugins: List<PluginDescriptor>,
        descriptors: MutableMap<String, PluginDescriptor>,
        pool: PluginWebViewPool<PluginWebViewInstance>,
        crashRecovery: WebViewCrashRecoveryCoordinator,
        onClearPinned: () -> Unit,
    ): Boolean {
        descriptors.clear()
        var activeNeedsRemount = false
        val activeId = activePluginIdProvider()
        val pinnedId = pinnedPluginIdProvider()
        for (item in plugins) {
            descriptors[item.id] = item
            val existing = pool.peek(item.id) ?: continue
            when (existing.updateDescriptor(item)) {
                DescriptorUpdateOutcome.RECREATE_REQUIRED -> {
                    crashRecovery.cancel(item.id, resetBackoff = true)
                    if (item.id == pinnedId) onClearPinned()
                    pool.removeAndDestroy(item.id)
                    if (item.id == activeId) activeNeedsRemount = true
                }

                DescriptorUpdateOutcome.SURFACE_UPDATED -> {
                    if (item.id == activeId && item.id != pinnedId) {
                        mountInstance(existing)
                    } else if (item.id == pinnedId) {
                        surfaceDelegate.onPluginBackdropChanged(existing.surfaceBackdrop)
                    }
                }

                DescriptorUpdateOutcome.UNCHANGED -> {
                    // No surface or trust-mode recreation required.
                }
            }
        }
        descriptors.putIfAbsent(BUILTIN_DIAG_PLUGIN_ID, PluginDescriptor.builtinDiagnostic())
        return activeNeedsRemount
    }

    override fun onDismissRequested(pluginId: String) {
        if (isActiveOrPinned(pluginId)) {
            surfaceDelegate.onDismissPanel()
        }
    }

    override fun onImeFocusRequested(
        pluginId: String,
        focused: Boolean,
    ) {
        if (pluginId == pinnedPluginIdProvider()) {
            surfaceDelegate.onSetPinnedImeFocused(focused)
        } else if (pluginId == activePluginIdProvider()) {
            surfaceDelegate.onSetImeFocused(focused)
        }
    }

    override fun onAppearanceSynced(
        pluginId: String,
        themeId: String,
        colorScheme: String,
    ) {
        if (pluginId == activePluginIdProvider()) {
            surfaceDelegate.onSyncAppearance(themeId, colorScheme)
        }
    }

    override fun onPreferredSizeChanged(
        pluginId: String,
        size: PluginSizeOverride,
    ) {
        if (pluginId == activePluginIdProvider()) {
            surfaceDelegate.onPluginSizeChanged(size)
        }
    }

    override fun onBackdropChanged(
        pluginId: String,
        backdrop: PluginSurfaceBackdrop,
    ) {
        if (isActiveOrPinned(pluginId)) {
            surfaceDelegate.onPluginBackdropChanged(backdrop)
        }
    }

    override fun onPresentationModeChanged(
        pluginId: String,
        mode: PluginPresentationMode,
    ) {
        if (isActiveOrPinned(pluginId)) {
            instanceLookup(pluginId)?.let { activeInst ->
                val toolbar =
                    if (mode == PluginPresentationMode.PINNED) {
                        activeInst.bindEmbedStatusListener(null)
                        null
                    } else {
                        activeInst.resolveEmbedToolbarBinding(context) { surfaceDelegate.onDismissPanel() }
                    }
                surfaceDelegate.onPluginPresentationChanged(
                    pluginId = pluginId,
                    webView = activeInst.webView,
                    backdrop = activeInst.surfaceBackdrop,
                    mode = mode,
                    sizeOverride = activeInst.sizeOverride,
                    embedToolbar = toolbar,
                )
            }
        }
    }

    override fun onPinnedBoundsChanged(
        pluginId: String,
        bounds: PinnedBoundsSpec,
    ) {
        if (isActiveOrPinned(pluginId)) {
            surfaceDelegate.onPinnedBoundsChanged(bounds)
        }
    }

    override fun onEdgeStatusChanged(
        pluginId: String,
        status: EdgeStatusPayload?,
    ) {
        if (isActiveOrPinned(pluginId)) {
            surfaceDelegate.onEdgeStatusChanged(status)
        }
    }

    override fun onRenderProcessCrashed(pluginId: String) = onCrash(pluginId)

    private fun isActiveOrPinned(pluginId: String): Boolean = pluginId == activePluginIdProvider() || pluginId == pinnedPluginIdProvider()
}
