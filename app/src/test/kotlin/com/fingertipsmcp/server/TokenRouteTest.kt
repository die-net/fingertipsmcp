package com.fingertipsmcp.server

import com.fingertipsmcp.core.ApiJson
import com.fingertipsmcp.model.Scope
import com.fingertipsmcp.model.TokenInfo
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TokenRouteTest {
    private val api = FakeApi()
    private val admin = api.tokens.issue("admin", setOf(Scope.ADMIN))
    private val laptop = api.tokens.issue("laptop", Scope.entries.toSet())

    @Test
    fun `patch replaces scopes`() = testApplication {
        application { fingertipsModule(api.services) }
        apiClient().use { http ->
            assertEquals(HttpStatusCode.OK, http.get("/v1/openapi.json") { bearer(laptop) }.status)
            val response = patch(http, laptop, """{"scopes":["stream"]}""")
            assertEquals(HttpStatusCode.OK, response.status)
            val info = ApiJson.decodeFromString(TokenInfo.serializer(), response.bodyAsText())
            assertEquals(setOf(Scope.STREAM), info.scopes)
            assertEquals(setOf(Scope.STREAM), api.tokens.authenticate(laptop)?.scopes)
            val refused = http.get("/v1/openapi.json") { bearer(laptop) }
            assertEquals(HttpStatusCode.Forbidden, refused.status)
        }
    }

    @Test
    fun `patch refuses bad requests`() = testApplication {
        application { fingertipsModule(api.services) }
        apiClient().use { http ->
            val empty = patch(http, laptop, """{"scopes":[]}""")
            assertEquals(HttpStatusCode.BadRequest, empty.status)
            assertEquals(Scope.entries.toSet(), api.tokens.authenticate(laptop)?.scopes)
            val unknown = patch(http, "nope", """{"scopes":["observe"]}""")
            assertEquals(HttpStatusCode.NotFound, unknown.status)
            assertTrue(unknown.bodyAsText().contains("not_found"))
        }
    }

    private suspend fun patch(http: HttpClient, id: String, body: String): HttpResponse =
        http.patch("/v1/tokens/$id") {
            bearer(admin)
            contentType(ContentType.Application.Json)
            setBody(body)
        }
}
