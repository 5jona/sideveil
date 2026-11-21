package pochita.sideveil.host

import android.graphics.Bitmap
import android.util.Log

/**
 * JNI bridge to `libsideveil_hud.so` (`sideveil-hud-jni` + `sideveil-gfx` `vello_cpu` NEON).
 * Rasterizes declarative `NativeHudScene` JSON directly into locked Android `ARGB_8888` Bitmaps.
 */
internal object NativeHudRenderer {
    private const val TAG = "SideveilNativeHudJni"

    val isAvailable: Boolean =
        runCatching {
            System.loadLibrary("sideveil_hud")
            true
        }.onFailure { err ->
            Log.w(TAG, "libsideveil_hud.so unavailable: ${err.message}")
        }.getOrDefault(false)

    fun renderInto(
        sceneJson: String,
        bitmap: Bitmap,
        density: Float,
    ): Boolean {
        if (!isAvailable) return false
        return runCatching {
            nativeRenderScene(sceneJson, bitmap, density)
        }.onFailure { err ->
            Log.w(TAG, "nativeRenderScene failed: ${err.message}")
        }.getOrDefault(false)
    }

    fun resetCache() {
        if (!isAvailable) return
        runCatching { nativeResetCache() }
    }

    @JvmStatic
    private external fun nativeRenderScene(
        sceneJson: String,
        bitmap: Bitmap,
        density: Float,
    ): Boolean

    @JvmStatic
    private external fun nativeResetCache()
}
