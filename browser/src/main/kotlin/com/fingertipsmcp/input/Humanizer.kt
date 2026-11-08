package com.fingertipsmcp.input

import com.fingertipsmcp.model.Point
import com.fingertipsmcp.model.Rect
import com.fingertipsmcp.model.TimedPoint
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.roundToLong
import kotlin.math.sqrt
import kotlin.random.Random

data class SwipeSpec(
    val durationMs: Long = 300,
    /** Keep velocity at release instead of settling before lift. */
    val fling: Boolean = true,
    val humanize: Boolean = true,
    val sampleRateHz: Float = 60f,
)

/**
 * Turns targets into touch paths that look like a finger. Every path starts at its own `tMs`
 * (relative to the gesture start); paths that don't overlap in time are sequential touches.
 */
/** Hold used when humanize is off. CDP taps use the same delay. */
internal const val PLAIN_TAP_HOLD_MS = 60L

/** How the contact ellipse changes along a humanized stroke. */
enum class FingerMotion {
    /** A tap or long press: the patch grows, holds, then eases off. */
    PRESS,
    /** A swipe that stops before the finger lifts. */
    DRAG,
    /** A swipe that releases while the finger is still moving. */
    FLING,
}

class Humanizer(private val random: Random = Random.Default) {
    /** The same hand on a mouse, for desktop browsers. */
    val mouse: MouseModel by lazy { MouseModel(random) }

    /** The same hand on a keyboard. */
    val keys: TypingModel by lazy { TypingModel(random) }

    /** One tap: jittered inside the inner [INNER_FRACTION] of [target], held with micro-moves. */
    fun tap(target: Rect, holdMs: Long? = null, humanize: Boolean = true): List<TimedPoint> =
        tapAt(pointIn(target, humanize), target, holdMs, humanize, startMs = 0)

    /** [count] taps on [target]; later taps land close to the first, like a double tap. */
    fun taps(
        target: Rect,
        count: Int,
        holdMs: Long? = null,
        humanize: Boolean = true,
    ): List<List<TimedPoint>> {
        require(count >= 1) { "count must be at least 1" }
        val first = pointIn(target, humanize)
        val strokes = ArrayList<List<TimedPoint>>(count)
        var start = 0L
        repeat(count) { i ->
            val point =
                if (i == 0 || !humanize) first
                else
                    Point(
                        clamp(first.x + gaussian(REPEAT_TAP_SIGMA_PX), target.left, target.right),
                        clamp(first.y + gaussian(REPEAT_TAP_SIGMA_PX), target.top, target.bottom),
                    )
            val stroke = tapAt(point, target, holdMs, humanize, start)
            strokes += stroke
            val gap =
                if (humanize) random.nextLong(MIN_TAP_GAP_MS, MAX_TAP_GAP_MS + 1)
                else PLAIN_TAP_GAP_MS
            start = stroke.last().tMs + gap
        }
        return strokes
    }

    /** A press held past [longPressTimeoutMs] so the platform reports a long click. */
    fun longPress(
        target: Rect,
        longPressTimeoutMs: Long,
        humanize: Boolean = true,
    ): List<TimedPoint> {
        val extra =
            if (humanize) random.nextLong(MIN_LONG_PRESS_EXTRA_MS, MAX_LONG_PRESS_EXTRA_MS + 1)
            else PLAIN_LONG_PRESS_EXTRA_MS
        return tapAt(
            pointIn(target, humanize),
            target,
            longPressTimeoutMs + extra,
            humanize,
            startMs = 0,
        )
    }

    /**
     * A swipe from [from] to [to]. Humanized swipes follow a quadratic Bézier with a small
     * perpendicular bow, ease in (and out unless flinging), and carry sub-pixel noise.
     */
    fun swipe(from: Point, to: Point, spec: SwipeSpec = SwipeSpec()): List<TimedPoint> {
        val length = hypot(to.x - from.x, to.y - from.y)
        val duration =
            (if (spec.humanize) spec.durationMs * random.nextDouble(0.9, 1.1)
                else spec.durationMs.toDouble())
                .roundToLong()
                .coerceAtLeast(MIN_SWIPE_MS)
        val control = if (spec.humanize) bowControl(from, to, length) else midpoint(from, to)
        val rate = spec.sampleRateHz.coerceIn(MIN_SAMPLE_HZ, MAX_SAMPLE_HZ)
        val steps = ceil(duration * rate / MS_PER_SECOND).toInt().coerceAtLeast(2)
        val points = ArrayList<TimedPoint>(steps + 2)
        for (i in 0..steps) {
            val u = i.toDouble() / steps
            val t = (u * duration).roundToLong()
            if (points.isNotEmpty() && t <= points.last().tMs) continue
            val s = progress(u, spec)
            var p = bezier(from, control, to, s)
            if (spec.humanize && i in 1 until steps) {
                p = Point(p.x + gaussian(PATH_NOISE_SIGMA_PX), p.y + gaussian(PATH_NOISE_SIGMA_PX))
            }
            points += TimedPoint(p.x, p.y, t)
        }
        if (!spec.fling) {
            val dwell =
                if (spec.humanize) random.nextLong(MIN_SETTLE_MS, MAX_SETTLE_MS + 1)
                else PLAIN_SETTLE_MS
            points += TimedPoint(to.x, to.y, points.last().tMs + dwell)
        }
        return points
    }

