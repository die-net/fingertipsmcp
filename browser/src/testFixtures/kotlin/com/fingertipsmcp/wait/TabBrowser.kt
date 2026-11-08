package com.fingertipsmcp.wait

import com.fingertipsmcp.browser.CHROME_SOCKET
import com.fingertipsmcp.core.BrowserService
import com.fingertipsmcp.model.ActionResult
import com.fingertipsmcp.model.BrowserCaptureResult
import com.fingertipsmcp.model.BrowserFindRequest
import com.fingertipsmcp.model.BrowserFindResult
import com.fingertipsmcp.model.BrowserGestureRequest
import com.fingertipsmcp.model.BrowserKeyRequest
import com.fingertipsmcp.model.BrowserReadRequest
import com.fingertipsmcp.model.BrowserReadResult
import com.fingertipsmcp.model.BrowserSnapshot
import com.fingertipsmcp.model.BrowserSnapshotOptions
import com.fingertipsmcp.model.BrowserSwipeRequest
import com.fingertipsmcp.model.BrowserTapRequest
import com.fingertipsmcp.model.BrowserTarget
import com.fingertipsmcp.model.BrowserTextRequest
import com.fingertipsmcp.model.ConsoleRequest
import com.fingertipsmcp.model.ConsoleResult
import com.fingertipsmcp.model.EvalRequest
import com.fingertipsmcp.model.EvalResult

/**
 * A browser with one Chrome tab, for a wait to look up, whose server has answered. A wait uses
 * nothing else, so every other call fails the test that made it. A test overrides what it needs. It
 * is a test fixture, so the `chrome` module's tests can use it too.
 */
open class TabBrowser : BrowserService {
    override suspend fun targets(): List<BrowserTarget> =
        listOf(BrowserTarget(id = "$CHROME_SOCKET~t", type = "page", socket = CHROME_SOCKET))

    override fun awaitingPage(id: String): Boolean = false

    override suspend fun awaitAnswer(id: String) = Unit

    override suspend fun openTab(
        url: String,
        capture: Boolean,
        incognito: Boolean,
        created: suspend (String) -> Unit,
    ): BrowserTarget = unused()

    override suspend fun chromeStopped(): Boolean = unused()

    override suspend fun navigate(id: String, url: String): BrowserTarget = unused()

    override suspend fun incognitoTabs(): Set<String> = unused()

    override suspend fun closeTab(id: String): ActionResult = unused()

    override suspend fun activate(id: String): Unit = unused()

    override suspend fun showing(id: String): Boolean? = unused()

    override suspend fun timersStalled(id: String): Boolean = unused()

    override suspend fun watchTimers(id: String): Unit = unused()

    override suspend fun snapshot(id: String, options: BrowserSnapshotOptions): BrowserSnapshot =
        unused()

    override suspend fun read(id: String, request: BrowserReadRequest): BrowserReadResult = unused()

    override suspend fun find(id: String, request: BrowserFindRequest): BrowserFindResult = unused()

    override suspend fun tap(id: String, request: BrowserTapRequest): ActionResult = unused()

    override suspend fun swipe(id: String, request: BrowserSwipeRequest): ActionResult = unused()

    override suspend fun gesture(id: String, request: BrowserGestureRequest): ActionResult =
        unused()

    override suspend fun key(id: String, request: BrowserKeyRequest): ActionResult = unused()

    override suspend fun text(id: String, request: BrowserTextRequest): ActionResult = unused()

    override suspend fun evaluate(id: String, request: EvalRequest): EvalResult = unused()

    override suspend fun console(id: String, request: ConsoleRequest): ConsoleResult = unused()

    override suspend fun capture(id: String, on: Boolean): BrowserCaptureResult = unused()

    private fun unused(): Nothing = error("A wait does not use this")
}
