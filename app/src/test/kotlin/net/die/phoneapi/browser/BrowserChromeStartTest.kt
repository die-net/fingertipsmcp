package net.die.phoneapi.browser

import net.die.phoneapi.model.ApiException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Opening a tab while Chrome is not running starts it first. */
class BrowserChromeStartTest {
    private val harness = BrowserServiceTest()

    @Test
    @Suppress("MissingUseCall") // The service closes each socket it opens.
    fun `opening starts chrome`() {
        val shown = mutableListOf<String>()
        val sockets =
            ArrayDeque(listOf(harness.created(), harness.ownTarget(), harness.committedPage()))
        val service =
            harness.service(
                byteArrayOf(),
                sockets = "[]",
                showPackage = { shown += it },
                open = { harness.socket(sockets.removeFirst()) },
                // Chrome opens its socket once something has started it.
                listSockets = { if (shown.isEmpty()) "[]" else BrowserServiceTest.CHROME },
            )
        assertTrue(harness.virtual { service.chromeStopped() })
        val target = harness.virtual { service.openTab("https://example.com/") }
        assertEquals(listOf("com.android.chrome"), shown)
        assertEquals("chrome_devtools_remote~abc", target.id)
        assertEquals(true, target.tookScreen)
        assertFalse(harness.virtual { service.chromeStopped() })
    }

    @Test
    fun `chrome that never opens fails`() = harness.virtual {
        val shown = mutableListOf<String>()
        val service = harness.service(byteArrayOf(), sockets = "[]", showPackage = { shown += it })
        // Virtual time runs the ten seconds of polls.
        val error =
            try {
                service.openTab("https://example.com/")
                null
            } catch (e: ApiException) {
                e
            }
        assertEquals("browser_unavailable", error?.error)
        assertTrue(error?.message.orEmpty().contains("within 10 s of starting"), error?.message)
        assertEquals(listOf("com.android.chrome"), shown)
    }
}
