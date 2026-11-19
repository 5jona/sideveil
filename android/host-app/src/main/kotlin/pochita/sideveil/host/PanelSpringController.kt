package pochita.sideveil.host

import androidx.dynamicanimation.animation.FloatValueHolder
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import kotlin.math.ln

/**
 * Pure horizontal gesture-kinematics and spring-animation controller decoupled from
 * Android Dialog / WindowManager lifecycle concerns.
 */
class PanelSpringController {
    private var activeSpring: SpringAnimation? = null

    var currentX: Float = 0f
        private set

    fun snapTo(x: Float) {
        cancel()
        currentX = x
    }

    fun updateDrag(
        progress: Float,
        hiddenX: Float,
        openX: Float,
    ): Float {
        val nextX = computeDragPosition(progress = progress, hiddenX = hiddenX, openX = openX)
        currentX = nextX
        return nextX
    }

    fun animateTo(
        startX: Float,
        endX: Float,
        startVelocityPx: Float,
        onUpdate: (Float) -> Unit,
        onSettled: () -> Unit,
    ) {
        cancel()
        currentX = startX
        val holder = FloatValueHolder(startX)
        activeSpring =
            SpringAnimation(holder, endX).apply {
                setStartVelocity(startVelocityPx)
                spring.stiffness = SpringForce.STIFFNESS_MEDIUM
                spring.dampingRatio = SpringForce.DAMPING_RATIO_NO_BOUNCY
                addUpdateListener { _, value, _ ->
                    currentX = value
                    onUpdate(value)
                }
                addEndListener { _, canceled, _, _ ->
                    activeSpring = null
                    if (!canceled) {
                        currentX = endX
                        onSettled()
                    }
                }
                start()
            }
    }

    fun cancel() {
        activeSpring?.cancel()
        activeSpring = null
    }

    companion object {
        private const val OVERSCROLL_DAMPING = 0.16f

        /**
         * Maps raw edge-swipe progress `[0, +inf)` to horizontal window offset with
         * linear 1:1 tracking inside `[0, 1]` and smooth logarithmic rubber-band resistance
         * past full panel reveal.
         */
        fun computeDragPosition(
            progress: Float,
            hiddenX: Float,
            openX: Float,
        ): Float {
            val clamped =
                if (progress <= 1.0f) {
                    progress.coerceAtLeast(0f)
                } else {
                    1.0f + ln(1.0 + (progress - 1.0)).toFloat() * OVERSCROLL_DAMPING
                }
            return hiddenX + (openX - hiddenX) * clamped
        }
    }
}
