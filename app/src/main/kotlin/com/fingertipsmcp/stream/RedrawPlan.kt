package com.fingertipsmcp.stream

/**
 * When [FrameRelay] draws the last mirrored frame again, on a clock in milliseconds.
 *
 * Once the screen stops changing, a few redraws at [settleMs] after the last mirrored frame let the
 * encoder sharpen a picture it blurred while things moved. After that, one redraw every [idleMs]
 * tells a viewer the stream is alive on a still screen. Each redraw is a message to every viewer,
 * so a still screen sends few of them.
 *
 * Not thread-safe; the relay uses it on its own thread.
 */
internal class RedrawPlan(
    private val settleMs: LongArray = SETTLE_MS,
    private val idleMs: Long = IDLE_MS,
) {
    private var hasFrame = false
    private var lastMirrorMs = 0L
    private var lastDrawMs = 0L
    private var settled = 0

    /** A new frame came from the mirror at [nowMs]. The relay draws it at once. */
    fun mirrored(nowMs: Long) {
        hasFrame = true
        lastMirrorMs = nowMs
        settled = 0
    }

    /** The relay drew at [nowMs], for a mirrored frame or a redraw. */
    fun drew(nowMs: Long) {
        lastDrawMs = nowMs
        while (settled < settleMs.size && nowMs >= lastMirrorMs + settleMs[settled]) settled++
    }

    /** Whether a redraw is due at [nowMs]. Nothing is due until the mirror sends a frame. */
    fun due(nowMs: Long): Boolean = hasFrame && nowMs >= nextAt()

    /** Milliseconds from [nowMs] until the next redraw is due, or null before the first frame. */
    fun delayMs(nowMs: Long): Long? = if (hasFrame) (nextAt() - nowMs).coerceAtLeast(1) else null

    private fun nextAt(): Long {
        val idleAt = lastDrawMs + idleMs
        if (settled >= settleMs.size) return idleAt
        return minOf(lastMirrorMs + settleMs[settled], idleAt)
    }

    companion object {
        /** Redraws after the screen stops changing, in ms after its last frame. */
        val SETTLE_MS = longArrayOf(100, 300, 700)

        /** Longest gap between frames on a still screen. The viewer's stall check waits longer. */
        const val IDLE_MS = 4_000L
    }
}
