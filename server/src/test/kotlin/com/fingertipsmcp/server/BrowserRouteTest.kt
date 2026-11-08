package com.fingertipsmcp.server

import com.fingertipsmcp.lease.Holder
import com.fingertipsmcp.model.BrowserTarget
import com.fingertipsmcp.model.Scope
import io.ktor.client.HttpClient
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CompletableDeferred
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BrowserRouteTest {
    @Test
    fun `no raw cdp socket`() {
        val api = FakeApi()
        val auth = "Authorization: Bearer ${api.tokens.issue("browser", Scope.entries.toSet())}"
        val path = "/v1/browser/targets/chrome_devtools_remote~t/cdp"
        RawServer(api.services).use { raw ->
            // Raw CDP would bypass tab ownership and the isolated world, so there is none.
            assertEquals(RawReply(404, ""), raw.upgrade(path, auth))
            assertEquals(RawReply(404, ""), raw.get(path, auth))
            assertEquals(101, raw.upgrade("/v1/events", auth).status)
        }
    }

    @Test
    fun `an open beside cleanup is fine`() = testApplication {
        val home = CompletableDeferred<Unit>()
        lateinit var api: FakeApi
        api =
            FakeApi(
                browser =
                    object : FakeBrowser() {
                        override suspend fun openTab(
                            url: String,
                            capture: Boolean,
                            incognito: Boolean,
                            created: suspend (String) -> Unit,
                        ): BrowserTarget {
                            // Cleanup reaches HOME and finishes while the open runs.
                            home.complete(Unit)
                            api.awaitCleanups(1)
                            return super.openTab(url, capture, incognito, created)
                        }
                    }
            )
        api.duringCleanup = {
            home.await()
            api.top = "com.launcher"
        }
        val token = api.tokens.issue("browser", setOf(Scope.BROWSER, Scope.CONTROL))
        application { fingertipsModule(api.services) }
        apiClient().use { client ->
            val leaving = startSession(client, token)
            val tap =
                client.post("/v1/input/tap") {
                    bearer(token)
                    session(leaving)
                    contentType(ContentType.Application.Json)
                    setBody("""{"x":1,"y":2}""")
                }
            assertEquals(HttpStatusCode.OK, tap.status)
            api.top = "com.android.chrome"
            val opening = startSession(client, token)
            // DELETE returns with cleanup still running, and Chrome still in front.
            val ended =
                client.delete("/v1/sessions/current") {
                    bearer(token)
                    session(leaving)
                }
            assertEquals(HttpStatusCode.NoContent, ended.status)
            val open =
                client.post("/v1/browser/tabs") {
                    bearer(token)
                    session(opening)
                    contentType(ContentType.Application.Json)
                    setBody("""{"url":"https://example.com/"}""")
                }
            assertEquals(HttpStatusCode.OK, open.status, open.bodyAsText())
            assertEquals("com.launcher", api.top)
            assertEquals(emptyList<String>(), api.tookScreen)
            assertNull(api.lease.state.value.holder)
        }
    }

    @Test
    fun `incognito takes the screen`() = testApplication {
        val api = FakeApi()
        val browser = api.tokens.issue("browser", setOf(Scope.BROWSER))
        application { fingertipsModule(api.services) }
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
            api.awaitCleanups(1)
            assertEquals(1, api.cleanups)
        }
    }

    @Test
    fun `chrome start takes screen`() = testApplication {
        val api = FakeApi()
        val browser = api.tokens.issue("browser", setOf(Scope.BROWSER))
        api.browser.stopped = true
        application { fingertipsModule(api.services) }
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
            api.awaitCleanups(1)
            assertEquals(1, api.cleanups)
        }
    }

    @Test
    fun `incognito keeps a held screen`() = testApplication {
        val api = FakeApi()
        val all = api.tokens.issue("all", Scope.entries.toSet())
        application { fingertipsModule(api.services) }
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
        application { fingertipsModule(api.services) }
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

    private suspend fun openIncognito(client: HttpClient, token: String, session: String) =
        client.post("/v1/browser/tabs") {
            bearer(token)
            session(session)
            contentType(ContentType.Application.Json)
            setBody("""{"url":"https://example.com/","incognito":true}""")
        }
}
