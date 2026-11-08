package com.fingertipsmcp.input

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToLong
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * A normal sample with mean zero and standard deviation [sigma], by the Box-Muller transform. A
 * [sigma] of zero is zero and draws nothing.
 */
fun Random.nextGaussian(sigma: Float): Float {
    if (sigma == 0f) return 0f
    val u1 = 1.0 - nextDouble()
    val u2 = nextDouble()
    return (sqrt(-2.0 * ln(u1)) * cos(2 * PI * u2)).toFloat() * sigma
}

/**
 * A duration in milliseconds around [medianMs], lognormal as human reaction and hold times are,
 * with [spread] the standard deviation of its log, kept inside [lowMs]..[highMs].
 */
fun Random.nextLogNormalMs(medianMs: Double, spread: Double, lowMs: Long, highMs: Long): Long =
    (medianMs * exp(nextGaussian(1f) * spread)).roundToLong().coerceIn(lowMs, highMs)
