package com.fingertipsmcp.input

import com.fingertipsmcp.model.ApiException
import com.fingertipsmcp.model.TimedPoint

/** The shortest swipe a request may ask for, which is about one display frame. */
const val MIN_SWIPE_MS: Long = 20L

/** The longest swipe a request may ask for. */
const val MAX_SWIPE_MS: Long = 10_000L

/** The most points one gesture may carry, across all its pointers. */
const val MAX_GESTURE_POINTS: Int = 10_000

/**
 * Refuses a swipe [durationMs] outside [MIN_SWIPE_MS]..[MAX_SWIPE_MS], for screen and page alike.
 */
fun checkSwipeDuration(durationMs: Long) {
    if (durationMs !in MIN_SWIPE_MS..MAX_SWIPE_MS) {
        throw ApiException.badRequest("durationMs must be $MIN_SWIPE_MS..$MAX_SWIPE_MS")
    }
}

/**
 * Refuses gesture [pointers] that a screen or a page could not play: a pointer with no points, more
 * than [MAX_GESTURE_POINTS] in all, or a negative time.
 */
fun checkGesturePointers(pointers: List<List<TimedPoint>>) {
    if (pointers.isEmpty() || pointers.any { it.isEmpty() }) {
        throw ApiException.badRequest("Each pointer needs at least one point")
    }
    if (pointers.sumOf { it.size } > MAX_GESTURE_POINTS) {
        throw ApiException.badRequest("At most $MAX_GESTURE_POINTS points per gesture")
    }
    if (pointers.any { path -> path.any { it.tMs < 0 } }) {
        throw ApiException.badRequest("tMs must be >= 0")
    }
}
