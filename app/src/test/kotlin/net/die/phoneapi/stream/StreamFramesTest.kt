package net.die.phoneapi.stream

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.die.phoneapi.model.DisplayInfo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class StreamFramesTest {
    @Test
    fun `packs a timestamp`() {
        val packed = packFrame(FRAME_KEY, 0x0102030405060708L, byteArrayOf(9, 10))
        assertEquals(FRAME_KEY.toByte(), packed[0])
        assertEquals(0x01.toByte(), packed[1])
        assertEquals(0x08.toByte(), packed[8])
        assertEquals(9.toByte(), packed[9])
        assertEquals(10.toByte(), packed[10])
    }

    @Test
    fun `scales the long side`() {
        assertEquals(576 to 1280, scaledSize(1080, 2400, 1280))
        assertEquals(100 to 200, scaledSize(100, 200, 1280))
    }

    @Test
    fun `rounds down to even`() {
        assertEquals(100 to 100, scaledSize(100, 101, 1000))
    }

    @Test
    fun `reads an avc config`() {
        val avcc =
            byteArrayOf(
                0x01,
                0x42,
                0x00,
                0x1e,
                0xff.toByte(),
                0xe1.toByte(),
                0x00,
                0x03,
                0x67,
                0x42,
                0x00,
                0x01,
                0x00,
                0x03,
                0x68,
                0xce.toByte(),
                0x06,
            )
        val annex = avcDecoderConfigToAnnexB(avcc)
        assertEquals(14, annex.size)
        assertEquals(1.toByte(), annex[3])
        assertEquals(0x67.toByte(), annex[4])
        assertEquals(0x68.toByte(), annex[11])
    }

    @Test
    fun `reads length prefixes`() {
        val data = byteArrayOf(0, 0, 0, 2, 0x65, 0x88.toByte(), 0, 0, 0, 1, 0x41)
        val annex = accessUnitToAnnexB(data)
        assertEquals(11, annex.size)
        assertEquals(0x65.toByte(), annex[4])
        assertEquals(0x41.toByte(), annex[10])
    }

    @Test
    fun `video header names the size`() {
        val json =
            Json.parseToJsonElement(
                    videoHeader(288, 640, VideoSpec(640, 30, 4_000_000), screen(1080, 2400))
                )
                .jsonObject
        assertEquals("avc", json.getValue("codec").jsonPrimitive.content)
        assertEquals(288, json.getValue("width").jsonPrimitive.int)
        assertEquals(640, json.getValue("height").jsonPrimitive.int)
        assertEquals(30, json.getValue("fps").jsonPrimitive.int)
        assertEquals(4_000_000, json.getValue("bitRate").jsonPrimitive.int)
    }

    @Test
    fun `unwraps android opus header`() {
        val opusHead = ByteArray(19) { index -> (index + 1).toByte() }
        val wrapped = ByteArray(16 + opusHead.size)
        "AOPUSHDR".encodeToByteArray().copyInto(wrapped)
        wrapped[8] = opusHead.size.toByte()
        opusHead.copyInto(wrapped, 16)
        assertEquals(opusHead.toList(), audioCodecDescription(wrapped).toList())
        assertEquals(opusHead.toList(), audioCodecDescription(opusHead).toList())
    }

    @Test
    fun `audio header names the codec`() {
        val json = Json.parseToJsonElement(audioHeader("opus", 48_000, 2)).jsonObject
        assertEquals("opus", json.getValue("codec").jsonPrimitive.content)
        assertEquals(48_000, json.getValue("sampleRate").jsonPrimitive.int)
        assertEquals(2, json.getValue("channels").jsonPrimitive.int)
    }

    @Test
    fun `rotation changes encoder size`() {
        val named = 1080 to 2400
        assertEquals(
            DisplayChange.NONE,
            displayChange(288 to 640, named, screen(1080, 2400), 640),
        )
        assertEquals(
            DisplayChange.ENCODER,
            displayChange(288 to 640, named, screen(2400, 1080), 640),
        )
        assertEquals(640 to 288, scaledSize(2400, 1080, 640))
    }

    @Test
    fun `same even size keeps encoder`() {
        assertEquals(
            DisplayChange.NONE,
            displayChange(100 to 100, 100 to 101, screen(100, 101), 1000),
        )
    }

    /** A resolution change that encodes at the same size still has to reach the viewer. */
    @Test
    fun `a new screen size resends`() {
        assertEquals(576 to 1280, scaledSize(1440, 3200, 1280))
        assertEquals(
            DisplayChange.HEADER,
            displayChange(576 to 1280, 1080 to 2400, screen(1440, 3200), 1280),
        )
    }

    @Test
    fun `video header names the screen`() {
        val json =
            Json.parseToJsonElement(
                    videoHeader(576, 1280, VideoSpec(1280, 30, 4_000_000), screen(1080, 2400))
                )
                .jsonObject
        assertEquals(1080, json.getValue("displayWidth").jsonPrimitive.int)
        assertEquals(2400, json.getValue("displayHeight").jsonPrimitive.int)
    }

    private fun screen(width: Int, height: Int) = DisplayInfo(width, height, 420, 0, 60f)
}
