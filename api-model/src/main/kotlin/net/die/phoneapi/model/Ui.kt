package net.die.phoneapi.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import net.die.phoneapi.model.schema.Doc

@Serializable
public data class UiNode(
    /** Short handle (`e12`) valid until the node disappears; pass back to actions. */
    val ref: String,
    /** Simplified role derived from className / roleDescription, e.g. `button`, `text`, `edit`. */
    val role: String,
    val className: String? = null,
    val text: String? = null,
    val desc: String? = null,
    val hint: String? = null,
    /** Resource id without the package prefix when it matches the window's package. */
    val id: String? = null,
    val bounds: Rect,
    /**
     * e.g. `clickable`, `checked`, `selected`, `focused`, `disabled`, `editable`, `password`,
     * `scrollable`, `obscured`.
     */
    val states: List<String> = emptyList(),
    val actions: List<String> = emptyList(),
    val children: List<UiNode> = emptyList(),
)

@Serializable
public data class UiWindow(
    val id: Int,
    /**
     * `application`, `input_method`, `system`, `accessibility_overlay`, `split_divider`,
     * `magnification`.
     */
    val type: String,
    val title: String? = null,
    @SerialName("package") val packageName: String? = null,
    val layer: Int,
    val focused: Boolean,
    val active: Boolean,
    val bounds: Rect,
    val root: UiNode? = null,
)

@Serializable
public data class UiSnapshot(
    /** Monotonic counter bumped on every observed UI change. */
    val seq: Long,
    val timestampMs: Long,
    val state: DeviceStateSummary,
    val windows: List<UiWindow>,
    /** Token-efficient outline, present when `format=compact`. */
    val compact: String? = null,
    /** How many secrets were masked when `redact` was set. */
    val redacted: Int = 0,
    /** True when Chrome turned off a page's nodes, so the snapshot shows none of what is in it. */
    val pageNodesOff: Boolean = false,
)

/** The `error` of a call that needs a page's nodes while Chrome has them turned off. */
public const val PAGE_NODES_OFF: String = "page_nodes_off"

@Serializable
public enum class SnapshotFormat {
    @SerialName("compact") COMPACT,
    @SerialName("json") JSON,
    @SerialName("both") BOTH,
}

@Serializable
public data class SnapshotOptions(
    val format: SnapshotFormat = SnapshotFormat.COMPACT,
    val windowId: Int? = null,
    val maxDepth: Int? = null,
    /** Most node lines in the compact outline. The rest are counted and reported as cut. */
    val maxNodes: Int? = null,
    /** Most characters of compact outline below its header, cut at a line. */
    val maxChars: Int? = null,
    /** Leave positions, resource ids, states, and actions out of the compact outline. */
    val textOnly: Boolean = false,
    /**
     * Mask the text of password and one-time-code fields, and card numbers, and count them in
     * [UiSnapshot.redacted]. On by default, so a caller sees secrets only when it asks.
     */
    val redact: Boolean = true,
    val includeInvisible: Boolean = false,
    /** Include every window, not just the ones that are interesting to act on. */
    val allWindows: Boolean = false,
    val autoWake: Boolean = true,
)

@Doc("All non-null fields must match. String matches are case-insensitive unless noted.")
@Serializable
public data class NodeSelector(
    @Doc("Snapshot handle such as e12. Valid until the node disappears.") val ref: String? = null,
    @Doc("Exact text, case-insensitive.") val text: String? = null,
    @Doc("Substring of the text, case-insensitive.") val textContains: String? = null,
    @Doc("Case-sensitive regular expression over text, then content description.")
    val textRegex: String? = null,
    @Doc("Exact content description, case-insensitive.") val desc: String? = null,
    @Doc("Substring of the content description, case-insensitive.")
    val descContains: String? = null,
    @Doc("Resource id, without the package prefix when it matches the window.")
    val id: String? = null,
    @Doc("Short role such as button, text, or edit.") val role: String? = null,
    @Doc("Window package name.") @SerialName("package") val packageName: String? = null,
    @Doc("Require a clickable or non-clickable node.") val clickable: Boolean? = null,
    @Doc("Require an editable or non-editable node.") val editable: Boolean? = null,
    @Doc("Require a node that has or lacks input focus.") val focused: Boolean? = null,
    @Doc("Zero-based index when several nodes match.") val index: Int? = null,
)

@Serializable
public data class FindRequest(
    val selector: NodeSelector,
    @Doc("Most matches to return. Defaults to 20.") val limit: Int = 20,
    @Doc("Also match nodes that are off screen or hidden.") val includeInvisible: Boolean = false,
    /** Mask secrets in the matches as [SnapshotOptions.redact] does, and count them. */
    @Doc(
        "Mask the text of password and one-time-code fields, and card numbers, as a snapshot does. redacted counts them. Defaults to true."
    )
    val redact: Boolean = true,
)

@Serializable
public data class FindResult(
    val seq: Long,
    val matches: List<UiNode>,
    /** How many secrets were masked when `redact` was set. */
    val redacted: Int = 0,
)

@Serializable
public enum class ActionMode {
    /** Perform the action with real touch input at the node's bounds where possible. */
    @SerialName("real") REAL,
    /** Use AccessibilityNodeInfo.performAction directly (no touch events are generated). */
    @SerialName("semantic") SEMANTIC,
}

@Serializable
public data class NodeActionRequest(
    @Doc(
        "Standard action (click, longClick, focus, clearFocus, select, setText, scrollForward, scrollBackward, expand, collapse, dismiss, showOnScreen, imeEnter, and the other accessibility actions) or a custom action label from the node's actions in the snapshot."
    )
    val action: String,
    @Doc("real uses a touch where it can. semantic calls performAction and generates no touch.")
    val mode: ActionMode = ActionMode.REAL,
    @Doc("Replacement text for setText.") val text: String? = null,
    @Doc("Act even when the node is covered.") val force: Boolean = false,
    @Doc("Turn the screen on first. Leaves the keyguard up. Defaults to true.")
    val autoWake: Boolean = true,
)
