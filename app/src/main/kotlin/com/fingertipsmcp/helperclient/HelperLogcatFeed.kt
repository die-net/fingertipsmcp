package com.fingertipsmcp.helperclient

import android.os.ParcelFileDescriptor
import android.os.RemoteException
import android.util.Log
import com.fingertipsmcp.core.EventBus
import com.fingertipsmcp.core.LogcatFilter
import com.fingertipsmcp.core.argv
import com.fingertipsmcp.model.Event
import com.fingertipsmcp.model.EventTypes
import com.fingertipsmcp.server.LogcatFeed
import java.io.IOException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One `logcat` process per collector. Closing the pipe when the flow is cancelled unblocks the read
 * on the IO dispatcher. Without a helper the flow completes empty.
 */
internal class HelperLogcatFeed(
    private val helper: HelperConnection,
    private val bus: EventBus,
    private val ioDispatcher: CoroutineDispatcher,
) : LogcatFeed {
    override fun lines(filter: LogcatFilter): Flow<Event> = callbackFlow {
        val outgoing = this
        open(filter)?.use { pipe ->
            val reader =
                launch(ioDispatcher) {
                    try {
                        read(pipe) { outgoing.send(it) }
                    } catch (e: IOException) {
                        Log.i(TAG, "logcat ended", e)
                    }
                }
            awaitClose { reader.cancel() }
        } ?: close()
    }

    private suspend fun open(filter: LogcatFilter): ParcelFileDescriptor? =
        withContext(ioDispatcher) {
            val remote = helper.getOrNull() ?: return@withContext null
            try {
                remote.logcat(filter.argv())
            } catch (e: RemoteException) {
                Log.w(TAG, "logcat unavailable", e)
                null
            }
        }

    private suspend fun read(pipe: ParcelFileDescriptor, emit: suspend (Event) -> Unit) {
        ParcelFileDescriptor.AutoCloseInputStream(pipe).bufferedReader().use { reader ->
            while (currentCoroutineContext().isActive) {
                val line = reader.readLine() ?: return
                val parsed = ThreadtimeLog.parse(line) ?: continue
                emit(bus.next(EventTypes.LOGCAT, parsed))
            }
        }
    }

    private companion object {
        const val TAG = "FingertipsLogcat"
    }
}
