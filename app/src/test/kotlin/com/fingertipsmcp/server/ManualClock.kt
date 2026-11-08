package com.fingertipsmcp.server

import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update

/**
 * A dispatcher that runs tasks at once on real threads, and whose delays end only when a test calls
 * [advance]. It is for code behind a test server, whose requests run on threads the test does not
 * own, so a test dispatcher could not run them and time cannot wait for them to settle. A test
 * knows a wait has begun by [awaitTimer].
 */
// Delay is how a dispatcher gives delay() its clock. The tasks have no injected dispatcher to use.
@OptIn(InternalCoroutinesApi::class)
@Suppress("InjectDispatcher")
internal class ManualClock : CoroutineDispatcher(), Delay {
    private inner class Timer(val at: Long, val fire: () -> Unit) : DisposableHandle {
        override fun dispose() = timers.update { it - this }
    }

    private val timers = MutableStateFlow(emptyList<Timer>())

    /** The time in ms. */
    @Volatile
    var now = 0L
        private set

    override fun dispatch(context: CoroutineContext, block: Runnable) =
        Dispatchers.Default.dispatch(context, block)

    override fun scheduleResumeAfterDelay(
        timeMillis: Long,
        continuation: CancellableContinuation<Unit>,
    ) {
        val timer = schedule(timeMillis) { continuation.resume(Unit) }
        continuation.invokeOnCancellation { timer.dispose() }
    }

    override fun invokeOnTimeout(
        timeMillis: Long,
        block: Runnable,
        context: CoroutineContext,
    ): DisposableHandle = schedule(timeMillis) { dispatch(context, block) }

    /** Waits until something has a delay of [ms] from now running. */
    suspend fun awaitTimer(ms: Long) {
        val at = now + ms
        timers.first { set -> set.any { it.at == at } }
    }

    /** Moves the time on by [ms], ending in order every delay that runs out by then. */
    fun advance(ms: Long) {
        val end = now + ms
        while (true) {
            val next = timers.value.filter { it.at <= end }.minByOrNull { it.at } ?: break
            next.dispose()
            now = next.at
            next.fire()
        }
        now = end
    }

    private fun schedule(ms: Long, fire: () -> Unit): Timer {
        val timer = Timer(now + ms.coerceAtLeast(0), fire)
        timers.update { it + timer }
        return timer
    }
}
