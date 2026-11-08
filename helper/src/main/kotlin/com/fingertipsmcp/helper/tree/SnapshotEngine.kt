package com.fingertipsmcp.helper.tree

import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.fingertipsmcp.model.ApiException
import com.fingertipsmcp.model.DeviceStateSummary
import com.fingertipsmcp.model.FindRequest
import com.fingertipsmcp.model.FindResult
import com.fingertipsmcp.model.NodeSelector
import com.fingertipsmcp.model.SnapshotFormat
import com.fingertipsmcp.model.SnapshotOptions
import com.fingertipsmcp.model.UiNode
import com.fingertipsmcp.model.UiSnapshot
import com.fingertipsmcp.model.UiWindow
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Builds snapshots and runs node queries over the live accessibility tree. */
internal class SnapshotEngine(
    private val windows: () -> List<AccessibilityWindowInfo>,
    private val io: CoroutineDispatcher,
    private val seq: StateFlow<Long>,
    private val imePackage: () -> String?,
    private val nodes: NodeRegistry,
    private val nodeCache: NodeCacheAge,
    private val content: ContentFrame,
    private val screenRect: () -> Rect,
) {
    private data class Built(val seq: Long, val windows: List<UiWindow>, val cut: TreeCut)

    private val mutex = Mutex()

    /** Makes the next read fetch current nodes; called after every action. */
    fun invalidate() {
        nodeCache.expire()
    }

    suspend fun snapshot(options: SnapshotOptions, summary: DeviceStateSummary): UiSnapshot {
        var nodesOff = false
        val built =
            withContext(io) {
                mutex.withLock {
                    val seq = this@SnapshotEngine.seq.value
                    nodeCache
                        .read { build(windows(), options, seq) }
                        .also {
                            nodesOff = content.nodesOff()
                        }
                }
            }
        val redactor = Redactor()
        val shown = if (options.redact) redactor.windows(built.windows) else built.windows
        val compact =
            if (options.format == SnapshotFormat.JSON) null
            else {
                val header = CompactFormatter.header(built.seq, summary, nodesOff)
                CompactFormatter.format(
                    if (redactor.count > 0) "$header redacted=${redactor.count}" else header,
                    shown,
                    OutlineLimits(options.maxNodes, options.maxChars, options.textOnly),
                    built.cut,
                )
            }
        val windows =
            if (options.format == SnapshotFormat.COMPACT) shown.map { it.copy(root = null) }
            else shown
        return UiSnapshot(
            built.seq,
            System.currentTimeMillis(),
            summary,
            windows,
            compact,
            redacted = redactor.count,
            pageNodesOff = nodesOff,
        )
    }

    suspend fun find(request: FindRequest): FindResult {
        val selector = request.selector
        val matcher = SelectorMatcher(selector)
        return withContext(io) {
            val seq = this@SnapshotEngine.seq.value
            val ref = selector.ref
            val picked =
                if (ref != null) {
                    listOfNotNull(findByRef(windows(), ref, matcher))
                } else {
                    val wanted =
                        selector.index?.let { it + 1 } ?: request.limit.coerceIn(1, MAX_FIND)
                    val matches = mutex.withLock {
                        nodeCache.read {
                            search(windows(), matcher, wanted, request.includeInvisible)
                        }
                    }
                    selector.index?.let { i -> listOfNotNull(matches.getOrNull(i)) } ?: matches
                }
            // No match on a page with no nodes says nothing about the page.
            if (picked.isEmpty() && content.nodesOff()) throw ContentFrame.pageNodesOff()
            if (!request.redact) return@withContext FindResult(seq, picked)
            val redactor = Redactor()
            val shown = redactor.nodes(picked)
            FindResult(seq, shown, redacted = redactor.count)
        }
    }

    /**
     * The node [selector] points at, as a copy owned by the caller. Throws `404 not_found` when
     * nothing matches. Must be called off the main thread.
     */
    suspend fun resolve(selector: NodeSelector): AccessibilityNodeInfo {
        val ref =
            selector.ref?.takeIf { SelectorMatcher(selector).isEmpty }
                ?: find(FindRequest(selector, limit = 1)).matches.firstOrNull()?.ref
                ?: throw ApiException.notFound("No node matches the selector")
        return nodes.acquire(ref)
    }

    private fun build(
        windows: List<AccessibilityWindowInfo>,
        options: SnapshotOptions,
        seq: Long,
    ): Built {
        val started = SystemClock.uptimeMillis()
        nodes.beginSnapshot()
        try {
            val layout = ScreenLayout.capture(windows, screenRect())
            val traversal = Traversal(layout, options)
            val out =
                windows
                    .asSequence()
                    .sortedByDescending { it.layer }
                    .filter { include(it, options, layout) }
                    .map { traversal.window(it, includeTree(it, options)) }
                    .toList()
            nodes.endSnapshot()
            Log.d(
                TAG,
                "snapshot: ${traversal.count} nodes, ${out.size} windows in " +
                    "${SystemClock.uptimeMillis() - started} ms",
            )
            val cut =
                TreeCut(
                    maxDepth = options.maxDepth.takeIf { traversal.depthCut },
                    nodeCap = MAX_NODES.takeIf { traversal.capped },
                )
            return Built(seq, out, cut)
        } finally {
            NodeCompat.recycleAll(windows)
        }
    }

    private fun include(
        w: AccessibilityWindowInfo,
        options: SnapshotOptions,
        layout: ScreenLayout,
    ): Boolean {
        options.windowId?.let {
            return w.id == it
        }
        if (options.allWindows) return true
        return when (w.type) {
            AccessibilityWindowInfo.TYPE_APPLICATION,
            AccessibilityWindowInfo.TYPE_INPUT_METHOD -> true
            AccessibilityWindowInfo.TYPE_SYSTEM -> {
                val bounds = layout.window(w.id)?.bounds ?: return false
                w.isFocused ||
                    w.isActive ||
                    area(bounds) >= area(layout.screen) * INTERESTING_SYSTEM_FRACTION
            }
            else -> false
        }
    }

    private fun includeTree(w: AccessibilityWindowInfo, options: SnapshotOptions) =
        w.type != AccessibilityWindowInfo.TYPE_INPUT_METHOD ||
            options.allWindows ||
            options.windowId != null

    private fun findByRef(
        windows: List<AccessibilityWindowInfo>,
        ref: String,
        matcher: SelectorMatcher,
    ): UiNode? {
        try {
            val layout = ScreenLayout.capture(windows, screenRect())
            return nodes.withNode(ref) { node ->
                NodeConverter(layout).convert(node, ref, emptyList()).takeIf {
                    matcher.matches(it, node.packageName?.toString())
                }
            }
        } finally {
            NodeCompat.recycleAll(windows)
        }
    }

    private fun search(
        windows: List<AccessibilityWindowInfo>,
        matcher: SelectorMatcher,
        wanted: Int,
        includeInvisible: Boolean,
    ): List<UiNode> {
        try {
            val layout = ScreenLayout.capture(windows, screenRect())
            val search = Search(NodeConverter(layout), matcher, wanted, includeInvisible)
            windows
                .filter { it.type != AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY }
                .sortedWith(
                    compareBy<AccessibilityWindowInfo> { searchOrder(it.type) }
                        .thenByDescending { it.layer }
                )
                .forEach { window -> if (!search.done) window.root?.let(search::tree) }
            return search.out
        } finally {
            NodeCompat.recycleAll(windows)
        }
    }

    private inner class Search(
        private val converter: NodeConverter,
        private val matcher: SelectorMatcher,
        private val wanted: Int,
        private val includeInvisible: Boolean,
    ) {
        val out = ArrayList<UiNode>()
        val done: Boolean
            get() = out.size >= wanted

        fun tree(root: AccessibilityNodeInfo) {
            val stack = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
            var visited = 0
            while (stack.isNotEmpty() && !done && visited < MAX_NODES) {
                val node = stack.removeLast()
                visited++
                if (includeInvisible || node.isVisibleToUser) visit(node, stack)
                else NodeCompat.recycle(node)
            }
            stack.forEach(NodeCompat::recycle)
        }

        private fun visit(node: AccessibilityNodeInfo, stack: ArrayDeque<AccessibilityNodeInfo>) {
            for (i in node.childCount - 1 downTo 0) node.getChild(i)?.let(stack::add)
            val converted = converter.convert(node, "", emptyList())
            if (matcher.matches(converted, node.packageName?.toString())) {
                out += converted.copy(ref = nodes.register(node))
            } else {
                NodeCompat.recycle(node)
            }
        }
    }

    private fun searchOrder(type: Int) =
        when (type) {
            AccessibilityWindowInfo.TYPE_APPLICATION -> 0
            AccessibilityWindowInfo.TYPE_SYSTEM -> 1
            AccessibilityWindowInfo.TYPE_INPUT_METHOD -> 2
            else -> 3
        }

    private inner class Traversal(
        private val layout: ScreenLayout,
        private val options: SnapshotOptions,
    ) {
        private val converter = NodeConverter(layout)
        var count = 0
            private set

        var depthCut = false
            private set

        var capped = false
            private set

        fun window(w: AccessibilityWindowInfo, includeTree: Boolean): UiWindow {
            val geometry = layout.window(w.id)
            val root = if (includeTree) w.root else null
            val pkg =
                root?.packageName?.toString()
                    ?: imePackage().takeIf {
                        w.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD
                    }
            return UiWindow(
                id = w.id,
                type = windowTypeName(w.type),
                title = w.title?.toString(),
                packageName = pkg,
                layer = w.layer,
                focused = w.isFocused,
                active = w.isActive,
                bounds = (geometry?.bounds ?: Rect()).toModel(),
                root = root?.let { node(it, 0) },
            )
        }

        private fun node(n: AccessibilityNodeInfo, depth: Int): UiNode? {
            if (!options.includeInvisible && !n.isVisibleToUser) {
                NodeCompat.recycle(n)
                return null
            }
            if (count >= MAX_NODES) {
                capped = true
                NodeCompat.recycle(n)
                return null
            }
            count++
            val ref = nodes.register(n)
            val maxDepth = options.maxDepth
            val children =
                if (maxDepth != null && depth >= maxDepth) {
                    if (n.childCount > 0) depthCut = true
                    emptyList()
                } else {
                    (0 until n.childCount).mapNotNull { i ->
                        n.getChild(i)?.let { node(it, depth + 1) }
                    }
                }
            return converter.convert(n, ref, children)
        }
    }

    private companion object {
        const val TAG = "FingertipsUi"
        const val MAX_NODES = 5_000
        const val MAX_FIND = 200
        const val INTERESTING_SYSTEM_FRACTION = 0.15
    }
}

