package com.fingertipsmcp.core

import com.fingertipsmcp.model.ActionResult
import com.fingertipsmcp.model.AppInfo
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
import com.fingertipsmcp.model.ClipboardContent
import com.fingertipsmcp.model.ClipboardReadRequest
import com.fingertipsmcp.model.ClipboardWriteRequest
import com.fingertipsmcp.model.ConsoleRequest
import com.fingertipsmcp.model.ConsoleResult
import com.fingertipsmcp.model.EvalRequest
import com.fingertipsmcp.model.EvalResult
import com.fingertipsmcp.model.FindRequest
import com.fingertipsmcp.model.FindResult
import com.fingertipsmcp.model.GestureRequest
import com.fingertipsmcp.model.ImeShowRequest
import com.fingertipsmcp.model.IntentRequest
import com.fingertipsmcp.model.KeyRequest
import com.fingertipsmcp.model.LaunchRequest
import com.fingertipsmcp.model.NodeActionRequest
import com.fingertipsmcp.model.OrientationRequest
import com.fingertipsmcp.model.PointerFrame
import com.fingertipsmcp.model.Scope
import com.fingertipsmcp.model.SnapshotOptions
import com.fingertipsmcp.model.SwipeRequest
import com.fingertipsmcp.model.TapRequest
import com.fingertipsmcp.model.TextRequest
import com.fingertipsmcp.model.UiSnapshot
import com.fingertipsmcp.model.UnlockRequest
import com.fingertipsmcp.model.WaitRequest
import com.fingertipsmcp.model.WaitResult
import kotlinx.coroutines.channels.ReceiveChannel

// Shared by the REST routes and the MCP tools. Implementations throw ApiException for
// client-visible failures.

interface UiService {
    suspend fun snapshot(options: SnapshotOptions): UiSnapshot

    suspend fun find(request: FindRequest): FindResult

    suspend fun act(ref: String, request: NodeActionRequest): ActionResult
}

interface InputService {
    suspend fun tap(request: TapRequest): ActionResult

    suspend fun swipe(request: SwipeRequest): ActionResult

    suspend fun gesture(request: GestureRequest): ActionResult

    /**
     * Live contacts from `WS /v1/input/pointer`. Reads [frames] until the channel closes. A contact
     * that is still down then is cancelled, which releases the touch.
     */
    suspend fun pointer(frames: ReceiveChannel<PointerFrame>)

    suspend fun key(request: KeyRequest): ActionResult

    suspend fun text(request: TextRequest): ActionResult

    suspend fun hideIme(): ActionResult

    suspend fun showIme(request: ImeShowRequest): ActionResult
}

/** The primary clip, through the shell helper. */
interface ClipboardService {
    /** With copy, presses KEYCODE_COPY first and waits for the clipboard to change. */
    suspend fun read(request: ClipboardReadRequest): ClipboardContent

    /** Replaces the clip with plain text, then presses KEYCODE_PASTE when asked. */
    suspend fun write(request: ClipboardWriteRequest): ActionResult

    /** The clip's image, or null when it holds none. */
    suspend fun image(): ClipImage?

    /** Replaces the clip with a PNG, JPEG, GIF, or WebP, then presses KEYCODE_PASTE when asked. */
    suspend fun writeImage(bytes: ByteArray, paste: Boolean): ActionResult

    /**
     * For cleanup: empties the clipboard if its clip is one FingertipsMCP wrote or copied and
     * nothing has replaced it since. A clip the phone's owner or an app copied is left alone.
     */
    suspend fun clearOwn(): ActionResult

    /**
     * Empties the clipboard, whoever copied the clip, so a session that just got the screen cannot
     * read what a person or an earlier holder left there.
     */
    suspend fun clear(): ActionResult
}

/** An image from the clipboard, and its MIME type. */
@Suppress("UseDataClass") // A data class would compare the bytes by reference.
class ClipImage(val bytes: ByteArray, val mimeType: String) {
    companion object {
        /** The largest image the clipboard calls move in either direction. */
        const val MAX_BYTES: Int = 20 * 1024 * 1024
    }
}

interface PowerService {
    suspend fun wake(): ActionResult

    suspend fun unlock(request: UnlockRequest): ActionResult

    suspend fun lock(): ActionResult

    /** Turns auto-rotate on, or locks the screen to a rotation. Needs the shell helper. */
    suspend fun orientation(request: OrientationRequest): ActionResult

    /**
     * Called before every action. When [autoWake] is set and the screen is off, turns it on and
     * returns whether that happened, or fails with `wake_failed` when it stays off. Extends the
     * awake lease. Leaves the keyguard up.
     */
    suspend fun prepareForAction(autoWake: Boolean): Boolean
}

interface AppsService {
    suspend fun list(launchableOnly: Boolean): List<AppInfo>

    suspend fun launch(packageName: String, request: LaunchRequest): ActionResult

    suspend fun stop(packageName: String): ActionResult

    suspend fun clear(packageName: String): ActionResult

    suspend fun intent(request: IntentRequest): IntentStart
}

/** What starting an intent did: the [result] for the caller, and the app it went to, if known. */
data class IntentStart(val result: ActionResult, val packageName: String?)

interface WaitService {
    /**
     * Blocks until [request] matches. A `browser.*` condition is 403 when [scopes] lacks
     * [Scope.BROWSER], before a DevTools session is opened.
     */
    suspend fun wait(request: WaitRequest, scopes: Set<Scope>): WaitResult
}

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
     * Chrome's incognito profile, which brings Chrome forward, so the caller holds the screen.
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
