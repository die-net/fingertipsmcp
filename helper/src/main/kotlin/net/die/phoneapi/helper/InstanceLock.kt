package net.die.phoneapi.helper

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException

/**
 * Makes one helper per app package. Each daemon takes a lock on a file named for its package, and
 * the kernel drops the lock when the process dies, so a crashed helper never blocks the next one. A
 * second launch for the same package finds the lock held and exits, which makes launching
 * idempotent. Helpers for different packages, such as a release and a debug build, each run.
 */
internal class InstanceLock(private val dir: File) {
    /**
     * Takes the lock for [packageName] for the life of this process. Null when another helper for
     * that package holds it. Throws [IOException] when the lock file can't be opened.
     */
    fun acquire(packageName: String): FileLock? {
        val file = RandomAccessFile(File(dir, "phoneapi-helper-$packageName.lock"), "rw")
        val lock =
            try {
                file.channel.tryLock()
            } catch (_: OverlappingFileLockException) {
                // This process already holds it, which only a second acquire in one process does.
                null
            }
        if (lock == null) file.close()
        return lock
    }
}
