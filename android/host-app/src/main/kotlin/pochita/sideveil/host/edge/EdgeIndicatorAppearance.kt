package pochita.sideveil.host.edge

import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.graphics.toColorInt
import pochita.sideveil.host.overlay.OverlayWindowFlags
import pochita.sideveil.protocol.EdgeConfig
import pochita.sideveil.protocol.EdgeSide
import pochita.sideveil.protocol.EdgeStatusPayload

/**
 * Immutable snapshot of `WindowManager.LayoutParams` fields relevant to `EdgeIndicatorWindow`
 * so `WindowManager.updateViewLayout` is only called when window geometry or flags actually change.
 */
internal data class EdgeWindowLayoutSnapshot(
    val gravity: Int,
    val width: Int,
    val height: Int,
    val x: Int,
    val y: Int,
    val flags: Int,
    val alpha: Float,
) {
    companion object {
        fun from(lp: WindowManager.LayoutParams): EdgeWindowLayoutSnapshot =
            EdgeWindowLayoutSnapshot(
                gravity = lp.gravity,
                width = lp.width,
                height = lp.height,
                x = lp.x,
                y = lp.y,
                flags = lp.flags,
                alpha = lp.alpha,
            )
    }
}

/**
 * Pure visual styling, badge layout, and idle-dim transform helpers for [EdgeIndicatorWindow].
 */
