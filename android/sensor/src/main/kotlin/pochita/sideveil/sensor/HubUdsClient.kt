package pochita.sideveil.sensor

import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Process
import pochita.sideveil.protocol.HubToSensor
import pochita.sideveil.protocol.SensorToHub
import pochita.sideveil.protocol.SensorUdsHello
import pochita.sideveil.protocol.SideveilWireJson
import pochita.sideveil.protocol.UdsHandshakeAck
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Persistent NDJSON Unix Domain Socket client connecting the UID 1000 `:sensor` process
 * to `sideveild` via `/data/system/sideveil/run/hub.sock` (`SO_PEERCRED` authenticated).
 */
class HubUdsClient(
    private val socketPath: String,
    private val version: String,
    private val onConnected: () -> Unit,
    private val onMessage: (HubToSensor) -> Unit,
) {
    private val running = AtomicBoolean(false)
    private val writeLock = Any()
    private val pendingDragMove = AtomicReference<String?>(null)
    private val writeExecutor =
        ThreadPoolExecutor(
            1,
            1,
            0L,
            TimeUnit.MILLISECONDS,
            LinkedBlockingQueue(),
        )

    @Volatile
    private var activeSocket: LocalSocket? = null

    @Volatile
    private var activeWriter: BufferedWriter? = null

    private var workerThread: Thread? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        workerThread =
            Thread(::connectLoop, "SideveilSensorUds").apply {
                isDaemon = true
                start()
            }
    }

    fun send(message: SensorToHub) {
        if (!running.get() || activeWriter == null || writeExecutor.isShutdown) return
        val wire = SideveilWireJson.encodeToString(SensorToHub.serializer(), message)
        if (message is SensorToHub.GestureDragMove) {
            val wasEmpty = pendingDragMove.getAndSet(wire) == null
            if (wasEmpty) {
                writeExecutor.execute {
                    val latest = pendingDragMove.getAndSet(null) ?: return@execute
                    writeLine(latest)
                }
            }
            return
        }
        writeExecutor.execute { writeLine(wire) }
    }

    private fun writeLine(wire: String) {
        synchronized(writeLock) {
            val writer = activeWriter ?: return
            runCatching {
                writer.write(wire)
                writer.write('\n'.code)
                writer.flush()
            }.onFailure { err ->
                SensorLog.warn("Sensor UDS write failed: ${err.message}")
                closeActiveSocket()
            }
        }
    }

    fun stop() {
        running.set(false)
        closeActiveSocket()
        workerThread?.interrupt()
        workerThread = null
        writeExecutor.shutdownNow()
    }

    private fun connectLoop() {
        var attempt = 0
        while (running.get()) {
            val connected = runSingleSession()
            attempt = if (connected) 0 else (attempt + 1).coerceAtMost(BACKOFF_MS.lastIndex)
            if (!running.get()) return
            sleepQuietly(BACKOFF_MS[attempt])
        }
    }

    private fun runSingleSession(): Boolean {
        val socket = LocalSocket(LocalSocket.SOCKET_STREAM)
        return try {
            socket.connect(LocalSocketAddress(socketPath, LocalSocketAddress.Namespace.FILESYSTEM))
            val reader = BufferedReader(InputStreamReader(socket.inputStream, StandardCharsets.UTF_8))
            val writer = BufferedWriter(OutputStreamWriter(socket.outputStream, StandardCharsets.UTF_8))

            performHandshake(reader, writer)
            synchronized(writeLock) {
                activeSocket = socket
                activeWriter = writer
            }
            SensorLog.info("Connected to sideveild UDS at $socketPath")
            onConnected()
            readIncomingLoop(reader)
            true
        } catch (t: Throwable) {
            if (running.get()) {
                SensorLog.warn("Sensor UDS session ended: ${t.message}")
            }
            false
        } finally {
            detachSocket(socket)
        }
    }

    private fun performHandshake(
        reader: BufferedReader,
        writer: BufferedWriter,
    ) {
        val hello =
            SideveilWireJson.encodeToString(
                SensorUdsHello(pid = Process.myPid(), version = version),
            )
        writer.write(hello)
        writer.write('\n'.code)
        writer.flush()

        val ackLine = reader.readLine() ?: error("hub.sock closed before handshake ack")
        val ack = SideveilWireJson.decodeFromString<UdsHandshakeAck>(ackLine)
        check(ack.ok) { "hub.sock rejected sensor handshake: ${ack.error}" }
    }

    private fun readIncomingLoop(reader: BufferedReader) {
        while (running.get()) {
            val line = reader.readLine() ?: return
            val trimmed = line.trim()
            if (trimmed.isNotEmpty()) {
                runCatching {
                    SideveilWireJson.decodeFromString(HubToSensor.serializer(), trimmed)
                }.onSuccess(onMessage)
                    .onFailure { err -> SensorLog.warn("Malformed HubToSensor frame: ${err.message}") }
            }
        }
    }

    private fun detachSocket(socket: LocalSocket) {
        synchronized(writeLock) {
            if (activeSocket === socket) {
                activeSocket = null
                activeWriter = null
            }
        }
        runCatching { socket.close() }
    }

    private fun closeActiveSocket() {
        synchronized(writeLock) {
            val sock = activeSocket
            activeSocket = null
            activeWriter = null
            runCatching { sock?.close() }
        }
    }

    private fun sleepQuietly(delayMs: Long) {
        try {
            Thread.sleep(delayMs)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    companion object {
        private val BACKOFF_MS = longArrayOf(500L, 1_000L, 2_000L, 5_000L)
    }
}
