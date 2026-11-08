package com.fingertipsmcp.server

import com.fingertipsmcp.core.ClipImage
import com.fingertipsmcp.model.ApiException
import com.fingertipsmcp.model.ClipboardReadRequest
import com.fingertipsmcp.model.ClipboardWriteRequest
import com.fingertipsmcp.model.KeyRequest
import com.fingertipsmcp.model.OrientationRequest
import com.fingertipsmcp.model.PointerFrame
import com.fingertipsmcp.model.TextRequest
import com.fingertipsmcp.model.ViewerHover
import com.fingertipsmcp.model.ViewerPointer
import com.fingertipsmcp.model.ViewerWheel
import kotlinx.coroutines.channels.ReceiveChannel

/**
 * What the viewer's input socket and media sockets do on this product: a phone's touchscreen,
 * buttons, and clipboard ([PhoneViewerControls]), or a desktop browser's mouse and keyboard on the
 * tab the viewer shows. The socket checks the screen lease before each call.
 */
@Suppress("ComplexInterface") // One call for each thing the viewer page can ask of the screen.
interface ViewerControls {
    /** What the page's pointer drives, which the video header tells it. */
    val pointerKind: ViewerPointer

    /** Plays live contacts from [frames] until the channel closes, ending any still down then. */
    suspend fun contacts(frames: ReceiveChannel<PointerFrame>)

    /** Readies the screen for a viewer that took it, turning it on when [autoWake]. */
    suspend fun take(autoWake: Boolean)

    /** Readies the screen for a video or audio stream. */
    suspend fun wake()

    suspend fun key(request: KeyRequest): Any

    suspend fun text(request: TextRequest): Any

    suspend fun orientation(request: OrientationRequest): Any

    /** Puts [request]'s text on the clipboard and pastes it into the focused field. */
    suspend fun paste(request: ClipboardWriteRequest): Any

    /** Puts [bytes], an image, on the clipboard and pastes it into the focused field. */
    suspend fun pasteImage(bytes: ByteArray): Any

    /** Copies the selection, or reads the clipboard when nothing is selected. */
    suspend fun copy(): ViewerCopy

    /** The pointer moved with no button down. Only a mouse has this. */
    suspend fun hover(at: ViewerHover): Any = throw mouseOnly()

    /** The wheel turned. Only a mouse has this. */
    suspend fun wheel(turn: ViewerWheel): Any = throw mouseOnly()
}

/** What a copy found: [text], or an [image], and whether the clipboard [changed]. */
data class ViewerCopy(val text: String?, val image: ClipImage?, val changed: Boolean)

private fun mouseOnly() = ApiException(422, "unsupported", "This screen has no mouse.")

/** A phone's viewer: contacts are touches, and keys, text, and the clipboard are the phone's. */
class PhoneViewerControls(private val phone: PhoneServices) : ViewerControls {
    override val pointerKind: ViewerPointer = ViewerPointer.TOUCH

    override suspend fun contacts(frames: ReceiveChannel<PointerFrame>) {
        phone.input.pointer(frames)
    }

    override suspend fun take(autoWake: Boolean) {
        phone.power.prepareForAction(autoWake)
    }

    override suspend fun wake() {
        phone.power.wake()
    }

    override suspend fun key(request: KeyRequest): Any = phone.input.key(request)

    override suspend fun text(request: TextRequest): Any = phone.input.text(request)

    override suspend fun orientation(request: OrientationRequest): Any =
        phone.power.orientation(request)

    override suspend fun paste(request: ClipboardWriteRequest): Any =
        phone.clipboard.write(request.copy(paste = true))

    override suspend fun pasteImage(bytes: ByteArray): Any =
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
