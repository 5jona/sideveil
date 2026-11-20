package pochita.sideveil.host

import android.view.WindowManager

/**
 * Reflection utilities for hidden `WindowManager.LayoutParams` private flags and
 * `HardwareRenderer.preload()` warm startup.
 */
internal object OverlayWindowFlags {
    private const val PRIVATE_FLAG_NO_MOVE_ANIMATION = 0x00000040

    private val privateFlagsField by lazy {
        runCatching {
            WindowManager.LayoutParams::class.java.getField("privateFlags")
        }.getOrNull()
    }

    fun disableMoveAnimation(lp: WindowManager.LayoutParams) {
        val field = privateFlagsField ?: return
        runCatching {
            field.setInt(lp, field.getInt(lp) or PRIVATE_FLAG_NO_MOVE_ANIMATION)
        }
    }

    fun preloadHardwareRenderer() {
        runCatching {
            Class.forName("android.graphics.HardwareRenderer").getMethod("preload").invoke(null)
        }
    }
}
