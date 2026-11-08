package com.fingertipsmcp.input

import com.fingertipsmcp.model.Point
import com.fingertipsmcp.model.TimedPoint
import kotlin.math.hypot
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.random.Random

/** A rectangle in CSS pixels, such as an element's box in the viewport. */
data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float
        get() = right - left

    val height: Float
        get() = bottom - top

    val center: Point
        get() = Point((left + right) / 2f, (top + bottom) / 2f)
}

operator fun Box.contains(point: Point): Boolean =
    point.x >= left && point.x <= right && point.y >= top && point.y <= bottom

/** One wheel notch, [tMs] after the scroll starts. */
data class WheelTick(val dx: Float, val dy: Float, val tMs: Long)

/**
 * How a hand moves a mouse. A move follows a bowed curve with a minimum-jerk speed profile, takes
 * about as long as Fitts's law says for the distance and target size, and on a long move often
 * overshoots and settles back. Points are sampled at about one per display frame, as Chrome
 * coalesces them, with sub-pixel tremor along the way. Wheel scrolling comes in notches, grouped
 * into flicks of a finger. With humanize off, a move is a straight line at a fixed pace.
 */
class MouseModel(private val random: Random = Random.Default) {
    /**
     * Where in [box] to land: near the middle, spread like aimed clicks, kept inside its inner part
     * so a thin border or rounded corner is not hit.
     */
    fun aim(box: Box, humanize: Boolean): Point {
        val center = box.center
        if (!humanize) return center
        val halfW = box.width * INNER_FRACTION / 2f
        val halfH = box.height * INNER_FRACTION / 2f
        return Point(
            center.x + random.nextGaussian(halfW / 2.5f).coerceIn(-halfW, halfW),
            center.y + random.nextGaussian(halfH / 2.5f).coerceIn(-halfH, halfH),
        )
    }

    /**
     * Mouse positions from [from] to [to], timed from zero. [targetSize] is the smaller side of the
     * target, which sets how careful the approach is. The first point is [from] at time zero and
     * the last is exactly [to].
     */
    fun move(from: Point, to: Point, targetSize: Float, humanize: Boolean): List<TimedPoint> {
        val distance = hypot(to.x - from.x, to.y - from.y)
        if (distance < MIN_MOVE_PX) return listOf(TimedPoint(to.x, to.y, 0))
        if (!humanize) return straight(from, to, distance)
        val size = targetSize.coerceAtLeast(MIN_TARGET_PX)
        val overshoot =
            distance > OVERSHOOT_MIN_PX && random.nextDouble() < overshootChance(distance)
        if (!overshoot) return stroke(from, to, fittsMs(distance, size), startMs = 0, tremor = true)
        val aim = overshootPoint(from, to, distance)
        val first = stroke(from, aim, fittsMs(distance, size) * OVERSHOOT_TIME_SHARE, 0, true)
        val pause = random.nextLong(MIN_CORRECTION_PAUSE_MS, MAX_CORRECTION_PAUSE_MS)
        val back = hypot(to.x - aim.x, to.y - aim.y)
        val correction =
            stroke(
                aim,
                to,
                fittsMs(back, size) * CORRECTION_TIME_SHARE,
                startMs = first.last().tMs + pause,
                tremor = false,
            )
        return first + correction.drop(1)
    }

    /** How long the pointer rests on a target before pressing. */
    fun hoverMs(humanize: Boolean): Long =
        if (!humanize) PLAIN_HOVER_MS
        else random.nextLogNormalMs(HOVER_MEDIAN_MS, HOVER_SPREAD, MIN_HOVER_MS, MAX_HOVER_MS)

    /** How long the button stays down for a click. */
    fun pressMs(humanize: Boolean): Long =
        if (!humanize) PLAIN_PRESS_MS
        else random.nextLogNormalMs(PRESS_MEDIAN_MS, PRESS_SPREAD, MIN_PRESS_MS, MAX_PRESS_MS)

