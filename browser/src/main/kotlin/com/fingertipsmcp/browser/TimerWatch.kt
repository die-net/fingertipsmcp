package com.fingertipsmcp.browser

import com.fingertipsmcp.model.ApiException
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Whether a tab's timers still run. On an Android 11 phone, Chrome stopped a hidden tab's timers
 * about a minute after hiding it, well before pausing the page, so input seemed to work but a page
 * that reacts through timers never did, and nothing said why.
 *
 * Waiting for a timer before every input would cost about a second each, since Chrome runs a hidden
 * tab's timers at most once a second. So a check starts alongside each input, in the background,
 * and the next call on the tab reads its result. Checks run in an isolated world, so the page
 * cannot see them, and Chrome stops that world's timers along with the page's.
 */
internal class TimerWatch(
    private val scope: CoroutineScope,
    /** Runs a block on the tab's DevTools connection. */
    private val onPage: suspend (String, suspend (CdpSession) -> Unit) -> Unit,
    /** The time in ms, on the clock [scope]'s waits run on. */
    private val now: () -> Long = System::currentTimeMillis,
) {
    // Starting and Waiting compare by identity, so each check records only over its own state.
    private sealed interface State {
        /** A check is connecting; it has not set its timer yet. */
        class Starting : State

        /** A check set its timer at [atMs] and is waiting for it. */
        class Waiting(val atMs: Long) : State

        /** A check's timer did not fire within [WATCH_MS]. */
        data object Stalled : State
    }

    private val states = ConcurrentHashMap<String, State>()

    /** Starts a check on [id], replacing any earlier one. */
    fun arm(id: String) {
        val starting = State.Starting()
        states[id] = starting
        scope.launch {
            var mine: State = starting
            val fired =
                check(id, WATCH_MS) { waiting ->
                    if (states.replace(id, starting, waiting)) mine = waiting
                }
            // Only the latest check records a result; a newer one has replaced this one's state.
            if (fired == false) states.replace(id, mine, State.Stalled) else states.remove(id, mine)
        }
    }

    /**
     * Starts a check on [id], a Chrome tab, unless one is under way or has found its timers
     * stopped. Other browsers' tabs are not checked, as for input.
     */
    fun watch(id: String) {
        if (parseBrowserTargetId(id).first == CHROME_SOCKET && !states.containsKey(id)) arm(id)
    }

    /** Whether [id]'s last check shows its timers stopped: overdue, or never fired. */
    fun stalled(id: String): Boolean =
        when (val state = states[id]) {
            is State.Waiting -> now() - state.atMs > STALL_MS
            State.Stalled -> true
            else -> false
        }

    /** Forgets [id]'s check, as when the tab comes on screen or loads a new page. */
    fun clear(id: String) {
        states.remove(id)
    }

    /**
     * Checks [id] again now, waiting for its timer, so a tab that has come back to life is not
     * refused. True when its timers are still stopped.
     */
    suspend fun confirm(id: String): Boolean {
        val fired = check(id, STALL_MS) {}
        if (fired == false) states[id] = State.Stalled else states.remove(id)
        return fired == false
    }

    /**
     * Sets a zero-length timer in an isolated world on [id] and waits up to [limitMs] for it. Null
     * when that cannot be told, as when the page is busy running script or has closed.
     */
    private suspend fun check(
        id: String,
        limitMs: Long,
        onSet: (State.Waiting) -> Unit,
    ): Boolean? {
        var fired: Boolean? = null
        try {
            onPage(id) { cdp ->
                val world = withTimeoutOrNull(SETUP_MS) { isolatedWorld(cdp) } ?: return@onPage
                onSet(State.Waiting(now()))
                fired =
                    withTimeoutOrNull(limitMs) { evaluateInWorld(cdp, world, TIMER, true) } != null
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: ApiException) {
            return null
        }
        return fired
    }

    companion object {
        /** Longer than the once-a-second batching Chrome gives a hidden tab that still runs. */
        const val STALL_MS = 1_100L

        /** How long a background check waits, within each DevTools call's 15 s. */
        const val WATCH_MS = 10_000L

        /** How long a check waits for its isolated world, past which the page is busy. */
        const val SETUP_MS = 2_000L
        // Not 0: Chrome runs a zero-delay timer at once, on a queue it never throttles or stops.
        private const val TIMER = "new Promise((done) => setTimeout(() => done(true), 1))"
    }
}

/** Input on a tab whose timers have stopped. */
internal fun timersStopped(id: String): ApiException =
    ApiException(
        409,
        "tab_frozen",
        "Chrome has stopped the timers of tab $id, as it does to tabs in the background, so the " +
            "page may not react to input, and your last input on it may not have taken effect. " +
            FROZEN_REMEDY,
    )

/**
 * A wait on a tab whose timers have stopped. Chrome stops a hidden tab's timers when it freezes the
 * whole page, so nothing on it will change and the wait could only run out its timeout.
 */
fun waitOnStoppedTab(id: String): ApiException =
    ApiException(
        409,
        "tab_frozen",
        "Chrome has frozen tab $id, as it does to tabs hidden in the background, so nothing on " +
            "the page will change and this wait cannot match. " +
            FROZEN_REMEDY,
    )

private const val FROZEN_REMEDY =
    "Reading it still works. To use it, bring it forward with input touch, or browser_read or " +
        "browser_snapshot with show, which take the screen. Or reload it with browser_navigate " +
        "to its URL, which loses anything typed or scrolled."
