package pochita.sideveil.host.plugin

import android.webkit.WebView
import pochita.sideveil.host.pinned.PinnedBoundsSpec
import pochita.sideveil.protocol.EdgeStatusPayload
import pochita.sideveil.protocol.PluginDescriptor
import pochita.sideveil.protocol.PluginPresentationMode
import pochita.sideveil.protocol.PluginSurfaceBackdrop

/**
 * Per-plugin container size override requested via manifest or `container.action`.
 */
data class PluginSizeOverride(
    val widthDp: Int? = null,
    val heightDp: Int? = null,
    val heightPercent: Int? = null,
)

/**
 * Toolbar binding attaching an untrusted web-embed instance to [EmbedQuickBarView].
 */
data class EmbedToolbarBinding(
    val title: String,
    val onReload: () -> Unit,
    val onHome: () -> Unit,
    val onOpenExternal: () -> Unit,
    val bindStatusListener: (((String?, Boolean) -> Unit)?) -> Unit = {},
)

internal enum class DescriptorUpdateOutcome {
    UNCHANGED,
    SURFACE_UPDATED,
    RECREATE_REQUIRED,
}

/**
 * Callbacks emitted by an individual [PluginWebViewInstance] sandbox when its container state
 * or render process transitions.
 */
interface PluginContainerCallbacks {
    fun onDismissRequested(pluginId: String)

    fun onImeFocusRequested(
        pluginId: String,
        focused: Boolean,
    )

    fun onAppearanceSynced(
        pluginId: String,
        themeId: String,
        colorScheme: String,
    )

    fun onPreferredSizeChanged(
        pluginId: String,
        size: PluginSizeOverride,
    )

    fun onBackdropChanged(
        pluginId: String,
        backdrop: PluginSurfaceBackdrop,
    )

    fun onPresentationModeChanged(
        pluginId: String,
        mode: PluginPresentationMode,
    )

    fun onPinnedBoundsChanged(
        pluginId: String,
        bounds: PinnedBoundsSpec,
    )

    fun onEdgeStatusChanged(
        pluginId: String,
        status: EdgeStatusPayload?,
    )

    fun onRenderProcessCrashed(pluginId: String)
}

/**
 * Host overlay surface delegate implemented by [pochita.sideveil.host.service.HostOverlayCoordinator].
 */
interface PluginHostSurfaceDelegate {
    fun mountPluginWebView(
        webView: WebView,
        backdrop: PluginSurfaceBackdrop,
        presentationMode: PluginPresentationMode,
        sizeOverride: PluginSizeOverride,
        embedToolbar: EmbedToolbarBinding? = null,
    )

    fun onDismissPanel()

    fun onSetImeFocused(focused: Boolean)

    fun onSetPinnedImeFocused(focused: Boolean)

    fun onSyncAppearance(
        themeId: String,
        colorScheme: String,
    )

    fun onPluginSizeChanged(sizeOverride: PluginSizeOverride)

    fun onPluginBackdropChanged(backdrop: PluginSurfaceBackdrop)

    fun onPluginPresentationChanged(
        pluginId: String,
        webView: WebView,
        backdrop: PluginSurfaceBackdrop,
        mode: PluginPresentationMode,
        sizeOverride: PluginSizeOverride,
        embedToolbar: EmbedToolbarBinding? = null,
    )

    fun onPinnedBoundsChanged(bounds: PinnedBoundsSpec)

    fun onEdgeStatusChanged(status: EdgeStatusPayload?)

    fun onMatchedPluginsUpdated(
        matchedPlugins: List<PluginDescriptor>,
        activePluginId: String,
    )
}
