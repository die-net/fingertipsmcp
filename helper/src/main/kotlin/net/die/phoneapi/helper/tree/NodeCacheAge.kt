package net.die.phoneapi.helper.tree

/**
 * Limits how long a read can trust UiAutomation's node cache.
 *
 * The cache forgets a node only when an accessibility event names it, and an app may send none:
 * Chrome 133 sends UiAutomation no events for a page's fields. A field's text, or a page that has
 * been replaced, then reads as it was until something else clears the cache. Clearing before every
 * read is correct but rebuilds a long page each time, so reads inside [maxAgeMs] share what the
 * last one fetched. [expire] makes the next read fetch again, for a change PhoneAPI made itself.
 */
internal class NodeCacheAge(
    private val clearCache: () -> Boolean,
    private val now: () -> Long,
    private val maxAgeMs: Long = MAX_NODE_CACHE_AGE_MS,
) {
    private var filledAtMs: Long? = null

    /**
     * Runs [block], clearing the cache first if it is too old. The age counts from the end of the
     * read that fetched the nodes, so polling a page slower to read than [maxAgeMs] still gets
     * cached reads between its full ones.
     */
    fun <T> read(block: () -> T): T {
        val fetching = isOld()
        if (fetching) clearCache()
        try {
            return block()
        } finally {
            if (fetching) markFilled()
        }
    }

    /** Clears the cache now, and says whether it could. */
    fun clear(): Boolean {
        val cleared = clearCache()
        markFilled()
        return cleared
    }

    /** Makes the next [read] fetch current nodes. */
    @Synchronized
    fun expire() {
        filledAtMs = null
    }

    @Synchronized
    private fun isOld(): Boolean {
        val filled = filledAtMs
        return filled == null || now() - filled >= maxAgeMs
    }

    @Synchronized
    private fun markFilled() {
        filledAtMs = now()
    }
}

/**
 * How stale a read can be. Reading a 3,000-element page past the cache took about 900 ms on the
 * Android 17 emulator, against 25 ms from it, so this stays well above a polling interval.
 */
internal const val MAX_NODE_CACHE_AGE_MS = 500L
