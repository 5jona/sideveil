package pochita.sideveil.protocol

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom

internal data class RawWsFrame(
    val fin: Boolean,
    val rawOpcode: Int,
    val payload: ByteArray,
)

/**
 * Low-level RFC 6455 binary frame header/payload I/O helpers shared by [WebSocketCodec].
 */
internal object WebSocketFrameIo {
    const val MAX_PAYLOAD_BYTES = 8 * 1024 * 1024
    private const val MAX_CONTROL_FRAME_BYTES = 125L

    fun writeFrame(
        rng: SecureRandom,
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

    fun readRawFrame(
        input: InputStream,
        requireMasked: Boolean,
        onEof: () -> Unit,
    ): RawWsFrame? {
        val b1 = input.read()
        if (b1 < 0) {
            onEof()
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
        return RawWsFrame(fin = fin, rawOpcode = rawOpcode, payload = payload)
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
