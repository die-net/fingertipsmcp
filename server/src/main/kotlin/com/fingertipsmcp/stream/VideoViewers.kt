package com.fingertipsmcp.stream

import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.selects.select

/**
 * The video viewers of one stream, and what they share: each frame, each header, and the key-frame
 * requests that viewers who fell behind need. The phone's encoder and the desktop's tab capture
 * both deliver through it.
 *
 * Each viewer has a short [FrameQueue]. A viewer whose queue is full skips delta frames until a key
 * frame, and while any viewer waits, [requestKey] is called as [KeyRequests] paces it, so a viewer
 * that stopped reading does not turn every frame into a key frame for every viewer.
 *
 * [emitFrame] and [emitHeader] are called on one thread at a time, the one frames arrive on: the
 * codec's callback thread on the phone and the DevTools reader on the desktop. Viewers join and
 * leave from any thread, so the list is copied on write and never locked while frames go out.
 */
class VideoViewers(private val requestKey: () -> Unit, private val nowMs: () -> Long) {
    private val viewers = CopyOnWriteArrayList<VideoViewer>()
    private val keyRequests = KeyRequests()

    fun add(viewer: VideoViewer) {
        viewers += viewer
    }

    fun remove(viewer: VideoViewer) {
        viewers -= viewer
    }

    fun isEmpty(): Boolean = viewers.isEmpty()

    /** Queues a frame made by [packFrame] for every viewer, and asks for a key frame when due. */
    fun emitFrame(frame: ByteArray) {
        for (viewer in viewers) viewer.queue.offer(frame)
        if (viewers.none { it.queue.waitsForKey }) {
            keyRequests.satisfied()
        } else if (keyRequests.due(nowMs())) {
            requestKey()
        }
    }

    /**
     * A header for frames of a new size. Frames already queued are not shown at that size, so they
     * are dropped, and each viewer waits for a key frame.
     */
    fun emitHeader(json: String) {
        for (viewer in viewers) {
            viewer.queue.drain()
            viewer.headers.trySend(json)
        }
    }

    /** A header for frames of the same size, as when only the display's size changed. */
    fun sendHeader(json: String) {
        for (viewer in viewers) viewer.headers.trySend(json)
    }

    /** Ends every viewer's stream, with [reason] as its socket's close reason. */
    fun close(reason: String) {
        for (viewer in viewers) {
            viewer.failure = reason
            viewer.close()
        }
    }
}

/**
 * One video viewer's frames and headers. They travel on separate channels so a full frame queue
 * cannot drop a header, and a header that waits is always sent before the frames after it.
 */
class VideoViewer(capacity: Int = CAPACITY) {
    val queue = FrameQueue(capacity)
    val headers = Channel<String>(Channel.CONFLATED)

    /** Why the stream closed this viewer, sent as the close reason. */
    @Volatile var failure: String? = null

    fun close() {
        queue.frames.close()
        headers.close()
    }

    /**
     * Sends headers and frames on [session] until the stream closes this viewer or the client
     * leaves. A viewer the stream closed gets its [failure] as the socket's close reason.
     */
    suspend fun send(session: DefaultWebSocketServerSession) {
        sendUntilClientLeaves(session) {
            while (true) {
                // select is biased to the first clause, so a size-change header always goes out
                // before the frames that follow it.
                val out: Frame =
                    select {
                        headers.onReceiveCatching { it.getOrNull()?.let(Frame::Text) }
                        queue.frames.onReceiveCatching { result ->
                            result.getOrNull()?.let { Frame.Binary(true, it) }
                        }
                    } ?: break
                session.send(out)
            }
        }
        failure?.let { session.close(CloseReason(CloseReason.Codes.INTERNAL_ERROR, it)) }
    }

    private companion object {
        const val CAPACITY = 4
    }
}
