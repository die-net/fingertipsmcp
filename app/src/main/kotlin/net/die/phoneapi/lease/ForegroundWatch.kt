package net.die.phoneapi.lease

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.die.phoneapi.model.ApiException

/**
 * Catches a call from a session without the screen that brings an app forward, as docs/sharing.md
 * describes under "When something comes forward anyway". Android and Chrome block nearly all of
 * these, so this is a tripwire for workflows that rely on one, not something agents handle in
 * normal use.
 *
 * A change of top task during the call, or within [LATE_MS] after, with no screen call running or
 * started meanwhile, is the session's doing. The call fails with `took_screen`. A session that
 * caused it while no one held the screen gets the screen, so it can look and clean up. Otherwise
 * the app that was in front comes back, and the holder is told.
 */
class ForegroundWatch(
    private val lease: ScreenLease,
    private val scope: CoroutineScope,
    /** The package of the top task, or null when that cannot be told. */
    private val top: suspend () -> String?,
    /** Brings a package's app back to the front. */
    private val bringBack: suspend (String) -> Unit,
    /** Logs and publishes that a session brought [String] forward. */
    private val report: (Holder.Session, String) -> Unit,
) {
    private val counts = ConcurrentHashMap<Holder.Session, AtomicInteger>()
    private val inFlight = AtomicInteger()

    /** How many times [holder] brought something forward. */
    fun count(holder: Holder.Session): Int = counts[holder]?.get() ?: 0

    fun forget(holder: Holder.Session) {
        counts.remove(holder)
    }

    /** Runs [holder]'s call [block], watching the top task when [holder] lacks the screen. */
    suspend fun <T> watch(holder: Holder.Session, block: suspend () -> T): T {
        if (lease.holds(holder) || lease.state.value.holder is Holder.Viewer) return block()
        val before = top() ?: return block()
        val calls = lease.screenCalls
        inFlight.incrementAndGet()
        val value =
            try {
                block()
            } finally {
                inFlight.decrementAndGet()
            }
        val after = top()
        if (after != null && after != before && quiet(calls)) {
            throw tookScreen(holder, before, after, late = false)
        }
        if (after != null) {
            scope.launch {
                delay(LATE_MS)
                val late = top()
                if (late != null && late != after && quiet(calls)) {
                    tookScreen(holder, after, late, late = true)
                }
            }
        }
        return value
    }

    /** No screen call ran since [calls] was read, so a change is not the holder's. */
    private fun quiet(calls: Long): Boolean =
        lease.screenCalls == calls &&
            !lease.callRunning() &&
            // A person in the viewer drives the screen without screen calls.
            lease.state.value.holder !is Holder.Viewer

    private suspend fun tookScreen(
        holder: Holder.Session,
        before: String,
        after: String,
        late: Boolean,
    ): ApiException {
        report(holder, after)
        val current = lease.state.value.holder
        // With another call from a session without the screen in flight, it is not clear whose
        // doing this was, so neither gets the screen.
        val alone = inFlight.get() == 0
        val outcome =
            if (current == null && alone && lease.claim(holder)) {
                "This session now holds the screen: check it, and undo what came forward, such " +
                    "as stopping navigation an app started."
            } else {
                bringBack(before)
                current?.let {
                    lease.addNote(
                        it,
                        "$after came forward briefly, from another session's call, and $before " +
                            "was brought back. Check the screen before acting.",
                    )
                }
                "PhoneAPI brought $before back."
            }
        val message =
            "$after came forward ${if (late) "just after" else "during"} a call from this " +
                "session, which does not hold the screen. $outcome Only calls that hold the " +
                "screen should bring an app forward."
        if (late) lease.addNote(holder, "took_screen: $message")
        // Counted last, so whoever sees the count also sees the screen and notes settled.
        counts.getOrPut(holder) { AtomicInteger() }.incrementAndGet()
        return ApiException(409, "took_screen", message)
    }

    companion object {
        /** How long after a call a change of top task is still the call's doing. */
        const val LATE_MS = 2_000L
    }
}