    /**
     * Wheel notches that scroll by about ([dx], [dy]) CSS pixels, [notch] pixels each, spread over
     * [durationMs]. A long scroll is several flicks with a pause between them, as a finger rolls a
     * wheel a few notches at a time.
     */
    fun wheel(
        dx: Float,
        dy: Float,
        notch: Float,
        durationMs: Long,
        humanize: Boolean,
    ): List<WheelTick> {
        val distance = hypot(dx, dy)
        val count = max(1, (distance / notch).roundToInt())
        val stepX = if (distance == 0f) 0f else dx / distance * notch
        val stepY = if (distance == 0f) 0f else dy / distance * notch
        if (!humanize) {
            val gap = if (count > 1) durationMs / (count - 1) else 0L
            return List(count) { i -> WheelTick(stepX, stepY, gap * i) }
        }
        val ticks = ArrayList<WheelTick>(count)
        var at = 0L
        var inFlick = 0
        var flick = random.nextInt(MIN_FLICK_NOTCHES, MAX_FLICK_NOTCHES + 1)
        val budget = max(durationMs, count * MIN_NOTCH_GAP_MS)
        val baseGap = (budget / count).coerceIn(MIN_NOTCH_GAP_MS, MAX_NOTCH_GAP_MS)
        repeat(count) {
            ticks += WheelTick(stepX, stepY, at)
            inFlick++
            at +=
                if (inFlick >= flick) {
                    inFlick = 0
                    flick = random.nextInt(MIN_FLICK_NOTCHES, MAX_FLICK_NOTCHES + 1)
                    random.nextLong(MIN_FLICK_PAUSE_MS, MAX_FLICK_PAUSE_MS)
                } else {
                    (baseGap * random.nextDouble(GAP_JITTER_LOW, GAP_JITTER_HIGH)).roundToLong()
                }
        }
        return ticks
    }

    /** Fitts's law with per-move variation: a fast person on a long move is still not instant. */
    private fun fittsMs(distance: Float, size: Float): Double {
        val index = log2(distance / size + 1.0)
        val a = random.nextDouble(FITTS_A_MIN, FITTS_A_MAX)
        val b = random.nextDouble(FITTS_B_MIN, FITTS_B_MAX)
        return (a + b * index).coerceIn(MIN_MOVE_MS, MAX_MOVE_MS)
    }

    private fun overshootChance(distance: Float): Double =
        (OVERSHOOT_BASE + distance / OVERSHOOT_PER_PX).coerceAtMost(OVERSHOOT_MAX)

    private fun overshootPoint(from: Point, to: Point, distance: Float): Point {
        val ux = (to.x - from.x) / distance
        val uy = (to.y - from.y) / distance
        val past = distance * random.nextDouble(MIN_OVERSHOOT, MAX_OVERSHOOT).toFloat()
        val side = random.nextGaussian(past * OVERSHOOT_SIDE)
        return Point(to.x + ux * past - uy * side, to.y + uy * past + ux * side)
    }

    /**
     * One submovement along a cubic Bezier. The control points sit off the chord on the same side,
     * at uneven distances, so the path bows the way a wrist pivots. Speed follows a minimum-jerk
     * profile that peaks a little before the middle.
     */
    private fun stroke(
        from: Point,
        to: Point,
        durationMs: Double,
        startMs: Long,
        tremor: Boolean,
    ): List<TimedPoint> {
        val length = hypot(to.x - from.x, to.y - from.y)
        if (length < MIN_MOVE_PX) return listOf(TimedPoint(to.x, to.y, startMs))
        val nx = -(to.y - from.y) / length
        val ny = (to.x - from.x) / length
        val sign = if (random.nextBoolean()) 1f else -1f
        val bow = length * random.nextDouble(MIN_BOW, MAX_BOW).toFloat() * sign
        val c1t = random.nextDouble(C1_MIN, C1_MAX).toFloat()
        val c2t = random.nextDouble(C2_MIN, C2_MAX).toFloat()
        val bow1 = bow * random.nextDouble(BOW_SPLIT_MIN, 1.0).toFloat()
        val bow2 = bow * random.nextDouble(BOW_SPLIT_MIN, 1.0).toFloat()
        val c1 = along(from, to, c1t, nx * bow1, ny * bow1)
        val c2 = along(from, to, c2t, nx * bow2, ny * bow2)
        val skew = random.nextDouble(MIN_SKEW, MAX_SKEW)
        val frames = max(2, (durationMs / FRAME_MS).roundToInt())
        val out = ArrayList<TimedPoint>(frames + 1)
        out += TimedPoint(from.x, from.y, startMs)
        var at = startMs.toDouble()
        for (i in 1..frames) {
            at += FRAME_MS * random.nextDouble(FRAME_JITTER_LOW, FRAME_JITTER_HIGH)
            val u = (i.toDouble() / frames).coerceAtMost(1.0)
            val s = minimumJerk(u.pow(skew))
            val p = cubic(from, c1, c2, to, s.toFloat())
            val last = i == frames
            val jitter = if (tremor && !last) TREMOR_PX else 0f
            out +=
                TimedPoint(
                    p.x + random.nextGaussian(jitter),
                    p.y + random.nextGaussian(jitter),
                    at.roundToLong(),
                )
        }
        val end = out.last()
        out[out.lastIndex] = TimedPoint(to.x, to.y, end.tMs)
        return out
    }

