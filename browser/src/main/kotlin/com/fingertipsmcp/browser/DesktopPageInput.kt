package com.fingertipsmcp.browser

import com.fingertipsmcp.core.holdsStill
import com.fingertipsmcp.input.Box
import com.fingertipsmcp.input.Humanizer
import com.fingertipsmcp.input.WheelTick
import com.fingertipsmcp.input.contains
import com.fingertipsmcp.model.ActionResult
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
import com.fingertipsmcp.wait.monotonicMs
import kotlin.math.abs
import kotlin.random.Random
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/** Host conventions a desktop browser's input follows. */
data class DesktopStyle(
    /** Shortcuts use Command, and Chrome needs the editing command with them. */
    val mac: Boolean,
    /** CSS pixels one wheel notch scrolls. */
    val wheelNotch: Float,
) {
    companion object {
        private const val MAC_NOTCH = 40f
        private const val LINUX_NOTCH = 160f / 3f
        private const val WINDOWS_NOTCH = 100f

        /** Chrome scrolls 100 px a notch on Windows, about 53 on Linux, and 40 on macOS. */
        fun forHost(osName: String = System.getProperty("os.name").orEmpty()): DesktopStyle {
            val name = osName.lowercase()
            return when {
                "mac" in name || "darwin" in name ->
                    DesktopStyle(mac = true, wheelNotch = MAC_NOTCH)
                "linux" in name -> DesktopStyle(mac = false, wheelNotch = LINUX_NOTCH)
                else -> DesktopStyle(mac = false, wheelNotch = WINDOWS_NOTCH)
            }
        }
    }
}

/**
 * Desktop browser input, timed and shaped like a hand on a mouse and keyboard. `cdp` sends mouse
 * and keyboard events over DevTools, never touch. Each action first brings the tab to the front if
 * it is hidden, as a person switches tabs to use one, and fails with `not_visible` if Chrome does
 * not. The mouse and keyboard are [pointer]'s, shared with the viewer: each move starts where the
 * pointer was left, and only one action runs at a time, the tab switch included. Elements are
 * scrolled into view with the wheel rather than jumped to. `cdp-mouse` is the same as `cdp` here;
 * `touch` needs a touch screen, which a desktop does not have.
 */
