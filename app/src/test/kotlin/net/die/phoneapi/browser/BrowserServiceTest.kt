package net.die.phoneapi.browser

import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import net.die.phoneapi.helper.TouchscreenInfo
import net.die.phoneapi.input.SwipeSpec
import net.die.phoneapi.input.fallbackTouchscreen
import net.die.phoneapi.model.ActionResult
import net.die.phoneapi.model.ApiException
import net.die.phoneapi.model.BrowserInput
import net.die.phoneapi.model.BrowserTapRequest
import net.die.phoneapi.model.ConsoleRequest
import net.die.phoneapi.model.KeyRequest
import net.die.phoneapi.model.Point
import net.die.phoneapi.model.Rect
import net.die.phoneapi.model.TextRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BrowserServiceTest {
    /**
     * The dispatcher and clock of [service]. A [StallingSocket] made by [socket] parks its reads
     * there, so time moves only while the service waits for something.
     */
    internal val clock = VirtualIo()

    @Test
    fun `lists a page`() {
        val body =
            targetsReply(
                """{"targetId":"abc","type":"page","title":"Hi","url":"https://example.com/"},""" +
                    """{"targetId":"pop","type":"page","url":"https://example.com/2","openerId":"abc"},""" +
                    """{"targetId":"w","type":"service_worker","url":"https://example.com/sw.js"}"""
            )
        val service = service(body, sockets = CHROME)
        val targets = virtual { service.targets() }
        assertEquals(2, targets.size)
        assertEquals("chrome_devtools_remote~abc", targets[0].id)
        assertEquals("Hi", targets[0].title)
        assertEquals("com.android.chrome", targets[0].packageName)
        assertEquals("chrome_devtools_remote~abc", targets[1].openerId)
    }

    @Test
    @Suppress("MissingUseCall") // The service closes each socket it opens.
    fun `opens a tab`() {
        val sockets = ArrayDeque(listOf(created(), committedPage()))
        val service =
            service(byteArrayOf(), sockets = CHROME, open = { socket(sockets.removeFirst()) })
        val target = virtual { service.openTab("https://example.com/") }
        assertEquals("chrome_devtools_remote~abc", target.id)
        assertEquals("https://example.com/", target.url)
        assertEquals(true, target.tookScreen)
    }

    @Test
    @Suppress("MissingUseCall") // The service closes each socket it opens.
    fun `opens a tab behind`() {
        val sockets = ArrayDeque(listOf(created(), committedPage()))
        val service =
            service(
                byteArrayOf(),
                sockets = CHROME,
                inFront = { false },
                open = { socket(sockets.removeFirst()) },
            )
        val target = virtual { service.openTab("https://example.com/") }
        assertEquals(false, target.tookScreen)
    }

    @Test
    fun `empty sockets name the gap`() {
        val service = service(byteArrayOf(), sockets = "[]")
        val error = assertThrows(ApiException::class.java) { virtual { service.targets() } }
        assertEquals("browser_unavailable", error.error)
        assertTrue(error.message.orEmpty().contains("starts Chrome"))
    }

    @Test
    fun `rejects a bad url`() {
        val service = service(byteArrayOf(), sockets = CHROME)
        val error =
            assertThrows(ApiException::class.java) {
                virtual { service.openTab("javascript:alert(1)") }
            }
        assertEquals(400, error.status)
    }

    @Test
    fun `reads an ax tree`() {
        val frames =
            serverTextFrame(
                """{"id":1,"result":{"frameTree":{"frame":{"url":"https://example.com/"}}}}"""
            ) +
                serverTextFrame("""{"id":2,"result":{}}""") +
                serverTextFrame(
                    """{"id":3,"result":{"nodes":[{"nodeId":"1","role":{"value":"RootWebArea"},"name":{"value":"Example"}}]}}"""
                )
        val service = service(switched() + frames, sockets = CHROME)
        val snapshot = virtual { service.snapshot("chrome_devtools_remote~abc") }
        assertEquals("https://example.com/", snapshot.url)
        assertEquals("Example", snapshot.title)
        assertTrue(snapshot.compact.contains("[1] RootWebArea"))
    }

    @Test
    @Suppress("MissingUseCall") // The service closes each socket it opens.
    fun `taps inside the link`() {
        val frames =
            switched() +
                serverTextFrame("""{"id":1,"result":{}}""") +
                visibility(2, "visible") +
                frameTree(5) +
                serverTextFrame("""{"id":6,"result":{}}""") +
                serverTextFrame(
                    """{"id":7,"result":{"quads":[[165.619049,453.68454,246.56548,453.68454,246.56548,479.27979,165.61905,479.27979]]}}"""
                ) +
                serverTextFrame(metrics(8))
        var touched = Rect(0, 0, 0, 0)
        val sockets = ArrayDeque(listOf(snapshotFrames(), frames))
        val service =
            service(
                frames,
                sockets = CHROME,
                content = { Rect(0, 283, 1080, 2339) },
                touch = { rect, _ ->
                    touched = rect
                    ActionResult(ok = true, backend = "inject")
                },
                open = { socket(sockets.removeFirst()) },
            )
        val result = virtual {
            service.snapshot(TAB)
            service.tap(
                "chrome_devtools_remote~abc",
                BrowserTapRequest(ref = "9", humanize = false),
            )
        }
        assertTrue(result.ok)
        assertEquals("inject", result.backend)
        assertEquals(Rect(435, 1474, 647, 1541), touched)
    }

    @Test
    @Suppress("MissingUseCall") // The service closes the socket it opens.
    fun `cdp tap leaves the tab behind`() {
        // ids 1-3 read visibilityState, 4 checks the snapshot's document is still there, and the
        // last two empty results are touchStart and touchEnd.
        val frames =
            switched() +
                showing() +
                frameTree(4) +
                serverTextFrame("""{"id":5,"result":{}}""") +
                serverTextFrame(quad(6)) +
                serverTextFrame(metrics(7)) +
                serverTextFrame("""{"id":8,"result":{}}""") +
                serverTextFrame("""{"id":9,"result":{}}""")
        var touched = false
        val socket = socket(frames)
        val sockets = ArrayDeque(listOf(socket(snapshotFrames()), socket))
        val service =
            service(
                frames,
                sockets = CHROME,
                touch = { _, _ ->
                    touched = true
                    ActionResult(ok = false, backend = "inject")
                },
                open = { sockets.removeFirst() },
            )
        val result = virtual {
            service.snapshot(TAB)
            service.tap(
                "chrome_devtools_remote~abc",
                BrowserTapRequest(ref = "9", humanize = false, input = BrowserInput.CDP),
            )
        }
        assertTrue(result.ok)
        assertEquals("cdp", result.backend)
        assertFalse(touched)
        assertEquals(206.09226f, result.points.single().x, 0.02f)
        assertEquals(466.48216f, result.points.single().y, 0.02f)
        assertEquals(
            listOf(
                "Page.getFrameTree",
                "Page.createIsolatedWorld",
                "Runtime.evaluate",
                "Page.getFrameTree",
                "DOM.scrollIntoViewIfNeeded",
                "DOM.getContentQuads",
                "Page.getLayoutMetrics",
                "Input.dispatchTouchEvent",
                "Input.dispatchTouchEvent",
            ),
            socket.methods(),
        )
    }

    @Test
    @Suppress("MissingUseCall") // The service closes each socket it opens.
    fun `keeps a broken tap single`() {
        // DevTools hangs up after the selector lookup, before any touch is sent.
        val dropped =
            switched() +
                showing() +
                serverTextFrame("""{"id":4,"result":{"root":{"nodeId":1}}}""") +
                serverTextFrame("""{"id":5,"result":{"nodeId":4}}""") +
                closeFrame()
        var opens = 0
        var prepared: Boolean? = null
        val service =
            service(
                byteArrayOf(),
                sockets = CHROME,
                open = {
                    opens++
                    socket(dropped)
                },
                prepare = { autoWake ->
                    prepared = autoWake
                    false
                },
            )
        val error =
            assertThrows(ApiException::class.java) {
                virtual {
                    service.tap(
                        "chrome_devtools_remote~abc",
                        BrowserTapRequest(selector = "a", input = BrowserInput.CDP),
                    )
                }
            }
        assertEquals("cdp_error", error.error)
        assertEquals(1, opens)
        assertEquals(false, prepared)
    }

    @Test
    @Suppress("MissingUseCall") // The service closes each socket it opens.
    fun `dropped snapshot runs once`() {
        val dropped =
            switched() +
                serverTextFrame(
                    """{"id":1,"result":{"frameTree":{"frame":{"url":"https://example.com/"}}}}"""
                ) +
                closeFrame()
        var opens = 0
        val service =
            service(
                byteArrayOf(),
                sockets = CHROME,
                open = {
                    opens++
                    socket(dropped)
                },
            )
        assertEquals("cdp_error", virtual { failure { service.snapshot(TAB) } })
        assertEquals(1, opens)
    }

    @Test
    fun `unreachable devtools runs once`() {
        // As CdpForward fails when wireless debugging is off and PhoneAPI cannot turn it on.
        var opens = 0
        val service =
            service(
                byteArrayOf(),
                sockets = CHROME,
                open = {
                    opens++
                    throw ApiException.unavailable("cdp_error", "Wireless debugging is off")
                },
            )
        assertEquals("cdp_error", virtual { failure { service.snapshot(TAB) } })
        assertEquals(1, opens)
    }

    @Test
    @Suppress("MissingUseCall") // The service closes each socket it opens.
    fun `refused command runs once`() {
        val refused =
            switched() +
                serverTextFrame(
                    """{"id":1,"result":{"frameTree":{"frame":{"url":"https://example.com/"}}}}"""
                ) +
                serverTextFrame("""{"id":2,"result":{}}""") +
                serverTextFrame("""{"id":3,"error":{"code":-32000,"message":"Not now"}}""")
        var opens = 0
        val service =
            service(
                byteArrayOf(),
                sockets = CHROME,
                open = {
                    opens++
                    socket(refused)
                },
            )
        assertEquals("cdp_error", virtual { failure { service.snapshot(TAB) } })
        assertEquals(1, opens)
    }

    @Test
    fun `closes a tab`() {
        val service =
            service(
                switched() + serverTextFrame("""{"id":1,"result":{"success":true}}"""),
                sockets = CHROME,
            )
        val result = virtual { service.closeTab("chrome_devtools_remote~abc") }
        assertTrue(result.ok)
        assertEquals("cdp", result.backend)
    }

    @Test
    @Suppress("MissingUseCall") // The service closes each socket it opens.
    fun `gone tab is not_found`() {
        // Chrome refuses a page socket for a closed tab, and the browser-wide commands on it.
        val refused = "No such target id: abc"
        val pageRefused =
            asciiLines("HTTP/1.1 500 Internal Server Error", "Content-Length: ${refused.length}") +
                refused.toByteArray()
        val browserRefused =
            switched() +
                serverTextFrame(
                    """{"id":1,"error":{"code":-32602,"message":"No target with given id found"}}"""
                )
        val service =
            service(
                byteArrayOf(),
                sockets = CHROME,
                open = { socket(browserRefused) },
            )
        val pageOnly = service(byteArrayOf(), sockets = CHROME, open = { socket(pageRefused) })
        virtual {
            assertEquals("not_found", failure { pageOnly.console(TAB, ConsoleRequest()) })
            assertEquals("not_found", failure { service.closeTab(TAB) })
            assertEquals("not_found", failure { service.activate(TAB) })
        }
    }

    @Test
    fun `taps a css selector`() {
        val frames =
            switched() +
                serverTextFrame("""{"id":1,"result":{}}""") +
                visibility(2, "visible") +
                serverTextFrame("""{"id":5,"result":{"root":{"nodeId":1}}}""") +
                serverTextFrame("""{"id":6,"result":{"nodeId":4}}""") +
                serverTextFrame("""{"id":7,"result":{}}""") +
                serverTextFrame(
                    """{"id":8,"result":{"quads":[[165.619049,453.68454,246.56548,453.68454,246.56548,479.27979,165.61905,479.27979]]}}"""
                ) +
                serverTextFrame(metrics(9))
        var touched = Rect(0, 0, 0, 0)
        val service =
            service(
                frames,
                sockets = CHROME,
                content = { Rect(0, 283, 1080, 2339) },
                touch = { rect, _ ->
                    touched = rect
                    ActionResult(ok = true, backend = "inject")
                },
            )
        val result = virtual {
            service.tap("chrome_devtools_remote~abc", BrowserTapRequest(selector = "a"))
        }
        assertTrue(result.ok)
        assertEquals(Rect(435, 1474, 647, 1541), touched)
    }

    @Test
    fun `tap wakes and drops snapshots`() {
        var invalidated = 0
        val frames =
            switched() +
                serverTextFrame("""{"id":1,"result":{}}""") +
                visibility(2, "visible") +
                serverTextFrame("""{"id":5,"result":{"root":{"nodeId":1}}}""") +
                serverTextFrame("""{"id":6,"result":{"nodeId":4}}""") +
                serverTextFrame("""{"id":7,"result":{}}""") +
                serverTextFrame(quad(8)) +
                serverTextFrame(metrics(9))
        val service =
            service(
                frames,
                sockets = CHROME,
                content = { Rect(0, 283, 1080, 2339) },
                touch = { _, _ -> ActionResult(ok = true, backend = "inject") },
                prepare = { true },
                sequence = { 7L },
                invalidate = { invalidated++ },
            )
        val result = virtual {
            service.tap("chrome_devtools_remote~abc", BrowserTapRequest(selector = "a"))
        }
        assertTrue(result.woke)
        assertEquals(7L, result.seq)
        assertEquals(1, invalidated)
    }

    @Test
    fun `rejected tap drops snapshots`() {
        var invalidated = 0
        val service = service(byteArrayOf(), sockets = CHROME, invalidate = { invalidated++ })
        val error =
            assertThrows(ApiException::class.java) {
                virtual { service.tap("chrome_devtools_remote~abc", BrowserTapRequest(" ")) }
            }
        assertEquals(400, error.status)
        assertEquals(1, invalidated)
    }

    @Test
    fun `rejects a bad ref`() {
        val service = service(byteArrayOf(), sockets = CHROME)
        val error =
            assertThrows(ApiException::class.java) {
                virtual { service.tap("chrome_devtools_remote~abc", BrowserTapRequest(" ")) }
            }
        assertEquals(400, error.status)
    }

    /** Runs [block] on [clock], so a wait in the service costs no real time. */
    internal fun <T> virtual(block: suspend () -> T): T = clock.run { block() }

    /**
     * Runs [open], and once it has waited [waitMs] of virtual time, which it must not have finished
     * in, runs [meanwhile], as for a page that answers late. Time moves only when nothing can run,
     * so [open] is by then waiting for what [meanwhile] does.
     */
    internal fun <T> answering(
        waitMs: Long,
        open: suspend () -> T,
        meanwhile: suspend () -> Unit,
    ): T = virtual {
        coroutineScope {
            val opening = async { open() }
            delay(waitMs)
            assertFalse(opening.isCompleted)
            meanwhile()
            opening.await()
        }
    }

    /** The error code [block] fails with, or "no error". */
    internal suspend fun failure(block: suspend () -> Unit): String =
        try {
            block()
            "no error"
        } catch (e: ApiException) {
            e.error
        }

    /** A socket whose peer answers [script] as the service asks, on [clock]. */
    internal fun socket(script: ByteArray, timedOut: Boolean = false) =
        StallingSocket(script, clock, timedOut)

    @Suppress("MissingUseCall") // BrowserServiceImpl closes each socket it opens.
    internal fun service(
        response: ByteArray,
        sockets: String,
        content: suspend (String?) -> Rect = { Rect(0, 0, 1, 1) },
        touch: suspend (Rect, Boolean) -> ActionResult = { _, _ ->
            ActionResult(ok = true)
        },
        swipe: suspend (Point, Point, SwipeSpec, Rect) -> ActionResult = { _, _, _, _ ->
            ActionResult(ok = false)
        },
        press: suspend (KeyRequest) -> ActionResult = { ActionResult(ok = false) },
        type: suspend (TextRequest) -> ActionResult = { ActionResult(ok = false) },
        open: (String) -> DevtoolsSocket = { socket(response) },
        targetTimeoutMs: Long = 5_000,
        axTreeCap: Int = AX_TREE_CACHE_CAP,
        prepare: suspend (Boolean) -> Boolean = { false },
        sequence: () -> Long = { 0L },
        invalidate: () -> Unit = {},
        inFront: suspend (String) -> Boolean = { true },
        showPackage: suspend (String) -> Unit = {},
        pageDialog: suspend () -> Boolean = { false },
        openPrivateTab: suspend (String) -> Unit = {},
        openProbe: ((String) -> DevtoolsSocket)? = null,
        touchscreen: suspend () -> TouchscreenInfo = { fallbackTouchscreen() },
        listSockets: () -> String = { sockets },
        io: CoroutineDispatcher = clock,
    ) =
        BrowserServiceImpl(
            io = io,
            listSockets = listSockets,
            open = open,
            websocketKey = { KEY },
            contentBounds = content,
            touchAt = touch,
            swipeAt = swipe,
            pressKey = press,
            typeText = type,
            targetTimeoutMs = targetTimeoutMs,
            axTreeCap = axTreeCap,
            prepare = prepare,
            sequence = sequence,
            invalidateSnapshots = invalidate,
            inFront = inFront,
            showPackage = showPackage,
            pageDialog = pageDialog,
            openPrivateTab = openPrivateTab,
            openProbe = openProbe?.let { probe -> { name: String -> probe(name) } },
            touchscreen = touchscreen,
        )

    /** A browser socket's reply to `Target.createTarget`, for a tab with Chrome id [id]. */
    internal fun created(id: String = "abc"): ByteArray =
        switched() + serverTextFrame("""{"id":1,"result":{"targetId":"$id"}}""")

    /** A new tab's page socket, through a navigation that commits at once. */
    internal fun committedPage(): ByteArray =
        switched() +
            serverTextFrame("""{"id":1,"result":{}}""") +
            serverTextFrame(
                """{"id":2,"result":{"frameTree":{"frame":{"id":"F","url":"about:blank",""" +
                    """"loaderId":"L0"}}}}"""
            ) +
            serverTextFrame("""{"id":3,"result":{"frameId":"F","loaderId":"L1"}}""") +
            serverTextFrame(
                """{"method":"Page.frameNavigated","params":{"frame":{"id":"F",""" +
                    """"url":"https://example.com/","loaderId":"L1"}}}"""
            )

    /** Replies to a snapshot whose tree has link 9. */
    internal fun snapshotFrames(): ByteArray =
        switched() +
            serverTextFrame(
                """{"id":1,"result":{"frameTree":{"frame":{"url":"https://example.com/",""" +
                    """"loaderId":"L1"}}}}"""
            ) +
            serverTextFrame("""{"id":2,"result":{}}""") +
            serverTextFrame(
                """{"id":3,"result":{"nodes":[{"nodeId":"9","role":{"value":"link"},"backendDOMNodeId":9}]}}"""
            )

    /** Chrome's reply [id] to Page.getFrameTree, with the main frame's document [loader]. */
    internal fun frameTree(id: Int, loader: String = "L1"): ByteArray =
        serverTextFrame(
            """{"id":$id,"result":{"frameTree":{"frame":{"id":"F","url":"https://example.com/",""" +
                """"loaderId":"$loader"}}}}"""
        )

    /** Replies to a visibilityState read: frame, world, and [state]. */
    internal fun visibility(firstId: Int, state: String): ByteArray =
        serverTextFrame("""{"id":$firstId,"result":{"frameTree":{"frame":{"id":"F"}}}}""") +
            serverTextFrame("""{"id":${firstId + 1},"result":{"executionContextId":7}}""") +
            serverTextFrame(
                """{"id":${firstId + 2},"result":{"result":{"type":"string","value":"$state"}}}"""
            )

    /** Chrome's `Target.getTargets` reply listing [infos]. */
    internal fun targetsReply(infos: String): ByteArray =
        switched() + serverTextFrame("""{"id":1,"result":{"targetInfos":[$infos]}}""")

    internal fun http(body: String): ByteArray =
        asciiLines("HTTP/1.1 200 OK", "Content-Length: ${body.toByteArray().size}") +
            body.toByteArray()

    internal fun switched(): ByteArray =
        asciiLines(
            "HTTP/1.1 101 Switching Protocols",
            "Sec-WebSocket-Accept: ${websocketAccept(KEY)}",
        )

    /** A WebSocket close from Chrome, as when its tab closes. */
    internal fun closeFrame(): ByteArray = byteArrayOf(0x88.toByte(), 0)

    internal fun serverTextFrame(text: String): ByteArray {
        val payload = text.toByteArray(Charsets.UTF_8)
        val out = ByteArrayOutputStream()
        out.write(0x81)
        val length = payload.size
        if (length < 126) {
            out.write(length)
        } else if (length <= 0xFFFF) {
            out.write(126)
            out.write(length ushr 8)
            out.write(length and 0xFF)
        } else {
            out.write(127)
            for (shift in 56 downTo 0 step 8) out.write(
                (length.toLong() ushr shift).toInt() and 0xFF
            )
        }
        out.write(payload)
        return out.toByteArray()
    }

    /** Replies to the visibilityState read before a cdp touch: frame, world, and "visible". */
    internal fun showing(state: String = "visible"): ByteArray =
        serverTextFrame("""{"id":1,"result":{"frameTree":{"frame":{"id":"F"}}}}""") +
            serverTextFrame("""{"id":2,"result":{"executionContextId":7}}""") +
            serverTextFrame("""{"id":3,"result":{"result":{"type":"string","value":"$state"}}}""")

    internal fun metrics(id: Int) =
        """{"id":$id,"result":{"cssVisualViewport":{"pageX":0,"pageY":0,"clientWidth":412.19049072265625,"clientHeight":783.2380981445312},"visualViewport":{"clientWidth":1082,"clientHeight":2056}}}"""

    internal fun quad(id: Int) =
        """{"id":$id,"result":{"quads":[[165.619049,453.68454,246.56548,453.68454,246.56548,479.27979,165.61905,479.27979]]}}"""

    internal companion object {
        const val KEY = "dGhlIHNhbXBsZSBub25jZQ=="
        const val TAB = "chrome_devtools_remote~abc"
        const val CHROME =
            """[{"name":"chrome_devtools_remote","pid":4,"package":"com.android.chrome"}]"""
    }
}
