package com.fingertipsmcp.wait

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WaitLoopTest {
    @Test
    fun `times out at the deadline`() = runTest {
        val outcome =
            runWait(
                all = listOf(ConditionWatcher { Check(false) }),
                any = emptyList(),
                changes = MutableSharedFlow(),
                deadline = 1_000,
                settleMs = 500,
                now = clock(),
            )
        assertFalse(outcome.matched)
        assertEquals(1_000, currentTime)
    }

    @Test
    fun `looks again on a change`() = runTest {
        var ready = false
        val changes = MutableSharedFlow<Any>()
        launch {
            delay(300)
            ready = true
            changes.emit(Unit)
        }
        val outcome =
            runWait(
                all = listOf(ConditionWatcher { Check(ready) }),
                any = emptyList(),
                changes = changes,
                deadline = 10_000,
                settleMs = 0,
                now = clock(),
            )
        assertTrue(outcome.matched)
        assertEquals(listOf(0), outcome.matchedAll)
        // The change, then the debounce that gathers the burst after it.
        assertEquals(340, currentTime)
    }

    @Test
    fun `settles until changes stop`() = runTest {
        val changes = flow {
            repeat(3) {
                delay(200)
                emit(Unit)
            }
        }
        val outcome =
            runWait(
                all = listOf(ConditionWatcher { Check(true) }),
                any = emptyList(),
                changes = changes,
                deadline = 10_000,
                settleMs = 500,
                now = clock(),
            )
        assertTrue(outcome.matched)
        // The last change is at 600, so it is quiet from 1100.
        assertEquals(1_100, currentTime)
    }

    @Test
    fun `settling ends at the deadline`() = runTest {
        val changes = flow {
            while (true) {
                delay(100)
                emit(Unit)
            }
        }
        val outcome =
            runWait(
                all = emptyList(),
                any = listOf(ConditionWatcher { Check(false) }, ConditionWatcher { Check(true) }),
                changes = changes,
                deadline = 2_000,
                settleMs = 500,
                now = clock(),
            )
        assertTrue(outcome.matched)
        assertEquals(listOf(1), outcome.matchedAny)
        assertEquals(2_000, currentTime)
    }

    @Test
    fun `settles on a given last change`() = runTest {
        // A change source that keeps emitting does not hold the wait back when settling watches
        // something else, as the phone's UI tree.
        val changes = flow {
            while (true) {
                delay(100)
                emit(Unit)
            }
        }
        runWait(
            all = listOf(ConditionWatcher { Check(true) }),
            any = emptyList(),
            changes = changes,
            deadline = 10_000,
            settleMs = 500,
            now = clock(),
            lastChangeMs = { 250 },
        )
        assertEquals(750, currentTime)
    }

    private fun TestScope.clock(): () -> Long = { testScheduler.currentTime }
}
