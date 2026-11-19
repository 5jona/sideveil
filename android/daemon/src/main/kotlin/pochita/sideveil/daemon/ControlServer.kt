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
import pochita.sideveil.shared.PanelState
import pochita.sideveil.shared.PanelStateChangedPayload
import pochita.sideveil.shared.PingResult
import pochita.sideveil.shared.RpcError
import pochita.sideveil.shared.RpcRequest
import pochita.sideveil.shared.RpcResponse
import pochita.sideveil.shared.SideveilEvents
import pochita.sideveil.shared.SideveilMethods
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
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

interface DaemonRpcDelegate {
    fun buildStatus(): DaemonStatus

    fun currentConfigJson(): JsonElement

    fun reloadConfig(): JsonElement

    fun patchAppearance(params: AppearancePatchParams): JsonElement

    fun sendPanelCommand(command: String)

    fun restartHost()

    fun onHostHello(payload: HostHelloPayload)

    fun onPanelStateChanged(state: PanelState)
}

/**
 * Single loopback HTTP/1.1 + RFC 6455 WebSocket server bound strictly to 127.0.0.1:0
 * with a per-session cryptographic Bearer token written atomically to <runDir>/daemon.env.
 */
class ControlServer(
    private val runDir: File,
    private val uptimeProvider: () -> Long,
    private val delegate: DaemonRpcDelegate,
) {
    private val executor = Executors.newCachedThreadPool()
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
        val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        serverSocket = server
        boundPort = server.localPort
        running = true

        writeEnvFile(boundPort, sessionToken)
        DaemonLog.info("ControlServer listening on 127.0.0.1:$boundPort")

        executor.execute { acceptLoop(server) }
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
        executor.shutdownNow()
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
            executor.execute { handleConnection(client) }
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

            val method = parts[0].uppercase(Locale.ROOT)
            val rawTarget = parts[1]
            val qIdx = rawTarget.indexOf('?')
            val path = if (qIdx >= 0) rawTarget.substring(0, qIdx) else rawTarget
            val queryParams = parseQueryParams(if (qIdx >= 0) rawTarget.substring(qIdx + 1) else "")

            val suppliedToken = extractToken(block.headers, queryParams)
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
                    val response = executeRpcText(bodyText)
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
                pochita.sideveil.shared.ConfigUpdatedPayload(
                    config = SideveilWireJson.decodeFromJsonElement(delegate.currentConfigJson()),
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
                val frame = WebSocketCodec.readFrame(input) ?: break
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
        val obj =
            runCatching { SideveilWireJson.parseToJsonElement(text) }.getOrNull() ?: return
        val jsonText = obj.toString()

        if (jsonText.contains("\"method\"") && jsonText.contains("\"id\"")) {
            val response = executeRpcText(jsonText)
            session.sendText(SideveilWireJson.encodeToString(response))
            return
        }

        if (jsonText.contains("\"event\"")) {
            val envelope =
                runCatching { SideveilWireJson.decodeFromString<HostEventEnvelope>(jsonText) }.getOrNull()
                    ?: return
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

    private fun executeRpcText(bodyText: String): RpcResponse {
        val req =
            try {
                SideveilWireJson.decodeFromString<RpcRequest>(bodyText)
            } catch (t: Throwable) {
                return RpcResponse(id = "0", error = RpcError(-32700, "Parse error: ${t.message}"))
            }

        return try {
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
                        delegate.currentConfigJson()
                    }

                    SideveilMethods.CONFIG_RELOAD -> {
                        delegate.reloadConfig()
                    }

                    SideveilMethods.CONFIG_PATCH_APPEARANCE -> {
                        val patch =
                            if (req.params != null) {
                                SideveilWireJson.decodeFromJsonElement<AppearancePatchParams>(req.params!!)
                            } else {
                                AppearancePatchParams()
                            }
                        delegate.patchAppearance(patch)
                    }

                    SideveilMethods.PANEL_OPEN -> {
                        delegate.sendPanelCommand("open")
                        buildJsonObject { put("ok", JsonPrimitive(true)) }
                    }

                    SideveilMethods.PANEL_CLOSE -> {
                        delegate.sendPanelCommand("close")
                        buildJsonObject { put("ok", JsonPrimitive(true)) }
                    }

                    SideveilMethods.PANEL_TOGGLE -> {
                        delegate.sendPanelCommand("toggle")
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
                404 -> "Not Found"
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

    private fun extractToken(
        headers: Map<String, String>,
        queryParams: Map<String, String>,
    ): String? {
        val auth = headers["authorization"]
        if (auth != null && auth.startsWith("Bearer ", ignoreCase = true)) {
            return auth.substring(7).trim()
        }
        return queryParams["token"]
    }

    private fun parseQueryParams(rawQuery: String): Map<String, String> {
        if (rawQuery.isBlank()) return emptyMap()
        val result = LinkedHashMap<String, String>()
        for (pair in rawQuery.split('&')) {
            val idx = pair.indexOf('=')
            if (idx > 0) {
                val key = URLDecoder.decode(pair.substring(0, idx), StandardCharsets.UTF_8)
                val value = URLDecoder.decode(pair.substring(idx + 1), StandardCharsets.UTF_8)
                result[key] = value
            }
        }
        return result
    }

    private fun writeEnvFile(
        port: Int,
        token: String,
    ) {
        if (!runDir.exists()) {
            runDir.mkdirs()
        }
        val envFile = File(runDir, "daemon.env")
        val tmpFile = File(runDir, ".daemon.env.tmp.${android.os.Process.myPid()}")
        val content =
            buildString {
                append("SIDEVEIL_PORT=").append(port).append('\n')
                append("SIDEVEIL_TOKEN=").append(token).append('\n')
                append("SIDEVEIL_PID=").append(android.os.Process.myPid()).append('\n')
            }
        tmpFile.writeText(content, StandardCharsets.UTF_8)
        runCatching {
            Runtime.getRuntime().exec(arrayOf("chmod", "0600", tmpFile.absolutePath)).waitFor()
        }
        Files.move(
            tmpFile.toPath(),
            envFile.toPath(),
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE,
        )
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
        private val writeLock = Any()

        @Volatile
        private var closed = false

        fun isOpen(): Boolean = !closed && !socket.isClosed

        fun sendText(text: String) {
            if (!isOpen()) return
            synchronized(writeLock) {
                if (!isOpen()) return
                runCatching { WebSocketCodec.writeText(output, text, mask = false) }
                    .onFailure { close("Write text failed") }
            }
        }

        fun sendPong(payload: ByteArray) {
            if (!isOpen()) return
            synchronized(writeLock) {
                if (!isOpen()) return
                runCatching { WebSocketCodec.writePong(output, payload, mask = false) }
                    .onFailure { close("Write pong failed") }
            }
        }

        fun sendPing() {
            if (!isOpen()) return
            synchronized(writeLock) {
                if (!isOpen()) return
                runCatching { WebSocketCodec.writePing(output, ByteArray(0), mask = false) }
                    .onFailure { close("Write ping failed") }
            }
        }

        fun close(reason: String) {
            if (closed) return
            closed = true
            synchronized(writeLock) {
                runCatching { WebSocketCodec.writeClose(output, 1000, reason.take(60), mask = false) }
            }
            runCatching { socket.close() }
        }
    }
}
