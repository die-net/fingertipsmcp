package com.fingertipsmcp.input

import com.fingertipsmcp.model.ApiException
import com.fingertipsmcp.model.TimedPoint
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** The pointer checks the phone's screen input and every page input share. */
class PointerChecksTest {
    @Test
    fun `a swipe takes 20 ms to 10 s`() {
        checkSwipeDuration(MIN_SWIPE_MS)
        checkSwipeDuration(MAX_SWIPE_MS)
        assertEquals(400, assertThrows<ApiException> { checkSwipeDuration(19) }.status)
        assertEquals(400, assertThrows<ApiException> { checkSwipeDuration(10_001) }.status)
    }

    @Test
    fun `a gesture needs sane points`() {
        val point = TimedPoint(1f, 1f, 0)
        checkGesturePointers(listOf(listOf(point)))
        assertThrows<ApiException> { checkGesturePointers(emptyList()) }
        assertThrows<ApiException> { checkGesturePointers(listOf(listOf(point), emptyList())) }
        assertThrows<ApiException> { checkGesturePointers(listOf(listOf(point.copy(tMs = -1)))) }
        val many = List(MAX_GESTURE_POINTS / 2 + 1) { point }
        assertThrows<ApiException> { checkGesturePointers(listOf(many, many)) }
    }
}
