package com.fingertipsmcp.browser

import com.fingertipsmcp.model.Point
import kotlin.math.hypot
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * A person's mouse and keyboard on one desktop page, from the viewer, at CSS viewport points.
 * Unlike [DesktopPageInput], nothing is planned or humanized: the person's own hand already moves
 * the pointer and types. Clicks close together in time and place count as a double click, as an OS
 * counts them.
 */
class ViewerPageInput(
    private val cdp: CdpSession,
    devicePixelRatio: Float,
    /** Monotonic milliseconds, for telling a double click. */
    private val now: () -> Long,
) {
    private val events = CdpDesktopEvents(cdp, devicePixelRatio)
    private var lastPress: Point? = null
    private var lastPressAt = 0L
    private var clicks = 1

    /** Moves the pointer, with the left button held when [pressed]. */
    suspend fun move(at: Point, pressed: Boolean) {
        events.mouseMove(at, buttons = if (pressed) 1 else 0)
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
        events.mouseMove(at, buttons = 0)
        events.mouseDown(at, clicks)
    }

    suspend fun release(at: Point) {
        events.mouseUp(at, clicks)
    }

    /** One turn of the wheel, by [dx] and [dy] CSS pixels of scroll. */
    suspend fun wheel(at: Point, dx: Float, dy: Float) {
        events.wheel(at, dx, dy)
    }

    /** A named key, as a key request names it, with Android's [metaState] for its modifiers. */
    suspend fun key(name: String, metaState: Int) {
        val key = domKey(name, metaState)
        val modifiers = cdpModifiers(metaState)
        events.keyDown(key, modifiers, 0, emptyList())
        events.keyUp(key, modifiers, 0)
    }

    /**
     * Types [text] key by key on a US layout, with Shift held for the characters that need it. Text
     * no key types, such as emoji, is inserted as an input method commits it.
     */
    suspend fun type(text: String) {
        val keyless = StringBuilder()
        for (c in text) {
            val stroke = usKeystroke(c).takeUnless { c.isSurrogate() }
            if (stroke == null) {
                keyless.append(c)
                continue
            }
            if (keyless.isNotEmpty()) insert(keyless.toString().also { keyless.clear() })
            if (stroke.shift) events.keyDown(SHIFT_KEY, SHIFT_MODIFIER, LEFT, emptyList())
            val modifiers = if (stroke.shift) SHIFT_MODIFIER else 0
            events.keyDown(stroke.key, modifiers, 0, emptyList())
            events.keyUp(stroke.key, modifiers, 0)
            if (stroke.shift) events.keyUp(SHIFT_KEY, 0, LEFT)
        }
        if (keyless.isNotEmpty()) insert(keyless.toString())
    }

    /** Puts [text] in the focused field at once, as a paste does. */
    suspend fun insert(text: String) {
        events.insertText(text)
    }

    /** The text the page has selected, or null when none is. */
    suspend fun selection(): String? {
        val value = evaluateIn(cdp, "getSelection().toString()", awaitPromise = false).value
        return (value as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }
    }

    private companion object {
        const val DOUBLE_CLICK_MS = 500L
        const val DOUBLE_CLICK_PX = 5f
    }
}

/** The CSS-to-device pixel ratio of the page [cdp] is on, which input snaps points with. */
suspend fun pagePixelRatio(cdp: CdpSession): Float = dipScale(cdp.call("Page.getLayoutMetrics"))
