package pochita.sideveil.host

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.view.Display
import android.view.WindowManager
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import pochita.sideveil.protocol.DebugDisplaySnapshot
import pochita.sideveil.protocol.DebugInspectUiParams
import pochita.sideveil.protocol.DebugInspectUiSnapshot
import pochita.sideveil.protocol.DebugSafeBoundsSnapshot
import pochita.sideveil.protocol.PanelState
import pochita.sideveil.protocol.RpcError
import pochita.sideveil.protocol.RpcRequest
import pochita.sideveil.protocol.RpcResponse
import pochita.sideveil.protocol.SideVeilErrorCodes
import pochita.sideveil.protocol.SideVeilMethods
import pochita.sideveil.protocol.SideVeilWireJson
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Pure-Kotlin dispatcher for daemon-initiated reverse debugging RPCs (`debug.inspectUi`,
 * `debug.resetFrameMetrics`). Kept Android-free for host-side JVM unit tests.
 */
internal class HostDebugRpcRouter(
    private val inspectUi: suspend (timeoutMs: Long) -> DebugInspectUiSnapshot,
    private val resetFrameMetrics: () -> Unit,
) {
    suspend fun handleRpcRequest(req: RpcRequest): RpcResponse =
        when (req.method) {
            SideVeilMethods.DEBUG_INSPECT_UI -> {
                val timeoutMs = resolveInspectTimeoutMs(req.params)
                val snapshot = inspectUi(timeoutMs)
                RpcResponse(
                    id = req.id,
                    result = SideVeilWireJson.encodeToJsonElement(snapshot),
                )
            }

            SideVeilMethods.DEBUG_RESET_FRAME_METRICS -> {
                resetFrameMetrics()
                RpcResponse(
                    id = req.id,
                    result = buildJsonObject { put("ok", JsonPrimitive(true)) },
                )
            }

            else -> {
                RpcResponse(
                    id = req.id,
                    error = RpcError(SideVeilErrorCodes.METHOD_NOT_FOUND, "Method not found: ${req.method}"),
                )
            }
        }

    companion object {
        const val MIN_INSPECT_TIMEOUT_MS = 500L
        const val DEFAULT_INSPECT_TIMEOUT_MS = 3_000L
        const val MAX_INSPECT_TIMEOUT_MS = 55_000L

        fun resolveInspectTimeoutMs(params: JsonElement?): Long {
            val requested =
                params?.let { elem ->
                    runCatching {
                        SideVeilWireJson.decodeFromJsonElement<DebugInspectUiParams>(elem)
                    }.getOrNull()?.timeoutMs
                }
            return (requested ?: DEFAULT_INSPECT_TIMEOUT_MS).coerceIn(
                MIN_INSPECT_TIMEOUT_MS,
                MAX_INSPECT_TIMEOUT_MS,
            )
        }
    }
}

/**
 * Handles daemon-initiated reverse RPC debugging methods (`debug.inspectUi`,
 * `debug.resetFrameMetrics`) by marshalling read-only UI, AGSL, blur, WebView pool, and
 * [WindowFrameMetricsTracker] inspection safely onto the Android main thread with a bounded
 * timeout.
 */
