package com.fingertipsmcp.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class LogcatSpecTest {
    @Test
    fun `logcat argv`() {
        assertEquals(
            listOf("-v", "threadtime", "-T", "1", "*:I"),
            LogcatFilter(tag = null, level = "I").argv().toList(),
        )
        assertEquals(
            listOf("-v", "threadtime", "-T", "1", "FingertipsSmoke:I", "*:S"),
            LogcatFilter(tag = "FingertipsSmoke", level = "I").argv().toList(),
        )
    }
}
