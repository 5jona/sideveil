package pochita.sideveil.daemon

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import pochita.sideveil.shared.AppearancePatchParams
import pochita.sideveil.shared.DaemonStatus
import pochita.sideveil.shared.HostEventEnvelope
import pochita.sideveil.shared.HostHelloPayload
import pochita.sideveil.shared.PanelCommand
import pochita.sideveil.shared.PanelState
import pochita.sideveil.shared.PanelStateChangedPayload
import pochita.sideveil.shared.PingResult
import pochita.sideveil.shared.RpcError
import pochita.sideveil.shared.RpcRequest
import pochita.sideveil.shared.RpcResponse
import pochita.sideveil.shared.SideveilConfig
import pochita.sideveil.shared.SideveilEvents
import pochita.sideveil.shared.SideveilMethods
import pochita.sideveil.shared.SideveilWireJson

interface DaemonRpcDelegate {
    fun buildStatus(): DaemonStatus

    fun currentConfig(): SideveilConfig

    fun reloadConfig(): SideveilConfig

    fun patchAppearance(params: AppearancePatchParams): SideveilConfig

    fun sendPanelCommand(command: PanelCommand)

    fun restartHost()

    fun onHostHello(payload: HostHelloPayload)

    fun onPanelStateChanged(state: PanelState)
}

/**
 * Stateless JSON-RPC 2.0 request and incoming host-event dispatcher for [ControlServer].
 */
class RpcDispatcher(
    private val uptimeProvider: () -> Long,
    private val delegate: DaemonRpcDelegate,
) {
    fun executeRpcText(bodyText: String): RpcResponse {
        val req =
            try {
                SideveilWireJson.decodeFromString<RpcRequest>(bodyText)
            } catch (t: Throwable) {
                return RpcResponse(id = "0", error = RpcError(-32700, "Parse error: ${t.message}"))
            }
        return executeRpcRequest(req)
    }

    fun executeRpcRequest(req: RpcRequest): RpcResponse =
        try {
            val result: JsonElement =
                when (req.method) {
                    SideveilMethods.DAEMON_PING -> {
                        SideveilWireJson.encodeToJsonElement(
                            PingResult(
                                pong = true,
                                daemonUptimeMs = uptimeProvider(),
                                timestampMs = System.currentTimeMillis(),
                            ),
                        )
                    }

                    SideveilMethods.DAEMON_STATUS -> {
                        SideveilWireJson.encodeToJsonElement(delegate.buildStatus())
                    }

                    SideveilMethods.DAEMON_LOGS -> {
                        buildJsonObject {
                            put(
                                "lines",
                                buildJsonArray {
                                    DaemonLog.recent().forEach { add(JsonPrimitive(it)) }
                                },
                            )
                        }
                    }

                    SideveilMethods.CONFIG_GET -> {
                        SideveilWireJson.encodeToJsonElement(delegate.currentConfig())
                    }

                    SideveilMethods.CONFIG_RELOAD -> {
                        SideveilWireJson.encodeToJsonElement(delegate.reloadConfig())
                    }

                    SideveilMethods.CONFIG_PATCH_APPEARANCE -> {
                        val patch =
                            if (req.params != null) {
                                SideveilWireJson.decodeFromJsonElement<AppearancePatchParams>(req.params!!)
                            } else {
                                AppearancePatchParams()
                            }
                        SideveilWireJson.encodeToJsonElement(delegate.patchAppearance(patch))
                    }

                    SideveilMethods.PANEL_OPEN -> {
                        delegate.sendPanelCommand(PanelCommand.OPEN)
                        buildJsonObject { put("ok", JsonPrimitive(true)) }
                    }

                    SideveilMethods.PANEL_CLOSE -> {
                        delegate.sendPanelCommand(PanelCommand.CLOSE)
                        buildJsonObject { put("ok", JsonPrimitive(true)) }
                    }

                    SideveilMethods.PANEL_TOGGLE -> {
                        delegate.sendPanelCommand(PanelCommand.TOGGLE)
                        buildJsonObject { put("ok", JsonPrimitive(true)) }
                    }

                    SideveilMethods.HOST_RESTART -> {
                        delegate.restartHost()
                        buildJsonObject { put("ok", JsonPrimitive(true)) }
                    }

                    else -> {
                        return RpcResponse(id = req.id, error = RpcError(-32601, "Method not found: ${req.method}"))
                    }
                }
            RpcResponse(id = req.id, result = result)
        } catch (t: Throwable) {
            DaemonLog.warn("RPC ${req.method} failed", t)
            RpcResponse(id = req.id, error = RpcError(-32000, t.message ?: "RPC execution error"))
        }

    fun dispatchHostEvent(envelope: HostEventEnvelope) {
        when (envelope.event) {
            SideveilEvents.HOST_HELLO -> {
                envelope.payload?.let { payload ->
                    runCatching {
                        SideveilWireJson.decodeFromJsonElement<HostHelloPayload>(payload)
                    }.onSuccess { delegate.onHostHello(it) }
                }
            }

            SideveilEvents.PANEL_STATE_CHANGED -> {
                envelope.payload?.let { payload ->
                    runCatching {
                        SideveilWireJson.decodeFromJsonElement<PanelStateChangedPayload>(payload)
                    }.onSuccess { delegate.onPanelStateChanged(it.state) }
                }
            }
        }
    }
}
