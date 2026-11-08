package com.fingertipsmcp.input

import com.fingertipsmcp.model.TimedPoint

enum class TouchPhase {
    DOWN,
    MOVE,
    UP,
}

/** One pointer change, with [tMs] relative to the start of the gesture. */
data class TouchSample(
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
fun touchTimeline(
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
