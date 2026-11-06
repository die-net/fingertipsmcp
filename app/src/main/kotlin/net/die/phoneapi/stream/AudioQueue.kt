package net.die.phoneapi.stream

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.selects.select

/**
 * One audio viewer's queue of packed frames. When a viewer falls behind, the oldest packets go,
 * which costs a moment of sound. The codec config never goes that way: a decoder can play nothing
 * without it, so it waits apart and is sent before any packet.
 */
internal class AudioQueue(capacity: Int) {
    private val config = Channel<ByteArray>(Channel.CONFLATED)
    private val packets = Channel<ByteArray>(capacity, BufferOverflow.DROP_OLDEST)

    /** Queues a frame made by [packFrame]. */
    fun offer(frame: ByteArray) {
        if (frame[0].toInt() == FRAME_CONFIG) config.trySend(frame) else packets.trySend(frame)
    }

    /** The next frame to send, the config first, or null once the queue is closed. */
    suspend fun next(): ByteArray? = select {
        // select is biased to the first clause, so a waiting config goes before any packet.
        config.onReceiveCatching { it.getOrNull() }
        packets.onReceiveCatching { it.getOrNull() }
    }

    fun close() {
        config.close()
        packets.close()
    }
}
