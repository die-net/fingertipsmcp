package net.die.phoneapi.server

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Runs the abstract-socket API while at least one holder wants it. [ListenerService] is that
 * holder, started by the client over adb, which starts it again on each `phoneapi helper`.
 *
 * Whether the API is listening is [start]'s to know, not a flag here: each acquire asks it to
 * start, which does nothing when it already listens, so a start that failed, as when the name was
 * still bound, is tried again at the next acquire rather than remembered as running.
 */
class ServerController(
    private val scope: CoroutineScope,
    /** Opens the listener, or does nothing when it is open. Throws when it cannot bind. */
    private val start: () -> Unit,
    /** Closes the listener, and returns once its name is free. */
    private val stop: () -> Unit,
) {
    private val holders = mutableSetOf<String>()

    @Synchronized
    fun acquire(holder: String) {
        holders += holder
        // Binding and building the server is too slow for the main thread, which calls this.
        scope.launch { startIfHeld() }
    }

    @Synchronized
    fun release(holder: String) {
        holders -= holder
        if (holders.isEmpty()) stop()
    }

    /** Starts the listener unless every holder let go meanwhile. */
    @Synchronized
    private fun startIfHeld() {
        if (holders.isEmpty()) return
        runCatching(start).onFailure { Log.e(TAG, "Failed to open the abstract socket", it) }
    }

    private companion object {
        const val TAG = "PhoneApiServer"
    }
}
