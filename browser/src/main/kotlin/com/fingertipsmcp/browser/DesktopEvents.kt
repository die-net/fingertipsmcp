package com.fingertipsmcp.browser

import com.fingertipsmcp.model.Point
import kotlin.math.roundToInt
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Where a desktop's mouse and keyboard events go, with points in CSS viewport pixels.
 * [CdpDesktopEvents] sends them to one page over DevTools. An OS backend (XTEST or uinput on Linux,
 * CGEvent on macOS) implements the same calls by mapping viewport points to the screen, so the
 * humanized plans in [DesktopPageInput] do not change with it.
 */
internal interface DesktopEvents {
    /** Moves the pointer. [buttons] is the DOM bitmask held during the move: 1 while dragging. */
    suspend fun mouseMove(at: Point, buttons: Int)

    suspend fun mouseDown(at: Point)

    suspend fun mouseUp(at: Point)

    /** One wheel notch at [at], in CSS pixels of scroll. */
    suspend fun wheel(at: Point, dx: Float, dy: Float)

    /** A key going down. [commands] are editing commands macOS needs for shortcuts. */
    suspend fun keyDown(key: DomKey, modifiers: Int, location: Int, commands: List<String>)

    suspend fun keyUp(key: DomKey, modifiers: Int, location: Int)

    /** Text no key produces, committed the way an input method does. */
    suspend fun insertText(text: String)
}

/**
 * Mouse and keyboard over `Input.dispatchMouseEvent` and `Input.dispatchKeyEvent`. Points are
 * snapped to the device pixel grid, as a real pointer's are, using [devicePixelRatio].
 */
internal class CdpDesktopEvents(
    private val cdp: CdpSession,
    private val devicePixelRatio: Float,
) : DesktopEvents {
    override suspend fun mouseMove(at: Point, buttons: Int) {
        cdp.call(
            "Input.dispatchMouseEvent",
            mouse("mouseMoved", at, if (buttons != 0) "left" else "none", buttons, 0),
        )
    }

    override suspend fun mouseDown(at: Point) {
        cdp.call("Input.dispatchMouseEvent", mouse("mousePressed", at, "left", 1, 1))
    }

    override suspend fun mouseUp(at: Point) {
        cdp.call("Input.dispatchMouseEvent", mouse("mouseReleased", at, "left", 0, 1))
    }

    override suspend fun wheel(at: Point, dx: Float, dy: Float) {
        val event = buildJsonObject {
            put("type", "mouseWheel")
            put("x", snap(at.x))
            put("y", snap(at.y))
            put("deltaX", dx)
            put("deltaY", dy)
            put("pointerType", "mouse")
        }
        cdp.call("Input.dispatchMouseEvent", event)
    }

    override suspend fun keyDown(
        key: DomKey,
        modifiers: Int,
        location: Int,
        commands: List<String>,
    ) {
        val withText = key.text != null && modifiers and NON_SHIFT_MODIFIERS == 0
        val event =
            keyEvent(if (withText) "keyDown" else "rawKeyDown", key, modifiers, location) {
                if (withText) {
                    put("text", key.text)
                    put("unmodifiedText", key.text)
                }
                if (commands.isNotEmpty()) put("commands", JsonArray(commands.map(::JsonPrimitive)))
            }
        cdp.call("Input.dispatchKeyEvent", event)
    }

    override suspend fun keyUp(key: DomKey, modifiers: Int, location: Int) {
        cdp.call("Input.dispatchKeyEvent", keyEvent("keyUp", key, modifiers, location) {})
    }

    override suspend fun insertText(text: String) {
        cdp.call("Input.insertText", buildJsonObject { put("text", text) })
    }

    private fun mouse(
        type: String,
        at: Point,
        button: String,
        buttons: Int,
        clicks: Int,
    ): JsonObject = buildJsonObject {
        put("type", type)
        put("x", snap(at.x))
        put("y", snap(at.y))
        put("button", button)
        put("buttons", buttons)
        put("clickCount", clicks)
        put("pointerType", "mouse")
    }

    private fun keyEvent(
        type: String,
        key: DomKey,
        modifiers: Int,
        location: Int,
        extra: JsonObjectBuilder.() -> Unit,
    ): JsonObject = buildJsonObject {
        put("type", type)
        put("key", key.key)
        put("code", key.code)
        put("windowsVirtualKeyCode", key.virtualKey)
        put("nativeVirtualKeyCode", key.virtualKey)
        put("modifiers", modifiers)
        if (location != 0) put("location", location)
        extra()
    }

    /** A real pointer sits on whole device pixels, so its CSS position is a multiple of 1/dpr. */
    private fun snap(value: Float): Float {
        val ratio = devicePixelRatio.takeIf { it > 0f } ?: 1f
        return (value * ratio).roundToInt() / ratio
    }

    private companion object {
        /** Alt, Ctrl, and Meta: with any of them held a key types nothing. */
        const val NON_SHIFT_MODIFIERS = 1 or 2 or 4
    }
}
