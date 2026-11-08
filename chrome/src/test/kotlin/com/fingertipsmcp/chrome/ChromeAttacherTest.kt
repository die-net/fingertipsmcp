package com.fingertipsmcp.chrome

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** A malformed `--attach` fails as a launch does, which the supervisor reports. */
@Suppress("MissingUseCall") // Each start fails, so nothing returns to close.
class ChromeAttacherTest {
    @Test
    fun `malformed attach fails launch`() = runTest {
        val io = StandardTestDispatcher(testScheduler)
        val error = assertThrows<LaunchException> { ChromeAttacher("http://[::1", io).start() }
        assertTrue(error.message.orEmpty().contains("not a valid address"), error.message)
    }

    @Test
    fun `portless attach fails launch`() = runTest {
        val io = StandardTestDispatcher(testScheduler)
        val error =
            assertThrows<LaunchException> {
                ChromeAttacher("ws://127.0.0.1/devtools/browser/x", io).start()
            }
        assertTrue(error.message.orEmpty().contains("No port"), error.message)
    }
}
