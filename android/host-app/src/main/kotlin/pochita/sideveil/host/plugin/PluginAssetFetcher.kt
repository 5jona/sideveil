package pochita.sideveil.host.plugin

import android.content.Context
import android.net.Uri
import android.util.Log
import android.webkit.WebResourceResponse
import pochita.sideveil.protocol.PluginDescriptor
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
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
    private val onFetchError: ((pluginId: String, url: String, description: String, isMainFrame: Boolean) -> Unit)? = null,
) {
    /** Returns the virtual hostname (`<pluginId>.plugins.sideveil.local`) for [pluginId]. */
    fun hostFor(pluginId: String): String = "$pluginId.$PLUGIN_ORIGIN_SUFFIX"

    /** Returns the virtual HTTPS origin (`https://<pluginId>.plugins.sideveil.local`) for [pluginId]. */
    fun originFor(pluginId: String): String = "https://${hostFor(pluginId)}"

    /** Resolves the normalized entry document URL for [descriptor] under its virtual origin. */
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

    /** Returns `true` if [uri] belongs to [pluginId]'s virtual HTTPS origin on the default port. */
    fun isAllowedPluginUri(
        uri: Uri?,
        pluginId: String,
    ): Boolean {
        if (uri == null) return false
        return uri.scheme == "https" &&
            uri.host == hostFor(pluginId) &&
            uri.port == -1
    }

    /** Intercepts and serves a virtual-origin asset request for [descriptor]. */
    fun fetch(
        descriptor: PluginDescriptor,
        uri: Uri,
        isMainFrame: Boolean = false,
    ): WebResourceResponse {
        if (!isAllowedPluginUri(uri, descriptor.id)) return errorResponse(403, "Forbidden")
        val rawPath = uri.path.orEmpty().trimStart('/')
        if (rawPath.contains("..") || rawPath.contains('\\')) return errorResponse(403, "Forbidden")
        val coi = descriptor.surface.crossOriginIsolated
        if (isFaviconShortCircuitPath(rawPath)) {
            return WebResourceResponse(
                "image/x-icon",
                null,
                204,
                "No Content",
                buildResponseHeaders(coi),
                ByteArrayInputStream(ByteArray(0)),
            )
        }
        val devBase =
            descriptor.devServerUrl
                ?.trim()
                ?.trimEnd('/')
                ?.takeIf(::isValidLoopbackDevUrl)
        if (devBase != null) {
            val rel = rawPath.removePrefix("web/").ifEmpty { "index.html" }
            val querySuffix = uri.encodedQuery?.let { "?$it" }.orEmpty()
            return fetchLoopbackUrl(
                descriptor = descriptor,
                targetUrl = "$devBase/$rel$querySuffix",
                authToken = null,
                fallbackPath = rel,
                isMainFrame = isMainFrame,
            )
        }
        return if (descriptor.builtin) {
            openBuiltinAsset(rawPath.ifEmpty { "index.html" }, coi = coi)
        } else {
            fetchExternalAsset(
                descriptor = descriptor,
                relativePath = rawPath.ifEmpty { descriptor.entry.trimStart('/') },
                isMainFrame = isMainFrame,
            )
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
        isMainFrame: Boolean,
    ): WebResourceResponse {
        val (port, token) = endpointProvider() ?: return errorResponse(503, "Hub Unavailable")
        val normalized = relativePath.trimStart('/')
        val url = "http://127.0.0.1:$port/plugins/${descriptor.id}/$normalized"
        val response = fetchLoopbackUrl(descriptor, url, authToken = token, fallbackPath = normalized, isMainFrame = isMainFrame)
        val entryPath = descriptor.entry.trimStart('/').ifEmpty { "index.html" }
        val isSpaRoute =
            descriptor.network?.spaFallback == true &&
                response.statusCode == 404 &&
                normalized != entryPath &&
                !normalized.substringAfterLast('/').contains('.')
        return if (isSpaRoute) {
            val entryUrl = "http://127.0.0.1:$port/plugins/${descriptor.id}/$entryPath"
            fetchLoopbackUrl(descriptor, entryUrl, authToken = token, fallbackPath = entryPath, isMainFrame = isMainFrame)
        } else {
            response
        }
    }

    /** Builds a synthetic `403 Forbidden` plain-text [WebResourceResponse]. */
    fun forbiddenResponse(): WebResourceResponse = errorResponse(403, "Forbidden")

    private fun fetchLoopbackUrl(
        descriptor: PluginDescriptor,
        targetUrl: String,
        authToken: String?,
        fallbackPath: String,
        isMainFrame: Boolean,
    ): WebResourceResponse {
        val coi = descriptor.surface.crossOriginIsolated
        return runCatching {
            val conn =
                (URI(targetUrl).toURL().openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    instanceFollowRedirects = false
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = READ_TIMEOUT_MS
                    useCaches = false
                    if (authToken != null) {
                        setRequestProperty("Authorization", "Bearer $authToken")
                    }
                }
            try {
                val code = conn.responseCode
                if (conn.contentLengthLong > MAX_RESPONSE_BYTES) {
                    return@runCatching errorResponse(413, "Payload Too Large")
                }
                val body =
                    if (code in 200..299) {
                        conn.inputStream.use { readBoundedBytes(it, MAX_RESPONSE_BYTES) }
                    } else {
                        conn.errorStream?.use { readBoundedBytes(it, MAX_RESPONSE_BYTES) } ?: ByteArray(0)
                    } ?: return@runCatching errorResponse(413, "Payload Too Large")
                val contentType = conn.contentType ?: mimeTypeFor(fallbackPath)
                val mime = contentType.substringBefore(';').trim().ifEmpty { mimeTypeFor(fallbackPath) }
                val encoding = if (isUtf8Mime(mime)) "UTF-8" else null
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
        }.getOrElse { err ->
            val safeUrl = WebViewDiagnosticsSanitizer.sanitizeUrl(targetUrl)
            val detail = "${err.javaClass.simpleName}: ${err.message.orEmpty()}".take(WebViewDiagnosticsSanitizer.MAX_DESCRIPTION_LEN)
            Log.w(TAG, "Loopback asset fetch failed plugin=${descriptor.id} url=$safeUrl ($detail)")
            onFetchError?.invoke(descriptor.id, safeUrl, detail, isMainFrame)
            errorResponse(502, SYNTHETIC_FETCH_FAILED_REASON, mapOf(SYNTHETIC_ERROR_HEADER to "1"))
        }
    }

    private fun isUtf8Mime(mime: String): Boolean = PluginAssetMimeTypes.isUtf8Mime(mime)

    private fun errorResponse(
        status: Int,
        reason: String,
        headers: Map<String, String> = emptyMap(),
    ): WebResourceResponse =
        WebResourceResponse(
            "text/plain",
            "UTF-8",
            status,
            reason,
            headers,
            ByteArrayInputStream(reason.toByteArray(Charsets.UTF_8)),
        )

    companion object {
        private const val TAG = "SideVeilAssetFetcher"
        const val PLUGIN_ORIGIN_SUFFIX = "plugins.sideveil.local"
        const val SYNTHETIC_ERROR_HEADER = "X-SideVeil-Synthetic-Error"
        const val SYNTHETIC_FETCH_FAILED_REASON = "Asset Fetch Failed"
        private const val CONNECT_TIMEOUT_MS = 2_000
        private const val READ_TIMEOUT_MS = 4_000
        internal const val MAX_RESPONSE_BYTES = 16 * 1024 * 1024

        /** Returns `true` when [rawPath] should short-circuit to `204 No Content` on virtual plugin origins. */
        fun isFaviconShortCircuitPath(rawPath: String): Boolean = rawPath == "favicon.ico"

        /** Resolves the MIME type for [path] based on its lowercase file extension. */
        fun mimeTypeFor(path: String): String = PluginAssetMimeTypes.mimeTypeFor(path)

        /** Builds HTTP response headers with optional COOP/COEP isolation headers. */
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

        /** Returns `true` if [raw] is a valid loopback HTTP/HTTPS dev-server base URL. */
        fun isValidLoopbackDevUrl(raw: String): Boolean = NetworkOriginParser.isValidLoopbackDevUrl(raw)

        internal fun readBoundedBytes(
            input: InputStream,
            maxBytes: Int = MAX_RESPONSE_BYTES,
        ): ByteArray? {
            if (maxBytes < 0) return null
            val out = ByteArrayOutputStream(minOf(maxBytes, 8 * 1024))
            val buffer = ByteArray(8 * 1024)
            var total = 0
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                total += read
                if (total > maxBytes) return null
                out.write(buffer, 0, read)
            }
            return out.toByteArray()
        }
    }
}
