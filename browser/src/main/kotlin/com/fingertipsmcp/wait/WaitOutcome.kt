package com.fingertipsmcp.wait

import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/** Where a wait's conditions stand. [recheckAtMs] is when one asks to be looked at again. */
data class WaitOutcome(
    val matched: Boolean,
    val matchedAll: List<Int>,
    val matchedAny: List<Int>,
    val recheckAtMs: Long?,
)

/**
 * Looks at the conditions until they match or [deadline] passes, in [now]'s time, which is
 * monotonic milliseconds. It looks when [changes] says something changed, and when a condition's
 * check asks to be looked at again.
 */
suspend fun awaitConditions(
    all: List<ConditionWatcher>,
    any: List<ConditionWatcher>,
    deadline: Long,
    changes: ReceiveChannel<Unit>,
    now: () -> Long,
): WaitOutcome {
    while (true) {
        val outcome = evaluate(all, any)
        val at = now()
        if (outcome.matched || at >= deadline) return outcome
        val until = minOf(deadline, outcome.recheckAtMs ?: Long.MAX_VALUE)
        val changed = withTimeoutOrNull((until - at).coerceAtLeast(1)) { changes.receive() }
        if (changed != null) {
            // Coalesce the burst of events a single screen transition produces, so the tree is
            // only searched once per settled change.
            delay(DEBOUNCE_MS)
            changes.tryReceive()
        }
    }
}

private suspend fun evaluate(
    all: List<ConditionWatcher>,
    any: List<ConditionWatcher>,
): WaitOutcome {
    val matchedAll = ArrayList<Int>(all.size)
    val matchedAny = ArrayList<Int>(any.size)
    var recheckAtMs: Long? = null
    all.forEachIndexed { i, watcher ->
        val check = watcher.check()
        if (check.satisfied) matchedAll += i
        recheckAtMs = earliest(recheckAtMs, check.recheckAtMs)
    }
    any.forEachIndexed { i, watcher ->
        val check = watcher.check()
        if (check.satisfied) matchedAny += i
        recheckAtMs = earliest(recheckAtMs, check.recheckAtMs)
    }
    val matched = matchedAll.size == all.size && (any.isEmpty() || matchedAny.isNotEmpty())
    return WaitOutcome(matched, matchedAll, matchedAny, recheckAtMs)
}

private const val DEBOUNCE_MS = 40L

private fun earliest(a: Long?, b: Long?): Long? =
    if (a == null || b == null) a ?: b else minOf(a, b)
