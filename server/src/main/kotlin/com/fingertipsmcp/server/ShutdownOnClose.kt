package com.fingertipsmcp.server

import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.InternalAPI
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A connection's write side: [base], which then calls [shutdown] once when it closes, so the client
 * sees the end of the stream as it would from a TCP socket. Ktor's channel over an `OutputStream`
 * only marks itself closed. After a WebSocket ends, CIO reads until the client hangs up, and a
 * client following RFC 6455 waits for the server to close first, so without this both wait.
 */
@OptIn(InternalAPI::class)
class ShutdownOnClose(
    private val base: ByteWriteChannel,
    private val shutdown: () -> Unit,
) : ByteWriteChannel by base {
    private val done = AtomicBoolean()

    override suspend fun flushAndClose() {
        try {
            base.flushAndClose()
        } finally {
            shutdownOnce()
        }
    }

    override fun cancel(cause: Throwable?) {
        base.cancel(cause)
        shutdownOnce()
    }

    private fun shutdownOnce() {
        if (done.compareAndSet(false, true)) shutdown()
    }
}
