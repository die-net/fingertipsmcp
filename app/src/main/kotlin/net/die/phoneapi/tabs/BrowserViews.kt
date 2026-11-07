package net.die.phoneapi.tabs

import net.die.phoneapi.core.BrowserService
import net.die.phoneapi.lease.Holder
import net.die.phoneapi.model.ActionResult
import net.die.phoneapi.model.BrowserCaptureResult
import net.die.phoneapi.model.BrowserFindRequest
import net.die.phoneapi.model.BrowserFindResult
import net.die.phoneapi.model.BrowserGestureRequest
import net.die.phoneapi.model.BrowserInput
import net.die.phoneapi.model.BrowserKeyRequest
import net.die.phoneapi.model.BrowserReadRequest
import net.die.phoneapi.model.BrowserReadResult
import net.die.phoneapi.model.BrowserSnapshot
import net.die.phoneapi.model.BrowserSnapshotOptions
import net.die.phoneapi.model.BrowserSwipeRequest
import net.die.phoneapi.model.BrowserTapRequest
import net.die.phoneapi.model.BrowserTarget
import net.die.phoneapi.model.BrowserTextRequest
import net.die.phoneapi.model.ConsoleRequest
import net.die.phoneapi.model.ConsoleResult
import net.die.phoneapi.model.EvalRequest
import net.die.phoneapi.model.EvalResult

/**
 * The browser as one MCP session sees it: its own tabs and unowned ones, as [Tabs] rules. Calls
 * that bring a tab forward tell [Tabs], and calls a page could answer with a popup let it put
 * Chrome back.
 */
internal class SessionBrowser(
    private val tabs: Tabs,
    private val base: BrowserService,
    private val holder: Holder.Session,
    private val related: (Holder.Session) -> Boolean,
) : BrowserService {
    override suspend fun targets(): List<BrowserTarget> {
        tabs.sweep()
        return tabs.visible(holder, base.targets())
    }

    override suspend fun openTab(
        url: String,
        capture: Boolean,
        incognito: Boolean,
        created: suspend (String) -> Unit,
    ): BrowserTarget = tabs.opened(holder, url, capture, incognito, created)

    override suspend fun chromeStopped(): Boolean = base.chromeStopped()

    override fun awaitingPage(id: String): Boolean = base.awaitingPage(id)

    override suspend fun awaitAnswer(id: String) = base.awaitAnswer(id)

    override suspend fun navigate(id: String, url: String): BrowserTarget =
        acting(id) { base.navigate(id, url) }

    // Only Tabs asks, of the browser under this view. A session must not see others' tabs.
    override suspend fun incognitoTabs(): Set<String> = emptySet()

    override suspend fun closeTab(id: String): ActionResult =
        mine(id) {
                tabs.requireOffPersonsScreen(id)
                base.closeTab(id)
            }
            .also { tabs.closed(id) }

    override suspend fun activate(id: String) = mine(id) { base.activate(id) }

    override suspend fun showing(id: String): Boolean? = mine(id) { base.showing(id) }

    override suspend fun timersStalled(id: String): Boolean = mine(id) { base.timersStalled(id) }

    override suspend fun watchTimers(id: String) = mine(id) { base.watchTimers(id) }

    override suspend fun snapshot(id: String, options: BrowserSnapshotOptions): BrowserSnapshot =
        mine(id) { base.snapshot(id, options) }.also { forward(id, options.show) }

    override suspend fun read(id: String, request: BrowserReadRequest): BrowserReadResult =
        mine(id) { base.read(id, request) }.also { forward(id, request.show) }

    override suspend fun find(id: String, request: BrowserFindRequest): BrowserFindResult =
        mine(id) { base.find(id, request) }

    override suspend fun tap(id: String, request: BrowserTapRequest): ActionResult =
        acting(id, request.input) { base.tap(id, request) }

    override suspend fun swipe(id: String, request: BrowserSwipeRequest): ActionResult =
        acting(id, request.input) { base.swipe(id, request) }

    override suspend fun gesture(id: String, request: BrowserGestureRequest): ActionResult =
        acting(id, request.input) { base.gesture(id, request) }

    override suspend fun key(id: String, request: BrowserKeyRequest): ActionResult =
        acting(id, request.input) { base.key(id, request) }

    override suspend fun text(id: String, request: BrowserTextRequest): ActionResult =
        acting(id, request.input) { base.text(id, request) }

    override suspend fun evaluate(id: String, request: EvalRequest): EvalResult =
        acting(id) { base.evaluate(id, request) }

    override suspend fun console(id: String, request: ConsoleRequest): ConsoleResult =
        mine(id) { base.console(id, request) }

    override suspend fun capture(id: String, on: Boolean): BrowserCaptureResult =
        mine(id) { base.capture(id, on) }

    private suspend fun <T> mine(id: String, block: suspend () -> T): T {
        tabs.access(holder, id, related)
        return block()
    }

    /** A call a page could answer with a popup; touch [input] brings the tab forward first. */
    private suspend fun <T> acting(
        id: String,
        input: BrowserInput? = null,
        block: suspend () -> T,
    ): T {
        val value = mine(id) { tabs.besidePerson(holder, id, block) }
        forward(id, input == BrowserInput.TOUCH)
        tabs.afterInput(holder)
        return value
    }

    private fun forward(id: String, shown: Boolean) {
        if (shown) tabs.broughtForward(holder, id)
    }
}

