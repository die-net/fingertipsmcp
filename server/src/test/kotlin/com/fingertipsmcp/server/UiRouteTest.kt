package com.fingertipsmcp.server

import com.fingertipsmcp.model.Scope
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class UiRouteTest {
    @Test
    fun `masks unless asked not to`() = testApplication {
        val api = FakeApi()
        val observe = api.tokens.issue("observe", setOf(Scope.OBSERVE))
        application { fingertipsModule(api.services) }
        apiClient().use { client ->
            val session = startSession(client, observe)
            suspend fun snapshot(query: String) =
                client.get("/v1/ui/snapshot$query") {
                    bearer(observe)
                    session(session)
                }
            assertEquals(HttpStatusCode.OK, snapshot("").status)
            assertTrue(api.snapshotOptions!!.redact)
            snapshot("?redact=false")
            assertFalse(api.snapshotOptions!!.redact)

            val found =
                client.post("/v1/ui/find") {
                    bearer(observe)
                    session(session)
                    contentType(ContentType.Application.Json)
                    setBody("""{"selector":{"text":"Settings"}}""")
                }
            assertEquals(HttpStatusCode.OK, found.status)
            assertTrue(api.findRequest!!.redact)
        }
    }
}
