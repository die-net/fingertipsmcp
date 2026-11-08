package com.fingertipsmcp.browser

import com.fingertipsmcp.model.Point
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The one mouse and keyboard of a desktop browser, which the agent's input ([DesktopPageInput]) and
 * a person's from the viewer ([ViewerPageInput]) both use. It records where the pointer is from the
 * events sent through [tracked], so a move starts where the last one ended, whoever made it. It
 * runs one action at a time ([act]), since there is one hand. Make one per browser.
 */
class DesktopPointer(
    /** The host's mouse and keyboard conventions. */
    val style: DesktopStyle = DesktopStyle.forHost()
) {
    private val turn = Mutex()

    /** Where the last pointer event put the pointer, in CSS viewport pixels; null before any. */
    @Volatile
    internal var at: Point? = null
        private set

    /** Runs [block] once no other action is using the mouse and keyboard. Not reentrant. */
    internal suspend fun <T> act(block: suspend () -> T): T = turn.withLock { block() }

    /** [events], recording where each pointer event it sends leaves the pointer. */
    internal fun tracked(events: DesktopEvents): DesktopEvents = Tracked(events)

    private inner class Tracked(private val inner: DesktopEvents) : DesktopEvents by inner {
        override suspend fun mouseMove(at: Point, buttons: Int) {
            inner.mouseMove(at, buttons)
            this@DesktopPointer.at = at
        }

        override suspend fun mouseDown(at: Point, clicks: Int) {
            inner.mouseDown(at, clicks)
            this@DesktopPointer.at = at
        }

        override suspend fun mouseUp(at: Point, clicks: Int) {
            inner.mouseUp(at, clicks)
            this@DesktopPointer.at = at
        }

        override suspend fun wheel(at: Point, dx: Float, dy: Float) {
            inner.wheel(at, dx, dy)
            this@DesktopPointer.at = at
        }
    }
}
