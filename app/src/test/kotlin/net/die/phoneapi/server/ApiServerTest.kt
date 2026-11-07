package net.die.phoneapi.server

import io.ktor.server.application.Application
import java.io.IOException
import kotlinx.coroutines.isActive
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/** Runs Ktor's server and the real engine, with a socket whose name is always taken. */
class ApiServerTest {
    private val applications = mutableListOf<Application>()
    private val server = ApiServer("phoneapi", StandardTestDispatcher()) { applications += this }

    @Test
    fun `a failed bind ends the app`() {
        assertThrows(IOException::class.java) { server.start() }
        assertFalse(applications.single().isActive)
    }

    /** ServerController relies on this: start again at the next acquire, not "already running". */
    @Test
    fun `a failed bind can be retried`() {
        repeat(2) { assertThrows(IOException::class.java) { server.start() } }
        assertEquals(2, applications.size)
        assertFalse(applications.any { it.isActive })
    }
}
