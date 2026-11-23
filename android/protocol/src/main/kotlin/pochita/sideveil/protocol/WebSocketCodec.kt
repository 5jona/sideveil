package pochita.sideveil.protocol

import java.io.ByteArrayOutputStream
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
    private val rng = SecureRandom()

    private class FragmentAssembly(
        val opcode: WsOpcode,
        val buffer: ByteArrayOutputStream,
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
            val raw =
                WebSocketFrameIo.readRawFrame(input, requireMasked) {
                    clearStreamState(input)
                } ?: return null
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
        WebSocketFrameIo.writeFrame(rng, output, opcode, payload, mask)
    }

    private fun assembleFrame(
        input: InputStream,
        raw: RawWsFrame,
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
        if (assembly.buffer.size() + payload.size > WebSocketFrameIo.MAX_PAYLOAD_BYTES) {
            clearStreamState(input)
            throw IOException(
                "Fragmented WebSocket message exceeded ${WebSocketFrameIo.MAX_PAYLOAD_BYTES} bytes",
            )
        }
        assembly.buffer.write(payload)
        if (!fin) return null
        clearStreamState(input)
        return WsFrame(assembly.opcode, assembly.buffer.toByteArray())
    }
}
