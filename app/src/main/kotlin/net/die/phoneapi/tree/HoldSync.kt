package net.die.phoneapi.tree

import net.die.phoneapi.model.ApiException

/**
 * Keeps the helper's UiAutomation hold equal to one wanted value: whether anything is collecting
 * events. The helper ties a hold to the client that set it and starts each new client with none, so
 * the whole state is sent again on every attach. A send that fails goes to [onFailure], and the
 * next attach sends the value again. A send fails when no helper is registered or it died during
 * the call, and either way a helper attaches next. A helper that is up and answers with an error
 * has a bug that resending on a timer would not fix.
 */
internal class HoldSync<P : Any>(
    private val send: (P, Boolean) -> Unit,
    private val onFailure: (Boolean, ApiException) -> Unit,
) {
    private var wanted = false

    /**
     * Records whether a hold is wanted and tells [proxy], if there is a helper, when it changed.
     */
    @Synchronized
    fun want(hold: Boolean, proxy: () -> P) {
        if (hold == wanted) return
        wanted = hold
        push { proxy() }
    }

    /** Tells a newly attached helper the current value. */
    @Synchronized
    fun attached(proxy: P) {
        push { proxy }
    }

    private fun push(proxy: () -> P) {
        try {
            send(proxy(), wanted)
        } catch (e: ApiException) {
            onFailure(wanted, e)
        }
    }
}
