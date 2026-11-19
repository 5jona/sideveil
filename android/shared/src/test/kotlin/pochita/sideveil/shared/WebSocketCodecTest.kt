package pochita.sideveil.shared

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class WebSocketCodecTest {
    @Test
    fun `rfc6455 accept key matches standard vector`() {
        val accept = WebSocketCodec.computeAcceptKey("dGhlIHNhbXBsZSBub25jZQ==")
        assertEquals("s3pPLMBiTxaQ9kYGzzhZRbK+xOo=", accept)
    }

    @Test
    fun `masked and unmasked text and medium binary frames round-trip`() {
        val out = ByteArrayOutputStream()
        WebSocketCodec.writeText(out, "hello sideveil", mask = true)
        val mediumBinary = ByteArray(1024) { (it and 0xFF).toByte() }
        WebSocketCodec.writeBinary(out, mediumBinary, mask = false)
        val largeBinary = ByteArray(70_000) { ((it * 7) and 0xFF).toByte() }
        WebSocketCodec.writeBinary(out, largeBinary, mask = true)

        val input = ByteArrayInputStream(out.toByteArray())
        val f1 = WebSocketCodec.readFrame(input)
        val f2 = WebSocketCodec.readFrame(input)
        val f3 = WebSocketCodec.readFrame(input)
        val eof = WebSocketCodec.readFrame(input)

        assertNotNull(f1)
        assertEquals(WsOpcode.TEXT, f1!!.opcode)
        assertEquals("hello sideveil", f1.textOrNull())

        assertNotNull(f2)
        assertEquals(WsOpcode.BINARY, f2!!.opcode)
        assertArrayEquals(mediumBinary, f2.payload)

        assertNotNull(f3)
        assertEquals(WsOpcode.BINARY, f3!!.opcode)
        assertArrayEquals(largeBinary, f3.payload)

        assertNull(eof)
    }

    @Test
    fun `loopback full-duplex handshake and rpc exchange works`() {
        val server = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
        val latch = CountDownLatch(1)
        var serverReceived: String? = null
        var clientReceived: String? = null

        val serverThread =
            thread(start = true) {
                server.accept().use { sock ->
                    val input = sock.getInputStream()
                    val output = sock.getOutputStream()
                    val headers = WebSocketCodec.readHttpHeaderBlock(input)!!
                    assertEquals("Bearer test-token", headers.headers["authorization"])
                    WebSocketCodec.writeServerUpgradeResponse(headers.headers, output)

                    val frame = WebSocketCodec.readFrame(input)!!
                    serverReceived = frame.textOrNull()
                    val resp =
                        RpcResponse(
                            id = "1",
                            result = buildJsonObject { put("pong", JsonPrimitive(true)) },
                        )
                    WebSocketCodec.writeText(output, SideveilWireJson.encodeToString(resp), mask = false)
                }
            }

        Socket("127.0.0.1", server.localPort).use { client ->
            val input = client.getInputStream()
            val output = client.getOutputStream()
            WebSocketCodec.performClientHandshake(
                input = input,
                output = output,
                path = "/ws",
                hostHeader = "127.0.0.1:${server.localPort}",
                extraHeaders = mapOf("Authorization" to "Bearer test-token"),
            )
            val req = RpcRequest(id = "1", method = SideveilMethods.DAEMON_PING)
            WebSocketCodec.writeText(output, SideveilWireJson.encodeToString(req), mask = true)
            val respFrame = WebSocketCodec.readFrame(input)!!
            clientReceived = respFrame.textOrNull()
            latch.countDown()
        }

        latch.await(5, TimeUnit.SECONDS)
        serverThread.join(5000)
        server.close()

        assertNotNull(serverReceived)
        assertNotNull(clientReceived)
        val decodedResp = SideveilWireJson.decodeFromString<RpcResponse>(clientReceived!!)
        assertEquals("1", decodedResp.id)
        assertEquals(null, decodedResp.error)
    }
}
