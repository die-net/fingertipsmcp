package net.die.phoneapi.helper.tree

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
import android.view.accessibility.AccessibilityWindowInfo
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import net.die.phoneapi.model.ApiException
import net.die.phoneapi.model.Rect as ModelRect

/** Works out where a real finger can touch a node. */
class NodeTargeting(private val screen: () -> Rect) {

    /**
     * The largest part of [node] that is on screen and not covered by another window. Scrolls the
     * node into view once if needed, and waits for the scroll to stop before reading where it is.
     * [windows] is read after that, so the layout matches the bounds. Throws `409 offscreen` or
     * `409 obscured` (unless [force]). Must be called off the main thread.
     */
    suspend fun touchTarget(
        node: AccessibilityNodeInfo,
        windows: () -> List<AccessibilityWindowInfo>,
        force: Boolean,
    ): ModelRect {
        if (!node.isVisibleToUser) {
            if (!node.performAction(AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)) throw offscreen()
            awaitSettled(SCROLL_POLL_MS, SCROLL_TIMEOUT_MS) {
                if (!node.refresh()) throw offscreen()
                if (node.isVisibleToUser) Rect().also(node::getBoundsInScreen) else null
            } ?: throw offscreen()
        }
        val open = windows()
        try {
            val layout = ScreenLayout.capture(open, screen())
            val raw = Rect().also(node::getBoundsInScreen)
            val clipped = layout.clip(node.windowId, raw)
            if (clipped.isEmpty) throw offscreen()
            if (force) return clipped.toModel()
            val visible = layout.visibleRegion(node.windowId, clipped).largestRect()
            if (
                visible == null ||
                    visible.width() < MIN_TARGET_PX ||
                    visible.height() < MIN_TARGET_PX
            ) {
                val cover = layout.mainCover(node.windowId, clipped)
                val by =
                    cover?.let { "the ${windowTypeName(it.type)} window (w${it.id})" }
                        ?: "another window"
                throw ApiException(
                    409,
                    "obscured",
                    "The node is covered by $by. Hide the keyboard (POST /v1/ime/hide), scroll " +
                        "it into view, or pass force=true.",
                )
            }
            return visible.toModel()
        } finally {
            NodeCompat.recycleAll(open)
        }
    }

    private fun offscreen() =
        ApiException(409, "offscreen", "The node is off screen and could not be scrolled into view")

    private companion object {
        /** How often a scroll into view is checked for having stopped. */
        const val SCROLL_POLL_MS = 50L

        /** How long a scroll into view may take, including a long smooth scroll. */
        const val SCROLL_TIMEOUT_MS = 2_000L

        const val MIN_TARGET_PX = 6
    }
}

/**
 * Polls [probe] every [intervalMs] until it returns the same value twice in a row, and returns that
 * value. A null from [probe] means "not there yet". Returns null if nothing settles within
 * [timeoutMs].
 */
internal suspend fun <T : Any> awaitSettled(
    intervalMs: Long,
    timeoutMs: Long,
    probe: () -> T?,
): T? =
    withTimeoutOrNull(timeoutMs) {
        var last: T?
        var now: T? = null
        do {
            last = now
            delay(intervalMs)
            now = probe()
        } while (now == null || now != last)
        now
    }
