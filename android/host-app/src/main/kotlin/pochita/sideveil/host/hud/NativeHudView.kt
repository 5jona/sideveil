package pochita.sideveil.host.hud

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import android.view.View
import androidx.core.graphics.createBitmap
import pochita.sideveil.host.NativeHudRenderer
import pochita.sideveil.protocol.HudTextAlign
import pochita.sideveil.protocol.HudTextOverlay
import pochita.sideveil.protocol.NativeHudSceneHeader
import kotlin.math.roundToInt

/**
 * Hybrid `vello_cpu` NEON + Android HWUI view for zero-WebView native HUD overlays.
 * Double-buffers reusable `ARGB_8888` Bitmaps for vector paths/sparklines/gauges and composites
 * crisp system CJK/Emoji/tabular text (`tnum`) directly via HWUI [Canvas.drawText].
 */
internal class NativeHudView(
    context: Context,
) : View(context) {
    private val textPaint =
        TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            isLinearText = true
        }
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    private var frontBitmap: Bitmap? = null
    private var backBitmap: Bitmap? = null
    private var currentTexts: List<HudTextOverlay> = emptyList()
    private var currentDensity: Float = 1f

    fun updateScene(
        rawSceneJson: String,
        header: NativeHudSceneHeader,
        density: Float,
    ): Boolean {
        val safeDensity = density.coerceIn(0.5f, 8f)
        currentDensity = safeDensity
        val targetW = (header.widthDp.coerceIn(48f, 420f) * safeDensity).roundToInt().coerceIn(32, 2048)
        val targetH = (header.heightDp.coerceIn(28f, 320f) * safeDensity).roundToInt().coerceIn(24, 2048)

        val dst = ensureBackBuffer(targetW, targetH)
        val rendered = NativeHudRenderer.renderInto(rawSceneJson, dst, safeDensity)
        if (rendered) {
            val prevFront = frontBitmap
            frontBitmap = dst
            backBitmap = prevFront
        }
        currentTexts = header.texts
        invalidate()
        return rendered
    }

    fun release() {
        frontBitmap?.recycle()
        backBitmap?.recycle()
        frontBitmap = null
        backBitmap = null
        currentTexts = emptyList()
        NativeHudRenderer.resetCache()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        frontBitmap?.takeIf { !it.isRecycled }?.let { bmp ->
            canvas.drawBitmap(bmp, 0f, 0f, bitmapPaint)
        }
        if (currentTexts.isEmpty()) return

        val density = currentDensity
        val fontScale = resources.configuration.fontScale.coerceIn(0.85f, 1.25f)
        val spToPx = density * fontScale
        for (overlay in currentTexts) {
            if (overlay.text.isEmpty()) continue
            textPaint.color = overlay.colorArgb.toInt()
            textPaint.textSize = overlay.sizeSp.coerceIn(6f, 48f) * spToPx
            textPaint.typeface = if (overlay.bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            textPaint.fontFeatureSettings = if (overlay.tabularNums) "tnum" else null
            textPaint.textAlign =
                when (overlay.align) {
                    HudTextAlign.START -> Paint.Align.LEFT
                    HudTextAlign.CENTER -> Paint.Align.CENTER
                    HudTextAlign.END -> Paint.Align.RIGHT
                }
            val drawnText =
                overlay.maxWidthDp?.takeIf { it > 4f }?.let { maxDp ->
                    TextUtils.ellipsize(overlay.text, textPaint, maxDp * density, TextUtils.TruncateAt.END).toString()
                } ?: overlay.text
            canvas.drawText(drawnText, overlay.x * density, overlay.y * density, textPaint)
        }
    }

    private fun ensureBackBuffer(
        widthPx: Int,
        heightPx: Int,
    ): Bitmap {
        val existing = backBitmap
        val reusable = existing != null && !existing.isRecycled
        if (reusable && existing.width == widthPx && existing.height == heightPx) {
            return existing
        }
        existing?.recycle()
        return createBitmap(widthPx, heightPx).also {
            backBitmap = it
        }
    }
}
