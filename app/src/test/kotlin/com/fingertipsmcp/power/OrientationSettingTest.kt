package com.fingertipsmcp.power

import com.fingertipsmcp.model.ScreenOrientation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class OrientationSettingTest {
    @Test
    fun `auto leaves rotation alone`() {
        val setting = orientationSetting(ScreenOrientation.AUTO, currentRotation = 1)
        assertEquals(1, setting.accelerometer)
        assertNull(setting.userRotation)
    }

    @Test
    fun `lock uses current rotation`() {
        val setting = orientationSetting(ScreenOrientation.LOCK, currentRotation = 1)
        assertEquals(0, setting.accelerometer)
        assertEquals(1, setting.userRotation)
        assertEquals("Locked at 90 degrees", setting.message)
    }

    @Test
    fun `degrees map to rotation`() {
        val setting = orientationSetting(ScreenOrientation.R270, currentRotation = 0)
        assertEquals(0, setting.accelerometer)
        assertEquals(3, setting.userRotation)
        assertEquals("Locked at 270 degrees", setting.message)
    }
}
