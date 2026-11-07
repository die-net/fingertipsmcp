package net.die.phoneapi.helper.tree

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Window refreshes on a thread pool. The window query can be slow, and two refreshes at once used
 * to race over the pending window changes and crash the helper.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SerialDebouncerTest {
    @Test
    fun `runs on a pool never overlap`() {
        val pool = Executors.newFixedThreadPool(2).asCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob())
        val running = AtomicInteger()
        val overlapped = AtomicBoolean()
        val firstDone = AtomicBoolean()
        val secondSawFirstDone = AtomicBoolean()
        val firstStarted = CountDownLatch(1)
        val secondRequested = CountDownLatch(1)
        val secondStarted = CountDownLatch(1)
        val runs = AtomicInteger()
        val debouncer =
            SerialDebouncer(scope, pool, DELAY_MS) {
                if (running.incrementAndGet() > 1) overlapped.set(true)
                when (runs.incrementAndGet()) {
                    1 -> {
                        firstStarted.countDown()
                        secondRequested.await(SAFETY_S, TimeUnit.SECONDS)
                        // A debouncer that overlaps would start the second run DELAY_MS after the
                        // request, while this one still holds. The free pool thread lets it.
                        secondStarted.await(HOLD_MS, TimeUnit.MILLISECONDS)
                        firstDone.set(true)
                    }
                    2 -> {
                        secondSawFirstDone.set(firstDone.get())
                        secondStarted.countDown()
                    }
                }
                running.decrementAndGet()
            }
        try {
            debouncer.request()
            assertTrue(firstStarted.await(SAFETY_S, TimeUnit.SECONDS))
            debouncer.request()
            secondRequested.countDown()
            assertTrue(secondStarted.await(SAFETY_S, TimeUnit.SECONDS))
            assertTrue(secondSawFirstDone.get())
            assertFalse(overlapped.get())
        } finally {
            secondRequested.countDown()
            scope.cancel()
            pool.close()
        }
    }

    @Test
    fun `a request mid-run runs once`() = runTest {
        var runs = 0
        lateinit var debouncer: SerialDebouncer
        debouncer =
            SerialDebouncer(backgroundScope, StandardTestDispatcher(testScheduler), DELAY_MS) {
                runs++
                if (runs == 1) {
                    debouncer.request()
                    debouncer.request()
                }
            }
        debouncer.request()
        runCurrent()
        advanceTimeBy(DELAY_MS * 10)
        runCurrent()
        assertEquals(2, runs)
    }

    @Test
    fun `requests in the delay join`() = runTest {
        var runs = 0
        val debouncer =
            SerialDebouncer(backgroundScope, StandardTestDispatcher(testScheduler), DELAY_MS) {
                runs++
            }
        debouncer.request()
        runCurrent()
        advanceTimeBy(DELAY_MS / 2)
        debouncer.request()
        debouncer.request()
        advanceTimeBy(DELAY_MS * 10)
        runCurrent()
        assertEquals(1, runs)
    }

    private companion object {
        const val DELAY_MS = 30L

        /**
         * How long the first run holds, ten times the delay, so an overlapping run has time to
         * start. The counts are checked in virtual time; only overlap needs real threads.
         */
        const val HOLD_MS = DELAY_MS * 10

        /** Fires only when a run never comes. */
        const val SAFETY_S = 5L
    }
}
