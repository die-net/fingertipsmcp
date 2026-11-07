package net.die.phoneapi.tree

import net.die.phoneapi.core.settled
import net.die.phoneapi.model.ApiException
import net.die.phoneapi.model.PAGE_NODES_OFF
import net.die.phoneapi.model.Rect

/**
 * Brings back a page's accessibility nodes. Chrome can turn them off while the page stays on
 * screen, and only something like Chrome being started again undoes that, as docs/device-notes.md
 * records. Chrome's timer is out of PhoneAPI's reach, since UiAutomation is no service it lists.
 * [start] starts the app, as tapping its icon would, which keeps the page selected. [bounds] is the
 * helper's read of where the page is, which fails until the nodes are back.
 */
class PageNodes(
    private val start: suspend (String) -> Unit,
    private val bounds: suspend (String) -> Rect,
) {
    /**
     * Runs [block], and once more after a start of [pkg] when it failed for want of the nodes. A
     * start changes the screen, so only calls that act on it come here, and [block] has to be safe
     * to run again from that failure. When the nodes stay off, the second run says so. A start
     * hides the keyboard, which moves the page, so [moved] waits that out before the second run.
     */
    suspend fun <T> restoring(
        pkg: String?,
        moved: suspend () -> Unit = {},
        block: suspend () -> T,
    ): T =
        try {
            block()
        } catch (e: ApiException) {
            if (e.error != PAGE_NODES_OFF || pkg == null) throw e
            start(pkg)
            settled(RETURN_MS) { hasContent { bounds(pkg) } }
            moved()
            block()
        }

    private companion object {
        const val RETURN_MS = 5_000L
    }
}

/** Whether [bounds] finds the app's content, which is missing until its window is up. */
internal suspend fun hasContent(bounds: suspend () -> Rect): Boolean =
    try {
        bounds()
        true
    } catch (e: ApiException) {
        if (e.error != "content_unavailable" && e.error != PAGE_NODES_OFF) throw e
        false
    }
