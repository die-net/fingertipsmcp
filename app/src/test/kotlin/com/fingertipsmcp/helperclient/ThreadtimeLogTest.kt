package com.fingertipsmcp.helperclient

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ThreadtimeLogTest {
    @Test
    fun `keeps colons in the message`() {
        val line =
            ThreadtimeLog.parse(
                "10-01 04:07:12.123  1234  5678 I FingertipsSmoke: hello-colon:test"
            )
        checkNotNull(line)
        assertEquals("10-01 04:07:12.123", line.time)
        assertEquals(1234L, line.pid)
        assertEquals(5678L, line.tid)
        assertEquals("I", line.level)
        assertEquals("FingertipsSmoke", line.tag)
        assertEquals("hello-colon:test", line.message)
    }

    @Test
    fun `trims a short padded tag`() {
        val line = ThreadtimeLog.parse("10-01 04:07:12.123  12  34 D chatty  : hi: there")
        checkNotNull(line)
        assertEquals("chatty", line.tag)
        assertEquals("hi: there", line.message)
    }

    @Test
    fun `skips a malformed line`() {
        assertNull(ThreadtimeLog.parse("--------- beginning of main"))
        assertNull(ThreadtimeLog.parse("not a log line"))
    }
}
