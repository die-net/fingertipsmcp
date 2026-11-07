package net.die.phoneapi.helper

import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** Helpers are separate processes, so the lock is held by a child JVM, as a second helper is. */
class InstanceLockTest {
    @TempDir lateinit var dir: File

    private fun holder(packageName: String): Process {
        val java = File(System.getProperty("java.home"), "bin/java").path
        val classpath = System.getProperty("java.class.path")
        val child =
            ProcessBuilder(
                    java,
                    "-cp",
                    classpath,
                    LockHolder::class.java.name,
                    dir.path,
                    packageName,
                )
                .redirectErrorStream(true)
                .start()
        // The child prints once it holds the lock.
        assertEquals("held", child.inputStream.bufferedReader().readLine())
        return child
    }

    @Test
    fun `same package is refused`() {
        val child = holder("net.die.phoneapi")
        try {
            InstanceLock(dir).acquire("net.die.phoneapi").use { assertNull(it) }
        } finally {
            child.destroyForcibly()
        }
    }

    @Test
    fun `another package starts`() {
        val child = holder("net.die.phoneapi")
        try {
            InstanceLock(dir).acquire("net.die.phoneapi.dev").use { assertNotNull(it) }
        } finally {
            child.destroyForcibly()
        }
    }

    @Test
    fun `a dead holder frees it`() {
        val child = holder("net.die.phoneapi")
        child.destroyForcibly()
        assertTrue(child.waitFor(SAFETY_S, TimeUnit.SECONDS))
        InstanceLock(dir).acquire("net.die.phoneapi").use { assertNotNull(it) }
    }

    private companion object {
        /** Fires only when the child does not exit. */
        const val SAFETY_S = 30L
    }
}

/** Takes the lock in its own process and holds it until killed. */
object LockHolder {
    private val HELD = "held\n".toByteArray()
    private val BUSY = "busy\n".toByteArray()

    @JvmStatic
    fun main(args: Array<String>) {
        InstanceLock(File(args[0])).acquire(args[1]).use { lock ->
            // The parent reads this line from the child's stdout.
            System.out.write(if (lock != null) HELD else BUSY)
            System.out.flush()
            // Holds the lock until the test kills this process.
            System.`in`.read()
        }
    }
}
