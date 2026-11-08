package com.fingertipsmcp.chrome

import com.fingertipsmcp.browser.ConnectionEndpoints
import com.fingertipsmcp.core.BrowserService
import com.fingertipsmcp.model.ApiException
import com.fingertipsmcp.model.Scope
import com.fingertipsmcp.model.WaitCondition
import com.fingertipsmcp.model.WaitRequest
import java.lang.reflect.Proxy
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class BrowserWaitsTest {
    private val waits =
        BrowserWaits(
            browser =
                Proxy.newProxyInstance(
                    BrowserService::class.java.classLoader,
                    arrayOf(BrowserService::class.java),
                ) { _, _, _ ->
                    error("not called")
                } as BrowserService,
            devtools = ConnectionEndpoints(connection = { error("not called") }),
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
