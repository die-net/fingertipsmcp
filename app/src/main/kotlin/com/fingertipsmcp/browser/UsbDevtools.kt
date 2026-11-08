package com.fingertipsmcp.browser

import android.os.ParcelFileDescriptor
import android.os.RemoteException
import com.fingertipsmcp.model.ApiException
import java.io.FileInputStream
import java.io.FileOutputStream
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * Android 10 DevTools. The helper connects to [USB_DEVTOOLS_SOCKET], which exists only after the
 * computer has reversed that abstract name onto an `adb forward` of Chrome's socket.
 */
internal class UsbDevtools(
    private val openSocket: () -> ParcelFileDescriptor,
    private val io: CoroutineDispatcher,
) {
    @Suppress("MissingUseCall") // The returned socket closes the descriptor.
    suspend fun open(name: String, answerTimeoutMs: Long): DevtoolsSocket =
        withContext(io) {
            val fd =
                try {
                    openSocket()
                } catch (e: RemoteException) {
                    throw ApiException.helperDropped(e)
                } catch (e: IllegalStateException) {
                    throw tunnelMissing(name, e)
                }
            val descriptor = fd.fileDescriptor
            AdbDevtoolsSocket(
                inputStream = FileInputStream(descriptor),
                outputStream = FileOutputStream(descriptor),
                release = { fd.close() },
                answerTimeoutMs = answerTimeoutMs,
                dispatcher = io,
            )
        }

    private fun tunnelMissing(name: String, error: IllegalStateException): ApiException =
        ApiException(503, "cdp_error", usbTunnelMessage(name), cause = error)
}
