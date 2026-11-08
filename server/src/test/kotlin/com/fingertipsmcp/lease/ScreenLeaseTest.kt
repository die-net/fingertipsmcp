package com.fingertipsmcp.lease

import com.fingertipsmcp.model.ApiException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ScreenLeaseTest {
    private val a = Holder.Session("a")
    private val b = Holder.Session("b")
    private val c = Holder.Session("c")
    private val person = Holder.Viewer(1)

    private var cleanups = 0
    private var failures = emptyList<String>()
    /** Whether each cleanup was told to lock, in order. */
    private val locks = mutableListOf<Boolean>()
    /** What each screen call and ready did, in order. */
    private val events = mutableListOf<String>()
    /** What ready returns, which is the steps that failed. */
    private var readyFails = emptyList<String>()
    /** The keyguard the fake phone shows. A cleanup that locks puts it up. */
    private var locked = true

    private fun TestScope.lease(scope: CoroutineScope = backgroundScope) =
        ScreenLease(
            scope = scope,
            cleanup = { lock ->
                cleanups++
                locks += lock
                if (lock) locked = true
                failures
            },
            phone = PhoneLock { locked },
            now = { testScheduler.currentTime },
            ready = {
                events += "ready"
                readyFails
            },
        )

    private fun readies() = events.count { it == "ready" }

    @Test
    fun `session keeps the screen`() = runTest {
        val lease = lease()
        lease.withScreen(a) {}
        assertTrue(lease.holds(a))
        val waiting = async { lease.withScreen(b) { "b ran" } }
        runCurrent()
        assertFalse(waiting.isCompleted)
        lease.release(a)
        assertEquals("b ran", waiting.await().value)
        assertEquals(1, cleanups)
        assertTrue(lease.holds(b))
    }

    @Test
    fun `failed cleanup is reported`() = runTest {
        val lease =
            ScreenLease(
                scope = backgroundScope,
                cleanup = { listOf("no blank tab") },
                now = { testScheduler.currentTime },
            )
        lease.withScreen(a) {}
        lease.release(a)
        runCurrent()
        val notes = lease.withScreen(b) {}.notes
        assertTrue(notes.single().contains("no blank tab"), notes.toString())
    }

    @Test
    fun `between calls waits its turn`() = runTest {
        val lease = lease()
        val going = CompletableDeferred<Unit>()
        val call = async { lease.withScreen(a) { going.await() } }
        runCurrent()
        val between = async { lease.betweenCalls { "fixed" } }
        runCurrent()
        assertFalse(between.isCompleted)
        going.complete(Unit)
        call.await()
        assertEquals("fixed", between.await())
    }

    @Test
    fun `calls run while between waits`() = runTest {
        // Waiting for a running call must not hold off the holder's next one, which may be the
        // very call the running one waits for.
        val lease = lease()
        val going = CompletableDeferred<Unit>()
        val call = async { lease.withScreen(a) { going.await() } }
        runCurrent()
        val between = async { lease.betweenCalls { "fixed" } }
        runCurrent()
        assertEquals("next", lease.withScreen(a) { "next" }.value)
        going.complete(Unit)
        call.await()
        assertEquals("fixed", between.await())
    }

    @Test
    fun `between calls gives up`() = runTest {
        val lease = lease()
        val going = CompletableDeferred<Unit>()
        val call = async { lease.withScreen(a) { going.await() } }
        runCurrent()
        var ran = false
        val between = async { lease.betweenCalls { ran = true } }
        advanceTimeBy(ScreenLease.BETWEEN_WAIT_MS + 1)
        assertNull(between.await())
        assertFalse(ran)
        going.complete(Unit)
        call.await()
        // Nothing was left paused.
        assertEquals("after", lease.withScreen(a) { "after" }.value)
    }

    @Test
    fun `calls wait for a between block`() = runTest {
        val lease = lease()
        val fixing = CompletableDeferred<Unit>()
        val between = async { lease.betweenCalls { fixing.await() } }
        runCurrent()
        val call = async { lease.withScreen(a) { "ran" } }
        runCurrent()
        assertFalse(call.isCompleted)
        fixing.complete(Unit)
        between.await()
        assertEquals("ran", call.await().value)
    }

    @Test
    fun `waiting calls all get it`() = runTest {
        val lease = lease()
        lease.withScreen(b) {}
        val first = async { lease.withScreen(a) { "first" } }
        val second = async { lease.withScreen(a) { "second" } }
        runCurrent()
        lease.release(b)
        runCurrent()
        assertEquals("first", first.await().value)
        assertEquals("second", second.await().value)
        assertTrue(lease.holds(a))
    }

    @Test
    fun `one waiting call can leave`() = runTest {
        val lease = lease()
        lease.withScreen(b) {}
        val first = async { lease.withScreen(a) { "first" } }
        val second = async { lease.withScreen(a) { "second" } }
        runCurrent()
        first.cancel()
        runCurrent()
        lease.release(b)
        assertEquals("second", second.await().value)
        assertTrue(lease.holds(a))
    }

    @Test
    fun `an ended session's call fails`() = runTest {
        val lease = lease()
        lease.withScreen(b) {}
        val waiting = async { apiError { lease.withScreen(a) {} } }
        runCurrent()
        lease.forget(a)
        assertEquals("session_not_found", waiting.await().error)
        assertTrue(lease.holds(b))
    }

    @Test
    fun `take over stops cleanup`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val lease =
            ScreenLease(
                scope = backgroundScope,
                cleanup = { lock ->
                    locks += lock
                    gate.await()
                    if (lock) locked = true
                    emptyList()
                },
                phone = PhoneLock { locked },
                now = { testScheduler.currentTime },
            )
        lease.withScreen(a) { locked = false }
        lease.release(a)
        runCurrent()
        assertEquals(listOf(true), locks)
        lease.takeOver(person)
        gate.complete(Unit)
        runCurrent()
        // The cleanup was cut off, so it neither locked the phone nor handed the screen on.
        assertFalse(locked)
        assertTrue(lease.holds(person))
        val waiting = async { lease.withScreen(b) { "b" } }
        runCurrent()
        assertFalse(waiting.isCompleted)
        lease.release(person)
        val next = waiting.await()
        assertTrue(next.notes.single().contains("A person used the screen"), next.notes.single())
        assertEquals(listOf(true), locks)
    }

    @Test
    fun `idle holder yields to waiter`() = runTest {
        val lease = lease()
        lease.withScreen(a) {}
        // The grace counts from the holder's last screen call.
        val waiting = async { lease.withScreen(b) { "b ran" } }
        advanceTimeBy(ScreenLease.GRACE_MS - 1)
        assertFalse(waiting.isCompleted)
        advanceTimeBy(2)
        runCurrent()
        assertEquals("b ran", waiting.await().value)
        assertEquals(1, cleanups)
        assertTrue(lease.holds(b))
        lease.release(b)
        val notes = lease.withScreen(a) {}.notes
        assertTrue(notes.any { it.contains("went to another client") }, notes.toString())
    }

    @Test
    fun `next holder hears of cleanup`() = runTest {
        val lease = lease()
        lease.withScreen(a) {}
        lease.release(a)
        runCurrent()
        val notes = lease.withScreen(b) {}.notes
        assertTrue(notes.single().contains("cleaned up after the last holder"), notes.toString())
    }

    @Test
    fun `long waits are noted`() = runTest {
        val lease = lease()
        lease.withScreen(a) {}
        val waiting = async { lease.withScreen(b) {} }
        advanceTimeBy(5_000)
        lease.release(a)
        runCurrent()
        val notes = waiting.await().notes
        assertTrue(notes.any { it.contains("waited 5 s") }, notes.toString())
    }

    @Test
    fun `active holder keeps screen`() = runTest {
        val lease = lease()
        lease.withScreen(a) {}
        val waiting = async { apiError { lease.withScreen(b) {} } }
        repeat(6) {
            advanceTimeBy(ScreenLease.GRACE_MS / 2)
            lease.withScreen(a) {}
        }
        assertEquals("screen_busy", waiting.await().error)
        assertTrue(lease.holds(a))
        assertEquals(0, cleanups)
    }

    @Test
    fun `no waiter, no yield`() = runTest {
        val lease = lease()
        lease.withScreen(a) {}
        advanceTimeBy(10 * ScreenLease.GRACE_MS)
        assertTrue(lease.holds(a))
        assertEquals(0, cleanups)
    }

    @Test
    fun `wait ends in screen busy`() = runTest {
        val lease = lease()
        // A holder in a long call keeps the screen, however long it runs.
        val going = CompletableDeferred<Unit>()
        val holding = async { lease.withScreen(a) { going.await() } }
        advanceTimeBy(5_000)
        val waiting = async { apiError { lease.withScreen(b) {} } }
        advanceTimeBy(ScreenLease.WAIT_MS + 1)
        val error = waiting.await()
        assertEquals("screen_busy", error.error)
        assertTrue(error.message.orEmpty().contains("65 s"))
        going.complete(Unit)
        holding.await()
        lease.release(a)
        runCurrent()
        assertNull(lease.state.value.holder)
    }

    @Test
    fun `canceled waiter leaves queue`() = runTest {
        val lease = lease()
        lease.withScreen(a) {}
        val order = mutableListOf<String>()
        val second = async { lease.withScreen(b) { order += "b" } }
        runCurrent()
        val third = async { lease.withScreen(c) { order += "c" } }
        runCurrent()
        second.cancel()
        runCurrent()
        lease.release(a)
        third.await()
        assertEquals(listOf("c"), order)
        assertTrue(lease.holds(c))
    }

    @Test
    fun `failed cleanup reaches next`() = runTest {
        val lease = lease()
        failures = listOf("lock: no keyguard")
        lease.withScreen(a) {}
        lease.release(a)
        runCurrent()
        val first = lease.withScreen(b) {}
        assertEquals(1, first.notes.size)
        assertTrue(first.notes.single().contains("lock: no keyguard"))
        assertEquals(emptyList<String>(), lease.withScreen(b) {}.notes)
    }

    @Test
    fun `viewer input takes free screen`() = runTest {
        val lease = lease()
        assertTrue(lease.viewerInput(person))
        assertTrue(lease.holds(person))
        lease.release(person)
        runCurrent()
        lease.withScreen(a) {}
        assertFalse(lease.viewerInput(person))
        assertTrue(lease.holds(a))
    }

    @Test
    fun `take over and hand back`() = runTest {
        val lease = lease()
        lease.withScreen(a) {}
        val started = CompletableDeferred<Unit>()
        val call = async {
            apiError {
                lease.withScreen(a) {
                    started.complete(Unit)
                    delay(10_000)
                }
            }
        }
        started.await()
        advanceTimeBy(1_000)
        lease.takeOver(person)
        assertEquals("screen_taken", call.await().error)
        val again = apiError { lease.withScreen(a) {} }
        assertEquals("screen_taken", again.error)

        advanceTimeBy(30_000)
        lease.release(person)
        assertTrue(lease.holds(a))
        val back = lease.withScreen(a) { "ran" }
        assertEquals("ran", back.value)
        assertTrue(back.notes.single().contains("30 s"))
        assertEquals(0, cleanups)
    }

    @Test
    fun `hand back skips ended session`() = runTest {
        val lease = lease()
        lease.withScreen(a) {}
        lease.takeOver(person)
        lease.forget(a)
        lease.release(person)
        runCurrent()
        assertNull(lease.state.value.holder)
        assertEquals(0, cleanups)
    }

    @Test
    fun `a person's screen is kept`() = runTest {
        val lease = lease()
        assertTrue(lease.viewerInput(person))
        lease.forget(person)
        val next = lease.withScreen(a) {}
        assertEquals(0, cleanups)
        assertTrue(next.notes.single().contains("A person used the screen"), next.notes.single())
    }

    @Test
    fun `ready runs before first call`() = runTest {
        val lease = lease()
        lease.withScreen(a) { events += "a" }
        lease.withScreen(a) { events += "a" }
        assertEquals(listOf("ready", "a", "a"), events)
        lease.release(a)
        runCurrent()
        lease.withScreen(a) {}
        assertEquals(2, readies())
    }

    @Test
    fun `a granted waiter readies`() = runTest {
        val lease = lease()
        lease.withScreen(a) {}
        val waiting = async { lease.withScreen(b) { events += "b" } }
        runCurrent()
        lease.release(a)
        waiting.await()
        assertEquals(listOf("ready", "ready", "b"), events)
    }

    @Test
    fun `a person does not ready`() = runTest {
        val lease = lease()
        assertTrue(lease.viewerInput(person))
        lease.release(person)
        runCurrent()
        assertEquals(0, readies())
        lease.withScreen(a) {}
        assertEquals(1, readies())
    }

    @Test
    fun `a hand back readies again`() = runTest {
        val lease = lease()
        lease.withScreen(a) {}
        lease.takeOver(person)
        lease.release(person)
        assertTrue(lease.holds(a))
        lease.withScreen(a) { events += "a" }
        assertEquals(listOf("ready", "ready", "a"), events)
    }

    @Test
    fun `a claim readies on next call`() = runTest {
        val lease = lease()
        assertTrue(lease.claim(a))
        assertEquals(0, readies())
        lease.withScreen(a) {}
        assertEquals(1, readies())
    }

    @Test
    fun `other calls wait for ready`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val lease =
            ScreenLease(
                scope = backgroundScope,
                cleanup = { emptyList() },
                phone = PhoneLock { true },
                now = { testScheduler.currentTime },
                ready = {
                    gate.await()
                    events += "ready"
                    emptyList()
                },
            )
        val first = async { lease.withScreen(a) { events += "first" } }
        runCurrent()
        val second = async { lease.withScreen(a) { events += "second" } }
        runCurrent()
        assertEquals(emptyList<String>(), events)
        gate.complete(Unit)
        first.await()
        second.await()
        assertEquals(listOf("ready", "first", "second"), events)
    }

    @Test
    fun `a failed ready retries`() = runTest {
        val lease = lease()
        readyFails = listOf("clear the clipboard: no helper")
        val failed = apiError {
            lease.withScreen(a) { throw ApiException.helperUnavailable("down") }
        }
        assertEquals("helper_unavailable", failed.error)
        val next = lease.withScreen(a) {}
        assertTrue(next.notes.single().contains("no helper"), next.notes.single())
        readyFails = emptyList()
        assertEquals(emptyList<String>(), lease.withScreen(a) {}.notes)
        lease.withScreen(a) {}
        assertEquals(3, readies())
    }

    @Test
    fun `release as is`() = runTest {
        val lease = lease()
        lease.withScreen(a) {}
        val waiting = async { lease.withScreen(b) {} }
        runCurrent()
        lease.releaseAsIs(a)
        val next = waiting.await()
        assertEquals(0, cleanups)
        assertTrue(next.notes.single().contains("as it was"), next.notes.single())
    }

    @Test
    fun `a waiter gets it unlocked`() = runTest {
        val lease = lease()
        lease.withScreen(a) { locked = false }
        val waiting = async { lease.withScreen(b) {} }
        runCurrent()
        lease.release(a)
        val notes = waiting.await().notes
        assertEquals(listOf(false), locks)
        assertTrue(notes.single().contains("not locked"), notes.toString())
        // b found it unlocked, but a's unlock is still owed a lock once no one waits.
        lease.release(b)
        runCurrent()
        assertEquals(listOf(false, true), locks)
        assertTrue(locked)
    }

    @Test
    fun `an owner's unlock is kept`() = runTest {
        locked = false
        val lease = lease()
        lease.withScreen(a) {}
        lease.release(a)
        runCurrent()
        assertEquals(listOf(false), locks)
        assertFalse(locked)
    }

    @Test
    fun `a gone waiter still locks`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val lease =
            ScreenLease(
                scope = backgroundScope,
                cleanup = { lock ->
                    locks += lock
                    if (!lock) gate.await()
                    if (lock) locked = true
                    emptyList()
                },
                phone = PhoneLock { locked },
                now = { testScheduler.currentTime },
            )
        lease.withScreen(a) { locked = false }
        val waiting = async { lease.withScreen(b) {} }
        runCurrent()
        lease.release(a)
        runCurrent()
        waiting.cancel()
        runCurrent()
        gate.complete(Unit)
        runCurrent()
        assertEquals(listOf(false, true), locks)
        assertTrue(locked)
        assertNull(lease.state.value.holder)
    }

    @Test
    fun `waiters wait for cleanup`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val lease =
            ScreenLease(
                scope = backgroundScope,
                cleanup = {
                    gate.await()
                    emptyList()
                },
                phone = PhoneLock { true },
                now = { testScheduler.currentTime },
            )
        lease.withScreen(a) {}
        lease.release(a)
        val waiting = async { lease.withScreen(b) { "b" } }
        runCurrent()
        assertFalse(waiting.isCompleted)
        assertFalse(lease.viewerInput(person))
        gate.complete(Unit)
        assertEquals("b", waiting.await().value)
    }

    @Test
    fun `a brief call gives it back`() = runTest {
        val lease = lease()
        val waiting = async { lease.withScreen(b) { "b ran" } }
        lease.withScreen(a, giveBack = true) {}
        runCurrent()
        assertEquals(1, cleanups)
        assertEquals("b ran", waiting.await().value)
        assertTrue(lease.holds(b))
    }

    @Test
    fun `a failed brief call too`() = runTest {
        val lease = lease()
        val error = apiError {
            lease.withScreen(a, giveBack = true) {
                throw ApiException.unavailable("browser_unavailable", "no tab")
            }
        }
        runCurrent()
        assertEquals("browser_unavailable", error.error)
        assertNull(lease.state.value.holder)
        assertEquals(1, cleanups)
    }

    @Test
    fun `a brief call keeps a held one`() = runTest {
        val lease = lease()
        lease.withScreen(a) {}
        lease.withScreen(a, giveBack = true) {}
        runCurrent()
        assertTrue(lease.holds(a))
        assertEquals(0, cleanups)
    }

    @Test
    fun `a running call keeps it`() = runTest {
        // The session's other screen call, still running when the brief one ends, keeps the screen.
        val lease = lease()
        val briefDone = CompletableDeferred<Unit>()
        val otherDone = CompletableDeferred<Unit>()
        val brief = async { lease.withScreen(a, giveBack = true) { briefDone.await() } }
        runCurrent()
        val other = async { lease.withScreen(a) { otherDone.await() } }
        runCurrent()
        briefDone.complete(Unit)
        brief.await()
        otherDone.complete(Unit)
        other.await()
        runCurrent()
        assertTrue(lease.holds(a))
        assertEquals(0, cleanups)
    }

    private suspend fun apiError(block: suspend () -> Unit): ApiException =
        try {
            block()
            error("expected an ApiException")
        } catch (e: ApiException) {
            e
        }
}
