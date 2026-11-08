package com.fingertipsmcp.browser

import com.fingertipsmcp.core.ApiJson
import com.fingertipsmcp.input.Box
import com.fingertipsmcp.model.ApiException
import com.fingertipsmcp.model.BrowserGestureRequest
import com.fingertipsmcp.model.BrowserInput
import com.fingertipsmcp.model.BrowserKeyRequest
import com.fingertipsmcp.model.BrowserSwipeRequest
import com.fingertipsmcp.model.BrowserTapRequest
import com.fingertipsmcp.model.BrowserTextRequest
import com.fingertipsmcp.model.Point
import com.fingertipsmcp.model.SwipeDirection
import com.fingertipsmcp.model.TimedPoint
import java.io.Closeable
import kotlin.random.Random
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** Desktop input as Chrome receives it: mouse and key events, timed like a hand. */
class DesktopPageInputTest {
    private val clock = VirtualIo()
    private val log = ArrayList<Sent>()

    @Test
    fun `a click lands in the box`() = clock.run {
        val page = FakePage("a")
        val result = input().tap(page, "t", TapTarget.Css("#b"), null, BrowserTapRequest(ref = "1"))
        val mouse = page.mouse()
        val types = mouse.map { it.type }
        assertTrue(types.size > 3, "the pointer travels to the box: $types")
        assertEquals(listOf("mousePressed", "mouseReleased"), types.takeLast(2))
        assertTrue(types.dropLast(2).all { it == "mouseMoved" })
        val press = mouse[mouse.size - 2]
        assertTrue(press.x in 100f..200f && press.y in 300f..340f, "pressed at ${press.at}")
        assertEquals(1, press.params["clickCount"]!!.jsonPrimitive.int)
        assertEquals("cdp", result.backend)
        assertNull(result.message)
        assertTrue(clock.now > 0, "a hand takes time to move and press")
        assertEquals(emptyList<Sent>(), page.calls("Page.bringToFront"), "the tab already shows")
    }

    @Test
    fun `smooth scrolling is waited out`() = clock.run {
        // Each notch scrolls over 900 ms, longer than a fixed wait after the flick would be.
        val page = FakePage("a", element = Box(100f, 2_000f, 200f, 2_040f), smoothMs = 900)
        val result = input().tap(page, "t", TapTarget.Css("#b"), null, BrowserTapRequest(ref = "1"))
        assertNull(result.message, "the wheel brought it in")
        assertEquals(0, page.jumps)
        val wheel = page.mouse().filter { it.type == "mouseWheel" }
        val dy = wheel.map { it.params["deltaY"]!!.jsonPrimitive.float }
        assertTrue(dy.all { it > 0f }, "one way only, never past it and back: $dy")
        // One flick from 2,020 to a goal 30 to 60 percent down the 600 px viewport.
        assertTrue(dy.sum() in 1_560f..1_940f, "one flick's worth: ${dy.sum()}")
        val press = page.mouse().last { it.type == "mousePressed" }
        val box = page.box()
        assertTrue(press.y in box.top..box.bottom, "pressed at ${press.at} on $box")
    }

    @Test
    fun `a stuck element is jumped to`() = clock.run {
        val page = FakePage("a", element = Box(100f, 2_000f, 200f, 2_040f), scrolls = false)
        val result = input().tap(page, "t", TapTarget.Css("#b"), null, BrowserTapRequest(ref = "1"))
        assertTrue(page.mouse().any { it.type == "mouseWheel" }, "the wheel was tried first")
        assertEquals(1, page.jumps)
        assertNotNull(result.message)
        val press = page.mouse().last { it.type == "mousePressed" }
        val box = page.box()
        assertTrue(press.y in box.top..box.bottom, "pressed at ${press.at} on $box")
    }

    @Test
    fun `an element aside is jumped to`() = clock.run {
        val page = FakePage("a", element = Box(1_200f, 300f, 1_300f, 340f))
        val result = input().tap(page, "t", TapTarget.Css("#b"), null, BrowserTapRequest(ref = "1"))
        assertEquals(emptyList<Sent>(), page.mouse().filter { it.type == "mouseWheel" })
        assertEquals(1, page.jumps)
        assertNotNull(result.message)
    }

