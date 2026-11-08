package com.fingertipsmcp.browser

import com.fingertipsmcp.model.ActionResult
import com.fingertipsmcp.model.ApiException
import com.fingertipsmcp.model.BrowserInput
import com.fingertipsmcp.model.BrowserKeyRequest
import com.fingertipsmcp.model.BrowserReadRequest
import com.fingertipsmcp.model.BrowserSwipeRequest
import com.fingertipsmcp.model.BrowserTapRequest
import com.fingertipsmcp.model.BrowserTextRequest
import com.fingertipsmcp.model.EvalRequest
import com.fingertipsmcp.model.SwipeDirection
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Which input modes reach a page while Chrome is hidden, and how a stalled socket is reported. */
class BrowserInputModeTest {
    private val harness = BrowserServiceTest()

    private companion object {
        const val MOUSE = "Input.dispatchMouseEvent"
        const val CAPTURE = "Page.captureScreenshot"

        /** More rounds of press and check than fit in the time Chrome gets to end the hold. */
        const val DROPPED_ROUNDS = 60
    }

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
        // The box, a page that has drawn, then mousePressed and mouseReleased.
        val frames =
            box() +
                harness.pageSays(6, false) +
                harness.serverTextFrame("""{"id":9,"result":{}}""") +
                harness.serverTextFrame("""{"id":10,"result":{}}""")
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
    @Suppress("MissingUseCall") // The service closes the socket it opens.
    fun `a drawn page is not captured`() {
        val page =
            harness.socket(
                box() +
                    harness.pageSays(6, false) +
                    harness.serverTextFrame("""{"id":9,"result":{}}""") +
                    harness.serverTextFrame("""{"id":10,"result":{}}""")
            )
        assertEquals("cdp-mouse", mouseTap(page).backend)
        assertEquals(2, page.methods().count { it == MOUSE })
        assertFalse(CAPTURE in page.methods())
    }

    @Test
    @Suppress("MissingUseCall") // The service closes the socket it opens.
    fun `a held tap lands`() {
        // Chrome answers each press and drops it, as it does until the hold ends. The capture at
        // id 11 is never answered, as on a tab Chrome has not drawn.
        val page =
            harness.socket(
                box() +
                    harness.pageSays(6, true) +
                    replied(9) +
                    harness.answer(10, false) +
                    replied(12) +
                    harness.answer(13, false) +
                    replied(14) +
                    harness.answer(15, true) +
                    replied(16)
            )
        assertTrue(mouseTap(page).ok)
        val sent = page.methods()
        assertEquals(1, sent.count { it == CAPTURE })
        assertEquals(MOUSE, sent[sent.indexOf(CAPTURE) - 2])
        assertEquals(4, sent.count { it == MOUSE })
    }

    @Test
    @Suppress("MissingUseCall") // The service closes the socket it opens.
    fun `a first press is not repeated`() {
        // A page with no paint that takes the press, as about:blank does, gets no capture.
        val page =
            harness.socket(
                box() +
                    harness.pageSays(6, true) +
                    replied(9) +
                    harness.answer(10, true) +
                    replied(11)
            )
        assertTrue(mouseTap(page).ok)
        assertFalse(CAPTURE in page.methods())
        assertEquals(2, page.methods().count { it == MOUSE })
    }

    @Test
    @Suppress("MissingUseCall") // The service closes the socket it opens.
    fun `a press in a frame lands`() {
        // Chrome refuses a world for the frame X. The top frame's listener hears nothing, and the
        // one in frame C does, so the press is not repeated.
        val page =
            harness.socket(
                box() +
                    harness.serverTextFrame(
                        """{"id":6,"result":{"frameTree":{"frame":{"id":"F"},"childFrames":""" +
                            """[{"frame":{"id":"X"}},{"frame":{"id":"C"}}]}}}"""
                    ) +
                    harness.serverTextFrame("""{"id":7,"result":{"executionContextId":7}}""") +
                    harness.answer(8, true) +
                    harness.serverTextFrame(
                        """{"id":9,"error":{"code":-32000,"message":"No frame for given id"}}"""
                    ) +
                    harness.serverTextFrame("""{"id":10,"result":{"executionContextId":8}}""") +
                    harness.answer(11, true) +
                    replied(12) +
                    harness.answer(13, false) +
                    harness.answer(14, true) +
                    replied(15)
            )
        assertTrue(mouseTap(page).ok)
        assertFalse(CAPTURE in page.methods())
        assertEquals(2, page.methods().count { it == MOUSE })
    }

