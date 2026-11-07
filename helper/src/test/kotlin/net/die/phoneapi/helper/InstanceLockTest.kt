package net.die.phoneapi.helper

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Two helpers sharing the phone's abstract socket namespace. The fake binds as the kernel does: a
 * name is taken until the socket holding it closes, which a process exit does.
 */
class InstanceLockTest {
    private class Namespace {
        val taken = HashSet<String>()

        fun bind(name: String): AutoCloseable? =
            if (taken.add(name)) AutoCloseable { taken.remove(name) } else null
    }

    private val namespace = Namespace()
    private val running = InstanceLock(namespace::bind)
    private val second = InstanceLock(namespace::bind)

    @Test
    fun `same package is refused`() {
        running.acquire("net.die.phoneapi").use {
            assertNotNull(it)
            second.acquire("net.die.phoneapi").use { refused -> assertNull(refused) }
        }
    }

    @Test
    fun `another package starts`() {
        running.acquire("net.die.phoneapi").use {
            second.acquire("net.die.phoneapi.dev").use { other -> assertNotNull(other) }
        }
    }

    @Test
    fun `a dead holder frees it`() {
        running.acquire("net.die.phoneapi")?.close()
        second.acquire("net.die.phoneapi").use { assertNotNull(it) }
    }

    @Test
    fun `the name is per package`() {
        running.acquire("net.die.phoneapi").use {
            assertEquals(setOf("phoneapi-helper-net.die.phoneapi"), namespace.taken)
        }
    }
}
