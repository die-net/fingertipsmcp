package net.die.phoneapi.lease

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * How [ScreenLease.betweenCalls] blocks, screen calls, and cleanup keep out of each other's way.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BetweenCallsTest {
    private val a = Holder.Session("a")
    /** What each screen call, block, and cleanup did, in order. */
    private val events = mutableListOf<String>()

    /** A lease whose cleanup waits for [home], then records that it ran. */
    private fun TestScope.gated(home: CompletableDeferred<Unit>) =
        ScreenLease(
            scope = backgroundScope,
            cleanup = {
                home.await()
                events += "cleaned"
                emptyList()
            },
            lockedNow = { false },
            now = { testScheduler.currentTime },
        )

    @Test
    fun `waits for cleanup`() = runTest {
        val home = CompletableDeferred<Unit>()
        val lease = gated(home)
        lease.withScreen(a) {}
        lease.release(a)
        runCurrent()
        val between = async { lease.betweenCalls { events += "between" } }
        runCurrent()
        assertEquals(emptyList<String>(), events)
        home.complete(Unit)
        between.await()
        assertEquals(listOf("cleaned", "between"), events)
    }

    @Test
    fun `blocks run together`() = runTest {
        val lease = gated(CompletableDeferred(Unit))
        val gate = CompletableDeferred<Unit>()
        val first = async { lease.betweenCalls { gate.await() } }
        val second = async { lease.betweenCalls { events += "second" } }
        runCurrent()
        assertEquals(listOf("second"), events)
        second.await()
        gate.complete(Unit)
        first.await()
    }

    @Test
    fun `waiting calls hold off blocks`() = runTest {
        val lease = gated(CompletableDeferred(Unit))
        val gate = CompletableDeferred<Unit>()
        val first = async { lease.betweenCalls { gate.await() } }
        runCurrent()
        val call = async { lease.withScreen(a) { events += "call" } }
        runCurrent()
        val second = async { lease.betweenCalls { events += "second" } }
        runCurrent()
        assertEquals(emptyList<String>(), events)
        gate.complete(Unit)
        first.await()
        call.await()
        second.await()
        assertEquals(listOf("call", "second"), events)
    }

    @Test
    fun `cleanup waits for a block`() = runTest {
        val home = CompletableDeferred<Unit>()
        home.complete(Unit)
        val lease = gated(home)
        lease.withScreen(a) {}
        val selected = CompletableDeferred<Unit>()
        val between = async {
            lease.betweenCalls {
                selected.await()
                events += "between"
            }
        }
        runCurrent()
        lease.release(a)
        runCurrent()
        assertEquals(emptyList<String>(), events)
        selected.complete(Unit)
        between.await()
        runCurrent()
        assertEquals(listOf("between", "cleaned"), events)
    }
}
