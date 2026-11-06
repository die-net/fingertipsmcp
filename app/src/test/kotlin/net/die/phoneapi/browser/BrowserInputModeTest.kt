package net.die.phoneapi.browser

import net.die.phoneapi.model.ActionResult
import net.die.phoneapi.model.ApiException
import net.die.phoneapi.model.BrowserInput
import net.die.phoneapi.model.BrowserReadRequest
import net.die.phoneapi.model.BrowserSwipeRequest
import net.die.phoneapi.model.BrowserTapRequest
import net.die.phoneapi.model.EvalRequest
import net.die.phoneapi.model.SwipeDirection
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Which input modes reach a page while Chrome is hidden, and how a stalled socket is reported. */
class BrowserInputModeTest {
    private val harness = BrowserServiceTest()

    @Test
    @Suppress("MissingUseCall") // The service closes each socket it opens.
    fun `cdp tap needs chrome on screen`() {
        var opens = 0
        var asked = ""
        val service =
            harness.service(
                byteArrayOf(),
                sockets = BrowserServiceTest.CHROME,
                open = {
                    opens++
                    harness.socket(byteArrayOf())
                },
                inFront = { pkg ->
                    asked = pkg
                    false
                },
            )
        val error =
            assertThrows(ApiException::class.java) {
                harness.virtual {
                    service.tap(
                        "chrome_devtools_remote~abc",
                        BrowserTapRequest(selector = "a", input = BrowserInput.CDP),
                    )
                }
            }
        assertEquals("not_visible", error.error)
        assertEquals("com.android.chrome", asked)
        assertEquals(0, opens)
    }

    @Test
    fun `cdp tap needs the tab showing`() {
        val service =
            harness.service(
                harness.switched() + harness.showing("hidden"),
                sockets = BrowserServiceTest.CHROME,
            )
        val error =
            assertThrows(ApiException::class.java) {
                harness.virtual {
                    service.tap(
                        "chrome_devtools_remote~abc",
                        BrowserTapRequest(selector = "a", input = BrowserInput.CDP),
                    )
                }
            }
        assertEquals("not_visible", error.error)
    }

    @Test
    @Suppress("MissingUseCall") // The service closes each socket it opens.
    fun `mouse tap works off screen`() {
        // Selector lookup, scroll, quads, and metrics, then mousePressed and mouseReleased.
        val frames =
            harness.switched() +
                harness.serverTextFrame("""{"id":1,"result":{"root":{"nodeId":1}}}""") +
                harness.serverTextFrame("""{"id":2,"result":{"nodeId":4}}""") +
                harness.serverTextFrame("""{"id":3,"result":{}}""") +
                harness.serverTextFrame(harness.quad(4)) +
                harness.serverTextFrame(harness.metrics(5)) +
                harness.serverTextFrame("""{"id":6,"result":{}}""") +
                harness.serverTextFrame("""{"id":7,"result":{}}""")
        var touched = false
        val service =
            harness.service(
                frames,
                sockets = BrowserServiceTest.CHROME,
                touch = { _, _ ->
                    touched = true
                    ActionResult(ok = false)
                },
                inFront = { false },
            )
        val result = harness.virtual {
            service.tap(
                "chrome_devtools_remote~abc",
                BrowserTapRequest(selector = "a", humanize = false, input = BrowserInput.CDP_MOUSE),
            )
        }
        assertTrue(result.ok)
        assertEquals("cdp-mouse", result.backend)
        assertFalse(touched)
        assertEquals(206.09226f, result.points.single().x, 0.02f)
    }

    @Test
    @Suppress("MissingUseCall") // The service closes each socket it opens.
    fun `mouse does not swipe`() {
        var opens = 0
        val service =
            harness.service(
                byteArrayOf(),
                sockets = BrowserServiceTest.CHROME,
                open = {
                    opens++
                    harness.socket(byteArrayOf())
                },
            )
        val error =
            assertThrows(ApiException::class.java) {
                harness.virtual {
                    service.swipe(
                        "chrome_devtools_remote~abc",
                        BrowserSwipeRequest(
                            direction = SwipeDirection.UP,
                            input = BrowserInput.CDP_MOUSE,
                        ),
                    )
                }
            }
        assertEquals(400, error.status)
        assertEquals(0, opens)
    }

    @Test
    @Suppress("MissingUseCall") // The service closes each socket it opens.
    fun `watchdog close is a timeout`() {
        val service =
            harness.service(
                byteArrayOf(),
                sockets = BrowserServiceTest.CHROME,
                open = { harness.socket(harness.switched(), timedOut = true) },
            )
        val error =
            assertThrows(ApiException::class.java) {
                harness.virtual {
                    service.evaluate("chrome_devtools_remote~abc", EvalRequest("1"))
                }
            }
        assertEquals("cdp_timeout", error.error)
    }

    @Test
    @Suppress("MissingUseCall") // The service closes each socket it opens.
    fun `a dialog explains a timeout`() {
        var opens = 0
        val service =
            harness.service(
                byteArrayOf(),
                sockets = BrowserServiceTest.CHROME,
                open = {
                    opens++
                    harness.socket(harness.switched(), timedOut = true)
                },
                pageDialog = { true },
            )
        val error =
            assertThrows(ApiException::class.java) {
                harness.virtual {
                    service.read("chrome_devtools_remote~abc", BrowserReadRequest())
                }
            }
        assertEquals("content_unavailable", error.error)
        assertTrue(error.message.orEmpty().contains("tap one of its buttons"))
        // A read is retried on a timeout, but not while a dialog holds the page.
        assertEquals(1, opens)
    }
}
