package com.fingertipsmcp.helper

import android.net.LocalServerSocket
import android.net.LocalSocket
import android.net.LocalSocketAddress
import java.io.IOException
import kotlin.concurrent.thread

/**
 * Makes one helper per app package. Each daemon listens on an abstract-namespace socket named for
 * its package, and the kernel frees the name when the process dies, so a crashed helper never
 * blocks the next one. The name has no owner or mode, so a helper running as root and one running
 * as shell see the same lock. A second launch for the same package finds the name held and exits,
 * which makes launching idempotent. Helpers for different packages, such as a release and a debug
 * build, each run.
 *
 * A failed listen tells a held name from any other failure by connecting to it, without reading the
 * error's text: the connect succeeds only while a holder listens. The holder accepts each
 * connection and closes it at once, reading and writing nothing, so probes never fill its backlog.
 */
internal class InstanceLock(
    private val listen: (String) -> AutoCloseable = ::listenAndTurnAway,
    private val held: (String) -> Boolean = ::someoneListens,
) {
    /**
     * Takes the lock for [packageName] for the life of this process. Null when another helper for
     * that package holds it. Throws [IOException] when listening failed for another reason.
     */
    fun acquire(packageName: String): AutoCloseable? {
        val name = "$NAME_PREFIX$packageName"
        return try {
            listen(name)
        } catch (e: IOException) {
            if (held(name)) null else throw e
        }
    }

    private companion object {
        const val NAME_PREFIX = "fingertips-helper-"
    }
}

/**
 * Listens on the abstract [name] and closes every connection as soon as it is accepted, on a daemon
 * thread that ends when the socket closes.
 */
@Suppress("MissingUseCall") // The caller holds the socket for the life of the process.
private fun listenAndTurnAway(name: String): AutoCloseable {
    val server = LocalServerSocket(name)
    thread(name = "helper-lock", isDaemon = true) {
        try {
            while (true) server.accept().close()
        } catch (_: IOException) {
            // The socket closed, which only happens as the process exits.
        }
    }
    return server
}

/** True when a socket is listening on the abstract [name]: a connect to it succeeds. */
@Suppress("SwallowedException") // A refused connect is the answer: nobody is listening.
private fun someoneListens(name: String): Boolean =
    try {
        LocalSocket().use {
            it.connect(LocalSocketAddress(name, LocalSocketAddress.Namespace.ABSTRACT))
        }
        true
    } catch (e: IOException) {
        false
    }
