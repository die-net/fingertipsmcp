package com.fingertipsmcp.helper

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class PackageUidTest {
    @Test
    fun `reads the package uid`() {
        val output = "package:com.fingertipsmcp uid:10213\n"
        assertEquals(10_213, parsePackageUid(output, "com.fingertipsmcp"))
    }

    @Test
    fun `ignores a longer name`() {
        val output =
            listOf(
                    "package:com.fingertipsmcp.helper uid:10001",
                    "package:com.fingertipsmcp uid:10213",
                    "package:other uid:10002",
                )
                .joinToString(separator = System.lineSeparator())
        assertEquals(10_213, parsePackageUid(output, "com.fingertipsmcp"))
    }

    @Test
    fun `ignores a missing uid`() {
        assertNull(parsePackageUid("package:com.fingertipsmcp\n", "com.fingertipsmcp"))
        assertNull(parsePackageUid("", "com.fingertipsmcp"))
        assertNull(parsePackageUid("package:com.fingertipsmcp uid:nope\n", "com.fingertipsmcp"))
    }
}
