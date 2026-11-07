package net.die.phoneapi.helper

import java.io.IOException
import net.die.phoneapi.helper.compat.AbstractName

/**
 * Makes one helper per app package. Each daemon binds an abstract-namespace socket named for its
 * package. A second bind of that name fails with EADDRINUSE, and the kernel frees the name when the
 * process dies, so a crashed helper never blocks the next one. Unlike a lock file, the name has no
 * owner or mode, so a helper running as root and one running as shell see the same lock. A second
 * launch for the same package finds the name taken and exits, which makes launching idempotent.
 * Helpers for different packages, such as a release and a debug build, each run.
 *
 * The socket is bound but never listens, so nothing can connect to it; it only holds the name.
 * [bind] is [AbstractName.bind], which returns null when the name is taken.
 */
internal class InstanceLock(private val bind: (String) -> AutoCloseable? = AbstractName::bind) {
    /**
     * Takes the lock for [packageName] for the life of this process. Null when another helper for
     * that package holds it. Throws [IOException] when the bind fails for another reason.
     */
    fun acquire(packageName: String): AutoCloseable? = bind("$NAME_PREFIX$packageName")

    private companion object {
        const val NAME_PREFIX = "phoneapi-helper-"
    }
}
