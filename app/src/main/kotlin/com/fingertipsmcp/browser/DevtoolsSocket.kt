package com.fingertipsmcp.browser

import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream

/** A connected DevTools socket. Closing it closes the underlying fd. */
internal interface DevtoolsSocket : Closeable {
    val input: InputStream
    val output: OutputStream

    /**
     * True once a timeout waiting for the peer to answer, rather than the peer, closed the socket.
     */
    val timedOut: Boolean
        get() = false
}