    @Test
    fun `a moving element is jumped to`() = clock.run {
        // Every look finds it 40 px further down, as a feed loading above it would.
        val page = FakePage("a", element = Box(100f, 2_000f, 200f, 2_040f), driftPx = 40f)
        val result = input().tap(page, "t", TapTarget.Css("#b"), null, BrowserTapRequest(ref = "1"))
        assertEquals(1, page.jumps)
        assertNotNull(result.message)
        assertTrue(clock.now < 30_000, "each wait for it to settle has a deadline: ${clock.now}")
    }

    @Test
    fun `a hidden tab comes forward`() = clock.run {
        val page = FakePage("a", visible = false, switchMs = 600)
        input().tap(page, "t", TapTarget.Css("#b"), null, BrowserTapRequest(ref = "1"))
        val front = page.calls("Page.bringToFront").single()
        val first = page.mouse().first()
        assertTrue(first.atMs >= front.atMs + 600, "input waited for the tab to show")
    }

    @Test
    fun `a tab that won't show refuses`() = clock.run {
        val page = FakePage("a", visible = false, switchMs = null)
        val error =
            assertThrows<ApiException> {
                input().key(page, null, BrowserKeyRequest(key = "a"), pageKey = null)
            }
        assertEquals(409, error.status)
        assertEquals("not_visible", error.error)
        assertEquals(emptyList<Sent>(), page.calls("Input.dispatchKeyEvent"))
    }

    @Test
    fun `a tab switch waits its turn`() = clock.run {
        val input = input()
        val shown = FakePage("shown")
        val hidden = FakePage("hidden", visible = false, switchMs = 100)
        coroutineScope {
            val one = async {
                input.tap(shown, "t", TapTarget.Css("#b"), null, BrowserTapRequest(ref = "1"))
            }
            val two = async {
                input.tap(hidden, "t", TapTarget.Css("#b"), null, BrowserTapRequest(ref = "1"))
            }
            one.await()
            two.await()
        }
        // The switch and the input on each page come as one run, not inside the other's action.
        val order =
            log.filter {
                    it.method == "Page.bringToFront" || it.method == "Input.dispatchMouseEvent"
                }
                .map { it.page }
        assertEquals(order.distinct().size, order.zipWithNext().count { (a, b) -> a != b } + 1)
    }

    @Test
    fun `a swipe up wheels down`() = clock.run {
        val page = FakePage("a")
        val request = BrowserSwipeRequest(from = Point(400f, 450f), to = Point(400f, 150f))
        input().swipe(page, "t", null, request)
        val wheel = page.mouse().filter { it.type == "mouseWheel" }
        assertEquals(
            listOf(100f, 100f, 100f),
            wheel.map { it.params["deltaY"]!!.jsonPrimitive.float },
        )
        assertTrue(wheel.all { it.at == Point(400f, 450f) }, "${wheel.map { it.at }}")
        assertEquals(Point(400f, 450f), page.mouse().last { it.type == "mouseMoved" }.at)
    }

    @Test
    fun `the viewer's pointer is shared`() = clock.run {
        val pointer = DesktopPointer(STYLE)
        val page = FakePage("a")
        ViewerPageInput(page, devicePixelRatio = 1f, pointer) { clock.now }
            .move(Point(700f, 50f), pressed = false)
        input(pointer).tap(page, "t", TapTarget.Css("#b"), null, BrowserTapRequest(ref = "1"))
        val moves = page.mouse().filter { it.type == "mouseMoved" }
        assertEquals(Point(700f, 50f), moves[0].at, "the viewer's move")
        assertEquals(Point(700f, 50f), moves[1].at, "the agent's move starts there")
    }

    @Test
    fun `a shortcut holds its modifier`() = clock.run {
        val page = FakePage("a")
        input().key(page, null, BrowserKeyRequest(key = "a", metaState = CTRL_ON), pageKey = null)
        val keys =
            page.calls("Input.dispatchKeyEvent").map {
                it.type + " " + it.params["key"]!!.jsonPrimitive.content
            }
        assertEquals(listOf("rawKeyDown Control", "rawKeyDown a", "keyUp a", "keyUp Control"), keys)
        val bits =
            page.calls("Input.dispatchKeyEvent").map { it.params["modifiers"]!!.jsonPrimitive.int }
        assertEquals(listOf(2, 2, 2, 0), bits)
    }

