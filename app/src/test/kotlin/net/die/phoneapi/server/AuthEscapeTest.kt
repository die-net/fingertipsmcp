package net.die.phoneapi.server

import net.die.phoneapi.model.Scope
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/** Sends bad percent escapes, which Ktor's client refuses, straight to a server. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AuthEscapeTest {
    private val api = FakeApi()
    private val secret = api.tokens.issue("viewer", setOf(Scope.OBSERVE, Scope.STREAM))
    private val raw = RawServer(api.services)

    @AfterAll
    fun stop() {
        raw.close()
    }

    @Test
    fun `bad query is no token`() {
        for (path in listOf("/", "/viewer", "/v1/device")) {
            for (query in BAD_QUERIES) {
                assertEquals(EMPTY_404, raw.get("$path?$query"), "$path?$query")
            }
        }
    }

    @Test
    fun `bad cookie is no token`() {
        for (path in listOf("/", "/viewer", "/v1/device")) {
            for (value in BAD_VALUES) {
                val cookie = "Cookie: $VIEWER_COOKIE=$value"
                assertEquals(EMPTY_404, raw.get(path, cookie), "$path with $value")
            }
        }
    }

    @Test
    fun `bad upgrade is no token`() {
        for (query in BAD_QUERIES) {
            val target = "/v1/stream/video?$query"
            assertEquals(EMPTY_404, raw.upgrade(target), target)
        }
        for (value in BAD_VALUES) {
            val cookie = "Cookie: $VIEWER_COOKIE=$value"
            assertEquals(EMPTY_404, raw.upgrade("/v1/stream/video", cookie), value)
        }
    }

    @Test
    fun `bad cookie beside a good token`() {
        val cookie = "Cookie: $VIEWER_COOKIE=%ZZ"
        val header = raw.get("/v1/device", "Authorization: Bearer $secret", cookie)
        assertEquals(200, header.status)
        val query = raw.get("/viewer?access_token=$secret", cookie)
        assertEquals(302, query.status)
    }

    @Test
    fun `bad query with a token is 400`() {
        val header = raw.get("/v1/device?a=%ZZ", "Authorization: Bearer $secret")
        assertEquals(400, header.status)
        val cookie = raw.get("/viewer?a=%ZZ", "Cookie: $VIEWER_COOKIE=$secret")
        assertEquals(400, cookie.status)
    }

    private companion object {
        val EMPTY_404 = RawReply(404, "")
        val BAD_VALUES = listOf("%ZZ", "%", "%E4%A")
        val BAD_QUERIES =
            BAD_VALUES.flatMap { listOf("a=$it", "access_token=$it") } + listOf("%ZZ=1", "%ZZ", "%")
    }
}
