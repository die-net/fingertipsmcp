package com.fingertipsmcp.lease

import com.fingertipsmcp.model.ApiException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class) // The late check runs on the virtual clock.
class ForegroundWatchTest {
    private val a = Holder.Session("a")
    private val b = Holder.Session("b")
    private var top = "com.launcher"
    private val reported = mutableListOf<String>()

    private data class Watched(val lease: ScreenLease, val watch: ForegroundWatch)

    private fun TestScope.watched(cleanup: suspend () -> Unit = {}): Watched {
        val lease =
            ScreenLease(
                backgroundScope,
                cleanup = {
                    cleanup()
                    emptyList()
                },
                phone = PhoneLock { false },
                now = { testScheduler.currentTime },
            )
        val watch =
            ForegroundWatch(
                lease,
                backgroundScope,
                top = { top },
                bringBack = { top = it },
                report = { holder, pkg -> reported += "${holder.id}:$pkg" },
            )
        return Watched(lease, watch)
    }

    @Test
    fun `holder may launch`() = runTest {
        val (lease, watch) = watched()
        lease.withScreen(a) {}
        watch.watch(a) { top = "com.maps" }
        advanceTimeBy(ForegroundWatch.LATE_MS + 1)
        assertEquals(0, watch.count(a))
    }

    @Test
    fun `free screen goes to culprit`() = runTest {
        val (lease, watch) = watched()
        assertEquals("took_screen", error { watch.watch(b) { top = "com.maps" } })
        assertTrue(lease.holds(b))
        assertEquals("com.maps", top)
        assertEquals(1, watch.count(b))
        assertEquals(listOf("b:com.maps"), reported)
    }

    @Test
    fun `holder's app comes back`() = runTest {
        val (lease, watch) = watched()
        lease.withScreen(a) {}
        top = "com.app"
        assertEquals("took_screen", error { watch.watch(b) { top = "com.maps" } })
        assertEquals("com.app", top)
        assertTrue(lease.holds(a))
        val notes = lease.withScreen(a) {}.notes
        assertTrue(notes.single().contains("com.maps came forward briefly"))
    }

    @Test
    fun `screen calls are not blamed`() = runTest {
        val (lease, watch) = watched()
        lease.withScreen(a) {}
        val launch = CompletableDeferred<Unit>()
        val going = CompletableDeferred<Unit>()
        val call = async {
            lease.withScreen(a) {
                launch.await()
                top = "com.maps"
                going.await()
            }
        }
        runCurrent()
        // The holder's call launches an app while b's call runs.
        watch.watch(b) {
            launch.complete(Unit)
            yield()
        }
        assertEquals("com.maps", top)
        // The late check finds the holder's call still running too.
        advanceTimeBy(ForegroundWatch.LATE_MS + 1)
        assertEquals(0, watch.count(b))
        going.complete(Unit)
        call.await()
    }

    @Test
    fun `a call ending midway is spared`() = runTest {
        val (lease, watch) = watched()
        lease.withScreen(a) {}
        val launch = CompletableDeferred<Unit>()
        val call = async {
            lease.withScreen(a) {
                launch.await()
                top = "com.maps"
            }
        }
        runCurrent()
        // The holder's call started before b's and ends within it, so no call is running at the
        // end, and none started during it.
        watch.watch(b) {
            launch.complete(Unit)
            call.await()
        }
        advanceTimeBy(ForegroundWatch.LATE_MS + 1)
        assertEquals("com.maps", top)
        assertEquals(0, watch.count(b))
    }

    @Test
    fun `cleanup ending is not blamed`() = runTest {
        val home = CompletableDeferred<Unit>()
        val (lease, watch) =
            watched {
                home.await()
                top = "com.launcher"
            }
        lease.withScreen(a) { top = CHROME }
        // Ending a session returns with its cleanup still running, as DELETE does.
        lease.forget(a)
        runCurrent()
        watch.watch(b) {
            home.complete(Unit)
            runCurrent()
        }
        advanceTimeBy(ForegroundWatch.LATE_MS + 1)
        assertEquals("com.launcher", top)
        assertEquals(0, watch.count(b))
        assertEquals(emptyList<String>(), reported)
        assertTrue(!lease.holds(b))
    }

    @Test
    fun `running cleanup is not blamed`() = runTest {
        val finish = CompletableDeferred<Unit>()
        val (lease, watch) =
            watched {
                top = "com.launcher"
                finish.await()
            }
        lease.withScreen(a) { top = CHROME }
        lease.forget(a)
        watch.watch(b) { runCurrent() }
        assertEquals("com.launcher", top)
        assertEquals(0, watch.count(b))
        finish.complete(Unit)
    }

    @Test
    fun `cleanup is not a late launch`() = runTest {
        val (lease, watch) = watched { top = "com.launcher" }
        lease.withScreen(a) { top = CHROME }
        watch.watch(b) {}
        lease.forget(a)
        advanceTimeBy(ForegroundWatch.LATE_MS + 1)
        assertEquals("com.launcher", top)
        assertEquals(0, watch.count(b))
    }

    @Test
    fun `a launch after cleanup counts`() = runTest {
        val (lease, watch) = watched { top = "com.launcher" }
        lease.withScreen(a) { top = CHROME }
        lease.forget(a)
        runCurrent()
        assertEquals("took_screen", error { watch.watch(b) { top = "com.maps" } })
        assertEquals(1, watch.count(b))
    }

    @Test
    fun `late launches are noted`() = runTest {
        val (lease, watch) = watched()
        watch.watch(b) {}
        top = "com.maps"
        advanceTimeBy(ForegroundWatch.LATE_MS - 1)
        assertEquals(0, watch.count(b))
        advanceTimeBy(2)
        assertEquals(1, watch.count(b))
        assertTrue(lease.holds(b))
        val notes = lease.withScreen(b) {}.notes
        assertTrue(notes.single().startsWith("took_screen: com.maps came forward just after"))
    }

    private suspend fun error(block: suspend () -> Unit): String =
        try {
            block()
            "no error"
        } catch (e: ApiException) {
            e.error
        }

    private companion object {
        const val CHROME = "com.android.chrome"
    }
}
