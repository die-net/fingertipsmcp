package com.fingertipsmcp.chrome

import com.fingertipsmcp.browser.CdpBrowserConnection
import com.fingertipsmcp.model.ApiException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

@OptIn(ExperimentalCoroutinesApi::class) // runCurrent runs what is due on the virtual clock.
class ViewerTargetsTest {
    /** A tab Chrome destroys is forgotten, so its page is not captured through a stale tab id. */
    @Test
    fun `a destroyed tab is forgotten`() = runTest {
        FakeChrome().use { chrome ->
            CdpBrowserConnection(chrome, StandardTestDispatcher(testScheduler)).use { connection ->
                connection.start()
                ViewerTargets.open(connection, backgroundScope).use { targets ->
                    assertEquals("TAB", targets.tabOf("PAGE"))
                    chrome.destroyed("TAB")
                    runCurrent()
                    val error = assertThrows<ApiException> { targets.tabOf("PAGE") }
                    assertEquals("not_found", error.error)
                }
            }
        }
    }
}
