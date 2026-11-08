package com.fingertipsmcp.input

import com.fingertipsmcp.model.ApiException
import com.fingertipsmcp.model.DisplayInfo
import com.fingertipsmcp.model.Point
import com.fingertipsmcp.model.PointerFrame
import com.fingertipsmcp.model.Rect
import com.fingertipsmcp.model.TimedPoint
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test

class TouchInputTest {
    private val display = DisplayInfo(1080, 2400, 420, 0, 60f)
    private val stroke =
        listOf(
            listOf(
                TimedPoint(-5f, 290f, 0),
                TimedPoint(500f, 270f, 8),
                TimedPoint(1200f, 2600f, 16),
            )
        )

    @Test
    fun `clamps to the display`() {
        val clamped = clampPoints(stroke, display, within = null).single()
        assertEquals(listOf(0f, 500f, 1079f), clamped.map { it.x })
        assertEquals(listOf(290f, 270f, 2399f), clamped.map { it.y })
    }

    @Test
    fun `clamps to the page`() {
        // A bow above the page's top edge would land on Chrome's toolbar.
        val page = Rect(0, 283, 1080, 2339)
        val clamped = clampPoints(stroke, display, page).single()
        assertEquals(listOf(0f, 500f, 1079f), clamped.map { it.x })
        assertEquals(listOf(290f, 283f, 2338f), clamped.map { it.y })
        assertEquals(listOf(0L, 8L, 16L), clamped.map { it.tMs })
    }

    @Test
    fun `a cancelled swipe fails`() = runTest {
        // Android cancels a swipe sent as the window changes. It was reported as a 200.
        assertCancelled { swipe(Point(100f, 200f), Point(100f, 900f), SWIPE) }
    }

    @Test
    fun `a cancelled tap fails`() = runTest { assertCancelled { tap(TARGET, humanize = false) } }

    @Test fun `a cancelled gesture fails`() = runTest { assertCancelled { gesture(stroke) } }

    @Test
    fun `a touch says where it went`() = runTest {
        val result = touch(accepted = true).swipe(Point(1f, 2f), Point(3f, 4f), SWIPE).asResult()
        assertTrue(result.ok)
        assertEquals("fake", result.backend)
        assertEquals(2, result.points.size)
    }

    private suspend fun assertCancelled(touching: suspend TouchInput.() -> Unit) {
        val failure =
            try {
                touch(accepted = false).touching()
                fail("The cancelled touch was reported as done")
            } catch (e: ApiException) {
                e
            }
        assertEquals(409, failure.status)
        assertEquals("gesture_cancelled", failure.error)
    }

    private fun touch(accepted: Boolean) =
        TouchInput(FakeInjector(accepted), Humanizer()) { display }

    /** Answers as the helper does once the system has taken or cancelled the touch. */
    private class FakeInjector(private val accepted: Boolean) : TouchInjector {
        override val name = "fake"

        override suspend fun shapedPerform(
            pointers: List<List<TimedPoint>>,
            fingers: List<List<FingerShape>>,
        ) = accepted

        override suspend fun playLive(
            first: PointerFrame,
            frames: ReceiveChannel<PointerFrame>,
            clamp: (PointerFrame) -> PointerFrame,
        ) = error("These touches are not live")
    }

    private companion object {
        val TARGET = Rect(10, 10, 110, 110)
        val SWIPE = SwipeSpec(durationMs = 300, fling = false, humanize = false)
    }
}
