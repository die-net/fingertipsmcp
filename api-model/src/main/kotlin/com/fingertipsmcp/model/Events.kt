package com.fingertipsmcp.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.serializer

/** Streamed over `WS /v1/events` as one JSON object per text frame. */
@Serializable
public data class Event(
    /**
     * e.g. `ui.changed`, `window.changed`, `ime.shown`, `ime.hidden`, `screen.on`, `screen.off`,
     * `user.present`, `toast`, `logcat`, `helper.status`, `screen.taken`.
     */
    val type: String,
    val timestampMs: Long,
    val seq: Long,
    val data: JsonObject = JsonObject(emptyMap()),
)

public object EventTypes {
    public const val UI_CHANGED: String = "ui.changed"
    public const val WINDOW_CHANGED: String = "window.changed"
    public const val IME_SHOWN: String = "ime.shown"
    public const val IME_HIDDEN: String = "ime.hidden"
    public const val SCREEN_ON: String = "screen.on"
    public const val SCREEN_OFF: String = "screen.off"
    public const val USER_PRESENT: String = "user.present"
    public const val TOAST: String = "toast"
    public const val LOGCAT: String = "logcat"
    public const val HELPER_STATUS: String = "helper.status"
    /**
     * An MCP session without the screen brought an app forward: `session` is its hash, `package`
     * what came forward.
     */
    public const val SCREEN_TAKEN: String = "screen.taken"
}

/**
 * The `data` of an [Event]. Each class is encoded on its own, so `data` carries no type field; the
 * event's `type` says which class it is.
 */
public sealed interface EventData

/** `ui.changed`: the UI sequence number moved. */
@Serializable
public data class UiChangedData(
    val seq: Long,
    @SerialName("package") val packageName: String? = null,
) : EventData

/** `window.changed`. [changes] names what changed, such as `state`, `added`, or `removed`. */
@Serializable
public data class WindowChangedData(
    val windowId: Int,
    @SerialName("package") val packageName: String? = null,
    val title: String? = null,
    val className: String? = null,
    val changes: List<String>,
) : EventData

/** `ime.shown` and `ime.hidden`: the keyboard's package and, while shown, its bounds. */
@Serializable
public data class ImeData(
    @SerialName("package") val packageName: String? = null,
    val left: Int? = null,
    val top: Int? = null,
    val right: Int? = null,
    val bottom: Int? = null,
) : EventData

/** `toast`. */
@Serializable
public data class ToastData(
    val text: String,
    @SerialName("package") val packageName: String? = null,
) : EventData

/**
 * `logcat`: one `logcat -v threadtime` line. [time] is logcat's timestamp, not the receive time.
 */
@Serializable
public data class LogcatLine(
    val time: String,
    val pid: Long,
    val tid: Long,
    val level: String,
    val tag: String,
    val message: String,
) : EventData

/** `helper.status`. */
@Serializable public data class HelperStatusData(val status: HelperStatus) : EventData

/** `screen.taken`: [session] is the hash of the session that brought [packageName] forward. */
@Serializable
public data class ScreenTakenData(
    val session: String,
    @SerialName("package") val packageName: String,
) : EventData

/**
 * Encodes [data] as its own class, which is what an [Event]'s `data` holds. It uses
 * `serializer<T>()` because detekt can't see the `serializer()` the plugin generates in this
 * module.
 */
public fun Json.encodeEventData(data: EventData): JsonObject =
    when (data) {
        is UiChangedData -> encodeToJsonElement(serializer<UiChangedData>(), data)
        is WindowChangedData -> encodeToJsonElement(serializer<WindowChangedData>(), data)
        is ImeData -> encodeToJsonElement(serializer<ImeData>(), data)
        is ToastData -> encodeToJsonElement(serializer<ToastData>(), data)
        is LogcatLine -> encodeToJsonElement(serializer<LogcatLine>(), data)
        is HelperStatusData -> encodeToJsonElement(serializer<HelperStatusData>(), data)
        is ScreenTakenData -> encodeToJsonElement(serializer<ScreenTakenData>(), data)
    }.jsonObject