    @Test
    @Suppress("MissingUseCall") // The service closes the socket it opens.
    fun `a dropped tap says so`() {
        val page = harness.socket(dropping(box() + harness.pageSays(6, true), firstId = 9))
        val error = assertThrows(ApiException::class.java) { mouseTap(page) }
        assertEquals(409, error.status)
        assertEquals("not_visible", error.error)
        assertTrue(CAPTURE in page.methods())
    }

    @Test
    @Suppress("MissingUseCall") // The service closes the socket it opens.
    fun `a held key lands`() {
        val page =
            harness.socket(
                harness.switched() +
                    harness.pageSays(1, true) +
                    replied(4) +
                    harness.answer(5, false) +
                    replied(7) +
                    harness.answer(8, true) +
                    replied(9)
            )
        assertTrue(cdpKey(page, "ENTER").ok)
        assertEquals(1, page.methods().count { it == CAPTURE })
        assertEquals(3, page.methods().count { it == "Input.dispatchKeyEvent" })
    }

    @Test
    @Suppress("MissingUseCall") // The service closes the socket it opens.
    fun `a dropped escape says so`() {
        // Escape gives a page no user activation, so the listener is what shows it landed.
        val page = harness.socket(dropping(harness.switched() + harness.pageSays(1, true), 4))
        val error = assertThrows(ApiException::class.java) { cdpKey(page, "ESCAPE") }
        assertEquals("not_visible", error.error)
    }

    @Test
    @Suppress("MissingUseCall") // The service closes the socket it opens.
    fun `a dropped clear says so`() {
        // The select-all that clears the field is a key, which Chrome drops, unlike inserted text.
        val page = harness.socket(dropping(harness.switched() + harness.pageSays(1, true), 4))
        val service =
            harness.service(byteArrayOf(), sockets = BrowserServiceTest.CHROME, open = { page })
        val error =
            assertThrows(ApiException::class.java) {
                harness.virtual {
                    service.text(
                        BrowserServiceTest.TAB,
                        BrowserTextRequest(text = "hi", clear = true, input = BrowserInput.CDP),
                    )
                }
            }
        assertEquals("not_visible", error.error)
        assertFalse("Input.insertText" in page.methods())
    }

    private fun replied(id: Int): ByteArray = harness.serverTextFrame("""{"id":$id,"result":{}}""")

    /**
     * [start] and then Chrome answering every press from [firstId] on and dropping it: the first
     * press and its check, the capture, and more presses and checks than fit in the time allowed.
     */
    private fun dropping(start: ByteArray, firstId: Int): ByteArray =
        (0 until DROPPED_ROUNDS).fold(
            start + replied(firstId) + harness.answer(firstId + 1, false)
        ) { frames, round ->
            val id = firstId + 3 + round * 2
            frames + replied(id) + harness.answer(id + 1, false)
        }

    private fun mouseTap(page: StallingSocket): ActionResult {
        val service =
            harness.service(
                byteArrayOf(),
                sockets = BrowserServiceTest.CHROME,
                open = { page },
                inFront = { false },
            )
        return harness.virtual {
            service.tap(
                BrowserServiceTest.TAB,
                BrowserTapRequest(selector = "a", humanize = false, input = BrowserInput.CDP_MOUSE),
            )
        }
    }

    private fun cdpKey(page: StallingSocket, key: String): ActionResult {
        val service =
            harness.service(byteArrayOf(), sockets = BrowserServiceTest.CHROME, open = { page })
        return harness.virtual {
            service.key(
                BrowserServiceTest.TAB,
                BrowserKeyRequest(key = key, input = BrowserInput.CDP),
            )
        }
    }

    /** Selector lookup, scroll, quads, and metrics for a tap. */
    private fun box(): ByteArray =
        harness.switched() +
            harness.serverTextFrame("""{"id":1,"result":{"root":{"nodeId":1}}}""") +
            harness.serverTextFrame("""{"id":2,"result":{"nodeId":4}}""") +
            harness.serverTextFrame("""{"id":3,"result":{}}""") +
            harness.serverTextFrame(harness.quad(4)) +
            harness.serverTextFrame(harness.metrics(5))

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

/** Replies to a check of the page that answers true or false: frame, world, and [answer]. */
internal fun BrowserServiceTest.pageSays(firstId: Int, answer: Boolean): ByteArray =
    serverTextFrame("""{"id":$firstId,"result":{"frameTree":{"frame":{"id":"F"}}}}""") +
        serverTextFrame("""{"id":${firstId + 1},"result":{"executionContextId":7}}""") +
        answer(firstId + 2, answer)

/** Chrome's reply [id] to a read of the page in a world it already has. */
internal fun BrowserServiceTest.answer(id: Int, value: Boolean): ByteArray =
    serverTextFrame("""{"id":$id,"result":{"result":{"type":"boolean","value":$value}}}""")
