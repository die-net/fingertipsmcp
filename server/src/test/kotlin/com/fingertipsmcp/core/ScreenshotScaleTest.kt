package com.fingertipsmcp.core

import com.fingertipsmcp.model.ApiException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ScreenshotScaleTest {
    @Test
    fun `takes 0_1 to 1 only`() {
        listOf(0.1f, 0.5f, 1f).forEach(ScreenshotScale::require)
        listOf(0.05f, 0f, -1f, 1.01f).forEach { scale ->
            val error = assertThrows(ApiException::class.java) { ScreenshotScale.require(scale) }
            assertEquals(400, error.status, "$scale")
        }
    }
}
