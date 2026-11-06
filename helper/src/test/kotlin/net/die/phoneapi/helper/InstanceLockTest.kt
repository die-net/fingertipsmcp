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
    fun `a second helper for the package finds the lock held`() {
        val child = holder("net.die.phoneapi")
        try {
            assertNull(InstanceLock(dir).acquire("net.die.phoneapi"))
        } finally {
            child.destroyForcibly()
        }
    }

    @Test
    fun `another package's helper still starts`() {
        val child = holder("net.die.phoneapi")
        try {
            assertNotNull(InstanceLock(dir).acquire("net.die.phoneapi.dev"))
        } finally {
            child.destroyForcibly()
        }
    }

    @Test
    fun `a helper that died leaves the lock free`() {
        val child = holder("net.die.phoneapi")
        child.destroyForcibly()
        assertTrue(child.waitFor(SAFETY_S, TimeUnit.SECONDS))
        assertNotNull(InstanceLock(dir).acquire("net.die.phoneapi"))
    }

    private companion object {
        /** Fires only when the child does not exit. */
        const val SAFETY_S = 30L
    }
}

/** Takes the lock in its own process and holds it until killed. */
object LockHolder {
    @JvmStatic
    fun main(args: Array<String>) {
        val lock = InstanceLock(File(args[0])).acquire(args[1])
        println(if (lock != null) "held" else "busy")
        System.out.flush()
        // Holds the lock until the test kills this process.
        System.`in`.read()
        lock?.release()
    }
}
