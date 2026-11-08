package com.fingertipsmcp.input

import kotlinx.coroutines.delay

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
