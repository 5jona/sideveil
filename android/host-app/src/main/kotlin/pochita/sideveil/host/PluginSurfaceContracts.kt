package pochita.sideveil.host

import android.webkit.WebView
import pochita.sideveil.protocol.EdgeStatusPayload
import pochita.sideveil.protocol.PluginDescriptor
import pochita.sideveil.protocol.PluginPresentationMode
import pochita.sideveil.protocol.PluginSurfaceBackdrop

data class PluginSizeOverride(
    val widthDp: Int? = null,
    val heightDp: Int? = null,
    val heightPercent: Int? = null,
)

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

interface PluginHostSurfaceDelegate {
    fun mountPluginWebView(
        webView: WebView,
        backdrop: PluginSurfaceBackdrop,
        presentationMode: PluginPresentationMode,
        sizeOverride: PluginSizeOverride,
    )

    fun onDismissPanel()

    fun onSetImeFocused(focused: Boolean)

    fun onSyncAppearance(
        themeId: String,
        colorScheme: String,
    )

    fun onPluginSizeChanged(sizeOverride: PluginSizeOverride)

    fun onPluginBackdropChanged(backdrop: PluginSurfaceBackdrop)

    fun onPluginPresentationChanged(
        webView: WebView,
        backdrop: PluginSurfaceBackdrop,
        mode: PluginPresentationMode,
    )

    fun onPinnedBoundsChanged(bounds: PinnedBoundsSpec)

    fun onEdgeStatusChanged(status: EdgeStatusPayload?)

    fun onMatchedPluginsUpdated(
        matchedPlugins: List<PluginDescriptor>,
        activePluginId: String,
    )
}