internal class HostDebugInspector(
    private val context: Context,
    private val windowManager: WindowManager,
    private val mainHandler: Handler,
    private val panelWindow: SidebarPanelWindow,
    private val pinnedWindow: PinnedContainerWindow,
    private val nativeHudWindow: NativeHudWindow,
    private val pluginRail: PluginRailWindow,
    private val edgeIndicator: EdgeIndicatorWindow,
    private val pluginManager: PluginWebViewManager,
    private val frameMetricsTracker: WindowFrameMetricsTracker,
) {
    private val router =
        HostDebugRpcRouter(
            inspectUi = ::inspectUi,
            resetFrameMetrics = frameMetricsTracker::reset,
        )

    suspend fun handleRpcRequest(req: RpcRequest): RpcResponse = router.handleRpcRequest(req)

    suspend fun inspectUi(timeoutMs: Long = HostDebugRpcRouter.DEFAULT_INSPECT_TIMEOUT_MS): DebugInspectUiSnapshot =
        runOnMainWithTimeout(timeoutMs) { collectInspectUiSnapshot() }

    private fun collectInspectUiSnapshot(): DebugInspectUiSnapshot {
        val hostPid = Process.myPid()
        val pm = pluginManager.debugState()
        val safe =
            if (panelWindow.currentState == PanelState.OPEN) {
                panelWindow.currentSafeBounds
            } else {
                PanelInsetsCoordinator.resolveBounds(context, windowManager)
            }
        val display = collectDisplaySnapshot(safe)
        val panelSnap = panelWindow.debugState()
        val fallbackDeadlineMs = if (display.refreshRateHz > 0f) 1_000.0 / display.refreshRateHz else 0.0

        return DebugInspectUiSnapshot(
            hostPid = hostPid,
            webviewDebuggingEnabled = pm.webviewDebuggingEnabled,
            cdpSocket = if (pm.webviewDebuggingEnabled) "webview_devtools_remote_$hostPid" else null,
            activePluginId = pm.activePluginId,
            pinnedPluginId = pm.pinnedPluginId,
            activeHudPluginId = pm.activeHudPluginId ?: nativeHudWindow.activePluginId,
            matchedPluginIds = pm.matchedPluginIds,
            display = display,
            safeBounds =
                DebugSafeBoundsSnapshot(
                    screenWidthPx = safe.screenWidthPx,
                    screenHeightPx = safe.screenHeightPx,
                    safeTopPx = safe.safeTopPx,
                    safeBottomPx = safe.safeBottomPx,
                    safeLeftPx = safe.safeLeftPx,
                    safeRightPx = safe.safeRightPx,
                    imeBottomPx = safe.imeBottomPx,
                    isTabletLandscape = safe.isTabletLandscape,
                ),
            agsl = panelSnap.agsl,
            panel = panelSnap,
            pinned = pinnedWindow.debugState(activePluginId = pm.pinnedPluginId),
            hud = nativeHudWindow.debugState(),
            rail = pluginRail.debugState(),
            edge = edgeIndicator.debugState(),
            webViews = pm.webViews,
            frameMetrics = frameMetricsTracker.snapshot(fallbackDeadlineMs),
            windowFrameMetrics = frameMetricsTracker.windowSnapshots(fallbackDeadlineMs),
        )
    }

    private fun collectDisplaySnapshot(safe: PanelSafeBounds): DebugDisplaySnapshot {
        val dm = context.resources.displayMetrics
        val display =
            panelWindow.overlayWindow?.decorView?.display
                ?: runCatching { context.display }.getOrNull()
                ?: context.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)
        val activeMode = display?.mode
        val maxMode = OverlayWindowFlags.resolveMaxRefreshMode(context, display)
        val activeHz = activeMode?.refreshRate ?: display?.refreshRate ?: 0f
        return DebugDisplaySnapshot(
            widthPx = safe.screenWidthPx,
            heightPx = safe.screenHeightPx,
            density = dm.density,
            rotation = display?.rotation ?: 0,
            refreshRateHz = activeHz,
            activeModeId = activeMode?.modeId ?: 0,
            maxRefreshRateHz = maxMode?.refreshRate ?: activeHz,
            maxSupportedModeId = maxMode?.modeId ?: (activeMode?.modeId ?: 0),
            crossWindowBlurSupported = panelWindow.isSystemBlurSupported,
        )
    }

    private suspend fun <T> runOnMainWithTimeout(
        timeoutMs: Long,
        block: () -> T,
    ): T {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return block()
        }
        return withTimeout(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                val runnable =
                    Runnable {
                        if (!cont.isActive) return@Runnable
                        runCatching(block)
                            .onSuccess { value -> if (cont.isActive) cont.resume(value) }
                            .onFailure { err -> if (cont.isActive) cont.resumeWithException(err) }
                    }
                cont.invokeOnCancellation { mainHandler.removeCallbacks(runnable) }
                if (!mainHandler.post(runnable) && cont.isActive) {
                    cont.resumeWithException(IllegalStateException("Main looper unavailable"))
                }
            }
        }
    }
}
