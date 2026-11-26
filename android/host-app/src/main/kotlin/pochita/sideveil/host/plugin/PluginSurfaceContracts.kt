package pochita.sideveil.host.plugin

import android.webkit.WebView
import kotlinx.serialization.json.JsonElement
import pochita.sideveil.host.pinned.PinnedBoundsSpec
import pochita.sideveil.protocol.EdgeStatusPayload
import pochita.sideveil.protocol.HostEventEnvelope
import pochita.sideveil.protocol.PanelState
import pochita.sideveil.protocol.PanelVisibilityPayload
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

/**
 * Actionable error or crash-recovery state shown natively over [pochita.sideveil.host.panel.SidebarPanelWindow].
 */
data class PluginSurfaceErrorState(
    val pluginId: String,
    val pluginName: String,
    val title: String,
    val detail: String,
    val onRetry: () -> Unit,
)

/** Native panel overlay state rendered when no plugin matches or an active plugin fails to load/crashes. */
sealed interface PluginPanelOverlayState {
    data object None : PluginPanelOverlayState

    data object Empty : PluginPanelOverlayState

    data class Error(
        val error: PluginSurfaceErrorState,
    ) : PluginPanelOverlayState
}

/** Identifies which auxiliary overlay window must be torn down when its owning plugin is evicted. */
enum class EvictedOverlayKind {
    PINNED,
    HUD,
}

/** Snapshot of whether a plugin currently owns the active sidebar panel and/or pinned window. */
data class PluginOwnershipState(
    val active: Boolean,
    val pinned: Boolean,
)

internal enum class DescriptorUpdateOutcome {
    UNCHANGED,
    SURFACE_UPDATED,
    RECREATE_REQUIRED,
}

/**
 * Pure-Kotlin per-instance visibility state tracker preventing duplicate `panel.visibility`
 * deliveries for unchanged `(visible, state)` payloads while allowing explicit `force` refreshes
 * and resetting when the WebView reloads its page.
 */
internal class PluginVisibilityTracker {
    var lastDispatched: PanelVisibilityPayload? = null
        private set

    fun shouldDispatch(
        visible: Boolean,
        state: PanelState,
        force: Boolean = false,
    ): Boolean {
        val next = PanelVisibilityPayload(visible = visible, state = state)
        if (!force && lastDispatched == next) return false
        lastDispatched = next
        return true
    }

    fun reset() {
        lastDispatched = null
    }
}

/**
 * Minimal target abstraction implemented by [PluginWebViewInstance] so initial hydration and
 * visibility deduplication in [PluginWebViewEventBridge] can be exercised directly in JVM tests.
 */
internal interface PluginEventTarget {
    val isUntrustedEmbed: Boolean
    val visibilityTracker: PluginVisibilityTracker

    fun loadEntry(restoreUrl: String? = null)

    fun pushBackendState()

    fun pushEvent(
        event: String,
        payload: JsonElement?,
    )

    fun pushEnvelope(envelope: HostEventEnvelope)
}

/**
 * Callbacks emitted by an individual [PluginWebViewInstance] sandbox when its container state,
 * main-frame load status, or render process transitions.
 */
interface PluginContainerCallbacks {
    fun pluginOwnership(pluginId: String): PluginOwnershipState

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

    fun onMainFrameStatusChanged(
        pluginId: String,
        errorTitle: String?,
        errorDetail: String?,
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

    fun setPanelOverlayState(state: PluginPanelOverlayState)

    fun onOverlayPluginEvicted(
        kind: EvictedOverlayKind,
        pluginId: String,
    )

    fun onDismissPanel()

    fun onSetImeFocused(
        focused: Boolean,
        pinned: Boolean,
    )

    fun onSyncAppearance(
        themeId: String,
        colorScheme: String,
    )

    fun onPluginSurfaceChanged(
        sizeOverride: PluginSizeOverride? = null,
        backdrop: PluginSurfaceBackdrop? = null,
    )

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