    private fun straight(from: Point, to: Point, distance: Float): List<TimedPoint> {
        val frames = max(2, (distance / PLAIN_PX_PER_FRAME).roundToInt())
        return List(frames + 1) { i ->
            val t = i.toFloat() / frames
            TimedPoint(
                from.x + (to.x - from.x) * t,
                from.y + (to.y - from.y) * t,
                (i * FRAME_MS).roundToLong(),
            )
        }
    }

    private companion object {
        const val INNER_FRACTION = 0.7f
        const val MIN_MOVE_PX = 0.5f
        const val MIN_TARGET_PX = 4f
        const val FRAME_MS = 1000.0 / 60.0
        const val FRAME_JITTER_LOW = 0.85
        const val FRAME_JITTER_HIGH = 1.15
        const val PLAIN_PX_PER_FRAME = 40f
        const val FITTS_A_MIN = 70.0
        const val FITTS_A_MAX = 130.0
        const val FITTS_B_MIN = 110.0
        const val FITTS_B_MAX = 170.0
        const val MIN_MOVE_MS = 110.0
        const val MAX_MOVE_MS = 1_400.0
        const val OVERSHOOT_MIN_PX = 140f
        const val OVERSHOOT_BASE = 0.15
        const val OVERSHOOT_PER_PX = 2_500.0
        const val OVERSHOOT_MAX = 0.55
        const val MIN_OVERSHOOT = 0.025
        const val MAX_OVERSHOOT = 0.07
        const val OVERSHOOT_SIDE = 0.4f
        const val OVERSHOOT_TIME_SHARE = 0.85
        const val CORRECTION_TIME_SHARE = 0.6
        const val MIN_CORRECTION_PAUSE_MS = 25L
        const val MAX_CORRECTION_PAUSE_MS = 90L
        const val MIN_BOW = 0.04
        const val MAX_BOW = 0.16
        const val BOW_SPLIT_MIN = 0.55
        const val C1_MIN = 0.15
        const val C1_MAX = 0.4
        const val C2_MIN = 0.6
        const val C2_MAX = 0.85
        const val MIN_SKEW = 0.82
        const val MAX_SKEW = 0.98
        const val TREMOR_PX = 0.35f
        const val PLAIN_HOVER_MS = 50L
        const val HOVER_MEDIAN_MS = 140.0
        const val HOVER_SPREAD = 0.45
        const val MIN_HOVER_MS = 60L
        const val MAX_HOVER_MS = 450L
        const val PLAIN_PRESS_MS = 60L
        const val PRESS_MEDIAN_MS = 95.0
        const val PRESS_SPREAD = 0.25
        const val MIN_PRESS_MS = 55L
        const val MAX_PRESS_MS = 170L
        const val MIN_FLICK_NOTCHES = 2
        const val MAX_FLICK_NOTCHES = 6
        const val MIN_NOTCH_GAP_MS = 18L
        const val MAX_NOTCH_GAP_MS = 70L
        const val GAP_JITTER_LOW = 0.7
        const val GAP_JITTER_HIGH = 1.3
        const val MIN_FLICK_PAUSE_MS = 110L
        const val MAX_FLICK_PAUSE_MS = 320L
    }
}

/** Position along a 0..1 minimum-jerk profile: zero velocity and acceleration at both ends. */
internal fun minimumJerk(u: Double): Double {
    val t = u.coerceIn(0.0, 1.0)
    return t * t * t * (10 - 15 * t + 6 * t * t)
}

private fun along(from: Point, to: Point, t: Float, offX: Float, offY: Float): Point =
    Point(from.x + (to.x - from.x) * t + offX, from.y + (to.y - from.y) * t + offY)

private fun cubic(p0: Point, c1: Point, c2: Point, p1: Point, t: Float): Point {
    val inv = 1f - t
    val a = inv * inv * inv
    val b = 3 * inv * inv * t
    val c = 3 * inv * t * t
    val d = t * t * t
    return Point(
        a * p0.x + b * c1.x + c * c2.x + d * p1.x,
        a * p0.y + b * c1.y + c * c2.y + d * p1.y,
    )
}
