package com.fingertipsmcp.core

import java.io.File
import java.io.IOException
import java.nio.file.Files
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Replacing a small file whole, and what a failed replace leaves behind. */
class AtomicTextFileTest {
    private val dir: File = Files.createTempDirectory("atomic").toFile()
    private val file = File(dir, "f.json")

    @AfterEach
    fun cleanUp() {
        dir.deleteRecursively()
    }

    @Test
    fun `writes and reads`() {
        val atomic = AtomicTextFile(file)
        assertNull(atomic.read())
        atomic.write("one")
        atomic.write("two")
        assertEquals("two", atomic.read())
        assertFalse(File(dir, "f.json.new").exists())
    }

    @Test
    fun `failed replace cleans up`() {
        // A directory with something in it can't be replaced on any system, as Windows can't
        // replace a file another program has open.
        assertTrue(File(file, "inside").mkdirs())
        assertThrows(IOException::class.java) { AtomicTextFile(file).write("text") }
        assertFalse(File(dir, "f.json.new").exists())
        assertTrue(File(file, "inside").isDirectory)
    }
}
