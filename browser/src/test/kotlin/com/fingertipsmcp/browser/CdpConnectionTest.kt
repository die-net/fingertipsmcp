package com.fingertipsmcp.browser

import com.fingertipsmcp.core.ApiJson
import com.fingertipsmcp.model.ApiException
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** One browser connection with flattened sessions per target, as a desktop browser serves them. */
@OptIn(ExperimentalCoroutinesApi::class) // runCurrent runs what is due on the virtual clock.
class CdpConnectionTest {
    @Test
    fun `routes sessions apart`() = runTest {
        connected(testScheduler) { connection, browser ->
            val endpoints = ConnectionEndpoints(connection = { connection })
            val listed = endpoints.targets(endpoints.endpoints().single())
            assertEquals(listOf("chrome~T1"), listed.map { it.id })
            val heard = ArrayList<CdpEvent>()
            val reply =
                endpoints.withPage(DESKTOP_ENDPOINT, "T1") { page ->
                    page.listen { heard += it }.use { page.callCounted("Runtime.evaluate") }
                }
            assertEquals("S1", reply.result["session"]?.jsonPrimitive?.contentOrNull)
            // The browser sent an event for S2 first, which this session does not hear or count.
            assertEquals(listOf("Page.loadEventFired"), heard.map { it.method })
            assertEquals(listOf(0L), heard.map { it.seq })
            assertEquals(1L, reply.eventsBefore)
            assertEquals(listOf("S1"), browser.detached, "closing the session detaches it")
        }
    }

    @Test
    fun `a browser call has a session`() = runTest {
        connected(testScheduler) { connection, browser ->
            val endpoints = ConnectionEndpoints(connection = { connection })
            endpoints.withBrowser(DESKTOP_ENDPOINT) { it.call("Target.setDiscoverTargets") }
            assertEquals(listOf("B1"), browser.detached)
            assertEquals("B1", browser.sessionOf("Target.setDiscoverTargets"))
        }
    }

    @Test
    fun `a detached tab fails its calls`() = runTest {
        connected(testScheduler, detachOnCall = true) { connection, browser ->
            connection.attach("T1").use { session ->
                val error = assertThrows<ApiException> { session.call("Page.navigate") }
                assertEquals("cdp_error", error.error)
                session.awaitClosed()
                // A later look at the session finds it stopped.
                session.awaitClosed()
            }
            // Closing a session the browser detached asks the browser nothing.
            assertEquals(emptyList<String>(), browser.detached)
        }
    }

    @Test
    fun `a closed tab is gone`() = runTest {
        connected(testScheduler) { connection, _ ->
            val endpoints = ConnectionEndpoints(connection = { connection })
            val gone = assertThrows<ApiException> { endpoints.withPage(DESKTOP_ENDPOINT, "T9") {} }
            assertTrue(tabGone(gone))
        }
    }

    @Test
    fun `a listed tab is not gone`() = runTest {
        connected(testScheduler, refuseAttach = true) { connection, _ ->
            val endpoints = ConnectionEndpoints(connection = { connection })
            val refused =
                assertThrows<ApiException> { endpoints.withPage(DESKTOP_ENDPOINT, "T1") {} }
            assertFalse(tabGone(refused))
            assertEquals("cdp_error", refused.error)
        }
    }

    @Test
    fun `a hang-up stops everything`() = runTest {
        connected(testScheduler) { connection, browser ->
            connection.attach("T1").use { session ->
                browser.hangUp()
                connection.awaitClosed()
                assertFalse(connection.isOpen)
                session.awaitClosed()
            }
            val error = assertThrows<ApiException> { connection.root.call("Browser.getVersion") }
            assertEquals("cdp_error", error.error)
        }
    }

    @Test
    @Suppress("MissingUseCall") // The attach is cancelled, so nothing returns to close.
    fun `a cancelled attach detaches`() = runTest {
        connected(testScheduler, holdAttach = true) { connection, browser ->
            val attaching = launch { connection.attach("T1") }
            runCurrent()
            // Cancelled at a wait's limit, before the browser answers.
            attaching.cancelAndJoin()
            browser.answerHeld()
            runCurrent()
            assertEquals(listOf("S1"), browser.detached)
        }
    }

    @Test
    @Suppress("MissingUseCall") // The attach is cancelled, so nothing returns to close.
    fun `late cancelled attach detaches`() = runTest {
        connected(testScheduler, holdAttach = true) { connection, browser ->
            val attaching = launch { connection.attach("T1") }
            runCurrent()
            // The reply is read, and the caller is cancelled before it resumes with it.
            browser.answerHeld()
            attaching.cancel()
            runCurrent()
            assertTrue(attaching.isCancelled)
            assertEquals(listOf("S1"), browser.detached)
        }
    }

