package net.die.phoneapi.core

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.withTimeoutOrNull
import net.die.phoneapi.model.DeviceStateSummary

/**
 * Waits until [predicate] holds for the live device state, or [timeoutMs] passes. Screen and
 * keyguard are not broadcast for every change (a dismissal may not be), so every state change and
 * published event re-reads them. Subscribing to [DeviceStateTracker.state] delivers its current
 * value first, so a change between the caller's last check and the subscription is not lost. The
 * re-read publishes on that same flow, which emits again only when the summary actually changed.
 */
suspend fun awaitDeviceState(
    state: DeviceStateTracker,
    bus: EventBus,
    timeoutMs: Long,
    predicate: (DeviceStateSummary) -> Boolean,
): Boolean {
    val changes: Flow<Any> = merge(state.state, bus.events)
    return withTimeoutOrNull(timeoutMs) { changes.first { predicate(state.refresh()) } } != null
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

private const val SETTLE_POLL_MS = 50L
