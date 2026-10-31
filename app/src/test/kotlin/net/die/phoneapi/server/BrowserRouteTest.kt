package net.die.phoneapi.server

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.delay
import net.die.phoneapi.lease.Holder
import net.die.phoneapi.model.Scope
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BrowserRouteTest {
    @Test
    fun `no raw cdp socket`() = testApplication {
        val api = FakeApi()
        val browser = api.tokens.issue("browser", Scope.entries.toSet())
        application { phoneApiModule(api.services) }
        apiClient().use { client ->
            // Raw CDP would bypass tab ownership and the isolated world, so there is none.
            val refused =
                try {
                    client.webSocket(
                        "/v1/browser/targets/chrome_devtools_remote~t/cdp",
                        { bearer(browser) },
                    ) {}
                    false
                } catch (e: CancellationException) {
                    throw e
                } catch (_: IllegalStateException) {
                    // The test client reports a refused handshake this way.
                    true
                }
            assertTrue(refused)
            val plain =
                client.get("/v1/browser/targets/chrome_devtools_remote~t/cdp") {
                    bearer(browser)
                }
            assertEquals(HttpStatusCode.NotFound, plain.status)
        }
    }

    @Test
    fun `incognito takes the screen`() = testApplication {
        val api = FakeApi()
        val browser = api.tokens.issue("browser", setOf(Scope.BROWSER))
        application { phoneApiModule(api.services) }
        apiClient().use { client ->
            val session = startSession(client, browser)
            val normal =
                client.post("/v1/browser/tabs") {
                    bearer(browser)
                    session(session)
                    contentType(ContentType.Application.Json)
                    setBody("""{"url":"https://example.com/"}""")
                }
            assertEquals(HttpStatusCode.OK, normal.status)
            assertNull(api.lease.state.value.holder)

            // Chrome's incognito shortcut brings Chrome forward, so the call holds the screen, and
            // gives it back after.
            val incognito = openIncognito(client, browser, session)
            assertEquals(HttpStatusCode.OK, incognito.status)
            assertNull(api.lease.state.value.holder)
            settle { api.cleanups == 1 }
            assertEquals(1, api.cleanups)
        }
    }

    @Test
    fun `chrome start takes screen`() = testApplication {
        val api = FakeApi()
        val browser = api.tokens.issue("browser", setOf(Scope.BROWSER))
        api.browser.stopped = true
        application { phoneApiModule(api.services) }
        apiClient().use { client ->
            val session = startSession(client, browser)
            // Starting Chrome brings it forward, so the call holds the screen and gives it back.
            val opened =
                client.post("/v1/browser/tabs") {
                    bearer(browser)
                    session(session)
                    contentType(ContentType.Application.Json)
                    setBody("""{"url":"https://example.com/"}""")
                }
            assertEquals(HttpStatusCode.OK, opened.status)
            assertNull(api.lease.state.value.holder)
            settle { api.cleanups == 1 }
            assertEquals(1, api.cleanups)
        }
    }

    @Test
    fun `incognito keeps a held screen`() = testApplication {
        val api = FakeApi()
        val all = api.tokens.issue("all", Scope.entries.toSet())
        application { phoneApiModule(api.services) }
        apiClient().use { client ->
            val session = startSession(client, all)
            client.post("/v1/device/wake") {
                bearer(all)
                session(session)
            }
            assertNotNull(api.lease.state.value.holder)
            assertEquals(HttpStatusCode.OK, openIncognito(client, all, session).status)
            assertEquals(Holder.Session(session), api.lease.state.value.holder)
            assertEquals(0, api.cleanups)
        }
    }

    @Test
    fun `touch keys need control`() = testApplication {
        val api = FakeApi()
        val browser = api.tokens.issue("browser", setOf(Scope.BROWSER))
        val both = api.tokens.issue("both", setOf(Scope.BROWSER, Scope.CONTROL))
        application { phoneApiModule(api.services) }
        apiClient().use { client ->
            for ((token, status) in
                listOf(
                    browser to HttpStatusCode.Forbidden,
                    both to HttpStatusCode.OK,
                )) {
                val session = startSession(client, token)
                val tab =
                    client.post("/v1/browser/tabs") {
                        bearer(token)
                        session(session)
                        contentType(ContentType.Application.Json)
                        setBody("""{"url":"https://example.com/"}""")
                    }
                assertEquals(HttpStatusCode.OK, tab.status)
                val key =
                    client.post("/v1/browser/targets/chrome_devtools_remote~t/key") {
                        bearer(token)
                        session(session)
                        contentType(ContentType.Application.Json)
                        setBody("""{"key":"HOME","input":"touch"}""")
                    }
                assertEquals(status, key.status, token)
                if (status == HttpStatusCode.Forbidden) {
                    assertTrue(key.bodyAsText().contains("forbidden"))
                }
            }
        }
    }

    private suspend fun settle(done: () -> Boolean) {
        repeat(SETTLE_TRIES) {
            if (done()) return
            delay(SETTLE_MS)
        }
    }

    private suspend fun openIncognito(client: HttpClient, token: String, session: String) =
        client.post("/v1/browser/tabs") {
            bearer(token)
            session(session)
            contentType(ContentType.Application.Json)
            setBody("""{"url":"https://example.com/","incognito":true}""")
        }
}

private const val SETTLE_TRIES = 50
private const val SETTLE_MS = 10L
