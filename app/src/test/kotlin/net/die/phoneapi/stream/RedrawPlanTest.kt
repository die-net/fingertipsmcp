package net.die.phoneapi.stream

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RedrawPlanTest {
    private val plan = RedrawPlan(settleMs = longArrayOf(100, 300, 700), idleMs = 4_000)

    /** Runs the relay's loop from [startMs] to [endMs] and returns when it redrew. */
    private fun redrawsUntil(startMs: Long, endMs: Long): List<Long> {
        val drawn = ArrayList<Long>()
        var now = startMs
        while (true) {
            now += plan.delayMs(now) ?: return drawn
            if (now > endMs) return drawn
            if (plan.due(now)) {
                plan.drew(now)
                drawn += now
            }
        }
    }

    private fun mirror(atMs: Long) {
        plan.mirrored(atMs)
        plan.drew(atMs)
    }

    @Test
    fun `nothing due before a frame`() {
        assertFalse(plan.due(10_000))
        assertNull(plan.delayMs(10_000))
    }

    @Test
    fun `still screen settles, idles`() {
        mirror(1_000)
        assertEquals(
            listOf(1_100L, 1_300L, 1_700L, 5_700L, 9_700L, 13_700L),
            redrawsUntil(1_000, 14_000),
        )
    }

    @Test
    fun `moving screen gets no redraws`() {
        var now = 0L
        repeat(60) {
            mirror(now)
            assertFalse(plan.due(now + 33))
            now += 33
        }
    }

    @Test
    fun `motion restarts settling`() {
        mirror(0)
        assertEquals(listOf(100L, 300L, 700L), redrawsUntil(0, 1_000))
        mirror(2_000)
        assertEquals(listOf(2_100L, 2_300L, 2_700L), redrawsUntil(2_000, 3_000))
    }

    @Test
    fun `join redraw delays idle`() {
        mirror(0)
        redrawsUntil(0, 1_000)
        plan.drew(3_000)
        assertFalse(plan.due(4_700))
        assertTrue(plan.due(7_000))
    }
}
