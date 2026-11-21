package pochita.sideveil.host

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import android.util.LruCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pochita.sideveil.protocol.PluginDescriptor
import java.net.HttpURLConnection
import java.net.URI

/**
 * Asynchronous LRU-cached loader for plugin icons served by `sideveild`'s authenticated
 * `/plugins/<id>/__icon?size=<px>&theme=<dark|light>` endpoint (backed by `sideveil-gfx`
 * `usvg` + `vello_cpu` NEON SVG rasterization).
 */
internal class RailIconLoader(
    private val scope: CoroutineScope,
    private val endpointProvider: () -> Pair<Int, String>?,
) {
    private data class CachedEntry(
        val bitmap: Bitmap?,
    )

    private val cache = LruCache<String, CachedEntry>(MAX_CACHE_ENTRIES)

    fun load(
        plugin: PluginDescriptor,
        sizePx: Int,
        darkSurface: Boolean,
        onResult: (Bitmap?) -> Unit,
    ) {
        val iconRel = plugin.icon?.trim().orEmpty()
        if (plugin.builtin || iconRel.isEmpty()) {
            onResult(null)
            return
        }

        val clampedSize = sizePx.coerceIn(24, 256)
        val theme = if (darkSurface) "dark" else "light"
        val cacheKey = "${plugin.id}|${plugin.version}|$iconRel|$clampedSize|$theme"

        synchronized(cache) { cache.get(cacheKey) }?.let { cached ->
            onResult(cached.bitmap)
            return
        }

        scope.launch {
            val decoded =
                withContext(Dispatchers.IO) {
                    fetchIconBitmap(plugin.id, clampedSize, theme)
                }
            synchronized(cache) {
                cache.put(cacheKey, CachedEntry(decoded))
            }
            onResult(decoded)
        }
    }

    fun clear() {
        synchronized(cache) {
            cache.evictAll()
        }
    }

    private fun fetchIconBitmap(
        pluginId: String,
        sizePx: Int,
        theme: String,
    ): Bitmap? {
        val (port, token) = endpointProvider() ?: return null
        val url = URI("http://127.0.0.1:$port/plugins/$pluginId/__icon?size=$sizePx&theme=$theme").toURL()
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
                if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                    return@runCatching null
                }
                val bytes = conn.inputStream.use { it.readBytes() }
                if (bytes.isEmpty() || bytes.size > MAX_ICON_BYTES) {
                    return@runCatching null
                }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            } finally {
                conn.disconnect()
            }
        }.onFailure { err ->
            Log.d(TAG, "Failed to fetch icon for plugin=$pluginId: ${err.message}")
        }.getOrNull()
    }

    companion object {
        private const val TAG = "SideVeilRailIconLoader"
        private const val MAX_CACHE_ENTRIES = 32
        private const val CONNECT_TIMEOUT_MS = 1_500
        private const val READ_TIMEOUT_MS = 3_000
        private const val MAX_ICON_BYTES = 512 * 1024
    }
}
