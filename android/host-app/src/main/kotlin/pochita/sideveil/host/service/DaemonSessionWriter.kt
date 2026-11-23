package pochita.sideveil.host.service

import pochita.sideveil.protocol.WebSocketCodec
import java.io.OutputStream
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
