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
 * Resolves isolated per-plugin virtual HTTPS origins (`https://<pluginId>.plugins.sideveil.local/`),
 * injects on-demand COOP/COEP headers for WASM/WebGPU plugins, proxies per-plugin loopback Dev Mode
 * servers, and loads static assets either from APK `assets/panel/` or `sideveild`'s loopback HTTP.
 */
class PluginAssetFetcher(
    private val context: Context,
    private val endpointProvider: () -> Pair<Int, String>?,
) {
    fun hostFor(pluginId: String): String = "$pluginId.$PLUGIN_ORIGIN_SUFFIX"

    fun originFor(pluginId: String): String = "https://${hostFor(pluginId)}"

    fun entryUrlFor(descriptor: PluginDescriptor): String {
        val cleanEntry =
            if (!descriptor.devServerUrl.isNullOrBlank()) {
                descriptor.entry
                    .trimStart('/')
                    .removePrefix("web/")
                    .ifEmpty { "index.html" }
            } else {
                descriptor.entry.trimStart('/').ifEmpty { "index.html" }
            }
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
        val coi = descriptor.surface.crossOriginIsolated
        val devBase = descriptor.devServerUrl?.trim()?.takeIf { isValidLoopbackDevUrl(it) }
        if (devBase != null) {
            val rel = rawPath.removePrefix("web/").ifEmpty { "index.html" }
            val querySuffix = uri.encodedQuery?.let { "?$it" }.orEmpty()
            return fetchLoopbackUrl("$devBase/$rel$querySuffix", authToken = null, fallbackPath = rel, coi = coi)
        }
        return if (descriptor.builtin) {
            openBuiltinAsset(rawPath.ifEmpty { "index.html" }, coi = coi)
        } else {
            fetchExternalAsset(descriptor, rawPath.ifEmpty { descriptor.entry.trimStart('/') }, coi = coi)
        }
    }

    private fun openBuiltinAsset(
        relativePath: String,
        coi: Boolean,
    ): WebResourceResponse {
        val normalized = relativePath.trimStart('/').ifEmpty { "index.html" }
        val assetPath = "panel/$normalized"
        val headers = buildResponseHeaders(coi)
        return try {
            val stream = context.assets.open(assetPath)
            WebResourceResponse(mimeTypeFor(normalized), "UTF-8", 200, "OK", headers, stream)
        } catch (_: IOException) {
            runCatching {
                val indexStream = context.assets.open("panel/index.html")
                WebResourceResponse("text/html", "UTF-8", 200, "OK", headers, indexStream)
            }.getOrElse {
                errorResponse(404, "Not Found")
            }
        }
    }

    private fun fetchExternalAsset(
        descriptor: PluginDescriptor,
        relativePath: String,
        coi: Boolean,
    ): WebResourceResponse {
        val (port, token) = endpointProvider() ?: return errorResponse(503, "Hub Unavailable")
        val normalized = relativePath.trimStart('/')
        val url = "http://127.0.0.1:$port/plugins/${descriptor.id}/$normalized"
        return fetchLoopbackUrl(url, authToken = token, fallbackPath = normalized, coi = coi)
    }

    private fun fetchLoopbackUrl(
        targetUrl: String,
        authToken: String?,
        fallbackPath: String,
        coi: Boolean,
    ): WebResourceResponse =
        runCatching {
            val conn =
                (URI(targetUrl).toURL().openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = READ_TIMEOUT_MS
                    useCaches = false
                    if (authToken != null) {
                        setRequestProperty("Authorization", "Bearer $authToken")
                    }
                }
            try {
                val code = conn.responseCode
                val body =
                    if (code in 200..299) {
                        conn.inputStream.use { it.readBytes() }
                    } else {
                        conn.errorStream?.use { it.readBytes() } ?: ByteArray(0)
                    }
                val contentType = conn.contentType ?: mimeTypeFor(fallbackPath)
                val mime = contentType.substringBefore(';').trim().ifEmpty { mimeTypeFor(fallbackPath) }
                val encoding =
                    if (mime.startsWith("text/") || mime.contains("javascript") || mime.contains("json")) {
                        "UTF-8"
                    } else {
                        null
                    }
                WebResourceResponse(
                    mime,
                    encoding,
                    code,
                    conn.responseMessage ?: if (code == 200) "OK" else "Error",
                    buildResponseHeaders(coi),
                    ByteArrayInputStream(body),
                )
            } finally {
                conn.disconnect()
            }
        }.getOrElse {
            errorResponse(502, "Asset Fetch Failed")
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

        fun buildResponseHeaders(crossOriginIsolated: Boolean): Map<String, String> =
            if (crossOriginIsolated) {
                mapOf(
                    "Cache-Control" to "no-store",
                    "Cross-Origin-Opener-Policy" to "same-origin",
                    "Cross-Origin-Embedder-Policy" to "require-corp",
                    "Cross-Origin-Resource-Policy" to "same-origin",
                )
            } else {
                mapOf("Cache-Control" to "no-store")
            }

        fun isValidLoopbackDevUrl(raw: String): Boolean {
            val trimmed = raw.trim().trimEnd('/')
            if (!trimmed.startsWith("http://")) return false
            val host = trimmed.removePrefix("http://").substringBefore('/').substringBefore(':')
            return host == "127.0.0.1" || host == "localhost"
        }
    }
}