/** The browser for REST and the raw CDP socket: every tab no live session owns. */
internal class SharedBrowser(private val tabs: Tabs, private val base: BrowserService) :
    BrowserService {
    override suspend fun targets(): List<BrowserTarget> = tabs.sharedTargets()

    override suspend fun openTab(
        url: String,
        capture: Boolean,
        incognito: Boolean,
        created: suspend (String) -> Unit,
    ): BrowserTarget = base.openTab(url, capture, incognito, created)

    override suspend fun chromeStopped(): Boolean = base.chromeStopped()

    override fun awaitingPage(id: String): Boolean = base.awaitingPage(id)

    override suspend fun awaitAnswer(id: String) = base.awaitAnswer(id)

    override suspend fun navigate(id: String, url: String): BrowserTarget =
        free(id) { base.navigate(id, url) }

    // Only Tabs asks, of the browser under this view.
    override suspend fun incognitoTabs(): Set<String> = emptySet()

    override suspend fun closeTab(id: String): ActionResult = free(id) { base.closeTab(id) }

    override suspend fun activate(id: String) = free(id) { base.activate(id) }

    override suspend fun showing(id: String): Boolean? = free(id) { base.showing(id) }

    override suspend fun timersStalled(id: String): Boolean = free(id) { base.timersStalled(id) }

    override suspend fun watchTimers(id: String) = free(id) { base.watchTimers(id) }

    override suspend fun snapshot(id: String, options: BrowserSnapshotOptions): BrowserSnapshot =
        free(id) { base.snapshot(id, options) }

    override suspend fun read(id: String, request: BrowserReadRequest): BrowserReadResult =
        free(id) { base.read(id, request) }

    override suspend fun find(id: String, request: BrowserFindRequest): BrowserFindResult =
        free(id) { base.find(id, request) }

    override suspend fun tap(id: String, request: BrowserTapRequest): ActionResult =
        free(id) { base.tap(id, request) }

    override suspend fun swipe(id: String, request: BrowserSwipeRequest): ActionResult =
        free(id) { base.swipe(id, request) }

    override suspend fun gesture(id: String, request: BrowserGestureRequest): ActionResult =
        free(id) { base.gesture(id, request) }

    override suspend fun key(id: String, request: BrowserKeyRequest): ActionResult =
        free(id) { base.key(id, request) }

    override suspend fun text(id: String, request: BrowserTextRequest): ActionResult =
        free(id) { base.text(id, request) }

    override suspend fun evaluate(id: String, request: EvalRequest): EvalResult =
        free(id) { base.evaluate(id, request) }

    override suspend fun console(id: String, request: ConsoleRequest): ConsoleResult =
        free(id) { base.console(id, request) }

    override suspend fun capture(id: String, on: Boolean): BrowserCaptureResult =
        free(id) { base.capture(id, on) }

    private suspend fun <T> free(id: String, block: suspend () -> T): T {
        tabs.accessShared(id)
        return block()
    }
}
