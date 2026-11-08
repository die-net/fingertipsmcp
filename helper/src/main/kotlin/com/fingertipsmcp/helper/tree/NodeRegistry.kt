package com.fingertipsmcp.helper.tree

import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import com.fingertipsmcp.model.ApiException

/**
 * Hands out short refs (`e1`, `e2`, ...) that stay the same across snapshots for as long as a node
 * persists, and maps them back to the latest [AccessibilityNodeInfo].
 *
 * Nodes are keyed by the app-assigned unique id where there is one (API 33+), otherwise by the
 * node's own identity: its source view id and window id, which is what
 * [AccessibilityNodeInfo.equals] compares and which stays stable while the view exists. Class name
 * and resource id are part of the key so a recycled view id showing a different widget gets a new
 * ref.
 */
class NodeRegistry(
    private val retainSnapshots: Int = 3,
    private val maxEntries: Int = 20_000,
) {
    private class Entry(val ref: String, val key: Any, node: AccessibilityNodeInfo, seen: Long) {
        var node: AccessibilityNodeInfo = node
            private set

        var seen: Long = seen
            private set

        /** Swaps in the latest copy of the node, recycling the previous one. */
        fun update(latest: AccessibilityNodeInfo, generation: Long) {
            if (node !== latest) NodeCompat.recycle(node)
            node = latest
            seen = generation
        }
    }

    private data class UniqueKey(val windowId: Int, val uniqueId: String)

    private data class SourceKey(
        val node: AccessibilityNodeInfo,
        val className: String?,
        val viewId: String?,
    )

    private val byKey = HashMap<Any, Entry>()
    private val byRef = HashMap<String, Entry>()
    private var generation = 0L
    private var nextRef = 1L

    /** Starts a full traversal; refs not seen for [retainSnapshots] traversals are dropped. */
    @Synchronized
    fun beginSnapshot() {
        generation++
    }

    @Synchronized
    fun endSnapshot() {
        val cutoff = generation - retainSnapshots
        val (stale, live) = byRef.values.partition { it.seen < cutoff }
        val overflow = live.size - maxEntries
        val evicted = if (overflow > 0) live.sortedBy { it.seen }.take(overflow) else emptyList()
        for (entry in stale + evicted) remove(entry)
    }

    /**
     * Registers [node] (taking ownership of it) and returns its ref. The caller may keep reading
     * [node] until the next traversal.
     */
    @Synchronized
    fun register(node: AccessibilityNodeInfo): String {
        val className = node.className?.toString()
        val viewId = node.viewIdResourceName
        val probe: Any = uniqueKey(node) ?: SourceKey(node, className, viewId)
        val existing = byKey[probe]
        if (existing != null) {
            existing.update(node, generation)
            return existing.ref
        }
        val key =
            if (probe is SourceKey) SourceKey(NodeCompat.copy(node), className, viewId) else probe
        val entry = Entry("e${nextRef++}", key, node, generation)
        byKey[key] = entry
        byRef[entry.ref] = entry
        return entry.ref
    }

    /**
     * Returns a refreshed copy of the node behind [ref], owned by the caller (recycle it with
     * [NodeCompat.recycle]). Throws `404 stale_ref` when the node no longer exists.
     */
    fun acquire(ref: String): AccessibilityNodeInfo {
        val node =
            synchronized(this) { byRef[ref]?.node?.let(NodeCompat::copy) }
                ?: throw staleRef(ref, "is unknown or expired; take a new snapshot")
        if (!node.refresh()) {
            NodeCompat.recycle(node)
            throw staleRef(ref, "is no longer on screen")
        }
        return node
    }

    private fun remove(entry: Entry) {
        byRef.remove(entry.ref)
        byKey.remove(entry.key)
        NodeCompat.recycle(entry.node)
    }

    private fun uniqueKey(node: AccessibilityNodeInfo): UniqueKey? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            node.uniqueId?.let { UniqueKey(node.windowId, it) }
        } else {
            null
        }

    private fun staleRef(ref: String, why: String) =
        ApiException(404, "stale_ref", "Node $ref $why")
}

/** Runs [block] with the node behind [ref] and recycles it afterwards. */
internal inline fun <T> NodeRegistry.withNode(ref: String, block: (AccessibilityNodeInfo) -> T): T {
    val node = acquire(ref)
    try {
        return block(node)
    } finally {
        NodeCompat.recycle(node)
    }
}
