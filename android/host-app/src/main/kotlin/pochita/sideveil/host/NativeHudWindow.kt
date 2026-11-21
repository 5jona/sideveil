package pochita.sideveil.host

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
import android.graphics.PixelFormat
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import pochita.sideveil.protocol.GlassTintOverride
import pochita.sideveil.protocol.HudBackdropKind
import pochita.sideveil.protocol.NativeHudSceneHeader
import pochita.sideveil.protocol.PanelConfig
import kotlin.math.roundToInt

/**
 * Zero-WebView `TYPE_APPLICATION_OVERLAY` Dialog window hosting [NativeHudView] on top of
 * optional SurfaceFlinger cross-window blur + [GlassBackdropDrawable].
 * Supports native display refresh-rate `vello_cpu` NEON vector updates, direct dragging, and
 * `clickThrough` mode without spawning any Chromium renderer process.
 */
@SuppressLint("RtlHardcoded", "ClickableViewAccessibility")
internal class NativeHudWindow(
    private val context: Context,
    private val windowManager: WindowManager,
    private val onActivePluginChanged: (String?) -> Unit = {},
) {
    private val density: Float
        get() =
            context.resources.displayMetrics.density
                .coerceAtLeast(1f)
    private val themedContext = ContextThemeWrapper(context, R.style.Theme_SideVeil_OverlayDialog)
    private val backdropDrawable = GlassBackdropDrawable(density)
    private val hudView = NativeHudView(themedContext)
    private val container =
        FrameLayout(themedContext).apply {
            background = backdropDrawable
            clipToOutline = true
            outlineProvider = GlassBackdropDrawable.createContentOutlineProvider { cornerPx() }
            addView(
                hudView,
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
            )
        }

    private val dialog = Dialog(themedContext, R.style.Theme_SideVeil_OverlayDialog)

    private var attached = false
    private var visible = false
    var activePluginId: String? = null
        private set
    private var currentHeader = NativeHudSceneHeader()
    private var panelConfig = PanelConfig()
    private var themeId = "coloros-glass"
    private var isDark = true
    private var systemBlurSupported = runCatching { windowManager.isCrossWindowBlurEnabled }.getOrDefault(false)
    private var dynamicTint: GlassTintOverride? = null
    private var lastAppliedBlur = -1
    private var lastCornerPx = -1f
    private var userDragged = false
    private var dragStartRawX = 0f
    private var dragStartRawY = 0f
    private var dragStartWinX = 0
    private var dragStartWinY = 0

    init {
        dialog.setCanceledOnTouchOutside(false)
        dialog.setCancelable(false)
        dialog.setContentView(
            container,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
        container.setOnTouchListener { v, ev ->
            if (ev.actionMasked == MotionEvent.ACTION_UP) v.performClick()
            handleDrag(ev)
        }

        val window = dialog.window ?: error("Native HUD dialog window unavailable")
        window.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        window.setFormat(PixelFormat.TRANSLUCENT)
        window.decorView.setPadding(0, 0, 0, 0)
        window.setBackgroundDrawable(BlurMaskDrawable(cornerPx()))
        lastCornerPx = cornerPx()

        val lp = window.attributes
        lp.title = "SideVeilNativeHud"
        lp.gravity = Gravity.TOP or Gravity.LEFT
        lp.width = 1
        lp.height = 1
        lp.flags = INVISIBLE_FLAGS
        OverlayWindowFlags.disableMoveAnimation(lp)
        window.attributes = lp
    }

    fun attachWarm() {
        if (attached) return
        runCatching {
            dialog.show()
            attached = true
            collapseHidden()
        }.onFailure { err ->
            Log.w(TAG, "Failed to attach NativeHudWindow", err)
        }
    }

    fun updateScene(
        rawSceneJson: String,
        header: NativeHudSceneHeader,
    ) {
        if (!attached) attachWarm()
        if (!attached) return
        if (activePluginId != header.pluginId) {
            activePluginId = header.pluginId
            userDragged = false
            onActivePluginChanged(activePluginId)
        }
        currentHeader = header
        visible = true
        hudView.updateScene(rawSceneJson, header, density)
        applyLayoutAndBackdrop()
    }

    fun dismiss(pluginId: String? = null) {
        if (pluginId != null && activePluginId != null && pluginId != activePluginId) {
            return
        }
        val prevActive = activePluginId
        visible = false
        activePluginId = null
        userDragged = false
        collapseHidden()
        hudView.release()
        if (prevActive != null) {
            onActivePluginChanged(null)
        }
    }

    fun updateStyle(
        config: PanelConfig,
        nextThemeId: String,
        dark: Boolean,
        blurSupported: Boolean,
        tint: GlassTintOverride?,
    ) {
        panelConfig = config
        themeId = nextThemeId
        isDark = dark
        systemBlurSupported = blurSupported
        dynamicTint = tint
        backdropDrawable.updateDensity(density)
        if (visible) {
            applyLayoutAndBackdrop()
        }
    }

    fun detach() {
        visible = false
        hudView.release()
        if (attached) {
            runCatching { dialog.dismiss() }
            attached = false
        }
    }

    private fun handleDrag(event: MotionEvent): Boolean {
        if (!visible || currentHeader.clickThrough) return false
        val window = dialog.window ?: return false
        val lp = window.attributes
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragStartRawX = event.rawX
                dragStartRawY = event.rawY
                dragStartWinX = lp.x
                dragStartWinY = lp.y
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                userDragged = true
                val safe = PanelInsetsCoordinator.resolveBounds(context, windowManager)
                val maxX = (safe.screenWidthPx - lp.width).coerceAtLeast(0)
                val maxY = (safe.screenHeightPx - safe.safeBottomPx - lp.height).coerceAtLeast(safe.safeTopPx)
                lp.x = (dragStartWinX + (event.rawX - dragStartRawX).roundToInt()).coerceIn(0, maxX)
                lp.y = (dragStartWinY + (event.rawY - dragStartRawY).roundToInt()).coerceIn(safe.safeTopPx, maxY)
                window.attributes = lp
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                return true
            }
        }
        return false
    }

    private fun applyLayoutAndBackdrop() {
        val window = dialog.window ?: return
        val safe = PanelInsetsCoordinator.resolveBounds(context, windowManager)
        val widthPx = dp(currentHeader.widthDp.coerceIn(48f, 420f))
        val heightPx = dp(currentHeader.heightDp.coerceIn(28f, 320f))
        val lp = window.attributes
        val nextFlags = if (currentHeader.clickThrough) CLICK_THROUGH_FLAGS else INTERACTIVE_FLAGS
        val maxX = (safe.screenWidthPx - widthPx).coerceAtLeast(0)
        val maxY = (safe.screenHeightPx - safe.safeBottomPx - heightPx).coerceAtLeast(safe.safeTopPx)
        val targetX =
            if (userDragged) {
                lp.x.coerceIn(0, maxX)
            } else {
                (maxX * (currentHeader.anchorXPercent.coerceIn(0f, 100f) / 100f)).roundToInt()
            }
        val targetY =
            if (userDragged) {
                lp.y.coerceIn(safe.safeTopPx, maxY)
            } else {
                (safe.safeTopPx + (maxY - safe.safeTopPx) * (currentHeader.anchorYPercent.coerceIn(0f, 100f) / 100f)).roundToInt()
            }

        val geometryChanged = lp.width != widthPx || lp.height != heightPx || lp.x != targetX || lp.y != targetY
        val flagsOrRateChanged = lp.flags != nextFlags || lp.preferredRefreshRate <= 0f
        if (geometryChanged || flagsOrRateChanged) {
            lp.gravity = Gravity.TOP or Gravity.LEFT
            lp.width = widthPx
            lp.height = heightPx
            lp.x = targetX
            lp.y = targetY
            lp.flags = nextFlags
            OverlayWindowFlags.applyPreferredMaxRefreshRate(context, lp, window.decorView.display)
            OverlayWindowFlags.disableMoveAnimation(lp)
            window.attributes = lp
        }
        window.decorView.visibility = View.VISIBLE
        OverlayWindowFlags.applyHighFrameRate(hudView, context, window.decorView.display)

        if (currentHeader.backdrop == HudBackdropKind.TRANSPARENT) {
            container.background = null
            container.clipToOutline = false
            applyBlur(window, 0)
            return
        }

        if (container.background !== backdropDrawable) {
            container.background = backdropDrawable
        }
        container.clipToOutline = true
        val corner = cornerPx()
        if (lastCornerPx != corner) {
            lastCornerPx = corner
            window.setBackgroundDrawable(BlurMaskDrawable(corner))
            container.invalidateOutline()
        }
        val blurAllowed =
            currentHeader.backdrop == HudBackdropKind.NATIVE_GLASS &&
                systemBlurSupported &&
                panelConfig.nativeBlurEnabled
        val radius = if (blurAllowed) panelConfig.blurRadiusPx.coerceIn(0, 180) else 0
        backdropDrawable.updateStyle(
            GlassBackdropStyle(
                themeId = themeId,
                isDark = isDark,
                blurActive = radius > 0,
                cornerRadiusPx = corner,
                dynamicOverride = dynamicTint,
            ),
        )
        applyBlur(window, radius)
    }

    private fun collapseHidden() {
        val window = dialog.window ?: return
        applyBlur(window, 0)
        OverlayWindowFlags.clearHighFrameRate(hudView)
        window.decorView.visibility = View.INVISIBLE
        val lp = window.attributes
        lp.gravity = Gravity.TOP or Gravity.LEFT
        lp.width = 1
        lp.height = 1
        lp.x = 0
        lp.y = 0
        lp.flags = INVISIBLE_FLAGS
        OverlayWindowFlags.clearPreferredRefreshRate(lp)
        window.attributes = lp
    }

    private fun applyBlur(
        window: android.view.Window,
        radius: Int,
    ) {
        if (lastAppliedBlur != radius) {
            lastAppliedBlur = radius
            window.setBackgroundBlurRadius(radius)
        }
    }

    private fun cornerPx(): Float = dp(currentHeader.cornerRadiusDp.coerceIn(0f, 40f)).toFloat()

    private fun dp(value: Float): Int = OverlayWindowFlags.dp(density, value)

    companion object {
        private const val TAG = "SideVeilNativeHud"

        private const val INVISIBLE_FLAGS =
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED

        private const val INTERACTIVE_FLAGS =
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED

        private const val CLICK_THROUGH_FLAGS =
            INTERACTIVE_FLAGS or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
    }
}
