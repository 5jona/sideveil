package pochita.sideveil.host

import pochita.sideveil.protocol.EdgeStatusPayload
import pochita.sideveil.protocol.PluginPresentationMode
import pochita.sideveil.protocol.PluginSurfaceBackdrop

/**
 * Routes [PluginContainerCallbacks] emitted by individual [PluginWebViewInstance] sandboxes
 * to [PluginHostSurfaceDelegate], gating actions by active or pinned plugin ownership.
 */
internal class PluginSurfaceCallbackRouter(
    private val surfaceDelegate: PluginHostSurfaceDelegate,
    private val activePluginIdProvider: () -> String,
    private val pinnedPluginIdProvider: () -> String?,
    private val instanceLookup: (String) -> PluginWebViewInstance?,
    private val onCrash: (String) -> Unit,
) : PluginContainerCallbacks {
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
                surfaceDelegate.onPluginPresentationChanged(
                    pluginId = pluginId,
                    webView = activeInst.webView,
                    backdrop = activeInst.surfaceBackdrop,
                    mode = mode,
                    sizeOverride = activeInst.sizeOverride,
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
