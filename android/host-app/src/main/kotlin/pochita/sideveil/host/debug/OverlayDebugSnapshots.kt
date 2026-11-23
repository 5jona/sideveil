package pochita.sideveil.host.debug

import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.webkit.WebView
import androidx.core.view.isVisible
import pochita.sideveil.host.panel.PanelSurfaceState
import pochita.sideveil.protocol.DebugAgslSnapshot
import pochita.sideveil.protocol.DebugOverlayWindowSnapshot
import pochita.sideveil.protocol.DebugPanelWindowSnapshot
import pochita.sideveil.protocol.DebugWebViewSnapshot
import pochita.sideveil.protocol.HostPluginDiagnosticsPayload
import pochita.sideveil.protocol.PanelState
import pochita.sideveil.protocol.PluginSurfaceBackdrop

internal data class PanelBackdropDebugState(
    val themeId: String,
    val colorScheme: String,
    val pluginBackdrop: PluginSurfaceBackdrop,
    val systemBlurSupported: Boolean,
    val appliedBlurRadiusPx: Int,
    val targetBlurRadiusPx: Int,
    val agsl: DebugAgslSnapshot,
)

internal data class PluginManagerDebugState(
    val activePluginId: String,
    val pinnedPluginId: String?,
    val activeHudPluginId: String?,
    val matchedPluginIds: List<String>,
    val webviewDebuggingEnabled: Boolean,
    val webViews: List<DebugWebViewSnapshot>,
)

internal fun buildPanelDebugSnapshot(
    attached: Boolean,
    window: Window?,
    state: PanelState,
    closingInProgress: Boolean,
    surfaceState: PanelSurfaceState,
    backdrop: PanelBackdropDebugState,
    motionProgress: Float,
): DebugPanelWindowSnapshot {
    val lp = window?.attributes
    return DebugPanelWindowSnapshot(
        attached = attached,
        visible = attached && window?.decorView?.isVisible == true,
        state = state,
        closingInProgress = closingInProgress,
        edgeSide = surfaceState.edgeSide,
        presentationMode = surfaceState.presentationMode,
        pluginBackdrop = backdrop.pluginBackdrop,
        railInsetDp = surfaceState.railInsetDp,
        x = lp?.x ?: 0,
        y = lp?.y ?: 0,
        width = lp?.width ?: 0,
        height = lp?.height ?: 0,
        gravity = lp?.gravity ?: 0,
        flags = lp?.flags ?: 0,
        dimAmount = lp?.dimAmount ?: 0f,
        preferredRefreshRateHz = lp?.preferredRefreshRate ?: 0f,
        preferredDisplayModeId = lp?.preferredDisplayModeId ?: 0,
        appliedBlurRadiusPx = backdrop.appliedBlurRadiusPx,
        targetBlurRadiusPx = backdrop.targetBlurRadiusPx,
        systemBlurSupported = backdrop.systemBlurSupported,
        themeId = backdrop.themeId,
        colorScheme = backdrop.colorScheme,
        motionProgress = motionProgress,
        agsl = backdrop.agsl,
    )
}

internal fun buildOverlayWindowDebugSnapshot(
    name: String,
    attached: Boolean,
    visible: Boolean,
    window: Window?,
    alpha: Float = 1f,
    appliedBlurRadiusPx: Int = 0,
    clickThrough: Boolean = false,
    imeFocused: Boolean = false,
    activePluginId: String? = null,
    agsl: DebugAgslSnapshot? = null,
): DebugOverlayWindowSnapshot {
    val lp = window?.attributes
    return DebugOverlayWindowSnapshot(
        name = name,
        attached = attached,
        visible = visible && attached && window?.decorView?.isVisible == true,
        x = lp?.x ?: 0,
        y = lp?.y ?: 0,
        width = lp?.width ?: 0,
        height = lp?.height ?: 0,
        gravity = lp?.gravity ?: 0,
        flags = lp?.flags ?: 0,
        alpha = alpha,
        preferredRefreshRateHz = lp?.preferredRefreshRate ?: 0f,
        preferredDisplayModeId = lp?.preferredDisplayModeId ?: 0,
        appliedBlurRadiusPx = appliedBlurRadiusPx.coerceAtLeast(0),
        clickThrough = clickThrough,
        imeFocused = imeFocused,
        activePluginId = activePluginId,
        agsl = agsl,
    )
}

internal fun buildEdgeWindowDebugSnapshot(
    attached: Boolean,
    rootView: View,
    layoutParams: WindowManager.LayoutParams,
): DebugOverlayWindowSnapshot =
    DebugOverlayWindowSnapshot(
        name = "edge",
        attached = attached,
        visible = attached && rootView.isVisible,
        x = layoutParams.x,
        y = layoutParams.y,
        width = layoutParams.width,
        height = layoutParams.height,
        gravity = layoutParams.gravity,
        flags = layoutParams.flags,
        alpha = rootView.alpha,
        preferredRefreshRateHz = layoutParams.preferredRefreshRate,
        preferredDisplayModeId = layoutParams.preferredDisplayModeId,
        appliedBlurRadiusPx = 0,
        clickThrough = true,
        imeFocused = false,
        activePluginId = null,
        agsl = null,
    )

/**
 * Tracks effective `WebView.setWebContentsDebuggingEnabled(...)` state across global debug
 * config and active plugin `devServerUrl` overrides, applying transitions on the main thread.
 */
class WebViewDebuggingController(
    private val applyDebugging: (Boolean) -> Unit = { enabled ->
        runCatching { WebView.setWebContentsDebuggingEnabled(enabled) }
            .onSuccess {
                Log.i(TAG, "WebView contents debugging ${if (enabled) "enabled" else "disabled"}")
            }.onFailure { err ->
                Log.w(TAG, "setWebContentsDebuggingEnabled($enabled) failed: ${err.message}")
            }
    },
    private val runOnMain: (() -> Unit) -> Unit = { block ->
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            Handler(Looper.getMainLooper()).post(block)
        }
    },
) {
    private var configEnabled = false
    private var hasDevServer = false

    /** Whether Chromium DevTools debugging is currently enabled for this host process. */
    @Volatile
    var effectiveEnabled: Boolean = false
        private set

    /** Updates the config-driven debugging flag and applies transitions if needed. */
    fun setConfigEnabled(enabled: Boolean): Boolean {
        configEnabled = enabled
        return sync()
    }

    /** Updates whether any enabled plugin defines a loopback `devServerUrl`. */
    fun setHasDevServer(present: Boolean): Boolean {
        hasDevServer = present
        return sync()
    }

    private fun sync(): Boolean {
        val desired = configEnabled || hasDevServer
        if (desired != effectiveEnabled) {
            effectiveEnabled = desired
            runOnMain { applyDebugging(desired) }
        }
        return effectiveEnabled
    }

    companion object {
        private const val TAG = "SideVeilWebViewDebug"
    }
}

internal fun buildHostDiagnosticsPayload(
    warmPluginIds: List<String>,
    activeHudPluginId: String?,
    webviewCrashCount: Int = 0,
): HostPluginDiagnosticsPayload =
    HostPluginDiagnosticsPayload(
        warmPluginIds = warmPluginIds,
        activeHudPluginId = activeHudPluginId,
        hostNativeHeapKb = Debug.getNativeHeapAllocatedSize() / 1024L,
        hostPssKb = runCatching { Debug.getPss() }.getOrNull(),
        webviewCrashCount = webviewCrashCount,
    )
