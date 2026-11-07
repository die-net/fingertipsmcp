package net.die.phoneapi

import kotlinx.coroutines.test.runTest
import net.die.phoneapi.apps.ActivityStart
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PutBackTest {
    @Test
    fun `goes home when the start fails`() = runTest {
        var home = 0
        putBack("com.x", { ActivityStart(code = 0) }) { home++ }
        assertEquals(0, home)
        // Already in front, so nothing new started, but it is back.
        putBack("com.x", { ActivityStart(code = 2) }) { home++ }
        assertEquals(0, home)
        putBack("com.x", { ActivityStart(code = -94, refusal = "Permission Denial") }) { home++ }
        assertEquals(1, home)
        putBack("com.x", { ActivityStart(code = -91) }) { home++ }
        assertEquals(2, home)
    }
}
