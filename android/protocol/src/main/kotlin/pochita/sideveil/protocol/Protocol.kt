package pochita.sideveil.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

typealias SideVeilEvents = pochita.sideveil.protocol.generated.SideVeilEvents
typealias SideVeilMethods = pochita.sideveil.protocol.generated.SideVeilMethods
typealias SideVeilErrorCodes = pochita.sideveil.protocol.generated.SideVeilErrorCodes
typealias SideVeilThemes = pochita.sideveil.protocol.generated.SideVeilThemes
typealias SideVeilBuiltinDiagnostic = pochita.sideveil.protocol.generated.SideVeilBuiltinDiagnostic
typealias SideVeilBuiltinTestPlugins = pochita.sideveil.protocol.generated.SideVeilBuiltinTestPlugins
typealias SideVeilTimeoutBounds = pochita.sideveil.protocol.generated.SideVeilTimeoutBounds
typealias SideVeilHudDefaults = pochita.sideveil.protocol.generated.SideVeilHudDefaults
typealias SideVeilConfigDefaults = pochita.sideveil.protocol.generated.SideVeilConfigDefaults
typealias SideVeilConfigBounds = pochita.sideveil.protocol.generated.SideVeilConfigBounds
typealias DaemonStatus = pochita.sideveil.protocol.generated.DaemonStatus
typealias DaemonHealth = pochita.sideveil.protocol.generated.DaemonHealth

object SideVeilTimeouts {
    const val MIN_PLUGIN_INVOKE_MS = SideVeilTimeoutBounds.MIN_PLUGIN_INVOKE_MS
    const val DEFAULT_RPC_MS = SideVeilTimeoutBounds.DEFAULT_PLUGIN_INVOKE_MS
    const val MAX_PLUGIN_INVOKE_MS = SideVeilTimeoutBounds.MAX_PLUGIN_INVOKE_MS
    const val FORWARD_WIRE_MARGIN_MS = 1_000L
    const val INSPECT_UI_WIRE_MARGIN_MS = 2_500L

    /**
     * Resolves the bounded WebSocket forwarding timeout for [req], giving `sideveild` a small
     * wire margin so backend timeout diagnostics return cleanly before host cancellation.
     */
    fun resolveForwardTimeoutMs(req: RpcRequest): Long {
        val marginMs =
            when (req.method) {
                SideVeilMethods.PLUGIN_INVOKE -> FORWARD_WIRE_MARGIN_MS
                SideVeilMethods.DEBUG_INSPECT_UI -> INSPECT_UI_WIRE_MARGIN_MS
                else -> return DEFAULT_RPC_MS
            }
        val requested =
            (req.params as? JsonObject)
                ?.get("timeoutMs")
                ?.jsonPrimitive
                ?.longOrNull
                ?: return DEFAULT_RPC_MS
        val bounded = requested.coerceIn(MIN_PLUGIN_INVOKE_MS, MAX_PLUGIN_INVOKE_MS)
        return bounded + marginMs
    }
}

@Serializable
data class DaemonEndpoint(
    val port: Int,
    val token: String,
)

@Serializable
enum class PanelCommand {
    @SerialName("open")
    OPEN,

    @SerialName("close")
    CLOSE,

    @SerialName("toggle")
    TOGGLE,
}

@Serializable
data class RpcError(
    val code: Int,
    val message: String,
)

@Serializable
data class RpcRequest(
    val jsonrpc: String = "2.0",
    val id: String,
    val method: String,
    val params: JsonElement? = null,
    val pluginId: String? = null,
)

@Serializable
data class RpcResponse(
    val jsonrpc: String = "2.0",
    val id: String,
    val result: JsonElement? = null,
    val error: RpcError? = null,
)

@Serializable
data class HostEventEnvelope(
    val event: String,
    val payload: JsonElement? = null,
)
