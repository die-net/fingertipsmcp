package net.die.phoneapi.input

import kotlinx.coroutines.delay
import net.die.phoneapi.model.TimedPoint

internal enum class TouchPhase {
    DOWN,
    MOVE,
    UP,
}

/** One pointer change, with [tMs] relative to the start of the gesture. */
internal data class TouchSample(
    val pointer: Int,
    val x: Float,
    val y: Float,
    val tMs: Long,
    val phase: TouchPhase,
    val shape: FingerShape = FingerShape.steady,
)

/**
 * Expands stroke paths into a single time-ordered stream of pointer changes. A one-point stroke is
 * a tap: down, then up.
 */
internal fun touchTimeline(
    pointers: List<List<TimedPoint>>,
    fingers: List<List<FingerShape>> = emptyList(),
): List<TouchSample> {
    val samples = ArrayList<TouchSample>()
    pointers.forEachIndexed { pointer, path ->
        require(path.isNotEmpty()) { "A stroke needs at least one point" }
        val shapes = fingers.getOrNull(pointer).orEmpty()
        fun shape(index: Int): FingerShape = shapes.getOrNull(index) ?: FingerShape.steady
        if (path.size == 1) {
            val point = path.first()
            val finger = shape(0)
            samples += TouchSample(pointer, point.x, point.y, point.tMs, TouchPhase.DOWN, finger)
            samples += TouchSample(pointer, point.x, point.y, point.tMs, TouchPhase.UP, finger)
        } else {
            path.forEachIndexed { index, point ->
                val phase =
                    when (index) {
                        0 -> TouchPhase.DOWN
                        path.lastIndex -> TouchPhase.UP
                        else -> TouchPhase.MOVE
                    }
                samples += TouchSample(pointer, point.x, point.y, point.tMs, phase, shape(index))
            }
        }
    }
    return samples.sortedWith(compareBy({ it.tMs }, { it.phase.ordinal }))
}

/**
 * The pointer events that inject [samples]: each carries every contact down at that moment, with
 * the changing one last lifted or first added in contact order.
 */
internal fun timelineEvents(samples: List<TouchSample>): List<PointerEvent> {
    val active = LinkedHashMap<Int, PointerPoint>()
    return samples.map { sample ->
        val first = active.isEmpty()
        active[sample.pointer] =
            PointerPoint(sample.pointer, sample.x, sample.y, sample.tMs, sample.shape)
        val points = active.values.toList()
        val phase =
            when (sample.phase) {
                TouchPhase.DOWN -> if (first) PointerPhase.DOWN else PointerPhase.POINTER_DOWN
                TouchPhase.MOVE -> PointerPhase.MOVE
                TouchPhase.UP -> {
                    active.remove(sample.pointer)
                    if (active.isEmpty()) PointerPhase.UP else PointerPhase.POINTER_UP
                }
            }
        PointerEvent(phase, sample.pointer, points, sample.tMs)
    }
}

/**
 * Sends [events] to [emit] at their times, counted from the start on the [now] clock. The schedule
 * is kept against the clock rather than by summing gaps, so slow sends do not stretch the gesture.
 * False when any send failed.
 */
internal suspend fun playTimeline(
    events: List<PointerEvent>,
    now: () -> Long,
    emit: suspend (PointerEvent) -> Boolean,
): Boolean {
    val start = now()
    var ok = true
    for (event in events) {
        val wait = start + event.tMs - now()
        if (wait > 0) delay(wait)
        ok = emit(event) && ok
    }
    return ok
}
