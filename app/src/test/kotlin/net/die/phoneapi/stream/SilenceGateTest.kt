package net.die.phoneapi.stream

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SilenceGateTest {
    private val frameUs = 20_000L

    private fun pcm(vararg samples: Int): ByteArray {
        val out = ByteArray(samples.size * 2)
        samples.forEachIndexed { index, sample ->
            out[index * 2] = (sample and 0xff).toByte()
            out[index * 2 + 1] = (sample shr 8).toByte()
        }
        return out
    }

    @Test
    fun `zeros and dither are silent`() {
        assertTrue(SilenceGate.isSilent(pcm(0, 0, 0, 0)))
        assertTrue(SilenceGate.isSilent(pcm(3, -8, 8, -1)))
    }

    @Test
    fun `a quiet sample is sound`() {
        assertFalse(SilenceGate.isSilent(pcm(0, 0, 9, 0)))
        assertFalse(SilenceGate.isSilent(pcm(0, -9, 0, 0)))
        assertFalse(SilenceGate.isSilent(pcm(0, 0, 0, Short.MIN_VALUE.toInt())))
    }

    @Test
    fun `drops packets before any sound`() {
        val gate = SilenceGate(3 * frameUs)
        for (frame in 0 until 5) {
            gate.heard(pcm(0, 0), frame * frameUs)
            assertFalse(gate.keeps(frame * frameUs))
        }
    }

    @Test
    fun `keeps the tail, then drops`() {
        val gate = SilenceGate(3 * frameUs)
        gate.heard(pcm(0, 0), 0)
        gate.heard(pcm(1000, -1000), frameUs)
        for (frame in 2 until 8) gate.heard(pcm(0, 0), frame * frameUs)
        // Capture runs ahead of the encoder, so the packet just before a sound already heard is
        // kept too. It is quiet, and gives the decoder a lead-in.
        assertTrue(gate.keeps(0))
        for (frame in 1..4) assertTrue(gate.keeps(frame * frameUs), "frame $frame")
        for (frame in 5 until 8) assertFalse(gate.keeps(frame * frameUs), "frame $frame")
    }

    @Test
    fun `sends again when sound returns`() {
        val gate = SilenceGate(3 * frameUs)
        gate.heard(pcm(500), 0)
        gate.heard(pcm(0), 10 * frameUs)
        assertFalse(gate.keeps(10 * frameUs))
        gate.heard(pcm(500), 11 * frameUs)
        assertTrue(gate.keeps(11 * frameUs))
    }
}
