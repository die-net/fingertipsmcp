package com.fingertipsmcp.chrome

import com.fingertipsmcp.browser.DevtoolsEndpoints
import com.fingertipsmcp.core.BrowserService
import com.fingertipsmcp.core.WaitService
import com.fingertipsmcp.model.ApiException
import com.fingertipsmcp.model.Scope
import com.fingertipsmcp.model.WaitRequest
import com.fingertipsmcp.model.WaitResult
import com.fingertipsmcp.wait.BrowserWatch
import com.fingertipsmcp.wait.ConditionWatcher
import com.fingertipsmcp.wait.awaitConditions
import com.fingertipsmcp.wait.isBrowserCondition
import com.fingertipsmcp.wait.monotonicMs
import com.fingertipsmcp.wait.requireWaitAccess
import com.fingertipsmcp.wait.settle
import com.fingertipsmcp.wait.validateBrowser
import com.fingertipsmcp.wait.validateWait
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * `POST /v1/wait` and `wait_for` with browser conditions only: there is no phone UI tree on a
 * desktop. The loop is the one Android's waits use. Settling waits for the pages to stop reporting
 * changes.
 */
class BrowserWaits(
    private val browser: BrowserService,
    private val devtools: DevtoolsEndpoints,
    /** Where a watch sets itself up. */
    private val io: CoroutineDispatcher,
    private val clock: () -> Long = ::monotonicMs,
) : WaitService {
    override suspend fun wait(request: WaitRequest, scopes: Set<Scope>): WaitResult {
        requireWaitAccess(request, scopes)
        validateWait(request)
        val conditions = request.all + request.any
        if (conditions.any { !isBrowserCondition(it) }) {
            throw ApiException.badRequest(
                "fingertips-chrome waits on browser conditions only. Node, window, ime, idle, screen, " +
                    "and keyguard conditions need a phone."
            )
        }
        if (request.snapshot) {
            throw ApiException.badRequest(
                "snapshot is the phone's UI tree. Take a browser_snapshot after the wait."
            )
        }
        conditions.forEach(::validateBrowser)
        val watch = BrowserWatch(browser, io, devtools, clock)
        val all = request.all.map { condition -> ConditionWatcher { watch.check(condition) } }
        val any = request.any.map { condition -> ConditionWatcher { watch.check(condition) } }
        val started = clock()
        val deadline = started + request.timeoutMs
        val lastChange = AtomicLong(started)
        return coroutineScope {
            val changes = Channel<Unit>(Channel.CONFLATED)
            try {
                watch.open(conditions, request.timeoutMs)
                val pump = launch {
                    watch.changes.collect {
                        lastChange.set(clock())
                        changes.trySend(Unit)
                    }
                }
                try {
                    val outcome = awaitConditions(all, any, deadline, changes, clock)
                    if (outcome.matched) {
                        settle(request.settleMs, deadline, changes, clock) { lastChange.get() }
                    }
                    WaitResult(
                        matched = outcome.matched,
                        timedOut = !outcome.matched,
                        elapsedMs = clock() - started,
                        matchedAll = outcome.matchedAll,
                        matchedAny = outcome.matchedAny,
                    )
                } finally {
                    pump.cancel()
                }
            } finally {
                watch.close()
            }
        }
    }
}
