package net.die.phoneapi.browser

import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.die.phoneapi.helper.TouchscreenInfo
import net.die.phoneapi.input.FingerContact
import net.die.phoneapi.input.FingerShape
import net.die.phoneapi.input.TouchPhase
import net.die.phoneapi.input.TouchSample
import net.die.phoneapi.input.touchTimeline
import net.die.phoneapi.model.Point
import net.die.phoneapi.model.TimedPoint

/** Plays pointer paths on the open page with `Input.dispatchTouchEvent`. */
internal suspend fun dispatchTouchPath(
    cdp: CdpSession,
    pointers: List<List<TimedPoint>>,
    fingers: List<List<FingerShape>>,
    axes: TouchscreenInfo,
    dipScale: Float,
) {
    val samples = touchTimeline(pointers, fingers)
    val active = LinkedHashMap<Int, TouchSpot>()
    var last = 0L
    var index = 0
    while (index < samples.size) {
        val at = samples[index].tMs
        val wait = at - last
        if (wait > 0) delay(wait)
        last = at
        val batch = ArrayList<TouchSample>()
        while (index < samples.size && samples[index].tMs == at) {
            batch += samples[index]
            index++
        }
        playBatch(cdp, active, batch, axes, dipScale)
    }
}

/**
 * A left click with `Input.dispatchMouseEvent`: pressed at the path's first point, held for the
 * path's duration, and released at its last point. Chrome delivers these to a hidden tab, where it
 * holds touch events, once [pressPastHold] gets the press there. There is no mouseMoved first: a
 * hidden Chrome delivers it but never answers.
 */
internal suspend fun dispatchMouseClick(cdp: CdpSession, path: List<TimedPoint>) {
    val down = path.first()
    val up = path.last()
    val landed =
        pressPastHold(cdp, "pointerdown") {
            cdp.call("Input.dispatchMouseEvent", mouseEvent("mousePressed", down, pressed = true))
        }
    val hold = up.tMs - down.tMs
    if (landed && hold > 0) delay(hold)
    cdp.call("Input.dispatchMouseEvent", mouseEvent("mouseReleased", up, pressed = false))
    if (!landed) throw inputDropped()
}

/** `Input.insertText`, with an optional select-all clear and Enter. */
internal suspend fun dispatchInsertText(
    cdp: CdpSession,
    text: String,
    clear: Boolean,
    submit: Boolean,
) {
    if (clear) {
        dispatchDomKey(cdp, domKey("A", CTRL), cdpModifiers(CTRL), longPress = false)
        dispatchDomKey(cdp, domKey("DEL", 0), modifiers = 0, longPress = false)
    }
    if (text.isNotEmpty()) {
        cdp.call("Input.insertText", buildJsonObject { put("text", text) })
    }
    if (submit) {
        dispatchDomKey(cdp, domKey("ENTER", 0), modifiers = 0, longPress = false)
    }
}

internal suspend fun dispatchDomKey(
    cdp: CdpSession,
    key: DomKey,
    modifiers: Int,
    longPress: Boolean,
) {
    val withText = key.text != null && modifiers == 0
    val down = if (withText) "keyDown" else "rawKeyDown"
    val landed =
        pressPastHold(cdp, "keydown") {
            cdp.call("Input.dispatchKeyEvent", keyEvent(down, key, modifiers, withText))
        }
    if (landed && longPress) delay(LONG_PRESS_MS)
    cdp.call("Input.dispatchKeyEvent", keyEvent("keyUp", key, modifiers, withText = false))
    if (!landed) throw inputDropped()
}

private suspend fun playBatch(
    cdp: CdpSession,
    active: LinkedHashMap<Int, TouchSpot>,
    batch: List<TouchSample>,
    axes: TouchscreenInfo,
    dipScale: Float,
) {
    val starting = batch.filter { it.phase == TouchPhase.DOWN }
    val moving = batch.filter { it.phase == TouchPhase.MOVE }
    val ending = batch.filter { it.phase == TouchPhase.UP }
    val wasEmpty = active.isEmpty()
    for (sample in starting) active[sample.pointer] = spot(sample)
    if (starting.isNotEmpty()) {
        val points =
            if (wasEmpty) active.entries.map { it.key to it.value }
            else starting.map { it.pointer to spot(it) }
        sendTouch(cdp, "touchStart", points, axes, dipScale)
    }
    for (sample in moving) active[sample.pointer] = spot(sample)
    if (moving.isNotEmpty()) {
        sendTouch(cdp, "touchMove", active.entries.map { it.key to it.value }, axes, dipScale)
    }
    for (sample in ending) active.remove(sample.pointer)
    if (ending.isEmpty()) return
    if (active.isEmpty()) sendTouch(cdp, "touchEnd", emptyList(), axes, dipScale)
    else sendTouch(cdp, "touchMove", active.entries.map { it.key to it.value }, axes, dipScale)
}

private fun spot(sample: TouchSample) = TouchSpot(Point(sample.x, sample.y), sample.shape)

private data class TouchSpot(val at: Point, val shape: FingerShape)

private suspend fun sendTouch(
    cdp: CdpSession,
    type: String,
    points: List<Pair<Int, TouchSpot>>,
    axes: TouchscreenInfo,
    dipScale: Float,
) {
    cdp.call(
        "Input.dispatchTouchEvent",
        buildJsonObject {
            put("type", type)
            put(
                "touchPoints",
                JsonArray(points.map { touchPoint(it.first, it.second, axes, dipScale) }),
            )
        },
    )
}

private fun touchPoint(
    id: Int,
    spot: TouchSpot,
    axes: TouchscreenInfo,
    dipScale: Float,
): JsonObject {
    val contact = FingerContact.from(axes, spot.shape).toCdp(dipScale)
    return buildJsonObject {
        put("x", spot.at.x)
        put("y", spot.at.y)
        put("radiusX", contact.radiusX)
        put("radiusY", contact.radiusY)
        put("rotationAngle", contact.rotationAngle)
        put("force", contact.force)
        put("id", id)
    }
}

private fun mouseEvent(type: String, at: TimedPoint, pressed: Boolean): JsonObject =
    buildJsonObject {
        put("type", type)
        put("x", at.x)
        put("y", at.y)
        put("button", "left")
        put("buttons", if (pressed) 1 else 0)
        put("clickCount", 1)
        put("pointerType", "mouse")
    }

private fun keyEvent(type: String, key: DomKey, modifiers: Int, withText: Boolean): JsonObject =
    buildJsonObject {
        put("type", type)
        put("key", key.key)
        put("code", key.code)
        put("windowsVirtualKeyCode", key.virtualKey)
        put("nativeVirtualKeyCode", key.virtualKey)
        put("modifiers", modifiers)
        if (withText && key.text != null) {
            put("text", key.text)
            put("unmodifiedText", key.text)
        }
    }

/** Android META_CTRL_ON, so select-all does not depend on [android.view.KeyEvent]. */
private const val CTRL = 4_096

private const val LONG_PRESS_MS = 400L
