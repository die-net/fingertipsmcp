package com.fingertipsmcp.input

import com.fingertipsmcp.model.Point
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.random.Random
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MouseModelTest {
    @Test
    fun `moves end on the target`() {
        repeat(200) { seed ->
            val model = MouseModel(Random(seed))
            val from = Point(10f, 20f)
            val to = Point(640f, 380f)
            val path = model.move(from, to, targetSize = 24f, humanize = true)
            assertEquals(from, Point(path.first().x, path.first().y))
            assertEquals(to, Point(path.last().x, path.last().y))
            assertEquals(0L, path.first().tMs)
            assertTrue(path.zipWithNext().all { (a, b) -> b.tMs >= a.tMs }, "time runs forward")
        }
    }

    @Test
    fun `moves take human time`() {
        val durations =
            (0 until 200).map { seed ->
                MouseModel(Random(seed))
                    .move(Point(0f, 0f), Point(800f, 0f), targetSize = 20f, humanize = true)
                    .last()
                    .tMs
            }
        assertTrue(durations.min() >= 300, "a long move is never instant: ${durations.min()}")
        assertTrue(durations.max() <= 2_000, "nor slow: ${durations.max()}")
    }

    @Test
    fun `long moves sometimes overshoot`() {
        val target = Point(900f, 300f)
        val overshoots =
            (0 until 200).count { seed ->
                val path =
                    MouseModel(Random(seed)).move(Point(0f, 300f), target, 30f, humanize = true)
                path.any { it.x > target.x + 5f }
            }
        assertTrue(overshoots in 20..150, "overshoots: $overshoots")
    }

    @Test
    fun `samples once a frame`() {
        val path = MouseModel(Random(3)).move(Point(0f, 0f), Point(500f, 200f), 30f, true)
        val gaps = path.zipWithNext { a, b -> b.tMs - a.tMs }.filter { it in 1..40 }
        assertTrue(gaps.average() in 12.0..22.0, "gaps ${gaps.average()}")
    }

    @Test
    fun `paths bow away from the chord`() {
        val bowed =
            (0 until 50).count { seed ->
                val path = MouseModel(Random(seed)).move(Point(0f, 0f), Point(600f, 0f), 30f, true)
                path.maxOf { abs(it.y) } > 6f
            }
        assertTrue(bowed > 40, "bowed: $bowed")
    }

    @Test
    fun `plain moves are straight`() {
        val path = MouseModel(Random(1)).move(Point(0f, 0f), Point(400f, 0f), 30f, humanize = false)
        assertTrue(path.all { it.y == 0f })
        val steps = path.zipWithNext { a, b -> hypot(b.x - a.x, b.y - a.y) }
        assertTrue(steps.max() - steps.min() < 0.01f)
    }

    @Test
    fun `aims inside the box`() {
        val box = Box(100f, 200f, 180f, 230f)
        repeat(500) { seed ->
            val at = MouseModel(Random(seed)).aim(box, humanize = true)
            assertTrue(at in box, "$at")
        }
        assertEquals(box.center, MouseModel().aim(box, humanize = false))
    }

    @Test
    fun `wheel adds up in flicks`() {
        val ticks =
            MouseModel(Random(9)).wheel(0f, 1_000f, notch = 100f, durationMs = 300, humanize = true)
        assertEquals(10, ticks.size)
        assertEquals(1_000f, ticks.sumOf { it.dy.toDouble() }.toFloat(), 0.01f)
        val gaps = ticks.zipWithNext { a, b -> b.tMs - a.tMs }
        assertTrue(gaps.any { it >= 110 }, "a long scroll pauses between flicks: $gaps")
        assertTrue(gaps.all { it >= 12 }, "notches are not instant: $gaps")
    }

    @Test
    fun `wheel scrolls back up`() {
        val ticks = MouseModel(Random(1)).wheel(0f, -120f, 40f, 0, humanize = false)
        assertEquals(3, ticks.size)
        assertTrue(ticks.all { it.dy == -40f && it.dx == 0f })
    }
}
