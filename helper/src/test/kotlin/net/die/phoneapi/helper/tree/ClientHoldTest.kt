package net.die.phoneapi.helper.tree

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ClientHoldTest {
    private var client: Any? = "first app"
    private val hold = ClientHold { client }

    @Test
    fun `a hold and its release`() {
        hold.set(true)
        assertTrue(hold.held)
        hold.set(false)
        assertFalse(hold.held)
    }

    @Test
    fun `a dead app's hold ends`() {
        hold.set(true)
        client = "restarted app"
        assertFalse(hold.held)
    }

    @Test
    fun `a restarted app can release`() {
        // The first app dies holding; the next one holds and then releases.
        hold.set(true)
        client = "restarted app"
        hold.set(true)
        hold.set(false)
        assertFalse(hold.held)
    }

    @Test
    fun `no client holds nothing`() {
        client = null
        hold.set(true)
        assertFalse(hold.held)
    }
}
