package com.fingertipsmcp.core

import com.fingertipsmcp.model.ActionResult
import com.fingertipsmcp.model.AppInfo
import com.fingertipsmcp.model.ClipboardContent
import com.fingertipsmcp.model.ClipboardReadRequest
import com.fingertipsmcp.model.ClipboardWriteRequest
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
