package net.die.phoneapi.stream

import java.io.ByteArrayOutputStream

/**
 * Joins Opus packets into longer ones, so the audio socket sends fewer messages.
 *
 * Android's Opus encoder puts one 20 ms frame in each packet and takes no setting for longer ones.
 * One Opus packet can hold several frames that share a configuration (RFC 6716 section 3.2.5), so
 * this joins consecutive one-frame packets with the same table-of-contents byte until they hold
 * [targetUs] of audio, and hands each result to [send] with the first frame's presentation time. A
 * decoder takes the joined packet like any other, so the viewer needs no change.
 *
 * A packet that already holds several frames, one with another configuration, or one that does not
 * follow on from the held frames in time first sends what is held. Call [flush] where the stream
 * pauses, so the last frames before a gap are not held back.
 *
 * Not thread-safe; the stream uses it from the codec's callback thread.
 */
internal class OpusBundler(
    private val targetUs: Long = TARGET_US,
    private val send: (ptsUs: Long, packet: ByteArray) -> Unit,
) {
    private val held = ArrayList<ByteArray>()
    private var toc = 0
    private var firstPtsUs = 0L
    private var heldUs = 0L

    /** Takes an encoded packet that starts at [ptsUs]. */
    fun add(ptsUs: Long, packet: ByteArray) {
        val head = packet.firstOrNull()?.toInt()?.and(BYTE_MASK)
        if (head == null || head and CODE_MASK != CODE_ONE_FRAME || frameUs(head) >= targetUs) {
            flush()
            send(ptsUs, packet)
            return
        }
        if (held.isNotEmpty() && (head != toc || ptsUs != firstPtsUs + heldUs)) flush()
        if (held.isEmpty()) {
            toc = head
            firstPtsUs = ptsUs
        }
        held += packet
        heldUs += frameUs(head)
        // Each frame is shorter than the target, so a joined packet stays under twice the target,
        // which keeps it within Opus's 120 ms limit.
        if (heldUs >= targetUs) flush()
    }

    /** Sends whatever is held. */
    fun flush() {
        when (held.size) {
            0 -> return
            1 -> send(firstPtsUs, held[0])
            else -> send(firstPtsUs, join())
        }
        held.clear()
        heldUs = 0
    }

    /** Builds a code 3 packet with variable-size frames from the held one-frame packets. */
    private fun join(): ByteArray {
        val out = ByteArrayOutputStream(held.sumOf { it.size } + held.size * 2)
        out.write(toc or CODE_FRAMES)
        out.write(VBR or held.size)
        for (index in 0 until held.size - 1) writeLength(out, held[index].size - 1)
        for (packet in held) out.write(packet, 1, packet.size - 1)
        return out.toByteArray()
    }

    companion object {
        /** 60 ms cuts messages to a third at the cost of 40 ms more delay. */
        const val TARGET_US = 60_000L

        private const val BYTE_MASK = 0xff
        private const val CODE_MASK = 0x03
        private const val CODE_ONE_FRAME = 0
        private const val CODE_FRAMES = 3
        private const val VBR = 0x80
        private const val CONFIG_SHIFT = 3
        private const val SILK_CONFIGS = 12
        private const val HYBRID_CONFIGS = 16
        private const val ONE_BYTE_LENGTH = 252
        private const val LENGTH_STEP = 4

        private val SILK_US = longArrayOf(10_000, 20_000, 40_000, 60_000)
        private val HYBRID_US = longArrayOf(10_000, 20_000)
        private val CELT_US = longArrayOf(2_500, 5_000, 10_000, 20_000)

        /** The duration of each frame in a packet with this table-of-contents byte. */
        fun frameUs(toc: Int): Long {
            val config = toc ushr CONFIG_SHIFT
            return when {
                config < SILK_CONFIGS -> SILK_US[config % SILK_US.size]
                config < HYBRID_CONFIGS -> HYBRID_US[config % HYBRID_US.size]
                else -> CELT_US[config % CELT_US.size]
            }
        }

        /** Writes a frame length in the one- or two-byte form of RFC 6716 section 3.2.1. */
        private fun writeLength(out: ByteArrayOutputStream, length: Int) {
            if (length < ONE_BYTE_LENGTH) {
                out.write(length)
                return
            }
            val first = ONE_BYTE_LENGTH + length % LENGTH_STEP
            out.write(first)
            out.write((length - first) / LENGTH_STEP)
        }
    }
}
