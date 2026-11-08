package com.fingertipsmcp.model

import com.fingertipsmcp.model.schema.Doc
import com.fingertipsmcp.model.schema.LenientEnumSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Node states a wait can ask for. Wire values match
 * [com.fingertipsmcp.model.schema.LenientEnumSerializer] and are case-insensitive.
 */
@Serializable(with = NodeStateSerializer::class)
public enum class NodeState {
    @SerialName("present") PRESENT,
    @SerialName("absent") ABSENT,
    @SerialName("visible") VISIBLE,
    @SerialName("enabled") ENABLED,
    @SerialName("disabled") DISABLED,
    @SerialName("checked") CHECKED,
    @SerialName("unchecked") UNCHECKED,
    @SerialName("focused") FOCUSED,
    @SerialName("selected") SELECTED,
}

public object NodeStateSerializer :
    LenientEnumSerializer<NodeState>(
        serialName = "com.fingertipsmcp.model.NodeState",
        wires =
            listOf(
                "present",
                "absent",
                "visible",
                "enabled",
                "disabled",
                "checked",
                "unchecked",
                "focused",
                "selected",
            ),
        values = { enumValues<NodeState>() },
    )

/** How a browser-element wait interprets [WaitCondition.BrowserElement.selector]. */
@Serializable
public enum class ElementBy {
    @SerialName("css") CSS,
    @SerialName("xpath") XPATH,
    @SerialName("text") TEXT,
}

/** Whether a browser element is in the document, and optionally has a box. */
@Serializable
public enum class ElementState {
    @SerialName("present") PRESENT,
    @SerialName("absent") ABSENT,
    @SerialName("visible") VISIBLE,
}

/** Chrome `Log.entryAdded` levels a browser-log wait can name. */
@Serializable(with = LogLevelSerializer::class)
public enum class LogLevel {
    @SerialName("verbose") VERBOSE,
    @SerialName("info") INFO,
    @SerialName("warning") WARNING,
    @SerialName("error") ERROR,
}

public object LogLevelSerializer :
    LenientEnumSerializer<LogLevel>(
        serialName = "com.fingertipsmcp.model.LogLevel",
        wires = listOf("verbose", "info", "warning", "error"),
        values = { enumValues<LogLevel>() },
    )

public val LogLevel.wire: String
    get() = LogLevelSerializer.wire(this)

/** Polymorphic on the `type` field. Browser conditions are evaluated against a CDP target. */
@Serializable
public sealed interface WaitCondition {
    @Doc("An accessibility node matches selector and state.")
    @Serializable
    @SerialName("node")
    public data class Node(
        @Doc("All non-null fields must match. String matches are case-insensitive unless noted.")
        val selector: NodeSelector,
        @Doc(
            "present, absent, visible, enabled, disabled, checked, unchecked, focused, or selected."
        )
        val state: NodeState = NodeState.PRESENT,
    ) : WaitCondition

    @Doc("The foreground window's package or title matches.")
    @Serializable
    @SerialName("window")
    public data class Window(
        @Doc("Foreground package name.") @SerialName("package") val packageName: String? = null,
        @Doc("Case-insensitive substring of a window title.") val titleContains: String? = null,
    ) : WaitCondition

    @Doc("The soft keyboard is visible or hidden.")
    @Serializable
    @SerialName("ime")
    public data class Ime(@Doc("True when the keyboard is on screen.") val visible: Boolean) :
        WaitCondition

    @Doc("No UI content changes for quietMs.")
    @Serializable
    @SerialName("idle")
    public data class Idle(
        @Doc("How long the UI must stay quiet, in milliseconds.") val quietMs: Long = 500
    ) : WaitCondition

    @Doc(
        "The screen is on or off, or a person holds it in the viewer. Pass on, person, or both. With person alone it does not use the screen."
    )
    @Serializable
    @SerialName("screen")
    public data class Screen(
        @Doc("True when the screen is on.") val on: Boolean? = null,
        @Doc(
            "True while a person holds the screen in the viewer, false while nobody does. It is the state now, so true does not match once they have finished."
        )
        val person: Boolean? = null,
    ) : WaitCondition

    @Doc("The keyguard is locked or dismissed.")
    @Serializable
    @SerialName("keyguard")
    public data class Keyguard(@Doc("True when the keyguard is showing.") val locked: Boolean) :
        WaitCondition

    @Doc("The page URL equals, contains, or matches a regular expression.")
    @Serializable
    @SerialName("browser.url")
    public data class BrowserUrl(
        @Doc("Browser target id. Omit only when one page is open.") val target: String? = null,
        @Doc("URL must equal this string.") val equals: String? = null,
        @Doc("URL must contain this string.") val contains: String? = null,
        @Doc("URL must match this regular expression.") val regex: String? = null,
    ) : WaitCondition