/** Converts platform nodes to wire nodes, including occlusion by windows stacked above. */
internal class NodeConverter(private val layout: ScreenLayout) {
    private val scratch = Rect()

    fun convert(n: AccessibilityNodeInfo, ref: String, children: List<UiNode>): UiNode {
        n.getBoundsInScreen(scratch)
        val clipped = layout.clip(n.windowId, scratch)
        val bounds = if (clipped.isEmpty) Rect(scratch) else clipped
        val obscured =
            !clipped.isEmpty &&
                layout.coverAt(n.windowId, clipped.centerX(), clipped.centerY()) != null
        val className = n.className?.toString()
        val role =
            Roles.roleOf(
                RoleHints(
                    className = className,
                    roleDescription = n.extras?.getCharSequence(ROLE_DESCRIPTION_KEY)?.toString(),
                    editable = n.isEditable,
                    checkable = n.isCheckable,
                    clickable = n.isClickable,
                    scrollable = n.isScrollable,
                    heading = n.isHeading,
                    collection = n.collectionInfo != null,
                    collectionItem = n.collectionItemInfo != null,
                )
            )
        val showingHint = n.isShowingHintText
        return UiNode(
            ref = ref,
            role = role,
            className = className,
            text = if (showingHint) null else n.text?.toString(),
            desc = n.contentDescription?.toString(),
            hint = n.hintText?.toString(),
            id = shortId(n.viewIdResourceName, n.packageName?.toString()),
            bounds = bounds.toModel(),
            states = states(n, obscured),
            actions = ActionCatalog.names(n),
            children = children,
        )
    }

