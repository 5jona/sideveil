package pochita.sideveil.daemon

import android.system.Os
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import pochita.sideveil.shared.ConfigUpdatedPayload
import pochita.sideveil.shared.HostEventEnvelope
import pochita.sideveil.shared.PanelState
import pochita.sideveil.shared.RpcError
import pochita.sideveil.shared.RpcRequest
import pochita.sideveil.shared.RpcResponse
import pochita.sideveil.shared.SideveilEvents
import pochita.sideveil.shared.SideveilWireJson
import pochita.sideveil.shared.WebSocketCodec
import pochita.sideveil.shared.WsOpcode
import java.io.EOFException
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock

/**
 * Single loopback HTTP/1.1 + RFC 6455 WebSocket server bound strictly to 127.0.0.1:0
 * with a per-session cryptographic Bearer token written atomically to <runDir>/daemon.env.
 */
class ControlServer(
    private val runDir: File,
    uptimeProvider: () -> Long,
    private val delegate: DaemonRpcDelegate,
) {
    private val rpcDispatcher = RpcDispatcher(uptimeProvider = uptimeProvider, delegate = delegate)
    private val acceptExecutor = Executors.newSingleThreadExecutor()
    private val workerPool =
        ThreadPoolExecutor(
            2,
            16,
            30L,
            TimeUnit.SECONDS,
            ArrayBlockingQueue(32),
        )
    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    private val sessionLock = Any()

    val sessionToken: String = generateToken()
    var boundPort: Int = 0
        private set

    @Volatile
    private var running = false

    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var activeSession: HostSession? = null

    fun start() {
        val server = ServerSocket(0, 32, InetAddress.getByName("127.0.0.1"))
        serverSocket = server
        boundPort = server.localPort
        running = true

        writeEnvFile(boundPort, sessionToken)
        DaemonLog.info("ControlServer listening on 127.0.0.1:$boundPort")

        acceptExecutor.execute { acceptLoop(server) }
        scheduler.scheduleAtFixedRate({ sendKeepalivePing() }, 15L, 15L, TimeUnit.SECONDS)
    }

    fun isHostConnected(): Boolean = activeSession?.isOpen() == true

    fun emitEvent(
        event: String,
        payload: JsonElement? = null,
    ) {
        val session = activeSession ?: return
        val wire = SideveilWireJson.encodeToString(HostEventEnvelope(event = event, payload = payload))
        session.sendText(wire)
    }

    fun closeActiveHostSession(reason: String = "Restarting host") {
        val prev =
            synchronized(sessionLock) {
                activeSession.also { activeSession = null }
            }
        prev?.close(reason)
    }

    fun stop() {
        running = false
        closeActiveHostSession("Daemon shutdown")
        runCatching { serverSocket?.close() }
        serverSocket = null
        scheduler.shutdownNow()
        acceptExecutor.shutdownNow()
        workerPool.shutdownNow()
        removeEnvFile()
    }

    private fun acceptLoop(server: ServerSocket) {
        while (running && !server.isClosed) {
            val client =
                try {
                    server.accept()
                } catch (_: Throwable) {
                    break
                }
            try {
                workerPool.execute { handleConnection(client) }
            } catch (_: RejectedExecutionException) {
                runCatching {
                    client.soTimeout = 1_000
                    writeHttpJson(client.getOutputStream(), 503, """{"error":"Server busy"}""")
                }
                runCatching { client.close() }
            }
        }
    }

    private fun handleConnection(socket: Socket) {
        var keepOpenForWebSocket = false
        try {
            socket.tcpNoDelay = true
            socket.soTimeout = 10_000
            val input = socket.getInputStream()
            val output = socket.getOutputStream()

            val block = WebSocketCodec.readHttpHeaderBlock(input) ?: return
            val parts = block.requestLine.split(' ')
            if (parts.size < 2) {
                writeHttpJson(output, 400, """{"error":"Bad request"}""")
                return
            }

            if (!block.headers["origin"].isNullOrBlank()) {
                writeHttpJson(output, 403, """{"error":"Cross-origin access forbidden"}""")
                return
            }

            val method = parts[0].uppercase(Locale.ROOT)
            val rawTarget = parts[1]
            val qIdx = rawTarget.indexOf('?')
            val path = if (qIdx >= 0) rawTarget.substring(0, qIdx) else rawTarget

            val suppliedToken = extractToken(block.headers)
            if (suppliedToken == null || !constantTimeEquals(suppliedToken, sessionToken)) {
                writeHttpJson(output, 401, """{"error":"Unauthorized"}""")
                return
            }

            if (method == "GET" && path == "/ws" &&
                block.headers["upgrade"].equals("websocket", ignoreCase = true)
            ) {
                socket.soTimeout = 45_000
                WebSocketCodec.writeServerUpgradeResponse(block.headers, output)
                keepOpenForWebSocket = true
                runWebSocketSession(socket, input, output)
                return
            }

            when {
                method == "GET" && path == "/health" -> {
                    writeHttpJson(output, 200, """{"ok":true}""")
                }

                method == "GET" && path == "/status" -> {
                    val statusJson = SideveilWireJson.encodeToString(delegate.buildStatus())
                    writeHttpJson(output, 200, statusJson)
                }

                method == "POST" && path == "/rpc" -> {
                    val contentLen = block.headers["content-length"]?.toIntOrNull() ?: 0
                    if (contentLen <= 0 || contentLen > 256 * 1024) {
                        writeHttpJson(output, 400, """{"error":"Invalid Content-Length"}""")
                        return
                    }
                    val bodyBytes = readExactBytes(input, contentLen)
                    val bodyText = String(bodyBytes, StandardCharsets.UTF_8)
                    val response = rpcDispatcher.executeRpcText(bodyText)
                    writeHttpJson(output, 200, SideveilWireJson.encodeToString(response))
                }

                else -> {
                    writeHttpJson(output, 404, """{"error":"Not found"}""")
                }
            }
        } catch (_: EOFException) {
            // Client disconnected cleanly.
        } catch (t: Throwable) {
            if (running) {
                DaemonLog.warn("ControlServer connection error", t)
            }
        } finally {
            if (!keepOpenForWebSocket) {
                runCatching { socket.close() }
            }
        }
    }

    private fun runWebSocketSession(
        socket: Socket,
        input: InputStream,
        output: OutputStream,
    ) {
        val session = HostSession(socket, output)
        val previous =
            synchronized(sessionLock) {
                val old = activeSession
                activeSession = session
                old
            }
        previous?.close("Superseded by new host connection")
        DaemonLog.info("Host WebSocket connected")

        // Immediately sync current config/scope snapshot to the newly connected Host.
        val status = delegate.buildStatus()
        val syncPayload =
            SideveilWireJson.encodeToJsonElement(
                ConfigUpdatedPayload(
                    config = delegate.currentConfig(),
                    scopeActive = status.scopeActive,
                    foregroundPackage = status.foregroundPackage,
                ),
            )
        session.sendText(
            SideveilWireJson.encodeToString(
                HostEventEnvelope(event = SideveilEvents.CONFIG_UPDATED, payload = syncPayload),
            ),
        )

        try {
            while (running && session.isOpen()) {
                val frame = WebSocketCodec.readFrame(input, requireMasked = true) ?: break
                when (frame.opcode) {
                    WsOpcode.CLOSE -> {
                        session.close("Peer closed")
                        break
                    }

                    WsOpcode.PING -> {
                        session.sendPong(frame.payload)
                    }

                    WsOpcode.PONG -> {
                        Unit
                    }

                    WsOpcode.BINARY -> {
                        // Bidirectional binary stream channel reserved for future low-overhead payloads;
                        // echo back only when explicitly tagged for diagnostics.
                        DaemonLog.info("Received binary WebSocket frame (${frame.payload.size} bytes)")
                    }

                    WsOpcode.TEXT -> {
                        val text = frame.textOrNull() ?: continue
                        handleWebSocketText(session, text)
                    }
                }
            }
        } catch (_: EOFException) {
            // Expected on socket close.
        } catch (t: Throwable) {
            if (running && session.isOpen()) {
                DaemonLog.warn("WebSocket session read terminated", t)
            }
        } finally {
            WebSocketCodec.clearStreamState(input)
            var dropped = false
            synchronized(sessionLock) {
                if (activeSession === session) {
                    activeSession = null
                    dropped = true
                }
            }
            session.close("Session ended")
            if (dropped) {
                delegate.onPanelStateChanged(PanelState.CLOSED)
                DaemonLog.warn("Host WebSocket disconnected")
            }
        }
    }

    private fun handleWebSocketText(
        session: HostSession,
        text: String,
    ) {
        val obj: JsonObject =
            runCatching { SideveilWireJson.parseToJsonElement(text).jsonObject }.getOrNull() ?: return

        if (obj.containsKey("method") && obj.containsKey("id")) {
            val response =
                runCatching {
                    val req = SideveilWireJson.decodeFromJsonElement<RpcRequest>(obj)
                    rpcDispatcher.executeRpcRequest(req)
                }.getOrElse { t ->
                    RpcResponse(id = "0", error = RpcError(-32700, "Parse error: ${t.message}"))
                }
            session.sendText(SideveilWireJson.encodeToString(response))
            return
        }

        if (obj.containsKey("event")) {
            val envelope =
                runCatching { SideveilWireJson.decodeFromJsonElement<HostEventEnvelope>(obj) }.getOrNull()
                    ?: return
            rpcDispatcher.dispatchHostEvent(envelope)
        }
    }

    private fun sendKeepalivePing() {
        val session = activeSession ?: return
        session.sendPing()
    }

    private fun writeHttpJson(
        output: OutputStream,
        statusCode: Int,
        body: String,
    ) {
        val reason =
            when (statusCode) {
                200 -> "OK"
                400 -> "Bad Request"
                401 -> "Unauthorized"
                403 -> "Forbidden"
                404 -> "Not Found"
                503 -> "Service Unavailable"
                else -> "Error"
            }
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        val headers =
            buildString {
                append("HTTP/1.1 ")
                    .append(statusCode)
                    .append(' ')
                    .append(reason)
                    .append("\r\n")
                append("Content-Type: application/json; charset=utf-8\r\n")
                append("Content-Length: ").append(bytes.size).append("\r\n")
                append("Connection: close\r\n")
                append("\r\n")
            }
        output.write(headers.toByteArray(StandardCharsets.US_ASCII))
        output.write(bytes)
        output.flush()
    }

    private fun readExactBytes(
        input: InputStream,
        len: Int,
    ): ByteArray {
        val buf = ByteArray(len)
        var offset = 0
        while (offset < len) {
            val n = input.read(buf, offset, len - offset)
            if (n < 0) throw EOFException("Unexpected EOF reading HTTP request body")
            offset += n
        }
        return buf
    }

    private fun extractToken(headers: Map<String, String>): String? {
        val auth = headers["authorization"]
        if (auth != null && auth.startsWith("Bearer ", ignoreCase = true)) {
            return auth.substring(7).trim()
        }
        return null
    }

    private fun writeEnvFile(
        port: Int,
        token: String,
    ) {
        if (!runDir.exists()) {
            runDir.mkdirs()
        }
        val envFile = File(runDir, "daemon.env")
        val pid = runCatching { android.os.Process.myPid() }.getOrDefault(0)
        val tmpFile = File(runDir, ".daemon.env.tmp.$pid")
        Files.deleteIfExists(tmpFile.toPath())
        tmpFile.createNewFile()
        applyRestrictedMode0600(tmpFile)
        val content =
            buildString {
                append("SIDEVEIL_PORT=").append(port).append('\n')
                append("SIDEVEIL_TOKEN=").append(token).append('\n')
                append("SIDEVEIL_PID=").append(pid).append('\n')
            }
        tmpFile.writeText(content, StandardCharsets.UTF_8)
        applyRestrictedMode0600(tmpFile)
        Files.move(
            tmpFile.toPath(),
            envFile.toPath(),
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE,
        )
    }

    private fun applyRestrictedMode0600(file: File) {
        runCatching {
            Os.chmod(file.absolutePath, 384) // 0600 octal
        }.recoverCatching {
            Files.setPosixFilePermissions(file.toPath(), PosixFilePermissions.fromString("rw-------"))
        }
    }

    private fun removeEnvFile() {
        runCatching { File(runDir, "daemon.env").delete() }
    }

    private fun generateToken(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString(separator = "") { "%02x".format(it) }
    }

    private fun constantTimeEquals(
        a: String,
        b: String,
    ): Boolean =
        MessageDigest.isEqual(
            a.toByteArray(StandardCharsets.UTF_8),
            b.toByteArray(StandardCharsets.UTF_8),
        )

    private class HostSession(
        private val socket: Socket,
        private val output: OutputStream,
    ) {
        private val writeLock = ReentrantLock()
        private val writeExecutor =
            ThreadPoolExecutor(
                1,
                1,
                0L,
                TimeUnit.MILLISECONDS,
                ArrayBlockingQueue(256),
                ThreadFactory { r ->
                    Thread(r, "SideveilWsWriter").apply { isDaemon = true }
                },
                ThreadPoolExecutor.DiscardOldestPolicy(),
            )

        private val closed = AtomicBoolean(false)

        fun isOpen(): Boolean = !closed.get() && !socket.isClosed

        fun sendText(text: String) {
            enqueueWrite {
                runCatching { WebSocketCodec.writeText(output, text, mask = false) }
                    .onFailure { close("Write text failed") }
            }
        }

        fun sendPong(payload: ByteArray) {
            enqueueWrite {
                runCatching { WebSocketCodec.writePong(output, payload, mask = false) }
                    .onFailure { close("Write pong failed") }
            }
        }

        fun sendPing() {
            enqueueWrite {
                runCatching { WebSocketCodec.writePing(output, ByteArray(0), mask = false) }
                    .onFailure { close("Write ping failed") }
            }
        }

        private fun enqueueWrite(action: () -> Unit) {
            if (!isOpen()) return
            runCatching {
                writeExecutor.execute {
                    if (!isOpen()) return@execute
                    writeLock.lock()
                    try {
                        if (!isOpen()) return@execute
                        action()
                    } finally {
                        writeLock.unlock()
                    }
                }
            }
        }

        fun close(reason: String) {
            if (!closed.compareAndSet(false, true)) return
            writeExecutor.shutdownNow()
            val locked = runCatching { writeLock.tryLock(50, TimeUnit.MILLISECONDS) }.getOrDefault(false)
            if (locked) {
                try {
                    runCatching { WebSocketCodec.writeClose(output, 1000, reason.take(60), mask = false) }
                } finally {
                    writeLock.unlock()
                }
            }
            runCatching { socket.close() }
        }
    }
}
