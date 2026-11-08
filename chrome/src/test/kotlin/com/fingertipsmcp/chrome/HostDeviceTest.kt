package com.fingertipsmcp.chrome

import com.fingertipsmcp.core.ApiJson
import com.fingertipsmcp.model.DeviceInfo
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HostDeviceTest {
    /** A desktop has no Android state, helper, or Android mechanisms, so it reports none. */
    @Test
    fun `reports no phone`() = runTest {
        BrowserSupervisor(backgroundScope, StandardTestDispatcher(testScheduler)) {
                error("not started in this test")
            }
            .use { supervisor ->
                val device = HostDevice(supervisor, "1.0", osName = "Mac OS X", osVersion = "15.0")
                val json =
                    ApiJson.encodeToJsonElement(DeviceInfo.serializer(), device.info()).jsonObject
                for (phoneOnly in listOf("sdkInt", "state", "helper", "uiAutomationConnected")) {
                    assertFalse(phoneOnly in json, "$phoneOnly in $json")
                }
                assertFalse("chromeKept" in json, json.toString())
                assertEquals("Mac OS X", json["manufacturer"]!!.jsonPrimitive.content)
                val capabilities = json["capabilities"]!!.jsonObject
                assertTrue(capabilities["browser.cdp"]!!.jsonPrimitive.boolean)
                for (mechanism in
                    listOf("screenshot.helper", "stream.video.mirror", "stream.audio.submix")) {
                    assertFalse(capabilities[mechanism]!!.jsonPrimitive.boolean, mechanism)
                }
            }
    }
}
