package net.die.phoneapi.core

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.withTimeoutOrNull
import net.die.phoneapi.model.DeviceStateSummary

/**
 * Waits until [predicate] holds for the live device state, or [timeoutMs] passes. Screen and
 * keyguard are not broadcast for every change (a dismissal may not be), so every state change and
 * published event re-reads them, and so does a poll every [STATE_POLL_MS] between them. Subscribing
 * to [DeviceStateTracker.state] delivers its current value first, so a change between the caller's
 * last check and the subscription is not lost. The re-read publishes on that same flow, which emits
 * again only when the summary actually changed.
 */
suspend fun awaitDeviceState(
    state: DeviceStateTracker,
    bus: EventBus,
    timeoutMs: Long,
    predicate: (DeviceStateSummary) -> Boolean,
): Boolean = awaitChecked(merge(state.state, bus.events), timeoutMs) { predicate(state.refresh()) }

/**
 * Waits until [check] holds, or [timeoutMs] passes, and says whether it held. It checks now, on
 * each of [changes], and every [STATE_POLL_MS], for a state that can change without anything
 * announcing it.
 */
internal suspend fun awaitChecked(
    changes: Flow<Any?>,
    timeoutMs: Long,
    check: suspend () -> Boolean,
): Boolean {
    val ticks = flow {
        while (true) {
            emit(Unit)
            delay(STATE_POLL_MS)
        }
    }
    return withTimeoutOrNull(timeoutMs) { merge(changes, ticks).first { check() } } != null
}

/**
 * Polls [done] until it holds or [timeoutMs] is up, and says whether it held. This is for a result
 * nothing announces, such as a tab switch or a keyboard redrawing, which takes as long as the phone
 * takes.
 */
internal suspend fun settled(timeoutMs: Long, done: suspend () -> Boolean): Boolean =
    withTimeoutOrNull(timeoutMs) {
        while (!done()) delay(SETTLE_POLL_MS)
        true
    } == true

/** How often screen and keyguard state is read again while a wait has heard nothing. */
internal const val STATE_POLL_MS = 100L

private const val SETTLE_POLL_MS = 50L
