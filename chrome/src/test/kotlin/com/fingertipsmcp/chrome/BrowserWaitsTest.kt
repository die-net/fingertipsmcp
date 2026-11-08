package com.fingertipsmcp.chrome

import com.fingertipsmcp.browser.ConnectionEndpoints
import com.fingertipsmcp.model.ApiException
import com.fingertipsmcp.model.Scope
import com.fingertipsmcp.model.WaitCondition
import com.fingertipsmcp.model.WaitRequest
import com.fingertipsmcp.wait.TabBrowser
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class BrowserWaitsTest {
    private val waits =
        BrowserWaits(
            browser = TabBrowser(),
            // Both refusals come before the wait opens a page.
            devtools = ConnectionEndpoints(connection = { error("A refused wait opens no page") }),
            io = StandardTestDispatcher(),
        )

    @Test
    fun `refuses phone conditions`() {
        val error =
            assertThrows(ApiException::class.java) {
                runBlocking {
                    waits.wait(
                        WaitRequest(all = listOf(WaitCondition.Ime(visible = true))),
                        setOf(Scope.OBSERVE, Scope.BROWSER),
                    )
                }
            }
        assertEquals(400, error.status)
    }

    @Test
    fun `needs the browser scope`() {
        val error =
            assertThrows(ApiException::class.java) {
                runBlocking {
                    waits.wait(
                        WaitRequest(all = listOf(WaitCondition.BrowserSettled())),
                        setOf(Scope.OBSERVE),
                    )
                }
            }
        assertEquals(403, error.status)
    }
}
