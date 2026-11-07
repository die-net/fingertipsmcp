package net.die.phoneapi.server

import android.util.Log
import java.io.Closeable
import java.io.IOException
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Takes connections from a blocking server socket on a thread of its own, and hands each to [serve]
 * in [scope].
 *
 * Closing a socket does not wake a thread blocked in accept, and that blocked call keeps the socket
 * and its name alive. So [stop] uses [wake] to end the blocked call, waits for the thread to leave,
 * and only then uses [close], which is what frees the name.
 */
internal class AcceptLoop<T : Closeable>(
    private val threadName: String,
    private val scope: CoroutineScope,
    /** Blocks for the next connection, and throws once [wake] has run and none is left. */
    private val accept: () -> T,
    /** Makes a blocked [accept] return. Throws when it can't. */
    private val wake: () -> Unit,
    /** Closes the server socket. */
    private val close: () -> Unit,
    private val serve: suspend CoroutineScope.(T) -> Unit,
) {
    private val exited = Job()
    @Volatile private var stopped = false

    fun start() {
        Thread(::run, threadName).start()
    }

    /**
     * Ends the loop and closes the socket. When this returns normally the thread has left accept
     * and the socket's name is free.
     *
     * @throws IOException when the thread can't be woken or is still in accept at the deadline. The
     *   socket is closed anyway, so the name goes as soon as the blocked call returns.
     */
    suspend fun stop() {
        stopped = true
        try {
            wake()
            withTimeoutOrNull(STOP_DEADLINE) { exited.join() }
                ?: throw IOException("The accept thread did not stop in $STOP_DEADLINE")
        } finally {
            close()
        }
    }

    private fun run() {
        try {
            while (true) {
                val client =
                    try {
                        accept()
                    } catch (e: IOException) {
                        if (!stopped) Log.e(TAG, "Abstract accept failed", e)
                        return
                    }
                if (stopped) {
                    // A failed close leaves nothing to do, and the thread still has to exit.
                    runCatching { client.close() }
                    return
                }
                // Undispatched, so the block runs and closes the client even when the scope is
                // cancelled first. A dispatched launch would drop it unclosed and unanswered.
                scope.launch(start = CoroutineStart.UNDISPATCHED) { client.use { serve(it) } }
            }
        } finally {
            exited.complete()
        }
    }

    private companion object {
        const val TAG = "PhoneApiServer"

        /** Far past the microseconds a woken accept takes, so reaching it means something broke. */
        val STOP_DEADLINE = 5.seconds
    }
}
