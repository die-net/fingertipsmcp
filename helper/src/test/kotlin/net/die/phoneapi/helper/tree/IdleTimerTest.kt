package net.die.phoneapi.helper.tree

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Drives [IdleTimer] with a fake clock and a fake looper that runs posts when told to. */
class IdleTimerTest {
    private var clock = 0L
    private var busy = false
    private var idled = 0
    private val posted = ArrayList<Pair<Long, Runnable>>()
    private val timer =
        IdleTimer(
            idleMs = IDLE,
            now = { clock },
            post = { check, delay -> posted += (clock + delay) to check },
            busy = { busy },
            onIdle = { idled++ },
        )

    /** Moves the clock to [ms], running each check that comes due on the way. */
    private fun advanceTo(ms: Long) {
        while (true) {
            val due = posted.filter { it.first <= ms }.minByOrNull { it.first } ?: break
            posted.remove(due)
            clock = due.first
            due.second.run()
        }
        clock = ms
    }

    @Test
    fun `a burst of events posts one check`() {
        timer.start()
        repeat(1_000) {
            clock += 1
            timer.touch()
        }
        assertEquals(1, posted.size)
    }

    @Test
    fun `idles out a full period after the last use`() {
        timer.start()
        advanceTo(IDLE / 2)
        timer.touch()
        advanceTo(IDLE)
        assertEquals(0, idled)
        advanceTo(IDLE / 2 + IDLE)
        assertEquals(1, idled)
    }

    @Test
    fun `a busy session stays up and idles once free`() {
        busy = true
        timer.start()
        advanceTo(IDLE * 3)
        assertEquals(0, idled)
        busy = false
        advanceTo(IDLE * 4)
        assertEquals(1, idled)
        advanceTo(IDLE * 10)
        assertEquals(1, idled)
    }

    @Test
    fun `a stopped timer does not idle`() {
        timer.start()
        timer.stop()
        advanceTo(IDLE * 2)
        assertEquals(0, idled)
    }

    @Test
    fun `a session reconnected before an old check still idles once`() {
        timer.start()
        timer.stop()
        advanceTo(IDLE / 2)
        timer.start()
        advanceTo(IDLE)
        assertEquals(0, idled)
        advanceTo(IDLE / 2 + IDLE)
        assertEquals(1, idled)
    }

    private companion object {
        const val IDLE = 180_000L
    }
}
