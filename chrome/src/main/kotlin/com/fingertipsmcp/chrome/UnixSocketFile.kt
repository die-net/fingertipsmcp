package com.fingertipsmcp.chrome

import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException

/**
 * Makes [socket] free for `--unix` to bind, before Chrome starts. A socket file whose server is
 * gone is deleted, as one is left behind when a server is killed. Anything else at the path is
 * refused: a server that answers there, which would keep running unreachable if its file were
 * replaced, or a file that is not a socket.
 */
internal fun claimUnixSocket(socket: File) {
    // sockaddr_un holds 104 bytes on macOS and 108 on Linux, with the terminating NUL.
    if (socket.path.toByteArray().size >= MAX_SOCKET_PATH) {
        throw UsageException(
            "--unix path is too long for a Unix socket (at most ${MAX_SOCKET_PATH - 1} bytes): " +
                socket.path
        )
    }
    val mode =
        try {
            Files.getAttribute(socket.toPath(), "unix:mode", LinkOption.NOFOLLOW_LINKS) as Int
        } catch (_: NoSuchFileException) {
            return
        } catch (e: UnsupportedOperationException) {
            throw UsageException("${socket.path} exists, and its file type cannot be read", e)
        }
    if (mode and S_IFMT != S_IFSOCK) {
        throw UsageException("${socket.path} exists and is not a socket")
    }
    try {
        SocketChannel.open(StandardProtocolFamily.UNIX).use { probe ->
            probe.connect(UnixDomainSocketAddress.of(socket.toPath()))
        }
    } catch (_: ConnectException) {
        // Nothing listens on it any more.
        Files.deleteIfExists(socket.toPath())
        return
    } catch (e: IOException) {
        throw UsageException("Could not check ${socket.path}: ${e.message.orEmpty()}", e)
    }
    throw UsageException("Another server is listening on ${socket.path}")
}

private const val MAX_SOCKET_PATH = 104
// The file type bits of st_mode, and the type of a socket, which POSIX fixes.
private const val S_IFMT = 0xF000
private const val S_IFSOCK = 0xC000
