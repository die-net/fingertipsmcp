package com.fingertipsmcp.server

import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.readBuffer
import io.ktor.utils.io.writeFully
import java.io.IOException
import kotlinx.coroutines.test.runTest
import kotlinx.io.readByteArray
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ShutdownOnCloseTest {
    @Test
    fun `close flushes then shuts down`() = runTest {
        val base = ByteChannel()
        var flushedFirst = false
        var shutdowns = 0
        val output =
            ShutdownOnClose(base) {
                flushedFirst = base.isClosedForWrite
                shutdowns++
            }
        output.writeFully(byteArrayOf(1, 2, 3))
        output.flushAndClose()
        output.cancel(IOException("late"))
        assertEquals(1, shutdowns)
        assertTrue(flushedFirst)
        assertArrayEquals(byteArrayOf(1, 2, 3), base.readBuffer().readByteArray())
    }

    @Test
    fun `cancel shuts down`() = runTest {
        var shutdowns = 0
        val output = ShutdownOnClose(ByteChannel()) { shutdowns++ }
        output.cancel(IOException("reset"))
        assertEquals(1, shutdowns)
    }
}
