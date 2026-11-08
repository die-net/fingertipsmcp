package com.fingertipsmcp.mcp

import com.fingertipsmcp.model.Scope
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class McpInstructionsTest {
    @Test
    fun `browser work comes first`() {
        val text = mcpInstructions(setOf(Scope.OBSERVE, Scope.CONTROL, Scope.BROWSER))
        val background = text.indexOf("Start browser work in the background")
        assertTrue(background >= 0, text)
        assertTrue(background < text.indexOf("screen_busy"), text)
    }

    @Test
    fun `no browser lines without scope`() {
        val text = mcpInstructions(setOf(Scope.OBSERVE, Scope.CONTROL))
        assertFalse(text.contains("browser_"), text)
        assertFalse(text.contains("browser work"), text)
        assertTrue(text.contains("screen_release"), text)
    }
}
