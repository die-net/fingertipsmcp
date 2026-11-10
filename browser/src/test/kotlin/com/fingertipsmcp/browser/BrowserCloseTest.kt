package com.fingertipsmcp.browser

import com.fingertipsmcp.browser.BrowserServiceTest.Companion.CHROME
import com.fingertipsmcp.browser.BrowserServiceTest.Companion.TAB
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Closing a tab returns once Chrome has destroyed it, not when Chrome answers the close. */
class BrowserCloseTest {
    private val harness = BrowserServiceTest()

    @Test
    fun `closes a tab`() {
        val service = harness.service(harness.closes(), sockets = CHROME)
        val result = harness.virtual { service.closeTab(TAB) }
        assertTrue(result.ok)
        assertEquals("cdp", result.backend)
        assertNull(result.message)
    }

    @Test
    @Suppress("MissingUseCall") // The service closes each socket it opens.
    fun `close waits for chrome`() {
        // Chrome answers Target.closeTarget before it destroys the tab, and a list of tabs taken
        // in between still holds it.
        val browser = harness.socket(closeAnswered())
        val service = harness.service(byteArrayOf(), sockets = CHROME, open = { browser })
        val result =
            harness.answering(WAIT_MS, { service.closeTab(TAB) }) {
                browser.send(harness.destroyed())
            }
        assertTrue(result.ok)
        assertNull(result.message)
        assertEquals(WAIT_MS, harness.clock.now)
    }

    @Test
    fun `a slow close says so`() {
        val service = harness.service(closeAnswered(), sockets = CHROME)
        val result = harness.virtual { service.closeTab(TAB) }
        assertTrue(result.ok)
        assertTrue(result.message.orEmpty().startsWith("Chrome is closing the tab"))
        assertEquals(CLOSE_MS, harness.clock.now)
    }

    /** Chrome answering that it will close a tab, before it destroys it. */
    private fun closeAnswered(): ByteArray =
        harness.switched() +
            harness.serverTextFrame("""{"id":1,"result":{}}""") +
            harness.serverTextFrame("""{"id":2,"result":{"success":true}}""")

    private companion object {
        /** How long Chrome takes to destroy the tab after answering that it will close it. */
        const val WAIT_MS = 1_000L
    }
}

/**
 * A browser socket's replies to closing the tab [id]: Chrome answers that it will, then destroys
 * the tab.
 */
internal fun BrowserServiceTest.closes(id: String = "abc"): ByteArray =
    switched() +
        serverTextFrame("""{"id":1,"result":{}}""") +
        serverTextFrame("""{"id":2,"result":{"success":true}}""") +
        destroyed(id)
