package com.fingertipsmcp.server.routes

import com.fingertipsmcp.model.PointerFrame
import com.fingertipsmcp.model.PointerOp
import kotlinx.coroutines.channels.SendChannel

/**
 * A viewer's contacts that the input service has seen go down, and the frames forwarded for them.
 * [holds] says whether the viewer has the screen now. Each decision and its send happen under one
 * lock, so the service gets a touch and the cancel for a lost screen in the order they were
 * decided. [frames] has no limit, so a send never waits while the lock is held.
 */
internal class ViewerFingers(
    private val holds: () -> Boolean,
    private val frames: SendChannel<PointerFrame>,
) {
    private val down = HashSet<Int>()

    /**
     * Forwards [frame] when it belongs to a gesture the service has. A DOWN starts one only while
     * the viewer holds the screen, which it checks again here, since another viewer can take the
     * screen after the touch claimed it.
     */
    fun forward(frame: PointerFrame) =
        synchronized(down) {
            val forward =
                when (frame.op) {
                    PointerOp.DOWN -> holds().also { if (it) down += frame.id }
                    PointerOp.MOVE -> frame.id in down
                    PointerOp.UP -> down.remove(frame.id)
                    PointerOp.CANCEL -> down.isNotEmpty().also { down.clear() }
                }
            // The channel stays open while frames are read, so this always sends.
            if (forward) frames.trySend(frame)
        }

    /**
     * Cancels the gesture in progress once the screen is someone else's. Once the stream has ended
     * the channel is closed, and the service has already let go of any contact still down, so the
     * cancel is dropped.
     */
    fun checkLost() =
        synchronized(down) {
            if (!holds() && down.isNotEmpty()) {
                down.clear()
                frames.trySend(PointerFrame(PointerOp.CANCEL))
            }
        }

    /** Whether no contact is down, so the screen can be let go. */
    fun noneDown(): Boolean = synchronized(down) { down.isEmpty() }
}
