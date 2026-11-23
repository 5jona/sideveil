package pochita.sideveil.host

import android.graphics.Bitmap
import android.util.Log

/**
 * JNI bridge to `libsideveil_hud.so` (`sideveil-hud-jni` + `sideveil-gfx` `vello_cpu` NEON).
 * Rasterizes declarative `NativeHudScene` JSON directly into locked Android `ARGB_8888` Bitmaps.
 *
 * Kept at root package `pochita.sideveil.host` to match exported JNI symbol names
 * (`Java_pochita_sideveil_host_NativeHudRenderer_*`).
 */
internal object NativeHudRenderer {
    private const val TAG = "SideVeilNativeHudJni"

    /** Whether `libsideveil_hud.so` loaded successfully in the current process. */
    val isAvailable: Boolean =
        runCatching {
            System.loadLibrary("sideveil_hud")
            true
        }.onFailure { err ->
            Log.w(TAG, "libsideveil_hud.so unavailable: ${err.message}")
        }.getOrDefault(false)

    /** Rasterizes [sceneJson] into [bitmap] at [density] via `vello_cpu`. */
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

    /** Resets thread-local `vello_cpu` renderer scratch buffers in native memory. */
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
