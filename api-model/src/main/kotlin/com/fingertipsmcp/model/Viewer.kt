package com.fingertipsmcp.model

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator
import kotlinx.serialization.json.JsonElement

/** A text frame the server sends on the viewer's input socket, `WS /v1/input/pointer`. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("op")
public sealed interface ViewerMessage

/** Who holds the screen, as the viewer page shows it. */
@Serializable
public enum class ViewerHolder {
    @SerialName("none") NONE,
    /** This socket's viewer. */
    @SerialName("you") YOU,
    /** Another viewer. */
    @SerialName("person") PERSON,
    /** A REST session. */
    @SerialName("script") SCRIPT,
    /** An MCP session. */
    @SerialName("agent") AGENT,
}

/**
 * The screen's holder changed. [takenFrom] is set when a viewer took the screen from a session, and
 * says whether that was an agent or a script.
 */
@Serializable
@SerialName("lease")
public data class ViewerLease(
    val holder: ViewerHolder,
    val heldMs: Long,
    val takenFrom: ViewerHolder? = null,
) : ViewerMessage

/** The reply to `copy`. [image] is the image's MIME type; its bytes follow in a binary frame. */
@Serializable
@SerialName("clipboard")
public data class ViewerClipboard(
    val id: JsonElement? = null,
    val text: String? = null,
    val image: String? = null,
    val changed: Boolean,
) : ViewerMessage

/**
 * `{"op":"take"}` from the page: take the screen at once, from whoever has it. [autoWake] turns the
 * screen on, leaving the keyguard up, as the first input would.
 */
@Serializable public data class ViewerTake(val autoWake: Boolean = true)

/** A request from the page failed. [id] echoes the request's own `id`. */
@Serializable
@SerialName("error")
public data class ViewerError(val id: JsonElement? = null, val message: String) : ViewerMessage

/**
 * The text frame the video socket sends before binary frames, and again when the encoded size or
 * the screen's size changes. [width] and [height] are the encoded picture. [displayWidth] and
 * [displayHeight] are the screen the input socket's `x` and `y` point into, so a viewer maps a
 * point on the picture with these alone: the phone's screen in pixels, in its current rotation, or
 * a desktop tab's viewport in CSS pixels.
 */
@Serializable
public data class VideoHeader(
    val codec: String,
    val width: Int,
    val height: Int,
    val fps: Int,
    val bitRate: Int,
    val displayWidth: Int,
    val displayHeight: Int,
    /** What the input socket's pointer drives, so the page sends touch or mouse input. */
    val pointer: ViewerPointer = ViewerPointer.TOUCH,
)

/** What a viewer's pointer stands for. */
@Serializable
public enum class ViewerPointer {
    /** A phone's touchscreen: contacts, and the phone's buttons. */
    @SerialName("touch") TOUCH,
    /** A desktop browser's mouse: contacts are the left button, with hover and the wheel too. */
    @SerialName("mouse") MOUSE,
}

/** `{"op":"hover"}` from a mouse viewer: the pointer moved with no button down, to [x], [y]. */
@Serializable public data class ViewerHover(val x: Float, val y: Float)

/**
 * `{"op":"wheel"}` from a mouse viewer: the wheel turned by [dx], [dy] CSS pixels with the pointer
 * at [x], [y].
 */
@Serializable
public data class ViewerWheel(val x: Float, val y: Float, val dx: Float, val dy: Float)

/** The text frame the audio socket sends once before binary frames. */
@Serializable
public data class AudioHeader(val codec: String, val sampleRate: Int, val channels: Int)
