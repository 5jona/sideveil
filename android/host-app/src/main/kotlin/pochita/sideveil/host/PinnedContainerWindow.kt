package pochita.sideveil.host

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import pochita.sideveil.protocol.GlassTintOverride
import pochita.sideveil.protocol.PanelConfig
import pochita.sideveil.protocol.PluginSurfaceBackdrop
import kotlin.math.roundToInt

data class PinnedBoundsSpec(
    val widthDp: Int? = null,
    val heightDp: Int? = null,
    val xPercent: Float? = null,
    val yPercent: Float? = null,
    val clickThrough: Boolean? = null,
)

/**
 * Independent draggable floating overlay container hosting plugins in
 * `PluginPresentationMode.PINNED` mode. Supports native glass or transparent backdrop,
 * edge-clamped dragging, click-through lock with a dedicated unlock chip, and unpinning back
 * to the main sidebar drawer.
 */
@SuppressLint("RtlHardcoded", "ClickableViewAccessibility")
internal class PinnedContainerWindow(
    private val context: Context,
    private val windowManager: WindowManager,
    private val onUnpinToDrawer: () -> Unit,
    private val onDismissPinned: () -> Unit,
) {
    private val density: Float
        get() =
            context.resources.displayMetrics.density
                .coerceAtLeast(1f)
    private val themedContext = ContextThemeWrapper(context, R.style.Theme_Sideveil_OverlayDialog)
    private val backdropDrawable = GlassBackdropDrawable(density)

    private val rootContainer =
        LinearLayout(themedContext).apply {
            orientation = LinearLayout.VERTICAL
            background = backdropDrawable
            clipToOutline = true
            outlineProvider = GlassBackdropDrawable.createContentOutlineProvider { cornerPx() }
        }
    private val headerBar = LinearLayout(themedContext)
    private val webSlot = FrameLayout(themedContext)
    private val unlockChip = FrameLayout(themedContext)

    private val mainLp =
        WindowManager
            .LayoutParams(
                dp(260f),
                dp(220f),
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                INTERACTIVE_FLAGS,
                PixelFormat.TRANSLUCENT,
            ).apply {
                title = "SideveilPinnedPlugin"
                gravity = Gravity.TOP or Gravity.LEFT
                x = dp(16f)
                y = dp(96f)
                OverlayWindowFlags.disableMoveAnimation(this)
            }

    private val unlockLp =
        WindowManager
            .LayoutParams(
                dp(32f),
                dp(32f),
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                INTERACTIVE_FLAGS,
                PixelFormat.TRANSLUCENT,
            ).apply {
                title = "SideveilPinnedUnlockChip"
                gravity = Gravity.TOP or Gravity.LEFT
                OverlayWindowFlags.disableMoveAnimation(this)
            }

    private var attached = false
    private var chipAttached = false
    private var clickThrough = false
    private var panelConfig = PanelConfig()
    private var pluginBackdrop = PluginSurfaceBackdrop.NATIVE_GLASS
    private var themeId = "coloros-glass"
    private var isDark = true
    private var dynamicTint: GlassTintOverride? = null
    private var dragStartRawX = 0f
    private var dragStartRawY = 0f
    private var dragStartWinX = 0
    private var dragStartWinY = 0

    val isVisible: Boolean
        get() = attached

    init {
        buildHeaderBar()
        buildUnlockChip()
        rootContainer.addView(
            headerBar,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(26f)),
        )
        rootContainer.addView(
            webSlot,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
        )
    }

    fun show(
        webView: WebView,
        backdrop: PluginSurfaceBackdrop,
        bounds: PinnedBoundsSpec? = null,
    ) {
        pluginBackdrop = backdrop
        (webView.parent as? ViewGroup)?.removeView(webView)
        webSlot.removeAllViews()
        webSlot.addView(
            webView,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
        webView.alpha = 1f

        bounds?.let { applyBounds(it, updateLayout = false) }
        clampWindowPosition()

        if (!attached) {
            runCatching {
                windowManager.addView(rootContainer, mainLp)
                attached = true
            }.onFailure { err ->
                Log.w(TAG, "Failed to attach PinnedContainerWindow", err)
            }
        }
        applyVisualStyle()
        syncWindowLayout()
    }

    fun updateBounds(bounds: PinnedBoundsSpec) {
        applyBounds(bounds, updateLayout = attached)
    }

    fun updateStyle(
        config: PanelConfig,
        nextThemeId: String,
        dark: Boolean,
        tint: GlassTintOverride?,
    ) {
        panelConfig = config
        themeId = nextThemeId
        isDark = dark
        dynamicTint = tint
        backdropDrawable.updateDensity(density)
        applyVisualStyle()
    }

    fun hide() {
        detachUnlockChip()
        clickThrough = false
        webSlot.removeAllViews()
        if (attached) {
            runCatching { windowManager.removeViewImmediate(rootContainer) }
            attached = false
        }
    }

    private fun buildHeaderBar() {
        headerBar.orientation = LinearLayout.HORIZONTAL
        headerBar.gravity = Gravity.CENTER_VERTICAL
        headerBar.setPadding(dp(10f), 0, dp(8f), 0)

        val grip =
            View(themedContext).apply {
                background =
                    GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = dp(2f).toFloat()
                        setColor(Color.argb(120, 226, 232, 240))
                    }
            }
        val gripWrap =
            FrameLayout(themedContext).apply {
                addView(grip, FrameLayout.LayoutParams(dp(34f), dp(4f), Gravity.CENTER))
                setOnTouchListener { v, ev ->
                    if (ev.actionMasked == MotionEvent.ACTION_UP) v.performClick()
                    handleHeaderDrag(ev)
                }
            }
        headerBar.addView(gripWrap, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        headerBar.addView(createHeaderAction("透", "点击穿透") { setClickThrough(true) })
        headerBar.addView(createHeaderAction("归", "返回侧边栏") { onUnpinToDrawer() })
        headerBar.addView(createHeaderAction("×", "关闭浮窗") { onDismissPinned() })
    }

    private fun createHeaderAction(
        glyph: String,
        desc: String,
        onClick: () -> Unit,
    ): View =
        TextView(themedContext).apply {
            text = glyph
            contentDescription = desc
            gravity = Gravity.CENTER
            textSize = 11f
            setTextColor(Color.argb(220, 241, 245, 249))
            setPadding(dp(6f), 0, dp(6f), 0)
            setOnClickListener { onClick() }
        }

    private fun buildUnlockChip() {
        unlockChip.background =
            GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(215, 15, 23, 42))
                setStroke(dp(1.2f), Color.argb(210, 56, 189, 248))
            }
        val label =
            TextView(themedContext).apply {
                text = "解"
                contentDescription = "解除穿透"
                gravity = Gravity.CENTER
                textSize = 11f
                setTextColor(Color.WHITE)
            }
        unlockChip.addView(
            label,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
        unlockChip.setOnClickListener { setClickThrough(false) }
    }

    private fun handleHeaderDrag(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragStartRawX = event.rawX
                dragStartRawY = event.rawY
                dragStartWinX = mainLp.x
                dragStartWinY = mainLp.y
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                mainLp.x = dragStartWinX + (event.rawX - dragStartRawX).roundToInt()
                mainLp.y = dragStartWinY + (event.rawY - dragStartRawY).roundToInt()
                clampWindowPosition()
                syncWindowLayout()
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                clampWindowPosition()
                syncWindowLayout()
                return true
            }
        }
        return false
    }

    private fun setClickThrough(enabled: Boolean) {
        if (clickThrough == enabled) return
        clickThrough = enabled
        headerBar.visibility = if (enabled) View.GONE else View.VISIBLE
        mainLp.flags = if (enabled) CLICK_THROUGH_FLAGS else INTERACTIVE_FLAGS
        syncWindowLayout()
        if (enabled && attached) {
            attachUnlockChip()
        } else {
            detachUnlockChip()
        }
    }

    private fun attachUnlockChip() {
        unlockLp.x = (mainLp.x + mainLp.width - dp(24f)).coerceAtLeast(dp(8f))
        unlockLp.y = (mainLp.y - dp(8f)).coerceAtLeast(dp(24f))
        if (!chipAttached) {
            runCatching {
                windowManager.addView(unlockChip, unlockLp)
                chipAttached = true
            }
        } else {
            runCatching { windowManager.updateViewLayout(unlockChip, unlockLp) }
        }
    }

    private fun detachUnlockChip() {
        if (chipAttached) {
            runCatching { windowManager.removeViewImmediate(unlockChip) }
            chipAttached = false
        }
    }

    private fun applyBounds(
        bounds: PinnedBoundsSpec,
        updateLayout: Boolean,
    ) {
        val safe = PanelInsetsCoordinator.resolveBounds(context, windowManager)
        bounds.widthDp?.let { mainLp.width = dp(it.coerceIn(140, 420).toFloat()) }
        bounds.heightDp?.let { mainLp.height = dp(it.coerceIn(96, 520).toFloat()) }
        bounds.xPercent?.let {
            val pct = it.coerceIn(0f, 100f) / 100f
            mainLp.x = ((safe.screenWidthPx - mainLp.width) * pct).roundToInt()
        }
        bounds.yPercent?.let {
            val pct = it.coerceIn(0f, 100f) / 100f
            mainLp.y = ((safe.screenHeightPx - mainLp.height) * pct).roundToInt()
        }
        bounds.clickThrough?.let { setClickThrough(it) }
        clampWindowPosition()
        if (updateLayout) syncWindowLayout()
    }

    private fun clampWindowPosition() {
        val safe = PanelInsetsCoordinator.resolveBounds(context, windowManager)
        val maxX = (safe.screenWidthPx - mainLp.width).coerceAtLeast(0)
        val minY = safe.safeTopPx
        val maxY = (safe.screenHeightPx - safe.safeBottomPx - mainLp.height).coerceAtLeast(minY)
        mainLp.x = mainLp.x.coerceIn(0, maxX)
        mainLp.y = mainLp.y.coerceIn(minY, maxY)
    }

    private fun applyVisualStyle() {
        if (pluginBackdrop == PluginSurfaceBackdrop.TRANSPARENT) {
            rootContainer.background = null
            rootContainer.clipToOutline = false
            return
        }
        if (rootContainer.background !== backdropDrawable) {
            rootContainer.background = backdropDrawable
        }
        rootContainer.clipToOutline = true
        backdropDrawable.updateStyle(
            GlassBackdropStyle(
                themeId = themeId,
                isDark = isDark,
                blurActive = false,
                cornerRadiusPx = cornerPx(),
                dynamicOverride = dynamicTint,
            ),
        )
    }

    private fun syncWindowLayout() {
        if (attached) {
            runCatching { windowManager.updateViewLayout(rootContainer, mainLp) }
        }
        if (chipAttached) {
            attachUnlockChip()
        }
    }

    private fun cornerPx(): Float = dp(panelConfig.cornerRadiusDp.coerceIn(12, 24).toFloat()).toFloat()

    private fun dp(value: Float): Int = (value * density + 0.5f).toInt().coerceAtLeast(1)

    companion object {
        private const val TAG = "SideveilPinnedWindow"

        private const val INTERACTIVE_FLAGS =
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED

        private const val CLICK_THROUGH_FLAGS =
            INTERACTIVE_FLAGS or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
    }
}
