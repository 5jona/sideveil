package pochita.sideveil.host

import androidx.dynamicanimation.animation.FloatValueHolder
import androidx.dynamicanimation.animation.SpringAnimation

/**
 * Normalized progress (`0 = closed`, `1 = open`, `> 1 = overscroll`) spring controller
 * decoupled from Android Dialog / WindowManager lifecycle concerns.
 */
class PanelSpringController {
    private var activeSpring: SpringAnimation? = null

    var currentProgress: Float = 0f
        private set

    fun snapTo(progress: Float) {
        cancel()
        currentProgress = progress.coerceAtLeast(0f)
    }

    fun updateDrag(progress: Float): Float {
        val clamped = progress.coerceAtLeast(0f)
        currentProgress = clamped
        return clamped
    }

    fun animateTo(
        startProgress: Float,
        endProgress: Float,
        startVelocityPerSec: Float,
        opening: Boolean,
        onUpdate: (progress: Float, velocityPerSec: Float) -> Unit,
        onSettled: () -> Unit,
    ) {
        cancel()
        val safeStart = startProgress.coerceAtLeast(0f)
        val safeEnd = endProgress.coerceAtLeast(0f)
        currentProgress = safeStart
        val holder = FloatValueHolder(safeStart * SPRING_SCALE)
        activeSpring =
            SpringAnimation(holder, safeEnd * SPRING_SCALE).apply {
                setStartVelocity(startVelocityPerSec * SPRING_SCALE)
                spring.stiffness = if (opening) OPEN_STIFFNESS else CLOSE_STIFFNESS
                spring.dampingRatio = if (opening) OPEN_DAMPING else CLOSE_DAMPING
                addUpdateListener { _, value, velocity ->
                    val normalized = (value / SPRING_SCALE).coerceAtLeast(0f)
                    currentProgress = normalized
                    onUpdate(normalized, velocity / SPRING_SCALE)
                }
                addEndListener { _, canceled, _, _ ->
                    activeSpring = null
                    if (!canceled) {
                        currentProgress = safeEnd
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
        private const val SPRING_SCALE = 1000f
        private const val OPEN_STIFFNESS = 380f
        private const val OPEN_DAMPING = 0.92f
        private const val CLOSE_STIFFNESS = 540f
        private const val CLOSE_DAMPING = 1.0f

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
            val curved = PanelMotionChoreographer.applyOverscrollCurve(progress)
            return hiddenX + (openX - hiddenX) * curved
        }
    }
}
