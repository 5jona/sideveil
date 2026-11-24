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
 * injects Host-computed CSP, `X-Content-Type-Options: nosniff`, and on-demand COOP/COEP headers,
 * proxies loopback Dev Mode servers with bounded streaming, and loads static assets from APK
 * `assets/panel/` or `sideveild`'s loopback HTTP.
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
            val encodedRel = encodeRelativePath(rel)
            val querySuffix = uri.encodedQuery?.let { "?$it" }.orEmpty()
            return fetchLoopbackUrl(
                descriptor = descriptor,
                targetUrl = "$devBase/$encodedRel$querySuffix",
                authToken = null,
                fallbackPath = rel,
                isMainFrame = isMainFrame,
            )
        }
        return if (descriptor.builtin) {
            openBuiltinAsset(descriptor, rawPath.ifEmpty { "index.html" }, coi = coi)
        } else {
            fetchExternalAsset(
                descriptor = descriptor,
                relativePath = rawPath.ifEmpty { descriptor.entry.trimStart('/') },
                isMainFrame = isMainFrame,
            )
        }
    }

    private fun openBuiltinAsset(
        descriptor: PluginDescriptor,
        relativePath: String,
        coi: Boolean,
    ): WebResourceResponse {
        val normalized = relativePath.trimStart('/').ifEmpty { "index.html" }
        val assetPath = "panel/$normalized"
        return try {
            val stream = context.assets.open(assetPath)
            val mime = mimeTypeFor(normalized)
            val csp = resolveCspForStatus(descriptor, statusCode = 200)
            WebResourceResponse(mime, "UTF-8", 200, "OK", buildResponseHeaders(coi, csp), stream)
        } catch (_: IOException) {
            runCatching {
                val indexStream = context.assets.open("panel/index.html")
                val csp = resolveCspForStatus(descriptor, statusCode = 200)
                WebResourceResponse("text/html", "UTF-8", 200, "OK", buildResponseHeaders(coi, csp), indexStream)
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
        val encodedPath = encodeRelativePath(normalized)
        val url = "http://127.0.0.1:$port/plugins/${descriptor.id}/$encodedPath"
        val response = fetchLoopbackUrl(descriptor, url, authToken = token, fallbackPath = normalized, isMainFrame = isMainFrame)
        val entryPath = descriptor.entry.trimStart('/').ifEmpty { "index.html" }
        val isSpaRoute =
            descriptor.network?.spaFallback == true &&
                response.statusCode == 404 &&
                normalized != entryPath &&
                !normalized.substringAfterLast('/').contains('.')
        return if (isSpaRoute) {
            runCatching { response.data?.close() }
            val encodedEntry = encodeRelativePath(entryPath)
            val entryUrl = "http://127.0.0.1:$port/plugins/${descriptor.id}/$encodedEntry"
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
            var streamOwnedByResponse = false
            try {
                val code = conn.responseCode
                if (conn.contentLengthLong > MAX_RESPONSE_BYTES) {
                    return@runCatching errorResponse(413, "Payload Too Large")
                }
                val contentType = conn.contentType ?: mimeTypeFor(fallbackPath)
                val mime = contentType.substringBefore(';').trim().ifEmpty { mimeTypeFor(fallbackPath) }
                val encoding = if (isUtf8Mime(mime)) "UTF-8" else null
                val headers = buildResponseHeaders(coi, resolveCspForStatus(descriptor, code))
                val reason = conn.responseMessage ?: if (code == 200) "OK" else "Error"
                if (code in 200..299) {
                    val boundedStream = BoundedConnectionInputStream(conn, conn.inputStream, MAX_RESPONSE_BYTES)
                    streamOwnedByResponse = true
                    WebResourceResponse(mime, encoding, code, reason, headers, boundedStream)
                } else {
                    val errBody =
                        conn.errorStream?.use { readBoundedBytes(it, MAX_RESPONSE_BYTES) } ?: ByteArray(0)
                    WebResourceResponse(mime, encoding, code, reason, headers, ByteArrayInputStream(errBody))
                }
            } finally {
                if (!streamOwnedByResponse) {
                    conn.disconnect()
                }
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
    ): WebResourceResponse {
        val mergedHeaders = LinkedHashMap<String, String>(headers.size + 1)
        mergedHeaders["X-Content-Type-Options"] = "nosniff"
        mergedHeaders.putAll(headers)
        return WebResourceResponse(
            "text/plain",
            "UTF-8",
            status,
            reason,
            mergedHeaders,
            ByteArrayInputStream(reason.toByteArray(Charsets.UTF_8)),
        )
    }

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

        /** Returns the document CSP header for all virtual-origin 2xx responses (including JS workers, SVG, and XML). */
        internal fun resolveCspForStatus(
            descriptor: PluginDescriptor,
            statusCode: Int,
        ): String? = if (statusCode in 200..299) PluginCspBuilder.buildDocumentCsp(descriptor) else null

        /** Builds HTTP response headers with `nosniff`, optional CSP, and optional COOP/COEP isolation. */
        fun buildResponseHeaders(
            crossOriginIsolated: Boolean,
            contentSecurityPolicy: String? = null,
        ): Map<String, String> =
            buildMap {
                put("Cache-Control", "no-store")
                put("X-Content-Type-Options", "nosniff")
                if (!contentSecurityPolicy.isNullOrBlank()) {
                    put("Content-Security-Policy", contentSecurityPolicy)
                }
                if (crossOriginIsolated) {
                    put("Cross-Origin-Opener-Policy", "same-origin")
                    put("Cross-Origin-Embedder-Policy", "require-corp")
                    put("Cross-Origin-Resource-Policy", "same-origin")
                }
            }

        /** Percent-encodes each path segment of a decoded relative asset path for safe [URI] construction. */
        fun encodeRelativePath(rawPath: String): String = PluginAssetMimeTypes.encodeRelativePath(rawPath)

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
