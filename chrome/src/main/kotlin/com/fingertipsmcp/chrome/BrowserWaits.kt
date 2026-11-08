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
import com.fingertipsmcp.wait.isBrowserCondition
import com.fingertipsmcp.wait.monotonicMs
import com.fingertipsmcp.wait.requireWaitAccess
import com.fingertipsmcp.wait.runWait
import com.fingertipsmcp.wait.validateBrowser
import com.fingertipsmcp.wait.validateWait
import kotlinx.coroutines.CoroutineDispatcher

/**
 * `POST /v1/wait` and `wait_for` with browser conditions only: there is no phone UI tree on a
 * desktop. The loop is the one Android's waits use, [runWait]. Settling waits for the pages to stop
 * reporting changes.
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
        val outcome =
            try {
                watch.open(conditions, request.timeoutMs)
                runWait(
                    all,
                    any,
                    watch.changes,
                    deadline = started + request.timeoutMs,
                    settleMs = request.settleMs,
                    now = clock,
                )
            } finally {
                watch.close()
            }
        return WaitResult(
            matched = outcome.matched,
            timedOut = !outcome.matched,
            elapsedMs = clock() - started,
            matchedAll = outcome.matchedAll,
            matchedAny = outcome.matchedAny,
        )
    }
}
