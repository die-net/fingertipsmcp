package com.fingertipsmcp.helper

import java.io.IOException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Two helpers sharing the phone's abstract socket namespace. The fake behaves as the kernel does: a
 * listen on a name already held fails, a connect to it succeeds only while its holder is open, and
 * closing the holder, which a process exit does, frees the name.
 */
class InstanceLockTest {
    private class Namespace {
        val listening = HashSet<String>()

        /** Names whose listen fails for a reason other than being held. */
        val broken = HashSet<String>()

        fun listen(name: String): AutoCloseable {
            if (name in broken) throw IOException("Permission denied")
            if (!listening.add(name)) throw IOException("Address already in use")
            return AutoCloseable { listening.remove(name) }
        }

        fun connects(name: String): Boolean = name in listening
    }

    private val namespace = Namespace()

    private fun lock() = InstanceLock(namespace::listen, namespace::connects)

    @Test
    fun `same package is refused`() {
        lock().acquire("com.fingertipsmcp").use {
            assertNotNull(it)
            lock().acquire("com.fingertipsmcp").use { refused -> assertNull(refused) }
        }
    }

    @Test
    fun `another package starts`() {
        lock().acquire("com.fingertipsmcp").use {
            lock().acquire("com.fingertipsmcp.dev").use { other -> assertNotNull(other) }
        }
    }

    @Test
    fun `a dead holder frees it`() {
        lock().acquire("com.fingertipsmcp")?.close()
        lock().acquire("com.fingertipsmcp").use { assertNotNull(it) }
    }

    @Test
    fun `another failure is thrown`() {
        // Nobody holds the name, so the failed listen is not a held lock.
        namespace.broken += "fingertips-helper-com.fingertipsmcp"
        assertThrows<IOException> { lock().acquire("com.fingertipsmcp") }
    }

    @Test
    fun `the name is per package`() {
        lock().acquire("com.fingertipsmcp").use {
            assertEquals(setOf("fingertips-helper-com.fingertipsmcp"), namespace.listening)
        }
    }
}
