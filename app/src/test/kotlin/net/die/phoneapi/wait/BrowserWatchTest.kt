package net.die.phoneapi.wait

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
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

/** A `browser.dialog` wait on a page that already shows a dialog. */
// Scripted sockets are read on a real dispatcher, and the watch closes each socket it opens.
@Suppress("InjectDispatcher", "MissingUseCall")
class BrowserWatchTest {
    private val harness = BrowserServiceTest()
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
    fun `counts a dialog already open`() = runBlocking {
        // A page held by a dialog answers nothing, so Page.enable never returns.
        val page = KeyedSocket(byteArrayOf())
        val hosts = ArrayList<String>()
        val watch =
            BrowserWatch(browser, Dispatchers.IO, { _, _ -> page }) { host ->
                hosts += host
                true
            }
        // The watch's readers run in the caller's scope until close, which the timeout covers.
        val satisfied =
            withTimeout(WAIT_MS) {
                watch.open(listOf(dialog), timeoutMs = 60_000)
                watch.check(dialog).satisfied.also { watch.close() }
            }
        assertTrue(satisfied)
        assertEquals(listOf("a.test:8765"), hosts)
    }

    @Test
    fun `waits for chrome without one`() = runBlocking {
        val page =
            KeyedSocket(
                harness.serverTextFrame("""{"id":1,"result":{}}""") +
                    harness.serverTextFrame(
                        """{"id":2,"result":{"frameTree":{"frame":{"id":"F"}}}}"""
                    )
            )
        val watch = BrowserWatch(browser, Dispatchers.IO, { _, _ -> page }) { false }
        val satisfied =
            withTimeout(WAIT_MS) {
                watch.open(listOf(dialog), timeoutMs = 60_000)
                watch.check(dialog).satisfied.also { watch.close() }
            }
        assertFalse(satisfied)
    }

    @Test
    fun `lifecycle waits turn the events on`() = runBlocking {
        val page =
            KeyedSocket(
                harness.serverTextFrame("""{"id":1,"result":{}}""") +
                    harness.serverTextFrame("""{"id":2,"result":{}}""") +
                    harness.serverTextFrame(
                        """{"id":3,"result":{"frameTree":{"frame":{"id":"F"}}}}"""
                    )
            )
        val load = WaitCondition.BrowserLifecycle(target = TAB, name = "load")
        val watch = BrowserWatch(browser, Dispatchers.IO, { _, _ -> page }) { false }
        withTimeout(WAIT_MS) {
            watch.open(listOf(load), timeoutMs = 60_000)
            watch.check(load).also { watch.close() }
        }
        assertEquals(
            listOf("Page.enable", "Page.setLifecycleEventsEnabled", "Page.getFrameTree"),
            sentMethods(page.output.toByteArray()),
        )
    }

    /** The CDP methods in a client's masked WebSocket frames, after its handshake. */
    private fun sentMethods(bytes: ByteArray): List<String> {
        var at = String(bytes, Charsets.ISO_8859_1).indexOf("\r\n\r\n") + HEADER_END
        val methods = mutableListOf<String>()
        while (at + 2 <= bytes.size) {
            var length = bytes[at + 1].toInt() and LENGTH_BITS
            var header = 2
            if (length == LENGTH_16) {
                length = (bytes[at + 2].toUByte().toInt() shl 8) or bytes[at + 3].toUByte().toInt()
                header = 4
            }
            val start = at + header + MASK_BYTES
            val payload =
                ByteArray(length) { i ->
                    val mask = bytes[at + header + i % MASK_BYTES].toInt()
                    (bytes[start + i].toInt() xor mask).toByte()
                }
            METHOD.find(payload.decodeToString())?.let { methods += it.groupValues[1] }
            at = start + length
        }
        return methods
    }

    private companion object {
        const val TAB = "${CHROME_SOCKET}~T1"
        const val WAIT_MS = 5_000L
        const val HEADER_END = 4
        const val LENGTH_BITS = 0x7f
        const val LENGTH_16 = 126
        const val MASK_BYTES = 4
        val METHOD = Regex(""""method":"([^"]+)"""")
    }
}
