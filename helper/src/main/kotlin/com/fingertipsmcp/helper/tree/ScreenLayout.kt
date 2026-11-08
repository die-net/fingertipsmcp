package com.fingertipsmcp.helper.tree

import android.graphics.Rect
import android.graphics.Region
import android.graphics.RegionIterator
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

internal data class WindowGeometry(
    val id: Int,
    val type: Int,
    val layer: Int,
    val bounds: Rect,
    /** The part of the window that takes touches (IME windows span most of the screen). */
    val touchRegion: Region,
)

/** Window stacking at one moment, used to tell which parts of a node a finger can reach. */
internal class ScreenLayout(val screen: Rect, val windows: List<WindowGeometry>) {
    private val byId = windows.associateBy { it.id }

    fun window(id: Int): WindowGeometry? = byId[id]

    /** [rect] clipped to its window and the screen; empty when nothing is left. */
    fun clip(windowId: Int, rect: Rect): Rect {
        val out = Rect(rect)
        byId[windowId]?.let { if (!out.intersect(it.bounds)) out.setEmpty() }
        if (!out.intersect(screen)) out.setEmpty()
        return out
    }

    /** The topmost window stacked above [windowId] that takes touches at ([x], [y]). */
    fun coverAt(windowId: Int, x: Int, y: Int): WindowGeometry? =
        above(windowId).firstOrNull { it.touchRegion.contains(x, y) }

    /** The window above [windowId] that hides the largest part of [rect]. */
    fun mainCover(windowId: Int, rect: Rect): WindowGeometry? =
        above(windowId).maxByOrNull { w ->
            val overlap = Region(rect)
            if (overlap.op(w.touchRegion, Region.Op.INTERSECT)) area(overlap.bounds) else 0
        }

    /** The part of [rect] (already clipped) that no window above [windowId] covers. */
    fun visibleRegion(windowId: Int, rect: Rect): Region {
        val region = Region(rect)
        for (w in above(windowId)) {
            if (Rect.intersects(w.bounds, rect)) region.op(w.touchRegion, Region.Op.DIFFERENCE)
        }
        return region
    }

    private fun above(windowId: Int): List<WindowGeometry> {
        val own = byId[windowId] ?: return emptyList()
        return windows.filter { it.layer > own.layer && it.type != TYPE_ACCESSIBILITY_OVERLAY }
    }

    companion object {
        private const val TYPE_ACCESSIBILITY_OVERLAY =
            AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY

        /** Windows sorted top-down. Does not take ownership of [windows]. */
        fun capture(windows: List<AccessibilityWindowInfo>, screen: Rect): ScreenLayout =
            ScreenLayout(
                screen,
                windows
                    .map { w ->
                        val bounds = Rect().also(w::getBoundsInScreen)
                        WindowGeometry(w.id, w.type, w.layer, bounds, touchRegion(w, bounds))
                    }
                    .sortedByDescending { it.layer },
            )
    }
}

/** The window's touchable region: exact on 33+, estimated from the keys for IMEs below that. */
internal fun touchRegion(window: AccessibilityWindowInfo, bounds: Rect): Region {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val region = Region()
        window.getRegionInScreen(region)
        if (!region.isEmpty) return region
    } else if (window.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
        clickableBounds(window)?.let {
            return Region(it)
        }
    }
    return Region(bounds)
}

/** Union of the visible clickable nodes' bounds in [window], or null if there are none. */
private fun clickableBounds(window: AccessibilityWindowInfo): Rect? {
    val root = window.root ?: return null
    val union = Rect()
    val stack = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
    var visited = 0
    val scratch = Rect()
    while (stack.isNotEmpty() && visited < MAX_IME_NODES) {
        val node = stack.removeLast()
        visited++
        if (node.isVisibleToUser) {
            if (node.isClickable) {
                node.getBoundsInScreen(scratch)
                union.union(scratch)
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let(stack::add)
        }
        NodeCompat.recycle(node)
    }
    stack.forEach(NodeCompat::recycle)
    return union.takeUnless { it.isEmpty }
}

internal fun Region.largestRect(): Rect? {
    val iterator = RegionIterator(this)
    val r = Rect()
    var best: Rect? = null
    while (iterator.next(r)) {
        if (best == null || area(r) > area(best)) best = Rect(r)
    }
    return best
}

internal fun area(r: Rect): Int = if (r.isEmpty) 0 else r.width() * r.height()

private const val MAX_IME_NODES = 400
