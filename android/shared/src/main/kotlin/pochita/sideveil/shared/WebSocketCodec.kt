package pochita.sideveil.shared

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

enum class WsOpcode(
    val code: Int,
) {
    TEXT(0x1),
    BINARY(0x2),
    CLOSE(0x8),
    PING(0x9),
    PONG(0xA),
}

data class WsFrame(
    val opcode: WsOpcode,
    val payload: ByteArray,
) {
    fun textOrNull(): String? = if (opcode == WsOpcode.TEXT) String(payload, StandardCharsets.UTF_8) else null

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is WsFrame) return false
        return opcode == other.opcode && payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int = 31 * opcode.hashCode() + payload.contentHashCode()
}

data class HttpHeaderBlock(
    val requestLine: String,
    val headers: Map<String, String>,
)

/**
 * Minimal, pure-stdlib RFC 6455 WebSocket and HTTP/1.1 header framing helper.
 * Supports full-duplex text frames, binary frames, fragmentation, and control frames.
 */
object WebSocketCodec {
    private const val WS_MAGIC_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
    private const val MAX_HEADER_BYTES = 32 * 1024
    private const val MAX_PAYLOAD_BYTES = 8 * 1024 * 1024
    private val rng = SecureRandom()

    fun computeAcceptKey(clientKey: String): String {
        val sha1 = MessageDigest.getInstance("SHA-1")
        val digest = sha1.digest((clientKey.trim() + WS_MAGIC_GUID).toByteArray(StandardCharsets.US_ASCII))
        return Base64.getEncoder().encodeToString(digest)
    }

    fun readHttpHeaderBlock(input: InputStream): HttpHeaderBlock? {
        val buf = ByteArrayOutputStream(512)
        var state = 0
        while (buf.size() < MAX_HEADER_BYTES) {
            val b = input.read()
            if (b < 0) {
                if (buf.size() == 0) return null
                throw EOFException("Unexpected EOF while reading HTTP headers")
            }
            buf.write(b)
            state =
                when {
                    state == 0 && b == '\r'.code -> 1
                    state == 1 && b == '\n'.code -> 2
                    state == 2 && b == '\r'.code -> 3
                    state == 3 && b == '\n'.code -> 4
                    b == '\r'.code -> 1
                    else -> 0
                }
            if (state == 4) {
                break
            }
        }
        if (state != 4) {
            throw IOException("HTTP header block exceeded $MAX_HEADER_BYTES bytes")
        }

        val raw = String(buf.toByteArray(), StandardCharsets.US_ASCII)
        val lines = raw.split("\r\n").filter { it.isNotEmpty() }
        if (lines.isEmpty()) return null

        val headers = LinkedHashMap<String, String>(lines.size)
        for (i in 1 until lines.size) {
            val idx = lines[i].indexOf(':')
            if (idx > 0) {
                val name = lines[i].substring(0, idx).trim().lowercase(Locale.ROOT)
                val value = lines[i].substring(idx + 1).trim()
                headers[name] = value
            }
        }
        return HttpHeaderBlock(requestLine = lines[0], headers = headers)
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
        input: InputStream,
        output: OutputStream,
        path: String,
        hostHeader: String,
        extraHeaders: Map<String, String> = emptyMap(),
    ) {
        val nonce = ByteArray(16)
        rng.nextBytes(nonce)
        val clientKey = Base64.getEncoder().encodeToString(nonce)
        val req =
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
        output.write(req.toByteArray(StandardCharsets.US_ASCII))
        output.flush()

        val block =
            readHttpHeaderBlock(input)
                ?: throw IOException("Server closed connection during WebSocket handshake")
        if (!block.requestLine.contains(" 101 ")) {
            throw IOException("WebSocket upgrade rejected: ${block.requestLine}")
        }
        val expectedAccept = computeAcceptKey(clientKey)
        val actualAccept = block.headers["sec-websocket-accept"]
        if (actualAccept != expectedAccept) {
            throw IOException("Invalid Sec-WebSocket-Accept header")
        }
    }

