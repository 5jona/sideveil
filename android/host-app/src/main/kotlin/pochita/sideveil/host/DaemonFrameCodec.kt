package pochita.sideveil.host

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
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Serializes outbound WebSocket frame writes onto a bounded single-threaded executor and owns
 * active socket attach/detach synchronization for [DaemonClient].
 */
internal class DaemonSessionWriter(
    private val onWriteError: (Throwable) -> Unit,
) {
    private val writeLock = Any()
    private val writeExecutor =
        ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(128))

    @Volatile
    private var activeSocket: Socket? = null

    @Volatile
    private var activeOutput: OutputStream? = null

    fun isConnected(): Boolean = activeSocket?.let { !it.isClosed && it.isConnected } == true

    fun attach(socket: Socket) {
        synchronized(writeLock) {
            activeSocket = socket
            activeOutput = socket.getOutputStream()
        }
    }

    fun detachIfSame(socket: Socket?): Boolean {
        synchronized(writeLock) {
            if (socket != null && activeSocket === socket) {
                activeSocket = null
                activeOutput = null
                return true
            }
        }
        return false
    }

    fun sendText(text: String): Boolean =
        enqueueWrite { output ->
            runCatching {
                WebSocketCodec.writeText(output, text, mask = true)
            }.onFailure { err ->
                onWriteError(err)
                closeCurrent()
            }
        }

    fun sendPong(payload: ByteArray) {
        enqueueWrite { output -> runCatching { WebSocketCodec.writePong(output, payload, mask = true) } }
    }

    fun sendClose() {
        synchronized(writeLock) {
            val output = activeOutput ?: return
            runCatching { WebSocketCodec.writeClose(output, 1000, "Peer close acknowledged", mask = true) }
        }
    }

    fun closeCurrent() {
        synchronized(writeLock) {
            val sock = activeSocket
            activeSocket = null
            activeOutput = null
            runCatching { sock?.close() }
        }
    }

    fun shutdown() {
        closeCurrent()
        writeExecutor.shutdownNow()
    }

    private fun enqueueWrite(action: (OutputStream) -> Unit): Boolean {
        if (activeOutput == null || writeExecutor.isShutdown) return false
        return try {
            writeExecutor.execute {
                synchronized(writeLock) {
                    val output = activeOutput ?: return@execute
                    action(output)
                }
            }
            true
        } catch (_: RejectedExecutionException) {
            false
        }
    }
}

/**
 * Decoded inbound text messages delivered over the `sideveild` full-duplex WebSocket session,
 * supporting JSON-RPC responses, inbound Host RPC requests, and typed host events.
 */
internal sealed interface DaemonInboundMessage {
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
internal object DaemonFrameCodec {
    private const val LOOPBACK_HOST = "127.0.0.1"
    private const val WS_PATH = "/ws"
    private const val CONNECT_TIMEOUT_MS = 3_000
    private const val READ_TIMEOUT_MS = 45_000

    fun openHandshakeSocket(
        port: Int,
        token: String,
    ): Socket {
        val socket = Socket()
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
    }

    fun encodeEvent(
        event: String,
        payload: JsonElement? = null,
    ): String = SideVeilWireJson.encodeToString(HostEventEnvelope(event = event, payload = payload))

    fun encodeRpcRequest(request: RpcRequest): String = SideVeilWireJson.encodeToString(request)

    fun encodeRpcResponse(response: RpcResponse): String = SideVeilWireJson.encodeToString(response)

    fun decodeIncomingText(text: String): DaemonInboundMessage? {
        val obj = runCatching { SideVeilWireJson.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
        if (obj.containsKey("jsonrpc") && obj.containsKey("id")) {
            val methodElem = obj["method"]
            if (methodElem != null) {
                if (methodElem is JsonNull) return null
                return runCatching { SideVeilWireJson.decodeFromJsonElement<RpcRequest>(obj) }
                    .getOrNull()
                    ?.let(DaemonInboundMessage::InboundRpc)
            }
            return runCatching { SideVeilWireJson.decodeFromJsonElement<RpcResponse>(obj) }
                .getOrNull()
                ?.let(DaemonInboundMessage::RpcReply)
        }
        if (obj.containsKey("event")) {
            return runCatching { SideVeilWireJson.decodeFromJsonElement<HostEventEnvelope>(obj) }
                .getOrNull()
                ?.let(DaemonInboundMessage::Event)
        }
        return null
    }

    fun readAndDispatchFrame(
        input: InputStream,
        onClose: () -> Unit,
        onPing: (ByteArray) -> Unit,
        onBinary: (Int) -> Unit,
        onMessage: (DaemonInboundMessage) -> Unit,
    ): Boolean {
        val frame = WebSocketCodec.readFrame(input, requireMasked = false) ?: return false
        return dispatchFrame(frame, onClose, onPing, onBinary, onMessage)
    }

    private fun dispatchFrame(
        frame: WsFrame,
        onClose: () -> Unit,
        onPing: (ByteArray) -> Unit,
        onBinary: (Int) -> Unit,
        onMessage: (DaemonInboundMessage) -> Unit,
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
                frame.textOrNull()?.let(::decodeIncomingText)?.let(onMessage)
                true
            }
        }
}
