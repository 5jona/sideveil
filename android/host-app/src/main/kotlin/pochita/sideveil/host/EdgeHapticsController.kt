package pochita.sideveil.host

import android.content.Context
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Stepped tactile haptic feedback controller using Android 16–17 [VibrationEffect.Composition]
 * primitives with graceful predefined-effect fallback on devices without rich actuators.
 */
internal class EdgeHapticsController(
    context: Context,
) {
    private val vibrator: Vibrator? =
        runCatching {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        }.getOrNull()

    private val hasVibrator: Boolean = runCatching { vibrator?.hasVibrator() == true }.getOrDefault(false)

    private val supportsTick: Boolean =
        hasVibrator && isPrimitiveSupported(VibrationEffect.Composition.PRIMITIVE_TICK)
    private val supportsClick: Boolean =
        hasVibrator && isPrimitiveSupported(VibrationEffect.Composition.PRIMITIVE_CLICK)
    private val supportsThud: Boolean =
        hasVibrator && isPrimitiveSupported(VibrationEffect.Composition.PRIMITIVE_THUD)

    fun onPilfer() {
        playPrimitiveOrFallback(
            supported = supportsTick,
            primitive = VibrationEffect.Composition.PRIMITIVE_TICK,
            scale = 0.42f,
            fallbackEffect = VibrationEffect.EFFECT_TICK,
        )
    }

    fun onThresholdCrossed() {
        playPrimitiveOrFallback(
            supported = supportsClick,
            primitive = VibrationEffect.Composition.PRIMITIVE_CLICK,
            scale = 0.72f,
            fallbackEffect = VibrationEffect.EFFECT_CLICK,
        )
    }

    fun onRailSwitch() {
        playPrimitiveOrFallback(
            supported = supportsTick,
            primitive = VibrationEffect.Composition.PRIMITIVE_TICK,
            scale = 0.34f,
            fallbackEffect = VibrationEffect.EFFECT_TICK,
        )
    }

    fun onCancel() {
        playPrimitiveOrFallback(
            supported = supportsThud,
            primitive = VibrationEffect.Composition.PRIMITIVE_THUD,
            scale = 0.40f,
            fallbackEffect = VibrationEffect.EFFECT_TICK,
        )
    }

    fun onFullscreenHint() {
        if (!hasVibrator) return
        val effect =
            if (supportsTick) {
                VibrationEffect
                    .startComposition()
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, 0.32f)
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, 0.46f, 45)
                    .compose()
            } else {
                VibrationEffect.createPredefined(VibrationEffect.EFFECT_DOUBLE_CLICK)
            }
        vibrateSafely(effect)
    }

    fun onRepositionStart() {
        playPrimitiveOrFallback(
            supported = supportsClick,
            primitive = VibrationEffect.Composition.PRIMITIVE_CLICK,
            scale = 0.85f,
            fallbackEffect = VibrationEffect.EFFECT_HEAVY_CLICK,
        )
    }

    fun onRepositionFlip() {
        playPrimitiveOrFallback(
            supported = supportsTick,
            primitive = VibrationEffect.Composition.PRIMITIVE_TICK,
            scale = 0.58f,
            fallbackEffect = VibrationEffect.EFFECT_CLICK,
        )
    }

    fun onRepositionCommit() {
        playPrimitiveOrFallback(
            supported = supportsClick,
            primitive = VibrationEffect.Composition.PRIMITIVE_CLICK,
            scale = 0.65f,
            fallbackEffect = VibrationEffect.EFFECT_CLICK,
        )
    }

    private fun playPrimitiveOrFallback(
        supported: Boolean,
        primitive: Int,
        scale: Float,
        fallbackEffect: Int,
    ) {
        if (!hasVibrator) return
        val effect =
            if (supported) {
                VibrationEffect.startComposition().addPrimitive(primitive, scale).compose()
            } else {
                VibrationEffect.createPredefined(fallbackEffect)
            }
        vibrateSafely(effect)
    }

    private fun isPrimitiveSupported(primitive: Int): Boolean =
        runCatching { vibrator?.areAllPrimitivesSupported(primitive) == true }.getOrDefault(false)

    private fun vibrateSafely(effect: VibrationEffect) {
        runCatching { vibrator?.vibrate(effect) }
    }
}
