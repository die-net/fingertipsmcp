package com.fingertipsmcp.helper.tree

import com.fingertipsmcp.model.DeviceStateSummary
import com.fingertipsmcp.model.Rect
import com.fingertipsmcp.model.UiNode
import com.fingertipsmcp.model.UiWindow

/** Canonical short names for accessibility actions, shared by snapshots and node actions. */
object ActionNames {
    const val CLICK = "click"
    const val LONG_CLICK = "longClick"
    const val FOCUS = "focus"
    const val CLEAR_FOCUS = "clearFocus"
    const val SELECT = "select"
    const val SET_TEXT = "setText"
    const val SCROLL_FORWARD = "scrollForward"
    const val SCROLL_BACKWARD = "scrollBackward"
    const val EXPAND = "expand"
    const val COLLAPSE = "collapse"
    const val DISMISS = "dismiss"
    const val SHOW_ON_SCREEN = "showOnScreen"
    const val IME_ENTER = "imeEnter"
    const val SET_PROGRESS = "setProgress"

    /** Actions implied by a node's role or states, or too generic to be worth tokens. */
    val IMPLIED: Set<String> =
        setOf(
            CLICK,
            LONG_CLICK,
            FOCUS,
            CLEAR_FOCUS,
            SELECT,
            "clearSelection",
            "accessibilityFocus",
            "clearAccessibilityFocus",
            SET_TEXT,
            "setSelection",
            SCROLL_FORWARD,
            SCROLL_BACKWARD,
            "scrollUp",
            "scrollDown",
            "scrollLeft",
            "scrollRight",
            "scrollToPosition",
            "pageUp",
            "pageDown",
            "pageLeft",
            "pageRight",
            SHOW_ON_SCREEN,
            "copy",
            "cut",
            "paste",
            "nextAtMovementGranularity",
            "previousAtMovementGranularity",
            "nextHtmlElement",
            "previousHtmlElement",
            "contextClick",
            IME_ENTER,
            "pressAndHold",
            "showTooltip",
            "hideTooltip",
            "moveWindow",
        )
}

object UiStates {
    const val CLICKABLE = "clickable"
    const val LONG_CLICKABLE = "longClickable"
    const val CHECKABLE = "checkable"
    const val CHECKED = "checked"
    const val SELECTED = "selected"
    const val FOCUSED = "focused"
    const val FOCUSABLE = "focusable"
    const val DISABLED = "disabled"
    const val EDITABLE = "editable"
    const val PASSWORD = "password"
    const val SCROLLABLE = "scrollable"
    const val OBSCURED = "obscured"

    val INTERACTIVE: Set<String> = setOf(CLICKABLE, LONG_CLICKABLE, CHECKABLE, EDITABLE, SCROLLABLE)
}

/**
 * Caps on how much of an outline is returned, where null means no cap. [textOnly] leaves out
 * positions, resource ids, states, and actions.
 */
data class OutlineLimits(
    val maxNodes: Int? = null,
    val maxChars: Int? = null,
    val textOnly: Boolean = false,
)

/**
 * Why reading the tree stopped short: nodes below [maxDepth] were skipped, or reading stopped at
 * [nodeCap] nodes. Null when that did not happen.
 */
data class TreeCut(val maxDepth: Int? = null, val nodeCap: Int? = null)

private data class OutlineLine(val text: String, val node: Boolean)

/**
 * Token-efficient outline of a snapshot: one line per meaningful node, indented by depth, with
 * layout-only containers flattened away and the labels of clickable rows pulled up into the row.
 */
object CompactFormatter {
    private const val MAX_TEXT = 100
    private const val MAX_LABEL_PARTS = 3
    private const val LABEL_SEPARATOR = " · "

    private val LANDMARK_ROLES = setOf("web", "list", "grid", "pager", "tabs", "tab", "heading")

    private val WINDOW_TYPES =
        mapOf(
            "application" to "app",
            "input_method" to "ime",
            "system" to "system",
            "accessibility_overlay" to "overlay",
            "split_divider" to "divider",
            "magnification" to "magnifier",
            "window_control" to "control",
        )

    fun header(seq: Long, state: DeviceStateSummary, nodesOff: Boolean = false): String =
        buildString {
            append("# seq=").append(seq)
            append(" screen=").append(state.screen.name.lowercase())
            append(" keyguard=")
            append(
                when {
                    !state.keyguard.locked -> "unlocked"
                    state.keyguard.secure -> "locked(secure)"
                    else -> "locked"
                }
            )
            val ime = state.ime
            append(" ime=")
            if (ime.visible) {
                append("shown")
                ime.bounds?.let { append(rectText(it)) }
                ime.packageName?.let { append(' ').append(it) }
            } else {
                append("hidden")
            }
            state.foregroundPackage?.let { append(" fg=").append(it) }
            if (nodesOff) append(" page_nodes_off")
        }

