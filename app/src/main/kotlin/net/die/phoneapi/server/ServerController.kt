package net.die.phoneapi.server

import android.util.Log
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Runs the abstract-socket API while at least one holder wants it. [ListenerService] is that
 * holder, started by the client over adb, which starts it again on each `phoneapi helper`.
 *
 * Whether the API is listening is [start]'s to know, not a flag here: each acquire asks it to
 * start, which does nothing when it already listens, so a start that failed, as when the name was
 * still bound, is tried again at the next acquire rather than remembered as running.
 *
 * Starts and stops run in [scope], since both are too slow for the main thread that calls acquire
 * and release. They run one at a time, and each checks the holders when its turn comes, so the last
 * acquire or release decides the outcome whatever order they ran in, and a start never overlaps the
 * stop that is freeing the name.
 */
class ServerController(
    private val scope: CoroutineScope,
    /** Opens the listener, or does nothing when it is open. Throws when it cannot bind. */
    private val start: () -> Unit,
    /**
     * Closes the listener, or does nothing when it is closed. Returns once its name is free, and
     * throws [IOException] when the name may still be bound.
     */
    private val stop: suspend () -> Unit,
) {
    private val holders = mutableSetOf<String>()
    private val turn = Mutex()

    @Synchronized
    fun acquire(holder: String) {
        holders += holder
        scope.launch { startIfHeld() }
    }

    @Synchronized
    fun release(holder: String) {
        holders -= holder
        scope.launch { stopUnlessHeld() }
    }

    @Synchronized private fun held(): Boolean = holders.isNotEmpty()

    private suspend fun startIfHeld() = turn.withLock {
        if (!held()) return@withLock
        runCatching(start).onFailure { Log.e(TAG, "Failed to open the abstract socket", it) }
    }

    private suspend fun stopUnlessHeld() = turn.withLock {
        if (held()) return@withLock
        try {
            stop()
        } catch (e: IOException) {
            Log.e(TAG, "Failed to close the abstract socket", e)
        }
    }

    private companion object {
        const val TAG = "PhoneApiServer"
    }
}
