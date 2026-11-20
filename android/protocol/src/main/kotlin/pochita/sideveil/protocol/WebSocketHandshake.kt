package pochita.sideveil.protocol

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.Locale

/**
 * RFC 6455 HTTP/1.1 upgrade handshake helpers shared by loopback WebSocket peers.
 */
internal object WebSocketHandshake {
    private const val WS_MAGIC_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
    private const val MAX_HEADER_BYTES = 32 * 1024

    fun computeAcceptKey(clientKey: String): String {
        val sha1 = MessageDigest.getInstance("SHA-1")
        val digest = sha1.digest((clientKey.trim() + WS_MAGIC_GUID).toByteArray(StandardCharsets.US_ASCII))
        return Base64.getEncoder().encodeToString(digest)
    }

    fun readHttpHeaderBlock(input: InputStream): HttpHeaderBlock? {
        val raw = readRawHeaderBytes(input) ?: return null
        val lines = String(raw, StandardCharsets.US_ASCII).split("\r\n").filter { it.isNotEmpty() }
        if (lines.isEmpty()) return null
        return HttpHeaderBlock(requestLine = lines[0], headers = parseHeaderMap(lines))
    }

    fun writeServerUpgradeResponse(
        headers: Map<String, String>,
        output: OutputStream,
    ) {
        val clientKey =
            headers["sec-websocket-key"]
                ?: throw IOException("Missing Sec-WebSocket-Key header")
        val accept = computeAcceptKey(clientKey)
        val response =
            buildString {
                append("HTTP/1.1 101 Switching Protocols\r\n")
                append("Upgrade: websocket\r\n")
                append("Connection: Upgrade\r\n")
                append("Sec-WebSocket-Accept: ").append(accept).append("\r\n")
                append("\r\n")
            }
        output.write(response.toByteArray(StandardCharsets.US_ASCII))
        output.flush()
    }

    fun performClientHandshake(
        rng: SecureRandom,
        input: InputStream,
        output: OutputStream,
        path: String,
        hostHeader: String,
        extraHeaders: Map<String, String>,
    ) {
        val nonce = ByteArray(16)
        rng.nextBytes(nonce)
        val clientKey = Base64.getEncoder().encodeToString(nonce)
        val req = buildClientRequest(path, hostHeader, clientKey, extraHeaders)
        output.write(req.toByteArray(StandardCharsets.US_ASCII))
        output.flush()

        val block =
            readHttpHeaderBlock(input)
                ?: throw IOException("Server closed connection during WebSocket handshake")
        verifyServerAccept(block, clientKey)
    }

    private fun readRawHeaderBytes(input: InputStream): ByteArray? {
        val buf = ByteArrayOutputStream(512)
        var delimiterState = 0
        while (buf.size() < MAX_HEADER_BYTES && delimiterState != 4) {
            val b = input.read()
            if (b < 0) {
                if (buf.size() == 0) return null
                throw EOFException("Unexpected EOF while reading HTTP headers")
            }
            buf.write(b)
            delimiterState = advanceHeaderDelimiterState(delimiterState, b)
        }
        if (delimiterState != 4) {
            throw IOException("HTTP header block exceeded $MAX_HEADER_BYTES bytes")
        }
        return buf.toByteArray()
    }

    private fun advanceHeaderDelimiterState(
        state: Int,
        byteVal: Int,
    ): Int =
        when (byteVal) {
            '\r'.code -> if (state == 2) 3 else 1
            '\n'.code -> if (state == 1 || state == 3) state + 1 else 0
            else -> 0
        }

    private fun parseHeaderMap(lines: List<String>): Map<String, String> {
        val headers = LinkedHashMap<String, String>(lines.size)
        for (i in 1 until lines.size) {
            val idx = lines[i].indexOf(':')
            if (idx > 0) {
                val name = lines[i].substring(0, idx).trim().lowercase(Locale.ROOT)
                headers[name] = lines[i].substring(idx + 1).trim()
            }
        }
        return headers
    }

    private fun buildClientRequest(
        path: String,
        hostHeader: String,
        clientKey: String,
        extraHeaders: Map<String, String>,
    ): String =
        buildString {
            append("GET ").append(path).append(" HTTP/1.1\r\n")
            append("Host: ").append(hostHeader).append("\r\n")
            append("Upgrade: websocket\r\n")
            append("Connection: Upgrade\r\n")
            append("Sec-WebSocket-Key: ").append(clientKey).append("\r\n")
            append("Sec-WebSocket-Version: 13\r\n")
            for ((k, v) in extraHeaders) {
                append(k).append(": ").append(v).append("\r\n")
            }
            append("\r\n")
        }

    private fun verifyServerAccept(
        block: HttpHeaderBlock,
        clientKey: String,
    ) {
        if (!block.requestLine.contains(" 101 ")) {
            throw IOException("WebSocket upgrade rejected: ${block.requestLine}")
        }
        val expectedAccept = computeAcceptKey(clientKey)
        if (block.headers["sec-websocket-accept"] != expectedAccept) {
            throw IOException("Invalid Sec-WebSocket-Accept header")
        }
    }
}
