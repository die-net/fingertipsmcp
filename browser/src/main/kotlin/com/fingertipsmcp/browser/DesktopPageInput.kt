package com.fingertipsmcp.browser

import com.fingertipsmcp.input.Box
import com.fingertipsmcp.input.Humanizer
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

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
 * and keyboard events over DevTools, never touch. Before any input the tab is brought to the front
 * if it is hidden, as a person switches tabs to use one. The pointer has one position for the whole
 * browser, so each move starts where the last one ended. Elements are scrolled into view with the
 * wheel rather than jumped to. Only one action runs at a time, since there is one hand. `cdp-mouse`
 * is the same as `cdp` here; `touch` needs a touch screen, which a desktop does not have.
 */
internal class DesktopPageInput(
    /**
     * The content quads of an element, scrolled into view first when asked, as [ElementLocator].
     */
    private val quads: suspend (CdpSession, String, TapTarget, Boolean) -> JsonArray,
    private val style: DesktopStyle = DesktopStyle.forHost(),
    random: Random = Random.Default,
    /** Monotonic milliseconds, which input plans are timed against. */
    private val now: () -> Long = ::monotonicMs,
) : PageInputs {
    private val hand = Humanizer(random)
    private val random = random
    private val busy = Mutex()
    @Volatile private var cursor: Point? = null

    override suspend fun ready(cdp: CdpSession, input: BrowserInput, pointer: Boolean) {
        requireMouse(input)
        val state = evaluateIn(cdp, "document.visibilityState", awaitPromise = false).value
        if ((state as? JsonPrimitive)?.contentOrNull == "visible") return
        cdp.call("Page.bringToFront")
        delay(random.nextLong(MIN_TAB_SWITCH_MS, MAX_TAB_SWITCH_MS))
    }

    override suspend fun tap(
        cdp: CdpSession,
        id: String,
        wanted: TapTarget,
        pkg: String?,
        request: BrowserTapRequest,
    ): ActionResult = busy.withLock {
        requireMouse(request.input)
        val events = events(cdp)
        val box = reveal(cdp, id, events, wanted, request.humanize)
        val at = click(events, box, request.humanize)
        ActionResult(ok = true, backend = BACKEND, points = listOf(at))
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
    ): ActionResult = busy.withLock {
        val events = events(cdp)
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
        playWheel(events, over, ticks.map { WheelAt(it.dx, it.dy, it.tMs) })
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
    ): ActionResult = busy.withLock {
        val events = events(cdp)
        val path = request.pointers.single()
        val start = path.first()
        moveTo(events, Point(start.x, start.y), MIN_TARGET_PX, request.humanize)
        delay(hand.mouse.hoverMs(request.humanize))
        events.mouseDown(Point(start.x, start.y))
        play(path.drop(1), offsetMs = start.tMs) { point -> events.mouseMove(point, buttons = 1) }
        val end = path.last()
        val last = Point(end.x, end.y)
        events.mouseUp(last)
        cursor = last
        ActionResult(ok = true, backend = BACKEND, points = listOf(Point(start.x, start.y), last))
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
    ): ActionResult = busy.withLock {
        val events = events(cdp)
        val key = pageKey ?: domKey(request.key, request.metaState)
        val modifiers = cdpModifiers(request.metaState)
        val held = heldModifiers(modifiers)
        for ((index, modifier) in held.withIndex()) {
            events.keyDown(
                modifier.first,
                held.take(index + 1).sumOf { it.second },
                LEFT,
                emptyList(),
            )
            delay(hand.keys.modifierLeadMs(DEFAULT_MAX_GAP_MS))
        }
        events.keyDown(key, modifiers, 0, emptyList())
        delay(if (request.longPress) LONG_PRESS_MS else hand.keys.holdMs(DEFAULT_MAX_GAP_MS))
        events.keyUp(key, modifiers, 0)
        for ((index, modifier) in held.withIndex().reversed()) {
            delay(hand.keys.modifierLeadMs(DEFAULT_MAX_GAP_MS))
            events.keyUp(modifier.first, held.take(index).sumOf { it.second }, LEFT)
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
    ): ActionResult = busy.withLock {
        val events = events(cdp)
        val points = ArrayList<Point>()
        if (focus != null) {
            val box = reveal(cdp, id, events, focus, humanize = true)
            points += click(events, box, humanize = true)
            delay(
                hand.keys.gapMs(null, 'a', request.minDelayMs, request.maxDelayMs) + AFTER_CLICK_MS
            )
        }
        if (request.clear) clearField(events, request.maxDelayMs)
        val steps = typingSteps(request.text, hand.keys, request.minDelayMs, request.maxDelayMs)
        playKeys(events, steps)
        if (request.submit) {
            delay(hand.keys.gapMs(' ', 'a', request.minDelayMs, request.maxDelayMs))
            playKeys(events, typingSteps("\n", hand.keys, request.minDelayMs, request.maxDelayMs))
        }
        ActionResult(ok = true, backend = BACKEND, points = points)
    }

    /** Select all, then Backspace: Ctrl+A here, Command+A with Chrome's command on macOS. */
    private suspend fun clearField(events: DesktopEvents, maxGapMs: Long) {
        val modifierKey = if (style.mac) META_KEY else CONTROL_KEY
        val modifier = if (style.mac) META_MODIFIER else CONTROL_MODIFIER
        val a = DomKey("a", "KeyA", 'A'.code, "a")
        val commands = if (style.mac) listOf("selectAll") else emptyList()
        events.keyDown(modifierKey, modifier, LEFT, emptyList())
        delay(hand.keys.modifierLeadMs(maxGapMs))
        events.keyDown(a, modifier, 0, commands)
        delay(hand.keys.holdMs(maxGapMs))
        events.keyUp(a, modifier, 0)
        delay(hand.keys.modifierLeadMs(maxGapMs))
        events.keyUp(modifierKey, 0, LEFT)
        delay(hand.keys.gapMs('a', ' ', 0, maxGapMs))
        val backspace = DomKey("Backspace", "Backspace", BACKSPACE_VK, null)
        events.keyDown(backspace, 0, 0, emptyList())
        delay(hand.keys.holdMs(maxGapMs))
        events.keyUp(backspace, 0, 0)
        delay(hand.keys.gapMs(' ', 'a', 0, maxGapMs))
    }

    private suspend fun playKeys(events: DesktopEvents, steps: List<KeyStep>) {
        val started = now()
        for (step in steps) {
            waitUntil(started, step.tMs)
            when (step) {
                is KeyStep.Down ->
                    events.keyDown(step.key, step.modifiers, step.location, emptyList())
                is KeyStep.Up -> events.keyUp(step.key, step.modifiers, step.location)
                is KeyStep.Insert -> events.insertText(step.text)
            }
        }
    }

    /**
     * The element's box, inside the viewport. When it is above or below the viewport, the wheel
     * scrolls toward it a flick at a time, until it shows or stops moving; then Chrome scrolls it
     * into view as a last resort.
     */
    private suspend fun reveal(
        cdp: CdpSession,
        id: String,
        events: DesktopEvents,
        wanted: TapTarget,
        humanize: Boolean,
    ): Box {
        var lastTop: Float? = null
        var rounds = 0
        while (rounds < MAX_SCROLL_ROUNDS) {
            rounds++
            when (val step = scrollToward(cdp, id, events, wanted, humanize, lastTop)) {
                is Reveal.Shown -> return step.box
                is Reveal.Scrolled -> lastTop = step.top
                Reveal.Stuck -> rounds = MAX_SCROLL_ROUNDS
            }
        }
        val quads = quads(cdp, id, wanted, true)
        val metrics = cdp.call("Page.getLayoutMetrics")
        return visibleBox(quads, metrics)
            ?: throw ApiException(409, "offscreen", "The node is outside the viewport")
    }

    /** One look at the element, and one flick of the wheel toward it when it is not shown. */
    private suspend fun scrollToward(
        cdp: CdpSession,
        id: String,
        events: DesktopEvents,
        wanted: TapTarget,
        humanize: Boolean,
        lastTop: Float?,
    ): Reveal {
        val metrics = cdp.call("Page.getLayoutMetrics")
        val view = viewportBox(metrics)
        val quads = quads(cdp, id, wanted, false)
        val whole =
            wholeBox(quads)
                ?: throw ApiException(409, "offscreen", "The node has no box on the page")
        val visible = visibleBox(quads, metrics)
        if (
            visible != null &&
                visible.height >= minOf(whole.height, MIN_VISIBLE_PX) &&
                visible.center.y in view.top + EDGE_PX..view.bottom - EDGE_PX
        ) {
            return Reveal.Shown(visible)
        }
        val sideways = whole.right < view.left || whole.left > view.right
        val stopped = lastTop != null && abs(whole.top - lastTop) < 1f
        if (sideways || stopped) return Reveal.Stuck
        val goal = view.top + view.height * random.nextDouble(GOAL_LOW, GOAL_HIGH).toFloat()
        val over = cursor?.takeIf { it in view } ?: idlePoint(view)
        moveTo(events, over, MIN_TARGET_PX, humanize)
        val ticks =
            hand.mouse.wheel(0f, whole.center.y - goal, style.wheelNotch, durationMs = 0, humanize)
        playWheel(events, over, ticks.map { WheelAt(it.dx, it.dy, it.tMs) })
        delay(hand.mouse.scrollSettleMs(humanize))
        return Reveal.Scrolled(whole.top)
    }

    private sealed interface Reveal {
        data class Shown(val box: Box) : Reveal

        data class Scrolled(val top: Float) : Reveal

        data object Stuck : Reveal
    }

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
        val from = cursor ?: Point(to.x + random.nextInt(-ENTRY_PX, ENTRY_PX), to.y + ENTRY_PX)
        val path = hand.mouse.move(from, to, targetSize, humanize)
        play(path, offsetMs = 0) { point -> events.mouseMove(point, buttons = 0) }
        cursor = to
    }

    private suspend fun play(
        path: List<TimedPoint>,
        offsetMs: Long,
        send: suspend (Point) -> Unit,
    ) {
        val started = now()
        for (point in path) {
            waitUntil(started, point.tMs - offsetMs)
            send(Point(point.x, point.y))
        }
    }

    private suspend fun playWheel(events: DesktopEvents, at: Point, ticks: List<WheelAt>) {
        val started = now()
        for (tick in ticks) {
            waitUntil(started, tick.tMs)
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

    private suspend fun events(cdp: CdpSession): DesktopEvents {
        val ratio = dipScale(cdp.call("Page.getLayoutMetrics"))
        return CdpDesktopEvents(cdp, devicePixelRatio = ratio)
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

    private fun heldModifiers(modifiers: Int): List<Pair<DomKey, Int>> = buildList {
        if (modifiers and CONTROL_MODIFIER != 0) add(CONTROL_KEY to CONTROL_MODIFIER)
        if (modifiers and META_MODIFIER != 0) add(META_KEY to META_MODIFIER)
        if (modifiers and ALT_MODIFIER != 0) add(ALT_KEY to ALT_MODIFIER)
        if (modifiers and SHIFT_MODIFIER != 0) add(SHIFT_KEY to SHIFT_MODIFIER)
    }

    /** Sleeps until [atMs] after [startedMs], so slow round trips do not stretch a plan. */
    private suspend fun waitUntil(startedMs: Long, atMs: Long) {
        val wait = atMs - (now() - startedMs)
        if (wait > 0) delay(wait)
    }

    private data class WheelAt(val dx: Float, val dy: Float, val tMs: Long)

    private companion object {
        const val MIN_TAB_SWITCH_MS = 180L
        const val MAX_TAB_SWITCH_MS = 420L
        const val MIN_TARGET_PX = 40f
        const val MAX_SCROLL_ROUNDS = 6
        const val MIN_VISIBLE_PX = 8f
        const val EDGE_PX = 4f
        const val GOAL_LOW = 0.3
        const val GOAL_HIGH = 0.6
        const val IDLE_LOW = 0.35
        const val IDLE_HIGH = 0.65
        const val ENTRY_PX = 120
        const val LONG_PRESS_MS = 600L
        const val DEFAULT_MAX_GAP_MS = 140L
        const val AFTER_CLICK_MS = 120L
        const val BACKSPACE_VK = 8
        const val ALT_MODIFIER = 1
        const val BACKEND = "cdp"
        val ALT_KEY = DomKey("Alt", "AltLeft", 18, null)
    }
}
