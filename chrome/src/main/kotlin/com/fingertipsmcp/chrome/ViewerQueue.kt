package com.fingertipsmcp.chrome

import io.ktor.websocket.Frame
import kotlinx.coroutines.channels.Channel

/**
 * One viewer socket's frames, waiting to be sent. A viewer that falls behind loses a frame, so it
 * skips delta frames from then on, which would decode against the frame it lost, until the next key
 * frame, and asks for one with [wantsKey], again if that key frame is lost too. A header that did
 * not fit waits, and goes before that key frame, since frames after it are of the size it gives.
 */
internal class ViewerQueue(capacity: Int = CAPACITY, private val wantsKey: () -> Unit) {
    val out = Channel<Frame>(capacity)
    private val gate = Any()
    private var behind = false
    private var header: String? = null

    /** Queues a frame in the sockets' layout, whose first byte says whether it is a delta. */
    fun offer(bytes: ByteArray) {
        synchronized(gate) { queue(bytes) }
    }

    /** Queues a header, which says the size of the frames after it. */
    fun text(json: String) {
        synchronized(gate) {
            if (out.trySend(Frame.Text(json)).isFailure) {
                header = json
                fellBehind()
            }
        }
    }

    private fun queue(bytes: ByteArray) {
        val key = bytes[0].toInt() != DELTA
        if (behind && !key) return
        val waiting = header
        if (waiting != null) {
            if (out.trySend(Frame.Text(waiting)).isFailure) return
            header = null
        }
        if (out.trySend(Frame.Binary(true, bytes)).isSuccess) {
            behind = false
            return
        }
        // A lost key frame was the one a viewer behind waited for, so it needs another.
        if (key) behind = false
        fellBehind()
    }

    private fun fellBehind() {
        if (behind) return
        behind = true
        wantsKey()
    }

    private companion object {
        const val DELTA = 3
        const val CAPACITY = 16
    }
}
