package net.die.phoneapi.stream

import kotlin.math.abs

/**
 * Decides which encoded audio packets are worth sending. Remote submix keeps delivering PCM while
 * nothing plays, and sending its silence costs a message every 20 ms. The viewer plays packets as
 * they arrive and needs none to stay connected, so silent stretches are left out.
 *
 * [heard] sees each PCM frame before the encoder does, and [keeps] is asked about each encoded
 * packet by its presentation time. A packet is kept until [hangoverUs] after the last frame with
 * sound. That covers the encoder's lookahead, which puts the end of a sound into the next packet,
 * and gives the decoder a quiet packet to fade out on before the gap.
 */
internal class SilenceGate(private val hangoverUs: Long) {
    @Volatile private var soundUntilUs = Long.MIN_VALUE

    /** Notes a 16-bit little-endian PCM frame that starts at [ptsUs]. */
    fun heard(pcm: ByteArray, ptsUs: Long) {
        if (!isSilent(pcm)) soundUntilUs = ptsUs + hangoverUs
    }

    /** Whether the packet at [ptsUs] carries sound or the tail of one. */
    fun keeps(ptsUs: Long): Boolean = ptsUs <= soundUntilUs

    companion object {
        /**
         * Largest sample treated as silence. Remote submix sends zeros while nothing plays, and
         * some apps dither their silence by a few steps, which no one can hear at 16 bits.
         */
        const val SILENCE_LEVEL = 8

        fun isSilent(pcm: ByteArray): Boolean {
            var index = 0
            while (index + 1 < pcm.size) {
                val sample = (pcm[index].toInt() and 0xff) or (pcm[index + 1].toInt() shl 8)
                if (abs(sample) > SILENCE_LEVEL) return false
                index += 2
            }
            return true
        }
    }
}
