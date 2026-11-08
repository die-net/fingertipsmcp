package com.fingertipsmcp.server

import com.fingertipsmcp.core.ApiJson
import com.fingertipsmcp.model.ApiError
import com.fingertipsmcp.model.Scope
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class WaitRouteTest {
    @Test
    fun `browser wait needs browser`() = testApplication {
        val api = FakeApi()
        val observe = api.tokens.issue("observe", setOf(Scope.OBSERVE))
        val browser = api.tokens.issue("browser", setOf(Scope.OBSERVE, Scope.BROWSER))
        application { fingertipsModule(api.services) }
        apiClient().use { client ->
            val denied =
                client.post("/v1/wait") {
                    bearer(observe)
                    contentType(ContentType.Application.Json)
                    setBody(BROWSER_WAIT)
                }
            assertEquals(HttpStatusCode.Forbidden, denied.status)
            assertEquals(
                "forbidden",
                ApiJson.decodeFromString(ApiError.serializer(), denied.bodyAsText()).error,
            )
            assertEquals(0, api.waits.calls)

            val node =
                client.post("/v1/wait") {
                    bearer(observe)
                    session(startSession(client, observe))
                    contentType(ContentType.Application.Json)
                    setBody(NODE_WAIT)
                }
            assertEquals(HttpStatusCode.OK, node.status)
            assertEquals(1, api.waits.calls)

            val allowed =
                client.post("/v1/wait") {
                    bearer(browser)
                    session(startSession(client, browser))
                    contentType(ContentType.Application.Json)
                    setBody(BROWSER_WAIT)
                }
            assertEquals(HttpStatusCode.OK, allowed.status)
            assertEquals(2, api.waits.calls)
            assertEquals(setOf(Scope.OBSERVE, Scope.BROWSER), api.waits.scopes)
        }
    }

    @Test
    fun `browser alone waits on pages`() = testApplication {
        val api = FakeApi()
        val browser = api.tokens.issue("browser", setOf(Scope.BROWSER))
        val stream = api.tokens.issue("stream", setOf(Scope.STREAM))
        application { fingertipsModule(api.services) }
        apiClient().use { client ->
            val session = startSession(client, browser)
            suspend fun wait(token: String, body: String) =
                client.post("/v1/wait") {
                    bearer(token)
                    session(session)
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
            assertEquals(HttpStatusCode.OK, wait(browser, BROWSER_WAIT).status)
            assertEquals(setOf(Scope.BROWSER), api.waits.scopes)
            // What watches the screen's UI needs observe.
            for (body in
                listOf(
                    NODE_WAIT,
                    BROWSER_WAIT.replace(TIMEOUT, """"snapshot":true,$TIMEOUT"""),
                    BROWSER_WAIT.replace(TIMEOUT, """"settleMs":100,$TIMEOUT"""),
                )) {
                val denied = wait(browser, body)
                assertEquals(HttpStatusCode.Forbidden, denied.status, body)
                assertEquals(
                    "forbidden",
                    ApiJson.decodeFromString(ApiError.serializer(), denied.bodyAsText()).error,
                )
            }
            // A token with neither scope is refused before the request is checked.
            assertEquals(HttpStatusCode.Forbidden, wait(stream, """{"all":[]}""").status)
            assertEquals(HttpStatusCode.Forbidden, wait(stream, BROWSER_WAIT).status)
            assertEquals(1, api.waits.calls)
        }
    }

    @Test
    fun `person wait skips screen`() = testApplication {
        val api = FakeApi()
        val observe = api.tokens.issue("observe", setOf(Scope.OBSERVE))
        application { fingertipsModule(api.services) }
        apiClient().use { client ->
            val person =
                client.post("/v1/wait") {
                    bearer(observe)
                    session(startSession(client, observe))
                    contentType(ContentType.Application.Json)
                    setBody(PERSON_WAIT)
                }
            assertEquals(HttpStatusCode.OK, person.status)
            assertNull(api.lease.state.value.holder)

            client.post("/v1/wait") {
                bearer(observe)
                session(startSession(client, observe))
                contentType(ContentType.Application.Json)
                setBody(NODE_WAIT)
            }
            assertNotNull(api.lease.state.value.holder)
        }
    }

    private companion object {
        const val TIMEOUT = """"timeoutMs""""
        const val PERSON_WAIT = """{"all":[{"type":"screen","person":false}],"timeoutMs":0}"""
        const val NODE_WAIT = """{"all":[{"type":"node","selector":{"text":"Ok"}}],"timeoutMs":0}"""
        // A session's browser wait names its tab unless the session can see exactly one.
        const val BROWSER_WAIT =
            """{"all":[{"type":"browser.url","target":"chrome_devtools_remote~t",""" +
                """"contains":"example"}],"timeoutMs":0}"""
    }
}
