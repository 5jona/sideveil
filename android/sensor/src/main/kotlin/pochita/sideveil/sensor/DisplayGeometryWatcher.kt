package pochita.sideveil.sensor

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Handler
import android.util.DisplayMetrics
import android.view.Display
import pochita.sideveil.protocol.DisplayGeometry

/**
 * Watches default display resolution and density changes via [DisplayManager], querying
 * `DisplayManagerGlobal` with a safe [Display.getRealMetrics] fallback.
 */
internal class DisplayGeometryWatcher(
    private val systemContext: Context,
    private val handler: Handler,
    private val onDisplayChanged: (DisplayGeometry) -> Unit,
) {
    @Volatile
    var geometry: DisplayGeometry = DisplayGeometry()
        private set

    private var displayManager: DisplayManager? = null
    private var displayListener: DisplayManager.DisplayListener? = null

    fun start() {
        refresh(notify = false)
        val dm = systemContext.getSystemService(DisplayManager::class.java) ?: return
        displayManager = dm
        val dl =
            object : DisplayManager.DisplayListener {
                override fun onDisplayAdded(displayId: Int) = Unit

                override fun onDisplayRemoved(displayId: Int) = Unit

                override fun onDisplayChanged(displayId: Int) {
                    if (displayId == Display.DEFAULT_DISPLAY) {
                        refresh(notify = true)
                    }
                }
            }
        dm.registerDisplayListener(dl, handler)
        displayListener = dl
    }

    fun stop() {
        displayListener?.let { dl -> displayManager?.unregisterDisplayListener(dl) }
        displayListener = null
        displayManager = null
    }

    private fun refresh(notify: Boolean) {
        val updated = queryDisplayGeometry()
        if (updated != geometry) {
            geometry = updated
            SensorLog.info("Display geometry: ${updated.widthPx}x${updated.heightPx} density=${updated.density}")
            if (notify) {
                onDisplayChanged(updated)
            }
        }
    }

    private fun queryDisplayGeometry(): DisplayGeometry {
        val fromGlobal =
            runCatching {
                val dmgClass = Class.forName("android.hardware.display.DisplayManagerGlobal")
                val dmg = dmgClass.getMethod("getInstance").invoke(null)
                val info =
                    dmgClass
                        .getMethod("getDisplayInfo", Int::class.javaPrimitiveType)
                        .invoke(dmg, Display.DEFAULT_DISPLAY)
                if (info != null) {
                    val w = info.javaClass.getField("logicalWidth").getInt(info)
                    val h = info.javaClass.getField("logicalHeight").getInt(info)
                    val dpi = info.javaClass.getField("logicalDensityDpi").getInt(info)
                    if (w > 0 && h > 0 && dpi > 0) {
                        return@runCatching DisplayGeometry(w, h, dpi / 160f)
                    }
                }
                null
            }.getOrNull()
        if (fromGlobal != null) {
            return fromGlobal
        }

        val metrics = DisplayMetrics()
        val display =
            systemContext
                .getSystemService(DisplayManager::class.java)
                ?.getDisplay(Display.DEFAULT_DISPLAY)
        if (display != null) {
            @Suppress("DEPRECATION")
            display.getRealMetrics(metrics)
            if (metrics.widthPixels > 0 && metrics.heightPixels > 0) {
                return DisplayGeometry(metrics.widthPixels, metrics.heightPixels, metrics.density)
            }
        }
        return geometry
    }
}
