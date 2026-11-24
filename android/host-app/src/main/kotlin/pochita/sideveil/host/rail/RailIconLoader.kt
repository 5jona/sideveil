package pochita.sideveil.host.rail

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.util.Log
import android.util.LruCache
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pochita.sideveil.host.R
import pochita.sideveil.host.overlay.GlassBackdropDrawable
import pochita.sideveil.host.overlay.OverlayWindowFlags
import pochita.sideveil.host.plugin.PluginAssetFetcher
import pochita.sideveil.protocol.PluginDescriptor
import java.net.HttpURLConnection
import java.net.URI

/**
 * Tracks active and recent touch activity on [PluginRailWindow] and builds its scrollable
 * dialog container hierarchy.
 */
@SuppressLint("ClickableViewAccessibility")
internal class RailTouchTracker(
    private val onTouchActivity: () -> Unit,
) {
    private var touchInProgress: Boolean = false
    private var lastTouchUptimeMs: Long = 0L

    fun isTouchActiveOrRecent(nowUptimeMs: Long = SystemClock.uptimeMillis()): Boolean =
        touchInProgress || (nowUptimeMs - lastTouchUptimeMs) in 0..RAIL_TOUCH_GRACE_MS

    fun reset() {
        touchInProgress = false
    }

    fun recordTouchEvent(ev: MotionEvent) {
        lastTouchUptimeMs = SystemClock.uptimeMillis()
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_POINTER_DOWN -> {
                touchInProgress = true
                onTouchActivity()
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                touchInProgress = false
                onTouchActivity()
            }
        }
    }

    fun createDialogAndBindContainer(
        themedContext: Context,
        density: Float,
        railContainer: FrameLayout,
        itemColumn: LinearLayout,
        surfaceController: PluginRailSurfaceController,
        onTapY: (Float) -> Unit,
    ): Dialog {
        val scroll =
            ScrollView(themedContext).apply {
                isVerticalScrollBarEnabled = false
                isVerticalFadingEdgeEnabled = true
                setFadingEdgeLength(OverlayWindowFlags.dp(density, FADING_EDGE_LENGTH_DP))
                overScrollMode = View.OVER_SCROLL_NEVER
                addView(
                    itemColumn,
                    FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
                )
            }
        railContainer.background = surfaceController.backdropDrawable
        railContainer.clipToOutline = true
        railContainer.outlineProvider = GlassBackdropDrawable.createContentOutlineProvider { surfaceController.cornerPx() }
        val padH = OverlayWindowFlags.dp(density, 2f)
        val padV = OverlayWindowFlags.dp(density, 6f)
        railContainer.setPadding(padH, padV, padH, padV)
        railContainer.isClickable = true
        railContainer.setOnTouchListener { _, ev ->
            if (ev.actionMasked == MotionEvent.ACTION_UP) {
                onTapY(ev.y)
            }
            true
        }
        railContainer.addView(
            scroll,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        val dialog =
            object : Dialog(themedContext, R.style.Theme_SideVeil_OverlayDialog) {
                override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
                    recordTouchEvent(ev)
                    return super.dispatchTouchEvent(ev)
                }
            }
        dialog.setCanceledOnTouchOutside(false)
        dialog.setCancelable(false)
        dialog.setContentView(
            railContainer,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        val window = dialog.window ?: error("Rail overlay dialog window unavailable")
        surfaceController.configureWindow(window)
        return dialog
    }

    companion object {
        private const val FADING_EDGE_LENGTH_DP = 18f
        private const val RAIL_TOUCH_GRACE_MS = 280L
    }
}

/**
 * Asynchronous LRU-cached loader for plugin icons served by `sideveild`'s authenticated
 * `/plugins/<id>/__icon?size=<px>&theme=<dark|light>` endpoint (backed by `sideveil-gfx`
 * `usvg` + `vello_cpu` NEON SVG rasterization).
 */
class RailIconLoader(
    private val scope: CoroutineScope,
    private val endpointProvider: () -> Pair<Int, String>?,
) {
    private data class CachedEntry(
        val bitmap: Bitmap?,
    )

    private val cache = LruCache<String, CachedEntry>(MAX_CACHE_ENTRIES)

    /** Asynchronously loads or retrieves from cache a rasterized icon for [plugin]. */
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

    /** Evicts all cached plugin icon bitmaps. */
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
                    instanceFollowRedirects = false
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = READ_TIMEOUT_MS
                    useCaches = false
                    setRequestProperty("Authorization", "Bearer $token")
                }
            try {
                if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                    return@runCatching null
                }
                if (conn.contentLengthLong > MAX_ICON_BYTES) {
                    return@runCatching null
                }
                val bytes = conn.inputStream.use { PluginAssetFetcher.readBoundedBytes(it, MAX_ICON_BYTES) }
                if (bytes == null || bytes.isEmpty()) {
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
