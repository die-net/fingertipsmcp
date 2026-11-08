package com.fingertipsmcp.stream

import kotlinx.coroutines.channels.Channel

/**
 * One viewer's queue of packed video frames.
 *
 * Each delta frame builds on the one before it, so losing one smears the picture until the next key
 * frame. When the queue is full, the new frame is dropped and the viewer skips delta frames until a
 * key frame reaches it. The frames already queued still decode, so the viewer shows a picture that
 * stops briefly rather than a smeared one. While [waitsForKey], the stream asks the encoder for a
 * key frame through [KeyRequests].
 *
 * A new queue waits for a key frame too, since the viewer cannot decode a delta frame first.
 */
internal class FrameQueue(capacity: Int) {
    val frames = Channel<ByteArray>(capacity)

    @Volatile private var needsKey = true

    /** Whether delta frames are skipped until a key frame reaches this viewer. */
    val waitsForKey: Boolean
        get() = needsKey

    /** Queues a frame made by [packFrame] and says whether the viewer now waits for a key frame. */
    fun offer(frame: ByteArray): Offer {
        val type = frame[0].toInt()
        if (type == FRAME_DELTA && needsKey) return Offer.WAITING
        if (frames.trySend(frame).isFailure) {
            if (type != FRAME_CONFIG) needsKey = true
            return Offer.DROPPED
        }
        if (type == FRAME_KEY) needsKey = false
        return Offer.QUEUED
    }

    /** Empties the queue, which breaks the chain of delta frames until the next key frame. */
    fun drain() {
        var skipped = frames.tryReceive()
        while (skipped.isSuccess) skipped = frames.tryReceive()
        needsKey = true
    }

    enum class Offer {
        QUEUED,

        /** Skipped because the viewer waits for a key frame. */
        WAITING,

        /** The queue was full. A dropped key or delta frame leaves the viewer waiting. */
        DROPPED,
    }
}

/**
 * Paces key-frame requests while a viewer waits for one.
 *
 * The first request goes out at once. The key frame it brings can itself be dropped by a viewer
 * that is still behind, so requests repeat while any viewer waits, with the gap doubling from
 * [firstGapMs] to [maxGapMs]. Every key frame goes to every viewer, so a viewer that stopped
 * reading would otherwise cost the others a key frame twice a second. The gap resets once no viewer
 * waits.
 *
 * Not thread-safe; the stream uses it from the codec's callback thread.
 */
internal class KeyRequests(
    private val firstGapMs: Long = FIRST_GAP_MS,
    private val maxGapMs: Long = MAX_GAP_MS,
) {
    private var nextAtMs: Long? = null
    private var gapMs = firstGapMs

    /** Whether to request a key frame now, for a viewer that waits at [nowMs]. */
    fun due(nowMs: Long): Boolean {
        val next = nextAtMs
        if (next != null && nowMs < next) return false
        nextAtMs = nowMs + gapMs
        gapMs = (gapMs * 2).coerceAtMost(maxGapMs)
        return true
    }

    /** No viewer waits any more. */
    fun satisfied() {
        nextAtMs = null
        gapMs = firstGapMs
    }

    companion object {
        const val FIRST_GAP_MS = 500L
        const val MAX_GAP_MS = 8_000L
    }
}