    /**
     * One [FingerShape] per point of a stroke. Humanized contacts are one finger for the whole
     * stroke: an ellipse that grows as it lands and eases as it lifts, with a small walk between
     * samples. Pressure follows that same curve. A plain stroke repeats [FingerShape.steady].
     */
    fun finger(count: Int, motion: FingerMotion, humanize: Boolean): List<FingerShape> {
        if (!humanize || count <= 0) return List(count.coerceAtLeast(0)) { FingerShape.steady }
        val major0 = (MAJOR_BASE + gaussian(MAJOR_BASE_SIGMA)).coerceIn(MAJOR_MIN, MAJOR_MAX)
        val aspect = random.nextDouble(ASPECT_MIN, ASPECT_MAX).toFloat()
        val pressure0 =
            (PRESSURE_BASE + gaussian(PRESSURE_BASE_SIGMA)).coerceIn(PRESSURE_MIN, PRESSURE_MAX)
        var orient = (ORIENT_CENTER + gaussian(ORIENT_BASE_SIGMA)).coerceIn(ORIENT_MIN, ORIENT_MAX)
        var sizeWalk = 0f
        var pressureWalk = 0f
        return List(count) { i ->
            val u = if (count == 1) 0.5f else i.toFloat() / (count - 1)
            val depth = depth(u, motion)
            sizeWalk =
                (sizeWalk * WALK_KEEP + gaussian(SIZE_WALK_SIGMA)).coerceIn(-SIZE_WALK, SIZE_WALK)
            pressureWalk =
                (pressureWalk * WALK_KEEP + gaussian(PRESSURE_WALK_SIGMA)).coerceIn(
                    -PRESSURE_WALK,
                    PRESSURE_WALK,
                )
            orient = (orient + gaussian(ORIENT_WALK_SIGMA)).coerceIn(ORIENT_MIN, ORIENT_MAX)
            val major =
                (major0 * (MAJOR_FLOOR + MAJOR_SPAN * depth) + sizeWalk).coerceIn(
                    MAJOR_CLAMP_MIN,
                    MAJOR_CLAMP_MAX,
                )
            val minor = (major * aspect).coerceIn(MINOR_CLAMP_MIN, major * MINOR_MAX_OF_MAJOR)
            val size = ((major + minor) / 2f).coerceIn(MINOR_CLAMP_MIN, MAJOR_CLAMP_MAX)
            val pressure =
                (pressure0 * (PRESSURE_FLOOR + PRESSURE_SPAN * depth) + pressureWalk).coerceIn(
                    PRESSURE_CLAMP_MIN,
                    PRESSURE_CLAMP_MAX,
                )
            FingerShape(pressure, size, major, minor, orient)
        }
    }

    /**
     * Keeps a caller-supplied gesture. Endpoints stay put. Intermediate points pick up the same
     * sub-pixel noise as a swipe, and each stroke gets a contact curve for how it moves.
     */
    fun humanGesture(pointers: List<List<TimedPoint>>, humanize: Boolean): HumanGesture {
        val paths = pointers.map { path -> if (humanize) noise(path) else path }
        val fingers = paths.map { path -> finger(path.size, motionOf(path), humanize) }
        return HumanGesture(paths, fingers)
    }

    private fun noise(path: List<TimedPoint>): List<TimedPoint> {
        if (path.size < 3) return path
        return path.mapIndexed { index, point ->
            if (index == 0 || index == path.lastIndex) point
            else
                point.copy(
                    x = point.x + gaussian(PATH_NOISE_SIGMA_PX),
                    y = point.y + gaussian(PATH_NOISE_SIGMA_PX),
                )
        }
    }

    /** A stroke that barely travels is a press. One that is still fast at the end is a fling. */
    private fun motionOf(path: List<TimedPoint>): FingerMotion {
        if (path.size < 2) return FingerMotion.PRESS
        val first = path.first()
        val last = path.last()
        if (hypot(last.x - first.x, last.y - first.y) < TAP_TRAVEL_PX) return FingerMotion.PRESS
        val speeds =
            path.zipWithNext().map { (a, b) ->
                val dt = (b.tMs - a.tMs).coerceAtLeast(1)
                hypot(b.x - a.x, b.y - a.y) / dt
            }
        val peak = speeds.max()
        return if (peak > 0f && speeds.last() >= peak * FLING_SPEED_FRACTION) FingerMotion.FLING
        else FingerMotion.DRAG
    }

