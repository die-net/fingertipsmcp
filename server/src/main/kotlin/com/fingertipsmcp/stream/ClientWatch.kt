package com.fingertipsmcp.stream

import io.ktor.server.websocket.DefaultWebSocketServerSession
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * Runs [send] until it returns or the client goes away. A sender waiting for its next frame never
 * touches the socket, so without watching the incoming side a viewer that left while the screen was
 * still would stay attached, holding the encoder and the screen-on lease.
 */
suspend fun sendUntilClientLeaves(
    session: DefaultWebSocketServerSession,
    send: suspend () -> Unit,
) {
    coroutineScope {
        val sender = launch { send() }
        val watcher = launch {
            // Anything a viewer sends is ignored. A failed receive means the socket closed.
            do {
                val next = session.incoming.receiveCatching()
            } while (next.isSuccess)
            sender.cancel()
        }
        sender.join()
        watcher.cancel()
    }
}
