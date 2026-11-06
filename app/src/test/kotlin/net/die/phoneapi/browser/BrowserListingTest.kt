package net.die.phoneapi.browser

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import net.die.phoneapi.model.ApiException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Listing targets when a socket opens and then says nothing. */
@Suppress("InjectDispatcher") // A socket that blocks its read needs a real dispatcher.
class BrowserListingTest {
    private val harness = BrowserServiceTest()

    @Test
    @Suppress("MissingUseCall") // The service closes each socket it opens.
    fun `slow socket does not block`() {
        val opened = ConcurrentHashMap<String, Long>()
        val closed = ConcurrentHashMap<String, Long>()
        val held = ConcurrentLinkedQueue<DevtoolsSocket>()
        val sockets =
            """[{"name":"fast-a","pid":1,"package":"a.pkg"},{"name":"slow-1","pid":2,"package":"s.pkg"},{"name":"fast-b","pid":3,"package":"b.pkg"},{"name":"slow-2","pid":4,"package":"s.pkg"}]"""
        val service =
            harness.service(
                byteArrayOf(),
                sockets = sockets,
                io = Dispatchers.IO,
                open = { name ->
                    val socket =
                        if (name.startsWith("slow")) {
                            BlockingSocket { closed.putIfAbsent(name, System.nanoTime()) }
                                .also(held::add)
                        } else {
                            val id = if (name == "fast-a") "a" else "b"
                            BrowserServiceTest.ScriptedSocket(
                                harness.http(
                                    """[{"id":"$id","type":"page","title":"$id","url":"https://example.com/"}]"""
                                )
                            )
                        }
                    opened[name] = System.nanoTime()
                    // The last socket lets the slow ones go, so all four must be open at once. A
                    // socket that held up the others would be closed by its timeout first.
                    if (opened.size == SOCKETS) held.forEach(DevtoolsSocket::close)
                    socket
                },
            )
        val targets = runBlocking { service.targets() }
        assertEquals(listOf("fast-a~a", "fast-b~b"), targets.map { it.id })
        assertTrue(opened.getValue("slow-2") < closed.getValue("slow-1"))
    }

    @Test
    @Suppress("MissingUseCall") // The service closes each socket it opens.
    fun `stuck socket is closed in time`() =
        runTest(harness.clock) {
            val closed = AtomicBoolean(false)
            val sockets = """[{"name":"stuck","pid":1,"package":"s.pkg"}]"""
            val service =
                harness.service(
                    byteArrayOf(),
                    sockets = sockets,
                    io = Dispatchers.IO,
                    open = { BlockingSocket { closed.set(true) } },
                )
            // Only the timeout can end this read, and virtual time brings it at once.
            val error =
                try {
                    service.targets()
                    null
                } catch (e: ApiException) {
                    e
                }
            assertEquals("DevTools did not answer in time", error?.message)
            assertTrue(closed.get())
        }

    private class BlockingSocket(private val onClose: () -> Unit) : DevtoolsSocket {
        private val closed = CountDownLatch(1)
        override val input: InputStream =
            object : InputStream() {
                override fun read(): Int = awaitClosed()

                override fun read(b: ByteArray, off: Int, len: Int): Int = awaitClosed()

                private fun awaitClosed(): Int {
                    if (!closed.await(5, TimeUnit.SECONDS)) throw IOException("not closed")
                    throw IOException("closed")
                }
            }
        override val output: OutputStream = ByteArrayOutputStream()

        override fun close() {
            val first =
                synchronized(this) {
                    if (closed.count == 0L) return@synchronized false
                    closed.countDown()
                    true
                }
            if (first) onClose()
        }
    }

    private companion object {
        const val SOCKETS = 4
    }
}