internal class DesktopPageInput(
    /**
     * The content quads of an element, scrolled into view first when asked, as [ElementLocator].
     */
    private val quads: suspend (CdpSession, String, TapTarget, Boolean) -> JsonArray,
    private val pointer: DesktopPointer = DesktopPointer(),
    random: Random = Random.Default,
    /** Monotonic milliseconds, which input plans are timed against. */
    private val now: () -> Long = ::monotonicMs,
) : PageInputs {
    private val hand = Humanizer(random)
    private val random = random
    private val style: DesktopStyle
        get() = pointer.style

    /**
     * Refuses touch. The tab switch waits for the action, inside [act], so another action can't
     * switch tabs between it and this one's input.
     */
    override suspend fun ready(cdp: CdpSession, input: BrowserInput, pointer: Boolean) {
        requireMouse(input)
    }

    override suspend fun tap(
        cdp: CdpSession,
        id: String,
        wanted: TapTarget,
        pkg: String?,
        request: BrowserTapRequest,
    ): ActionResult {
        requireMouse(request.input)
        return act(cdp) { events ->
            val revealed = reveal(cdp, id, events, wanted, request.humanize)
            val at = click(events, revealed.box, request.humanize)
            ActionResult(
                ok = true,
                backend = BACKEND,
                points = listOf(at),
                message = revealed.note,
            )
        }
    }

    override fun checkSwipe(request: BrowserSwipeRequest) {
        requireMouse(request.input)
        checkSwipeRequest(request)
    }

    override suspend fun swipe(
        cdp: CdpSession,
        id: String,
        pkg: String?,
        request: BrowserSwipeRequest,
    ): ActionResult =
        act(cdp) { events ->
            val metrics = cdp.call("Page.getLayoutMetrics")
            val view = viewportBox(metrics)
            val (from, to) = swipeEnds(cdp, id, request, metrics)
            val over = clampInto(from, view)
            moveTo(events, over, MIN_TARGET_PX, request.humanize)
            // A swipe drags content with the finger, so the page scrolls against the motion.
            val ticks =
                hand.mouse.wheel(
                    dx = from.x - to.x,
                    dy = from.y - to.y,
                    notch = style.wheelNotch,
                    durationMs = request.durationMs,
                    humanize = request.humanize,
                )
            playWheel(events, over, ticks)
            ActionResult(ok = true, backend = BACKEND, points = listOf(over, to))
        }

    override fun checkGesture(request: BrowserGestureRequest) {
        requireMouse(request.input)
        checkGestureRequest(request)
        if (request.pointers.size > 1) {
            throw ApiException(
                422,
                "unsupported",
                "A desktop has one mouse pointer, so a gesture takes one path. " +
                    "Multi-finger gestures need a touch screen.",
            )
        }
    }

    override suspend fun gesture(
        cdp: CdpSession,
        pkg: String?,
        request: BrowserGestureRequest,
    ): ActionResult =
        act(cdp) { events ->
            val path = request.pointers.single()
            val start = path.first()
            val first = Point(start.x, start.y)
            moveTo(events, first, MIN_TARGET_PX, request.humanize)
            delay(hand.mouse.hoverMs(request.humanize))
            events.mouseDown(first)
            play(path.drop(1), offsetMs = start.tMs) { point ->
                events.mouseMove(point, buttons = 1)
            }
            val end = path.last()
            val last = Point(end.x, end.y)
            events.mouseUp(last)
            ActionResult(ok = true, backend = BACKEND, points = listOf(first, last))
        }

    override fun prepareKey(request: BrowserKeyRequest): DomKey {
        requireMouse(request.input)
        return domKey(request.key, request.metaState)
    }

    override suspend fun key(
        cdp: CdpSession,
        pkg: String?,
        request: BrowserKeyRequest,
        pageKey: DomKey?,
    ): ActionResult =
        act(cdp) { events ->
            val key = pageKey ?: domKey(request.key, request.metaState)
            val modifiers = CdpModifier.fromMetaState(request.metaState)
            val held = modifiers.toList()
            for ((index, modifier) in held.withIndex()) {
                events.keyDown(modifier.key, held.take(index + 1).toSet(), LEFT, emptyList())
                delay(hand.keys.modifierLeadMs(DEFAULT_MAX_GAP_MS))
            }
            events.keyDown(key, modifiers, 0, emptyList())
            delay(if (request.longPress) LONG_PRESS_MS else hand.keys.holdMs(DEFAULT_MAX_GAP_MS))
            events.keyUp(key, modifiers, 0)
            for ((index, modifier) in held.withIndex().reversed()) {
                delay(hand.keys.modifierLeadMs(DEFAULT_MAX_GAP_MS))
                events.keyUp(modifier.key, held.take(index).toSet(), LEFT)
            }
            ActionResult(ok = true, backend = BACKEND)
        }

    override fun prepareText(request: BrowserTextRequest): TapTarget? {
        requireMouse(request.input)
        return checkTextRequest(request)
    }

    override suspend fun text(
        cdp: CdpSession,
        id: String,
        pkg: String?,
        request: BrowserTextRequest,
        focus: TapTarget?,
    ): ActionResult =
        act(cdp) { events ->
            val points = ArrayList<Point>()
            var note: String? = null
            if (focus != null) {
                val revealed = reveal(cdp, id, events, focus, humanize = true)
                note = revealed.note
                points += click(events, revealed.box, humanize = true)
                delay(hand.keys.firstKeyMs(request.minDelayMs, request.maxDelayMs))
            }
            if (request.clear) clearField(events, request.maxDelayMs)
            val steps = typingSteps(request.text, hand.keys, request.minDelayMs, request.maxDelayMs)
            playKeys(events, steps, now)
            if (request.submit) {
                delay(hand.keys.gapMs(' ', 'a', request.minDelayMs, request.maxDelayMs))
                val enter = typingSteps("\n", hand.keys, request.minDelayMs, request.maxDelayMs)
                playKeys(events, enter, now)
            }
            ActionResult(ok = true, backend = BACKEND, points = points, message = note)
        }

    /**
     * Runs [block] as the only action on the mouse and keyboard, once this tab is the one Chrome
     * shows, with events for the page that move [pointer].
     */
    private suspend fun <T> act(cdp: CdpSession, block: suspend (DesktopEvents) -> T): T =
        pointer.act {
            if (!tabShowing(cdp)) selectTab(cdp)
            val ratio = dipScale(cdp.call("Page.getLayoutMetrics"))
            block(pointer.tracked(CdpDesktopEvents(cdp, devicePixelRatio = ratio)))
        }

    /** Select all, then Backspace: Ctrl+A here, Command+A with Chrome's command on macOS. */
    private suspend fun clearField(events: DesktopEvents, maxGapMs: Long) {
        val modifier = if (style.mac) CdpModifier.META else CdpModifier.CONTROL
        val held = setOf(modifier)
        val a = DomKey("a", "KeyA", 'A'.code, "a")
        val commands = if (style.mac) listOf("selectAll") else emptyList()
        events.keyDown(modifier.key, held, LEFT, emptyList())
        delay(hand.keys.modifierLeadMs(maxGapMs))
        events.keyDown(a, held, 0, commands)
        delay(hand.keys.holdMs(maxGapMs))
        events.keyUp(a, held, 0)
        delay(hand.keys.modifierLeadMs(maxGapMs))
        events.keyUp(modifier.key, emptySet(), LEFT)
        delay(hand.keys.gapMs('a', ' ', 0, maxGapMs))
        val backspace = DomKey("Backspace", "Backspace", BACKSPACE_VK, null)
        events.keyDown(backspace, emptySet(), 0, emptyList())
        delay(hand.keys.holdMs(maxGapMs))
        events.keyUp(backspace, emptySet(), 0)
        delay(hand.keys.gapMs(' ', 'a', 0, maxGapMs))
    }

    /**
     * The element's box, inside the viewport. When it is above or below the viewport, the wheel
     * scrolls toward it a flick at a time, waiting after each for the page to stop moving. When the
     * wheel can't bring it in, Chrome scrolls it into view, and [Revealed.note] says so: the
     * element is beside the viewport, the wheel did not move it, or the page kept moving it for
     * [MAX_FLICKS] flicks.
     */
    private suspend fun reveal(
        cdp: CdpSession,
        id: String,
        events: DesktopEvents,
        wanted: TapTarget,
        humanize: Boolean,
    ): Revealed {
        var flicks = 0
        while (true) {
            val look = look(cdp, id, wanted)
            look.shown?.let {
                return Revealed(it, note = null)
            }
            val sideways = look.whole.right < look.view.left || look.whole.left > look.view.right
            if (sideways || flicks == MAX_FLICKS) return jumpTo(cdp, id, wanted)
            flick(events, look, humanize)
            flicks++
            val top = settledTop(cdp, id, wanted)
            if (abs(top - look.whole.top) < MOVED_PX) return jumpTo(cdp, id, wanted)
        }
    }

    /** Where the element and the viewport are now, and the element's visible box if it shows. */
    private suspend fun look(cdp: CdpSession, id: String, wanted: TapTarget): Look {
        val metrics = cdp.call("Page.getLayoutMetrics")
        val view = viewportBox(metrics)
        val quads = quads(cdp, id, wanted, false)
        val whole = wholeBox(quads) ?: throw noBox()
        val visible = visibleBox(quads, metrics)
        val shown = visible?.takeIf {
            it.height >= minOf(whole.height, MIN_VISIBLE_PX) &&
                it.center.y in view.top + EDGE_PX..view.bottom - EDGE_PX
        }
        return Look(view, whole, shown)
    }

    /**
     * One flick of the wheel that brings the element from where [look] saw it toward the middle.
     */
    private suspend fun flick(events: DesktopEvents, look: Look, humanize: Boolean) {
        val view = look.view
        val goal = view.top + view.height * random.nextDouble(GOAL_LOW, GOAL_HIGH).toFloat()
        val over = pointer.at?.takeIf { it in view } ?: idlePoint(view)
        moveTo(events, over, MIN_TARGET_PX, humanize)
        val ticks =
            hand.mouse.wheel(
                0f,
                look.whole.center.y - goal,
                style.wheelNotch,
                durationMs = 0,
                humanize,
            )
        playWheel(events, over, ticks)
    }

    /**
     * The element's top once it holds still, as smooth scrolling and anything the scroll set off,
     * such as content loading above it, come to rest. A page that keeps moving it past
     * [SCROLL_SETTLE_MS] gives where it was then.
     */
    private suspend fun settledTop(cdp: CdpSession, id: String, wanted: TapTarget): Float {
        var top = Float.NaN
        holdsStill(SCROLL_SETTLE_MS, SCROLL_POLL_MS) {
            val box = wholeBox(quads(cdp, id, wanted, false)) ?: throw noBox()
            box.top.also { top = it }
        }
        return top
    }

    /** Has Chrome scroll the element into view, since the wheel could not. */
    private suspend fun jumpTo(cdp: CdpSession, id: String, wanted: TapTarget): Revealed {
        val quads = quads(cdp, id, wanted, true)
        val metrics = cdp.call("Page.getLayoutMetrics")
        val box =
            visibleBox(quads, metrics)
                ?: throw ApiException(409, "offscreen", "The node is outside the viewport")
        return Revealed(box, note = JUMPED)
    }

    private data class Look(val view: Box, val whole: Box, val shown: Box?)

    /** The element's visible [box], and a [note] for the result when Chrome scrolled to it. */
    private data class Revealed(val box: Box, val note: String?)

    /** Moves into [box], rests, and clicks. Returns where. */
    private suspend fun click(events: DesktopEvents, box: Box, humanize: Boolean): Point {
        val at = hand.mouse.aim(box, humanize)
        moveTo(events, at, minOf(box.width, box.height), humanize)
        delay(hand.mouse.hoverMs(humanize))
        events.mouseDown(at)
        delay(hand.mouse.pressMs(humanize))
        events.mouseUp(at)
        return at
    }

    private suspend fun moveTo(
        events: DesktopEvents,
        to: Point,
        targetSize: Float,
        humanize: Boolean,
    ) {
        val from = pointer.at ?: Point(to.x + random.nextInt(-ENTRY_PX, ENTRY_PX), to.y + ENTRY_PX)
        val path = hand.mouse.move(from, to, targetSize, humanize)
        play(path, offsetMs = 0) { point -> events.mouseMove(point, buttons = 0) }
    }

    private suspend fun play(
        path: List<TimedPoint>,
        offsetMs: Long,
        send: suspend (Point) -> Unit,
    ) {
        val started = now()
        for (point in path) {
            waitUntil(now, started, point.tMs - offsetMs)
            send(Point(point.x, point.y))
        }
    }

    private suspend fun playWheel(events: DesktopEvents, at: Point, ticks: List<WheelTick>) {
        val started = now()
        for (tick in ticks) {
            waitUntil(now, started, tick.tMs)
            events.wheel(at, tick.dx, tick.dy)
        }
    }

    private suspend fun swipeEnds(
        cdp: CdpSession,
        id: String,
        request: BrowserSwipeRequest,
        metrics: JsonObject,
    ): Pair<Point, Point> {
        val from = request.from
        val to = request.to
        if (from != null && to != null) return from to to
        val direction: SwipeDirection =
            request.direction ?: throw ApiException.badRequest("Pass from and to, or a direction")
        val quads =
            optionalTarget(request.ref, request.selector)?.let {
                quads(cdp, id, it, false)
            }
        return directionSwipe(quads, metrics, direction, request.distance)
    }

    /** Refuses touch, which needs a touch screen. Every other input is the mouse and keyboard. */
    private fun requireMouse(input: BrowserInput) {
        if (input != BrowserInput.TOUCH) return
        throw ApiException(
            422,
            "unsupported",
            "A desktop browser has no touch screen. Use input cdp, the default here.",
        )
    }

    private fun idlePoint(view: Box): Point =
        Point(
            view.left + view.width * random.nextDouble(IDLE_LOW, IDLE_HIGH).toFloat(),
            view.top + view.height * random.nextDouble(IDLE_LOW, IDLE_HIGH).toFloat(),
        )

    private fun clampInto(point: Point, view: Box): Point =
        Point(
            point.x.coerceIn(view.left + 1f, view.right - 1f),
            point.y.coerceIn(view.top + 1f, view.bottom - 1f),
        )

    private fun noBox() = ApiException(409, "offscreen", "The node has no box on the page")

    private companion object {
        const val MIN_TARGET_PX = 40f
        /**
         * Flicks before Chrome scrolls the element in instead. Each flick scrolls the whole way to
         * the element, so another is needed only when the page moved the element while it scrolled,
         * as a feed that loads more above it does. This bounds how often that may happen.
         */
        const val MAX_FLICKS = 6
        /** How far the element has to move for a flick to count as having scrolled it. */
        const val MOVED_PX = 1f
        /** How long smooth scrolling, and what the scroll set off, may take to come to rest. */
        const val SCROLL_SETTLE_MS = 1_500L
        /** How far apart two reads of the element's place are when checking that it holds still. */
        const val SCROLL_POLL_MS = 50L
        const val MIN_VISIBLE_PX = 8f
        const val EDGE_PX = 4f
        const val GOAL_LOW = 0.3
        const val GOAL_HIGH = 0.6
        const val IDLE_LOW = 0.35
        const val IDLE_HIGH = 0.65
        const val ENTRY_PX = 120
        const val LONG_PRESS_MS = 600L
        const val DEFAULT_MAX_GAP_MS = 140L
        const val BACKSPACE_VK = 8
        const val BACKEND = "cdp"
        const val JUMPED =
            "The wheel could not bring the element into view, so Chrome scrolled to it."
    }
}
