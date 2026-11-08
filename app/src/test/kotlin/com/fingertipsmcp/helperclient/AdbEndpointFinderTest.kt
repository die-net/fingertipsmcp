package com.fingertipsmcp.helperclient

import com.fingertipsmcp.core.EventBus
import com.fingertipsmcp.model.HelperStatus
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

@OptIn(ExperimentalCoroutinesApi::class) // Virtual time, read and stepped by hand.
class AdbEndpointFinderTest {
    @TempDir lateinit var dir: File

    private val nsd = FakeNsd()
    private lateinit var listening: ServerSocket

    /** A port on this machine that takes connections, as a phone's adbd would. */
    private val open: Int
        get() = listening.localPort

    @Suppress("MissingUseCall") // Each test uses it, and close() closes it after.
    @BeforeEach
    fun listen() {
        listening = ServerSocket(0, 8, LOCAL)
    }

    @AfterEach
    fun close() {
        listening.close()
    }

    private fun TestScope.finder(serial: String? = SERIAL): AdbEndpointFinder {
        val identity = AdbIdentity(dir)
        serial?.let(identity::rememberSerial)
        val helper = HelperConnection(EventBus(), HelperStatus.NEEDS_PAIRING)
        return AdbEndpointFinder(helper, StandardTestDispatcher(testScheduler), identity, nsd)
    }

    @Test
    fun `a hung record is left alone`() = runTest {
        val search = async { finder().pairingPort() }
        runCurrent()
        assertEquals(listOf(PAIRING), nsd.browsed)
        nsd.announce(FakeRecord(FOREIGN_NAME, nsd, hangs = true))
        nsd.announce(FakeRecord(OWN_NAME, nsd, LOCAL, open))
        assertEquals(open, search.await())
        assertEquals(listOf(OWN_NAME), nsd.asked)
    }

    @Test
    fun `connect skips a hung record`() = runTest {
        // The same two records, for a connection search and then a pairing one.
        val connect = async { finder().connectPort() }
        runCurrent()
        assertEquals(listOf(CONNECT), nsd.browsed)
        nsd.announce(FakeRecord(FOREIGN_NAME, nsd, hangs = true))
        nsd.announce(FakeRecord(OWN_NAME, nsd, LOCAL, open))
        assertEquals(open, connect.await())
        assertFalse(nsd.busy)
        assertEquals(listOf(OWN_NAME), nsd.asked)
    }

    @Test
    fun `an unknown serial is learned`() = runTest {
        val finder = finder(serial = null)
        val search = async { finder.pairingPort() }
        runCurrent()
        nsd.announce(FakeRecord(FOREIGN_NAME, nsd, FOREIGN, open))
        nsd.announce(FakeRecord(OWN_NAME, nsd, LOCAL, open))
        assertEquals(open, search.await())
        assertEquals(listOf(FOREIGN_NAME, OWN_NAME), nsd.asked)
        assertEquals(SERIAL, AdbIdentity(dir).serial)
    }

    @Test
    fun `learned serial drops the queue`() = runTest {
        val finder = finder(serial = null)
        val ports = mutableListOf<Int?>()
        val watch = launch { finder.pairingPorts().collect { ports += it } }
        runCurrent()
        // Answered late, so the foreign record is already queued when the serial is learned.
        val own = FakeRecord(OWN_NAME, nsd, LOCAL, open, answerLater = true)
        nsd.announce(own)
        nsd.announce(FakeRecord(FOREIGN_NAME, nsd, hangs = true))
        runCurrent()
        own.answer()
        runCurrent()
        assertEquals(listOf<Int?>(open), ports)
        assertEquals(listOf(OWN_NAME), nsd.asked)
        watch.cancel()
    }

    @Test
    fun `another host is skipped`() = runTest {
        val search = async { finder().pairingPort() }
        runCurrent()
        // A record named for this serial, as a second emulator from one image can be.
        nsd.announce(FakeRecord(OWN_NAME, nsd, FOREIGN, open))
        nsd.announce(FakeRecord("adb-$SERIAL-other", nsd, LOCAL, open))
        assertEquals(open, search.await())
    }

    @Test
    fun `a closed port is skipped`() = runTest {
        val closed = ServerSocket(0, 8, LOCAL).use { it.localPort }
        val search = async { finder().pairingPort() }
        runCurrent()
        nsd.announce(FakeRecord(OWN_NAME, nsd, LOCAL, closed))
        nsd.announce(FakeRecord("adb-$SERIAL-other", nsd, LOCAL, open))
        assertEquals(open, search.await())
    }

    @Test
    fun `a search waits out a busy nsd`() = runTest {
        val search = async { finder().pairingPort() }
        runCurrent()
        // Another search's resolve is running, and ends a moment later.
        nsd.busy = true
        nsd.announce(FakeRecord(OWN_NAME, nsd, LOCAL, open))
        runCurrent()
        assertEquals(1, nsd.refused)
        nsd.busy = false
        assertEquals(open, search.await())
    }

