package com.fingertipsmcp.server

import com.fingertipsmcp.core.ApiJson
import com.fingertipsmcp.model.ApiError
import com.fingertipsmcp.model.Scope
import com.fingertipsmcp.model.openapi.ApiMethod
import com.fingertipsmcp.model.openapi.fingertipsEndpoints
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.Application
import io.ktor.server.application.plugin
import io.ktor.server.routing.HttpHeaderRouteSelector
import io.ktor.server.routing.HttpMethodRouteSelector
import io.ktor.server.routing.RoutingNode
import io.ktor.server.routing.RoutingRoot
import io.ktor.server.routing.getAllRoutes
import io.ktor.server.routing.path
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RouteScopeTest {
    @Test
    fun `routes match openapi scopes`() = testApplication {
        val api = FakeApi()
        val holding =
            Scope.entries.associateWith { scope ->
                api.tokens.issue("has-$scope", setOf(scope))
            }
        lateinit var app: Application
        application {
            app = this
            fingertipsModule(api.services)
        }
        apiClient().use { client ->
            client.get("/v1/device")
            val routing = app.plugin(RoutingRoot) as RoutingNode
            val routes = routing.scopedRoutes()
            val declared = fingertipsEndpoints.map {
                RegisteredRoute(it.method, it.path, it.scopes)
            }
            assertEquals(declared.toSet(), routes.toSet())
            val unscoped =
                routing.getAllRoutes().filter { it.scopeSelector() == null }.map { it.routePath() }
            assertTrue(unscoped.all { it in UNSCOPED }, "routes without a scope: $unscoped")

            RawServer(api.services).use { raw ->
                routes.forEach { route ->
                    if (route.scopes.isEmpty()) {
                        // Any token may call it, even one with only the browser scope.
                        val anyone = request(client, raw, route, holding.getValue(Scope.BROWSER))
                        assertTrue(anyone.status != HttpStatusCode.Forbidden, "$route -> $anyone")
                        return@forEach
                    }
                    val others = Scope.entries.toSet() - route.scopes
                    val missing = api.tokens.issue("missing-$route", others)
                    val denied = request(client, raw, route, missing)
                    assertEquals(HttpStatusCode.Forbidden, denied.status, route.toString())
                    val error = ApiJson.decodeFromString(ApiError.serializer(), denied.body)
                    assertEquals("forbidden", error.error, route.toString())
                    // Each of the route's scopes is enough on its own.
                    route.scopes.forEach { scope ->
                        val allowed = request(client, raw, route, holding.getValue(scope))
                        assertTrue(
                            allowed.status != HttpStatusCode.Forbidden,
                            "$route with $scope -> ${allowed.status} ${allowed.body}",
                        )
                    }
                }
            }
        }
    }
}

/** MCP checks scopes per tool; it is not wrapped in [scoped]. */
private val UNSCOPED = setOf("/mcp")

private data class RegisteredRoute(
    val method: ApiMethod,
    val path: String,
    val scopes: Set<Scope>,
)

private data class CallResult(val status: HttpStatusCode, val body: String)

private suspend fun request(
    client: HttpClient,
    raw: RawServer,
    route: RegisteredRoute,
    secret: String,
): CallResult {
    val path =
        route.path.replace("{ref}", "e1").replace("{pkg}", "com.example").replace("{id}", "abc")
    if (route.method == ApiMethod.WEBSOCKET) {
        val reply = raw.upgrade(path, "Authorization: Bearer $secret")
        return CallResult(HttpStatusCode.fromValue(reply.status), reply.body)
    }
    val response =
        when (route.method) {
            ApiMethod.GET -> client.get(path) { bearer(secret) }
            ApiMethod.POST -> client.post(path) { json(secret) }
            ApiMethod.PUT -> client.put(path) { json(secret) }
            ApiMethod.PATCH -> client.patch(path) { json(secret) }
            ApiMethod.DELETE -> client.delete(path) { bearer(secret) }
            ApiMethod.WEBSOCKET -> error(route)
        }
    return CallResult(response.status, response.bodyAsText())
}

private fun HttpRequestBuilder.json(secret: String) {
    bearer(secret)
    contentType(ContentType.Application.Json)
    setBody("{}")
}

private fun RoutingNode.scopedRoutes(): List<RegisteredRoute> =
    getAllRoutes().mapNotNull { node ->
        val selector = node.scopeSelector() ?: return@mapNotNull null
        RegisteredRoute(node.routeMethod(), node.routePath(), selector.scopes)
    }

/** The [scoped] or [anyToken] block a route is in, or null for a route in neither. */
private fun RoutingNode.scopeSelector(): ScopeRouteSelector? =
    generateSequence(this) { it.parent }
        .mapNotNull { it.selector as? ScopeRouteSelector }
        .firstOrNull()

private fun RoutingNode.routeMethod(): ApiMethod {
    val selectors = generateSequence(this) { it.parent }.mapNotNull { it.selector }.toList()
    val websocket = selectors.any { selector ->
        selector is HttpHeaderRouteSelector &&
            selector.name.equals(HttpHeaders.Upgrade, ignoreCase = true) &&
            selector.value.equals("websocket", ignoreCase = true)
    }
    if (websocket) return ApiMethod.WEBSOCKET
    val method =
        selectors.filterIsInstance<HttpMethodRouteSelector>().firstOrNull()?.method
            ?: error("no method on $this")
    return when (method) {
        HttpMethod.Get -> ApiMethod.GET
        HttpMethod.Post -> ApiMethod.POST
        HttpMethod.Put -> ApiMethod.PUT
        HttpMethod.Patch -> ApiMethod.PATCH
        HttpMethod.Delete -> ApiMethod.DELETE
        else -> error(method.value)
    }
}

private fun RoutingNode.routePath(): String {
    val raw = path
    val slashed = if (raw.startsWith("/")) raw else "/$raw"
    return slashed.trimEnd('/').ifEmpty { "/" }
}
