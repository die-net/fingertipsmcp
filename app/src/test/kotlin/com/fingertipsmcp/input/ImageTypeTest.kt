package com.fingertipsmcp.input

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ImageTypeTest {
    @Test
    fun `sniffs each format`() {
        assertEquals(ImageType.PNG, ImageType.sniff(bytes(0x89, 'P', 'N', 'G', 0x0D, 0x0A)))
        assertEquals(ImageType.JPEG, ImageType.sniff(bytes(0xFF, 0xD8, 0xFF, 0xE0)))
        assertEquals(ImageType.GIF, ImageType.sniff("GIF89a".toByteArray()))
        assertEquals(
            ImageType.WEBP,
            ImageType.sniff("RIFF\u0000\u0000\u0000\u0000WEBPVP8 ".toByteArray()),
        )
    }

    @Test
    fun `rejects other bytes`() {
        assertNull(ImageType.sniff(ByteArray(0)))
        assertNull(ImageType.sniff(bytes(0x89, 'P')))
        assertNull(ImageType.sniff("RIFF\u0000\u0000\u0000\u0000WAVE".toByteArray()))
        assertNull(ImageType.sniff("hello".toByteArray()))
    }

    @Test
    fun `maps extensions back`() {
        for (type in ImageType.entries) assertEquals(type, ImageType.ofExtension(type.extension))
        assertNull(ImageType.ofExtension("exe"))
    }

    private fun bytes(vararg values: Any): ByteArray =
        values
            .map {
                when (it) {
                    is Char -> it.code.toByte()
                    else -> (it as Int).toByte()
                }
            }
            .toByteArray()
}
