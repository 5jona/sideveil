package pochita.sideveil.host.plugin

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pochita.sideveil.host.service.DaemonClient
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class WebViewSafetyTest {
    @Test
    fun `PendingWebMessageQueue never evicts RPC replies under event bursts`() {
        val queue = PendingWebMessageQueue(maxPendingEvents = 4)
        queue.enqueueResponse("""{"id":"rpc-1"}""")
        for (i in 1..20) {
            queue.enqueueEvent("""{"event":"e-$i"}""")
        }
        queue.enqueueResponse("""{"id":"rpc-2"}""")

        assertEquals(2, queue.responseCount())
        assertEquals(4, queue.eventCount())

        val drained = mutableListOf<String>()
        queue.drainAll { drained += it }
        assertEquals(
            listOf(
                """{"id":"rpc-1"}""",
                """{"id":"rpc-2"}""",
                """{"event":"e-17"}""",
                """{"event":"e-18"}""",
                """{"event":"e-19"}""",
                """{"event":"e-20"}""",
            ),
            drained,
        )
        assertEquals(0, queue.responseCount())
        assertEquals(0, queue.eventCount())
    }

    @Test
    fun `WebViewCrashBackoff applies bounded exponential backoff and trips circuit breaker`() {
        var nowMs = 1_000L
        val backoff =
            WebViewCrashBackoff(
                windowMs = 60_000L,
                baseDelayMs = 500L,
                maxDelayMs = 4_000L,
                maxRapidCrashes = 5,
                cooldownMs = 30_000L,
                clockMs = { nowMs },
            )

        assertEquals(CrashRecoveryDecision.RetryAfter(delayMs = 500L, attempt = 1), backoff.recordCrashAndDecide("p1"))
        nowMs += 1_000L
        assertEquals(CrashRecoveryDecision.RetryAfter(delayMs = 1_000L, attempt = 2), backoff.recordCrashAndDecide("p1"))
        nowMs += 1_000L
        assertEquals(CrashRecoveryDecision.RetryAfter(delayMs = 2_000L, attempt = 3), backoff.recordCrashAndDecide("p1"))
        nowMs += 1_000L
        assertEquals(CrashRecoveryDecision.RetryAfter(delayMs = 4_000L, attempt = 4), backoff.recordCrashAndDecide("p1"))
        nowMs += 1_000L
        assertEquals(
            CrashRecoveryDecision.CircuitOpen(attempt = 5, cooldownRemainingMs = 30_000L),
            backoff.recordCrashAndDecide("p1"),
        )

        // During cooldown the circuit stays open.
        nowMs += 10_000L
        assertEquals(
            CrashRecoveryDecision.CircuitOpen(attempt = 5, cooldownRemainingMs = 20_000L),
            backoff.recordCrashAndDecide("p1"),
        )

        // Manual reset clears the open circuit immediately.
        backoff.reset("p1")
        assertEquals(CrashRecoveryDecision.RetryAfter(delayMs = 500L, attempt = 1), backoff.recordCrashAndDecide("p1"))

        // Quiet period beyond window resets the streak.
        nowMs += 65_000L
        assertEquals(CrashRecoveryDecision.RetryAfter(delayMs = 500L, attempt = 1), backoff.recordCrashAndDecide("p1"))
    }

    @Test
    fun `DaemonClient close aborts in-flight handshake promptly and blocks subsequent updateEndpoint`() {
        val accepted = CountDownLatch(1)
        val clientClosed = CountDownLatch(1)
        val acceptCount = AtomicInteger(0)
        val server = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
        val acceptThread =
            Thread {
                while (!server.isClosed) {
                    val sock = runCatching { server.accept() }.getOrNull() ?: break
                    acceptCount.incrementAndGet()
                    accepted.countDown()
                    try {
                        // Stall the WebSocket HTTP upgrade until peer closes the socket.
                        while (sock.getInputStream().read() != -1) {
                            // wait for EOF
                        }
                        clientClosed.countDown()
                    } catch (_: Throwable) {
                        clientClosed.countDown()
                    } finally {
                        runCatching { sock.close() }
                    }
                }
            }
        acceptThread.isDaemon = true
        acceptThread.start()

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val client =
                DaemonClient(
                    scope = scope,
                    onConnected = {},
                    onDisconnected = {},
                    onEvent = {},
                )
            client.updateEndpoint(server.localPort, "test-token")
            assertTrue("expected client to initiate TCP connect", accepted.await(3, TimeUnit.SECONDS))

            client.close()
            assertTrue("expected close() to abort stalled handshake socket", clientClosed.await(2, TimeUnit.SECONDS))
            assertFalse(client.isConnected())
            assertNull(client.currentEndpoint())

            // updateEndpoint after close must be ignored.
            client.updateEndpoint(server.localPort, "new-token")
            Thread.sleep(150)
            assertEquals(1, acceptCount.get())
            assertNull(client.currentEndpoint())
        } finally {
            scope.cancel()
            runCatching { server.close() }
        }
    }
}
