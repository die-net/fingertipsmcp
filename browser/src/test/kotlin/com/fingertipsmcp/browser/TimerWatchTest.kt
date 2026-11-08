package com.fingertipsmcp.browser

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The background check of whether a tab's timers run, in virtual time at its real lengths. */
@Suppress("MissingUseCall") // Each check closes its own socket.
class TimerWatchTest {
    private val harness = BrowserServiceTest()
    private val clock = harness.clock
    private var timerFires = true
    private val started = AtomicInteger()
    private val ended = AtomicInteger()

    /** A watch whose checks get [replies] after the handshake, or the default timer replies. */
    private fun watch(
        replies: () -> ByteArray = { world() + if (timerFires) fired() else byteArrayOf() }
    ) =
        TimerWatch(
            CoroutineScope(clock),
            CHROME_SOCKET,
            onPage = { _, block ->
                started.incrementAndGet()
                val page = harness.socket(harness.switched() + replies())
                try {
                    CdpSocketSession(page.input, page.output, clock) { BrowserServiceTest.KEY }
                        .use { cdp ->
                            cdp.open("/devtools/page/t")
                            block(cdp)
                        }
                } finally {
                    page.close()
                    ended.incrementAndGet()
                }
            },
            now = { clock.now },
        )

    @Test
    fun `running timers are fine`() = clock.run {
        val watch = watch()
        watch.arm("t")
        delay(TimerWatch.STALL_MS * 3)
        assertEquals(1, ended.get(), "the check ran to the end")
        assertFalse(watch.stalled("t"))
    }

    @Test
    fun `stopped timers show`() = clock.run {
        timerFires = false
        val watch = watch()
        watch.arm("t")
        delay(1)
        assertFalse(watch.stalled("t"), "a check that has just set its timer is not late")
        delay(TimerWatch.STALL_MS)
        // Overdue while the check still waits.
        assertTrue(watch.stalled("t"))
        assertEquals(0, ended.get())
        delay(TimerWatch.WATCH_MS)
        // And stalled once it gives up.
        assertEquals(1, ended.get())
        assertTrue(watch.stalled("t"))
        assertTrue(watch.confirm("t"))
    }

    @Test
    fun `waits keep a found stop`() = clock.run {
        timerFires = false
        val watch = watch()
        val tab = "$CHROME_SOCKET~t"
        watch.watch(tab)
        delay(TimerWatch.WATCH_MS + 1)
        assertEquals(1, ended.get())
        assertTrue(watch.stalled(tab))
        // A wait that starts its own check must not reset one that found the timers stopped.
        watch.watch(tab)
        assertTrue(watch.stalled(tab))
        assertEquals(1, started.get())
    }

    @Test
    fun `revived tabs clear`() = clock.run {
        timerFires = false
        val watch = watch()
        watch.arm("t")
        delay(TimerWatch.WATCH_MS + 1)
        assertTrue(watch.stalled("t"))
        timerFires = true
        assertFalse(watch.confirm("t"))
        assertFalse(watch.stalled("t"))
    }

    @Test
    fun `busy pages are unknown`() = clock.run {
        // No answer even to setting up the isolated world, as while script runs: not a stall.
        val watch = watch { byteArrayOf() }
        watch.arm("t")
        delay(TimerWatch.SETUP_MS + 1)
        assertEquals(1, ended.get())
        assertFalse(watch.stalled("t"))
    }

    private fun world() =
        harness.serverTextFrame("""{"id":1,"result":{"frameTree":{"frame":{"id":"F"}}}}""") +
            harness.serverTextFrame("""{"id":2,"result":{"executionContextId":7}}""")

    private fun fired() =
        harness.serverTextFrame("""{"id":3,"result":{"result":{"type":"boolean","value":true}}}""")
}
