package net.die.phoneapi.browser

import java.util.PriorityQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.createCoroutine
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Delay
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.coroutineScope

/**
 * A dispatcher on real threads with a virtual clock, for code that does blocking reads on a fake
 * DevTools socket. A test dispatcher runs everything on one thread, so a read that waits for the
 * client's next command would block the client too.
 *
 * Time moves only when nothing can run: every task has finished, or is blocked in a fake socket's
 * read waiting for a command ([park]). Then the earliest timer fires at once. So a call that waits
 * for an answer gets it before any timeout, and one that gets none times out without waiting.
 */
@OptIn(InternalCoroutinesApi::class) // Delay is how a dispatcher gives delay() its clock.
internal class VirtualIo : CoroutineDispatcher(), Delay {
    private val lock = ReentrantLock()
    private val changed = lock.newCondition()
    private val threads: ExecutorService by lazy {
        Executors.newCachedThreadPool { task ->
            Thread(task, "virtual-io").apply { isDaemon = true }
        }
    }
    private val timers = PriorityQueue<Timer>(compareBy<Timer>({ it.at }, { it.order }))
    private var order = 0L
    /** Tasks dispatched and not yet finished, including those parked. */
    private var busy = 0
    /** Tasks blocked in a fake socket's read. */
    private var parked = 0

    /** The virtual time, in ms. */
    @Volatile
    var now = 0L
        private set

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        lock.withLock { busy++ }
        threads.execute {
            try {
                block.run()
            } finally {
                lock.withLock {
                    busy--
                    changed.signalAll()
                }
            }
        }
    }

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

    /** A task is about to block in a read until a command or a close lets it go. */
    fun park() = lock.withLock {
        parked++
        changed.signalAll()
    }

    /** [count] parked tasks have been let go, by whoever let them go, before they wake. */
    fun unpark(count: Int) {
        if (count == 0) return
        lock.withLock { parked -= count }
    }

    /**
     * Runs [block] here, firing timers whenever nothing else can run, and returns its result or
     * throws its failure.
     */
    fun <T> run(block: suspend () -> T): T {
        var outcome: Result<T>? = null
        val body: suspend () -> T = { coroutineScope { block() } }
        // Resuming the new coroutine dispatches it here, like any other task.
        body
            .createCoroutine(
                Continuation(this) { result ->
                    lock.withLock {
                        outcome = result
                        changed.signalAll()
                    }
                }
            )
            .resume(Unit)
        while (true) {
            val next = lock.withLock { settle { outcome != null } } ?: break
            next.action()
        }
        return lock.withLock { checkNotNull(outcome) }.getOrThrow()
    }

    /**
     * Waits, holding [lock], until every task has finished or parked. Then null when [done], or
     * else the next timer, with the clock moved to it.
     */
    private fun settle(done: () -> Boolean): Timer? {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(STUCK_S)
        while (busy > parked) {
            val left = deadline - System.nanoTime()
            check(left > 0) { "Tasks ran for $STUCK_S s without settling" }
            changed.await(left, TimeUnit.NANOSECONDS)
        }
        if (done()) return null
        val timer =
            generateSequence { timers.poll() }.firstOrNull { !it.disposed }
                ?: error("Nothing can run and no timer is set, so the call never ends")
        now = timer.at
        return timer
    }

    private fun schedule(timeMillis: Long, action: () -> Unit): Timer = lock.withLock {
        val timer = Timer(now + timeMillis.coerceAtLeast(0), order++, action)
        timers += timer
        timer
    }

    private class Timer(val at: Long, val order: Long, val action: () -> Unit) : DisposableHandle {
        @Volatile var disposed = false

        override fun dispose() {
            disposed = true
        }
    }

    private companion object {
        /** How long tasks may run in real time between timers: only reached on a failure. */
        const val STUCK_S = 20L
    }
}
