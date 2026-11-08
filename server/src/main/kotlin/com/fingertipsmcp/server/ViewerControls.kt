package com.fingertipsmcp.server

import com.fingertipsmcp.core.ClipImage
import com.fingertipsmcp.model.ActionResult
import com.fingertipsmcp.model.ClipboardReadRequest
import com.fingertipsmcp.model.ClipboardWriteRequest
import com.fingertipsmcp.model.KeyRequest
import com.fingertipsmcp.model.OrientationRequest
import com.fingertipsmcp.model.PointerFrame
import com.fingertipsmcp.model.TextRequest
import com.fingertipsmcp.model.ViewerHover
import com.fingertipsmcp.model.ViewerWheel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * What the viewer's input socket and media sockets do on this product. Every product plays
 * contacts, keys, text, paste, and copy. A phone's touchscreen, buttons, and clipboard are
 * [PhoneControls]; a desktop browser's mouse and keyboard on the tab the viewer shows are
 * [MouseControls]. The socket checks the screen lease before each call, and refuses an operation
 * the product's controls do not have.
 */
interface ViewerControls {
    /** Plays live contacts from [frames] until the channel closes, ending any still down then. */
    suspend fun contacts(frames: ReceiveChannel<PointerFrame>)

    suspend fun key(request: KeyRequest): ActionResult

    suspend fun text(request: TextRequest): ActionResult

    /** Puts [request]'s text on the clipboard and pastes it into the focused field. */
    suspend fun paste(request: ClipboardWriteRequest): ActionResult

    /** Copies the selection, or reads the clipboard when nothing is selected. */
    suspend fun copy(): ViewerCopy

    /**
     * Why the picture differs from what the screen shows, or null when it doesn't. The input socket
     * sends each change to its page as a `notice`. Only a desktop browser has one.
     */
    val notice: StateFlow<String?>
        get() = NO_NOTICE
}

private val NO_NOTICE: StateFlow<String?> = MutableStateFlow(null)

/** A mouse's controls: the pointer moving with no button down, and the wheel. */
interface MouseControls : ViewerControls {
    /** The pointer moved with no button down. */
    suspend fun hover(at: ViewerHover)

    /** The wheel turned. */
    suspend fun wheel(turn: ViewerWheel)
}

/** A phone's controls: a screen to ready, an orientation, and a clipboard that holds images. */
interface PhoneControls : ViewerControls {
    /** Readies the screen for a viewer that took it, turning it on when [autoWake]. */
    suspend fun take(autoWake: Boolean)

    /** Readies the screen for a video or audio stream. */
    suspend fun wake()

    suspend fun orientation(request: OrientationRequest): ActionResult

    /** Puts [bytes], an image, on the clipboard and pastes it into the focused field. */
    suspend fun pasteImage(bytes: ByteArray): ActionResult
}

/** What a copy found: [text], or an [image], and whether the clipboard [changed]. */
data class ViewerCopy(val text: String?, val image: ClipImage?, val changed: Boolean)

/** A phone's viewer: contacts are touches, and keys, text, and the clipboard are the phone's. */
class PhoneViewerControls(private val phone: PhoneServices) : PhoneControls {
    override suspend fun contacts(frames: ReceiveChannel<PointerFrame>) {
        phone.input.pointer(frames)
    }

    override suspend fun take(autoWake: Boolean) {
        phone.power.prepareForAction(autoWake)
    }

    override suspend fun wake() {
        phone.power.wake()
    }

    override suspend fun key(request: KeyRequest): ActionResult = phone.input.key(request)

    override suspend fun text(request: TextRequest): ActionResult = phone.input.text(request)

    override suspend fun orientation(request: OrientationRequest): ActionResult =
        phone.power.orientation(request)

    override suspend fun paste(request: ClipboardWriteRequest): ActionResult =
        phone.clipboard.write(request.copy(paste = true))

    override suspend fun pasteImage(bytes: ByteArray): ActionResult =
        phone.clipboard.writeImage(bytes, paste = true)

    override suspend fun copy(): ViewerCopy {
        val clip = phone.clipboard.read(ClipboardReadRequest(copy = true))
        val image =
            if (clip.text == null && clip.mimeTypes.any { it.startsWith("image/") }) {
                phone.clipboard.image()
            } else {
                null
            }
        return ViewerCopy(clip.text, image, changed = clip.changed == true)
    }
}
