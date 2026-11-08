package com.fingertipsmcp.wait

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * One wait, as the phone and FingertipsMCP for Chrome both run it: looks at the conditions each
 * time [changes] emits, until they match or [deadline] passes, then holds a match back until
 * nothing has changed for [settleMs]. Times are [now]'s monotonic milliseconds. [lastChangeMs] says
 * when what settling watches last changed; by default that is the last time [changes] emitted. It
 * returns once it has stopped collecting [changes], so a source can be closed after.
 */
suspend fun runWait(
    all: List<ConditionWatcher>,
    any: List<ConditionWatcher>,
    changes: Flow<Any>,
    deadline: Long,
    settleMs: Long,
    now: () -> Long,
    lastChangeMs: (() -> Long)? = null,
): WaitOutcome = coroutineScope {
    val wake = Channel<Unit>(Channel.CONFLATED)
    val lastEmitMs = AtomicLong(now())
    val pump = launch {
        changes.collect {
            lastEmitMs.set(now())
            wake.trySend(Unit)
        }
    }
    try {
        val outcome = awaitConditions(all, any, deadline, wake, now)
        if (outcome.matched) settle(settleMs, deadline, wake, now, lastChangeMs ?: lastEmitMs::get)
        outcome
    } finally {
        pump.cancel()
    }
}
