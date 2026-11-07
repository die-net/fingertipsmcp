package net.die.phoneapi.browser

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import net.die.phoneapi.model.ApiException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Running script in an isolated world while the page loads a new document. */
// Scripted sockets are read on a real dispatcher, and onPage closes each page socket.
@Suppress("InjectDispatcher", "MissingUseCall")
class PageEvalTest {
    private val harness = BrowserServiceTest()

    @Test
    fun `runs again on a new document`() {
        // The first world goes with the old document, so the second world runs it.
        val page =
            StallingSocket(
                harness.switched() + world(1) + gone(2) + world(3) + value(4, "\"done\"")
            )
        val result = onPage(page) { evaluateInFrame(it, "F", "x", awaitPromise = false) }
        assertEquals(JsonPrimitive("done"), result.value)
    }

    @Test
    fun `gives up after two documents`() {
        val page = StallingSocket(harness.switched() + world(1) + gone(2) + world(3) + gone(4))
        val error =
            assertThrows(ApiException::class.java) {
                onPage(page) { evaluateInFrame(it, "F", "x", awaitPromise = false) }
            }
        assertEquals("cdp_error", error.error)
        assertTrue(error.message.orEmpty().startsWith("The page loaded a new document"))
    }

    @Test
    fun `other errors are not run again`() {
        val failed = harness.serverTextFrame("""{"id":2,"error":{"message":"Not allowed"}}""")
        val page = StallingSocket(harness.switched() + world(1) + failed)
        val error =
            assertThrows(ApiException::class.java) {
                onPage(page) { evaluateInFrame(it, "F", "x", awaitPromise = false) }
            }
        assertEquals("Not allowed", error.message)
    }

    @Test
    fun `only Chrome's refusal counts`() {
        // The same words under another code are some other failure, so nothing says the
        // expression did not run, and it must not run twice.
        val other = "Cannot find context with specified id"
        val failed =
            harness.serverTextFrame("""{"id":2,"error":{"code":-32602,"message":"$other"}}""")
        val page = StallingSocket(harness.switched() + world(1) + failed)
        val error =
            assertThrows(ApiException::class.java) {
                onPage(page) { evaluateInFrame(it, "F", "x", awaitPromise = false) }
            }
        assertEquals(other, error.message)
        assertEquals(1, page.methods().count { it == "Runtime.evaluate" })
    }

    private fun <T> onPage(page: StallingSocket, block: suspend (CdpSession) -> T): T =
        runBlocking {
            try {
                CdpSession(page.input, page.output, Dispatchers.IO) { BrowserServiceTest.KEY }
                    .use { cdp ->
                        cdp.open("/devtools/page/t")
                        block(cdp)
                    }
            } finally {
                page.close()
            }
        }

    /** The reply to `Page.createIsolatedWorld`. */
    private fun world(id: Int) =
        harness.serverTextFrame("""{"id":$id,"result":{"executionContextId":$id}}""")

    /** Chrome's reply to `Runtime.evaluate` in a world whose document has gone. */
    private fun gone(id: Int) =
        harness.serverTextFrame(
            """{"id":$id,"error":{"code":-32000,"message":"Cannot find context with specified id"}}"""
        )

    private fun value(id: Int, json: String) =
        harness.serverTextFrame(
            """{"id":$id,"result":{"result":{"type":"string","value":$json}}}"""
        )
}
