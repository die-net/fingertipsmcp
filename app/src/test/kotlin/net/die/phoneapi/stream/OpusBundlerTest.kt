package net.die.phoneapi.stream

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class OpusBundlerTest {
    private val sent = ArrayList<Pair<Long, ByteArray>>()
    private val bundler = OpusBundler { pts, packet -> sent += pts to packet }

    /** CELT fullband, 20 ms, stereo, one frame. */
    private val celt20 = (31 shl 3) or (1 shl 2)

    /** SILK wideband, 20 ms, stereo, one frame. */
    private val silk20 = (9 shl 3) or (1 shl 2)

    private fun packet(toc: Int, size: Int, fill: Int = size): ByteArray =
        ByteArray(size + 1) { if (it == 0) toc.toByte() else (fill + it).toByte() }

    /** Reads a code 3 packet's frames back, per RFC 6716 section 3.2.5. */
    private fun frames(packet: ByteArray): List<ByteArray> {
        assertEquals(3, packet[0].toInt() and 3)
        val count = packet[1].toInt() and 0x3f
        assertEquals(0x80, packet[1].toInt() and 0xc0, "VBR without padding")
        var pos = 2
        val lengths = ArrayList<Int>()
        repeat(count - 1) {
            val first = packet[pos++].toInt() and 0xff
            lengths += if (first < 252) first else first + 4 * (packet[pos++].toInt() and 0xff)
        }
        lengths += packet.size - pos - lengths.sum()
        return lengths.map { length ->
            packet.copyOfRange(pos, pos + length).also { pos += length }
        }
    }

    @Test
    fun `joins three 20 ms frames`() {
        val input = listOf(packet(celt20, 100, 1), packet(celt20, 300, 2), packet(celt20, 1275, 3))
        input.forEachIndexed { index, packet -> bundler.add(1_000_000L + index * 20_000, packet) }
        assertEquals(1, sent.size)
        val (pts, joined) = sent[0]
        assertEquals(1_000_000L, pts)
        assertEquals(celt20 or 3, joined[0].toInt() and 0xff)
        val out = frames(joined)
        for (index in input.indices) {
            assertArrayEquals(input[index].copyOfRange(1, input[index].size), out[index])
        }
    }

    @Test
    fun `holds until 60 ms`() {
        bundler.add(0, packet(celt20, 10))
        bundler.add(20_000, packet(celt20, 10))
        assertEquals(0, sent.size)
        bundler.add(40_000, packet(celt20, 10))
        bundler.add(60_000, packet(celt20, 10))
        assertEquals(listOf(0L), sent.map { it.first })
    }

    @Test
    fun `flush sends a lone packet`() {
        val only = packet(celt20, 10)
        bundler.add(0, only)
        bundler.flush()
        bundler.flush()
        assertEquals(1, sent.size)
        assertSame(only, sent[0].second)
    }

    @Test
    fun `new config sends what is held`() {
        bundler.add(0, packet(celt20, 10))
        bundler.add(20_000, packet(celt20, 10))
        bundler.add(40_000, packet(silk20, 10))
        assertEquals(listOf(0L), sent.map { it.first })
        assertEquals(2, frames(sent[0].second).size)
        bundler.flush()
        assertEquals(listOf(0L, 40_000L), sent.map { it.first })
        assertEquals(silk20, sent[1].second[0].toInt() and 0xff)
    }

    @Test
    fun `gap in time sends what is held`() {
        bundler.add(0, packet(celt20, 10))
        bundler.add(100_000, packet(celt20, 10))
        assertEquals(listOf(0L), sent.map { it.first })
        bundler.add(120_000, packet(celt20, 10))
        bundler.add(140_000, packet(celt20, 10))
        assertEquals(listOf(0L, 100_000L), sent.map { it.first })
    }

    @Test
    fun `multi-frame packets pass`() {
        bundler.add(0, packet(celt20, 10))
        val twoFrames = packet(celt20 or 1, 20)
        bundler.add(20_000, twoFrames)
        assertEquals(listOf(0L, 20_000L), sent.map { it.first })
        assertSame(twoFrames, sent[1].second)
    }

    @Test
    fun `60 ms frames pass`() {
        val silk60 = (3 shl 3) or (1 shl 2)
        val long = packet(silk60, 40)
        bundler.add(0, long)
        assertSame(long, sent.single().second)
    }

    @Test
    fun `frame durations by config`() {
        assertEquals(10_000L, OpusBundler.frameUs(0 shl 3))
        assertEquals(60_000L, OpusBundler.frameUs(11 shl 3))
        assertEquals(10_000L, OpusBundler.frameUs(12 shl 3))
        assertEquals(20_000L, OpusBundler.frameUs(15 shl 3))
        assertEquals(2_500L, OpusBundler.frameUs(16 shl 3))
        assertEquals(20_000L, OpusBundler.frameUs(31 shl 3))
    }
}
