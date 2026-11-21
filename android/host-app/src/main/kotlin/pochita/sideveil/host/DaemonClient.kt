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
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import pochita.sideveil.protocol.HostEventEnvelope
import pochita.sideveil.protocol.RpcError
import pochita.sideveil.protocol.RpcRequest
import pochita.sideveil.protocol.RpcResponse
import pochita.sideveil.protocol.SideVeilWireJson
import pochita.sideveil.protocol.WebSocketCodec
import pochita.sideveil.protocol.WsOpcode
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.cancellation.CancellationException

/**
 * Persistent full-duplex loopback WebSocket client connecting SideVeilHost to the UID 1000 daemon.
 */
class DaemonClient(
    private val scope: CoroutineScope,
    private val onConnected: () -> Unit,
    private val onDisconnected: () -> Unit,
    private val onEvent: (HostEventEnvelope) -> Unit,
) {
    private val writeLock = Any()
    private val writeExecutor =
        ThreadPoolExecutor(
            1,
            1,
            0L,
            TimeUnit.MILLISECONDS,
            ArrayBlockingQueue(128),
        )
    private val pendingRpcs = ConcurrentHashMap<String, CompletableDeferred<RpcResponse>>()
    private val generationCounter = AtomicLong(0L)
    private val rpcSeq = AtomicLong(0L)

    @Volatile
    private var port: Int = 0

    @Volatile
    private var token: String = ""

    @Volatile
    private var activeSocket: Socket? = null

    @Volatile
    private var activeOutput: OutputStream? = null

    private var connectionJob: Job? = null

    fun isConnected(): Boolean = activeSocket?.let { !it.isClosed && it.isConnected } == true

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
        closeCurrentSocket()
        connectionJob = scope.launch(Dispatchers.IO) { connectLoop(nextGen) }
    }

    fun sendEvent(
        event: String,
        payload: JsonElement? = null,
    ) {
        val wire = SideVeilWireJson.encodeToString(HostEventEnvelope(event = event, payload = payload))
        sendTextFrame(wire)
    }

    suspend fun forwardRpc(req: RpcRequest): RpcResponse {
        if (!isConnected()) {
            return RpcResponse(id = req.id, error = RpcError(-32001, "Daemon WebSocket disconnected"))
        }

        val wireId = "h${rpcSeq.incrementAndGet()}_${req.id}"
        val wireJson = SideVeilWireJson.encodeToString(req.copy(id = wireId))
        val deferred = CompletableDeferred<RpcResponse>()
        pendingRpcs[wireId] = deferred

        return try {
            if (!sendTextFrame(wireJson)) {
                RpcResponse(id = req.id, error = RpcError(-32001, "Failed to transmit RPC to daemon"))
            } else {
                withTimeout(8_000L) { deferred.await() }.copy(id = req.id)
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
        closeCurrentSocket()
        failAllPending("DaemonClient closed")
        writeExecutor.shutdownNow()
    }

    private suspend fun connectLoop(generation: Long) {
        var attempt = 0
        while (canContinueConnecting(generation)) {
            val connected = runSingleSession(generation)
            if (connected) {
                attempt = 0
            }
            if (generation != generationCounter.get()) {
                break
            }
            val delayMs = BACKOFF_MS[attempt.coerceAtMost(BACKOFF_MS.lastIndex)]
            attempt++
            delay(delayMs)
        }
    }

    private fun canContinueConnecting(generation: Long): Boolean {
        val currentGeneration = generation == generationCounter.get()
        val hasEndpoint = port > 0 && token.isNotEmpty()
        return scope.isActive && currentGeneration && hasEndpoint
    }

    private fun runSingleSession(generation: Long): Boolean {
        val currentPort = port
        val currentToken = token
        var loopSocket: Socket? = null
        var connected = false
        try {
            val socket = openHandshakeSocket(currentPort, currentToken)
            loopSocket = socket
            if (generation != generationCounter.get()) {
                return false
            }
            attachActiveSocket(socket)
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

    private fun openHandshakeSocket(
        currentPort: Int,
        currentToken: String,
    ): Socket {
        val socket = Socket()
        socket.tcpNoDelay = true
        socket.connect(InetSocketAddress("127.0.0.1", currentPort), 3_000)
        socket.soTimeout = 45_000
        WebSocketCodec.performClientHandshake(
            input = socket.getInputStream(),
            output = socket.getOutputStream(),
            path = "/ws",
            hostHeader = "127.0.0.1:$currentPort",
            extraHeaders = mapOf("Authorization" to "Bearer $currentToken"),
        )
        return socket
    }

    private fun attachActiveSocket(socket: Socket) {
        synchronized(writeLock) {
            activeSocket = socket
            activeOutput = socket.getOutputStream()
        }
    }

    private fun readIncomingFrames(
        socket: Socket,
        generation: Long,
    ) {
        val input = socket.getInputStream()
        var running = true
        while (running && isReaderActive(socket, generation)) {
            val frame = WebSocketCodec.readFrame(input, requireMasked = false)
            running = frame != null && handleFrame(frame)
        }
    }

    private fun isReaderActive(
        socket: Socket,
        generation: Long,
    ): Boolean = scope.isActive && generation == generationCounter.get() && !socket.isClosed

    private fun handleFrame(frame: pochita.sideveil.protocol.WsFrame): Boolean =
        when (frame.opcode) {
            WsOpcode.CLOSE -> {
                sendCloseFrame()
                false
            }

            WsOpcode.PING -> {
                sendPongFrame(frame.payload)
                true
            }

            WsOpcode.PONG -> {
                true
            }

            WsOpcode.BINARY -> {
                Log.d(TAG, "Received binary frame (${frame.payload.size} bytes)")
                true
            }

            WsOpcode.TEXT -> {
                frame.textOrNull()?.let(::handleIncomingText)
                true
            }
        }

    private fun cleanupSession(
        loopSocket: Socket?,
        generation: Long,
        connected: Boolean,
    ) {
        runCatching { loopSocket?.getInputStream()?.let(WebSocketCodec::clearStreamState) }
        var detachedActive = false
        synchronized(writeLock) {
            if (loopSocket != null && activeSocket === loopSocket) {
                activeSocket = null
                activeOutput = null
                detachedActive = true
            }
        }
        runCatching { loopSocket?.close() }
        val isCurrentGen = generation == generationCounter.get()
        if (detachedActive || isCurrentGen) {
            failAllPending("Daemon WebSocket connection lost")
        }
        if (connected && (detachedActive || isCurrentGen)) {
            onDisconnected()
        }
    }

    private fun handleIncomingText(text: String) {
        val obj = runCatching { SideVeilWireJson.parseToJsonElement(text).jsonObject }.getOrNull() ?: return

        if (obj.containsKey("jsonrpc") && obj.containsKey("id")) {
            val resp = runCatching { SideVeilWireJson.decodeFromJsonElement<RpcResponse>(obj) }.getOrNull()
            if (resp != null) {
                pendingRpcs.remove(resp.id)?.complete(resp)
                return
            }
        }

        if (obj.containsKey("event")) {
            val env = runCatching { SideVeilWireJson.decodeFromJsonElement<HostEventEnvelope>(obj) }.getOrNull()
            if (env != null) {
                onEvent(env)
            }
        }
    }

    private fun sendTextFrame(text: String): Boolean {
        if (activeOutput == null || writeExecutor.isShutdown) return false
        return try {
            writeExecutor.execute {
                synchronized(writeLock) {
                    val output = activeOutput ?: return@execute
                    runCatching {
                        WebSocketCodec.writeText(output, text, mask = true)
                    }.onFailure { err ->
                        Log.w(TAG, "sendTextFrame failed: ${err.javaClass.simpleName}: ${err.message}")
                        closeCurrentSocket()
                    }
                }
            }
            true
        } catch (_: RejectedExecutionException) {
            false
        }
    }

    private fun sendPongFrame(payload: ByteArray) {
        if (activeOutput == null || writeExecutor.isShutdown) return
        try {
            writeExecutor.execute {
                synchronized(writeLock) {
                    val output = activeOutput ?: return@execute
                    runCatching { WebSocketCodec.writePong(output, payload, mask = true) }
                }
            }
        } catch (_: RejectedExecutionException) {
            // Ignore pong failure if write queue is saturated or shutting down.
        }
    }

    private fun sendCloseFrame() {
        synchronized(writeLock) {
            val output = activeOutput ?: return
            runCatching { WebSocketCodec.writeClose(output, 1000, "Peer close acknowledged", mask = true) }
        }
    }

    private fun closeCurrentSocket() {
        synchronized(writeLock) {
            val sock = activeSocket
            activeSocket = null
            activeOutput = null
            runCatching { sock?.close() }
        }
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
