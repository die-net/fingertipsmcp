package net.die.phoneapi.core

import kotlinx.coroutines.channels.ReceiveChannel
import net.die.phoneapi.model.ActionResult
import net.die.phoneapi.model.ApiException
import net.die.phoneapi.model.AppInfo
import net.die.phoneapi.model.BrowserCaptureResult
import net.die.phoneapi.model.BrowserFindRequest
import net.die.phoneapi.model.BrowserFindResult
import net.die.phoneapi.model.BrowserGestureRequest
import net.die.phoneapi.model.BrowserKeyRequest
import net.die.phoneapi.model.BrowserReadRequest
import net.die.phoneapi.model.BrowserReadResult
import net.die.phoneapi.model.BrowserSnapshot
import net.die.phoneapi.model.BrowserSnapshotOptions
import net.die.phoneapi.model.BrowserSwipeRequest
import net.die.phoneapi.model.BrowserTapRequest
import net.die.phoneapi.model.BrowserTarget
import net.die.phoneapi.model.BrowserTextRequest
import net.die.phoneapi.model.ClipboardContent
import net.die.phoneapi.model.ClipboardReadRequest
import net.die.phoneapi.model.ClipboardWriteRequest
import net.die.phoneapi.model.ConsoleRequest
import net.die.phoneapi.model.ConsoleResult
import net.die.phoneapi.model.EvalRequest
import net.die.phoneapi.model.EvalResult
import net.die.phoneapi.model.FindRequest
import net.die.phoneapi.model.FindResult
import net.die.phoneapi.model.GestureRequest
import net.die.phoneapi.model.ImeShowRequest
import net.die.phoneapi.model.IntentRequest
import net.die.phoneapi.model.KeyRequest
import net.die.phoneapi.model.LaunchRequest
import net.die.phoneapi.model.NodeActionRequest
import net.die.phoneapi.model.OrientationRequest
import net.die.phoneapi.model.PointerFrame
import net.die.phoneapi.model.Scope
import net.die.phoneapi.model.SnapshotOptions
import net.die.phoneapi.model.SwipeRequest
import net.die.phoneapi.model.TapRequest
import net.die.phoneapi.model.TextRequest
import net.die.phoneapi.model.UiSnapshot
import net.die.phoneapi.model.UnlockRequest
import net.die.phoneapi.model.WaitRequest
import net.die.phoneapi.model.WaitResult

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
     * For cleanup: empties the clipboard if its clip is one PhoneAPI wrote or copied and nothing
     * has replaced it since. A clip the phone's owner or an app copied is left alone.
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
     * Opens a tab on [url]. With [capture], the tab records the JSON responses its page fetches
     * from the start of its load, as [capture] does. With [incognito], the tab is in Chrome's
     * incognito profile, which brings Chrome forward, so the caller holds the screen.
     */
    suspend fun openTab(
        url: String,
        capture: Boolean = false,
        incognito: Boolean = false,
    ): BrowserTarget

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
    suspend fun incognitoTabs(): Set<String> = emptySet()

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
    suspend fun timersStalled(id: String): Boolean = false

    /**
     * Starts a check of [id]'s timers in the background, as background input does, unless one is
     * under way or has found them stopped, so a wait can fail on a tab no input has checked.
     */
    suspend fun watchTimers(id: String) {}

    suspend fun snapshot(
        id: String,
        options: BrowserSnapshotOptions = BrowserSnapshotOptions(),
    ): BrowserSnapshot

    /** Reads a target's content, or its outline. */
    suspend fun read(id: String, request: BrowserReadRequest): BrowserReadResult

    /**
     * Whether Chrome saved the PDF at [url] rather than showing it, as far as this remembers. Such
     * a URL goes to [loadPdf], since opening it would save another copy.
     */
    fun savedPdf(url: String): Boolean = false

    /**
     * Puts the PDF at [url] in the tab [id], without Chrome showing or saving it, for [read] and
     * [find] to read. False when [url] is not a PDF.
     */
    suspend fun loadPdf(id: String, url: String): Boolean = false

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
    suspend fun capture(id: String, on: Boolean): BrowserCaptureResult =
        throw ApiException.unavailable(
            "browser_unavailable",
            "Recording responses is not available",
        )
}
