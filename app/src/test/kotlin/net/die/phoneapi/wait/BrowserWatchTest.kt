package net.die.phoneapi.wait

import kotlinx.coroutines.withTimeout
import net.die.phoneapi.browser.BrowserServiceTest
import net.die.phoneapi.browser.CHROME_SOCKET
import net.die.phoneapi.browser.KeyedSocket
import net.die.phoneapi.model.BrowserTarget
import net.die.phoneapi.model.WaitCondition
import net.die.phoneapi.server.FakeBrowser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Browser waits over a fake DevTools page that answers only what the watch has sent. */
@Suppress("MissingUseCall") // The watch closes each socket it opens.
class BrowserWatchTest {
    private val harness = BrowserServiceTest()
    private val clock = harness.clock
    private val browser =
        object : FakeBrowser() {
            override suspend fun targets() =
                listOf(
                    BrowserTarget(
                        id = TAB,
                        type = "page",
                        url = "http://a.test:8765/x",
                        socket = CHROME_SOCKET,
                    )
                )
        }
    private val dialog = WaitCondition.BrowserDialog(target = TAB)

    @Test
    fun `counts a dialog already open`() = clock.run {
        // A page held by a dialog answers nothing, so Page.enable never returns.
        val page = KeyedSocket(byteArrayOf(), clock)
        val hosts = ArrayList<String>()
        val watch =
            BrowserWatch(browser, clock, { _, _ -> page }) { host ->
                hosts += host
                true
            }
        val satisfied =
            withTimeout(WAIT_MS) {
                watch.open(listOf(dialog), timeoutMs = 60_000)
                watch.check(dialog).satisfied.also { watch.close() }
            }
        assertTrue(satisfied)
        assertEquals(listOf("a.test:8765"), hosts)
    }

    @Test
    fun `waits for chrome without one`() = clock.run {
        val page =
            KeyedSocket(
                harness.serverTextFrame("""{"id":1,"result":{}}""") +
                    harness.serverTextFrame(
                        """{"id":2,"result":{"frameTree":{"frame":{"id":"F"}}}}"""
                    ),
                clock,
            )
        val watch = BrowserWatch(browser, clock, { _, _ -> page }) { false }
        val satisfied =
            withTimeout(WAIT_MS) {
                watch.open(listOf(dialog), timeoutMs = 60_000)
                watch.check(dialog).satisfied.also { watch.close() }
            }
        assertFalse(satisfied)
    }

    @Test
    fun `lifecycle events are enabled`() = clock.run {
        val page =
            KeyedSocket(
                harness.serverTextFrame("""{"id":1,"result":{}}""") +
                    harness.serverTextFrame("""{"id":2,"result":{}}""") +
                    harness.serverTextFrame(
                        """{"id":3,"result":{"frameTree":{"frame":{"id":"F"}}}}"""
                    ),
                clock,
            )
        val load = WaitCondition.BrowserLifecycle(target = TAB, name = "load")
        val watch = BrowserWatch(browser, clock, { _, _ -> page }) { false }
        withTimeout(WAIT_MS) {
            watch.open(listOf(load), timeoutMs = 60_000)
            watch.check(load).also { watch.close() }
        }
        assertEquals(
            listOf("Page.enable", "Page.setLifecycleEventsEnabled", "Page.getFrameTree"),
            page.methods(),
        )
    }

    private companion object {
        const val TAB = "${CHROME_SOCKET}~T1"
        const val WAIT_MS = 5_000L
    }
}
