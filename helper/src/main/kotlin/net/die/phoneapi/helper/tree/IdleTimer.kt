package net.die.phoneapi.helper.tree

/**
 * Calls [onIdle] once nothing has [touch]ed this for [idleMs] and [busy] is false. At most one
 * check is pending at a time: a touch only records the time, and the check that comes due waits out
 * whatever is left. Accessibility events can arrive many times a second, so a touch must not post
 * anything of its own.
 */
internal class IdleTimer(
    private val idleMs: Long,
    private val now: () -> Long,
    private val post: (Runnable, Long) -> Unit,
    private val busy: () -> Boolean,
    private val onIdle: () -> Unit,
) {
    private val lock = Any()
    private var lastUseMs = 0L
    private var pending = false
    private var running = false
    private val check = Runnable { check() }

    /** Starts timing; called when the session connects. */
    fun start() {
        synchronized(lock) { running = true }
        touch()
    }

    /** Stops timing; a check already posted does nothing. */
    fun stop() {
        synchronized(lock) { running = false }
    }

    /** Records a use, and posts a check if none is pending. */
    fun touch() {
        val schedule: Boolean
        synchronized(lock) {
            lastUseMs = now()
            schedule = running && !pending
            if (schedule) pending = true
        }
        if (schedule) post(check, idleMs)
    }

    private fun check() {
        val wait: Long?
        synchronized(lock) {
            pending = false
            if (!running) return
            val left = idleMs - (now() - lastUseMs)
            wait =
                when {
                    left > 0 -> left
                    busy() -> idleMs
                    else -> null
                }
            if (wait != null) pending = true else running = false
        }
        if (wait != null) post(check, wait) else onIdle()
    }
}