    private fun tapAt(
        point: Point,
        target: Rect,
        holdMs: Long?,
        humanize: Boolean,
        startMs: Long,
    ): List<TimedPoint> {
        if (!humanize) {
            val hold = (holdMs ?: PLAIN_HOLD_MS).coerceAtLeast(1)
            return listOf(
                TimedPoint(point.x, point.y, startMs),
                TimedPoint(point.x, point.y, startMs + hold),
            )
        }
        val hold =
            holdMs?.let {
                (it * random.nextDouble(HOLD_JITTER_LOW, HOLD_JITTER_HIGH)).roundToLong()
            } ?: random.nextLong(MIN_HOLD_MS, MAX_HOLD_MS + 1)
        val safeHold = hold.coerceAtLeast(MIN_MICRO_MOVES.toLong() + 1)
        val moveCount = random.nextInt(MIN_MICRO_MOVES, MAX_MICRO_MOVES + 1)
        val times = generateSequence {
            random.nextLong(1, safeHold)
        }
            .take(moveCount * 4)
            .distinct()
            .take(moveCount)
            .sorted()
            .toList()
        val path = ArrayList<TimedPoint>(times.size + 2)
        path += TimedPoint(point.x, point.y, startMs)
        var x = point.x
        var y = point.y
        for (t in times) {
            x =
                clamp(
                    x + random.nextDouble(-MICRO_MOVE_PX, MICRO_MOVE_PX).toFloat(),
                    target.left,
                    target.right,
                )
            y =
                clamp(
                    y + random.nextDouble(-MICRO_MOVE_PX, MICRO_MOVE_PX).toFloat(),
                    target.top,
                    target.bottom,
                )
            path += TimedPoint(x, y, startMs + t)
        }
        path += TimedPoint(x, y, startMs + safeHold)
        return path
    }

    private fun pointIn(target: Rect, humanize: Boolean): Point {
        val cx = (target.left + target.right) / 2f
        val cy = (target.top + target.bottom) / 2f
        if (!humanize) return Point(cx, cy)
        val halfW = (target.right - target.left) * INNER_FRACTION / 2f
        val halfH = (target.bottom - target.top) * INNER_FRACTION / 2f
        return Point(
            cx + gaussian(halfW / 2f).coerceIn(-halfW, halfW),
            cy + gaussian(halfH / 2f).coerceIn(-halfH, halfH),
        )
    }

    private fun bowControl(from: Point, to: Point, length: Float): Point {
        val mid = midpoint(from, to)
        if (length < 1f) return mid
        val offset =
            length *
                random.nextDouble(MIN_BOW_FRACTION, MAX_BOW_FRACTION).toFloat() *
                (if (random.nextBoolean()) 1f else -1f)
        // Unit normal to the chord.
        val nx = -(to.y - from.y) / length
        val ny = (to.x - from.x) / length
        // The curve's peak deviation is half the control point's, so double it.
        return Point(mid.x + nx * offset * 2f, mid.y + ny * offset * 2f)
    }

    private fun progress(u: Double, spec: SwipeSpec): Double =
        when {
            !spec.humanize -> u
            spec.fling -> easeInThenCruise(u)
            else -> u * u * (3 - 2 * u)
        }

    /**
     * Velocity ramps linearly from zero to its peak over the first [FLING_RAMP] of the gesture and
     * stays there until release.
     */
    private fun easeInThenCruise(u: Double): Double {
        val peak = 1.0 / (1.0 - FLING_RAMP / 2)
        return if (u < FLING_RAMP) peak * u * u / (2 * FLING_RAMP)
        else peak * (FLING_RAMP / 2 + (u - FLING_RAMP))
    }

    /**
     * 0 at the edges of a press, 1 while the finger is planted. A fling stays planted at release.
     */
    private fun depth(u: Float, motion: FingerMotion): Float {
        fun smooth(edge: Float): Float {
            val s = edge.coerceIn(0f, 1f)
            return s * s * (3 - 2 * s)
        }
        return when (motion) {
            FingerMotion.PRESS -> {
                val edge = min(smooth(u / PRESS_EDGE), smooth((1f - u) / PRESS_EDGE))
                PRESS_DEPTH_FLOOR + (1f - PRESS_DEPTH_FLOOR) * edge
            }
            FingerMotion.DRAG -> {
                val edge = min(smooth(u / DRAG_EDGE), smooth((1f - u) / DRAG_EDGE))
                DRAG_DEPTH_FLOOR + (1f - DRAG_DEPTH_FLOOR) * edge
            }
            FingerMotion.FLING -> {
                val rise = smooth(u / DRAG_EDGE)
                DRAG_DEPTH_FLOOR + (1f - DRAG_DEPTH_FLOOR) * rise
            }
        }
    }

