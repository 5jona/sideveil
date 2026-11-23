package pochita.sideveil.host.plugin

import java.util.Locale

/**
 * Pure-Kotlin static web asset MIME table and UTF-8 text classification for [PluginAssetFetcher].
 */
internal object PluginAssetMimeTypes {
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
}
