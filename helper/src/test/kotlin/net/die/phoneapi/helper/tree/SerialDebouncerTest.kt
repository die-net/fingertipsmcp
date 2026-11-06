package net.die.phoneapi.helper.tree

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Window refreshes on a thread pool. The window query can be slow, and two refreshes at once used
 * to race over the pending window changes and crash the helper.
 */
class SerialDebouncerTest {
    @Test
    fun `runs wait for each other`() {
        val pool = Executors.newFixedThreadPool(2).asCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob())
        val running = AtomicInteger()
        val overlapped = AtomicBoolean()
        val runs = AtomicInteger()
        val started = LinkedBlockingQueue<Int>()
        val release = CountDownLatch(1)
        val debouncer =
            SerialDebouncer(scope, pool, DELAY_MS) {
                if (running.incrementAndGet() > 1) overlapped.set(true)
                val run = runs.incrementAndGet()
                started.put(run)
                if (run == 1) release.await(TIMEOUT_S, TimeUnit.SECONDS)
                running.decrementAndGet()
            }
        try {
            debouncer.request()
            assertEquals(1, started.poll(TIMEOUT_S, TimeUnit.SECONDS))
            debouncer.request()
            assertNull(started.poll(DELAY_MS * 10, TimeUnit.MILLISECONDS))
            release.countDown()
            assertEquals(2, started.poll(TIMEOUT_S, TimeUnit.SECONDS))
            assertNull(started.poll(DELAY_MS * 10, TimeUnit.MILLISECONDS))
            assertFalse(overlapped.get())
        } finally {
            release.countDown()
            scope.cancel()
            pool.close()
        }
    }

    private companion object {
        /**
         * Short, since each check for no run waits ten of these. A slow machine can only make such
         * a check miss a bug, not fail. Overlap between threads needs a real pool to show.
         */
        const val DELAY_MS = 1L
        const val TIMEOUT_S = 5L
    }
}
