package pochita.sideveil.host.hud

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.text.TextPaint
import android.text.TextUtils
import android.view.View
import androidx.core.graphics.createBitmap
import pochita.sideveil.host.NativeHudRenderer
import pochita.sideveil.protocol.HudTextAlign
import pochita.sideveil.protocol.HudTextOverlay
import pochita.sideveil.protocol.NativeHudSceneHeader
import pochita.sideveil.protocol.SideVeilHudDefaults
import kotlin.math.roundToInt

/**
 * Hybrid `vello_cpu` NEON + Android HWUI view for zero-WebView native HUD overlays.
 * Double-buffers reusable `ARGB_8888` Bitmaps on a dedicated single-thread worker for vector
 * paths/sparklines/gauges with frame coalescing, and composites crisp system CJK/Emoji/tabular
 * text (`tnum`) directly via HWUI [Canvas.drawText] on the main thread.
 */
internal class NativeHudView(
    context: Context,
) : View(context) {
    private data class PendingHudFrame(
        val rawSceneJson: String,
        val header: NativeHudSceneHeader,
        val density: Float,
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private val textPaint =
        TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            isLinearText = true
        }
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    private var renderThread: HandlerThread? = null
    private var renderHandler: Handler? = null
    private var frontBitmap: Bitmap? = null
    private var backBitmap: Bitmap? = null
    private var pendingFrame: PendingHudFrame? = null
    private var renderInFlight = false
    private var generation = 0L
    private var currentTexts: List<HudTextOverlay> = emptyList()
    private var currentDensity: Float = 1f

    fun updateScene(
        rawSceneJson: String,
        header: NativeHudSceneHeader,
        density: Float,
    ) {
        pendingFrame = PendingHudFrame(rawSceneJson, header, density.coerceIn(0.5f, 8f))
        if (!renderInFlight) {
            dispatchNextRender()
        }
    }

    fun release() {
        generation++
        pendingFrame = null
        frontBitmap?.recycle()
        backBitmap?.recycle()
        frontBitmap = null
        backBitmap = null
        currentTexts = emptyList()
        renderHandler?.post { NativeHudRenderer.resetCache() }
    }

    fun shutdown() {
        release()
        val thread = renderThread
        renderThread = null
        renderHandler = null
        thread?.quitSafely()
    }

    private fun ensureRenderHandler(): Handler {
        renderHandler?.let { return it }
        val thread =
            HandlerThread("SideVeilHudRender", Process.THREAD_PRIORITY_DISPLAY).apply {
                start()
            }
        renderThread = thread
        return Handler(thread.looper).also { renderHandler = it }
    }

    private fun dispatchNextRender() {
        val frame = pendingFrame ?: return
        pendingFrame = null
        val targetW = (frame.header.widthDp.coerceIn(48f, 420f) * frame.density).roundToInt().coerceIn(32, 2048)
        val targetH = (frame.header.heightDp.coerceIn(28f, 320f) * frame.density).roundToInt().coerceIn(24, 2048)

        val dst = acquireBackBuffer(targetW, targetH)
        renderInFlight = true
        val capturedGen = generation
        val worker = ensureRenderHandler()
        val posted =
            worker.post {
                val rendered = NativeHudRenderer.renderInto(frame.rawSceneJson, dst, frame.density)
                mainHandler.post { onRenderComplete(capturedGen, frame, dst, rendered) }
            }
        if (!posted) {
            onRenderComplete(capturedGen, frame, dst, rendered = false)
        }
    }

    private fun onRenderComplete(
        capturedGen: Long,
        frame: PendingHudFrame,
        dst: Bitmap,
        rendered: Boolean,
    ) {
        renderInFlight = false
        if (capturedGen != generation) {
            dst.recycle()
            return
        }
        if (rendered) {
            val prevFront = frontBitmap
            frontBitmap = dst
            backBitmap = prevFront
        } else {
            backBitmap = dst
        }
        currentDensity = frame.density
        currentTexts = frame.header.texts.take(SideVeilHudDefaults.MAX_TEXTS.toInt())
        invalidate()
        if (pendingFrame != null) {
            dispatchNextRender()
        }
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
            val boundedText = overlay.text.take(MAX_TEXT_CHARS)
            val drawnText =
                overlay.maxWidthDp?.takeIf { it > 4f }?.let { maxDp ->
                    TextUtils.ellipsize(boundedText, textPaint, maxDp * density, TextUtils.TruncateAt.END).toString()
                } ?: boundedText
            canvas.drawText(drawnText, overlay.x * density, overlay.y * density, textPaint)
        }
    }

    private companion object {
        const val MAX_TEXT_CHARS = 128
    }

    private fun acquireBackBuffer(
        widthPx: Int,
        heightPx: Int,
    ): Bitmap {
        val existing = backBitmap
        backBitmap = null
        val reusable = existing != null && !existing.isRecycled
        if (reusable && existing.width == widthPx && existing.height == heightPx) {
            return existing
        }
        existing?.recycle()
        return createBitmap(widthPx, heightPx)
    }
}
