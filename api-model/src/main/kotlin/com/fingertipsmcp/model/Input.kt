package com.fingertipsmcp.model

import com.fingertipsmcp.model.schema.Doc
import com.fingertipsmcp.model.schema.LenientEnumSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
public data class TapRequest(
    @Doc("Screen x in pixels. Omit when selector is set.") val x: Float? = null,
    @Doc("Screen y in pixels. Omit when selector is set.") val y: Float? = null,
    @Doc("Tap a node instead of coordinates. The point is jittered inside its visible bounds.")
    val selector: NodeSelector? = null,
    @Doc("How many times to tap. Defaults to 1.") val count: Int = 1,
    @Doc("Hold the pointer down for this many milliseconds.") val holdMs: Long? = null,
    @Doc("Jitter the point, timing, and finger contact. Defaults to true.")
    val humanize: Boolean = true,
    @Doc("Tap even when the node is covered by the keyboard or another window.")
    val force: Boolean = false,
    @Doc("Turn the screen on first. Leaves the keyguard up. Defaults to true.")
    val autoWake: Boolean = true,
)

/** Direction of a swipe that does not name [SwipeRequest.from] and [SwipeRequest.to]. */
@Serializable(with = SwipeDirectionSerializer::class)
public enum class SwipeDirection {
    @SerialName("up") UP,
    @SerialName("down") DOWN,
    @SerialName("left") LEFT,
    @SerialName("right") RIGHT,
}

public object SwipeDirectionSerializer :
    LenientEnumSerializer<SwipeDirection>(
        serialName = "com.fingertipsmcp.model.SwipeDirection",
        wires = listOf("up", "down", "left", "right"),
        values = { enumValues<SwipeDirection>() },
    )

@Serializable
public data class SwipeRequest(
    @Doc("Start point. Pass both from and to, or a direction.") val from: Point? = null,
    @Doc("End point. Pass both from and to, or a direction.") val to: Point? = null,
    /**
     * Alternative to from/to: swipe inside a node in a direction (`up`, `down`, `left`, `right`).
     */
    @Doc("Swipe inside this node. Omit to swipe across the screen.")
    val selector: NodeSelector? = null,
    @Doc("up, down, left, or right. Use instead of from and to.")
    val direction: SwipeDirection? = null,
    @Doc("Fraction of the node or screen to travel when using direction.")
    val distance: Float = 0.6f,
    @Doc("How long the swipe takes, in milliseconds.") val durationMs: Long = 300,
    @Doc("Keep velocity at release instead of stopping before the pointer lifts.")
    val fling: Boolean = true,
    @Doc("Jitter the path, timing, and finger contact. Defaults to true.")
    val humanize: Boolean = true,
    @Doc("Turn the screen on first. Leaves the keyguard up. Defaults to true.")
    val autoWake: Boolean = true,
)

@Serializable public data class TimedPoint(val x: Float, val y: Float, val tMs: Long)

/** One contact change on `WS /v1/input/pointer`. `cancel` omits the other fields. */
@Serializable(with = PointerOpSerializer::class)
public enum class PointerOp {
    @SerialName("down") DOWN,
    @SerialName("move") MOVE,
    @SerialName("up") UP,
    @SerialName("cancel") CANCEL,
}

public object PointerOpSerializer :
    LenientEnumSerializer<PointerOp>(
        serialName = "com.fingertipsmcp.model.PointerOp",
        wires = listOf("down", "move", "up", "cancel"),
        values = { enumValues<PointerOp>() },
    )

@Serializable
public data class PointerFrame(
    @Doc("down, move, up, or cancel.") val op: PointerOp,
    @Doc("Contact id, 0 through 9. Omit on cancel.") val id: Int = 0,
    @Doc("Screen x in pixels. Omit on cancel.") val x: Float = 0f,
    @Doc("Screen y in pixels. Omit on cancel.") val y: Float = 0f,
    @Doc("Milliseconds since this gesture's first down. Omit on cancel.") val tMs: Long = 0,
)

@Serializable
public data class GestureRequest(
    /** One path per pointer; times are relative to the gesture start. */
    val pointers: List<List<TimedPoint>>,
    @Doc("Jitter the path and vary the finger contact. Defaults to true.")
    val humanize: Boolean = true,
    @Doc("Turn the screen on first. Leaves the keyguard up. Defaults to true.")
    val autoWake: Boolean = true,
)

