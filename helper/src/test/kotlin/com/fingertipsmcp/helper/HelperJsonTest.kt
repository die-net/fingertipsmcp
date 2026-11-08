package com.fingertipsmcp.helper

import com.fingertipsmcp.model.DevtoolsSocketInfo
import kotlinx.serialization.builtins.ListSerializer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class HelperJsonTest {
    @Test
    fun `omits unknown socket owners`() {
        val json =
            HelperJson.encodeToString(
                ListSerializer(DevtoolsSocketInfo.serializer()),
                listOf(
                    DevtoolsSocketInfo("chrome_devtools_remote", pid = 4),
                    DevtoolsSocketInfo("webview_devtools_remote_9", 9, 10_100, "com.example"),
                ),
            )
        assertEquals(
            """[{"name":"chrome_devtools_remote","pid":4},""" +
                """{"name":"webview_devtools_remote_9","pid":9,"uid":10100,"package":"com.example"}]""",
            json,
        )
    }
}
