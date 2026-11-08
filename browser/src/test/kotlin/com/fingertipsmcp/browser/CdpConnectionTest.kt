package com.fingertipsmcp.browser

import com.fingertipsmcp.core.ApiJson
import com.fingertipsmcp.model.ApiException
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runTest
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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** One browser connection with flattened sessions per target, as a desktop browser serves them. */
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
    fun `the pipe ends with nul`() {
        val out = ByteArrayOutputStream()
        PipeTransport(ByteArray(0).inputStream(), out, StandardTestDispatcher()).use {
            it.send("""{"a":1}""")
        }
        assertEquals("{\"a\":1}\u0000", out.toString(Charsets.UTF_8))
    }

    /** Runs [block] on a connection to a [FakeBrowser], read on [scheduler]'s virtual time. */
    private suspend fun connected(
        scheduler: TestCoroutineScheduler,
        detachOnCall: Boolean = false,
        refuseAttach: Boolean = false,
        block: suspend (CdpBrowserConnection, FakeBrowser) -> Unit,
    ) {
        FakeBrowser(detachOnCall, refuseAttach).use { browser ->
            CdpBrowserConnection(browser, StandardTestDispatcher(scheduler)).use { connection ->
                connection.start()
                block(connection, browser)
            }
        }
    }

    /**
     * Answers like Chrome on the other end of a browser connection. It answers each command once it
     * has been sent, in the order a browser does: a target's events, then the reply.
     */
    private class FakeBrowser(
        private val detachOnCall: Boolean = false,
        private val refuseAttach: Boolean = false,
    ) : CdpTransport {
        private val incoming = Channel<String>(Channel.UNLIMITED)
        private val sent = ArrayList<JsonObject>()
        val detached = ArrayList<String>()
        private var browserSessions = 0

        fun hangUp() {
            incoming.close()
        }

        /** The session the last [method] went to, or null for the root. */
        fun sessionOf(method: String): String? =
            sent.last { it["method"]?.jsonPrimitive?.content == method }.session()

        override fun send(text: String) {
            val message = ApiJson.parseToJsonElement(text).jsonObject
            sent += message
            answer(message).forEach { incoming.trySend(it.toString()) }
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
                        listOf(reply(id) { put("sessionId", "S1") })
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
}
