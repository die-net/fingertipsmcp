package com.fingertipsmcp.helper.tree

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Runs [block] on [dispatcher] [delayMs] after a [request], one run at a time. Requests made during
 * the delay join that run. A request made while [block] runs gets one more run after it, so runs
 * never overlap and none is lost.
 */
internal class SerialDebouncer(
    scope: CoroutineScope,
    dispatcher: CoroutineDispatcher,
    private val delayMs: Long,
    private val block: () -> Unit,
) {
    private val requests = Channel<Unit>(Channel.CONFLATED)

    init {
        scope.launch(dispatcher) {
            while (true) {
                requests.receive()
                delay(delayMs)
                requests.tryReceive()
                block()
            }
        }
    }

    fun request() {
        requests.trySend(Unit)
    }
}
