package com.fingertipsmcp.input

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToLong
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * How a person's typing is timed. Letters inside a word come quickly; the key after a space or
 * punctuation comes slower, as the next word is read or thought of. Every gap stays inside the
 * caller's range. Keys are held for a short, varying time, so fast typing rolls over: the next key
 * can go down before the last one comes up.
 */
class TypingModel(private val random: Random = Random.Default) {
    /**
     * The gap from the key before [next] to [next] going down, in [minMs]..[maxMs]. [previous] is
     * the character typed before, or null for the first key.
     */
    fun gapMs(previous: Char?, next: Char, minMs: Long, maxMs: Long): Long {
        if (previous == null || maxMs <= minMs) return if (previous == null) 0L else minMs
        val range = (maxMs - minMs).toDouble()
        val slow = !previous.isLetterOrDigit() || next.isUpperCase()
        val u = random.nextDouble()
        // Skewed toward the short end inside a word and the long end at a boundary.
        val fraction = if (slow) 1.0 - u.pow(SLOW_SKEW) else u.pow(FAST_SKEW)
        return (minMs + range * fraction).roundToLong().coerceIn(minMs, maxMs)
    }

    /** How long a key stays down. Zero when the caller asked for no delay at all. */
    fun holdMs(maxGapMs: Long): Long {
        if (maxGapMs <= 0) return 0L
        val sample = HOLD_MEDIAN_MS * exp(gaussian() * HOLD_SPREAD)
        return sample.roundToLong().coerceIn(MIN_HOLD_MS, MAX_HOLD_MS)
    }

    /** Time between pressing a modifier, such as Shift, and the key it changes. */
    fun modifierLeadMs(maxGapMs: Long): Long =
        if (maxGapMs <= 0) 0L else random.nextLong(MIN_MODIFIER_LEAD_MS, MAX_MODIFIER_LEAD_MS)

    private fun gaussian(): Double {
        val u1 = 1.0 - random.nextDouble()
        val u2 = random.nextDouble()
        return sqrt(-2.0 * ln(u1)) * cos(2 * PI * u2)
    }

    private companion object {
        const val FAST_SKEW = 1.7
        const val SLOW_SKEW = 1.8
        const val HOLD_MEDIAN_MS = 78.0
        const val HOLD_SPREAD = 0.28
        const val MIN_HOLD_MS = 35L
        const val MAX_HOLD_MS = 160L
        const val MIN_MODIFIER_LEAD_MS = 25L
        const val MAX_MODIFIER_LEAD_MS = 70L
    }
}
