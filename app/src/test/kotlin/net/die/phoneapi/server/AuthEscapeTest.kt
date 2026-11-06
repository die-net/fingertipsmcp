package net.die.phoneapi.server

import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import java.net.Socket
import kotlinx.coroutines.test.runTest
import net.die.phoneapi.model.Scope
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * Sends raw requests to a real CIO server, because Ktor's client decodes a URL as it builds one and
 * refuses the bad percent escapes these tests are about.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AuthEscapeTest {
    private val api = FakeApi()
    private val secret = api.tokens.issue("viewer", setOf(Scope.OBSERVE, Scope.STREAM))

    // Built here, not in runTest. There embeddedServer resolves to its CoroutineScope overload, so
    // the application runs on the test dispatcher, and stop() blocks that dispatcher's thread until
    // shutdownTimeout, 5 seconds by default, runs out.
    private val server =
        embeddedServer(CIO, host = LOOPBACK, port = 0) { phoneApiModule(api.services) }
    private var port = 0

    @BeforeAll
    fun start() {
        runTest {
            server.start(wait = false)
            port = server.engine.resolvedConnectors().first().port
        }
    }

    @AfterAll
    fun stop() {
        server.stop(gracePeriodMillis = 0, timeoutMillis = 0)
    }

    @Test
    fun `bad query is no token`() {
        for (path in listOf("/", "/viewer", "/v1/device")) {
            for (query in BAD_QUERIES) {
                assertEquals(EMPTY_404, get("$path?$query"), "$path?$query")
            }
        }
    }

    @Test
    fun `bad cookie is no token`() {
        for (path in listOf("/", "/viewer", "/v1/device")) {
            for (value in BAD_VALUES) {
                val cookie = "Cookie: $VIEWER_COOKIE=$value"
                assertEquals(EMPTY_404, get(path, cookie), "$path with $value")
            }
        }
    }

    @Test
    fun `bad upgrade is no token`() {
        val upgrade = "Upgrade: websocket"
        for (query in BAD_QUERIES) {
            val target = "/v1/stream/video?$query"
            assertEquals(EMPTY_404, get(target, upgrade), target)
        }
        for (value in BAD_VALUES) {
            val cookie = "Cookie: $VIEWER_COOKIE=$value"
            assertEquals(EMPTY_404, get("/v1/stream/video", upgrade, cookie), value)
        }
    }

    @Test
    fun `bad cookie beside a good token`() {
        val cookie = "Cookie: $VIEWER_COOKIE=%ZZ"
        val header = get("/v1/device", "Authorization: Bearer $secret", cookie)
        assertEquals("200", header.status)
        val query = get("/viewer?access_token=$secret", cookie)
        assertEquals("302", query.status)
    }

    @Test
    fun `bad query with a token is 400`() {
        val header = get("/v1/device?a=%ZZ", "Authorization: Bearer $secret")
        assertEquals("400", header.status)
        val cookie = get("/v1/device?a=%ZZ", "Cookie: $VIEWER_COOKIE=$secret")
        assertEquals("400", cookie.status)
    }

    private fun get(target: String, vararg headers: String): Reply =
        Socket(LOOPBACK, port).use { socket ->
            socket.soTimeout = READ_TIMEOUT_MS
            val lines = listOf("GET $target HTTP/1.1", "Host: $LOOPBACK", "Connection: close")
            val request = (lines + headers).joinToString("\r\n", postfix = "\r\n\r\n")
            socket.getOutputStream().write(request.toByteArray())
            val response = socket.getInputStream().readBytes().decodeToString()
            Reply(
                status = response.substringAfter(' ').substringBefore(' '),
                body = response.substringAfter("\r\n\r\n"),
            )
        }

    private data class Reply(val status: String, val body: String)

    private companion object {
        const val LOOPBACK = "127.0.0.1"
        const val READ_TIMEOUT_MS = 5_000
        val EMPTY_404 = Reply("404", "")
        val BAD_VALUES = listOf("%ZZ", "%", "%E4%A")
        val BAD_QUERIES =
            BAD_VALUES.flatMap { listOf("a=$it", "access_token=$it") } + listOf("%ZZ=1", "%ZZ", "%")
    }
}
