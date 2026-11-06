package net.die.phoneapi.helperclient

import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import net.die.phoneapi.model.HelperStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RestartLoopTest {
    /**
     * The phone as the loop sees it. Wireless debugging registers an mDNS record when the setting
     * goes on, and loses it when mdnsd stops, which [recordLost] acts out. On a network the user
     * hasn't approved, Android puts the setting back to off.
     */
    private inner class Phone : HelperLauncher {
        var setting = false
        var approved = true
        var record = false
        var recordLost = false
        var adbdRegisters = true
        var launchWorks = true
        val writes = mutableListOf<Pair<Long, Boolean>>()
        var searches = 0
        var launches = 0
        var shutdowns = 0
        val statuses = mutableListOf<HelperStatus>()
        private var searching = false
        override var running = false
        override var paired = true
        override var canWriteSetting = true

        fun read(): Boolean = setting

        fun write(on: Boolean) {
            writes += now() to on
            setting = on && approved
            record = setting && adbdRegisters && !(on && recordLost)
        }

        override suspend fun connectPort(): Int? {
            check(!searching) { "Two port searches at once" }
            searching = true
            searches += 1
            try {
                if (setting && record) return PORT
                delay(SEARCH_MS)
                return null
            } finally {
                searching = false
            }
        }

        override suspend fun launch(port: Int): Boolean {
            launches += 1
            delay(LAUNCH_MS)
            running = launchWorks
            return launchWorks
        }

        override fun showStarting() {
            statuses += HelperStatus.STARTING
        }

        override fun showIdle() {
            statuses += HelperStatus.NEEDS_PAIRING
        }

        override fun shutdown() {
            shutdowns += 1
            running = false
        }
    }

    private lateinit var scope: TestScope
    private val phone = Phone()
    private lateinit var setting: WirelessSetting
    private lateinit var loop: RestartLoop

    private fun now(): Long = scope.testScheduler.currentTime

    private fun TestScope.start(): RestartLoop {
        scope = this
        setting = WirelessSetting(read = phone::read, write = phone::write)
        setting.onOutsideChange = { on -> if (on) loop.wake(Wake.FRESH) }
        loop = RestartLoop(phone, setting)
        backgroundScope.launch { loop.run() }
        return loop
    }

    /** Lets every attempt and backoff run out. */
    private fun TestScope.settle() {
        advanceTimeBy(10.minutes)
        runCurrent()
    }

    @Test
    fun `a lost record is cycled once`() = runTest {
        phone.setting = true
        phone.recordLost = true
        start().wake(Wake.RETRY)
        runCurrent()
        phone.recordLost = false
        settle()
        assertTrue(phone.running)
        assertEquals(listOf(false, true), phone.writes.map { it.second })
        val (off, on) = phone.writes.map { it.first }
        assertTrue(on - off >= 1_000L, "off for ${on - off} ms")
        assertTrue(phone.setting)
    }

    @Test
    fun `giving up keeps the user's on`() = runTest {
        phone.setting = true
        phone.adbdRegisters = false
        start().wake(Wake.RETRY)
        settle()
        assertFalse(phone.running)
        assertTrue(phone.setting)
        assertEquals(6 * 2, phone.searches)
        assertEquals(HelperStatus.NEEDS_PAIRING, phone.statuses.last())
    }

    @Test
    fun `own on goes back off`() = runTest {
        phone.adbdRegisters = false
        start().wake(Wake.RETRY)
        settle()
        assertFalse(phone.setting)
        assertEquals(6, phone.searches)
        assertEquals(List(6) { listOf(true, false) }.flatten(), phone.writes.map { it.second })
        assertEquals(HelperStatus.NEEDS_PAIRING, phone.statuses.last())
    }

    @Test
    fun `a refusal waits for allow`() = runTest {
        phone.approved = false
        start().wake(Wake.RETRY)
        settle()
        assertEquals(listOf(true), phone.writes.map { it.second })
        assertEquals(HelperStatus.NEEDS_PAIRING, phone.statuses.last())
        loop.wake(Wake.RETRY)
        settle()
        assertEquals(1, phone.searches)
        // The user taps Allow, which turns the setting on.
        phone.approved = true
        phone.write(true)
        setting.refresh()
        settle()
        assertTrue(phone.running)
    }

    @Test
    fun `a late allow is kept`() = runTest {
        phone.approved = false
        start().wake(Wake.RETRY)
        runCurrent()
        // Android's off reaches the observer at once. The user's Allow lands near the end of the
        // search, and its observer call has not arrived when the attempt ends.
        setting.refresh()
        advanceTimeBy(SEARCH_MS - 1)
        phone.approved = true
        phone.write(true)
        settle()
        assertTrue(phone.running)
        assertEquals(listOf(true, true), phone.writes.map { it.second })
    }

    @Test
    fun `wakes keep the off time`() = runTest {
        phone.setting = true
        phone.recordLost = true
        start().wake(Wake.RETRY)
        runCurrent()
        phone.recordLost = false
        advanceTimeBy(SEARCH_MS + 1)
        assertEquals(listOf(false), phone.writes.map { it.second })
        loop.wake(Wake.RESUME)
        loop.wake(Wake.FRESH)
        loop.wake(Wake.RETRY)
        settle()
        assertTrue(phone.running)
        assertEquals(listOf(false, true), phone.writes.map { it.second })
        val (off, on) = phone.writes.map { it.first }
        assertTrue(on - off >= 1_000L, "off for ${on - off} ms")
        assertEquals(1, phone.launches)
    }

    @Test
    fun `stop holds until resume`() = runTest {
        phone.adbdRegisters = false
        start().wake(Wake.RETRY)
        advanceTimeBy(SEARCH_MS + 1)
        assertEquals(1, phone.searches)
        loop.wake(Wake.STOP)
        settle()
        assertEquals(1, phone.searches)
        assertEquals(1, phone.shutdowns)
        assertFalse(phone.setting)
        assertEquals(HelperStatus.NEEDS_PAIRING, phone.statuses.last())
        phone.adbdRegisters = true
        loop.wake(Wake.RETRY)
        loop.wake(Wake.FRESH)
        settle()
        assertEquals(1, phone.searches)
        loop.wake(Wake.RESUME)
        settle()
        assertTrue(phone.running)
    }

    @Test
    fun `failures back off until six`() = runTest {
        phone.adbdRegisters = false
        start().wake(Wake.RETRY)
        settle()
        val starts = phone.writes.filter { it.second }.map { it.first }
        val gaps = starts.zipWithNext { a, b -> b - a - SEARCH_MS }
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L), gaps)
    }

    @Test
    fun `fresh forgets giving up`() = runTest {
        phone.adbdRegisters = false
        start().wake(Wake.RETRY)
        settle()
        assertEquals(6, phone.searches)
        phone.adbdRegisters = true
        loop.wake(Wake.FRESH)
        settle()
        assertTrue(phone.running)
        assertEquals(7, phone.searches)
    }

    @Test
    fun `unpaired starts nothing`() = runTest {
        phone.paired = false
        start().wake(Wake.RETRY)
        settle()
        phone.paired = true
        phone.canWriteSetting = false
        loop.wake(Wake.RESUME)
        settle()
        assertEquals(0, phone.searches)
        assertEquals(emptyList<Pair<Long, Boolean>>(), phone.writes)
        assertEquals(listOf(HelperStatus.NEEDS_PAIRING, HelperStatus.NEEDS_PAIRING), phone.statuses)
    }

    private companion object {
        const val PORT = 41_609
        const val SEARCH_MS = 15_000L
        const val LAUNCH_MS = 500L
    }
}
