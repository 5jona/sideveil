package pochita.sideveil.protocol

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.WeakHashMap

/**
 * Minimal, pure-stdlib RFC 6455 WebSocket and HTTP/1.1 header framing helper.
 * Supports full-duplex text frames, binary frames, fragmentation, and control frames.
 */
object WebSocketCodec {
    private const val MAX_PAYLOAD_BYTES = 8 * 1024 * 1024
    private const val MAX_CONTROL_FRAME_BYTES = 125L
    private val rng = SecureRandom()

    private class FragmentAssembly(
        val opcode: WsOpcode,
        val buffer: ByteArrayOutputStream,
    )

    private data class RawFrame(
        val fin: Boolean,
        val rawOpcode: Int,
        val payload: ByteArray,
    )

    private val fragmentStates = WeakHashMap<InputStream, FragmentAssembly>()

    fun clearStreamState(input: InputStream) {
        synchronized(fragmentStates) {
            fragmentStates.remove(input)
        }
    }

    fun computeAcceptKey(clientKey: String): String = WebSocketHandshake.computeAcceptKey(clientKey)

    fun readHttpHeaderBlock(input: InputStream): HttpHeaderBlock? = WebSocketHandshake.readHttpHeaderBlock(input)

    fun writeServerUpgradeResponse(
        headers: Map<String, String>,
        output: OutputStream,
    ) = WebSocketHandshake.writeServerUpgradeResponse(headers, output)

    fun performClientHandshake(
        input: InputStream,
        output: OutputStream,
        path: String,
        hostHeader: String,
        extraHeaders: Map<String, String> = emptyMap(),
    ) = WebSocketHandshake.performClientHandshake(rng, input, output, path, hostHeader, extraHeaders)

    fun readFrame(
        input: InputStream,
        requireMasked: Boolean = false,
    ): WsFrame? {
        while (true) {
            val raw = readRawFrame(input, requireMasked) ?: return null
            val assembled = assembleFrame(input, raw)
            if (assembled != null) {
                return assembled
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
        val header = encodeFrameHeader(opcode, payload.size, mask)
        output.write(header)
        if (mask) {
            val maskKey = ByteArray(4)
            rng.nextBytes(maskKey)
            output.write(maskKey)
            val maskedPayload = ByteArray(payload.size)
            for (i in payload.indices) {
                maskedPayload[i] = (payload[i].toInt() xor maskKey[i and 3].toInt()).toByte()
            }
            output.write(maskedPayload)
        } else {
            output.write(payload)
        }
        output.flush()
    }

    private fun readRawFrame(
        input: InputStream,
        requireMasked: Boolean,
    ): RawFrame? {
        val b1 = input.read()
        if (b1 < 0) {
            clearStreamState(input)
            return null
        }
        val b2 = readByteOrEof(input)

        val fin = (b1 and 0x80) != 0
        val rawOpcode = b1 and 0x0F
        val masked = (b2 and 0x80) != 0
        val len7 = b2 and 0x7F
        validateFrameHeader(b1 and 0x70, rawOpcode, fin, masked, requireMasked, len7)

        val payloadLen = readPayloadLength(input, len7)
        val maskKey = if (masked) readExactBytes(input, 4) else null
        val payload = readExactBytes(input, payloadLen)
        if (maskKey != null) {
            unmaskInPlace(payload, maskKey)
        }
        return RawFrame(fin = fin, rawOpcode = rawOpcode, payload = payload)
    }

    private fun validateFrameHeader(
        rsv: Int,
        rawOpcode: Int,
        fin: Boolean,
        masked: Boolean,
        requireMasked: Boolean,
        len7: Int,
    ) {
        val isControl = (rawOpcode and 0x08) != 0
        val error =
            when {
                rsv != 0 -> {
                    "Unsupported WebSocket RSV bits: 0x${rsv.toString(16)}"
                }

                requireMasked && !masked -> {
                    "RFC 6455 violation: client frame must be masked"
                }

                isControl && !fin -> {
                    "RFC 6455 violation: control frame must not be fragmented"
                }

                isControl && len7 > MAX_CONTROL_FRAME_BYTES -> {
                    "RFC 6455 violation: control frame payload exceeds 125 bytes ($len7)"
                }

                else -> {
                    null
                }
            }
        if (error != null) {
            throw IOException(error)
        }
    }

    private fun readPayloadLength(
        input: InputStream,
        len7: Int,
    ): Int {
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
        return payloadLen.toInt()
    }

    private fun assembleFrame(
        input: InputStream,
        raw: RawFrame,
    ): WsFrame? {
        if (raw.rawOpcode == 0) {
            return handleContinuationFrame(input, raw.fin, raw.payload)
        }
        val opcode =
            WsOpcode.entries.firstOrNull { it.code == raw.rawOpcode }
                ?: throw IOException("Unsupported WebSocket opcode: 0x${raw.rawOpcode.toString(16)}")

        // Control frames (Close/Ping/Pong) may be interleaved between fragments without resetting fragment state.
        if (opcode == WsOpcode.CLOSE || opcode == WsOpcode.PING || opcode == WsOpcode.PONG) {
            return WsFrame(opcode, raw.payload)
        }

        val hasInFlightFragment = synchronized(fragmentStates) { fragmentStates.containsKey(input) }
        if (hasInFlightFragment) {
            clearStreamState(input)
            throw IOException("RFC 6455 violation: new data frame started before fragmented message completed")
        }

        if (raw.fin) {
            return WsFrame(opcode, raw.payload)
        }

        val newAssembly =
            FragmentAssembly(
                opcode = opcode,
                buffer =
                    ByteArrayOutputStream(raw.payload.size.coerceAtLeast(256)).apply {
                        write(raw.payload)
                    },
            )
        synchronized(fragmentStates) {
            fragmentStates[input] = newAssembly
        }
        return null
    }

    private fun handleContinuationFrame(
        input: InputStream,
        fin: Boolean,
        payload: ByteArray,
    ): WsFrame? {
        val assembly =
            synchronized(fragmentStates) { fragmentStates[input] }
                ?: throw IOException("Unexpected continuation frame")
        if (assembly.buffer.size() + payload.size > MAX_PAYLOAD_BYTES) {
            clearStreamState(input)
            throw IOException("Fragmented WebSocket message exceeded $MAX_PAYLOAD_BYTES bytes")
        }
        assembly.buffer.write(payload)
        if (!fin) return null
        clearStreamState(input)
        return WsFrame(assembly.opcode, assembly.buffer.toByteArray())
    }

    private fun encodeFrameHeader(
        opcode: WsOpcode,
        len: Int,
        mask: Boolean,
    ): ByteArray {
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
        return header.toByteArray()
    }

    private fun unmaskInPlace(
        payload: ByteArray,
        maskKey: ByteArray,
    ) {
        for (i in payload.indices) {
            payload[i] = (payload[i].toInt() xor maskKey[i and 3].toInt()).toByte()
        }
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
