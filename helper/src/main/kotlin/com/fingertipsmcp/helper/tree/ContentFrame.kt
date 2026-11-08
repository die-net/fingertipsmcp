package com.fingertipsmcp.helper.tree

import android.graphics.Rect as AndroidRect
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.fingertipsmcp.model.ApiException
import com.fingertipsmcp.model.PAGE_NODES_OFF
import com.fingertipsmcp.model.Rect

/**
 * The WebView that is actually showing the page. Chrome's window includes the toolbar, so quads
 * have to be added to this view's origin, not the window's. [roots] gives the root of each
 * application window, of the package named or of every one for null, which this recycles. Reads go
 * through [cache]'s age limit, or a tab just brought forward reads as the page that was there
 * before, which is no page at all.
 */
internal class ContentFrame(
    private val cache: NodeCacheAge,
    private val roots: (String?) -> List<AccessibilityNodeInfo>,
) {
    fun find(packageName: String): Rect = cache.read {
        locate(roots(packageName.takeIf { it.isNotEmpty() }))
    }

    /** Whether a window on screen has a page Chrome turned the nodes off for. */
    fun nodesOff(): Boolean = cache.read {
        val open = roots(null)
        try {
            open.any(::bridgeOff)
        } finally {
            open.forEach(NodeCompat::recycle)
        }
    }

    private fun locate(open: List<AccessibilityNodeInfo>): Rect =
        try {
            val best = open.fold(null as AndroidRect?) { best, root -> larger(best, search(root)) }
            val found =
                best
                    ?: throw if (open.any(::bridgeOff)) pageNodesOff()
                    else unavailable(open.isNotEmpty())
            Rect(found.left, found.top, found.right, found.bottom)
        } finally {
            open.forEach(NodeCompat::recycle)
        }

    /**
     * Whether Chrome has turned off the page's accessibility nodes, which leaves the page on screen
     * with no node to say where. Chrome 133 does this about 5 s after a read of a new tab, since no
     * accessibility service is listed, as docs/device-notes.md records. Its content view then
     * stands alone under the compositor view, childless and the same size. Chrome's description of
     * it is translated, so the shape decides, and the description only has to be there.
     */
    private fun bridgeOff(node: AccessibilityNodeInfo): Boolean {
        if (node.viewIdResourceName?.endsWith(COMPOSITOR_ID) == true) return holdsBareView(node)
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            try {
                if (bridgeOff(child)) return true
            } finally {
                NodeCompat.recycle(child)
            }
        }
        return false
    }

    private fun holdsBareView(holder: AccessibilityNodeInfo): Boolean {
        val box = AndroidRect().also(holder::getBoundsInScreen)
        for (index in 0 until holder.childCount) {
            val child = holder.getChild(index) ?: continue
            try {
                val rect = AndroidRect().also(child::getBoundsInScreen)
                val bare =
                    child.childCount == 0 &&
                        !child.contentDescription.isNullOrEmpty() &&
                        !isContent(child)
                if (bare && child.isVisibleToUser && rect == box) return true
            } finally {
                NodeCompat.recycle(child)
            }
        }
        return false
    }

    /**
     * Why there is no page to tap. When the browser has a window on screen but none shows the page,
     * something of its own covers it. A dialog such as Chrome's for a download is a window of its
     * own, and while it is up, no window shows the page's view as visible.
     */
    private fun unavailable(shown: Boolean): ApiException =
        ApiException(
            409,
            "content_unavailable",
            if (shown) {
                "The browser is in front but is not showing the page: a dialog, a menu, or the " +
                    "tab switcher covers it. ui_snapshot shows what is there; tap one of its " +
                    "buttons to dismiss it."
            } else {
                "The browser did not come to the front, so the page has no box to tap. The " +
                    "phone may be locked, or something else may be in front. ui_snapshot shows " +
                    "what is there."
            },
        )

    private fun search(node: AccessibilityNodeInfo): AndroidRect? {
        var best = boundsOf(node)
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            try {
                best = larger(best, search(child))
            } finally {
                NodeCompat.recycle(child)
            }
        }
        return best
    }

    private fun boundsOf(node: AccessibilityNodeInfo): AndroidRect? {
        if (!isContent(node) || !node.isVisibleToUser) return null
        val rect = AndroidRect().also(node::getBoundsInScreen)
        if (rect.width() < MIN_EDGE || rect.height() < MIN_EDGE) return null
        return rect
    }

    private fun isContent(node: AccessibilityNodeInfo): Boolean {
        val name = node.className?.toString() ?: return false
        return name.endsWith("WebView") ||
            name.endsWith("CompositorViewHolder") ||
            name.endsWith("ContentView")
    }

    private fun larger(current: AndroidRect?, next: AndroidRect?): AndroidRect? {
        if (next == null) return current
        if (current == null || area(next) > area(current)) return next
        return current
    }

    private fun area(rect: AndroidRect): Int = rect.width() * rect.height()

    companion object {
        private const val MIN_EDGE = 20
        private const val COMPOSITOR_ID = "/compositor_view_holder"

        /** The page is on screen, but its nodes are gone. Actions recover from this one. */
        fun pageNodesOff(): ApiException =
            ApiException(
                409,
                PAGE_NODES_OFF,
                "Chrome turned off its accessibility nodes for this page, so FingertipsMCP cannot see " +
                    "what is in it or place a touch on it. The page itself is fine: browser_read " +
                    "and input cdp still work. Starting Chrome with app_launch, or switching " +
                    "tabs and back, turns them on again.",
            )

        /** The roots of [windows]' application windows, of [packageName] when it is not null. */
        fun applicationRoots(
            windows: List<AccessibilityWindowInfo>,
            packageName: String?,
        ): List<AccessibilityNodeInfo> = windows.mapNotNull { ownedRoot(it, packageName) }

        private fun ownedRoot(
            window: AccessibilityWindowInfo,
            packageName: String?,
        ): AccessibilityNodeInfo? {
            if (window.type != AccessibilityWindowInfo.TYPE_APPLICATION) return null
            val root = window.root ?: return null
            if (packageName != null && root.packageName?.toString() != packageName) {
                NodeCompat.recycle(root)
                return null
            }
            return root
        }
    }
}
