package com.fingertipsmcp.core

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.SecureRandom
import java.util.Base64

fun randomToken(random: SecureRandom, bytes: Int): String {
    val buf = ByteArray(bytes)
    random.nextBytes(buf)
    return Base64.getUrlEncoder().withoutPadding().encodeToString(buf)
}

/**
 * A small file replaced whole: the new text goes to a sibling `.new` file, which is synced and then
 * renamed over the old one, so a reader sees the old file or the new one and never half of a write.
 */
class AtomicTextFile(private val file: File) {
    fun read(): String? = if (file.exists()) file.readText(Charsets.UTF_8) else null

    fun write(text: String) {
        val dir = file.absoluteFile.parentFile
        dir.mkdirs()
        val next = File(dir, file.name + ".new")
        FileOutputStream(next).use { out ->
            out.write(text.toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }
        Files.move(
            next.toPath(),
            file.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
    }
}