    @Test
    @Suppress("MissingUseCall") // The open times out, so nothing returns to close.
    fun `an unanswered open times out`() = runTest {
        connected(testScheduler, holdAttach = true) { connection, browser ->
            val endpoints = ConnectionEndpoints(connection = { connection })
            val error =
                assertThrows<ApiException> {
                    endpoints.openPage(DESKTOP_ENDPOINT, "T1", answerTimeoutMs = 1_000)
                }
            assertEquals("cdp_timeout", error.error)
            // The open's own limit, not the connection's 15 s call timeout.
            assertEquals(1_000L, testScheduler.currentTime)
            browser.answerHeld()
            runCurrent()
            assertEquals(listOf("S1"), browser.detached)
        }
    }

    @Test
    fun `an early detach stops it`() = runTest {
        connected(testScheduler, detachOnAttach = true) { connection, _ ->
            connection.attach("T1").use { session ->
                // Virtual time: a session that never stops fails here at once.
                assertNotNull(withTimeoutOrNull(1_000) { session.awaitClosed() })
            }
        }
    }

    @Test
    @Suppress("MissingUseCall") // The listener lasts for the connection.
    fun `a listener bug is reported`() {
        val bug =
            assertThrows<IllegalStateException> {
                runTest {
                    connected(testScheduler) { connection, _ ->
                        connection.root.listen { error("listener bug") }
                        val error =
                            assertThrows<ApiException> { connection.root.call("Runtime.evaluate") }
                        assertEquals(DEVTOOLS_FAILED, error.message)
                        assertEquals(DEVTOOLS_FAILED, connection.awaitClosed().message)
                    }
                }
            }
        // Still fatal: the test engine sees it as an uncaught exception.
        assertEquals("listener bug", bug.message)
    }

    @Test
    fun `inline resumes free the gate`() = runTest {
        val router = CdpRouter {}
        var blocked: Boolean? = null
        launch(UnconfinedTestDispatcher(testScheduler)) {
            router.callCounted("Browser.getVersion", null, null)
            // The reply resumes this caller, unconfined, inside the delivery. Another thread
            // asking the router anything must not wait for that delivery to finish.
            val other = thread { router.closed }
            other.join(TimeUnit.SECONDS.toMillis(STALL_LIMIT_S))
            blocked = other.isAlive
        }
        router.dispatch("""{"id":1,"result":{}}""")
        assertEquals(false, blocked)
    }

    @Test
    @Suppress("MissingUseCall") // The finally closes the output, and the connection the pipe.
    fun `a stalled pipe still times out`() = runTest {
        val threads = Executors.newFixedThreadPool(2).asCoroutineDispatcher()
        val stalled = StalledOutput()
        try {
            val pipe = PipeTransport(PipedInputStream(PipedOutputStream()), stalled, threads)
            CdpBrowserConnection(pipe, StandardTestDispatcher(testScheduler)).use { connection ->
                connection.start()
                repeat(2) {
                    // The second call is not queued behind the first one's write.
                    val error =
                        assertThrows<ApiException> { connection.root.call("Browser.getVersion") }
                    assertEquals("cdp_timeout", error.error)
                }
            }
        } finally {
            stalled.close()
            threads.close()
        }
    }

    @Test
    @Suppress("MissingUseCall") // The finally closes the output, and the pipe closes it too.
    fun `a stalled pipe refuses more`() = runTest {
        val threads = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val stalled = StalledOutput()
        try {
            PipeTransport(PipedInputStream(PipedOutputStream()), stalled, threads).use { pipe ->
                // The writer may hold one message in its stalled write, and the queue the rest.
                val full = assertThrows<IOException> { repeat(PIPE_QUEUE + 2) { pipe.send("{}") } }
                assertTrue(full.message.orEmpty().contains("stopped reading"))
            }
        } finally {
            stalled.close()
            threads.close()
        }
    }

