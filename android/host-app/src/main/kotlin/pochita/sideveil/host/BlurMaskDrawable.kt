package pochita.sideveil.host

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Outline
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable

/**
 * Zero-draw immutable outline provider bound to [android.view.Window.setBackgroundDrawable].
 *
 * Supplies an alpha-zero round-rect outline for SurfaceFlinger's cross-window blur region
 * without drawing any pixels on the window DecorView canvas, separating window blur masking
 * from [GlassBackdropDrawable] optical rendering on the content container.
 */
class BlurMaskDrawable(
    val cornerRadiusPx: Float,
) : Drawable() {
    override fun draw(canvas: Canvas) = Unit

    override fun getOutline(outline: Outline) {
        outline.setRoundRect(bounds, cornerRadiusPx.coerceAtLeast(0f))
        outline.alpha = 0f
    }

    override fun setAlpha(alpha: Int) = Unit

    override fun setColorFilter(colorFilter: ColorFilter?) = Unit

    @Suppress("OVERRIDE_DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
