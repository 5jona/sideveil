package pochita.sideveil.host.plugin

import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.util.Locale

/**
 * Pure-Kotlin static web asset MIME table, RFC 3986 path-segment percent encoding, and bounded
 * streaming [HttpURLConnection] wrapper for [PluginAssetFetcher].
 */
internal object PluginAssetMimeTypes {
    private const val HEX_DIGITS = "0123456789ABCDEF"
    private val UTF8_MIME_SUBSTRINGS = listOf("javascript", "json", "xml")

    private val MIME_TYPES_BY_EXT: Map<String, String> =
        mapOf(
            "html" to "text/html",
            "htm" to "text/html",
            "js" to "application/javascript",
            "mjs" to "application/javascript",
            "css" to "text/css",
            "svg" to "image/svg+xml",
            "json" to "application/json",
            "map" to "application/json",
            "webmanifest" to "application/manifest+json",
            "png" to "image/png",
            "jpg" to "image/jpeg",
            "jpeg" to "image/jpeg",
            "gif" to "image/gif",
            "webp" to "image/webp",
            "avif" to "image/avif",
            "ico" to "image/x-icon",
            "woff2" to "font/woff2",
            "woff" to "font/woff",
            "ttf" to "font/ttf",
            "otf" to "font/otf",
            "wasm" to "application/wasm",
            "txt" to "text/plain",
            "xml" to "application/xml",
        )

    fun mimeTypeFor(path: String): String {
        val clean = path.substringBefore('?').substringBefore('#')
        val fileName = clean.substringAfterLast('/')
        val ext = fileName.substringAfterLast('.', missingDelimiterValue = "").lowercase(Locale.ROOT)
        return MIME_TYPES_BY_EXT[ext] ?: "application/octet-stream"
    }

    fun isUtf8Mime(mime: String): Boolean = mime.startsWith("text/") || UTF8_MIME_SUBSTRINGS.any { mime.contains(it) }

    fun encodeRelativePath(rawPath: String): String =
        rawPath
            .trimStart('/')
            .split('/')
            .joinToString("/") { segment -> encodePathSegment(segment) }

    private fun encodePathSegment(segment: String): String {
        val bytes = segment.toByteArray(Charsets.UTF_8)
        val sb = StringBuilder(bytes.size)
        for (byte in bytes) {
            val u = byte.toInt() and 0xFF
            val unreserved =
                (u in 'A'.code..'Z'.code) ||
                    (u in 'a'.code..'z'.code) ||
                    (u in '0'.code..'9'.code) ||
                    u == '-'.code ||
                    u == '.'.code ||
                    u == '_'.code ||
                    u == '~'.code
            if (unreserved) {
                sb.append(u.toChar())
            } else {
                sb.append('%')
                sb.append(HEX_DIGITS[u ushr 4])
                sb.append(HEX_DIGITS[u and 0x0F])
            }
        }
        return sb.toString()
    }
}

/**
 * Streaming [FilterInputStream] enforcing a hard byte ceiling while releasing the underlying
 * [HttpURLConnection] on close.
 */
internal class BoundedConnectionInputStream(
    private val conn: HttpURLConnection,
    delegate: InputStream,
    private val maxBytes: Int,
) : FilterInputStream(delegate) {
    private var bytesRead = 0
    private var closed = false

    override fun read(): Int {
        val b = super.read()
        if (b >= 0) advance(1)
        return b
    }

    override fun read(
        b: ByteArray,
        off: Int,
        len: Int,
    ): Int {
        val n = super.read(b, off, len)
        if (n > 0) advance(n)
        return n
    }

    private fun advance(count: Int) {
        bytesRead += count
        if (bytesRead > maxBytes) {
            close()
            throw IOException("Asset response exceeded $maxBytes bytes")
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        try {
            super.close()
        } finally {
            conn.disconnect()
        }
    }
}
