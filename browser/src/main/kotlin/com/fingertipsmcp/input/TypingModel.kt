package com.fingertipsmcp.input

import kotlin.math.pow
import kotlin.math.roundToLong
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
        if (previous == null) return 0L
        return gap(slow = !previous.isLetterOrDigit() || next.isUpperCase(), minMs, maxMs)
    }

    /**
     * The pause between clicking a field and its first key, in [minMs]..[maxMs], drawn like the gap
     * at a word boundary, as the hand goes from the mouse to the keys. Zero when [maxMs] is.
     */
    fun firstKeyMs(minMs: Long, maxMs: Long): Long = gap(slow = true, minMs, maxMs)

    /** How long a key stays down. Zero when the caller asked for no delay at all. */
    fun holdMs(maxGapMs: Long): Long =
        if (maxGapMs <= 0) 0L
        else random.nextLogNormalMs(HOLD_MEDIAN_MS, HOLD_SPREAD, MIN_HOLD_MS, MAX_HOLD_MS)

    /** Time between pressing a modifier, such as Shift, and the key it changes. */
    fun modifierLeadMs(maxGapMs: Long): Long =
        if (maxGapMs <= 0) 0L else random.nextLong(MIN_MODIFIER_LEAD_MS, MAX_MODIFIER_LEAD_MS)

    /** A gap in [minMs]..[maxMs], skewed short inside a word and long at a [slow] boundary. */
    private fun gap(slow: Boolean, minMs: Long, maxMs: Long): Long {
        if (maxMs <= minMs) return minMs
        val range = (maxMs - minMs).toDouble()
        val u = random.nextDouble()
        val fraction = if (slow) 1.0 - u.pow(SLOW_SKEW) else u.pow(FAST_SKEW)
        return (minMs + range * fraction).roundToLong().coerceIn(minMs, maxMs)
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
