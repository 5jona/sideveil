package pochita.sideveil.host

import kotlinx.serialization.KSerializer
import pochita.sideveil.protocol.ConfigUpdatedPayload
import pochita.sideveil.protocol.DynamicThemePalette
import pochita.sideveil.protocol.EdgeRepositionPayload
import pochita.sideveil.protocol.GestureDragEndPayload
import pochita.sideveil.protocol.GestureDragMovePayload
import pochita.sideveil.protocol.GestureDragStartPayload
import pochita.sideveil.protocol.GestureFullscreenHintPayload
import pochita.sideveil.protocol.HostEventEnvelope
import pochita.sideveil.protocol.HudDismissPayload
import pochita.sideveil.protocol.NativeHudSceneHeader
import pochita.sideveil.protocol.PanelCommand
import pochita.sideveil.protocol.PanelCommandPayload
import pochita.sideveil.protocol.PluginActiveChangedPayload
import pochita.sideveil.protocol.PluginBackendStatePayload
import pochita.sideveil.protocol.PluginScopedEventPayload
import pochita.sideveil.protocol.PluginsSnapshotPayload
import pochita.sideveil.protocol.ScopeChangedPayload
import pochita.sideveil.protocol.SideveilEvents
import pochita.sideveil.protocol.SideveilWireJson

internal interface HostGestureEventCallbacks {
    fun onGestureStart(decoded: GestureDragStartPayload)

    fun onGestureMove(decoded: GestureDragMovePayload)

    fun onGestureEnd(decoded: GestureDragEndPayload)

    fun onFullscreenHint(decoded: GestureFullscreenHintPayload)

    fun onEdgeReposition(decoded: EdgeRepositionPayload)
}

internal interface HostOverlayEventCallbacks {
    fun onThemePaletteUpdated(
        decoded: DynamicThemePalette,
        rawEnvelope: HostEventEnvelope,
    )

    fun onHudSceneUpdated(
        rawSceneJson: String,
        header: NativeHudSceneHeader,
    )

    fun onHudDismissed(decoded: HudDismissPayload)
}

internal interface HostEventCallbacks {
    fun onConfigUpdated(
        decoded: ConfigUpdatedPayload,
        rawEnvelope: HostEventEnvelope,
    )

    fun onScopeChanged(
        decoded: ScopeChangedPayload,
        rawEnvelope: HostEventEnvelope,
    )

    fun onPluginsUpdated(decoded: PluginsSnapshotPayload)

    fun onPluginActiveChanged(pluginId: String)

    fun onPluginBackendState(decoded: PluginBackendStatePayload)

    fun onPluginScopedEvent(decoded: PluginScopedEventPayload)

    fun onPanelCommand(command: PanelCommand)

    fun onFallbackGlobalEvent(envelope: HostEventEnvelope)
}

/**
 * Decodes and routes inbound `sideveild` [HostEventEnvelope] messages to typed
 * [HostEventCallbacks], [HostGestureEventCallbacks], and [HostOverlayEventCallbacks].
 */
internal class HostEventDispatcher(
    private val callbacks: HostEventCallbacks,
    private val gestureCallbacks: HostGestureEventCallbacks,
    private val overlayCallbacks: HostOverlayEventCallbacks,
) {
    private val handlers: Map<String, (HostEventEnvelope) -> Unit> =
        mapOf(
            SideveilEvents.CONFIG_UPDATED to { env ->
                decode(env, ConfigUpdatedPayload.serializer())?.let { callbacks.onConfigUpdated(it, env) }
            },
            SideveilEvents.SCOPE_CHANGED to { env ->
                decode(env, ScopeChangedPayload.serializer())?.let { callbacks.onScopeChanged(it, env) }
            },
            SideveilEvents.PLUGINS_UPDATED to { env ->
                decode(env, PluginsSnapshotPayload.serializer())?.let(callbacks::onPluginsUpdated)
            },
            SideveilEvents.PLUGINS_ACTIVE_CHANGED to { env ->
                decode(env, PluginActiveChangedPayload.serializer())?.let {
                    callbacks.onPluginActiveChanged(it.pluginId)
                }
            },
            SideveilEvents.PLUGIN_BACKEND_STATE to { env ->
                decode(env, PluginBackendStatePayload.serializer())?.let(callbacks::onPluginBackendState)
            },
            SideveilEvents.PLUGIN_EVENT to { env ->
                decode(env, PluginScopedEventPayload.serializer())?.let(callbacks::onPluginScopedEvent)
            },
            SideveilEvents.THEME_PALETTE_UPDATED to { env ->
                decode(env, DynamicThemePalette.serializer())?.let { overlayCallbacks.onThemePaletteUpdated(it, env) }
            },
            SideveilEvents.HUD_SCENE_UPDATED to { env ->
                env.payload?.let { payload ->
                    runCatching {
                        val header = SideveilWireJson.decodeFromJsonElement(NativeHudSceneHeader.serializer(), payload)
                        val rawJson =
                            SideveilWireJson.encodeToString(
                                kotlinx.serialization.json.JsonElement
                                    .serializer(),
                                payload,
                            )
                        overlayCallbacks.onHudSceneUpdated(rawJson, header)
                    }
                }
            },
            SideveilEvents.HUD_DISMISSED to { env ->
                val decoded = decode(env, HudDismissPayload.serializer()) ?: HudDismissPayload()
                overlayCallbacks.onHudDismissed(decoded)
            },
            SideveilEvents.GESTURE_DRAG_START to { env ->
                decode(env, GestureDragStartPayload.serializer())?.let(gestureCallbacks::onGestureStart)
            },
            SideveilEvents.GESTURE_DRAG_MOVE to { env ->
                decode(env, GestureDragMovePayload.serializer())?.let(gestureCallbacks::onGestureMove)
            },
            SideveilEvents.GESTURE_DRAG_END to { env ->
                decode(env, GestureDragEndPayload.serializer())?.let(gestureCallbacks::onGestureEnd)
            },
            SideveilEvents.GESTURE_FULLSCREEN_HINT to { env ->
                decode(env, GestureFullscreenHintPayload.serializer())?.let(gestureCallbacks::onFullscreenHint)
            },
            SideveilEvents.EDGE_REPOSITION to { env ->
                decode(env, EdgeRepositionPayload.serializer())?.let(gestureCallbacks::onEdgeReposition)
            },
            SideveilEvents.PANEL_COMMAND to { env ->
                decode(env, PanelCommandPayload.serializer())?.let { callbacks.onPanelCommand(it.command) }
            },
        )

    fun dispatch(envelope: HostEventEnvelope) {
        val handler = handlers[envelope.event]
        if (handler != null) {
            handler(envelope)
        } else {
            callbacks.onFallbackGlobalEvent(envelope)
        }
    }

    private fun <T> decode(
        envelope: HostEventEnvelope,
        serializer: KSerializer<T>,
    ): T? =
        envelope.payload?.let { element ->
            runCatching { SideveilWireJson.decodeFromJsonElement(serializer, element) }.getOrNull()
        }
}