    /** Standard normal via Box-Muller, scaled by [sigma]. */
    private fun gaussian(sigma: Float): Float {
        val u1 = 1.0 - random.nextDouble()
        val u2 = random.nextDouble()
        return (sqrt(-2.0 * ln(u1)) * cos(2 * PI * u2)).toFloat() * sigma
    }

    private companion object {
        const val INNER_FRACTION = 0.6f
        const val MIN_HOLD_MS = 50L
        const val MAX_HOLD_MS = 120L
        const val PLAIN_HOLD_MS = PLAIN_TAP_HOLD_MS
        const val HOLD_JITTER_LOW = 0.95
        const val HOLD_JITTER_HIGH = 1.05
        const val MIN_MICRO_MOVES = 1
        const val MAX_MICRO_MOVES = 3
        const val MICRO_MOVE_PX = 1.0
        const val MIN_TAP_GAP_MS = 90L
        const val MAX_TAP_GAP_MS = 160L
        const val PLAIN_TAP_GAP_MS = 120L
        const val REPEAT_TAP_SIGMA_PX = 2f
        const val MIN_LONG_PRESS_EXTRA_MS = 150L
        const val MAX_LONG_PRESS_EXTRA_MS = 350L
        const val PLAIN_LONG_PRESS_EXTRA_MS = 200L
        const val MIN_BOW_FRACTION = 0.02
        const val MAX_BOW_FRACTION = 0.06
        const val FLING_RAMP = 0.4
        const val PATH_NOISE_SIGMA_PX = 0.25f
        const val MIN_SETTLE_MS = 40L
        const val MAX_SETTLE_MS = 90L
        const val PLAIN_SETTLE_MS = 60L
        const val MIN_SWIPE_MS = 20L
        const val MIN_SAMPLE_HZ = 30f
        const val MAX_SAMPLE_HZ = 240f
        const val MS_PER_SECOND = 1000.0
        const val MAJOR_BASE = 0.08f
        const val MAJOR_BASE_SIGMA = 0.012f
        const val MAJOR_MIN = 0.05f
        const val MAJOR_MAX = 0.12f
        const val MAJOR_FLOOR = 0.75f
        const val MAJOR_SPAN = 0.4f
        const val MAJOR_CLAMP_MIN = 0.035f
        const val MAJOR_CLAMP_MAX = 0.16f
        const val MINOR_CLAMP_MIN = 0.02f
        const val MINOR_MAX_OF_MAJOR = 0.96f
        const val ASPECT_MIN = 0.72
        const val ASPECT_MAX = 0.93
        const val PRESSURE_BASE = 0.55f
        const val PRESSURE_BASE_SIGMA = 0.03f
        const val PRESSURE_MIN = 0.42f
        const val PRESSURE_MAX = 0.68f
        const val PRESSURE_FLOOR = 0.7f
        const val PRESSURE_SPAN = 0.45f
        const val PRESSURE_CLAMP_MIN = 0.25f
        const val PRESSURE_CLAMP_MAX = 0.85f
        const val PRESS_EDGE = 0.28f
        const val PRESS_DEPTH_FLOOR = 0.35f
        const val DRAG_EDGE = 0.12f
        const val DRAG_DEPTH_FLOOR = 0.82f
        const val WALK_KEEP = 0.65f
        const val SIZE_WALK_SIGMA = 0.003f
        const val SIZE_WALK = 0.008f
        const val PRESSURE_WALK_SIGMA = 0.004f
        const val PRESSURE_WALK = 0.012f
        const val ORIENT_CENTER = 0.5f
        const val ORIENT_BASE_SIGMA = 0.012f
        const val ORIENT_WALK_SIGMA = 0.004f
        const val ORIENT_MIN = 0.42f
        const val ORIENT_MAX = 0.58f
        const val TAP_TRAVEL_PX = 12f
        const val FLING_SPEED_FRACTION = 0.6f
    }
}

/** A gesture after humanizing: one finger curve per pointer, aligned with [pointers]. */
data class HumanGesture(
    val pointers: List<List<TimedPoint>>,
    val fingers: List<List<FingerShape>>,
)

private fun midpoint(a: Point, b: Point) = Point((a.x + b.x) / 2f, (a.y + b.y) / 2f)

private fun bezier(p0: Point, c: Point, p1: Point, s: Double): Point {
    val t = s.toFloat()
    val inv = 1f - t
    return Point(
        inv * inv * p0.x + 2 * inv * t * c.x + t * t * p1.x,
        inv * inv * p0.y + 2 * inv * t * c.y + t * t * p1.y,
    )
}

private fun clamp(v: Float, low: Int, high: Int): Float =
    if (high <= low) low.toFloat() else v.coerceIn(low.toFloat(), high.toFloat())