    @Test
    fun `a search ends at its deadline`() = runTest {
        val search = async { finder().pairingPort(timeoutMs = 2_000) }
        runCurrent()
        nsd.busy = true
        nsd.announce(FakeRecord(OWN_NAME, nsd, LOCAL, open))
        assertNull(search.await())
        assertEquals(2_000, currentTime)
        assertTrue(nsd.stopped)
    }

    @Test
    fun `a failed browse ends a search`() = runTest {
        val search = async { finder().pairingPort() }
        runCurrent()
        nsd.fail()
        assertNull(search.await())
        assertEquals(0, currentTime)
    }

    @Test
    fun `a found port stops the browse`() = runTest {
        val search = async { finder().pairingPort() }
        runCurrent()
        nsd.announce(FakeRecord(OWN_NAME, nsd, LOCAL, open))
        search.await()
        runCurrent()
        assertTrue(nsd.stopped)
    }

    @Test
    fun `the watch follows the dialog`() = runTest {
        val finder = finder()
        val ports = mutableListOf<Int?>()
        val watch = launch { finder.pairingPorts().collect { ports += it } }
        runCurrent()
        nsd.announce(FakeRecord(FOREIGN_NAME, nsd, hangs = true))
        nsd.announce(FakeRecord(OWN_NAME, nsd, LOCAL, 41_000))
        runCurrent()
        nsd.withdraw(OWN_NAME)
        runCurrent()
        // The port is not tried: the record's being there is what the watch reports.
        nsd.announce(FakeRecord(OWN_NAME, nsd, LOCAL, 42_000))
        runCurrent()
        assertEquals(listOf(41_000, null, 42_000), ports)
        watch.cancel()
        runCurrent()
        assertTrue(nsd.stopped)
    }

    @Test
    fun `a lost record is not reported`() = runTest {
        val finder = finder()
        val ports = mutableListOf<Int?>()
        val watch = launch { finder.pairingPorts().collect { ports += it } }
        runCurrent()
        val late = FakeRecord(OWN_NAME, nsd, LOCAL, 41_000, answerLater = true)
        nsd.announce(late)
        runCurrent()
        nsd.withdraw(OWN_NAME)
        runCurrent()
        late.answer()
        runCurrent()
        assertEquals(emptyList<Int?>(), ports)
        watch.cancel()
    }

    @Test
    fun `a failed browse ends a watch`() = runTest {
        val finder = finder()
        val ports = async { finder.pairingPorts().toList() }
        runCurrent()
        nsd.fail()
        assertEquals(emptyList<Int?>(), ports.await())
    }

    @Test
    fun `a retry drops a lost record`() = runTest {
        val finder = finder()
        val watch = launch { finder.pairingPorts().collect {} }
        runCurrent()
        nsd.busy = true
        nsd.announce(FakeRecord(OWN_NAME, nsd, LOCAL, 41_000))
        runCurrent()
        nsd.withdraw(OWN_NAME)
        runCurrent()
        nsd.busy = false
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(1, nsd.refused)
        assertEquals(emptyList<String>(), nsd.asked)
        watch.cancel()
    }

    /**
     * NsdManager as Android 11 has it: one resolve at a time in a process, so a resolve asked for
     * while another runs fails at once, and one that never answers keeps every later one out.
     */
    private class FakeNsd : RecordBrowser {
        val browsed = ArrayList<String>()
        val asked = ArrayList<String>()
        var refused = 0
        var busy = false
        var stopped = false
        private var listener: RecordListener? = null

        override fun browse(type: String, listener: RecordListener): AutoCloseable {
            browsed += type
            this.listener = listener
            return AutoCloseable { stopped = true }
        }

        fun announce(record: AdbRecord) {
            checkNotNull(listener).found(record)
        }

        fun withdraw(name: String) {
            checkNotNull(listener).lost(name)
        }

        fun fail() {
            checkNotNull(listener).failed()
        }
    }

    private class FakeRecord(
        override val name: String,
        private val nsd: FakeNsd,
        private val host: InetAddress? = null,
        private val port: Int = 0,
        private val hangs: Boolean = false,
        private val answerLater: Boolean = false,
    ) : AdbRecord {
        private var waiting: ((ResolveOutcome) -> Unit)? = null

        override fun resolve(done: (ResolveOutcome) -> Unit) {
            if (nsd.busy) {
                nsd.refused++
                done(ResolveOutcome.Busy)
                return
            }
            nsd.asked += name
            when {
                hangs -> nsd.busy = true
                answerLater -> {
                    nsd.busy = true
                    waiting = done
                }
                else -> done(ResolveOutcome.Resolved(host, port))
            }
        }

        fun answer() {
            nsd.busy = false
            checkNotNull(waiting)(ResolveOutcome.Resolved(host, port))
        }
    }

    private companion object {
        const val SERIAL = "ZY227DX4L7"
        const val OWN_NAME = "adb-$SERIAL-AbC123"
        const val FOREIGN_NAME = "adb-EMULATOR37X1X11X0-xYz789"
        const val PAIRING = "_adb-tls-pairing._tcp."
        const val CONNECT = "_adb-tls-connect._tcp."
        val LOCAL: InetAddress = InetAddress.getLoopbackAddress()
        val FOREIGN: InetAddress = InetAddress.getByAddress(byteArrayOf(192.toByte(), 0, 2, 7))
    }
}
