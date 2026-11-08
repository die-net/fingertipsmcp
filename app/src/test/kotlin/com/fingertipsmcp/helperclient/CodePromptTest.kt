package com.fingertipsmcp.helperclient

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CodePromptTest {
    @Test
    fun `an unfound dialog asks quietly`() {
        val prompt = codePrompt(null)

        assertTrue("6-digit code" in prompt.text)
        assertFalse(prompt.alert)
    }

    @Test
    fun `a found dialog alerts`() {
        val prompt = codePrompt(37_000)

        assertTrue("6-digit code" in prompt.text)
        assertTrue(prompt.alert)
    }
}
