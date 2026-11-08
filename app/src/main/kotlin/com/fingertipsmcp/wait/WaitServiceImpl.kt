package com.fingertipsmcp.wait

import android.os.SystemClock
import com.fingertipsmcp.browser.DevtoolsSocket
import com.fingertipsmcp.core.BrowserService
import com.fingertipsmcp.core.DeviceStateTracker
import com.fingertipsmcp.core.EventBus
import com.fingertipsmcp.core.WaitService
import com.fingertipsmcp.lease.Holder
import com.fingertipsmcp.lease.LeaseState
import com.fingertipsmcp.model.ApiException
import com.fingertipsmcp.model.Scope
import com.fingertipsmcp.model.SnapshotOptions
import com.fingertipsmcp.model.WaitRequest
import com.fingertipsmcp.model.WaitResult
import com.fingertipsmcp.tree.TreeSession
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The single condition engine behind `POST /v1/wait`. Conditions are re-evaluated when something
 * actually changes — accessibility content and window changes, device state, and anything on the
 * event bus — rather than on a polling interval. Conditions that depend only on time passing (an
 * idle UI), or that can change with nothing to say so (a page's nodes), ask to be looked at again
 * at a time instead.
 *
 * Browser conditions open a DevTools session for the wait and wake the same loop when Chrome
 * reports a page, network, or target event.
 */
internal class WaitServiceImpl(
    private val prepare: suspend (Boolean) -> Boolean,
    private val tree: TreeSession,
    private val state: DeviceStateTracker,
    private val bus: EventBus,
    private val io: CoroutineDispatcher,
    private val browser: BrowserService,
    private val openDevtools: suspend (String, Long) -> DevtoolsSocket,
    /** Who holds the screen, for `screen` with `person`. */
    private val lease: StateFlow<LeaseState>,
    /** Whether Chrome shows a dialog from a page at this host, for `browser.dialog`. */
    private val dialogFrom: suspend (String) -> Boolean = { false },
) : WaitService {
    override suspend fun wait(request: WaitRequest, scopes: Set<Scope>): WaitResult {
        requireWaitAccess(request, scopes)
        validate(request)
        // Waiting never wakes the device on its own, but it does keep the screen from going dark
        // underneath a long wait.
        prepare(false)
        val browserWatch =
            BrowserWatch(
                    browser,
                    io,
                    openDevtools,
                    now = SystemClock::uptimeMillis,
                    dialogFrom = dialogFrom,
                )
                .takeIf {
                    (request.all + request.any).any(::isBrowserCondition)
                }
        val watchers =
            ConditionWatchers(
                tree = tree,
                state = state,
                io = io,
                lastChangeMs = { tree.lastChangeMs },
                personHolds = { lease.value.holder is Holder.Viewer },
                browser = browserWatch,
            )
        val all = request.all.map(watchers::watcher)
        val any = request.any.map(watchers::watcher)
        val started = SystemClock.uptimeMillis()
        val deadline = started + request.timeoutMs
        return coroutineScope {
            val changes = Channel<Unit>(Channel.CONFLATED)
            try {
                browserWatch?.open(request.all + request.any, request.timeoutMs)
                val pump: Job = launch {
                    changeSources(browserWatch).collect { changes.trySend(Unit) }
                }
                try {
                    val outcome =
                        awaitConditions(all, any, deadline, changes, SystemClock::uptimeMillis)
                    if (outcome.matched) settle(request.settleMs, deadline, changes)
                    result(request, outcome, SystemClock.uptimeMillis() - started, scopes)
                } finally {
                    pump.cancel()
                }
            } finally {
                browserWatch?.close()
            }
        }
    }

    /** Holds the result back until the UI has been quiet for [settleMs], or time runs out. */
    private suspend fun settle(settleMs: Long, deadline: Long, changes: ReceiveChannel<Unit>) {
        if (settleMs <= 0) return
        while (true) {
            val now = SystemClock.uptimeMillis()
            val quietAt = tree.lastChangeMs + settleMs
            if (now >= quietAt || now >= deadline) return
            withTimeoutOrNull((minOf(quietAt, deadline) - now).coerceAtLeast(1)) {
                changes.receive()
            }
        }
    }

    private fun changeSources(browser: BrowserWatch?): Flow<Any> {
        val device =
            merge(
                tree.seq.drop(1),
                tree.windowsVersion.drop(1),
                state.state.drop(1),
                lease.drop(1),
                bus.events,
            )
        val page = browser?.changes ?: return device
        return merge(device, page)
    }

    /** The result. [WaitResult.state] names the foreground app, so only observe gets it. */
    private fun result(
        request: WaitRequest,
        outcome: WaitOutcome,
        elapsedMs: Long,
        scopes: Set<Scope>,
    ): WaitResult =
        WaitResult(
            matched = outcome.matched,
            timedOut = !outcome.matched,
            elapsedMs = elapsedMs,
            matchedAll = outcome.matchedAll,
            matchedAny = outcome.matchedAny,
            snapshot =
                if (request.snapshot) {
                    val options = request.snapshotOptions ?: SnapshotOptions()
                    tree.snapshot(options.copy(format = request.snapshotFormat))
                } else {
                    null
                },
            state = if (Scope.OBSERVE in scopes) state.refresh() else null,
        )

    private fun validate(request: WaitRequest) {
        val count = request.all.size + request.any.size
        if (count == 0) throw ApiException.badRequest("Pass at least one condition in all or any")
        if (count > MAX_CONDITIONS) {
            throw ApiException.badRequest("At most $MAX_CONDITIONS conditions per wait")
        }
        if (request.timeoutMs !in 0..MAX_TIMEOUT_MS) {
            throw ApiException.badRequest("timeoutMs must be 0..$MAX_TIMEOUT_MS")
        }
        if (request.settleMs !in 0..MAX_SETTLE_MS) {
            throw ApiException.badRequest("settleMs must be 0..$MAX_SETTLE_MS")
        }
        val options = request.snapshotOptions ?: return
        if ((options.maxNodes ?: 1) < 1 || (options.maxChars ?: 1) < 1) {
            throw ApiException.badRequest("maxNodes and maxChars must be at least 1")
        }
    }

    private companion object {
        const val MAX_CONDITIONS = 16
        const val MAX_TIMEOUT_MS = 300_000L
        const val MAX_SETTLE_MS = 30_000L
    }
}
