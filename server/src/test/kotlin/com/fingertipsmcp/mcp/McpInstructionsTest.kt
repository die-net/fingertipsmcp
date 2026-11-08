package com.fingertipsmcp.mcp

import com.fingertipsmcp.model.Scope
import com.fingertipsmcp.model.openapi.ApiGroup
import com.fingertipsmcp.server.Product
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class McpInstructionsTest {
    @Test
    fun `browser work comes first`() {
        val text = phone(setOf(Scope.OBSERVE, Scope.CONTROL, Scope.BROWSER))
        val background = text.indexOf("Start browser work in the background")
        assertTrue(background >= 0, text)
        assertTrue(background < text.indexOf("screen_busy"), text)
    }

    @Test
    fun `no browser lines without scope`() {
        val text = phone(setOf(Scope.OBSERVE, Scope.CONTROL))
        assertFalse(text.contains("browser_"), text)
        assertFalse(text.contains("browser work"), text)
        assertTrue(text.contains("screen_release"), text)
    }

    private fun phone(scopes: Set<Scope>) =
        mcpInstructions(scopes, Product.ANDROID, ApiGroup.entries.toSet())
}
