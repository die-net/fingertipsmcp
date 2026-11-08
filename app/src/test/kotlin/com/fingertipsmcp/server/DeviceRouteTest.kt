package com.fingertipsmcp.server

import com.fingertipsmcp.core.ApiJson
import com.fingertipsmcp.model.DeviceInfo
import com.fingertipsmcp.model.Scope
import com.fingertipsmcp.model.ScreenOrientation
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DeviceRouteTest {
    @Test
    fun `orientation reaches power`() = testApplication {
        val api = FakeApi()
        val control = api.tokens.issue("control", setOf(Scope.CONTROL))
        application { fingertipsModule(api.services) }
        apiClient().use { client ->
            val session = startSession(client, control)
            val response =
                client.post("/v1/device/orientation") {
                    bearer(control)
                    session(session)
                    contentType(ContentType.Application.Json)
                    setBody("""{"orientation":"90"}""")
                }
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(ScreenOrientation.R90, api.orientation?.orientation)
        }
    }

    @Test
    fun `other tokens' ids name no one`() = testApplication {
        val api = FakeApi()
        val alice = api.tokens.issue("alice", Scope.entries.toSet())
        val bob = api.tokens.issue("bob", Scope.entries.toSet())
        application { fingertipsModule(api.services) }
        apiClient().use { client ->
            val session = startSession(client, alice)
            val tap =
                client.post("/v1/input/tap") {
                    bearer(alice)
                    session(session)
                    contentType(ContentType.Application.Json)
                    setBody("""{"x":1,"y":2}""")
                }
            assertEquals(HttpStatusCode.OK, tap.status)
            suspend fun holder(secret: String): String? {
                val body =
                    client
                        .get("/v1/device") {
                            bearer(secret)
                            session(session)
                        }
                        .bodyAsText()
                return ApiJson.decodeFromString(DeviceInfo.serializer(), body).screenHolder
            }
            assertEquals("you", holder(alice))
            // The id is not a credential: with another token it names no session.
            assertEquals("script", holder(bob))
        }
    }
}
