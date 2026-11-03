package com.blekeymouse.util

import kotlin.math.sqrt

/**
 * Ballistic mouse pointer acceleration utility tuned for natural 1:1 trackpad feel.
 *
 * Curve:
 *  • Low velocity  (< 3dp)  → 0.85× scaling for pixel-precise selections & clicks.
 *  • Mid velocity  (3..8dp) → 0.85× .. 1.45× smooth ramp for comfortable dragging.
 *  • High velocity (> 8dp)  → 1.45× .. 2.25× max for quick flicks across screen.
 */
object PointerAcceleration {

    private const val BASE_SENSITIVITY = 0.85f

    fun scaleDelta(rawDeltaX: Float, rawDeltaY: Float): Pair<Float, Float> {
        val magnitude = sqrt(rawDeltaX * rawDeltaX + rawDeltaY * rawDeltaY)
        if (magnitude == 0f) return Pair(0f, 0f)

        val velocityMultiplier = when {
            magnitude < 3.0f  -> 0.85f
            magnitude < 8.0f  -> 0.85f + (magnitude - 3.0f) * 0.12f // 0.85× .. 1.45×
            magnitude < 16.0f -> 1.45f + (magnitude - 8.0f) * 0.10f // 1.45× .. 2.25×
            else              -> 2.25f
        }.coerceAtMost(2.25f)

        val totalScale = BASE_SENSITIVITY * velocityMultiplier
        return Pair(rawDeltaX * totalScale, rawDeltaY * totalScale)
    }
}