    @Test
    fun `typing shifts capitals`() = clock.run {
        val page = FakePage("a")
        val request = BrowserTextRequest(text = "Hi", input = BrowserInput.CDP)
        input().text(page, "t", null, request, focus = null)
        val keys =
            page.calls("Input.dispatchKeyEvent").map {
                it.type + " " + it.params["key"]!!.jsonPrimitive.content
            }
        assertEquals(
            listOf(
                "rawKeyDown Shift",
                "keyDown H",
                "keyUp H",
                "keyUp Shift",
                "keyDown i",
                "keyUp i",
            ),
            keys,
        )
        assertEquals(emptyList<Sent>(), page.calls("Input.insertText"))
    }

    @Test
    fun `no delay types at the click`() = clock.run {
        val page = FakePage("a")
        val request =
            BrowserTextRequest(text = "a", selector = "#b", minDelayMs = 0, maxDelayMs = 0)
        input().text(page, "t", null, request, focus = TapTarget.Css("#b"))
        val release = page.mouse().last { it.type == "mouseReleased" }
        val key = page.calls("Input.dispatchKeyEvent").first()
        assertEquals(release.atMs, key.atMs)
    }

    @Test
    fun `the first key waits in range`() = clock.run {
        val page = FakePage("a")
        val request =
            BrowserTextRequest(text = "a", selector = "#b", minDelayMs = 300, maxDelayMs = 400)
        input().text(page, "t", null, request, focus = TapTarget.Css("#b"))
        val release = page.mouse().last { it.type == "mouseReleased" }
        val key = page.calls("Input.dispatchKeyEvent").first()
        assertTrue(key.atMs - release.atMs in 300..400, "${key.atMs - release.atMs} ms")
    }

    @Test
    fun `touch is refused`() {
        val input = input()
        val swipe =
            assertThrows<ApiException> {
                input.checkSwipe(
                    BrowserSwipeRequest(direction = SwipeDirection.UP, input = BrowserInput.TOUCH)
                )
            }
        assertEquals(422, swipe.status)
        assertEquals("unsupported", swipe.error)
        val key =
            assertThrows<ApiException> {
                input.prepareKey(BrowserKeyRequest(key = "a", input = BrowserInput.TOUCH))
            }
        assertEquals("unsupported", key.error)
    }

    @Test
    fun `one pointer per gesture`() {
        val path = listOf(TimedPoint(1f, 1f, 0), TimedPoint(2f, 2f, 50))
        val error =
            assertThrows<ApiException> {
                input().checkGesture(BrowserGestureRequest(pointers = listOf(path, path)))
            }
        assertEquals("unsupported", error.error)
    }

    private fun input(pointer: DesktopPointer = DesktopPointer(STYLE)) =
        DesktopPageInput(
            quads = { cdp, _, _, scroll -> (cdp as FakePage).quads(scroll) },
            pointer = pointer,
            random = Random(SEED),
            now = { clock.now },
        )

    /** A call a page was sent, on [page], at [atMs] of virtual time. */
    private data class Sent(
        val page: String,
        val method: String,
        val params: JsonObject,
        val atMs: Long,
    ) {
        val type: String
            get() = params["type"]!!.jsonPrimitive.content

        val x: Float
            get() = params["x"]!!.jsonPrimitive.float

        val y: Float
            get() = params["y"]!!.jsonPrimitive.float

        val at: Point
            get() = Point(x, y)
    }

