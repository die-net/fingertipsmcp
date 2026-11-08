package com.fingertipsmcp.core

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

@Suppress("ComplexInterface") // A tab's targets, navigation, and input are one service.
interface BrowserService {
    suspend fun targets(): List<BrowserTarget>

    /**
     * Opens a tab on [url], returning once its navigation has committed, so the tab holds the page
     * and not the blank one a new tab shows first. The page may still be loading. A server that
     * does not answer in time leaves the tab waiting, with `loading` set. A PDF Chrome saves rather
     * than shows is put in a tab of FingertipsMCP's own, with `savedPdf` set, and any other
     * download fails with `download_saved`. With [capture], the tab records the JSON responses its
     * page fetches from the start of its load, as [capture] does. With [incognito], the tab is in
     * Chrome's incognito profile, which brings Chrome forward, so the caller holds the screen. With
     * [background], a desktop Chrome leaves the tab hidden from its first load, and the tab showing
     * stays showing. Chrome on Android ignores it.
     *
     * [created] runs with each tab's id as soon as Chrome has made it, before the wait, for what
     * must not wait for the page, such as owning the tab. A failure in it closes the tab and fails
     * the call. It can run twice, since a saved PDF moves to a second tab, so the id returned is
     * the one that lasts.
     */
    suspend fun openTab(
        url: String,
        capture: Boolean = false,
        incognito: Boolean = false,
        background: Boolean = false,
        created: suspend (String) -> Unit = {},
    ): BrowserTarget

    /**
     * Whether [id] is a tab whose server had not answered when [openTab] returned. While such a tab
     * shows about:blank, that is its first empty page, not the page it was opened on.
     */
    fun awaitingPage(id: String): Boolean

    /**
     * Returns once the tab [id] is no longer held up by its server: it committed or closed, or
     * FingertipsMCP stopped listening for that. At once for any other tab. A connection made before
     * then is not worth having, since Chrome either holds it until the commit or shows it the blank
     * first page.
     */
    suspend fun awaitAnswer(id: String)

    /**
     * Whether Chrome is not running, so opening a tab starts it first. That brings Chrome forward,
     * so the caller holds the screen while the tab opens. False when it cannot tell.
     */
    suspend fun chromeStopped(): Boolean

    suspend fun navigate(id: String, url: String): BrowserTarget

    /**
     * The ids of Chrome's incognito tabs, whoever opened them. Only the DevTools browser knows, so
     * the session and shared views, which never need it, have none.
     */
    suspend fun incognitoTabs(): Set<String>

    /** Closes a tab over DevTools, without bringing it forward. */
    suspend fun closeTab(id: String): ActionResult

    /**
     * Makes [id] the tab Chrome shows. With Chrome in the background, this may bring it forward.
     */
    suspend fun activate(id: String)

    /**
     * Whether [id] reports itself visible, or null when it does not say, as a busy page or one held
     * by a dialog does not answer. Only meaningful while its app is in front.
     */
    suspend fun showing(id: String): Boolean?

    /**
     * Whether Chrome has stopped [id]'s timers, as the check started with its last background input
     * shows, confirmed again now. False when nothing has checked.
     */
    suspend fun timersStalled(id: String): Boolean

    /**
     * Starts a check of [id]'s timers in the background, as background input does, unless one is
     * under way or has found them stopped, so a wait can fail on a tab no input has checked.
     */
    suspend fun watchTimers(id: String)

    suspend fun snapshot(
        id: String,
        options: BrowserSnapshotOptions = BrowserSnapshotOptions(),
    ): BrowserSnapshot

    /** Reads a target's content, or its outline. */
    suspend fun read(id: String, request: BrowserReadRequest): BrowserReadResult

    /** Finds text or links in a target's content, at the offsets [read] uses. */
    suspend fun find(id: String, request: BrowserFindRequest): BrowserFindResult

    suspend fun tap(id: String, request: BrowserTapRequest): ActionResult

    suspend fun swipe(id: String, request: BrowserSwipeRequest): ActionResult

    suspend fun gesture(id: String, request: BrowserGestureRequest): ActionResult

    suspend fun key(id: String, request: BrowserKeyRequest): ActionResult

    suspend fun text(id: String, request: BrowserTextRequest): ActionResult

    suspend fun evaluate(id: String, request: EvalRequest): EvalResult

    suspend fun console(id: String, request: ConsoleRequest): ConsoleResult

    /**
     * Starts or stops recording the JSON responses [id]'s page fetches, which data reads of it then
     * list. Stopping one that is not recording does nothing.
     */
    suspend fun capture(id: String, on: Boolean): BrowserCaptureResult
}
