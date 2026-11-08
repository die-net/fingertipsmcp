package com.fingertipsmcp.chrome

import com.fingertipsmcp.browser.ConnectionEndpoints
import com.fingertipsmcp.model.ApiException
import com.fingertipsmcp.wait.TabBrowser
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class TabScreenshotsTest {
    @Test
    fun `refuses a scale out of bounds`() {
        val shots =
            TabScreenshots(
                TabBrowser(),
                ConnectionEndpoints(connection = { error("A refused screenshot opens no page") }),
            )
        listOf(0.05f, 1.5f).forEach { scale ->
            val error = assertThrows(ApiException::class.java) { runBlocking { shots.png(scale) } }
            assertEquals(400, error.status, "$scale")
        }
    }
}
