package com.fingertipsmcp.input

private val PNG_MAGIC = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte())
private val JPEG_MAGIC = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
private val GIF_MAGIC = "GIF8".toByteArray()
private val RIFF_MAGIC = "RIFF".toByteArray()
private val WEBP_MAGIC = "WEBP".toByteArray()

/** An image format the clipboard calls take, by MIME type and the extension its file gets. */
internal enum class ImageType(val mimeType: String, val extension: String) {
    PNG("image/png", "png"),
    JPEG("image/jpeg", "jpg"),
    GIF("image/gif", "gif"),
    WEBP("image/webp", "webp");

    companion object {
        fun ofExtension(extension: String): ImageType? = entries.firstOrNull {
            it.extension == extension
        }

        /** The format [bytes] start with, from its magic number, or null for anything else. */
        fun sniff(bytes: ByteArray): ImageType? =
            when {
                bytes.startsWith(PNG_MAGIC) -> PNG
                bytes.startsWith(JPEG_MAGIC) -> JPEG
                bytes.startsWith(GIF_MAGIC) -> GIF
                bytes.startsWith(RIFF_MAGIC) && bytes.startsWith(WEBP_MAGIC, at = 8) -> WEBP
                else -> null
            }

        private fun ByteArray.startsWith(prefix: ByteArray, at: Int = 0): Boolean =
            size >= at + prefix.size && prefix.indices.all { this[at + it] == prefix[it] }
    }
}
