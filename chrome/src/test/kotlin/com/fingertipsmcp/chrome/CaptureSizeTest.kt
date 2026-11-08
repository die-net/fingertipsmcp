package com.fingertipsmcp.chrome

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** The size a tab is captured at. */
class CaptureSizeTest {
    @Test
    fun `capture is half size`() {
        assertEquals(600 to 400, captureSize(1200, 800, 1f, 1280))
        assertEquals(1200 to 800, captureSize(1200, 800, 2f, 1280))
        // Longer than the viewer asked for: scaled down to it, in even sizes.
        assertEquals(1280 to 852, captureSize(1600, 1066, 2f, 1280))
    }
}
