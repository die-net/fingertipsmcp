package com.fingertipsmcp.input

/**
 * Which clip on the clipboard is FingertipsMCP's, by the timestamp Android gave it when
 * FingertipsMCP wrote or copied it. A later copy by anyone gets a new timestamp. A later change to
 * the same clip keeps it, which a change count does not: on Android 16 one write moves the count
 * twice, the second time about a second later. The helper restarting keeps it too.
 */
internal class OwnClip {
    @Volatile private var timestamp = NONE

    /** Whether FingertipsMCP put a clip on the clipboard that cleanup has not looked at yet. */
    val held: Boolean
        get() = timestamp != NONE

    /**
     * FingertipsMCP put the clip with [timestamp] on the clipboard. -1, an empty clipboard,
     * forgets.
     */
    fun put(timestamp: Long) {
        this.timestamp = if (timestamp < 0) NONE else timestamp
    }

    fun forget() {
        timestamp = NONE
    }

    /**
     * Whether the clip with timestamp [now] is still FingertipsMCP's. Forgets it either way, since
     * the caller then clears it, or someone has replaced it.
     */
    fun take(now: Long): Boolean {
        val mine = timestamp
        timestamp = NONE
        return mine != NONE && mine == now
    }

    private companion object {
        const val NONE = -1L
    }
}
