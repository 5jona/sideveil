package pochita.sideveil.host

import android.content.Context
import android.net.Uri
import android.webkit.WebResourceResponse
import pochita.sideveil.protocol.PluginDescriptor
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI

/**
 * Resolves isolated per-plugin virtual HTTPS origins (`https://<pluginId>.plugins.sideveil.local/`)
 * and loads static assets either from APK `assets/panel/` (built-in) or from `sideveild`'s
 * authenticated loopback HTTP endpoint (`127.0.0.1:<port>/plugins/<pluginId>/...`).
 */
class PluginAssetFetcher(
    private val context: Context,
    private val endpointProvider: () -> Pair<Int, String>?,
) {
    fun hostFor(pluginId: String): String = "$pluginId.$PLUGIN_ORIGIN_SUFFIX"

    fun originFor(pluginId: String): String = "https://${hostFor(pluginId)}"

    fun entryUrlFor(descriptor: PluginDescriptor): String {
        val cleanEntry = descriptor.entry.trimStart('/').ifEmpty { "index.html" }
        return "${originFor(descriptor.id)}/$cleanEntry"
    }

    fun isAllowedPluginUri(
        uri: Uri?,
        pluginId: String,
    ): Boolean {
        if (uri == null) return false
        return uri.scheme == "https" &&
            uri.host == hostFor(pluginId) &&
            uri.port == -1
    }

    fun fetch(
        descriptor: PluginDescriptor,
        uri: Uri,
    ): WebResourceResponse {
        if (!isAllowedPluginUri(uri, descriptor.id)) {
            return errorResponse(403, "Forbidden")
        }
        val rawPath = uri.path.orEmpty().trimStart('/')
        if (rawPath.contains("..") || rawPath.contains('\\')) {
            return errorResponse(403, "Forbidden")
        }
        return if (descriptor.builtin) {
            openBuiltinAsset(rawPath.ifEmpty { "index.html" })
        } else {
            fetchExternalAsset(descriptor, rawPath.ifEmpty { descriptor.entry.trimStart('/') })
        }
    }

    private fun openBuiltinAsset(relativePath: String): WebResourceResponse {
        val normalized = relativePath.trimStart('/').ifEmpty { "index.html" }
        val assetPath = "panel/$normalized"
        return try {
            val stream = context.assets.open(assetPath)
            WebResourceResponse(mimeTypeFor(normalized), "UTF-8", stream)
        } catch (_: IOException) {
            runCatching {
                val indexStream = context.assets.open("panel/index.html")
                WebResourceResponse("text/html", "UTF-8", indexStream)
            }.getOrElse {
                errorResponse(404, "Not Found")
            }
        }
    }

    private fun fetchExternalAsset(
        descriptor: PluginDescriptor,
        relativePath: String,
    ): WebResourceResponse {
        val (port, token) = endpointProvider() ?: return errorResponse(503, "Hub Unavailable")
        val normalized = relativePath.trimStart('/')
        val url = URI("http://127.0.0.1:$port/plugins/${descriptor.id}/$normalized").toURL()
        return runCatching {
            val conn =
                (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = READ_TIMEOUT_MS
                    useCaches = false
                    setRequestProperty("Authorization", "Bearer $token")
                }
            try {
                val code = conn.responseCode
                val body =
                    if (code in 200..299) {
                        conn.inputStream.use { it.readBytes() }
                    } else {
                        conn.errorStream?.use { it.readBytes() } ?: ByteArray(0)
                    }
                val contentType = conn.contentType ?: mimeTypeFor(normalized)
                val mime = contentType.substringBefore(';').trim().ifEmpty { mimeTypeFor(normalized) }
                val encoding = if (mime.startsWith("text/") || mime.contains("javascript") || mime.contains("json")) "UTF-8" else null
                WebResourceResponse(
                    mime,
                    encoding,
                    code,
                    conn.responseMessage ?: if (code == 200) "OK" else "Error",
                    mapOf("Cache-Control" to "no-store"),
                    ByteArrayInputStream(body),
                )
            } finally {
                conn.disconnect()
            }
        }.getOrElse {
            errorResponse(502, "Asset Fetch Failed")
        }
    }

    private fun errorResponse(
        status: Int,
        reason: String,
    ): WebResourceResponse =
        WebResourceResponse(
            "text/plain",
            "UTF-8",
            status,
            reason,
            emptyMap(),
            ByteArrayInputStream(reason.toByteArray(Charsets.UTF_8)),
        )

    private fun mimeTypeFor(path: String): String =
        when {
            path.endsWith(".html") || path.endsWith(".htm") -> "text/html"
            path.endsWith(".js") || path.endsWith(".mjs") -> "application/javascript"
            path.endsWith(".css") -> "text/css"
            path.endsWith(".svg") -> "image/svg+xml"
            path.endsWith(".json") -> "application/json"
            path.endsWith(".png") -> "image/png"
            path.endsWith(".webp") -> "image/webp"
            path.endsWith(".woff2") -> "font/woff2"
            path.endsWith(".wasm") -> "application/wasm"
            else -> "application/octet-stream"
        }

    companion object {
        const val PLUGIN_ORIGIN_SUFFIX = "plugins.sideveil.local"
        private const val CONNECT_TIMEOUT_MS = 2_000
        private const val READ_TIMEOUT_MS = 4_000
    }
}
