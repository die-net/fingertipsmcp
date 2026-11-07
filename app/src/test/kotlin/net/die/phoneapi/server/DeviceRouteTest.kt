package net.die.phoneapi.server

import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import net.die.phoneapi.core.ApiJson
import net.die.phoneapi.model.DeviceInfo
import net.die.phoneapi.model.Scope
import net.die.phoneapi.model.ScreenOrientation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DeviceRouteTest {
    @Test
    fun `orientation reaches power`() = testApplication {
        val api = FakeApi()
        val control = api.tokens.issue("control", setOf(Scope.CONTROL))
        application { phoneApiModule(api.services) }
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
        application { phoneApiModule(api.services) }
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