    /**
     * `DOMContentLoaded`, `load`, `networkAlmostIdle`, `networkIdle`, `firstMeaningfulPaint`, ...
     */
    @Doc("A page lifecycle event has fired.")
    @Serializable
    @SerialName("browser.lifecycle")
    public data class BrowserLifecycle(
        @Doc("Browser target id. Omit only when one page is open.") val target: String? = null,
        @Doc("Lifecycle name, such as load or networkIdle.") val name: String,
    ) : WaitCondition

    @Doc("Fewer than maxInflight requests are in flight for quietMs.")
    @Serializable
    @SerialName("browser.networkIdle")
    public data class BrowserNetworkIdle(
        @Doc("Browser target id. Omit only when one page is open.") val target: String? = null,
        @Doc("Maximum requests still in flight.") val maxInflight: Int = 0,
        @Doc("How long the network must stay quiet, in milliseconds.") val quietMs: Long = 500,
    ) : WaitCondition

    @Doc("A DOM node matches selector.")
    @Serializable
    @SerialName("browser.element")
    public data class BrowserElement(
        @Doc("Browser target id. Omit only when one page is open.") val target: String? = null,
        @Doc("CSS selector, XPath, or plain text, depending on by.") val selector: String,
        @Doc("css, xpath, or text.") val by: ElementBy = ElementBy.CSS,
        @Doc("present, absent, or visible.") val state: ElementState = ElementState.PRESENT,
    ) : WaitCondition

    @Doc("A network response matches the URL, method, or status.")
    @Serializable
    @SerialName("browser.request")
    public data class BrowserRequest(
        @Doc("Browser target id. Omit only when one page is open.") val target: String? = null,
        @Doc("Response URL must contain this string.") val urlContains: String? = null,
        @Doc("Response URL must match this regular expression.") val urlRegex: String? = null,
        @Doc("HTTP method, case-insensitive.") val method: String? = null,
        @Doc("HTTP status code.") val status: Int? = null,
    ) : WaitCondition

    @Doc(
        "A JavaScript dialog, such as an alert, is open. One that opened before the wait counts only while Chrome shows it on screen."
    )
    @Serializable
    @SerialName("browser.dialog")
    public data class BrowserDialog(
        @Doc("Browser target id. Omit only when one page is open.") val target: String? = null
    ) : WaitCondition

    @Doc("A new browser target has opened.")
    @Serializable
    @SerialName("browser.newTarget")
    public data class BrowserNewTarget(
        @Doc("New target URL must contain this string.") val urlContains: String? = null
    ) : WaitCondition

    @Doc("A console message matches level or text.")
    @Serializable
    @SerialName("browser.log")
    public data class BrowserLog(
        @Doc("Browser target id. Omit only when one page is open.") val target: String? = null,
        @Doc("verbose, info, warning, or error. Omit to match any level.")
        val level: LogLevel? = null,
        @Doc("Message text must contain this string.") val textContains: String? = null,
    ) : WaitCondition

    @Doc(
        "The page's document has loaded, as its load event says. Matches at once on a page that already has. Unlike browser.lifecycle, it does not wait for another load."
    )
    @Serializable
    @SerialName("browser.loaded")
    public data class BrowserLoaded(
        @Doc("Browser target id. Omit only when one page is open.") val target: String? = null
    ) : WaitCondition

    @Doc(
        "The page has loaded, and since then the layer tree has been unchanged for quietMs with no requests in flight."
    )
    @Serializable
    @SerialName("browser.settled")
    public data class BrowserSettled(
        @Doc("Browser target id. Omit only when one page is open.") val target: String? = null,
        @Doc("How long the page must stay settled, in milliseconds.") val quietMs: Long = 500,
    ) : WaitCondition
}

@Serializable
public data class WaitRequest(
    @Doc(
        "Every condition must match. Each object's type is node, window, ime, idle, screen, keyguard, or browser.*."
    )
    val all: List<WaitCondition> = emptyList(),
    @Doc("At least one condition must match. Same types as all.")
    val any: List<WaitCondition> = emptyList(),
    @Doc("Give up after this many milliseconds.") val timeoutMs: Long = 10_000,
    @Doc("After a match, keep waiting until the UI is quiet for this many milliseconds.")
    val settleMs: Long = 0,
    @Doc("Include a fresh UI snapshot in the result.") val snapshot: Boolean = false,
    val snapshotFormat: SnapshotFormat = SnapshotFormat.COMPACT,
    @Doc(
        "How to cut and mask that snapshot, as for GET /v1/ui/snapshot. Its format is snapshotFormat's."
    )
    val snapshotOptions: SnapshotOptions? = null,
)

@Serializable
public data class WaitResult(
    val matched: Boolean,
    val timedOut: Boolean,
    val elapsedMs: Long,
    /** Indexes of satisfied conditions within `all` / `any`. */
    val matchedAll: List<Int> = emptyList(),
    val matchedAny: List<Int> = emptyList(),
    val snapshot: UiSnapshot? = null,
    val state: DeviceStateSummary? = null,
)
