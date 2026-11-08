package com.fingertipsmcp.browser

import kotlin.coroutines.CoroutineContext

/**
 * Android's endpoints over sockets from [open], which a test scripts, with [listSockets] for the
 * helper's list and the WebSocket key [key].
 */
internal fun testEndpoints(
    io: CoroutineContext,
    listSockets: suspend () -> String = { "[]" },
    key: () -> String = ::websocketKey,
    targetTimeoutMs: Long = 5_000L,
    open: suspend (String, Long) -> DevtoolsSocket,
): SocketEndpoints = SocketEndpoints(io, listSockets, open, key, targetTimeoutMs = targetTimeoutMs)
