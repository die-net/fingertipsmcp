package com.fingertipsmcp.helperclient

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SecureSettingsGrantTest {
    @Test
    fun `an app without it asks`() {
        assertTrue(shouldGrantSecureSettings(sdk = 31, granted = false))
    }

    @Test
    fun `it asks only once`() {
        assertFalse(shouldGrantSecureSettings(sdk = 31, granted = true))
    }

    @Test
    fun `android 10 has no use for it`() {
        assertFalse(shouldGrantSecureSettings(sdk = 29, granted = false))
    }
}
