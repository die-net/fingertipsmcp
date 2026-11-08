package com.fingertipsmcp.input

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import com.fingertipsmcp.core.ClipImage
import com.fingertipsmcp.core.ClipboardService
import com.fingertipsmcp.core.InputService
import com.fingertipsmcp.core.settled
import com.fingertipsmcp.helper.IHelper
import com.fingertipsmcp.helperclient.HelperConnection
import com.fingertipsmcp.model.ActionResult
import com.fingertipsmcp.model.ApiException
import com.fingertipsmcp.model.ClipboardContent
import com.fingertipsmcp.model.ClipboardReadRequest
import com.fingertipsmcp.model.ClipboardWriteRequest
import com.fingertipsmcp.model.KeyRequest
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.withContext

/**
 * The clipboard through the helper, which reads it as the shell user: this app is never in front,
 * and Android 10 and later let only the app in front or the keyboard read it. Copy and paste press
 * KEYCODE_COPY and KEYCODE_PASTE, which text fields and Chrome handle. An image is put on the
 * clipboard by this app rather than the helper, since the clip's URI is this app's [ClipProvider]
 * and only its owner may share it.
 *
 * [own] remembers the last clip it wrote or copied, so [clearOwn] can tell that clip from one
 * someone copied since.
 */
class ClipboardServiceImpl(
    private val context: Context,
    private val helper: HelperConnection,
    private val input: InputService,
    private val io: CoroutineContext,
) : ClipboardService {
    private val own = OwnClip()

    override suspend fun read(request: ClipboardReadRequest): ClipboardContent {
        if (!request.copy) return content(call { primaryClip() })
        val before = call { primaryClipTimestamp() }
        input.key(KeyRequest("KEYCODE_COPY", autoWake = request.autoWake))
        val copied =
            awaitCopied(
                before,
                COPY_WAIT_MS,
                stamp = { call { primaryClipTimestamp() } },
                read = { call { primaryClip() } },
                filled = { clip ->
                    (0 until clip.itemCount).any {
                        val item = clip.getItemAt(it)
                        !item.text.isNullOrEmpty() || item.uri != null
                    }
                },
            )
        if (copied.changed) mark()
        val clip = if (copied.changed) copied.clip else call { primaryClip() }
        return content(clip).copy(changed = copied.changed)
    }

    override suspend fun write(request: ClipboardWriteRequest): ActionResult {
        if (request.text.length > MAX_TEXT) {
            throw ApiException.badRequest("text is longer than $MAX_TEXT characters")
        }
        // No label, as when a person copies from a text field, so the clip looks like theirs.
        call { setPrimaryClip(ClipData.newPlainText(null, request.text)) }
        mark()
        return pasted(request.paste, request.autoWake)
    }

    override suspend fun image(): ClipImage? {
        val clip = call { primaryClip() } ?: return null
        val description = clip.description
        val declared =
            (0 until description.mimeTypeCount)
                .map { description.getMimeType(it) }
                .firstOrNull { it.startsWith("image/") } ?: return null
        val uri =
            (0 until clip.itemCount).firstNotNullOfOrNull { clip.getItemAt(it).uri } ?: return null
        val bytes = readContent(uri)
        if (bytes.isEmpty()) {
            throw ApiException(409, "clipboard_unreadable", "The helper could not read the image")
        }
        return ClipImage(bytes, ImageType.sniff(bytes)?.mimeType ?: declared)
    }

    override suspend fun writeImage(bytes: ByteArray, paste: Boolean): ActionResult {
        if (bytes.size > ClipImage.MAX_BYTES) {
            throw ApiException(413, "too_large", "The image is over ${ClipImage.MAX_BYTES} bytes")
        }
        val type =
            ImageType.sniff(bytes)
                ?: throw ApiException.badRequest("The image is not PNG, JPEG, GIF, or WebP")
        withContext(io) {
            val dir = ClipProvider.dir(context)
            dir.mkdirs()
            // Only the newest image stays readable, under a name no earlier clip used.
            dir.listFiles()?.forEach { it.delete() }
            val name = "${UUID.randomUUID().toString().replace("-", "")}.${type.extension}"
            File(dir, name).writeBytes(bytes)
            val uri =
                Uri.Builder()
                    .scheme("content")
                    .authority(ClipProvider.authority(context))
                    .appendPath(name)
                    .build()
            val clip = ClipData.newUri(context.contentResolver, null, uri)
            context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
        }
        // The image goes on through this app, but only the helper reads the clip's timestamp.
        if (helper.isRunning) mark() else own.forget()
        return pasted(paste, autoWake = true)
    }

    override suspend fun clearOwn(): ActionResult {
        // Without a clip of FingertipsMCP's there is nothing to ask the helper, which may be down.
        if (!own.held) return ActionResult(ok = true, message = "The clip is not FingertipsMCP's")
        if (!own.take(call { primaryClipTimestamp() })) {
            return ActionResult(ok = true, message = "Someone copied something since")
        }
        call { clearPrimaryClip() }
        return ActionResult(ok = true, backend = BACKEND)
    }

    override suspend fun clear(): ActionResult {
        // An empty clipboard is left alone, so keyboards and apps see no change.
        if (call { primaryClipTimestamp() } < 0) return ActionResult(ok = true, backend = BACKEND)
        call { clearPrimaryClip() }
        own.forget()
        return ActionResult(ok = true, backend = BACKEND)
    }

    /** Remembers the clip FingertipsMCP just put on the clipboard, by its timestamp. */
    private suspend fun mark() {
        own.put(call { primaryClipTimestamp() })
    }

    private suspend fun pasted(paste: Boolean, autoWake: Boolean): ActionResult {
        if (!paste) return ActionResult(ok = true, backend = BACKEND)
        return input.key(KeyRequest("KEYCODE_PASTE", autoWake = autoWake)).copy(backend = BACKEND)
    }

    /**
     * Reads [uri] through the helper. The pipe is opened and read in one block, so a cancel between
     * the two can't leave it open.
     */
    private suspend fun readContent(uri: Uri): ByteArray {
        val proxy = helper.require()
        return withContext(io) {
            try {
                val pipe = proxy.readContent(uri.toString())
                ParcelFileDescriptor.AutoCloseInputStream(pipe).use { input ->
                    val out = ByteArrayOutputStream()
                    val buffer = ByteArray(BUFFER_BYTES)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        if (out.size() > ClipImage.MAX_BYTES) {
                            throw ApiException(
                                413,
                                "too_large",
                                "The clipboard's image is over ${ClipImage.MAX_BYTES} bytes",
                            )
                        }
                    }
                    out.toByteArray()
                }
            } catch (e: RemoteException) {
                throw ApiException.helperDropped(e)
            } catch (e: IOException) {
                throw ApiException.helperDropped(e)
            }
        }
    }

    private suspend fun <T> call(block: IHelper.() -> T): T {
        val proxy = helper.require()
        return try {
            withContext(io) { proxy.block() }
        } catch (e: RemoteException) {
            throw ApiException.helperDropped(e)
        }
    }

    private fun content(clip: ClipData?): ClipboardContent {
        if (clip == null) return ClipboardContent()
        val description = clip.description
        val texts = (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).text?.toString() }
        return ClipboardContent(
            text = texts.takeIf { it.isNotEmpty() }?.joinToString("\n"),
            mimeTypes = (0 until description.mimeTypeCount).map { description.getMimeType(it) },
            sensitive = description.extras?.getBoolean(EXTRA_IS_SENSITIVE) == true,
        )
    }

    private companion object {
        const val BACKEND = "clipboard"
        /** Binder calls carry at most about 1 MB, and text is two bytes a character. */
        const val MAX_TEXT = 100_000
        /** How long an app gets to put its selection on the clipboard after KEYCODE_COPY. */
        const val COPY_WAIT_MS = 1_000L
        const val BUFFER_BYTES = 64 * 1024
        /** ClipDescription.EXTRA_IS_SENSITIVE, which Android 13 named; apps set it earlier. */
        const val EXTRA_IS_SENSITIVE = "android.content.extra.IS_SENSITIVE"
    }
}

/** What a copy did: whether a new clip went on the clipboard, and that clip. */
internal data class Copied<C>(val changed: Boolean, val clip: C?)

/**
 * Waits up to [timeoutMs] for a clip newer than the one with timestamp [before] that is [filled]. A
 * copy gives the clip a new timestamp, which [stamp] reads; the change count is no use, since on
 * Android 16 one write moves it twice, a second apart. On Android 16, Chrome's first copy after it
 * starts can read back empty right after it lands and hold the text 100 ms later, so an empty new
 * clip is read again until the deadline.
 */
internal suspend fun <C : Any> awaitCopied(
    before: Long,
    timeoutMs: Long,
    stamp: suspend () -> Long,
    read: suspend () -> C?,
    filled: (C) -> Boolean,
): Copied<C> {
    var changed = false
    var clip: C? = null
    settled(timeoutMs) {
        val now = stamp()
        changed = now >= 0 && now != before
        clip = if (changed) read() else null
        clip?.let(filled) == true
    }
    return Copied(changed, clip)
}
