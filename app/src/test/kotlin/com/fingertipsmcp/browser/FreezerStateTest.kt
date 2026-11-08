package com.fingertipsmcp.browser

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class FreezerStateTest {
    private fun dumpsys(vararg lines: String) = lines.joinToString(System.lineSeparator())

    @Test
    fun `reads use_freezer`() {
        val on = dumpsys("Freezer settings:", "  use_freezer=true", "  mFreezerDebounceTimeout=10")
        assertEquals(true, freezerOn(dumpsys("ACTIVITY MANAGER SETTINGS", on)))
        assertEquals(false, freezerOn(on.replace("true", "false")))
    }

    @Test
    fun `older android has none`() {
        assertEquals(null, freezerOn(dumpsys("ACTIVITY MANAGER SETTINGS", "  max_cached=32")))
    }
}
