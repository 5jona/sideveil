package pochita.sideveil.host.service

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import pochita.sideveil.protocol.HostEventEnvelope
import pochita.sideveil.protocol.RpcRequest
import pochita.sideveil.protocol.RpcResponse
import pochita.sideveil.protocol.SideVeilWireJson
import pochita.sideveil.protocol.WebSocketCodec
import pochita.sideveil.protocol.WsFrame
import pochita.sideveil.protocol.WsOpcode
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Decoded inbound text messages delivered over the `sideveild` full-duplex WebSocket session,
 * supporting JSON-RPC responses, inbound Host RPC requests, and typed host events.
 */
sealed interface DaemonInboundMessage {
    data class RpcReply(
        val response: RpcResponse,
    ) : DaemonInboundMessage

    data class InboundRpc(
        val request: RpcRequest,
    ) : DaemonInboundMessage

    data class Event(
        val envelope: HostEventEnvelope,
    ) : DaemonInboundMessage
}

/**
 * Pure-Kotlin loopback WebSocket handshake, wire JSON codec, and frame dispatcher for
 * [DaemonClient].
 */
object DaemonFrameCodec {
    private const val LOOPBACK_HOST = "127.0.0.1"
    private const val WS_PATH = "/ws"
    private const val CONNECT_TIMEOUT_MS = 3_000
    private const val READ_TIMEOUT_MS = 45_000

    /** Connects and completes a client WebSocket handshake against `127.0.0.1:[port]/ws`. */
    fun openHandshakeSocket(
        port: Int,
        token: String,
        onSocketCreated: (Socket) -> Unit = {},
    ): Socket {
        val socket = Socket()
        onSocketCreated(socket)
        try {
            socket.tcpNoDelay = true
            socket.connect(InetSocketAddress(LOOPBACK_HOST, port), CONNECT_TIMEOUT_MS)
            socket.soTimeout = READ_TIMEOUT_MS
            WebSocketCodec.performClientHandshake(
                input = socket.getInputStream(),
                output = socket.getOutputStream(),
                path = WS_PATH,
                hostHeader = "$LOOPBACK_HOST:$port",
                extraHeaders = mapOf("Authorization" to "Bearer $token"),
            )
            return socket
        } catch (t: Throwable) {
            runCatching { socket.close() }
            throw t
        }
    }

    /** Encodes a [HostEventEnvelope] to wire JSON. */
    fun encodeEvent(
        event: String,
        payload: JsonElement? = null,
    ): String = SideVeilWireJson.encodeToString(HostEventEnvelope(event = event, payload = payload))

    /** Encodes an [RpcRequest] to wire JSON. */
    fun encodeRpcRequest(request: RpcRequest): String = SideVeilWireJson.encodeToString(request)

    /** Encodes an [RpcResponse] to wire JSON. */
    fun encodeRpcResponse(response: RpcResponse): String = SideVeilWireJson.encodeToString(response)

    /** Classifies and decodes an incoming text frame into a [DaemonInboundMessage]. */
    fun decodeIncomingText(
        text: String,
        onDecodeError: ((String) -> Unit)? = null,
    ): DaemonInboundMessage? {
        val obj =
            runCatching { SideVeilWireJson.parseToJsonElement(text).jsonObject }
                .onFailure { err -> onDecodeError?.invoke("invalid JSON object: ${err.message}") }
                .getOrNull() ?: return null
        if (obj.containsKey("jsonrpc") && obj.containsKey("id")) {
            val methodElem = obj["method"]
            if (methodElem != null) {
                if (methodElem is JsonNull) {
                    onDecodeError?.invoke("RPC request had null method")
                    return null
                }
                return runCatching { SideVeilWireJson.decodeFromJsonElement<RpcRequest>(obj) }
                    .onFailure { err -> onDecodeError?.invoke("RpcRequest decode failed: ${err.message}") }
                    .getOrNull()
                    ?.let(DaemonInboundMessage::InboundRpc)
            }
            return runCatching { SideVeilWireJson.decodeFromJsonElement<RpcResponse>(obj) }
                .onFailure { err -> onDecodeError?.invoke("RpcResponse decode failed: ${err.message}") }
                .getOrNull()
                ?.let(DaemonInboundMessage::RpcReply)
        }
        if (obj.containsKey("event")) {
            return runCatching { SideVeilWireJson.decodeFromJsonElement<HostEventEnvelope>(obj) }
                .onFailure { err -> onDecodeError?.invoke("HostEventEnvelope decode failed: ${err.message}") }
                .getOrNull()
                ?.let(DaemonInboundMessage::Event)
        }
        onDecodeError?.invoke("unrecognized frame shape (keys=${obj.keys.take(6)})")
        return null
    }

    /** Reads one WebSocket frame from [input] and dispatches control or text callbacks. */
    fun readAndDispatchFrame(
        input: InputStream,
        onClose: () -> Unit,
        onPing: (ByteArray) -> Unit,
        onBinary: (Int) -> Unit,
        onMessage: (DaemonInboundMessage) -> Unit,
        onDecodeError: ((String) -> Unit)? = null,
    ): Boolean {
        val frame = WebSocketCodec.readFrame(input, requireMasked = false) ?: return false
        return dispatchFrame(frame, onClose, onPing, onBinary, onMessage, onDecodeError)
    }

    private fun dispatchFrame(
        frame: WsFrame,
        onClose: () -> Unit,
        onPing: (ByteArray) -> Unit,
        onBinary: (Int) -> Unit,
        onMessage: (DaemonInboundMessage) -> Unit,
        onDecodeError: ((String) -> Unit)?,
    ): Boolean =
        when (frame.opcode) {
            WsOpcode.CLOSE -> {
                onClose()
                false
            }

            WsOpcode.PING -> {
                onPing(frame.payload)
                true
            }

            WsOpcode.PONG -> {
                true
            }

            WsOpcode.BINARY -> {
                onBinary(frame.payload.size)
                true
            }

            WsOpcode.TEXT -> {
                frame.textOrNull()?.let { decodeIncomingText(it, onDecodeError) }?.let(onMessage)
                true
            }
        }
}