    @Test
    fun `the pipe splits on nul`() = runTest {
        val bytes =
            listOf("""{"a":1}""", """{"b":2}""", """{"c"""").joinToString("\u0000").toByteArray()
        val seen = ArrayList<String>()
        val io = StandardTestDispatcher(testScheduler)
        PipeTransport(bytes.inputStream(), ByteArrayOutputStream(), io).use { pipe ->
            pipe.receive { seen += it }
        }
        assertEquals(listOf("{\"a\":1}", "{\"b\":2}"), seen)
    }

    @Test
    fun `the pipe ends with nul`() = runTest {
        val out = ByteArrayOutputStream()
        PipeTransport(ByteArray(0).inputStream(), out, StandardTestDispatcher(testScheduler)).use {
            it.send("""{"a":1}""")
            runCurrent()
        }
        assertEquals("{\"a\":1}\u0000", out.toString(Charsets.UTF_8))
    }

    /**
     * Chrome's end of the pipe when it stops reading: a write blocks until the pipe closes. The
     * limit only ends a run in which a caller is stuck in the write.
     */
    private class StalledOutput : OutputStream() {
        private val closed = CountDownLatch(1)

        override fun write(b: Int) {
            if (!closed.await(STALL_LIMIT_S, TimeUnit.SECONDS)) throw IOException("Stalled")
            throw IOException("Closed")
        }

        override fun close() {
            closed.countDown()
        }
    }

    /** Runs [block] on a connection to a [FakeBrowser], read on [scheduler]'s virtual time. */
    private suspend fun connected(
        scheduler: TestCoroutineScheduler,
        detachOnCall: Boolean = false,
        refuseAttach: Boolean = false,
        holdAttach: Boolean = false,
        detachOnAttach: Boolean = false,
        block: suspend (CdpBrowserConnection, FakeBrowser) -> Unit,
    ) {
        FakeBrowser(detachOnCall, refuseAttach, holdAttach, detachOnAttach).use { browser ->
            CdpBrowserConnection(browser, StandardTestDispatcher(scheduler)).use { connection ->
                connection.start()
                block(connection, browser)
            }
        }
    }

    /**
     * Answers like Chrome on the other end of a browser connection. It answers each command once it
     * has been sent, in the order a browser does: a target's events, then the reply. With
     * [holdAttach], an attach is answered only at [answerHeld], as a slow browser does. With
     * [detachOnAttach], the tab closes as it is attached, so the detach follows the reply.
     */
    private class FakeBrowser(
        private val detachOnCall: Boolean = false,
        private val refuseAttach: Boolean = false,
        private val holdAttach: Boolean = false,
        private val detachOnAttach: Boolean = false,
    ) : CdpTransport {
        private val incoming = Channel<String>(Channel.UNLIMITED)
        private val sent = ArrayList<JsonObject>()
        private val held = ArrayList<JsonObject>()
        val detached = ArrayList<String>()
        private var browserSessions = 0

        fun hangUp() {
            incoming.close()
        }

        /** Sends the attach replies [holdAttach] held back. */
        fun answerHeld() {
            held.forEach { incoming.trySend(it.toString()) }
            held.clear()
        }

        /** The session the last [method] went to, or null for the root. */
        fun sessionOf(method: String): String? =
            sent.last { it["method"]?.jsonPrimitive?.content == method }.session()

        override fun send(text: String) {
            val message = ApiJson.parseToJsonElement(text).jsonObject
            sent += message
            val answers = answer(message)
            if (
                holdAttach && message["method"]?.jsonPrimitive?.content == "Target.attachToTarget"
            ) {
                held += answers
                return
            }
            answers.forEach { incoming.trySend(it.toString()) }
        }

        override suspend fun receive(onText: suspend (String) -> Unit) {
            for (text in incoming) onText(text)
        }

        override fun close() {
            incoming.close()
        }

        private fun JsonObject.session(): String? =
            (this["sessionId"] as? JsonPrimitive)?.contentOrNull

        private fun answer(message: JsonObject): List<JsonObject> {
            val id = message["id"]!!.jsonPrimitive.int
            val session = message.session()
            val params = message["params"] as? JsonObject
            return when (message["method"]!!.jsonPrimitive.content) {
                "Target.getTargets" ->
                    listOf(
                        reply(id) {
                            put(
                                "targetInfos",
                                ApiJson.parseToJsonElement(
                                    """[{"targetId":"T1","type":"page","url":"https://a/"},""" +
                                        """{"targetId":"U","type":"browser_ui","url":"chrome://x/"}]"""
                                ),
                            )
                        }
                    )
                "Target.attachToTarget" ->
                    if (refuseAttach || params?.get("targetId")?.jsonPrimitive?.content != "T1") {
                        listOf(refusal(id, "No target with given id found"))
                    } else {
                        listOfNotNull(
                            reply(id) { put("sessionId", "S1") },
                            event("Target.detachedFromTarget", null) { put("sessionId", "S1") }
                                .takeIf { detachOnAttach },
                        )
                    }
                "Target.attachToBrowserTarget" ->
                    listOf(reply(id) { put("sessionId", "B${++browserSessions}") })
                "Target.detachFromTarget" -> {
                    detached += params!!["sessionId"]!!.jsonPrimitive.content
                    listOf(reply(id) {})
                }
                else ->
                    if (detachOnCall) {
                        listOf(event("Target.detachedFromTarget", null) { put("sessionId", "S1") })
                    } else {
                        listOf(
                            event("Network.dataReceived", "S2") {},
                            event("Page.loadEventFired", session) {},
                            reply(id, session) { put("session", session) },
                        )
                    }
            }
        }

        private fun reply(id: Int, session: String? = null, result: JsonObjectBuilder.() -> Unit) =
            buildJsonObject {
                put("id", id)
                put("result", buildJsonObject(result))
                if (session != null) put("sessionId", session)
            }

        private fun refusal(id: Int, message: String) = buildJsonObject {
            put("id", id)
            put(
                "error",
                buildJsonObject {
                    put("code", SERVER_ERROR)
                    put("message", message)
                },
            )
        }

        private fun event(method: String, session: String?, params: JsonObjectBuilder.() -> Unit) =
            buildJsonObject {
                put("method", method)
                put("params", buildJsonObject(params))
                if (session != null) put("sessionId", session)
            }
    }

    private companion object {
        const val STALL_LIMIT_S = 10L
    }
}
