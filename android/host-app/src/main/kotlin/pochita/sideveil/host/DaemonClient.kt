package pochita.sideveil.host

import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import pochita.sideveil.protocol.HostEventEnvelope
import pochita.sideveil.protocol.RpcError
import pochita.sideveil.protocol.RpcRequest
import pochita.sideveil.protocol.RpcResponse
import pochita.sideveil.protocol.SideVeilErrorCodes
import pochita.sideveil.protocol.SideVeilTimeouts
import pochita.sideveil.protocol.WebSocketCodec
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.cancellation.CancellationException

/**
 * Persistent full-duplex loopback WebSocket client connecting SideVeilHost to `sideveild`.
 * Delegates handshake, outbound write serialization, and JSON/frame codecs to [DaemonFrameCodec]
 * and [DaemonSessionWriter], and exposes an optional [onHostRpcRequest] seam for daemon-initiated
 * Host RPCs.
 */
class DaemonClient(
    private val scope: CoroutineScope,
    private val onConnected: () -> Unit,
    private val onDisconnected: () -> Unit,
    private val onEvent: (HostEventEnvelope) -> Unit,
    private val onHostRpcRequest: (suspend (RpcRequest) -> RpcResponse)? = null,
) {
    private val writer =
        DaemonSessionWriter { err ->
            Log.w(TAG, "sendTextFrame failed: ${err.javaClass.simpleName}: ${err.message}")
        }
    private val pendingRpcs = ConcurrentHashMap<String, CompletableDeferred<RpcResponse>>()
    private val generationCounter = AtomicLong(0L)
    private val rpcSeq = AtomicLong(0L)

    @Volatile
    private var port: Int = 0

    @Volatile
    private var token: String = ""

    private var connectionJob: Job? = null

    fun isConnected(): Boolean = writer.isConnected()

    fun currentEndpoint(): Pair<Int, String>? {
        val currentPort = port
        val currentToken = token
        return if (currentPort in 1..65535 && currentToken.isNotBlank()) currentPort to currentToken else null
    }

    @Synchronized
    fun updateEndpoint(
        nextPort: Int,
        nextToken: String,
    ) {
        if (nextPort <= 0 || nextToken.isBlank()) return
        if (nextPort == port && nextToken == token && connectionJob?.isActive == true) {
            return
        }
        port = nextPort
        token = nextToken
        val nextGen = generationCounter.incrementAndGet()
        connectionJob?.cancel()
        writer.closeCurrent()
        connectionJob = scope.launch(Dispatchers.IO) { connectLoop(nextGen) }
    }

    fun sendEvent(
        event: String,
        payload: JsonElement? = null,
    ) {
        writer.sendText(DaemonFrameCodec.encodeEvent(event, payload))
    }

    suspend fun forwardRpc(req: RpcRequest): RpcResponse {
        if (!isConnected()) {
            return RpcResponse(id = req.id, error = RpcError(-32001, "Daemon WebSocket disconnected"))
        }

        val wireId = "h${rpcSeq.incrementAndGet()}_${req.id}"
        val wireJson = DaemonFrameCodec.encodeRpcRequest(req.copy(id = wireId))
        val deferred = CompletableDeferred<RpcResponse>()
        pendingRpcs[wireId] = deferred

        val forwardTimeoutMs = SideVeilTimeouts.resolveForwardTimeoutMs(req)
        return try {
            if (!writer.sendText(wireJson)) {
                RpcResponse(id = req.id, error = RpcError(-32001, "Failed to transmit RPC to daemon"))
            } else {
                withTimeout(forwardTimeoutMs) { deferred.await() }.copy(id = req.id)
            }
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            RpcResponse(id = req.id, error = RpcError(-32002, t.message ?: "RPC timed out"))
        } finally {
            pendingRpcs.remove(wireId)
        }
    }

    fun close() {
        generationCounter.incrementAndGet()
        connectionJob?.cancel()
        connectionJob = null
        failAllPending("DaemonClient closed")
        writer.shutdown()
    }

    private suspend fun connectLoop(generation: Long) {
        var attempt = 0
        while (canContinueConnecting(generation)) {
            val connected = runSingleSession(generation)
            if (connected) attempt = 0
            if (generation != generationCounter.get()) break
            delay(BACKOFF_MS[attempt.coerceAtMost(BACKOFF_MS.lastIndex)])
            attempt++
        }
    }

    private fun canContinueConnecting(generation: Long): Boolean =
        scope.isActive && generation == generationCounter.get() && port > 0 && token.isNotEmpty()

    private fun runSingleSession(generation: Long): Boolean {
        val currentPort = port
        val currentToken = token
        var loopSocket: Socket? = null
        var connected = false
        try {
            val socket = DaemonFrameCodec.openHandshakeSocket(currentPort, currentToken)
            loopSocket = socket
            if (generation != generationCounter.get()) return false
            writer.attach(socket)
            connected = true
            Log.i(TAG, "Connected full-duplex WebSocket to 127.0.0.1:$currentPort")
            onConnected()
            readIncomingFrames(socket, generation)
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            if (generation == generationCounter.get()) {
                Log.w(TAG, "WebSocket loop error: ${t.message}")
            }
        } finally {
            cleanupSession(loopSocket, generation, connected)
        }
        return connected
    }

    private fun readIncomingFrames(
        socket: Socket,
        generation: Long,
    ) {
        val input = socket.getInputStream()
        var running = true
        while (running && isReaderActive(socket, generation)) {
            running =
                DaemonFrameCodec.readAndDispatchFrame(
                    input = input,
                    onClose = writer::sendClose,
                    onPing = writer::sendPong,
                    onBinary = { size -> Log.d(TAG, "Received binary frame ($size bytes)") },
                    onMessage = ::handleInboundMessage,
                )
        }
    }

    private fun isReaderActive(
        socket: Socket,
        generation: Long,
    ): Boolean = scope.isActive && generation == generationCounter.get() && !socket.isClosed

    private fun handleInboundMessage(message: DaemonInboundMessage) {
        when (message) {
            is DaemonInboundMessage.RpcReply -> pendingRpcs.remove(message.response.id)?.complete(message.response)
            is DaemonInboundMessage.Event -> onEvent(message.envelope)
            is DaemonInboundMessage.InboundRpc -> handleInboundRpc(message.request)
        }
    }

    private fun handleInboundRpc(request: RpcRequest) {
        val handler = onHostRpcRequest
        if (handler == null) {
            val unsupported =
                RpcResponse(
                    id = request.id,
                    error = RpcError(SideVeilErrorCodes.METHOD_NOT_FOUND, "Method not found: ${request.method}"),
                )
            writer.sendText(DaemonFrameCodec.encodeRpcResponse(unsupported))
            return
        }
        scope.launch {
            val response =
                runCatching { handler(request) }.getOrElse { err ->
                    if (err is CancellationException) throw err
                    RpcResponse(
                        id = request.id,
                        error = RpcError(SideVeilErrorCodes.INTERNAL_ERROR, err.message ?: "Host RPC failed"),
                    )
                }
            writer.sendText(DaemonFrameCodec.encodeRpcResponse(response.copy(id = request.id)))
        }
    }

    private fun cleanupSession(
        loopSocket: Socket?,
        generation: Long,
        connected: Boolean,
    ) {
        runCatching { loopSocket?.getInputStream()?.let(WebSocketCodec::clearStreamState) }
        val detachedActive = writer.detachIfSame(loopSocket)
        runCatching { loopSocket?.close() }
        val isCurrentGen = generation == generationCounter.get()
        if (detachedActive || isCurrentGen) failAllPending("Daemon WebSocket connection lost")
        if (connected && (detachedActive || isCurrentGen)) onDisconnected()
    }

    private fun failAllPending(reason: String) {
        val entries = pendingRpcs.entries.toList()
        pendingRpcs.clear()
        for ((id, deferred) in entries) {
            deferred.complete(RpcResponse(id = id, error = RpcError(-32001, reason)))
        }
    }

    companion object {
        private const val TAG = "SideVeilDaemonClient"
        private val BACKOFF_MS = longArrayOf(1_000L, 2_000L, 5_000L, 10_000L)
    }
}
