package com.fingertipsmcp.apps

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ResumedActivityTest {
    @Test
    fun `reads android 11`() {
        val dumpsys =
            listOf(
                    "Display #0 (activities from top to bottom):",
                    "  Stack #41: type=home mode=fullscreen",
                    "    ResumedActivity: ActivityRecord{ed6f195 u0 " +
                        "com.motorola.launcher3/com.android.launcher3.CustomizationPanelLauncher t41}",
                )
                .joinToString("\n")
        assertEquals("com.motorola.launcher3", resumedPackage(dumpsys))
    }

    @Test
    fun `reads later versions`() {
        val dumpsys =
            "  topResumedActivity=ActivityRecord{e6614cc u0 com.android.chrome/com.google.android.apps.chrome.Main t68}"
        assertEquals("com.android.chrome", resumedPackage(dumpsys))
    }

    @Test
    fun `nothing resumed`() {
        assertNull(resumedPackage("Display #0 (activities from top to bottom):"))
    }
}