    /**
     * The outline under [header], cut to [limits]. When anything is left out, either here or by
     * [cut] while reading the tree, the header ends with ` truncated` and `#` lines after it say
     * what is missing.
     */
    fun format(
        header: String,
        windows: List<UiWindow>,
        limits: OutlineLimits = OutlineLimits(),
        cut: TreeCut = TreeCut(),
    ): String {
        val lines = ArrayList<OutlineLine>()
        for (window in windows) {
            lines += OutlineLine(windowLine(window), node = false)
            val root = window.root
            if (root == null) {
                if (window.type == "input_method") {
                    lines += OutlineLine("  (keys omitted; pass all=true)", node = false)
                }
            } else {
                emit(root, depth = 1, absorbed = HashSet(), out = lines, limits.textOnly)
            }
        }
        val kept = keep(lines, limits)
        val notes = buildList {
            if (kept.size < lines.size) {
                val shown = kept.count { it.node }
                val total = lines.count { it.node }
                add("# showing $shown of $total nodes; raise maxNodes or maxChars for more")
            }
            cut.maxDepth?.let { add("# nodes below maxDepth $it left out") }
            cut.nodeCap?.let {
                add("# stopped reading at $it nodes; pass window or maxDepth to narrow")
            }
        }
        return buildString {
            append(header)
            if (notes.isNotEmpty()) append(" truncated")
            append('\n')
            for (note in notes) append(note).append('\n')
            for (line in kept) append(line.text).append('\n')
        }
    }

    private fun keep(lines: List<OutlineLine>, limits: OutlineLimits): List<OutlineLine> {
        val maxNodes = limits.maxNodes ?: Int.MAX_VALUE
        val maxChars = limits.maxChars ?: Int.MAX_VALUE
        var nodes = 0
        var chars = 0
        return lines.takeWhile { line ->
            if (line.node) nodes++
            chars += line.text.length + 1
            nodes <= maxNodes && chars <= maxChars
        }
    }

    fun windowLine(window: UiWindow): String = buildString {
        append("[w").append(window.id).append(' ')
        append(WINDOW_TYPES[window.type] ?: window.type)
        window.packageName?.let { append(' ').append(it) }
        window.title?.takeIf { it.isNotBlank() }?.let { append(' ').append(quote(it)) }
        if (window.focused) append(" focused") else if (window.active) append(" active")
        append(']')
    }

    private fun emit(
        node: UiNode,
        depth: Int,
        absorbed: MutableSet<String>,
        out: MutableList<OutlineLine>,
        textOnly: Boolean,
    ) {
        val printed = node.ref !in absorbed && isMeaningful(node)
        if (printed) out += OutlineLine(line(node, depth, absorbed, textOnly), node = true)
        val childDepth = if (printed) depth + 1 else depth
        for (child in node.children) emit(child, childDepth, absorbed, out, textOnly)
    }

    private fun line(
        node: UiNode,
        depth: Int,
        absorbed: MutableSet<String>,
        textOnly: Boolean,
    ): String = buildString {
        repeat(depth) { append("  ") }
        append('[').append(node.ref).append("] ").append(node.role)
        val text = node.text?.takeIf { it.isNotBlank() }
        val desc = node.desc?.takeIf { it.isNotBlank() }
        when {
            text != null -> {
                append(' ').append(quote(text))
                if (desc != null && desc != text) append(" desc:").append(quote(desc))
            }
            desc != null -> append(" desc:").append(quote(desc))
            isInteractive(node) -> {
                val parts = ArrayList<UiNode>()
                collectLabels(node, parts)
                if (parts.isNotEmpty()) {
                    parts.forEach { absorbed += it.ref }
                    val label =
                        parts.joinToString(LABEL_SEPARATOR) {
                            (it.text ?: it.desc).orEmpty().trim()
                        }
                    append(' ').append(quote(label))
                }
            }
        }
        node.hint
            ?.takeIf { it.isNotBlank() && it != text }
            ?.let { append(" hint:").append(quote(it)) }
        if (!textOnly) appendDetails(node)
    }

    /** The resource id, center, states, and extra actions, which textOnly leaves out. */
    private fun StringBuilder.appendDetails(node: UiNode) {
        node.id?.let { append(" #").append(it) }
        append(" (")
            .append(centerX(node.bounds))
            .append(',')
            .append(centerY(node.bounds))
            .append(')')
        val flags = flags(node)
        if (flags.isNotEmpty()) append(" {").append(flags.joinToString(",")).append('}')
        val actions = node.actions.filter { it !in ActionNames.IMPLIED }
        if (actions.isNotEmpty()) append(" actions:").append(actions.joinToString(","))
    }

    private fun collectLabels(node: UiNode, out: MutableList<UiNode>) {
        for (child in node.children) {
            if (out.size >= MAX_LABEL_PARTS) return
            if (isInteractive(child)) continue
            if (!child.text.isNullOrBlank() || !child.desc.isNullOrBlank()) out += child
            collectLabels(child, out)
        }
    }

    private fun flags(node: UiNode): List<String> {
        val states = node.states
        return buildList {
            for (state in states) {
                when (state) {
                    UiStates.FOCUSABLE -> Unit
                    UiStates.CHECKABLE -> if (UiStates.CHECKED !in states) add("unchecked")
                    UiStates.EDITABLE -> if (node.role != "edit") add(state)
                    else -> add(state)
                }
            }
        }
    }

    fun isMeaningful(node: UiNode): Boolean =
        !node.text.isNullOrBlank() ||
            !node.desc.isNullOrBlank() ||
            !node.hint.isNullOrBlank() ||
            isInteractive(node) ||
            node.role in LANDMARK_ROLES ||
            node.actions.any { it !in ActionNames.IMPLIED }

    private fun isInteractive(node: UiNode) = node.states.any { it in UiStates.INTERACTIVE }

    fun quote(text: String): String {
        val single = text.trim().replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
        val clipped = if (single.length > MAX_TEXT) single.take(MAX_TEXT) + "…" else single
        return "\"" + clipped + "\""
    }

    private fun rectText(r: Rect) = "[${r.left},${r.top},${r.right},${r.bottom}]"

    private fun centerX(r: Rect) = (r.left + r.right) / 2

    private fun centerY(r: Rect) = (r.top + r.bottom) / 2
}