@SuppressLint("RtlHardcoded")
internal class EdgeIndicatorAppearance(
    private val densityProvider: () -> Float,
    private val rootView: FrameLayout,
    private val pillView: View,
    private val badgeView: TextView,
    private val pillDrawable: GradientDrawable,
) {
    private val badgeDrawable = GradientDrawable().apply { shape = GradientDrawable.RECTANGLE }

    fun applyLayoutAndVisuals(
        layoutParams: WindowManager.LayoutParams,
        screenHeightPx: Int,
        edge: EdgeConfig,
        repositionActive: Boolean,
        repositionSide: EdgeSide,
        repositionTopPercent: Int,
        fullscreenBarsHidden: Boolean,
        idleDimmed: Boolean,
        darkSurface: Boolean,
        dragPulseProgress: Float,
        activeEdgeStatus: EdgeStatusPayload?,
    ) {
        val activeSide = if (repositionActive) repositionSide else edge.side
        val activeTopPercent = if (repositionActive) repositionTopPercent else edge.topPercent
        val heightPx = dp(edge.heightDp.toFloat()).coerceAtMost(screenHeightPx)
        val topPx =
            ((activeTopPercent / 100f) * screenHeightPx)
                .toInt()
                .coerceIn(0, (screenHeightPx - heightPx).coerceAtLeast(0))
        val badgeText =
            if (repositionActive) {
                null
            } else {
                activeEdgeStatus
                    ?.badgeText
                    ?.trim()
                    ?.take(4)
                    ?.takeIf { it.isNotEmpty() }
            }
        val horiz = if (activeSide == EdgeSide.LEFT) Gravity.LEFT else Gravity.RIGHT
        layoutParams.gravity = Gravity.TOP or horiz
        layoutParams.width =
            dp(
                when {
                    badgeText != null -> 30f
                    repositionActive -> 10f
                    else -> 8f
                },
            )
        layoutParams.height = heightPx
        layoutParams.x = (edge.edgeOffsetDp * densityProvider() + 0.5f).toInt().coerceAtLeast(0)
        layoutParams.y = topPx
        OverlayWindowFlags.applyTouchSafeAlpha(layoutParams)

        applyBadgeOrPill(badgeText, horiz, heightPx, repositionActive, activeEdgeStatus)
        applyPillColors(repositionActive, darkSurface, activeEdgeStatus)
        applyTransformAndAlpha(activeSide, edge, fullscreenBarsHidden, idleDimmed, repositionActive, dragPulseProgress)
    }

    fun applyBadgeOrPill(
        badgeText: String?,
        gravityHorizontal: Int,
        heightPx: Int,
        repositionActive: Boolean,
        activeEdgeStatus: EdgeStatusPayload?,
    ) {
        val pillParams = pillView.layoutParams as FrameLayout.LayoutParams
        pillParams.width = dp(if (repositionActive) 6f else 4f)
        val progressFactor = activeEdgeStatus?.progress?.coerceIn(0.15f, 1f) ?: 1f
        pillParams.height = (heightPx * 0.64f * progressFactor).toInt().coerceAtLeast(dp(36f))
        pillParams.gravity = Gravity.CENTER_VERTICAL or gravityHorizontal
        pillView.layoutParams = pillParams
        if (badgeText != null) {
            badgeView.text = badgeText
            badgeView.visibility = View.VISIBLE
            (badgeView.layoutParams as FrameLayout.LayoutParams).gravity = Gravity.CENTER_VERTICAL or gravityHorizontal
            badgeDrawable.cornerRadius = dp(9f).toFloat()
            badgeDrawable.setColor(parseStatusAccent(activeEdgeStatus) ?: Color.argb(230, 37, 99, 235))
            if (badgeView.background !== badgeDrawable) {
                badgeView.background = badgeDrawable
            }
            pillView.visibility = View.GONE
        } else {
            badgeView.visibility = View.GONE
            pillView.visibility = View.VISIBLE
        }
    }

    fun applyTransformAndAlpha(
        activeSide: EdgeSide,
        edge: EdgeConfig,
        fullscreenBarsHidden: Boolean,
        idleDimmed: Boolean,
        repositionActive: Boolean,
        dragPulseProgress: Float,
    ) {
        val activeAlpha = computeActiveAlpha(edge, fullscreenBarsHidden, repositionActive)
        val dimmed = idleDimmed && !repositionActive
        rootView.alpha = if (dimmed) activeAlpha * IDLE_ALPHA_FACTOR else activeAlpha
        pillView.translationX = if (dimmed) tuckTranslationPx(activeSide) else 0f
        pillView.scaleX = if (repositionActive) 1.25f else 1f + dragPulseProgress * 0.45f
        pillView.scaleY = if (repositionActive) 1.04f else 1f + dragPulseProgress * 0.08f
    }

    fun applyPillColors(
        repositionActive: Boolean,
        darkSurface: Boolean,
        activeEdgeStatus: EdgeStatusPayload?,
    ) {
        val statusAccent = parseStatusAccent(activeEdgeStatus)
        val fillColor =
            when {
                repositionActive -> Color.argb(240, 56, 189, 248)
                statusAccent != null -> statusAccent
                darkSurface -> Color.argb(215, 240, 246, 255)
                else -> Color.argb(190, 24, 34, 48)
            }
        val strokeColor =
            when {
                repositionActive || statusAccent != null -> Color.argb(235, 255, 255, 255)
                darkSurface -> Color.argb(110, 96, 165, 250)
                else -> Color.argb(90, 255, 255, 255)
            }
        pillDrawable.cornerRadius = dp(4f).toFloat()
        pillDrawable.setColor(fillColor)
        pillDrawable.setStroke(dp(1f), strokeColor)
    }

    fun animateIdleState(
        dimmed: Boolean,
        edge: EdgeConfig,
        fullscreenBarsHidden: Boolean,
        repositionActive: Boolean,
    ) {
        val activeAlpha = computeActiveAlpha(edge, fullscreenBarsHidden, repositionActive)
        val targetAlpha = if (dimmed) (activeAlpha * IDLE_ALPHA_FACTOR).coerceAtLeast(0.12f) else activeAlpha
        val targetTx = if (dimmed) tuckTranslationPx(edge.side) else 0f
        rootView
            .animate()
            .alpha(targetAlpha)
            .setDuration(IDLE_ANIM_MS)
            .start()
        pillView
            .animate()
            .translationX(targetTx)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(IDLE_ANIM_MS)
            .start()
    }

    fun animateDragPulse(progress: Float) {
        if (progress > 0f) {
            pillView.animate().cancel()
            pillView.scaleX = 1f + progress * 0.45f
            pillView.scaleY = 1f + progress * 0.08f
        } else {
            pillView
                .animate()
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(140L)
                .start()
        }
    }

    fun animateFullscreenHintPulse() {
        rootView.animate().cancel()
        rootView.alpha = 1f
        pillView.animate().cancel()
        pillView
            .animate()
            .scaleX(1.65f)
            .scaleY(1.12f)
            .setDuration(140L)
            .withEndAction {
                pillView
                    .animate()
                    .scaleX(1.15f)
                    .scaleY(1.03f)
                    .setDuration(260L)
                    .start()
            }.start()
    }

    fun cancelAnimations() {
        rootView.animate().cancel()
        pillView.animate().cancel()
    }

    private fun parseStatusAccent(activeEdgeStatus: EdgeStatusPayload?): Int? {
        val raw = activeEdgeStatus?.accentColor?.trim().orEmpty()
        if (raw.isEmpty()) return null
        return runCatching { raw.toColorInt() }.getOrNull()
    }

    private fun computeActiveAlpha(
        edge: EdgeConfig,
        fullscreenBarsHidden: Boolean,
        repositionActive: Boolean,
    ): Float {
        if (repositionActive) return 1f
        val baseOpacity = edge.handleOpacity.coerceIn(0.1f, 1.0f)
        return if (edge.dimInFullscreen && fullscreenBarsHidden) {
            (baseOpacity * 0.35f).coerceAtLeast(0.15f)
        } else {
            baseOpacity
        }
    }

    private fun tuckTranslationPx(side: EdgeSide): Float {
        val shift = 1.6f * densityProvider()
        return if (side == EdgeSide.LEFT) -shift else shift
    }

    private fun dp(value: Float): Int = OverlayWindowFlags.dp(densityProvider(), value)

    companion object {
        private const val IDLE_ALPHA_FACTOR = 0.38f
        private const val IDLE_ANIM_MS = 280L
    }
}
