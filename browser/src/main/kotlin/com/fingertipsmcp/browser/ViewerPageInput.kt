package com.fingertipsmcp.browser

import com.fingertipsmcp.input.TypingModel
import com.fingertipsmcp.model.ActionResult
import com.fingertipsmcp.model.Point
import kotlin.math.hypot
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * A person's mouse and keyboard on one desktop page, from the viewer, at CSS viewport points.
 * Unlike [DesktopPageInput], nothing is planned or humanized: the person's own hand already moves
 * the pointer and types. Each event goes through [pointer], the one the agent's input uses too, so
 * it waits for an agent's action to finish and leaves the pointer where the person put it. Clicks
 * close together in time and place count as a double click, as an OS counts them.
 */
class ViewerPageInput(
    private val cdp: CdpSession,
    devicePixelRatio: Float,
    private val pointer: DesktopPointer,
    /** Monotonic milliseconds, for telling a double click. */
    private val now: () -> Long,
) {
    private val events = pointer.tracked(CdpDesktopEvents(cdp, devicePixelRatio))
    private val untimed = TypingModel()
    private var lastPress: Point? = null
    private var lastPressAt = 0L
    private var clicks = 1

    /** Moves the pointer, with the left button held when [pressed]. */
    suspend fun move(at: Point, pressed: Boolean) {
        pointer.act { events.mouseMove(at, buttons = if (pressed) 1 else 0) }
    }

    suspend fun press(at: Point) {
        val at0 = now()
        val before = lastPress
        clicks =
            if (
                before != null &&
                    at0 - lastPressAt <= DOUBLE_CLICK_MS &&
                    hypot(at.x - before.x, at.y - before.y) <= DOUBLE_CLICK_PX
            ) {
                clicks + 1
            } else {
                1
            }
        lastPress = at
        lastPressAt = at0
        pointer.act {
            events.mouseMove(at, buttons = 0)
            events.mouseDown(at, clicks)
        }
    }

    suspend fun release(at: Point) {
        pointer.act { events.mouseUp(at, clicks) }
    }

    /** One turn of the wheel, by [dx] and [dy] CSS pixels of scroll. */
    suspend fun wheel(at: Point, dx: Float, dy: Float) {
        pointer.act { events.wheel(at, dx, dy) }
    }

    /** A named key, as a key request names it, with Android's [metaState] for its modifiers. */
    suspend fun key(name: String, metaState: Int): ActionResult {
        val key = domKey(name, metaState)
        val modifiers = CdpModifier.fromMetaState(metaState)
        pointer.act {
            events.keyDown(key, modifiers, 0, emptyList())
            events.keyUp(key, modifiers, 0)
        }
        return ActionResult(ok = true, backend = BACKEND)
    }

    /**
     * Types [text] key by key on a US layout, with Shift held for the characters that need it, as
     * [typingSteps] lays it out with no delays. Text no key types, such as emoji, is inserted as an
     * input method commits it.
     */
    suspend fun type(text: String): ActionResult {
        val steps = typingSteps(text, untimed, minMs = 0, maxMs = 0)
        pointer.act { playKeys(events, steps, now) }
        return ActionResult(ok = true, backend = BACKEND)
    }

    /** Puts [text] in the focused field at once, as a paste does. */
    suspend fun insert(text: String): ActionResult {
        pointer.act { events.insertText(text) }
        return ActionResult(ok = true, backend = BACKEND)
    }

    /** The text the page has selected, or null when none is. */
    suspend fun selection(): String? {
        val value = evaluateIn(cdp, "getSelection().toString()", awaitPromise = false).value
        return (value as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }
    }

    private companion object {
        const val DOUBLE_CLICK_MS = 500L
        const val DOUBLE_CLICK_PX = 5f
        const val BACKEND = "cdp"
    }
}

/** The CSS-to-device pixel ratio of the page [cdp] is on, which input snaps points with. */
suspend fun pagePixelRatio(cdp: CdpSession): Float = dipScale(cdp.call("Page.getLayoutMetrics"))
