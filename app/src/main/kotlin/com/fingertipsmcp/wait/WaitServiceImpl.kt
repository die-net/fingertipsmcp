package com.fingertipsmcp.wait

import android.os.SystemClock
import com.fingertipsmcp.browser.DevtoolsEndpoints
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
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch

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
    private val devtools: DevtoolsEndpoints,
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
                    devtools,
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
                    if (outcome.matched) {
                        settle(request.settleMs, deadline, changes, SystemClock::uptimeMillis) {
                            tree.lastChangeMs
                        }
                    }
                    result(request, outcome, SystemClock.uptimeMillis() - started, scopes)
                } finally {
                    pump.cancel()
                }
            } finally {
                browserWatch?.close()
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
        validateWait(request)
        val options = request.snapshotOptions ?: return
        if ((options.maxNodes ?: 1) < 1 || (options.maxChars ?: 1) < 1) {
            throw ApiException.badRequest("maxNodes and maxChars must be at least 1")
        }
    }
}
