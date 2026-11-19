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
import kotlinx.serialization.json.encodeToJsonElement
import pochita.sideveil.shared.HostEventEnvelope
import pochita.sideveil.shared.RpcError
import pochita.sideveil.shared.RpcRequest
import pochita.sideveil.shared.RpcResponse
import pochita.sideveil.shared.SideveilWireJson
import pochita.sideveil.shared.WebSocketCodec
import pochita.sideveil.shared.WsOpcode
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.coroutines.cancellation.CancellationException

/**
 * Persistent full-duplex loopback WebSocket client connecting SideveilHost to the UID 1000 daemon.
 */
class DaemonClient(
    private val scope: CoroutineScope,
    private val onConnected: () -> Unit,
    private val onDisconnected: () -> Unit,
    private val onEvent: (HostEventEnvelope) -> Unit,
) {
    private val writeLock = Any()
    private val writeExecutor = Executors.newSingleThreadExecutor()
    private val pendingRpcs = ConcurrentHashMap<String, CompletableDeferred<RpcResponse>>()

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
        connectionJob?.cancel()
        closeCurrentSocket()
        connectionJob = scope.launch(Dispatchers.IO) { connectLoop() }
    }

    fun sendEvent(
        event: String,
        payload: JsonElement? = null,
    ) {
        val wire = SideveilWireJson.encodeToString(HostEventEnvelope(event = event, payload = payload))
        sendTextFrame(wire)
    }

    suspend fun forwardRpc(rawRequestJson: String): RpcResponse {
        val req =
            runCatching { SideveilWireJson.decodeFromString<RpcRequest>(rawRequestJson) }
                .getOrElse { err ->
                    return RpcResponse(id = "0", error = RpcError(-32700, "Invalid RPC payload: ${err.message}"))
                }

        if (!isConnected()) {
            return RpcResponse(id = req.id, error = RpcError(-32001, "Daemon WebSocket disconnected"))
        }

        val deferred = CompletableDeferred<RpcResponse>()
        pendingRpcs[req.id] = deferred

        return try {
            if (!sendTextFrame(rawRequestJson)) {
                RpcResponse(id = req.id, error = RpcError(-32001, "Failed to transmit RPC to daemon"))
            } else {
                withTimeout(8_000L) { deferred.await() }
            }
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            RpcResponse(id = req.id, error = RpcError(-32002, t.message ?: "RPC timed out"))
        } finally {
            pendingRpcs.remove(req.id)
        }
    }

    fun close() {
        connectionJob?.cancel()
        connectionJob = null
        closeCurrentSocket()
        failAllPending("DaemonClient closed")
        writeExecutor.shutdownNow()
    }

    private suspend fun connectLoop() {
        var attempt = 0
        while (scope.isActive && port > 0 && token.isNotEmpty()) {
            val currentPort = port
            val currentToken = token
            try {
                val socket = Socket()
                socket.tcpNoDelay = true
                socket.connect(InetSocketAddress("127.0.0.1", currentPort), 3_000)
                socket.soTimeout = 45_000

                val input = socket.getInputStream()
                val output = socket.getOutputStream()
                WebSocketCodec.performClientHandshake(
                    input = input,
                    output = output,
                    path = "/ws",
                    hostHeader = "127.0.0.1:$currentPort",
                    extraHeaders = mapOf("Authorization" to "Bearer $currentToken"),
                )

                synchronized(writeLock) {
                    activeSocket = socket
                    activeOutput = output
                }
                attempt = 0
                Log.i(TAG, "Connected full-duplex WebSocket to 127.0.0.1:$currentPort")
                onConnected()

                while (scope.isActive && !socket.isClosed) {
                    val frame = WebSocketCodec.readFrame(input) ?: break
                    when (frame.opcode) {
                        WsOpcode.CLOSE -> {
                            break
                        }

                        WsOpcode.PING -> {
                            sendPongFrame(frame.payload)
                        }

                        WsOpcode.PONG -> {
                            Unit
                        }

                        WsOpcode.BINARY -> {
                            Log.d(TAG, "Received binary frame (${frame.payload.size} bytes)")
                        }

                        WsOpcode.TEXT -> {
                            val text = frame.textOrNull() ?: continue
                            handleIncomingText(text)
                        }
                    }
                }
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                Log.w(TAG, "WebSocket loop error: ${t.message}")
            } finally {
                val wasConnected = activeSocket != null
                closeCurrentSocket()
                failAllPending("Daemon WebSocket connection lost")
                if (wasConnected) {
                    onDisconnected()
                }
            }

            val delayMs = BACKOFF_MS[attempt.coerceAtMost(BACKOFF_MS.lastIndex)]
            attempt++
            delay(delayMs)
        }
    }

    private fun handleIncomingText(text: String) {
        if (text.contains("\"jsonrpc\"") && text.contains("\"id\"")) {
            val resp = runCatching { SideveilWireJson.decodeFromString<RpcResponse>(text) }.getOrNull()
            if (resp != null) {
                pendingRpcs.remove(resp.id)?.complete(resp)
                return
            }
        }

        if (text.contains("\"event\"")) {
            val env = runCatching { SideveilWireJson.decodeFromString<HostEventEnvelope>(text) }.getOrNull()
            if (env != null) {
                onEvent(env)
            }
        }
    }

    private fun sendTextFrame(text: String): Boolean {
        if (activeOutput == null) return false
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
        return true
    }

    private fun sendPongFrame(payload: ByteArray) {
        if (activeOutput == null) return
        writeExecutor.execute {
            synchronized(writeLock) {
                val output = activeOutput ?: return@execute
                runCatching { WebSocketCodec.writePong(output, payload, mask = true) }
            }
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
        private const val TAG = "SideveilDaemonClient"
        private val BACKOFF_MS = longArrayOf(1_000L, 2_000L, 5_000L, 10_000L)
    }
}