    private fun states(n: AccessibilityNodeInfo, obscured: Boolean): List<String> {
        val states = STATE_TESTS.mapNotNull { (name, test) -> name.takeIf { test(n) } }
        return if (obscured) states + UiStates.OBSCURED else states
    }

    private fun shortId(id: String?, pkg: String?): String? {
        if (id == null || pkg == null) return id
        val prefix = "$pkg:id/"
        return if (id.startsWith(prefix)) id.substring(prefix.length) else id
    }

    private companion object {
        /** Where androidx and Compose store a node's role description. */
        const val ROLE_DESCRIPTION_KEY = "AccessibilityNodeInfo.roleDescription"

        val STATE_TESTS: List<Pair<String, (AccessibilityNodeInfo) -> Boolean>> =
            listOf(
                UiStates.CLICKABLE to AccessibilityNodeInfo::isClickable,
                UiStates.LONG_CLICKABLE to AccessibilityNodeInfo::isLongClickable,
                UiStates.CHECKABLE to AccessibilityNodeInfo::isCheckable,
                UiStates.CHECKED to NodeCompat::isChecked,
                UiStates.SELECTED to AccessibilityNodeInfo::isSelected,
                UiStates.FOCUSED to AccessibilityNodeInfo::isFocused,
                UiStates.FOCUSABLE to AccessibilityNodeInfo::isFocusable,
                UiStates.DISABLED to { n -> !n.isEnabled },
                UiStates.EDITABLE to AccessibilityNodeInfo::isEditable,
                UiStates.PASSWORD to AccessibilityNodeInfo::isPassword,
                UiStates.SCROLLABLE to AccessibilityNodeInfo::isScrollable,
            )
    }
}

internal fun windowTypeName(type: Int): String =
    when (type) {
        AccessibilityWindowInfo.TYPE_APPLICATION -> "application"
        AccessibilityWindowInfo.TYPE_INPUT_METHOD -> "input_method"
        AccessibilityWindowInfo.TYPE_SYSTEM -> "system"
        AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY -> "accessibility_overlay"
        AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER -> "split_divider"
        TYPE_MAGNIFICATION_OVERLAY -> "magnification"
        TYPE_WINDOW_CONTROL -> "window_control"
        else -> "unknown"
    }

// Values of AccessibilityWindowInfo constants added after minSdk; the ints are stable.
private const val TYPE_MAGNIFICATION_OVERLAY = 6
private const val TYPE_WINDOW_CONTROL = 7
