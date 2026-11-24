package pochita.sideveil.host.service

import android.content.Context
import android.view.WindowManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import pochita.sideveil.host.edge.EdgeIndicatorWindow
import pochita.sideveil.host.overlay.EdgeHapticsController
import pochita.sideveil.host.panel.SidebarPanelWindow
import pochita.sideveil.host.plugin.PluginWebViewManager
import pochita.sideveil.host.rail.PluginRailWindow
import pochita.sideveil.host.rail.RailQuickAction
import pochita.sideveil.protocol.BUILTIN_DIAG_PLUGIN_ID
import pochita.sideveil.protocol.EdgeRepositionPayload
import pochita.sideveil.protocol.EdgeRepositionPhase
import pochita.sideveil.protocol.EdgeSide
import pochita.sideveil.protocol.GestureDragEndPayload
import pochita.sideveil.protocol.GestureDragMovePayload
import pochita.sideveil.protocol.GestureDragStartPayload
import pochita.sideveil.protocol.GestureFullscreenHintPayload
import pochita.sideveil.protocol.RpcRequest
import pochita.sideveil.protocol.SideVeilConfig
import pochita.sideveil.protocol.SideVeilMethods

/**
 * Coordinates edge gesture haptics, threshold confirmation, one-stroke blind Rail scrubbing,
 * handle reposition previews, and Rail quick-menu RPC actions on behalf of [pochita.sideveil.host.HostService].
 */
class HostGestureCoordinator(
    private val context: Context,
    private val windowManager: WindowManager,
    private val scope: CoroutineScope,
    private val haptics: EdgeHapticsController,
    private val edgeIndicator: EdgeIndicatorWindow,
    private val pluginRail: PluginRailWindow,
    private val panelWindow: SidebarPanelWindow,
    private val daemonClient: DaemonClient,
    private val pluginManager: PluginWebViewManager,
    private val configProvider: () -> SideVeilConfig,
    private val onSelectPlugin: (String) -> Unit,
    private val onBeforeGestureOpen: () -> Unit = {},
) : HostGestureEventCallbacks {
    private var thresholdHapticFired = false
    private var lastRepositionSide: EdgeSide? = null

    override fun onGestureStart(decoded: GestureDragStartPayload) {
        thresholdHapticFired = false
        onBeforeGestureOpen()
        haptics.onPilfer()
        panelWindow.onGestureStart(decoded.startYFraction)
    }

    override fun onGestureMove(decoded: GestureDragMovePayload) {
        val density =
            context.resources.displayMetrics.density
                .coerceAtLeast(1f)
        val triggerPx = configProvider().edge.triggerDistanceDp * density
        if (!thresholdHapticFired && decoded.deltaX >= triggerPx) {
            thresholdHapticFired = true
            haptics.onThresholdCrossed()
        }

        edgeIndicator.setDragPulse(decoded.progress)
        panelWindow.onGestureMove(decoded.progress)

        val bounds = runCatching { windowManager.currentWindowMetrics.bounds }.getOrNull()
        val screenW = bounds?.width() ?: context.resources.displayMetrics.widthPixels
        val screenH = bounds?.height() ?: context.resources.displayMetrics.heightPixels
        val hovered = pluginRail.updateScrubHighlight(decoded.rawX, decoded.rawY, decoded.progress, screenW, screenH)
        if (hovered != null) {
            haptics.onRailSwitch()
        }
    }

    override fun onGestureEnd(decoded: GestureDragEndPayload) {
        thresholdHapticFired = false
        edgeIndicator.setDragPulse(0f)
        val scrubTarget = pluginRail.consumeScrubTarget()
        if (decoded.shouldOpen && scrubTarget != null) {
            onSelectPlugin(scrubTarget)
        } else if (decoded.canceled) {
            haptics.onCancel()
        }
        panelWindow.onGestureEnd(decoded.shouldOpen, decoded.velocityPxPerSec)
    }

    override fun onFullscreenHint(decoded: GestureFullscreenHintPayload) {
        haptics.onFullscreenHint()
        edgeIndicator.pulseFullscreenHint()
    }

    override fun onEdgeReposition(decoded: EdgeRepositionPayload) {
        when (decoded.phase) {
            EdgeRepositionPhase.START -> {
                lastRepositionSide = decoded.side
                haptics.onRepositionStart()
                edgeIndicator.setRepositionPreview(true, decoded.side, decoded.topPercent)
            }

            EdgeRepositionPhase.MOVE -> {
                if (lastRepositionSide != null && lastRepositionSide != decoded.side) {
                    haptics.onRepositionFlip()
                }
                lastRepositionSide = decoded.side
                edgeIndicator.setRepositionPreview(true, decoded.side, decoded.topPercent)
            }

            EdgeRepositionPhase.END -> {
                lastRepositionSide = null
                if (decoded.committed) haptics.onRepositionCommit() else haptics.onCancel()
                edgeIndicator.setRepositionPreview(false, decoded.side, decoded.topPercent)
            }
        }
    }

    /** Executes a long-press context menu action from [PluginRailWindow] against `sideveild`. */
    fun executeRailQuickAction(
        pluginId: String,
        action: RailQuickAction,
    ) {
        haptics.onRailSwitch()
        if (action == RailQuickAction.REFRESH_WEBVIEW) {
            pluginManager.reloadPluginWebView(pluginId)
            return
        }
        val rpc =
            when (action) {
                RailQuickAction.PIN_TOP -> {
                    val nextPriority = (pluginManager.maxPluginPriority() + 10).coerceAtMost(1000)
                    RpcRequest(
                        id = "rail-pin-${System.nanoTime()}",
                        method = SideVeilMethods.PLUGINS_SET_PRIORITY,
                        params =
                            buildJsonObject {
                                put("pluginId", JsonPrimitive(pluginId))
                                put("priority", JsonPrimitive(nextPriority))
                            },
                        pluginId = BUILTIN_DIAG_PLUGIN_ID,
                    )
                }

                RailQuickAction.DISABLE -> {
                    RpcRequest(
                        id = "rail-dis-${System.nanoTime()}",
                        method = SideVeilMethods.PLUGINS_SET_ENABLED,
                        params =
                            buildJsonObject {
                                put("pluginId", JsonPrimitive(pluginId))
                                put("enabled", JsonPrimitive(false))
                            },
                        pluginId = BUILTIN_DIAG_PLUGIN_ID,
                    )
                }

                RailQuickAction.RELOAD -> {
                    RpcRequest(
                        id = "rail-rel-${System.nanoTime()}",
                        method = SideVeilMethods.PLUGINS_RELOAD,
                        pluginId = BUILTIN_DIAG_PLUGIN_ID,
                    )
                }

                RailQuickAction.REFRESH_WEBVIEW -> {
                    return
                }
            }
        scope.launch {
            runCatching { daemonClient.forwardRpc(rpc) }
        }
    }
}
