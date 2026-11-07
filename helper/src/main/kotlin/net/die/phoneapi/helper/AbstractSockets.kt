package net.die.phoneapi.helper

import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import android.util.Log
import java.io.File
import java.io.FileDescriptor
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlinx.serialization.builtins.ListSerializer
import net.die.phoneapi.model.DevtoolsSocketInfo

/** Abstract-namespace sockets, which is where Chrome publishes its DevTools endpoint. */
internal object AbstractSockets {
    private const val TAG = "PhoneApiHelper"
    private const val UNIX_TABLE = "/proc/net/unix"
    private const val RELAY_BUFFER = 8 * 1024
    private const val RELAY_POLL_FOREVER = -1
    private const val CONNECT_TIMEOUT_MS = 1_000L

    /**
     * How many connects to one name may be in flight, counting ones whose caller gave up but whose
     * thread is still blocked in connect. Enough for the app's overlapping healthy connects; a
     * socket that never accepts stops costing threads here.
     */
    private const val MAX_WAITING_CONNECTS = 4
    private val connecting = InFlight(MAX_WAITING_CONNECTS)

    fun connect(name: String): ParcelFileDescriptor {
        val remote =
            try {
                connectSocket(name)
            } catch (e: IOException) {
                // IllegalStateException crosses Binder. RemoteException does not, so the app would
                // see a null descriptor instead of this message.
                throw IllegalStateException("connect $name: ${e.message.orEmpty()}", e)
            }
        val pair = ParcelFileDescriptor.createSocketPair()
        // Chrome records the peer uid of whoever holds the connected fd. Handing Chrome's fd to
        // the app makes the peer the app, and Chrome then stops reading. Keep Chrome's fd here
        // and relay bytes over a socketpair the app owns.
        relay(remote, pair[1])
        return pair[0]
    }

    @Suppress("MissingUseCall") // The relay owns the connected socket and closes it.
    private fun connectSocket(name: String): LocalSocket {
        if (!connecting.enter(name)) {
            throw IOException(
                "connect $name: earlier connects are still waiting; it is not accepting"
            )
        }
        val result = CompletableFuture<LocalSocket>()
        val worker = Thread {
            val socket = LocalSocket()
            try {
                socket.connect(LocalSocketAddress(name, LocalSocketAddress.Namespace.ABSTRACT))
                if (!result.complete(socket)) socket.close()
            } catch (e: IOException) {
                socket.close()
                result.completeExceptionally(e)
            } finally {
                connecting.exit(name)
            }
        }
        worker.isDaemon = true
        worker.start()
        return awaitSocket(name, result)
    }

