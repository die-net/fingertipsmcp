package net.die.phoneapi

import kotlinx.coroutines.test.runTest
import net.die.phoneapi.helperclient.ShellResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PutBackTest {
    @Test
    fun `goes home when am start fails`() = runTest {
        var home = 0
        // A component whose name holds "Error" started fine.
        val started = "Starting: Intent { cmp=com.x/.ErrorScreen }"
        putBack("com.x", { ShellResult(exit = 0, stdout = started) }) { home++ }
        assertEquals(0, home)
        // am exits 0 and reports the failure only on stderr.
        val refused = "java.lang.SecurityException: Permission Denial: starting Intent"
        putBack("com.x", { ShellResult(exit = 0, stderr = refused) }) { home++ }
        assertEquals(1, home)
        val unresolved = "Error: Activity not started, unable to resolve Intent"
        putBack("com.x", { ShellResult(exit = 0, stdout = unresolved) }) { home++ }
        assertEquals(2, home)
    }
}