@Serializable
public data class KeyRequest(
    /**
     * `BACK`, `HOME`, `RECENTS`, `ENTER`, `DEL`, `TAB`, `NOTIFICATIONS`, `QUICK_SETTINGS`, `POWER`,
     * or any `KEYCODE_*` name.
     */
    val key: String,
    val longPress: Boolean = false,
    val metaState: Int = 0,
    @Doc("Turn the screen on first. Leaves the keyguard up. Defaults to true.")
    val autoWake: Boolean = true,
)

@Serializable
public enum class TextMode {
    @SerialName("auto") AUTO,
    /** Tap the on-screen keyboard's keys with real touches. */
    @SerialName("keyboard") KEYBOARD,
    /** Helper KeyEvent injection (looks like a hardware keyboard). */
    @SerialName("keyevent") KEYEVENT,
    /** ACTION_SET_TEXT; no input events at all. */
    @SerialName("setText") SET_TEXT,
}

@Serializable
public data class TextRequest(
    @Doc("Characters to type.") val text: String,
    @Doc("Focus this node first. Omit to type into the focused field.")
    val selector: NodeSelector? = null,
    @Doc(
        "auto, keyboard, keyevent, or setText. auto taps the on-screen keyboard, then uses setText."
    )
    val mode: TextMode = TextMode.AUTO,
    @Doc("Clear the field before typing.") val clear: Boolean = false,
    @Doc("Press the IME action (Enter, Go, or Search) afterwards.") val submit: Boolean = false,
    @Doc("Minimum delay between characters, in milliseconds.") val minDelayMs: Long = 40,
    @Doc("Maximum delay between characters, in milliseconds.") val maxDelayMs: Long = 140,
    @Doc("Turn the screen on first. Leaves the keyguard up. Defaults to true.")
    val autoWake: Boolean = true,
)

@Serializable
public data class ClipboardReadRequest(
    @Doc(
        "Press KEYCODE_COPY first, so the focused app copies its selection, and wait up to 1 s for the clipboard to change."
    )
    val copy: Boolean = false,
    @Doc("Turn the screen on first, for copy. Leaves the keyguard up. Defaults to true.")
    val autoWake: Boolean = true,
)

@Serializable
public data class ClipboardContent(
    @Doc("The clip's text. Absent when the clipboard is empty or holds no text, such as an image.")
    val text: String? = null,
    @Doc("The clip's MIME types, such as text/plain. Empty when the clipboard is empty.")
    val mimeTypes: List<String> = emptyList(),
    @Doc("The app that copied it marked it sensitive, as password managers do.")
    val sensitive: Boolean = false,
    @Doc(
        "With copy, whether the clipboard changed after KEYCODE_COPY. False means it still holds what was there before."
    )
    val changed: Boolean? = null,
)

@Serializable
public data class ClipboardWriteRequest(
    @Doc("Plain text for the clipboard, up to 100000 characters.") val text: String,
    @Doc("Press KEYCODE_PASTE afterwards, so the focused field pastes it.")
    val paste: Boolean = false,
    @Doc("Turn the screen on first, for paste. Leaves the keyguard up. Defaults to true.")
    val autoWake: Boolean = true,
)

@Serializable
public data class ImeShowRequest(
    @Doc(
        "Editable node to focus, the same selector as tap, including ref. Omit to use the field that already has input focus."
    )
    val selector: NodeSelector? = null,
    @Doc("Turn the screen on first. Leaves the keyguard up. Defaults to true.")
    val autoWake: Boolean = true,
)

@Serializable
public data class ActionResult(
    val ok: Boolean,
    val backend: String? = null,
    val woke: Boolean = false,
    val message: String? = null,
    /** The point(s) actually touched, after humanization. */
    val points: List<Point> = emptyList(),
    val seq: Long? = null,
)

@Serializable
public data class LaunchRequest(
    @Doc("Clear the app's task and start it fresh.") val fresh: Boolean = false,
    @Doc("An activity to start instead of the launcher's.") val activity: String? = null,
    @Doc("Wait for the app to reach the foreground. Defaults to true.") val wait: Boolean = true,
)

@Serializable
public data class IntentRequest(
    val action: String = "android.intent.action.VIEW",
    val data: String? = null,
    @SerialName("package") val packageName: String? = null,
    val component: String? = null,
    val categories: List<String> = emptyList(),
    val extras: Map<String, String> = emptyMap(),
    val flags: Int? = null,
)

@Serializable
public data class AppInfo(
    @SerialName("package") val packageName: String,
    val label: String,
    val versionName: String? = null,
    val versionCode: Long,
    val system: Boolean,
    val enabled: Boolean,
    val launchable: Boolean,
)

@Serializable
public data class UnlockRequest(
    /** Use the PIN stored in the device config if the keyguard is secure. */
    val useStoredPin: Boolean = true
)