    private fun awaitSocket(name: String, result: CompletableFuture<LocalSocket>): LocalSocket =
        try {
            result.get(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            throw IOException("connect $name: timed out", e)
        } catch (e: ExecutionException) {
            throw IOException("connect $name: ${e.cause?.message ?: e.message.orEmpty()}", e)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("connect $name: interrupted", e)
        }

    private fun relay(remote: LocalSocket, local: ParcelFileDescriptor) {
        val worker = Thread {
            try {
                copyBothWays(remote.fileDescriptor, local.fileDescriptor)
            } catch (e: ErrnoException) {
                Log.w(TAG, "DevTools relay stopped", e)
            } finally {
                closeQuietly(remote)
                closeQuietly(local)
            }
        }
        worker.isDaemon = true
        worker.start()
    }

    private fun copyBothWays(remote: FileDescriptor, local: FileDescriptor) {
        val buffer = ByteArray(RELAY_BUFFER)
        val polls = arrayOf(pollFd(remote), pollFd(local))
        while (true) {
            if (Os.poll(polls, RELAY_POLL_FOREVER) == 0) continue
            val remoteOpen = !readable(polls[0]) || copyOnce(remote, local, buffer)
            val localOpen = !readable(polls[1]) || copyOnce(local, remote, buffer)
            if (!remoteOpen || !localOpen) return
        }
    }

    private fun pollFd(fd: FileDescriptor): StructPollfd {
        val poll = StructPollfd()
        poll.fd = fd
        poll.events = OsConstants.POLLIN.toShort()
        return poll
    }

    private fun readable(poll: StructPollfd): Boolean {
        val revents = poll.revents.toInt()
        return revents and (OsConstants.POLLIN or OsConstants.POLLERR or OsConstants.POLLHUP) != 0
    }

    private fun copyOnce(from: FileDescriptor, to: FileDescriptor, buffer: ByteArray): Boolean {
        val read =
            try {
                Os.read(from, buffer, 0, buffer.size)
            } catch (e: ErrnoException) {
                return e.errno == OsConstants.EINTR || e.errno == OsConstants.EAGAIN
            }
        if (read <= 0) return false
        var offset = 0
        while (offset < read) {
            val wrote =
                try {
                    Os.write(to, buffer, offset, read - offset)
                } catch (e: ErrnoException) {
                    if (e.errno == OsConstants.EINTR) continue
                    return false
                }
            if (wrote <= 0) return false
            offset += wrote
        }
        return true
    }

    private fun closeQuietly(closeable: AutoCloseable) {
        try {
            closeable.close()
        } catch (e: IOException) {
            Log.w(TAG, "Closing a DevTools relay socket", e)
        }
    }

    fun devtools(): String {
        val table =
            try {
                File(UNIX_TABLE).readText()
            } catch (e: IOException) {
                Log.w(TAG, "Could not read $UNIX_TABLE", e)
                return "[]"
            }
        val entries =
            parseDevtoolsSockets(table).map { socket ->
                val owner = ownerOf(socket.inode)
                DevtoolsSocketInfo(
                    name = socket.name,
                    pid = owner?.pid,
                    uid = owner?.uid,
                    packageName = owner?.packageName,
                )
            }
        return HelperJson.encodeToString(ListSerializer(DevtoolsSocketInfo.serializer()), entries)
    }

    private fun ownerOf(inode: Long): Owner? {
        val marker = "socket:[$inode]"
        val proc = File("/proc").listFiles() ?: return null
        for (dir in proc) {
            val owner = ownerIn(dir, marker)
            if (owner != null) return owner
        }
        return null
    }

    private fun ownerIn(dir: File, marker: String): Owner? {
        val pid = dir.name.toIntOrNull() ?: return null
        val fds = File(dir, "fd").list() ?: return null
        for (fd in fds) {
            if (readLink("${dir.path}/fd/$fd") == marker) {
                return Owner(pid, uidOf(dir), packageOf(dir))
            }
        }
        return null
    }

    private fun readLink(path: String): String? =
        try {
            Os.readlink(path)
        } catch (_: ErrnoException) {
            null
        }

    private fun uidOf(dir: File): Int? {
        val status = File(dir, "status")
        val line =
            try {
                status.useLines { lines -> lines.firstOrNull { it.startsWith("Uid:") } }
            } catch (_: IOException) {
                null
            } ?: return null
        return line.substringAfter(':').trim().substringBefore(' ').toIntOrNull()
    }

    private fun packageOf(dir: File): String? {
        val raw =
            try {
                File(dir, "cmdline").readBytes()
            } catch (_: IOException) {
                return null
            }
        val first = raw.decodeToString().substringBefore('\u0000').substringBefore(' ')
        return first.takeIf { it.isNotEmpty() && '/' !in it && '.' in it }
    }

    private data class Owner(val pid: Int, val uid: Int?, val packageName: String?)
}

/**
 * One listening abstract-namespace socket from `/proc/net/unix`. [inode] is how a caller finds the
 * owning process; the table itself has no pid.
 */
internal data class AbstractSocket(val name: String, val inode: Long)

private val DEVTOOLS = Regex("""^(chrome_devtools_remote|webview_devtools_remote_.+)$""")

/**
 * The listening sockets whose names are Chrome or WebView DevTools endpoints. Every connection to a
 * listener has a row under the same name, so the name alone does not make a row an endpoint.
 */
internal fun parseDevtoolsSockets(table: String): List<AbstractSocket> =
    table.lineSequence().mapNotNull { raw -> socketRow(raw.trim()) }.toList()

private fun socketRow(line: String): AbstractSocket? {
    if (line.isEmpty() || line.startsWith("Num")) return null
    val columns = line.split(Regex("\\s+"))
    val flags = columns.getOrNull(FLAGS_COLUMN)?.toIntOrNull(HEX) ?: return null
    if (flags and SO_ACCEPTCON == 0) return null
    val inode = columns.getOrNull(INODE_COLUMN)?.toLongOrNull() ?: return null
    val path = columns.getOrNull(PATH_COLUMN) ?: return null
    val name = path.removePrefix("@")
    return if (DEVTOOLS.matches(name)) AbstractSocket(name, inode) else null
}

private const val FLAGS_COLUMN = 3
private const val INODE_COLUMN = 6
private const val PATH_COLUMN = 7
private const val HEX = 16

/** The kernel's `__SO_ACCEPTCON`, the flag `/proc/net/unix` sets on a socket that is listening. */
private const val SO_ACCEPTCON = 0x10000

/** Counts work in flight per key, up to [max] at once for each. */
internal class InFlight(private val max: Int) {
    private val counts = HashMap<String, Int>()

    /** Counts one more for [key], or returns false when [max] are already in flight. */
    @Synchronized
    fun enter(key: String): Boolean {
        val now = counts[key] ?: 0
        if (now >= max) return false
        counts[key] = now + 1
        return true
    }

    /** Counts one fewer for [key]. */
    @Synchronized
    fun exit(key: String) {
        val left = (counts[key] ?: return) - 1
        if (left > 0) counts[key] = left else counts.remove(key)
    }
}