    fun readFrame(input: InputStream): WsFrame? {
        var fragmentedOpcode: WsOpcode? = null
        var fragmentBuffer: ByteArrayOutputStream? = null

        while (true) {
            val b1 = input.read()
            if (b1 < 0) return null
            val b2 = readByteOrEof(input)

            val fin = (b1 and 0x80) != 0
            val rawOpcode = b1 and 0x0F
            val masked = (b2 and 0x80) != 0
            val len7 = b2 and 0x7F

            val payloadLen =
                when (len7) {
                    126 -> {
                        val hi = readByteOrEof(input)
                        val lo = readByteOrEof(input)
                        ((hi shl 8) or lo).toLong()
                    }

                    127 -> {
                        var len = 0L
                        repeat(8) {
                            len = (len shl 8) or readByteOrEof(input).toLong()
                        }
                        len
                    }

                    else -> {
                        len7.toLong()
                    }
                }

            if (payloadLen < 0 || payloadLen > MAX_PAYLOAD_BYTES) {
                throw IOException("WebSocket frame payload too large: $payloadLen")
            }

            val maskKey =
                if (masked) {
                    readExactBytes(input, 4)
                } else {
                    null
                }
            val payload = readExactBytes(input, payloadLen.toInt())
            if (maskKey != null) {
                for (i in payload.indices) {
                    payload[i] = (payload[i].toInt() xor maskKey[i and 3].toInt()).toByte()
                }
            }

            if (rawOpcode == 0) {
                val acc = fragmentBuffer ?: throw IOException("Unexpected continuation frame")
                if (acc.size() + payload.size > MAX_PAYLOAD_BYTES) {
                    throw IOException("Fragmented WebSocket message exceeded $MAX_PAYLOAD_BYTES bytes")
                }
                acc.write(payload)
                if (fin) {
                    val op = fragmentedOpcode ?: throw IOException("Missing fragmented opcode")
                    return WsFrame(op, acc.toByteArray())
                }
                continue
            }

            val opcode =
                WsOpcode.entries.firstOrNull { it.code == rawOpcode }
                    ?: throw IOException("Unsupported WebSocket opcode: 0x${rawOpcode.toString(16)}")

            // Control frames (Close/Ping/Pong) may be interleaved between fragments and are never fragmented.
            if (opcode == WsOpcode.CLOSE || opcode == WsOpcode.PING || opcode == WsOpcode.PONG) {
                return WsFrame(opcode, payload)
            }

            if (fin) {
                return WsFrame(opcode, payload)
            }

            fragmentedOpcode = opcode
            fragmentBuffer =
                ByteArrayOutputStream(payload.size.coerceAtLeast(256)).apply {
                    write(payload)
                }
        }
    }

    fun writeText(
        output: OutputStream,
        text: String,
        mask: Boolean,
    ) {
        writeFrame(output, WsOpcode.TEXT, text.toByteArray(StandardCharsets.UTF_8), mask)
    }

    fun writeBinary(
        output: OutputStream,
        data: ByteArray,
        mask: Boolean,
    ) {
        writeFrame(output, WsOpcode.BINARY, data, mask)
    }

    fun writePong(
        output: OutputStream,
        payload: ByteArray,
        mask: Boolean,
    ) {
        writeFrame(output, WsOpcode.PONG, payload, mask)
    }

    fun writePing(
        output: OutputStream,
        payload: ByteArray = ByteArray(0),
        mask: Boolean,
    ) {
        writeFrame(output, WsOpcode.PING, payload, mask)
    }

    fun writeClose(
        output: OutputStream,
        code: Int = 1000,
        reason: String = "",
        mask: Boolean,
    ) {
        val reasonBytes = reason.toByteArray(StandardCharsets.UTF_8)
        val payload = ByteArray(2 + reasonBytes.size)
        payload[0] = ((code ushr 8) and 0xFF).toByte()
        payload[1] = (code and 0xFF).toByte()
        reasonBytes.copyInto(payload, destinationOffset = 2)
        writeFrame(output, WsOpcode.CLOSE, payload, mask)
    }

    fun writeFrame(
        output: OutputStream,
        opcode: WsOpcode,
        payload: ByteArray,
        mask: Boolean,
    ) {
        val len = payload.size
        val header = ByteArrayOutputStream(14)
        header.write(0x80 or opcode.code)
        val maskBit = if (mask) 0x80 else 0x00
        when {
            len <= 125 -> {
                header.write(maskBit or len)
            }

            len <= 65535 -> {
                header.write(maskBit or 126)
                header.write((len ushr 8) and 0xFF)
                header.write(len and 0xFF)
            }

            else -> {
                header.write(maskBit or 127)
                val len64 = len.toLong()
                for (shift in 56 downTo 0 step 8) {
                    header.write(((len64 ushr shift) and 0xFFL).toInt())
                }
            }
        }

        output.write(header.toByteArray())
        if (mask) {
            val maskKey = ByteArray(4)
            rng.nextBytes(maskKey)
            output.write(maskKey)
            val maskedPayload = ByteArray(len)
            for (i in 0 until len) {
                maskedPayload[i] = (payload[i].toInt() xor maskKey[i and 3].toInt()).toByte()
            }
            output.write(maskedPayload)
        } else {
            output.write(payload)
        }
        output.flush()
    }

    private fun readByteOrEof(input: InputStream): Int {
        val b = input.read()
        if (b < 0) throw EOFException("Unexpected EOF in WebSocket stream")
        return b
    }

    private fun readExactBytes(
        input: InputStream,
        length: Int,
    ): ByteArray {
        val result = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val n = input.read(result, offset, length - offset)
            if (n < 0) throw EOFException("Unexpected EOF reading $length bytes")
            offset += n
        }
        return result
    }
}
