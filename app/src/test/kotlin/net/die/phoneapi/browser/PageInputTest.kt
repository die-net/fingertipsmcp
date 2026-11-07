package net.die.phoneapi.browser

import net.die.phoneapi.model.ActionResult
import net.die.phoneapi.model.ApiException
import net.die.phoneapi.model.BrowserInput
import net.die.phoneapi.model.BrowserKeyRequest
import net.die.phoneapi.model.BrowserSwipeRequest
import net.die.phoneapi.model.BrowserTextRequest
import net.die.phoneapi.model.Point
import net.die.phoneapi.model.Rect
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PageInputTest {
    private val harness = BrowserServiceTest()

    private companion object {
        /** A read of the page, such as visibilityState before a cdp touch. */
        val SHOWING = listOf("Page.getFrameTree", "Page.createIsolatedWorld", "Runtime.evaluate")
    }

    @Test
    @Suppress("MissingUseCall") // The service closes the socket it opens.
    fun `cdp swipe stays in the back`() {
        val frames =
            harness.switched() +
                harness.showing() +
                harness.serverTextFrame(harness.metrics(4)) +
                harness.serverTextFrame("""{"id":5,"result":{}}""") +
                harness.serverTextFrame("""{"id":6,"result":{}}""") +
                harness.serverTextFrame("""{"id":7,"result":{}}""")
        var swiped = false
        val page = harness.socket(frames)
        val service =
            harness.service(
                frames,
                sockets = BrowserServiceTest.CHROME,
                swipe = { _, _, _, _ ->
                    swiped = true
                    ActionResult(ok = false, backend = "inject")
                },
                open = { page },
            )
        val result = harness.virtual {
            service.swipe(
                "chrome_devtools_remote~abc",
                BrowserSwipeRequest(
                    from = Point(100f, 200f),
                    to = Point(300f, 400f),
                    durationMs = 20,
                    humanize = false,
                    input = BrowserInput.CDP,
                ),
            )
        }
        assertTrue(result.ok)
        assertEquals("cdp", result.backend)
        assertFalse(swiped)
        assertEquals(100f, result.points.first().x, 0.01f)
        assertEquals(300f, result.points.last().x, 0.01f)
        assertEquals(
            SHOWING +
                listOf(
                    "Page.getLayoutMetrics",
                    "Input.dispatchTouchEvent",
                    "Input.dispatchTouchEvent",
                    "Input.dispatchTouchEvent",
                ),
            page.methods(),
        )
    }

    @Test
    @Suppress("MissingUseCall") // The service closes the socket it opens.
    fun `cdp swipe needs touch axes`() {
        // Without the helper there are no axes to size the contact by, so nothing is sent.
        val page =
            harness.socket(
                harness.switched() + harness.showing() + harness.serverTextFrame(harness.metrics(4))
            )
        val service =
            harness.service(
                byteArrayOf(),
                sockets = BrowserServiceTest.CHROME,
                open = { page },
                touchscreen = { throw ApiException.helperUnavailable() },
            )
        val error = harness.virtual {
            harness.failure {
                service.swipe(
                    "chrome_devtools_remote~abc",
                    BrowserSwipeRequest(
                        from = Point(100f, 200f),
                        to = Point(300f, 400f),
                        input = BrowserInput.CDP,
                    ),
                )
            }
        }
        assertEquals("helper_unavailable", error)
        assertFalse("Input.dispatchTouchEvent" in page.methods())
    }

    @Test
    @Suppress("MissingUseCall") // The service closes the socket it opens.
    fun `touch swipe maps onto screen`() {
        val frames =
            harness.switched() +
                harness.serverTextFrame("""{"id":1,"result":{}}""") +
                harness.visibility(2, "visible") +
                harness.serverTextFrame(harness.metrics(5))
        var from = Point(0f, 0f)
        var to = Point(0f, 0f)
        var area: Rect? = null
        val service =
            harness.service(
                frames,
                sockets = BrowserServiceTest.CHROME,
                content = { Rect(0, 283, 1080, 2339) },
                swipe = { start, end, _, within ->
                    from = start
                    to = end
                    area = within
                    ActionResult(ok = true, backend = "inject", points = listOf(start, end))
                },
            )
        val result = harness.virtual {
            service.swipe(
                "chrome_devtools_remote~abc",
                BrowserSwipeRequest(
                    from = Point(100f, 200f),
                    to = Point(300f, 400f),
                    humanize = false,
                ),
            )
        }
        assertTrue(result.ok)
        assertEquals("inject", result.backend)
        assertEquals(262.50f, from.x, 0.05f)
        assertEquals(808.00f, from.y, 0.05f)
        assertEquals(787.50f, to.x, 0.05f)
        assertEquals(1333.00f, to.y, 0.05f)
        assertEquals(Rect(0, 283, 1080, 2339), area)
    }

    @Test
    @Suppress("MissingUseCall") // The service closes the socket it opens.
    fun `touch swipe stays on the page`() {
        val frames =
            harness.switched() +
                harness.serverTextFrame("""{"id":1,"result":{}}""") +
                harness.visibility(2, "visible") +
                harness.serverTextFrame(harness.metrics(5))
        var swiped = false
        val service =
            harness.service(
                frames,
                sockets = BrowserServiceTest.CHROME,
                content = { Rect(0, 283, 1080, 2339) },
                swipe = { _, _, _, _ ->
                    swiped = true
                    ActionResult(ok = true, backend = "inject")
                },
            )
        // From the page's top edge up past it, onto Chrome's toolbar and the status bar.
        val error =
            assertThrows(ApiException::class.java) {
                harness.virtual {
                    service.swipe(
                        "chrome_devtools_remote~abc",
                        BrowserSwipeRequest(
                            from = Point(100f, 10f),
                            to = Point(100f, -200f),
                            humanize = false,
                        ),
                    )
                }
            }
        assertEquals("offscreen", error.error)
        assertFalse(swiped)
    }

    @Test
    @Suppress("MissingUseCall") // The service closes the socket it opens.
    fun `cdp key stays in the back`() {
        val frames =
            harness.switched() +
                harness.pageSays(1, false) +
                harness.serverTextFrame("""{"id":4,"result":{}}""") +
                harness.serverTextFrame("""{"id":5,"result":{}}""")
        var pressed = false
        val page = harness.socket(frames)
        val service =
            harness.service(
                frames,
                sockets = BrowserServiceTest.CHROME,
                press = {
                    pressed = true
                    ActionResult(ok = false, backend = "inject")
                },
                open = { page },
            )
        val result = harness.virtual {
            service.key(
                "chrome_devtools_remote~abc",
                BrowserKeyRequest(key = "ENTER", input = BrowserInput.CDP),
            )
        }
        assertTrue(result.ok)
        assertEquals("cdp", result.backend)
        assertFalse(pressed)
        assertEquals(SHOWING + "Input.dispatchKeyEvent" + "Input.dispatchKeyEvent", page.methods())
    }

    @Test
    fun `cdp rejects a device key`() {
        val service = harness.service(byteArrayOf(), sockets = BrowserServiceTest.CHROME)
        val error =
            assertThrows(ApiException::class.java) {
                harness.virtual {
                    service.key(
                        "chrome_devtools_remote~abc",
                        BrowserKeyRequest(key = "HOME", input = BrowserInput.CDP),
                    )
                }
            }
        assertEquals(400, error.status)
    }

    @Test
    @Suppress("MissingUseCall") // The service closes the socket it opens.
    fun `cdp text stays in the back`() {
        val frames = harness.switched() + harness.serverTextFrame("""{"id":1,"result":{}}""")
        var typed = false
        val page = harness.socket(frames)
        val service =
            harness.service(
                frames,
                sockets = BrowserServiceTest.CHROME,
                type = {
                    typed = true
                    ActionResult(ok = false, backend = "inject")
                },
                open = { page },
            )
        val result = harness.virtual {
            service.text(
                "chrome_devtools_remote~abc",
                BrowserTextRequest(text = "hi", input = BrowserInput.CDP),
            )
        }
        assertTrue(result.ok)
        assertEquals("cdp", result.backend)
        assertFalse(typed)
        assertEquals(listOf("Input.insertText"), page.methods())
    }
}
