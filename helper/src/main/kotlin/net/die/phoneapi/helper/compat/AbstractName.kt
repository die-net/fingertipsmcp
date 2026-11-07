package net.die.phoneapi.helper.compat

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.IOException
import java.net.SocketAddress

/**
 * Holds a name in the abstract socket namespace. `LocalServerSocket` reports a failed bind only as
 * an `IOException` with errno's text, so this binds through [Os], whose [ErrnoException] carries
 * the errno itself. The address type, `android.system.UnixSocketAddress`, is hidden, and its
 * `createAbstract` has been there since before minSdk.
 */
internal object AbstractName {
    /**
     * Binds a socket to [name] and returns it, to be closed to give the name up. Null when another
     * socket already has the name. Throws [IOException] for any other failure.
     */
    fun bind(name: String): AutoCloseable? {
        val address =
            reflective("UnixSocketAddress.createAbstract") {
                Class.forName("android.system.UnixSocketAddress")
                    .getMethod("createAbstract", String::class.java)
                    .invoke(null, name) as SocketAddress
            }
        val fd =
            try {
                Os.socket(OsConstants.AF_UNIX, OsConstants.SOCK_STREAM, 0)
            } catch (e: ErrnoException) {
                throw IOException("socket for $name: errno ${e.errno}", e)
            }
        try {
            Os.bind(fd, address)
        } catch (e: ErrnoException) {
            Os.close(fd)
            if (e.errno == OsConstants.EADDRINUSE) return null
            throw IOException("bind $name: errno ${e.errno}", e)
        }
        return AutoCloseable { Os.close(fd) }
    }
}