    /**
     * A tab on an 800 by 600 viewport with one element at [element] in the document. A wheel notch
     * scrolls the page by its delta over [smoothMs], as Chrome's smooth scrolling does, unless the
     * page does not [scroll][scrolls]. Each look at the element finds it [driftPx] further down.
     * The tab shows when [visible], or [switchMs] after `Page.bringToFront`, or never when that is
     * null.
     */
    private inner class FakePage(
        private val name: String,
        private var element: Box = Box(100f, 300f, 200f, 340f),
        private val scrolls: Boolean = true,
        private val smoothMs: Long = 0,
        private val driftPx: Float = 0f,
        visible: Boolean = true,
        private val switchMs: Long? = 0,
    ) : CdpSession {
        private var shownAt: Long? = if (visible) 0L else null
        private var baseScroll = 0f
        private val notches = ArrayList<Pair<Long, Float>>()
        var jumps = 0
            private set

        fun calls(method: String): List<Sent> =
            synchronized(log) { log.filter { it.page == name && it.method == method } }

        fun mouse(): List<Sent> = calls("Input.dispatchMouseEvent")

        /** The element's box in the viewport now. */
        fun box(): Box {
            val y = scrollY()
            return Box(element.left, element.top - y, element.right, element.bottom - y)
        }

        fun quads(scroll: Boolean): JsonArray {
            if (scroll) {
                jumps++
                val middle = (element.top + element.bottom) / 2f - VIEW_HEIGHT / 2f
                baseScroll += middle - scrollY()
                element = element.copy(left = 100f, right = 200f)
            } else if (driftPx != 0f) {
                element =
                    element.copy(top = element.top + driftPx, bottom = element.bottom + driftPx)
            }
            val b = box()
            val corners = listOf(b.left, b.top, b.right, b.top, b.right, b.bottom, b.left, b.bottom)
            return buildJsonArray { add(buildJsonArray { corners.forEach { add(it) } }) }
        }

        private fun scrollY(): Float {
            val now = clock.now
            return baseScroll +
                notches
                    .sumOf { (at, dy) ->
                        val done =
                            if (smoothMs == 0L) 1.0
                            else ((now - at).toDouble() / smoothMs).coerceIn(0.0, 1.0)
                        dy * done
                    }
                    .toFloat()
        }

        override fun listen(onEvent: (CdpEvent) -> Unit): Closeable = Closeable {}

        override suspend fun callCounted(method: String, params: JsonObject?): CdpReply {
            val sent = params ?: JsonObject(emptyMap())
            synchronized(log) { log += Sent(name, method, sent, clock.now) }
            val result =
                when (method) {
                    "Page.getLayoutMetrics" -> METRICS
                    "Page.getFrameTree" -> FRAME_TREE
                    "Page.createIsolatedWorld" -> buildJsonObject { put("executionContextId", 1) }
                    "Runtime.evaluate" -> visibility()
                    "Page.bringToFront" -> {
                        if (shownAt == null) shownAt = switchMs?.let { clock.now + it }
                        JsonObject(emptyMap())
                    }
                    "Input.dispatchMouseEvent" -> {
                        val type = sent["type"]!!.jsonPrimitive.content
                        if (type == "mouseWheel" && scrolls) {
                            notches += clock.now to sent["deltaY"]!!.jsonPrimitive.float
                        }
                        JsonObject(emptyMap())
                    }
                    else -> JsonObject(emptyMap())
                }
            return CdpReply(result, eventsBefore = 0)
        }

        private fun visibility(): JsonObject {
            val at = shownAt
            val state = if (at != null && clock.now >= at) "visible" else "hidden"
            return buildJsonObject {
                putJsonObject("result") {
                    put("type", "string")
                    put("value", state)
                }
            }
        }

        override fun post(method: String, params: JsonObject?) {
            synchronized(log) {
                log += Sent(name, method, params ?: JsonObject(emptyMap()), clock.now)
            }
        }

        override suspend fun awaitClosed() = Unit
    }

    private companion object {
        const val SEED = 7
        const val CTRL_ON = 0x1000
        const val VIEW_HEIGHT = 600f
        val STYLE = DesktopStyle(mac = false, wheelNotch = 100f)
        val METRICS =
            ApiJson.parseToJsonElement(
                    """{"cssVisualViewport":{"clientWidth":800,"clientHeight":600},""" +
                        """"visualViewport":{"clientWidth":1600,"clientHeight":1200}}"""
                )
                .jsonObject
        val FRAME_TREE =
            ApiJson.parseToJsonElement("""{"frameTree":{"frame":{"id":"main"}}}""").jsonObject
    }
}
