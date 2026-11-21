package pochita.sideveil.host

import android.app.WallpaperColors
import android.app.WallpaperManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import pochita.sideveil.protocol.HostDynamicColorsPayload

/**
 * Extracts Android 16+ Material You dynamic accent seed (`system_accent1_500`) and
 * wallpaper luminance hints (`WallpaperColors`), notifying [HostService] on changes so
 * `sideveild` synthesizes unified `Oklch` glass & WebView CSS tokens.
 */
internal class DynamicThemeExtractor(
    private val context: Context,
    private val onColorsChanged: (HostDynamicColorsPayload) -> Unit,
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var wallpaperManager: WallpaperManager? = null
    private var listenerRegistered = false
    private var lastIsDark = true
    private var lastReported: HostDynamicColorsPayload? = null

    private val wallpaperListener =
        WallpaperManager.OnColorsChangedListener { colors, which ->
            if ((which and WallpaperManager.FLAG_SYSTEM) != 0) {
                emitSnapshot(isDark = lastIsDark, overrideColors = colors, force = false)
            }
        }

    fun start(isDark: Boolean) {
        lastIsDark = isDark
        val wm = runCatching { WallpaperManager.getInstance(context) }.getOrNull()
        wallpaperManager = wm
        if (wm != null && !listenerRegistered) {
            runCatching {
                wm.addOnColorsChangedListener(wallpaperListener, mainHandler)
                listenerRegistered = true
            }.onFailure { err ->
                Log.d(TAG, "Wallpaper color listener unavailable: ${err.message}")
            }
        }
        emitSnapshot(isDark = isDark, overrideColors = null, force = true)
    }

    fun refresh(
        isDark: Boolean,
        force: Boolean = false,
    ) {
        lastIsDark = isDark
        emitSnapshot(isDark = isDark, overrideColors = null, force = force)
    }

    fun stop() {
        if (listenerRegistered) {
            runCatching { wallpaperManager?.removeOnColorsChangedListener(wallpaperListener) }
            listenerRegistered = false
        }
    }

    private fun emitSnapshot(
        isDark: Boolean,
        overrideColors: WallpaperColors?,
        force: Boolean,
    ) {
        val seedInt = resolveAccentSeed(overrideColors)
        val lumaHint = resolveLumaHint(overrideColors, isDark)
        val payload =
            HostDynamicColorsPayload(
                seedArgb = seedInt.toLong() and 0xFFFF_FFFFL,
                lumaHint = lumaHint,
                isDark = isDark,
            )
        if (force || payload != lastReported) {
            lastReported = payload
            onColorsChanged(payload)
        }
    }

    private fun resolveAccentSeed(overrideColors: WallpaperColors?): Int {
        val sysAccent =
            runCatching { context.getColor(android.R.color.system_accent1_500) }
                .getOrNull()
        if (sysAccent != null && (sysAccent ushr 24) > 0) {
            return sysAccent
        }
        val wpColors =
            overrideColors ?: runCatching {
                wallpaperManager?.getWallpaperColors(WallpaperManager.FLAG_SYSTEM)
            }.getOrNull()
        return wpColors?.primaryColor?.toArgb() ?: DEFAULT_SEED_ARGB
    }

    private fun resolveLumaHint(
        overrideColors: WallpaperColors?,
        isDark: Boolean,
    ): Float {
        val wpColors =
            overrideColors ?: runCatching {
                wallpaperManager?.getWallpaperColors(WallpaperManager.FLAG_SYSTEM)
            }.getOrNull()
        if (wpColors != null) {
            val hints = wpColors.colorHints
            val brightWallpaper = (hints and WallpaperColors.HINT_SUPPORTS_DARK_TEXT) != 0
            return if (brightWallpaper) 0.82f else 0.28f
        }
        return if (isDark) 0.35f else 0.72f
    }

    companion object {
        private const val TAG = "SideVeilDynamicTheme"
        private const val DEFAULT_SEED_ARGB = 0xFF38BDF8.toInt()
    }
}
