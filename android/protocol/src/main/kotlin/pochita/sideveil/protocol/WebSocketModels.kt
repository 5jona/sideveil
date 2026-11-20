package pochita.sideveil.protocol

import java.nio.charset.StandardCharsets

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
